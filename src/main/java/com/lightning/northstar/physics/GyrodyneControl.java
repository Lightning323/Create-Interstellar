package com.lightning.northstar.physics;

import net.minecraft.core.Direction;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.Nullable;

/**
 * Attitude control law for vessel-mounted gyrodyne reaction wheels.
 *
 * <p>A gyrodyne reads how a vessel is turning and torques it back toward a
 * reference: hold a fixed attitude, or point somewhere specific. This class owns
 * that law and nothing else — no world state, no Sable types — so the whole
 * controller can be exercised without a running level or a physics pipeline. The
 * block entity supplies the readings and hands the resulting angular impulse to
 * Sable via {@code RigidBodyHandle.applyAngularImpulse}.
 *
 * <p>Everything is a proportional-derivative law on the vessel's attitude, in
 * the gyrodyne's own mounting frame:
 *
 * <pre>torque = proportional * attitudeError - damping * angularVelocity</pre>
 *
 * <p>Units: torque is newton-metres, attitude error is radians, angular velocity
 * is radians per second, and the impulse handed to Sable is newton-metre-seconds.
 * Sable resolves the inertia tensor itself, so this class never multiplies by
 * mass when producing the impulse — mass only scales how much torque a given
 * gyrodyne is worth, through {@link #torqueCapacity(double, double)}.
 */
@ParametersAreNonnullByDefault
public class GyrodyneControl {

    /**
     * Vessel mass, in kilograms, that {@code baseTorque} is quoted against. Heavier
     * vessels get proportionally more torque from each gyrodyne, which is what makes
     * a small craft need fewer reaction wheels than a freighter.
     */
    public static final double REFERENCE_MASS_KG = 30d;

    /**
     * Gimbal deflection, in degrees, commanded at full torque. The tilt is a pure
     * visual and gameplay tell of how hard the wheel is working; it never limits the
     * torque itself.
     */
    public static final double MAX_TILT_DEGREES = 45d;

    /**
     * Below this the attitude error is treated as zero. Guards the degenerate cases
     * where a rotation vector has no meaningful direction, and keeps a settled vessel
     * from being nudged by floating point noise.
     */
    private static final double PARALLEL_EPSILON = 1e-9d;

    private GyrodyneControl() {
    }

    /**
     * Proportional-derivative gains for the attitude loop.
     *
     * @param proportional newton-metres of torque per radian of attitude error
     * @param damping     newton-metres of torque per rad/s of angular velocity
     */
    public record Gains(double proportional, double damping) {
        /**
         * Softer than the Cosmonautics original (kp 30, kd 22) because those gains
         * were multiplied by a fudge factor before reaching the body. These are in
         * real newton-metres and go straight to {@code applyAngularImpulse}.
         */
        public static final Gains DEFAULT = new Gains(4000d, 900d);
    }

    /**
     * The vessel readings a control law needs, already in world space.
     *
     * @param vesselOrientation    vessel orientation, local to world
     * @param mountOrientation     gyrodyne mounting frame, mount-local to world. Its
     *                             local +Y is the mounting axis, so gains and tilt
     *                             are distributed along the axis the player chose.
     * @param angularVelocityWorld vessel angular velocity, radians per second
     */
    public record Attitude(Quaterniond vesselOrientation, Quaterniond mountOrientation,
                           Vector3dc angularVelocityWorld) {

        /**
         * Angular velocity resolved into the gyrodyne's mounting frame.
         */
        public Vector3d localAngularVelocity() {
            return new Quaterniond(mountOrientation).conjugate()
                    .transform(new Vector3d(angularVelocityWorld));
        }

        /**
         * The mounting axis in world space: where this gyrodyne considers "forward".
         */
        public Vector3d mountAxisWorld() {
            return new Quaterniond(mountOrientation).transform(new Vector3d(0, 1, 0));
        }
    }

    /**
     * What the control law decided this step.
     *
     * @param angularImpulseWorld torque integrated over the physics step, in
     *                            newton-metre-seconds. Hand this straight to
     *                            {@code RigidBodyHandle.applyAngularImpulse}.
     * @param tiltXDegrees        commanded gimbal tilt about the mount's local X, for
     *                            rendering and for future thrust vectoring
     * @param tiltZDegrees        commanded gimbal tilt about the mount's local Z
     */
    public record Response(Vector3d angularImpulseWorld, double tiltXDegrees, double tiltZDegrees) {
        public static final Response IDLE = new Response(new Vector3d(), 0d, 0d);

        public boolean isFiring() {
            return angularImpulseWorld.lengthSquared() > PARALLEL_EPSILON * PARALLEL_EPSILON;
        }
    }

