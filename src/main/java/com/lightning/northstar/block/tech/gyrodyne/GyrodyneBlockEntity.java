package com.lightning.northstar.block.tech.gyrodyne;

import com.lightning.northstar.compat.sable.NorthstarSable;
import com.lightning.northstar.config.NorthstarConfigs;
import com.lightning.northstar.physics.GyrodyneControl;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;

/**
 * A vessel-mounted reaction wheel.
 *
 * <p>Cosmonautics' gyrodyne was the only attitude authority in that mod, and it is
 * the piece Northstar was missing: {@link com.lightning.northstar.block.tech.rocket_thruster.RocketThrusterBlockEntity}
 * can only accelerate a hull, so nothing could stop it tumbling or point it at
 * anything.
 *
 * <p>Inside a Sable sublevel this is ticked through
 * {@link BlockEntitySubLevelActor#sable$physicsTick}. Unlike a thruster, which
 * pushes at a point and lets Sable derive the torque, a gyrodyne is a pure couple:
 * it torques the body without translating it, so it applies an angular impulse
 * about the centre of mass and nothing else. All of the control law lives in
 * {@link GyrodyneControl}; this class only gathers readings, resolves the
 * direction the current mode is asking for, and hands over the impulse.
 *
 * <p>Torque is applied about the vessel's centre of mass and never produces linear
 * motion, which is what makes several gyrodyne blocks on the same hull sum cleanly
 * instead of fighting each other.
 */
