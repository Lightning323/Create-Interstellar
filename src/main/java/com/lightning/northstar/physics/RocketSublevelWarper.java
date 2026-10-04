package com.lightning.northstar.physics;

import com.lightning.northstar.compat.sable.NorthstarSable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Copies a rocket's Sable plot between dimensions without routing motion through an entity. */
public final class RocketSublevelWarper {

    private RocketSublevelWarper() {
    }

    public static boolean warp(ServerSubLevel source, ServerLevel destination, BlockPos targetAnchor) {
        ServerLevel sourceLevel = source.getLevel();
        ServerSubLevelContainer sourceContainer = ServerSubLevelContainer.getContainer(sourceLevel);
        ServerSubLevelContainer destinationContainer = ServerSubLevelContainer.getContainer(destination);
        if (sourceContainer == null || destinationContainer == null || source.isRemoved()) return false;

        List<BlockSnapshot> blocks = captureBlocks(source);
        if (blocks.isEmpty()) return false;

        List<BlockPos> destinationBlocks = new ArrayList<>(blocks.size());
        BoundingBox3ic bounds = boundsAt(blocks, targetAnchor, destination, destinationBlocks);
        if (bounds == null || destinationBlocks.stream().anyMatch(pos -> !destination.isEmptyBlock(pos))) return false;

        RocketShipState state = RocketSublevelState.get(source);
        state.setThrottle(0f);
        state.clearAttitude();
        state.setPhase(com.lightning.northstar.contraption.rocket.LaunchStatus.DESCENDING);
        RocketSublevelState.save(source, state);

        CompoundTag userData = source.getUserDataTag() == null ? null : source.getUserDataTag().copy();
        Vector3d linearVelocity = new Vector3d();
        Vector3d angularVelocity = new Vector3d();
        RigidBodyHandle oldBody = NorthstarSable.handleFor(source);
        if (oldBody.isValid()) {
            oldBody.getLinearVelocity(linearVelocity);
            oldBody.getAngularVelocity(angularVelocity);
        }

        List<Passenger> passengers = capturePassengers(source);
        for (int i = 0; i < blocks.size(); i++) {
            BlockSnapshot block = blocks.get(i);
            BlockPos target = destinationBlocks.get(i);
            destination.setBlock(target, block.state(), 3);
            if (block.blockEntity() != null) {
                BlockEntity blockEntity = destination.getBlockEntity(target);
                if (blockEntity != null) {
                    CompoundTag tag = block.blockEntity().copy();
                    tag.putInt("x", target.getX());
                    tag.putInt("y", target.getY());
                    tag.putInt("z", target.getZ());
                    rebaseTankPosition(tag, "Controller", block.sourcePosition(), target);
                    rebaseTankPosition(tag, "LastKnownPos", block.sourcePosition(), target);
                    blockEntity.loadWithComponents(tag, destination.registryAccess());
                    blockEntity.setChanged();
                }
            }
        }

        ServerSubLevel copy = SubLevelAssemblyHelper.assembleBlocks(destination, targetAnchor,
                destinationBlocks, bounds);
        if (userData != null) copy.setUserDataTag(userData);
        if (source.getName() != null) copy.setName(source.getName());

        Quaterniond orientation = new Quaterniond(source.logicalPose().orientation());
        Vector3d localCom = new Vector3d(copy.getMassTracker().getCenterOfMass())
                .sub(copy.getPlot().getCenterBlock().getX(),
                        copy.getPlot().getCenterBlock().getY(),
                        copy.getPlot().getCenterBlock().getZ());
        orientation.transform(localCom);
        Vector3d targetCom = new Vector3d(targetAnchor.getX(), targetAnchor.getY(), targetAnchor.getZ()).add(localCom);
        copy.logicalPose().orientation().set(orientation);
        copy.logicalPose().position().set(targetCom);
        destinationContainer.physicsSystem().getPipeline().teleport(copy, targetCom, orientation);
        copy.updateLastPose();

        RigidBodyHandle newBody = NorthstarSable.handleFor(copy);
        if (newBody.isValid()) newBody.addLinearAndAngularVelocity(linearVelocity, angularVelocity);

        for (Passenger passenger : passengers) {
            SubLevelHelper.popEntityLocal(source, passenger.player());
            Vector3d local = new Vector3d(passenger.localPlotPosition())
                    .add(copy.getPlot().getCenterBlock().getX(),
                            copy.getPlot().getCenterBlock().getY(),
                            copy.getPlot().getCenterBlock().getZ());
            Vector3d world = copy.logicalPose().transformPosition(local);
            passenger.player().teleportTo(destination, world.x, world.y, world.z,
                    Set.of(), passenger.yRot(), passenger.xRot());
            SubLevelHelper.pushEntityLocal(copy, passenger.player());
        }

        RocketSublevelState.forget(source);
        NorthstarSable.forget(source);
        sourceContainer.removeSubLevel(source, SubLevelRemovalReason.REMOVED);
        RocketSublevelState.save(copy);
        return true;
    }