    /**
     * Torque one gyrodyne can deliver, in newton-metres.
     *
     * <p>Scaling is the square root of mass rather than linear: rotational inertia
     * grows with mass but a ship's dimensions do too, so a heavier vessel has more
     * leverage per wheel. The square root keeps a large vessel from needing an
     * absurd number of gyrodyne blocks.
     *
     * @param baseTorqueNm torque at {@link #REFERENCE_MASS_KG}, newton-metres
     * @param massKg        vessel mass, kilograms
     */
    public static double torqueCapacity(double baseTorqueNm, double massKg) {
        if (baseTorqueNm <= 0d) {
            return 0d;
        }
        double massScale = Math.max(1d, Math.sqrt(Math.max(massKg, 0d) / REFERENCE_MASS_KG));
        return baseTorqueNm * massScale;
    }

    /**
     * Rotational stabilisation only: bleeds off whatever the vessel is currently
     * spinning at, without caring which way it points. This is the mode a player
     * leaves on for ordinary flight.
     */
    public static Response dampRotation(Attitude attitude, double timeStep, Gains gains, double capacityNm) {
        return respond(attitude, new Vector3d(), true, timeStep, gains, capacityNm);
    }

    /** Tracks a pilot-commanded angular velocity expressed in world-space radians per second. */
    public static Response trackAngularVelocity(Attitude attitude, Vector3dc desiredWorld,
                                                double timeStep, Gains gains, double capacityNm) {
        if (timeStep <= 0d || capacityNm <= 0d) {
            return Response.IDLE;
        }

        Vector3d errorLocal = new Vector3d(desiredWorld)
                .sub(attitude.angularVelocityWorld());
        new Quaterniond(attitude.mountOrientation()).conjugate().transform(errorLocal);
        Vector3d torqueLocal = errorLocal.mul(gains.damping());
        double magnitude = torqueLocal.length();
        if (magnitude > capacityNm) {
            torqueLocal.mul(capacityNm / magnitude);
        }

        Vector3d torqueWorld = new Quaterniond(attitude.mountOrientation())
                .transform(torqueLocal, new Vector3d());
        return new Response(torqueWorld.mul(timeStep),
                tiltXFor(torqueLocal, capacityNm), tiltZFor(torqueLocal, capacityNm));
    }

    /**
     * Returns the vessel to a previously captured orientation and then holds it there.
     *
     * @param holdOrientation orientation to hold, local to world
     */
    public static Response holdOrientation(Attitude attitude, Quaterniond holdOrientation,
                                           double timeStep, Gains gains, double capacityNm) {
        // The error is the rotation that takes the vessel back onto the held
        // orientation, expressed as a rotation vector in world space.
        Vector3d errorWorld = rotationVector(new Quaterniond(holdOrientation)
                .mul(new Quaterniond(attitude.vesselOrientation()).invert()));
        return respond(attitude, errorWorld, false, timeStep, gains, capacityNm);
    }

    /**
     * Turns the mounting axis onto a world-space direction and holds it there. Used
     * by the vector modes — prograde, radial, horizon and so on.
     *
     * @param targetDirectionWorld direction to point at, need not be normalised
     */
    public static Response alignTo(Attitude attitude, Vector3dc targetDirectionWorld,
                                   double timeStep, Gains gains, double capacityNm) {
        return respond(attitude, alignmentError(attitude.mountAxisWorld(), targetDirectionWorld),
                false, timeStep, gains, capacityNm);
    }

    /**
     * Shared tail of every control law: convert a world-space attitude error into
     * torque in the mounting frame, clamp it to what one gyrodyne can produce, and
     * integrate it over the physics step.
     *
     * @param errorWorld       attitude error as a rotation vector, world space
     * @param dampingOnly      true for rate damping, where the error term is skipped
     */
    private static Response respond(Attitude attitude, Vector3d errorWorld, boolean dampingOnly,
                                    double timeStep, Gains gains, double capacityNm) {
        if (timeStep <= 0d || capacityNm <= 0d) {
            return Response.IDLE;
        }

        Vector3d torqueLocal = new Vector3d();
        if (!dampingOnly) {
            // transform(vec, dest) rather than transform(vec): JOML's single argument
            // form rewrites its argument in place, and torqueLocal must survive as the
            // mounting-frame reading that the gimbal tilt is measured from.
            Quaterniond intoMountFrame = new Quaterniond(attitude.mountOrientation()).conjugate();
            torqueLocal.add(intoMountFrame.transform(errorWorld, new Vector3d())
                    .mul(gains.proportional()));
        }
        torqueLocal.sub(attitude.localAngularVelocity().mul(gains.damping()));

        double magnitude = torqueLocal.length();
        if (magnitude > capacityNm) {
            torqueLocal.mul(capacityNm / magnitude);
        }

        Vector3d torqueWorld = new Quaterniond(attitude.mountOrientation())
                .transform(torqueLocal, new Vector3d());
        Vector3d impulse = torqueWorld.mul(timeStep);

        return new Response(impulse, tiltXFor(torqueLocal, capacityNm), tiltZFor(torqueLocal, capacityNm));
    }

