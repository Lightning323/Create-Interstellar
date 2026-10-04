package com.lightning.northstar.block.tech.rocket_thruster;

import com.lightning.northstar.contraption.FuelType;
import com.lightning.northstar.particle.NorthstarParticles;
import com.lightning.northstar.physics.RocketPropulsion;
import com.lightning.northstar.physics.RocketFuelStore;
import com.lightning.northstar.physics.RocketSublevelState;
import com.lightning.northstar.contraption.rocket.packet.RocketSyncPacket;
import com.lightning.northstar.config.NorthstarConfigs;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.fluid.SmartFluidTank;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import dev.ryanhcode.sable.api.physics.force.ForceTotal;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.joml.Vector3d;
import org.joml.Quaterniond;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;

/**
 * A rocket thruster that participates in Sable's rigid-body physics.
 *
 * <p>When the containing structure is a Sable sublevel this block entity is
 * ticked through {@link BlockEntitySubLevelActor#sable$physicsTick} and pushes
 * its thrust into the vessel's rigid body at the nozzle position. Because the
 * impulse is applied at a point, Sable derives the torque itself, so off-axis
 * thruster placement steers and rotates the ship rather than merely accelerating
 * it.
 *
 * <p>Propellant is drawn from this block entity's own tank. Each thruster pays
 * for its own thrust, which keeps fuel accounting local and avoids a second pass
 * over the vessel's tanks.
 */
@ParametersAreNonnullByDefault
public class RocketThrusterBlockEntity extends SmartBlockEntity implements BlockEntitySubLevelActor {

    private static final int TANK_CAPACITY = 8000;

    /**
     * Throttle commanded by the vessel's controls, 0..1. Flight code writes
     * this, the physics tick reads it.
     */
    private float throttle;
    private float fractionalPropellantMb;

    private final SmartFluidTank fuel = new SmartFluidTank(TANK_CAPACITY, stack -> setChanged());

    {
        fuel.setValidator(stack -> FuelType.getFuelType(stack.getFluid()) != null);
    }

    public RocketThrusterBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    public SmartFluidTank getFuelTank() {
        return fuel;
    }

    public float getThrottle() {
        return throttle;
    }

    public void setThrottle(float throttle) {
        this.throttle = Math.max(0f, Math.min(1f, throttle));
        setChanged();
    }

    public boolean isFiring() {
        return throttle > 0f;
    }

    /**
     * The face the thruster is mounted on. The exhaust points along it, so the
     * thrust pushes the vessel the other way.
     */
    public Direction getFacing() {
        return getBlockState().getValue(RocketThrusterBlock.FACING);
    }

    public int getTier() {
        return getBlockState().getValue(RocketThrusterBlock.TIER);
    }