    private static List<BlockSnapshot> captureBlocks(ServerSubLevel subLevel) {
        List<BlockSnapshot> result = new ArrayList<>();
        ServerLevel level = subLevel.getLevel();
        BlockPos center = subLevel.getPlot().getCenterBlock().offset(0, level.dimensionType().minY(), 0);
        HolderLookup.Provider registries = level.registryAccess();

        for (PlotChunkHolder holder : subLevel.getPlot().getLoadedChunks()) {
            ChunkPos chunkPos = holder.getPos();
            LevelChunk chunk = holder.getChunk();
            for (int sectionIndex = 0; sectionIndex < chunk.getSectionsCount(); sectionIndex++) {
                LevelChunkSection section = chunk.getSection(sectionIndex);
                if (section.hasOnlyAir()) continue;
                int sectionY = level.getSectionYFromSectionIndex(sectionIndex);
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            BlockState state = section.getBlockState(x, y, z);
                            if (state.isAir()) continue;
                            BlockPos pos = new BlockPos(chunkPos.getMinBlockX() + x,
                                    (sectionY << 4) + y, chunkPos.getMinBlockZ() + z);
                            CompoundTag blockEntity = chunk.getBlockEntityNbtForSaving(pos, registries);
                            result.add(new BlockSnapshot(pos.immutable(), pos.subtract(center).immutable(), state,
                                    blockEntity == null ? null : blockEntity.copy()));
                        }
                    }
                }
            }
        }
        return result;
    }

    private static void rebaseTankPosition(CompoundTag tag, String key, BlockPos sourcePos, BlockPos targetPos) {
        if (!tag.contains(key, CompoundTag.TAG_COMPOUND)) return;
        BlockPos delta = targetPos.subtract(sourcePos);
        NbtUtils.readBlockPos(tag, key).ifPresent(pos -> tag.put(key, NbtUtils.writeBlockPos(pos.offset(delta))));
    }

    @Nullable
    private static BoundingBox3ic boundsAt(List<BlockSnapshot> blocks, BlockPos anchor,
                                           ServerLevel level, List<BlockPos> positions) {
        int minX = anchor.getX(), minY = anchor.getY(), minZ = anchor.getZ();
        int maxX = minX, maxY = minY, maxZ = minZ;
        for (BlockSnapshot block : blocks) {
            BlockPos pos = anchor.offset(block.offset());
            if (!level.isInWorldBounds(pos)) return null;
            positions.add(pos);
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        return new BoundingBox3i(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static List<Passenger> capturePassengers(ServerSubLevel subLevel) {
        List<Passenger> result = new ArrayList<>();
        BlockPos center = subLevel.getPlot().getCenterBlock();
        for (UUID playerId : subLevel.getTrackingPlayers()) {
            if (subLevel.getLevel().getPlayerByUUID(playerId) instanceof ServerPlayer player) {
                Vector3d local = new Vector3d(player.getX(), player.getY(), player.getZ());
                subLevel.logicalPose().transformPositionInverse(local);
                local.sub(center.getX(), center.getY(), center.getZ());
                result.add(new Passenger(player, local, player.getYRot(), player.getXRot()));
            }
        }
        return result;
    }

    private record BlockSnapshot(BlockPos sourcePosition, BlockPos offset, BlockState state,
                                 @Nullable CompoundTag blockEntity) {
    }

    private record Passenger(ServerPlayer player, Vector3d localPlotPosition, float yRot, float xRot) {
    }
}