    /**
     * The minimal rotation vector, in world space, that turns {@code from} onto
     * {@code to}.
     */
    public static Vector3d alignmentError(Vector3dc from, Vector3dc to) {
        Vector3d start = new Vector3d(from);
        Vector3d end = new Vector3d(to);
        if (start.lengthSquared() < PARALLEL_EPSILON || end.lengthSquared() < PARALLEL_EPSILON) {
            return new Vector3d();
        }
        start.normalize();
        end.normalize();

        // start.cross(end), not new Vector3d().cross(start, end): the two argument
        // JOML overload writes this x arg into the destination rather than replacing
        // the receiver, so it would silently yield the destination untouched.
        Vector3d axis = new Vector3d(start).cross(end);
        double sin = axis.length();
        double cos = Math.max(-1d, Math.min(1d, start.dot(end)));

        if (sin < PARALLEL_EPSILON) {
            if (cos > 0d) {
                // Already pointing the right way.
                return new Vector3d();
            }
            // Exactly opposed: any axis perpendicular to the start will do, so pick
            // the world axis least aligned with it to stay well conditioned.
            axis.set(Math.abs(start.x) < 0.9d ? 1d : 0d,
                    Math.abs(start.x) < 0.9d ? 0d : 1d,
                    0d);
            axis.cross(start).normalize(Math.PI);
            return axis;
        }

        return axis.normalize(Math.atan2(sin, cos));
    }

    /**
     * Converts a quaternion to the rotation vector that produces it: axis scaled by
     * angle. Choosing the double cover with a non-negative scalar part keeps the
     * result on the shortest arc, so a vessel just past 180 degrees of error does not
     * unwind the long way round.
     */
    public static Vector3d rotationVector(Quaterniond quaternion) {
        Quaterniond q = new Quaterniond(quaternion);
        if (q.w() < 0d) {
            q.mul(-1d);
        }
        double sinHalfAngle = Math.sqrt(q.x() * q.x() + q.y() * q.y() + q.z() * q.z());
        if (sinHalfAngle < PARALLEL_EPSILON) {
            return new Vector3d();
        }
        double angle = 2d * Math.atan2(sinHalfAngle, q.w());
        return new Vector3d(q.x(), q.y(), q.z()).mul(angle / sinHalfAngle);
    }

    /**
     * Mounting frame of a gyrodyne placed against {@code facing}, taking mount-local
     * axes to world. Local +Y ends up along {@code facing}, so a gyrodyne bolted to
     * the underside of a hull points down.
     */
    public static Quaterniond mountOrientation(Direction facing) {
        return switch (facing) {
            case UP -> new Quaterniond();
            case DOWN -> new Quaterniond().rotateX(Math.PI);
            case SOUTH -> new Quaterniond().rotateX(Math.PI / 2d);
            case NORTH -> new Quaterniond().rotateX(-Math.PI / 2d);
            case EAST -> new Quaterniond().rotateZ(-Math.PI / 2d);
            case WEST -> new Quaterniond().rotateZ(Math.PI / 2d);
        };
    }

    /**
     * Gimbal tilt about the mount's local X, in degrees. Torque about local Z rolls
     * the rotor about local X, hence the cross axis.
     */
    private static double tiltXFor(Vector3dc torqueLocal, double capacityNm) {
        return tiltFor(torqueLocal.z(), capacityNm);
    }

    /**
     * Gimbal tilt about the mount's local Z, in degrees.
     */
    private static double tiltZFor(Vector3dc torqueLocal, double capacityNm) {
        return tiltFor(-torqueLocal.x(), capacityNm);
    }

    private static double tiltFor(double torqueComponent, double capacityNm) {
        if (capacityNm <= 0d) {
            return 0d;
        }
        double normalised = torqueComponent / capacityNm;
        return Math.max(-MAX_TILT_DEGREES,
                Math.min(MAX_TILT_DEGREES, normalised * MAX_TILT_DEGREES));
    }

    /**
     * Rotational rate of a vessel whose orientation is changing, for callers that
     * only have two samples.
     *
     * @param from previous vessel orientation
     * @param to   current vessel orientation
     * @param seconds elapsed time between the samples
     */
    public static Vector3d angularVelocityBetween(Quaterniond from, Quaterniond to, double seconds) {
        if (seconds <= 0d) {
            return new Vector3d();
        }
        return rotationVector(new Quaterniond(to).mul(new Quaterniond(from).invert()))
                .div(seconds);
    }

    /**
     * Null-tolerant {@link #alignTo} for modes whose reference direction may be
     * undefined, such as prograde while the vessel is stationary.
     */
    public static Response alignToOrIdle(Attitude attitude, @Nullable Vector3dc targetDirectionWorld,
                                         double timeStep, Gains gains, double capacityNm) {
        if (targetDirectionWorld == null || targetDirectionWorld.lengthSquared() < PARALLEL_EPSILON) {
            return Response.IDLE;
        }
        return alignTo(attitude, targetDirectionWorld, timeStep, gains, capacityNm);
    }
}
