package com.lightning.northstar.contraption.rocket;

import com.lightning.northstar.block.simple.InterplanetaryNavigatorBlock;
import com.lightning.northstar.block.tech.auto_lander.AutoLanderBlock;
import com.lightning.northstar.block.tech.computer_rack.TargetingComputerRackBlockEntity;
import com.lightning.northstar.block.tech.rocket_controls.RocketControlsBlock;
import com.lightning.northstar.block.tech.rocket_station.RocketStationBlock;
import com.lightning.northstar.block.tech.rocket_thruster.RocketThrusterBlock;
import com.lightning.northstar.block.tech.rocket_thruster.RocketThrusterBlockEntity;
import com.lightning.northstar.content.NorthstarTags.NorthstarBlockTags;
import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/** Block and component inventory gathered immediately before sublevel assembly. */
public final class RocketContraption {

    private static final int MAXIMUM_BLOCKS = 32_768;

    public final Set<BlockPos> blocks;
    public final BoundingBox3ic bounds;
    public final List<BlockPos> thrusterPositions;
    public final List<BlockPos> seatPositions;
    public final List<BlockPos> fuelTankPositions;
    public final boolean hasControls;
    public final boolean hasAutoLander;
    public final boolean hasInterplanetaryNavigator;
    public final int computerCount;
    public final double massWeight;
    public final double heatShielding;
    public RocketDestination destination;

    private RocketContraption(Set<BlockPos> blocks, BoundingBox3ic bounds,
                              List<BlockPos> thrusters, List<BlockPos> seats, List<BlockPos> fuelTanks,
                              boolean controls, boolean autoLander, boolean navigator,
                              int computers, double massWeight, double heatShielding) {
        this.blocks = Set.copyOf(blocks);
        this.bounds = bounds;
        this.thrusterPositions = List.copyOf(thrusters);
        this.seatPositions = List.copyOf(seats);
        this.fuelTankPositions = List.copyOf(fuelTanks);
        this.hasControls = controls;
        this.hasAutoLander = autoLander;
        this.hasInterplanetaryNavigator = navigator;
        this.computerCount = computers;
        this.massWeight = massWeight;
        this.heatShielding = heatShielding;
    }

    public static RocketContraption capture(Level level, BlockPos stationPos) throws AssemblyException {
        Set<BlockPos> scanned = new LinkedHashSet<>();
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> frontier = new ArrayDeque<>();
        frontier.add(stationPos.immutable());
        int minX = stationPos.getX(), minY = stationPos.getY(), minZ = stationPos.getZ();
        int maxX = minX, maxY = minY, maxZ = minZ;

        while (!frontier.isEmpty()) {
            BlockPos pos = frontier.remove();
            if (!visited.add(pos) || level.getBlockState(pos).isAir()) continue;
            if (scanned.size() > MAXIMUM_BLOCKS) {
                throw new AssemblyException(Component.translatable("northstar.contraption.rocket.assembly.too_many_blocks"));
            }
            scanned.add(pos);
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (!visited.contains(next) && !level.getBlockState(next).isAir()) frontier.add(next);
            }
        }

        Set<BlockPos> blocks = scanned;
        blocks.remove(stationPos);
        if (blocks.isEmpty()) {
            throw new AssemblyException(Component.translatable("northstar.contraption.rocket.assembly.no_blocks"));
        }

        List<BlockPos> thrusters = new ArrayList<>();
        List<BlockPos> seats = new ArrayList<>();
        List<BlockPos> fuelTanks = new ArrayList<>();
        boolean controls = false;
        boolean autoLander = false;
        boolean navigator = false;
        int computers = 0;
        double massWeight = 0d;
        double heatShielding = 0d;

        for (BlockPos pos : blocks) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof RocketThrusterBlock) {
                thrusters.add(pos.immutable());
                fuelTanks.add(pos.immutable());
            }
            if (state.getBlock() instanceof SeatBlock) {
                seats.add(pos.immutable());
            }
            if (level.getBlockEntity(pos) instanceof FluidTankBlockEntity) {
                fuelTanks.add(pos.immutable());
            }
            controls |= state.getBlock() instanceof RocketControlsBlock;
            autoLander |= state.getBlock() instanceof AutoLanderBlock;
            navigator |= state.getBlock() instanceof InterplanetaryNavigatorBlock;
            if (level.getBlockEntity(pos) instanceof TargetingComputerRackBlockEntity rack) {
                computers += rack.getComputerCount();
            }

            double blockMass = NorthstarBlockTags.SUPER_HEAVY_BLOCKS.matches(state) ? 10d
                    : NorthstarBlockTags.HEAVY_BLOCKS.matches(state) ? 5d : 1d;
            massWeight += blockMass;
            if (NorthstarBlockTags.TIER_1_HEAT_RESISTANCE.matches(state)) heatShielding += 3d;
            if (NorthstarBlockTags.TIER_2_HEAT_RESISTANCE.matches(state)) heatShielding += 8d;
            if (NorthstarBlockTags.TIER_3_HEAT_RESISTANCE.matches(state)) heatShielding += 20d;
        }

        return new RocketContraption(blocks, new BoundingBox3i(minX, minY, minZ, maxX, maxY, maxZ), thrusters, seats, fuelTanks,
                controls, autoLander, navigator, computers, massWeight, heatShielding);
    }
}
