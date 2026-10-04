package com.lightning.northstar.physics;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;

/**
 * Distributes a pilot's attitude command across a vessel's thrusters.
 *
 * <p>The old flight model could only go straight up: every thruster pointed at the
 * sky and the contraption entity integrated a single vertical acceleration. A rigid
 * body can do much better, but only if the thrusters are told which ones to fire.
 *
 * <p>The rule is the same one a real spacecraft uses — a thruster contributes to a
 * commanded rotation in proportion to how much its thrust line passes to one side
 * of the vessel's centre of mass:
 *
 * <pre>moment = (nozzle - centreOfMass) x exhaust</pre>
 *
 * <p>A moment aligned with the commanded axis spins the vessel that way; the
 * opposite moment brakes it. Thrusters on the centre line contribute nothing,
 * which is correct: they push straight forward and cannot steer.
 *
 * <p>Pure maths with no world or Sable types, so the whole distribution can be
 * unit-tested.
 */
@ParametersAreNonnullByDefault
public class RocketFlightControl {

    /**
     * Below this the moment is treated as zero. Thrusters sitting almost exactly on
     * the centre line produce a moment of nearly nothing, and letting floating point
     * noise decide whether they fire would make a ship twitch for no reason.
     */
    private static final double MOMENT_EPSILON = 1e-4d;

    private RocketFlightControl() {
    }

    /**
     * One thruster as the control law sees it: where its nozzle is and which way it
     * points.
     *
     * @param nozzle        nozzle position in local vessel space
     * @param exhaust       unit exhaust direction in local vessel space, pointing
     *                      out of the nozzle
     * @param baseThrottle  throttle already commanded by the pilot, 0..1
     */
    public record Thruster(Vec3 nozzle, Vector3dc exhaustAxis, float baseThrottle) {

        public Thruster {
            if (baseThrottle < 0f) {
                baseThrottle = 0f;
            } else if (baseThrottle > 1f) {
                baseThrottle = 1f;
            }
        }

