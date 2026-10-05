package com.lightning.northstar.physics;

import com.lightning.northstar.Northstar;
import com.lightning.northstar.accessor.NorthstarLevel;
import com.lightning.northstar.compat.sable.NorthstarSable;
import com.lightning.northstar.contraption.rocket.LaunchStatus;
import com.lightning.northstar.contraption.rocket.RocketDestination;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import net.minecraft.core.SectionPos;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Moves a rocket's Sable plot from one dimension to another without routing its motion
 * through an entity.
 *
 * <p>The handoff is transactional. Everything that can be checked cheaply &mdash; reading
 * the hull, finding an anchor the hull fits inside, confirming the destination is clear
 * &mdash; happens before a single block is mutated. The source plot is only destroyed once
 * the vessel has been reassembled, the crew re-seated and the momentum carried over, so a
 * transfer that cannot complete leaves the rocket exactly where it was rather than
 * deleting it.
 *
 * <p>Sable destroys {@code create:seat} entities when a plot is dropped
 * ({@code sable:destroy_with_sub_level}), so entities are copied across explicitly and
 * each rider is put back in their own seat.
 */
public final class RocketSublevelWarper {

    /** Why a transfer did not happen. Everything except {@link #SUCCESS} is retryable. */
    public enum Outcome {
        SUCCESS,
        SOURCE_GONE,
        NO_CONTAINER,
        SAME_DIMENSION,
        EMPTY_HULL,
        NO_DESTINATION_ROOM,
        DESTINATION_BLOCKED,
        FAILED;

        public boolean success() {
            return this == SUCCESS;
        }
    }

    /**
     * @param outcome what happened
     * @param vessel  the reassembled vessel, only set on success
     * @param detail  human readable reason, for the log
     */
    public record Result(Outcome outcome, @Nullable ServerSubLevel vessel, String detail) {

        public boolean success() {
            return outcome.success();
        }
    }

    private RocketSublevelWarper() {
    }

    /**
     * Copies a vessel into {@code destination} carrying its hull, its occupants and its
     * motion.
     *
     * <p>{@code requestedAnchor} is where the hull's plot centre is asked to end up. Its X
     * and Z are honoured exactly; its Y is nudged if the hull would otherwise land outside
     * the destination's build height, so a tall ship transfers instead of failing.
     */
    public static Result warp(ServerSubLevel source, ServerLevel destination, BlockPos requestedAnchor) {
        ServerLevel sourceLevel = source.getLevel();
        if (source.isRemoved()) return fail(Outcome.SOURCE_GONE, "source sub-level was already removed");
        if (destination == sourceLevel) return fail(Outcome.SAME_DIMENSION, "destination is the source dimension");

        ServerSubLevelContainer sourceContainer = ServerSubLevelContainer.getContainer(sourceLevel);
        ServerSubLevelContainer destinationContainer = ServerSubLevelContainer.getContainer(destination);
        if (sourceContainer == null) {
            return fail(Outcome.NO_CONTAINER, "no sub-level container for " + sourceLevel.dimension().location());
        }
        if (destinationContainer == null) {
            return fail(Outcome.NO_CONTAINER, "no sub-level container for " + destination.dimension().location());
        }

        Plan plan = plan(source, destination, destinationContainer, requestedAnchor);
        if (plan.failure() != null) return fail(plan.failure(), plan.detail());

        return commit(plan, source, sourceLevel, sourceContainer, destination, destinationContainer);
    }

    // -------------------------------------------------------------------- planning

    private record Plan(Outcome failure, String detail, List<BlockSnapshot> hull, List<BlockPos> targets,
                        BoundingBox3i hullBounds, BlockPos anchor, Vector3d sourcePlotCentre,
                        List<Rider> riders, List<Entity> cargo) {

        static Plan rejected(Outcome outcome, String detail) {
            return new Plan(outcome, detail, List.of(), List.of(), null, null, null, List.of(), List.of());
        }
    }

