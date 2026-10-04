package com.lightning.northstar.physics;

import com.lightning.northstar.block.tech.rocket_thruster.RocketThrusterBlockEntity;
import com.lightning.northstar.contraption.FuelType;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared propellant access across a vessel's assembled Create fluid tanks. */
public final class RocketFuelStore {

    private RocketFuelStore() {
    }

    /**
     * Drains the requested whole millibuckets only when the vessel contains enough
     * valid propellant in total. Multiblock tanks are considered once at their
     * controller, even when several plot chunks contain parts of the same tank.
     */
    public static boolean consume(ServerSubLevel subLevel, int amount) {
        if (amount <= 0) return true;

        Map<BlockPos, FluidTankBlockEntity> tanks = collectCreateTanks(subLevel);
        Map<BlockPos, RocketThrusterBlockEntity> thrusters = collectThrusters(subLevel);
        int available = tanks.values().stream().mapToInt(tank -> fuelAmount(tank.getTankInventory().getFluid())).sum()
                + thrusters.values().stream().mapToInt(thruster -> fuelAmount(thruster.getFuelTank().getFluid())).sum();
        if (available < amount) return false;

        int remaining = amount;
        for (FluidTankBlockEntity tank : tanks.values()) {
            FluidStack fluid = tank.getTankInventory().getFluid();
            if (!validFuel(fluid)) continue;
            int drained = Math.min(remaining, fluid.getAmount());
            tank.getTankInventory().drain(drained, IFluidHandler.FluidAction.EXECUTE);
            remaining -= drained;
            if (remaining == 0) return true;
        }
        for (RocketThrusterBlockEntity thruster : thrusters.values()) {
            FluidStack fluid = thruster.getFuelTank().getFluid();
            if (!validFuel(fluid)) continue;
            int drained = Math.min(remaining, fluid.getAmount());
            thruster.getFuelTank().drain(drained, IFluidHandler.FluidAction.EXECUTE);
            remaining -= drained;
            if (remaining == 0) return true;
        }
        return remaining == 0;
    }

    /** Fraction of installed fuel capacity currently holding supported propellant. */
    public static float fraction(ServerSubLevel subLevel) {
        long amount = 0;
        long capacity = 0;
        for (FluidTankBlockEntity tank : collectCreateTanks(subLevel).values()) {
            capacity += tank.getTankInventory().getCapacity();
            FluidStack fluid = tank.getTankInventory().getFluid();
            if (validFuel(fluid)) amount += fluid.getAmount();
        }
        for (RocketThrusterBlockEntity thruster : collectThrusters(subLevel).values()) {
            capacity += thruster.getFuelTank().getCapacity();
            amount += fuelAmount(thruster.getFuelTank().getFluid());
        }
        return capacity == 0 ? 0f : (float) amount / capacity;
    }

    private static Map<BlockPos, FluidTankBlockEntity> collectCreateTanks(ServerSubLevel subLevel) {
        Map<BlockPos, FluidTankBlockEntity> tanks = new LinkedHashMap<>();
        for (var holder : subLevel.getPlot().getLoadedChunks()) {
            for (BlockEntity blockEntity : holder.getChunk().getBlockEntities().values()) {
                if (!(blockEntity instanceof FluidTankBlockEntity tank)) continue;
                FluidTankBlockEntity controller = tank.getControllerBE();
                if (controller != null && controller.isController()) {
                    tanks.putIfAbsent(controller.getBlockPos().immutable(), controller);
                }
            }
        }
        return tanks;
    }

    private static Map<BlockPos, RocketThrusterBlockEntity> collectThrusters(ServerSubLevel subLevel) {
        Map<BlockPos, RocketThrusterBlockEntity> thrusters = new LinkedHashMap<>();
        for (var holder : subLevel.getPlot().getLoadedChunks()) {
            for (BlockEntity blockEntity : holder.getChunk().getBlockEntities().values()) {
                if (blockEntity instanceof RocketThrusterBlockEntity thruster) {
                    thrusters.putIfAbsent(thruster.getBlockPos().immutable(), thruster);
                }
            }
        }
        return thrusters;
    }

    private static int fuelAmount(FluidStack fluid) {
        return validFuel(fluid) ? fluid.getAmount() : 0;
    }

    private static boolean validFuel(FluidStack fluid) {
        if (fluid.isEmpty()) return false;
        FuelType fuel = FuelType.getFuelType(fluid.getFluid());
        return fuel != null && fuel.gjPerMb() > 0f;
    }
}