        /**
         * A thruster mounted against {@code facing}, nozzle offset half a block along
         * it. The exhaust points out along {@code facing}.
         */
        public static Thruster of(Direction facing, Vec3 blockCentre, float baseThrottle) {
            return new Thruster(
                    blockCentre.add(facing.getStepX() * 0.5d, facing.getStepY() * 0.5d,
                            facing.getStepZ() * 0.5d),
                    new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ()),
                    baseThrottle);
        }
    }

    /**
     * How a single thruster responds to the current command.
     *
     * @param throttle  commanded throttle for this thruster, 0..1
     * @param pitch     signed pitch contribution, for readouts and tests
     */
    public record Demand(float throttle, double pitch, double yaw, double roll) {

        public static final Demand OFF = new Demand(0f, 0d, 0d, 0d);

        public boolean isFiring() {
            return throttle > 0f;
        }
    }

    /**
     * A pilot's command, in the vessel's own frame.
     *
     * @param throttle 0..1
     * @param pitch    -1..1, nose down to nose up
     * @param yaw      -1..1, nose left to nose right
     * @param roll     -1..1
     */
    public record Command(float throttle, float pitch, float yaw, float roll) {
    }

    /**
     * The vessel axes, resolved in local vessel space, against which the pitch/yaw/
     * roll moments are compared.
     *
     * @param right unit vessel +X
     * @param up    unit vessel +Y
     * @param back  unit vessel +Z, pointing out of the nose
     */
    public record Axes(Vector3dc right, Vector3dc up, Vector3dc back) {

        public static final Axes LEVEL = new Axes(
                new Vector3d(1, 0, 0), new Vector3d(0, 1, 0), new Vector3d(0, 0, 1));

        /**
         * Unit moment vector for each rotation axis, in local vessel space.
         *
         * <p>Pitch turns about the right axis, yaw about the up axis, roll about the
         * back axis. All three are mutually perpendicular on a rigid body, so this
         * is an orthonormal basis and the projections below are independent.
         */
        public Vector3d pitchAxis() {
            return new Vector3d(right);
        }

        public Vector3d yawAxis() {
            return new Vector3d(up);
        }

        public Vector3d rollAxis() {
            return new Vector3d(back);
        }
    }

    /**
     * Resolves one thruster's throttle given the pilot's command.
     *
     * <p>The base throttle is the floor: with the sticks centred every thruster fires
     * at exactly the throttle the pilot asked for, so a ship with no steering
     * thrusters still flies straight. Steering then works as <em>differential</em>
     * thrust, which is how real spacecraft do it: a thruster whose moment helps the
     * commanded rotation is throttled up, one whose moment opposes it is throttled
     * down. A ship with an opposed pair therefore rotates even though neither
     * thruster ever fires on its own for that axis.
     *
     * <p>Because a single throttle cannot both push and steer, the base throttle wins
     * whenever the pilot is not asking to rotate. Steering only redistributes the
     * thrust that is already there.
     *
     * @param thruster the thruster
     * @param command  the pilot's command
     * @param axes     vessel axes in local vessel space
     * @return the throttle this thruster should run at
     */
    public static Demand resolve(Thruster thruster, Command command, Axes axes) {
        return resolve(thruster, command, axes, Vec3.ZERO, 1d);
    }

    /**
     * {@link #resolve(Thruster, Command, Axes)} with the centre of mass and gain
     * supplied explicitly, so the distribution can be tested without a vessel.
     *
     * @param centreOfMass vessel centre of mass in local vessel space
     * @param gain         how strongly a full-scale moment demand commands a thruster
     */
    public static Demand resolve(Thruster thruster, Command command, Axes axes,
                                 Vec3 centreOfMass, double gain) {
        if (thruster.baseThrottle() <= 0f) {
            return Demand.OFF;
        }

        Vector3d exhaust = new Vector3d(thruster.exhaustAxis()).normalize();
        if (exhaust.lengthSquared() < 0.5d) {
            return Demand.OFF;
        }

        Vector3d lever = new Vector3d(thruster.nozzle().x, thruster.nozzle().y, thruster.nozzle().z)
                .sub(centreOfMass.x, centreOfMass.y, centreOfMass.z);

        // new Vector3d(lever).cross(exhaust): JOML's two argument cross writes
        // this x arg into the destination rather than replacing the receiver.
        Vector3d moment = new Vector3d(lever).cross(exhaust);

        double pitchMoment = axes.pitchAxis().dot(moment);
        double yawMoment = axes.yawAxis().dot(moment);
        double rollMoment = axes.rollAxis().dot(moment);

        float base = thruster.baseThrottle();
        double demand = command.pitch() * pitchMoment
                + command.yaw() * yawMoment
                + command.roll() * rollMoment;

        boolean commanding = command.pitch() != 0f || command.yaw() != 0f || command.roll() != 0f;
        if (!commanding || Math.abs(demand) < MOMENT_EPSILON) {
            // Sticks centred, or this thruster sits on the centre line and has no
            // leverage: it pushes at exactly the throttle the pilot set.
            return new Demand(base, pitchMoment, yawMoment, rollMoment);
        }

        if (gain <= 0d) {
            return Demand.OFF;
        }

        float throttle = demand > 0d
                ? Math.min(1f, base + (float) (demand / gain))
                : Math.max(0f, base - (float) (-demand / gain));
        return new Demand(throttle, pitchMoment, yawMoment, rollMoment);
    }

    /**
     * Net moment a command produces across every thruster, in newton-metres.
     *
     * <p>Returned as a vector rather than a magnitude, because the magnitude alone
     * cannot say which way the ship will turn. Two thrusters pointing opposite ways
     * can produce a large magnitude and almost no rotation, which is exactly the
     * mistake a symmetric ship makes if you only ever look at the total.
     *
     * @param thrusters       every thruster on the vessel
     * @param command         the pilot's command
     * @param axes            vessel axes in local vessel space
     * @param centreOfMass    vessel centre of mass in local vessel space
     * @param forcePerThruster thrust of one thruster at full throttle
     * @return the net moment vector, in local vessel space
     */
    public static Vector3d totalMoment(List<Thruster> thrusters, Command command, Axes axes,
                                      Vec3 centreOfMass, double forcePerThruster) {
        return totalMoment(thrusters, command, axes, centreOfMass, forcePerThruster, 1d);
    }

    /**
     * {@link #totalMoment(List, Command, Axes, Vec3, double)} with the differential
     * gain supplied explicitly.
     *
     * @param gain see {@link #resolve(Thruster, Command, Axes, Vec3, double)}
     */
    public static Vector3d totalMoment(List<Thruster> thrusters, Command command, Axes axes,
                                      Vec3 centreOfMass, double forcePerThruster, double gain) {
        Vector3d total = new Vector3d();
        for (Thruster thruster : thrusters) {
            Demand demand = resolve(thruster, command, axes, centreOfMass, gain);
            if (!demand.isFiring()) {
                continue;
            }
            Vector3d exhaust = new Vector3d(thruster.exhaustAxis()).normalize();
            Vector3d lever = new Vector3d(thruster.nozzle().x, thruster.nozzle().y, thruster.nozzle().z)
                    .sub(centreOfMass.x, centreOfMass.y, centreOfMass.z);
            Vector3d moment = new Vector3d(lever).cross(exhaust);
            total.add(moment.mul(demand.throttle() * forcePerThruster));
        }
        return total;
    }

    /**
     * Steering authority about one vessel axis: the component of {@link
     * #totalMoment} along that axis. Positive means the ship turns the way the
     * command asked, negative means it turns the wrong way, and near zero means this
     * axis has no thrusters behind it.
     */
    public static double authorityAbout(List<Thruster> thrusters, Command command, Axes axes,
                                        Vec3 centreOfMass, double forcePerThruster, Vector3dc axis) {
        return authorityAbout(thrusters, command, axes, centreOfMass, forcePerThruster, axis, 1d);
    }

    /**
     * {@link #authorityAbout(List, Command, Axes, Vec3, double, Vector3dc)} with the
     * differential gain supplied explicitly.
     *
     * @param gain see {@link #resolve(Thruster, Command, Axes, Vec3, double)}
     */
    public static double authorityAbout(List<Thruster> thrusters, Command command, Axes axes,
                                        Vec3 centreOfMass, double forcePerThruster, Vector3dc axis,
                                        double gain) {
        return totalMoment(thrusters, command, axes, centreOfMass, forcePerThruster, gain).dot(axis);
    }
}