    /**
     * Works out everything the commit needs without mutating anything, so a transfer that
     * cannot succeed is rejected while the rocket is still intact.
     */
    private static Plan plan(ServerSubLevel source, ServerLevel destination,
                              ServerSubLevelContainer destinationContainer, BlockPos requestedAnchor) {
        BoundingBox3ic plotBounds = source.getPlot().getBoundingBox();
        if (plotBounds == null || plotBounds.volume() <= 0L) {
            return Plan.rejected(Outcome.EMPTY_HULL, "the vessel's plot reports no blocks");
        }

        List<BlockSnapshot> hull = captureHull(source, plotBounds);
        if (hull.isEmpty()) return Plan.rejected(Outcome.EMPTY_HULL, "the vessel's plot contains no non-air blocks");

        BlockPos sourceCenterBlock = source.getPlot().getCenterBlock();
        Vector3d sourceCenter = new Vector3d(sourceCenterBlock.getX(), sourceCenterBlock.getY(), sourceCenterBlock.getZ());

        BlockPos anchor = fitAnchor(requestedAnchor, hull, destination);
        if (anchor == null) {
            return Plan.rejected(Outcome.NO_DESTINATION_ROOM,
                    "the hull does not fit inside " + destination.dimension().location());
        }

        List<BlockPos> targets = new ArrayList<>(hull.size());
        BoundingBox3i bounds = new BoundingBox3i(anchor, anchor);
        for (BlockSnapshot block : hull) {
            BlockPos target = anchor.offset(block.offset());
            if (!destination.isInWorldBounds(target)) {
                return Plan.rejected(Outcome.NO_DESTINATION_ROOM, "the hull would reach "
                        + target.toShortString() + ", outside " + destination.dimension().location());
            }
            targets.add(target);
            bounds = bounds.expandTo(new BoundingBox3i(target, target));
        }

        // The hull is written into the destination as ordinary world blocks first and only
        // then pulled into a plot by SubLevelAssemblyHelper. That staging area must stay out
        // of the plotgrid: a chunk inside it belongs to a plot, and a client that is handed
        // such a chunk while no plot has been created for it yet rejects the whole region
        // with "Cannot add chunk ... in nonexistent sub-level plot", which is how a vessel
        // ends up invisible on the client while the server insists it is there.
        if (isInsidePlotGrid(destinationContainer, bounds)) {
            return Plan.rejected(Outcome.DESTINATION_BLOCKED,
                    "the hull would be staged inside the plotgrid of " + destination.dimension().location()
                            + " around " + bounds.minX() + "," + bounds.minY() + "," + bounds.minZ());
        }

        for (int i = 0; i < targets.size(); i++) {
            BlockPos target = targets.get(i);
            if (NorthstarSable.containing(destination, target) != null) {
                return Plan.rejected(Outcome.DESTINATION_BLOCKED,
                        "another vessel already occupies " + target.toShortString());
            }
            if (!destination.isEmptyBlock(target)) {
                return Plan.rejected(Outcome.DESTINATION_BLOCKED, "destination block "
                        + destination.getBlockState(target).getBlock() + " is in the way at " + target.toShortString());
            }
        }

        // Cargo is gathered before the crew, because a seat is the only reliable way to find
        // the pilot: Sable's tracking list is about who can see the plot, not who is in it.
        List<Entity> cargo = captureCargo(source);
        List<Rider> riders = captureRiders(source, sourceCenter, cargo);
        for (Rider rider : riders) {
            if (rider.seat() != null && !rider.seat().isRemoved() && !cargo.contains(rider.seat())) {
                cargo.add(rider.seat());
            }
        }

        return new Plan(null, "", hull, targets, bounds, anchor, sourceCenter, riders, cargo);
    }