    /**
     * Propellant energy available to this thruster, in GJ, using the specific
     * energy of whichever fuel is loaded.
     */
    public double availableEnergy() {
        FluidStack stack = fuel.getFluid();
        if (stack.isEmpty()) {
            return 0d;
        }
        FuelType fuelType = FuelType.getFuelType(stack.getFluid());
        if (fuelType == null || fuelType.gjPerMb() <= 0f) {
            return 0d;
        }
        return (double) fuelType.gjPerMb() * stack.getAmount();
    }

    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle body, double deltaTime) {
        throttle = RocketSublevelState.get(subLevel).throttle();
        if (!isFiring() || body == null || !body.isValid() || deltaTime <= 0d) {
            return;
        }

        if (fuel.getFluid().isEmpty()) {
            RocketSublevelState.get(subLevel).setThrottle(0f);
            RocketSublevelState.save(subLevel);
            fractionalPropellantMb = 0f;
            return;
        }

        if (subLevel.getMassTracker() == null || subLevel.getMassTracker().getMass() <= 0d) {
            // Without a mass tracker there is nothing to push against, so burning
            // propellant would be theft. Cut the throttle so the failure is visible.
            RocketSublevelState.get(subLevel).setThrottle(0f);
            RocketSublevelState.save(subLevel);
            return;
        }

        ForceTotal total = new ForceTotal();
        RocketPropulsion.Burn burn = RocketPropulsion.resolve(
                body, subLevel.getMassTracker(), List.of(currentThruster()),
                new org.joml.Quaterniond(), deltaTime, 0d, forceForTier(),
                NorthstarConfigs.server().propellantSpecificImpulse.getF(), total);
        if (!burn.isFiring()) return;

        float requestedMb = fractionalPropellantMb + burn.propellantMb();
        int wholeMb = (int) requestedMb;
        if (!tryConsume(wholeMb, subLevel)) {
            RocketSublevelState.get(subLevel).setThrottle(0f);
            RocketSublevelState.save(subLevel);
            fractionalPropellantMb = 0f;
            return;
        }
        fractionalPropellantMb = requestedMb - wholeMb;

        var group = subLevel.getOrCreateQueuedForceGroup(ForceGroups.PROPULSION.get());
        group.getForceTotal().applyForceTotal(total);
        group.recordPointForce(new Vector3d(nozzlePosition().x, nozzlePosition().y, nozzlePosition().z),
                new Vector3d(burn.linearImpulse()));
    }

    private double forceForTier() {
        return switch (getTier()) {
            case 2 -> NorthstarConfigs.server().thrusterTier2Force.getF();
            case 3 -> NorthstarConfigs.server().thrusterTier3Force.getF();
            default -> NorthstarConfigs.server().thrusterTier1Force.getF();
        };
    }

    /**
     * This thruster's contribution for the current tick, with its nozzle in the
     * sublevel's local frame.
     */
    private RocketPropulsion.Thruster currentThruster() {
        return new RocketPropulsion.Thruster(
                exhaustAxis(),
                new Vector3d(nozzlePosition().x, nozzlePosition().y, nozzlePosition().z),
                throttle,
                0f);
    }

    /**
     * Exhaust direction in local vessel space, pointing out of the nozzle.
     */
    private Vector3d exhaustAxis() {
        Direction facing = getFacing();
        return new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ());
    }

    /**
     * Nozzle offset from the block centre, half a block along the mounting face.
     */
    private Vec3 nozzleOffset() {
        return RocketPropulsion.mountPointFor(getFacing());
    }

    /**
     * Absolute nozzle position in the sublevel's coordinate frame, which is the
     * frame {@link RigidBodyHandle#applyImpulseAtPoint} expects.
     */
    private Vec3 nozzlePosition() {
        return Vec3.atCenterOf(getBlockPos()).add(nozzleOffset());
    }

    /**
     * Attempts to draw the requested propellant, returning false and leaving
     * the tank untouched when it cannot be satisfied in full.
     *
     * @param propellantMb propellant required, mB
     */
    private boolean tryConsume(int propellantMb, ServerSubLevel subLevel) {
        if (propellantMb <= 0) {
            return true;
        }

        FluidStack stack = fuel.getFluid();
        FuelType fuelType = stack.isEmpty() ? null : FuelType.getFuelType(stack.getFluid());
        if (fuelType != null && fuelType.gjPerMb() > 0f && stack.getAmount() >= propellantMb) {
            return fuel.drain(propellantMb, IFluidHandler.FluidAction.EXECUTE).getAmount() >= propellantMb;
        }

        // Fluid tank blocks are part of the assembled vessel too. Drain their
        // controller inventories so installed Create tanks remain the main fuel
        // store, while retaining the thruster's own tank as a compact fallback.
        return RocketFuelStore.consume(subLevel, propellantMb);
    }

    @Override
    public void tick() {
        super.tick();
        if (level != null && level.isClientSide && clientIsFiring()) {
            spawnPlume();
        }
    }

    private boolean clientIsFiring() {
        if (level == null || !level.isClientSide) return false;
        SubLevelAccess subLevel = SableCompanion.INSTANCE.getContaining(level, getBlockPos());
        if (subLevel == null) return false;
        RocketSyncPacket snapshot = RocketSyncPacket.latest(subLevel.getUniqueId());
        return snapshot != null && snapshot.throttle() > 0f;
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putFloat("Throttle", throttle);
        tag.putFloat("FractionalPropellant", fractionalPropellantMb);
        tag.put("Fuel", fuel.writeToNBT(registries, new CompoundTag()));
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        throttle = tag.getFloat("Throttle");
        fractionalPropellantMb = tag.getFloat("FractionalPropellant");
        if (tag.contains("Fuel")) {
            fuel.readFromNBT(registries, tag.getCompound("Fuel"));
        }
    }

    /**
     * Spawns the exhaust plume for this thruster. Client side only.
     */
    public void spawnPlume() {
        if (level == null || !level.isClientSide) {
            return;
        }
        Vec3 localNozzle = nozzlePosition();
        Vector3d nozzle = new Vector3d(localNozzle.x, localNozzle.y, localNozzle.z);
        Vector3d velocity = new Vector3d(exhaustAxis()).mul(0.18d);
        SubLevelAccess subLevel = SableCompanion.INSTANCE.getContaining(level, getBlockPos());
        if (subLevel != null) {
            subLevel.logicalPose().transformPosition(nozzle);
            new Quaterniond(subLevel.logicalPose().orientation()).transform(velocity);
        }
        level.addAlwaysVisibleParticle(NorthstarParticles.ROCKET_PLUME.get(), true,
                nozzle.x, nozzle.y, nozzle.z, velocity.x, velocity.y, velocity.z);
    }

    /**
     * Spawns the cold air puff shown while the thruster is spooling up.
     * Client side only.
     */
    public void spawnIdlePuff() {
        if (level == null || !level.isClientSide) {
            return;
        }
        Vec3 nozzle = nozzlePosition();
        level.addAlwaysVisibleParticle(NorthstarParticles.COLD_AIR.get(), true,
                nozzle.x, nozzle.y, nozzle.z, 0, 0, 0);
    }
}
