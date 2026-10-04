package com.lightning.northstar.block.tech.rocket_station;

import com.lightning.northstar.contraption.rocket.RocketContraption;
import com.lightning.northstar.contraption.rocket.RocketDestination;
import com.lightning.northstar.physics.RocketSublevelState;
import com.lightning.northstar.util.BetterSimpleContainer;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.IDisplayAssemblyExceptions;
import com.simibubi.create.foundation.advancement.AllAdvancements;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public class RocketStationBlockEntity extends SmartBlockEntity implements IDisplayAssemblyExceptions {

    public final SimpleContainer container = new BetterSimpleContainer(2);

    public AssemblyException lastException;
    public RocketDestination destination;
    public RocketContraption scannedAssembly;

    public RocketStationBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        registerAwardables(behaviours, AllAdvancements.CONTRAPTION_ACTORS);
    }

    @Override
    public void destroy() {
        super.destroy();

        Containers.dropContents(level, worldPosition, container);
    }

    public RocketContraption assembleContraption() {
        lastException = null;
        RocketContraption rocket;
        try {
            if (level.northstar$planet() == null) {
                throw new AssemblyException(Component.translatable("northstar.contraption.rocket.assembly.unsupported_dimension"));
            }

            rocket = RocketContraption.capture(getLevel(), getBlockPos());

            if (!rocket.hasControls) {
                throw new AssemblyException(Component.translatable("northstar.contraption.rocket.assembly.missing_controls"));
            }

            rocket.destination = destination;
        } catch (AssemblyException exception) {
            lastException = exception;
            rocket = null;
        }
        sendData();
        scannedAssembly = rocket;
        return rocket;
    }

    public void assemble() {
        RocketContraption rocket = assembleContraption();
        if (rocket == null) {
            return;
        }

        if (!(level instanceof ServerLevel serverLevel)) return;

        List<Player> passengers = serverLevel.getEntitiesOfClass(Player.class, rocket.bounds.toAABB().inflate(1));
        ServerSubLevel subLevel = SubLevelAssemblyHelper.assembleBlocks(
                serverLevel, worldPosition, rocket.blocks, rocket.bounds);
        RocketSublevelState.initialize(subLevel, destination,
                new RocketDestination(serverLevel.dimension().location(), worldPosition,
                        getBlockState().getValue(RocketStationBlock.FACING)));
        for (Player passenger : passengers) {
            if (!passenger.isRemoved()) {
                SubLevelHelper.pushEntityLocal(subLevel, passenger);
            }
        }

        AllSoundEvents.CONTRAPTION_ASSEMBLE.playOnServer(serverLevel, worldPosition);
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);

        AssemblyException.write(compound, registries, lastException);
        compound.put("Inventory", container.createTag(registries));
        if (destination != null) compound.put("Destination", destination.toTag());
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);

        lastException = AssemblyException.read(compound, registries);
        container.fromTag(compound.getList("Inventory", Tag.TAG_COMPOUND), registries);
        destination = RocketDestination.fromTag(compound.getCompound("Destination"));

        if (compound.contains("item", Tag.TAG_COMPOUND)) {
            container.setItem(0, ItemStack.parseOptional(registries, compound.getCompound("item")));
        }
    }

    @Override
    public AssemblyException getLastAssemblyException() {
        return lastException;
    }

}