    /**
     * Whether any corner of the given world-space bounds falls inside the destination's
     * sub-level plotgrid.
     *
     * <p>The plotgrid is a large reserved block of the world that Sable keeps for sub-level
     * plots. Staging a hull in it writes chunks that the client cannot interpret, because a
     * chunk inside the grid is expected to belong to a plot rather than to the terrain.
     */
    private static boolean isInsidePlotGrid(ServerSubLevelContainer container, BoundingBox3ic bounds) {
        int logPlotSize = container.getLogPlotSize();
        Vector2i origin = container.getOrigin();
        int sideLength = 1 << container.getLogSideLength();
        int plotBlocks = 1 << (logPlotSize + SectionPos.SECTION_BITS);

        // Grow the box by a plot's width so a hull that merely touches the grid counts too.
        int minPlotX = Math.floorDiv(bounds.minX() - plotBlocks, plotBlocks) - origin.x;
        int maxPlotX = Math.floorDiv(bounds.maxX() + plotBlocks, plotBlocks) - origin.x;
        int minPlotZ = Math.floorDiv(bounds.minZ() - plotBlocks, plotBlocks) - origin.y;
        int maxPlotZ = Math.floorDiv(bounds.maxZ() + plotBlocks, plotBlocks) - origin.y;

        return minPlotX < sideLength && maxPlotX >= 0 && minPlotZ < sideLength && maxPlotZ >= 0;
    }

    /**
     * Moves the anchor's Y until the whole hull sits inside the destination's build height.
     *
     * @return the adjusted anchor, or null if the hull cannot fit at any height
     */
    @Nullable
    private static BlockPos fitAnchor(BlockPos requestedAnchor, List<BlockSnapshot> hull, ServerLevel destination) {
        int offsetMinY = Integer.MAX_VALUE;
        int offsetMaxY = Integer.MIN_VALUE;
        for (BlockSnapshot block : hull) {
            int offsetY = block.offset().getY();
            offsetMinY = Math.min(offsetMinY, offsetY);
            offsetMaxY = Math.max(offsetMaxY, offsetY);
        }

        int destinationMin = destination.getMinBuildHeight();
        int destinationMax = destination.getMaxBuildHeight() - 1;
        if (offsetMaxY - offsetMinY + 1 > destinationMax - destinationMin + 1) return null;

        int anchorY = requestedAnchor.getY();
        int minY = anchorY + offsetMinY;
        int maxY = anchorY + offsetMaxY;
        if (minY < destinationMin) anchorY += destinationMin - minY;
        else if (maxY > destinationMax) anchorY -= maxY - destinationMax;

        return new BlockPos(requestedAnchor.getX(), anchorY, requestedAnchor.getZ());
    }