@ParametersAreNonnullByDefault
public class GyrodyneBlockEntity extends SmartBlockEntity
        implements BlockEntitySubLevelActor, IHaveGoggleInformation {

    /**
     * Speed below which a vessel has no meaningful prograde or normal direction, in
     * blocks/tick. Without this the direction modes would spin the wheel chasing
     * floating point noise while a craft sits on the pad.
     */
    private static final double DIRECTION_EPSILON_LENGTH_SQ = 0.04d;

    /**
     * How far the gimbal tilt has to move, in degrees, before the block entity is
     * rebroadcast to clients.
     */
    private static final double TILT_SYNC_THRESHOLD = 1d;

    public final ScrollValueBehaviour modeSelector;

    /**
     * Attitude captured when {@link GyrodyneMode#HOLD} was selected. Null whenever
     * the mode is anything else, so re-entering HOLD re-captures.
     */
    @Nullable
    private Quaterniond holdOrientation;

    private double tiltXDegrees;
    private double tiltZDegrees;

    public GyrodyneBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        modeSelector = new ScrollValueBehaviour(
                Component.translatable("northstar.gui.gyrodyne.mode"),
                this,
                new CenteredSideValueBoxTransform(
                        // The value box sits on the sides that are not the mounting face,
                        // so it never ends up buried inside whatever the wheel is bolted to.
                        (s, dir) -> dir != getFacing() && dir != getFacing().getOpposite()));
        modeSelector.between(0, GyrodyneMode.values().length - 1);
        modeSelector.withFormatter(index -> GyrodyneMode.byIndex(index).getComponent().getString());
        modeSelector.setValue(GyrodyneMode.OFF.ordinal());
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        behaviours.add(modeSelector);
    }

    public GyrodyneMode getMode() {
        return GyrodyneMode.byIndex(Math.round(modeSelector.getValue()));
    }

    public void setMode(GyrodyneMode mode) {
        if (getMode() == mode) {
            return;
        }
        modeSelector.setValue(mode.ordinal());
        // Dropping the snapshot is what makes HOLD re-capture: selecting HOLD records
        // the attitude on the tick it starts, and switching away then back re-levels.
        holdOrientation = null;
        notifyUpdate();
    }

    /**
     * The mounting face, which fixes the gyrodyne's local frame and therefore which
     * way the direction modes aim.
     */
    public Direction getFacing() {
        return getBlockState().hasProperty(GyrodyneBlock.FACING)
                ? getBlockState().getValue(GyrodyneBlock.FACING)
                : Direction.UP;
    }

    /**
     * Whether the wheel should be applying torque.
     *
     * <p>Redstone <em>disengages</em> it. That inversion is deliberate: a reaction
     * wheel needs a way to be cut mid-flight, and a redstone signal reads as an
     * emergency stop rather than as permission to spin up.
     */
    public boolean isActive() {
        BlockState state = getBlockState();
        if (state.hasProperty(GyrodyneBlock.POWERED) && state.getValue(GyrodyneBlock.POWERED)) {
            return false;
        }
        return getMode() != GyrodyneMode.OFF;
    }

    /**
     * Commanded gimbal tilt about the mounting frame's local X, in degrees. Purely a
     * readout of how hard the wheel is working; it is the seam a thrust-vectoring
     * renderer will read, and it is synced so the client does not have to guess.
     */
    public double getTiltXDegrees() {
        return tiltXDegrees;
    }

    public double getTiltZDegrees() {
        return tiltZDegrees;
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide) {
            return;
        }
        if (getMode() != GyrodyneMode.HOLD) {
            holdOrientation = null;
            tiltXDegrees = 0d;
            tiltZDegrees = 0d;
        }
    }

    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle body, double deltaTime) {
        GyrodyneMode mode = getMode();
        GyrodyneControl.Response response = GyrodyneControl.Response.IDLE;

        if (isActive() && deltaTime > 0d) {
            // One snapshot of pose and mass serves every read below, rather than each
            // call reaching into the sublevel again.
            NorthstarSable.VesselFrame frame = NorthstarSable.frame(subLevel, body);
            if (frame.isUsable()) {
                response = solve(frame, mode, deltaTime);
                if (response.isFiring()) {
                    frame.body().applyAngularImpulse(response.angularImpulseWorld());
                }
            }
        }

        if (mode != GyrodyneMode.HOLD) {
            holdOrientation = null;
        }

        double tiltX = response.tiltXDegrees();
        double tiltZ = response.tiltZDegrees();
        // The tilt only moves the readout, so sync on a threshold rather than every
        // physics tick; a wheel straining at full torque would otherwise rebroadcast
        // its block entity 60 times a second for every gyrodyne on the hull.
        if (Math.abs(tiltX - tiltXDegrees) > TILT_SYNC_THRESHOLD
                || Math.abs(tiltZ - tiltZDegrees) > TILT_SYNC_THRESHOLD) {
            tiltXDegrees = tiltX;
            tiltZDegrees = tiltZ;
            if (level != null && !level.isClientSide) {
                notifyUpdate();
            }
        }
    }

    /**
     * Runs the control law for the active mode.
     */
    private GyrodyneControl.Response solve(NorthstarSable.VesselFrame frame,
                                           GyrodyneMode mode, double deltaTime) {
        GyrodyneControl.Attitude attitude = new GyrodyneControl.Attitude(
                frame.orientation(),
                GyrodyneControl.mountOrientation(getFacing()),
                frame.body().getAngularVelocity());

        GyrodyneControl.Gains gains = new GyrodyneControl.Gains(
                NorthstarConfigs.server().gyrodyneProportionalGain.get(),
                NorthstarConfigs.server().gyrodyneDampingGain.get());
        double capacity = GyrodyneControl.torqueCapacity(
                NorthstarConfigs.server().gyrodyneTorque.get(), frame.massKg());

        return switch (mode) {
            case SAS -> GyrodyneControl.dampRotation(attitude, deltaTime, gains, capacity);
            case HOLD -> holdResponse(attitude, deltaTime, gains, capacity);
            default -> GyrodyneControl.alignToOrIdle(attitude,
                    targetDirection(frame, mode),
                    deltaTime, gains, capacity);
        };
    }

    /**
     * HOLD snapshots the vessel's orientation the first tick it runs and then drives
     * back to it, so selecting the mode never yanks the ship.
     */
    private GyrodyneControl.Response holdResponse(GyrodyneControl.Attitude attitude, double deltaTime,
                                                  GyrodyneControl.Gains gains, double capacity) {
        if (holdOrientation == null) {
            holdOrientation = new Quaterniond(attitude.vesselOrientation());
        }
        return GyrodyneControl.holdOrientation(attitude, holdOrientation, deltaTime, gains, capacity);
    }

    /**
     * The world-space direction a mode is asking the mounting axis to point at, or
     * null when that direction is undefined. A null target makes the mode idle rather
     * than guess, so a stationary vessel does not have its wheel chase noise.
     */
    @Nullable
    private Vector3dc targetDirection(NorthstarSable.VesselFrame frame, GyrodyneMode mode) {
        Vector3d velocity = velocity(frame);
        Vector3d up = new Vector3d(0, 1, 0);
        boolean moving = velocity.lengthSquared() > DIRECTION_EPSILON_LENGTH_SQ;

        return switch (mode) {
            case PROGRADE -> moving ? velocity.normalize() : null;
            case RETROGRADE -> moving ? velocity.normalize().negate() : null;
            case NORMAL -> moving ? perpendicularTo(velocity, up) : null;
            case ANTINORMAL -> {
                Vector3d normal = moving ? perpendicularTo(velocity, up) : null;
                yield normal == null ? null : normal.negate();
            }
            case RADIAL_IN -> new Vector3d(0, -1, 0);
            case RADIAL_OUT -> up;
            case HORIZON -> horizonDirection(velocity, frame.orientation(), moving);
            case SUN -> sunDirection();
            default -> null;
        };
    }

    /**
     * Unit vector perpendicular to both {@code a} and {@code b}, falling back to an
     * axis-aligned direction when the two are parallel and no perpendicular exists.
     */
    @Nullable
    private static Vector3d perpendicularTo(Vector3d a, Vector3d b) {
        // new Vector3d(a).cross(b), not new Vector3d().cross(a, b): the two argument
        // JOML overload writes this x arg into the destination instead of replacing the
        // receiver, so it would hand back b unchanged and every mode would be wrong.
        Vector3d result = new Vector3d(a).cross(b);
        if (result.lengthSquared() < 1e-8d) {
            // Velocity is vertical, so "normal" is undefined. Fall back to a fixed
            // horizontal axis rather than spinning the wheel.
            return new Vector3d(Math.abs(a.x()) < 0.9d ? 1d : 0d, 0d,
                    Math.abs(a.x()) < 0.9d ? 0d : 1d);
        }
        return result.normalize();
    }

    /**
     * Flattens the vessel's velocity, or failing that its mounting axis, into the
     * horizontal plane. Levels the ship rather than only damping it.
     */
    private static Vector3d horizonDirection(Vector3d velocity, Quaterniond vesselOrientation, boolean moving) {
        if (moving) {
            Vector3d horizontal = new Vector3d(velocity.x, 0, velocity.z);
            if (horizontal.lengthSquared() > 1e-8d) {
                return horizontal.normalize();
            }
        }
        // Stationary or travelling straight up: level the vessel itself.
        Vector3d flattened = new Quaterniond(vesselOrientation).transform(new Vector3d(0, 1, 0));
        flattened.y = 0;
        if (flattened.lengthSquared() > 1e-8d) {
            return flattened.normalize();
        }
        return new Vector3d(0, 0, 1);
    }

    /**
     * Direction of the world's sun. Northstar has no per-planet sun vector, so this
     * uses the level's sun angle, which every dimension exposes.
     */
    @Nullable
    private Vector3d sunDirection() {
        Level level = getLevel();
        if (level == null) {
            return null;
        }
        float sunAngle = level.getSunAngle(1f);
        return new Vector3d(Math.sin(sunAngle), Math.cos(sunAngle), 0.2d).normalize();
    }

    /**
     * Vessel velocity in blocks/tick, from the rigid body, falling back to the pose
     * delta for bodies the pipeline has not integrated yet this tick.
     */
    private Vector3d velocity(NorthstarSable.VesselFrame frame) {
        RigidBodyHandle body = frame.body();
        if (body != null && body.isValid()) {
            Vector3d fromBody = new Vector3d(body.getLinearVelocity());
            if (fromBody.lengthSquared() > 1e-8d) {
                return fromBody;
            }
        }
        return new Vector3d(frame.position())
                .sub(frame.subLevel().lastPose().position())
                .mul(20d);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        GyrodyneMode mode = getMode();
        tooltip.add(Component.literal("    ")
                .append(Component.translatable(getBlockState().getBlock().getDescriptionId())
                        .withStyle(ChatFormatting.GOLD)));
        tooltip.add(Component.literal("  ")
                .append(Component.translatable("northstar.gui.gyrodyne.mode")).append(": ")
                .append(mode.getComponent().withStyle(ChatFormatting.AQUA)));
        tooltip.add(Component.literal("  ")
                .append(Component.translatable("northstar.gui.gyrodyne.status")).append(": ")
                .append(Component.translatable(isActive()
                                ? "northstar.gui.gyrodyne.status.active"
                                : "northstar.gui.gyrodyne.status.inactive")
                        .withStyle(isActive() ? ChatFormatting.GREEN : ChatFormatting.RED)));
        return true;
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt("Mode", getMode().ordinal());
        tag.putDouble("TiltX", tiltXDegrees);
        tag.putDouble("TiltZ", tiltZDegrees);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (tag.contains("Mode")) {
            modeSelector.setValue(GyrodyneMode.byIndex(tag.getInt("Mode")).ordinal());
        }
        tiltXDegrees = tag.getDouble("TiltX");
        tiltZDegrees = tag.getDouble("TiltZ");
    }
}