    /**
     * Reads every non-air block of the hull out of the plot.
     *
     * <p>Offsets are relative to the plot's own centre block, which is the same reference
     * Sable's assembly transform uses, so the hull keeps its shape across the transfer.
     */
    private static List<BlockSnapshot> captureHull(ServerSubLevel subLevel, BoundingBox3ic plotBounds) {
        List<BlockSnapshot> result = new ArrayList<>();
        ServerLevel level = subLevel.getLevel();
        BlockPos center = subLevel.getPlot().getCenterBlock();
        HolderLookup.Provider registries = level.registryAccess();

        for (PlotChunkHolder holder : subLevel.getPlot().getLoadedChunks()) {
            BoundingBox3ic chunkBounds = holder.getBoundingBox();
            if (chunkBounds == null || !chunkBounds.intersects(plotBounds)) continue;

            LevelChunk chunk = holder.getChunk();
            ChunkPos chunkPos = holder.getPos();

            for (int sectionIndex = 0; sectionIndex < chunk.getSectionsCount(); sectionIndex++) {
                LevelChunkSection section = chunk.getSection(sectionIndex);
                if (section.hasOnlyAir()) continue;

                int sectionMinY = level.getSectionYFromSectionIndex(sectionIndex) << 4;
                if (sectionMinY + 15 < chunkBounds.minY() || sectionMinY > chunkBounds.maxY()) continue;

                for (int x = 0; x < 16; x++) {
                    int worldX = chunkPos.getMinBlockX() + x;
                    if (worldX < chunkBounds.minX() || worldX > chunkBounds.maxX()) continue;
                    for (int y = 0; y < 16; y++) {
                        int worldY = sectionMinY + y;
                        if (worldY < chunkBounds.minY() || worldY > chunkBounds.maxY()) continue;
                        for (int z = 0; z < 16; z++) {
                            int worldZ = chunkPos.getMinBlockZ() + z;
                            if (worldZ < chunkBounds.minZ() || worldZ > chunkBounds.maxZ()) continue;

                            BlockState state = section.getBlockState(x, y, z);
                            if (state.isAir()) continue;

                            BlockPos pos = new BlockPos(worldX, worldY, worldZ);
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

    /**
     * Everyone riding the vessel, with their seat recorded so they can be put back in it.
     *
     * <p>Found two ways. Sable's tracking list catches a pilot walking around the hull, and
     * the seats caught in the plot catch a pilot who is actually strapped into one, which is
     * the normal case for a rocket and the one that must not be missed.
     */
    private static List<Rider> captureRiders(ServerSubLevel subLevel, Vector3d sourceCenter, List<Entity> cargo) {
        Set<UUID> seen = new HashSet<>();
        List<Rider> result = new ArrayList<>();

        for (UUID playerId : subLevel.getTrackingPlayers()) {
            if (subLevel.getLevel().getPlayerByUUID(playerId) instanceof ServerPlayer player) {
                addRider(result, seen, subLevel, sourceCenter, player);
            }
        }

        for (Entity entity : cargo) {
            for (Entity passenger : entity.getPassengers()) {
                if (passenger instanceof ServerPlayer player) {
                    addRider(result, seen, subLevel, sourceCenter, player);
                }
            }
        }

        return result;
    }

    private static void addRider(List<Rider> result, Set<UUID> seen, ServerSubLevel subLevel,
                                 Vector3d sourceCenter, ServerPlayer player) {
        if (player.isRemoved() || player.isSpectator() || !seen.add(player.getUUID())) return;
        Vector3d offset = plotLocal(subLevel, player.position()).sub(sourceCenter);
        result.add(new Rider(player, offset, player.getYRot(), player.getXRot(), player.getVehicle()));
    }

    /**
     * Everything that lives inside the plot. Seats matter most: without copying them the
     * crew would arrive in orbit with nothing to sit in, because Sable destroys
     * {@code create:seat} entities along with the plot they were in.
     */
    private static List<Entity> captureCargo(ServerSubLevel subLevel) {
        ServerLevel level = subLevel.getLevel();
        ChunkPos min = subLevel.getPlot().getChunkMin();
        ChunkPos max = subLevel.getPlot().getChunkMax();

        // Entities inside a plot are indexed under the plot's own chunks, so the plot's
        // chunk range is the box that holds them.
        AABB plot = new AABB(min.getMinBlockX(), level.getMinBuildHeight(), min.getMinBlockZ(),
                max.getMaxBlockX() + 1, level.getMaxBuildHeight(), max.getMaxBlockZ() + 1);

        List<Entity> result = new ArrayList<>();
        for (Entity entity : level.getEntitiesOfClass(Entity.class, plot)) {
            if (entity instanceof ServerPlayer || entity.isRemoved()) continue;
            if (NorthstarSable.containing(entity) != subLevel) continue;
            result.add(entity);
        }
        return result;
    }

    // ------------------------------------------------------------------ committing

    private static Result commit(Plan plan, ServerSubLevel source, ServerLevel sourceLevel,
                                 ServerSubLevelContainer sourceContainer, ServerLevel destination,
                                 ServerSubLevelContainer destinationContainer) {
        RocketShipState state = RocketSublevelState.get(source);
        CompoundTag userData = source.getUserDataTag() == null ? null : source.getUserDataTag().copy();
        String name = source.getName();

        Vector3d linearVelocity = new Vector3d();
        Vector3d angularVelocity = new Vector3d();
        RigidBodyHandle oldBody = NorthstarSable.handleFor(source);
        if (oldBody.isValid()) {
            oldBody.getLinearVelocity(linearVelocity);
            oldBody.getAngularVelocity(angularVelocity);
        }
        Quaterniond sourceOrientation = new Quaterniond(source.logicalPose().orientation());

        ServerSubLevel vessel = null;
        List<Rider> movedRiders = new ArrayList<>();
        List<Entity> movedCargo = new ArrayList<>();

        try {
            placeHull(plan, destination);
            vessel = assemble(plan, destination);

            if (userData != null) vessel.setUserDataTag(userData);
            if (name != null) vessel.setName(name);

            placeVessel(vessel, plan.anchor(), sourceOrientation);
            carryMotion(destinationContainer, vessel, linearVelocity, angularVelocity);

            Map<UUID, Entity> seatCopies = copyCargo(source, plan, vessel, movedCargo);
            movedRiders.addAll(carryRiders(source, plan, vessel, seatCopies));

            RocketSublevelState.forget(source);
            NorthstarSable.forget(source);
            sourceContainer.removeSubLevel(source, SubLevelRemovalReason.REMOVED);

            RocketSublevelState.save(vessel, arrivalState(state, sourceLevel));
            Northstar.LOGGER.info("Transferred vessel {} from {} to {} at {}",
                    vessel.getUniqueId(), sourceLevel.dimension().location(),
                    destination.dimension().location(), plan.anchor().toShortString());
            return new Result(Outcome.SUCCESS, vessel, "");
        } catch (Throwable t) {
            Northstar.LOGGER.error("Transfer into {} failed; rolling the rocket back", destination.dimension().location(), t);
            rollback(source, vessel, plan, movedRiders, movedCargo, destinationContainer, destination);
            return new Result(Outcome.FAILED, null, describe(t));
        }
    }

    private static void placeHull(Plan plan, ServerLevel destination) {
        List<BlockSnapshot> hull = plan.hull();
        List<BlockPos> targets = plan.targets();

        for (int i = 0; i < hull.size(); i++) {
            BlockSnapshot block = hull.get(i);
            BlockPos target = targets.get(i);
            destination.setBlock(target, block.state(), 3);

            if (block.blockEntity() == null) continue;
            if (!(destination.getBlockEntity(target) instanceof BlockEntity blockEntity)) continue;

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

    @Nullable
    private static ServerSubLevel assemble(Plan plan, ServerLevel destination) {
        ServerSubLevel vessel = SubLevelAssemblyHelper.assembleBlocks(destination, plan.anchor(),
                plan.targets(), plan.hullBounds());
        if (vessel == null || vessel.isRemoved()) {
            throw new IllegalStateException("assembly produced no vessel");
        }
        return vessel;
    }

    /**
     * Puts the reassembled vessel at the requested anchor, keeping the attitude it had on
     * the way out.
     *
     * <p>A pose's position is where its rotation point ended up in world space, and its
     * rotation point is the vessel's centre of mass in plot space. Rotating about the mass
     * centre keeps the hull where it was asked to be instead of swinging it around the
     * world origin.
     */
    private static void placeVessel(ServerSubLevel vessel, BlockPos anchor, Quaterniond sourceOrientation) {
        BlockPos centreBlock = vessel.getPlot().getCenterBlock();
        Vector3d plotCentre = new Vector3d(centreBlock.getX(), centreBlock.getY(), centreBlock.getZ());
        Vector3d centreOfMass = centreOfMass(vessel, plotCentre);

        Quaterniond orientation = new Quaterniond(sourceOrientation);
        Vector3d lever = new Vector3d(plotCentre).sub(centreOfMass);
        orientation.transform(lever);

        vessel.logicalPose().orientation().set(orientation);
        vessel.logicalPose().position().set(anchor.getX() - lever.x, anchor.getY() - lever.y, anchor.getZ() - lever.z);
        vessel.logicalPose().rotationPoint().set(centreOfMass);
        vessel.updateLastPose();
    }

    private static Vector3d centreOfMass(ServerSubLevel vessel, Vector3d fallback) {
        if (vessel.getMassTracker() == null) return fallback;
        Vector3dc com = vessel.getMassTracker().getCenterOfMass();
        return com == null ? fallback : new Vector3d(com);
    }

    /**
     * Hands the vessel's momentum over. A rigid body handle is not always live yet for a
     * plot created moments ago, so the physics pipeline is the fallback rather than
     * letting the ship arrive dead in the water.
     */
    private static void carryMotion(ServerSubLevelContainer destinationContainer, ServerSubLevel vessel,
                                    Vector3d linearVelocity, Vector3d angularVelocity) {
        RigidBodyHandle handle = NorthstarSable.handleFor(vessel);
        if (handle.isValid()) {
            handle.teleport(vessel.logicalPose().position(), vessel.logicalPose().orientation());
            handle.addLinearAndAngularVelocity(linearVelocity, angularVelocity);
            return;
        }
        Northstar.LOGGER.debug("Vessel {} has no live rigid body yet, applying motion through the pipeline",
                vessel.getUniqueId());
        destinationContainer.physicsSystem().getPipeline().teleport(vessel,
                vessel.logicalPose().position(), vessel.logicalPose().orientation());
        destinationContainer.physicsSystem().getPipeline()
                .addLinearAndAngularVelocity(vessel, linearVelocity, angularVelocity);
    }

    /**
     * Rebuilds the vessel's non-player entities inside the new plot, keyed by the original
     * entity so riders can find their seat again.
     *
     * @return the copies, keyed by the entity they came from
     */
    private static Map<UUID, Entity> copyCargo(ServerSubLevel source, Plan plan, ServerSubLevel vessel,
                                               List<Entity> movedCargo) {
        Map<UUID, Entity> copies = new Object2ObjectOpenHashMap<>();
        Vector3d destinationCenter = plotCentre(vessel);

        for (Entity entity : plan.cargo()) {
            if (entity.isRemoved()) continue;

            Entity copy;
            try {
                copy = entity.getType().create(vessel.getLevel());
            } catch (Throwable t) {
                Northstar.LOGGER.warn("Could not create a copy of entity type {} for the new vessel", entity.getType(), t);
                continue;
            }
            if (copy == null) continue;

            try {
                copy.restoreFrom(entity);
                copy.stopRiding();

                Vector3d world = placeInNewVessel(source, vessel, entity.position(),
                        plan.sourcePlotCentre(), destinationCenter);
                copy.moveTo(world.x, world.y, world.z, entity.getYRot(), entity.getXRot());
                SubLevelHelper.pushEntityLocal(vessel, copy);
                vessel.getLevel().addDuringTeleport(copy);

                copies.putIfAbsent(entity.getUUID(), copy);
                movedCargo.add(copy);
            } catch (Throwable t) {
                Northstar.LOGGER.error("Failed to carry entity {} into the new vessel", entity, t);
                copy.discard();
            }
        }
        return copies;
    }

    /**
     * Moves the crew across and puts each one back in their own seat.
     */
    private static List<Rider> carryRiders(ServerSubLevel source, Plan plan, ServerSubLevel vessel,
                                            Map<UUID, Entity> seatCopies) {
        List<Rider> moved = new ArrayList<>();
        Vector3d destinationCenter = plotCentre(vessel);

        for (Rider rider : plan.riders()) {
            ServerPlayer player = rider.player();
            if (player.isRemoved()) continue;

            SubLevelHelper.popEntityLocal(source, player);

            Vector3d world = vessel.logicalPose().transformPosition(
                    new Vector3d(rider.offsetFromPlotCentre()).add(destinationCenter));
            player.teleportTo(vessel.getLevel(), world.x, world.y, world.z, Set.of(), rider.yRot(), rider.xRot());
            SubLevelHelper.pushEntityLocal(vessel, player);
            moved.add(rider);

            Entity seat = rider.seat() == null ? null : seatCopies.get(rider.seat().getUUID());
            if (seat != null && !seat.isRemoved()) {
                player.startRiding(seat, true);
                Northstar.LOGGER.debug("Re-seated {} aboard vessel {}", player.getName().getString(), vessel.getUniqueId());
            } else if (rider.seat() != null) {
                Northstar.LOGGER.warn("No seat could be carried over for {}; they will stand in the vessel",
                        player.getName().getString());
            }
        }
        return moved;
    }

    /**
     * Where a point of the source hull ends up in the destination, given the world position
     * it used to have.
     *
     * <p>The hull keeps its place relative to the plot centre, so a point that was
     * {@code d} away from the source plot centre is {@code d} away from the destination plot
     * centre, whatever plot slot the destination grid handed out.
     */
    private static Vector3d placeInNewVessel(ServerSubLevel source, ServerSubLevel vessel, Vec3 globalPosition,
                                             Vector3d sourceCenter, Vector3d destinationCenter) {
        Vector3d local = plotLocal(source, globalPosition);
        return vessel.logicalPose().transformPosition(local.sub(sourceCenter).add(destinationCenter));
    }

    private static Vector3d plotLocal(ServerSubLevel subLevel, Vec3 globalPosition) {
        Vector3d local = new Vector3d(globalPosition.x, globalPosition.y, globalPosition.z);
        subLevel.logicalPose().transformPositionInverse(local);
        return local;
    }

    private static Vector3d plotCentre(ServerSubLevel subLevel) {
        BlockPos centre = subLevel.getPlot().getCenterBlock();
        return new Vector3d(centre.getX(), centre.getY(), centre.getZ());
    }

    /**
     * Puts the rocket's flight state into a form that makes sense on the far side: the
     * outbound leg is done, and the waypoint now names the dimension underneath so the
     * return descent can find its boundary.
     */
    private static RocketShipState arrivalState(RocketShipState state, ServerLevel sourceLevel) {
        RocketShipState arrived = new RocketShipState();
        arrived.setThrottle(0f);
        arrived.clearAttitude();
        arrived.setFuelRemainingGj(state.fuelRemainingGj());
        arrived.setArmed(false);
        arrived.setPhase(LaunchStatus.DESCENDING);
        arrived.setOrigin(new RocketDestination(sourceLevel.dimension().location(), null, null));

        var dimension = ((NorthstarLevel) sourceLevel).northstar$dimension();
        var below = dimension == null ? null : dimension.dimensionBelow();
        arrived.setDestination(below == null ? null : new RocketDestination(below.location(), null, null));
        return arrived;
    }

    private static void rollback(ServerSubLevel source, @Nullable ServerSubLevel vessel, Plan plan, List<Rider> movedRiders,
                                 List<Entity> movedCargo, ServerSubLevelContainer destinationContainer,
                                 ServerLevel destination) {
        for (Entity copy : movedCargo) {
            if (!copy.isRemoved()) copy.discard();
        }

        if (vessel == null) {
            for (BlockPos target : plan.targets()) {
                if (!destination.isInWorldBounds(target)) continue;
                destination.setBlock(target, Blocks.AIR.defaultBlockState(), 3);
            }
            return;
        }

        // The source plot is still standing, so anyone who was already moved goes home
        // rather than being left floating in the dimension we failed to reach.
        for (Rider rider : movedRiders) {
            ServerPlayer player = rider.player();
            if (player.isRemoved() || player.level() != destination) continue;
            player.stopRiding();
            Vector3d world = source.logicalPose().transformPosition(
                    new Vector3d(rider.offsetFromPlotCentre()).add(plan.sourcePlotCentre()));
            player.teleportTo(source.getLevel(), world.x, world.y, world.z, Set.of(), rider.yRot(), rider.xRot());
            SubLevelHelper.pushEntityLocal(source, player);
        }

        try {
            NorthstarSable.forget(vessel);
            destinationContainer.removeSubLevel(vessel, SubLevelRemovalReason.REMOVED);
        } catch (Throwable t) {
            Northstar.LOGGER.error("Failed to remove the partially assembled vessel {}", vessel.getUniqueId(), t);
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    private static void rebaseTankPosition(CompoundTag tag, String key, BlockPos sourcePos, BlockPos targetPos) {
        if (!tag.contains(key, CompoundTag.TAG_COMPOUND)) return;
        BlockPos delta = targetPos.subtract(sourcePos);
        NbtUtils.readBlockPos(tag, key).ifPresent(pos -> tag.put(key, NbtUtils.writeBlockPos(pos.offset(delta))));
    }

    private static Result fail(Outcome outcome, String detail) {
        return new Result(outcome, null, detail);
    }

    private record BlockSnapshot(BlockPos sourcePosition, BlockPos offset, BlockState state,
                                 @Nullable CompoundTag blockEntity) {
    }

    /**
     * @param seat the entity the rider was on, matched to its copy by UUID so the rider can
     *             be re-seated in the new dimension
     */
    private record Rider(ServerPlayer player, Vector3d offsetFromPlotCentre, float yRot, float xRot,
                         @Nullable Entity seat) {
    }
}