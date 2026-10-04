package com.lightning.northstar.physics;

import com.lightning.northstar.config.NorthstarConfigs;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;

/**
 * Resolves the net thrust produced by a vessel's thrusters and the propellant
 * that thrust costs.
 *
 * <p>This class holds no world state and no Sable types, so the force and fuel
 * model can be exercised without a running level or a physics pipeline. The
 * caller hands the resulting impulse to Sable, either per thruster via
 * {@code RigidBodyHandle.applyImpulseAtPoint} (which resolves the centre of mass
 * and rotation itself) or for a whole vessel at once via
 * {@code applyLinearAndAngularImpulse}.
 *
 * <p>Units: force is newtons, impulse is newton-seconds, mass is kilograms, and
 * propellant is megabytes (mB).
 */
@ParametersAreNonnullByDefault
public class RocketPropulsion {

    /**
     * Standard gravity in m/s^2. Used to express thrust as a multiple of the
     * vessel's weight so that tier comparisons stay meaningful across planets
     * with differing gravityScale.
     */
    public static final double STANDARD_GRAVITY = 9.80665d;

    /**
     * Converts blocks/tick to m/s. One block is one metre and one tick is
     * 1/20th of a second.
     */
    public static final double BLOCKS_PER_TICK_TO_METERS_PER_SECOND = 20d;

    private RocketPropulsion() {
    }

    /**
     * A single thruster's contribution to a burn.
     *
     * @param exhaustAxis  unit exhaust direction in <em>local</em> vessel space,
     *                     pointing out of the nozzle. Thrust acts along its
     *                     reverse, since a rocket is pushed away from its plume.
     * @param mountPoint   nozzle position in local vessel space
     * @param throttle     commanded throttle for this thruster, 0..1
     * @param dryMassKg    mass of the thruster itself, excluded from propellant
     *                     budget but included in vessel mass
     */
    public record Thruster(Vector3dc exhaustAxis, Vector3dc mountPoint, float throttle, float dryMassKg) {
        public Thruster {
            if (throttle < 0f) {
                throttle = 0f;
            } else if (throttle > 1f) {
                throttle = 1f;
            }
        }

        /**
         * A thruster mounted on {@code facing} at {@code mountPoint} in local
         * vessel space. The exhaust points along {@code facing}, so the thrust
         * pushes the vessel the opposite way: a thruster on the underside of a
         * hull lifts it.
         */
        public static Thruster of(Direction facing, Vec3 mountPoint, float throttle) {
            return new Thruster(
                    new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ()),
                    new Vector3d(mountPoint.x, mountPoint.y, mountPoint.z),
                    throttle, 0f);
        }

        public boolean isFiring() {
            return throttle > 0f;
        }
    }

    /**
     * Result of resolving a burn: the impulse the vessel receives this physics
     * step, and what that impulse costs in propellant.
     *
     * @param linearImpulse   world-space impulse, newton-seconds
     * @param angularImpulse  world-space angular impulse about the centre of
     *                        mass, newton-metre-seconds. Informational: Sable
     *                        derives this itself when impulses are applied at
     *                        points, so it is only needed by callers that apply
     *                        force and torque separately.
     * @param propellantMb    propellant consumed this step, mB
     * @param thrustToWeight  total thrust divided by vessel weight, in g
     */
    public record Burn(Vector3d linearImpulse, Vector3d angularImpulse, float propellantMb, double thrustToWeight) {
        public static final Burn EMPTY = new Burn(new Vector3d(), new Vector3d(), 0f, 0d);

        public boolean isFiring() {
            return linearImpulse.length() > 1e-9d;
        }

        public double thrustNewtons(double deltaTime) {
            return deltaTime > 0d ? linearImpulse.length() / deltaTime : 0d;
        }
    }

    /**
     * Sums every firing thruster into a single world-space impulse, together
     * with the torque that impulse produces about the vessel's centre of mass.
     *
     * <p>Torque is the cross product of each nozzle's offset from the centre of
     * mass with its thrust, which is what makes off-axis placement rotate the
     * vessel. Applying thrust through the centre of mass produces linear
     * acceleration only.
     *
     * @param thrusters         thrusters on the vessel
     * @param vesselQuaternion  vessel orientation, local to world
     * @param centreOfMassLocal centre of mass in local vessel space
     * @param deltaTime         physics step length in seconds
     * @param massKg            vessel mass in kilograms, used for thrust-to-weight
     * @param gravityScale      local gravity multiplier, 1 for Earth-like
     */
    public static Burn resolve(List<Thruster> thrusters, Quaterniond vesselQuaternion,
                               Vector3dc centreOfMassLocal, double deltaTime,
                               double massKg, double gravityScale) {
        return resolve(thrusters, vesselQuaternion, centreOfMassLocal, deltaTime, massKg, gravityScale,
                NorthstarConfigs.server().thrusterPower.getF(),
                NorthstarConfigs.server().propellantSpecificImpulse.getF());
    }

    /**
     * {@link #resolve(List, Quaterniond, Vector3dc, double, double, double)}
     * with the engine constants supplied explicitly, so the algebra can be
     * exercised without a loaded config.
     */
    public static Burn resolve(List<Thruster> thrusters, Quaterniond vesselQuaternion,
                               Vector3dc centreOfMassLocal, double deltaTime,
                               double massKg, double gravityScale,
                               double forcePerThruster, double specificImpulse) {
        if (thrusters.isEmpty() || deltaTime <= 0d) {
            return Burn.EMPTY;
        }

        Quaterniond orientation = new Quaterniond(vesselQuaternion);
        Vector3d linearImpulse = new Vector3d();
        Vector3d angularImpulse = new Vector3d();
        Vector3d thrustLocal = new Vector3d();
        Vector3d pointLocal = new Vector3d();
        Vector3d leverArm = new Vector3d();

        double totalThrust = 0d;

        for (Thruster thruster : thrusters) {
            if (!thruster.isFiring()) {
                continue;
            }

            // Rotate the nozzle into world space and reverse it, so the vessel
            // is pushed away from the plume.
            thrustLocal.set(thruster.exhaustAxis()).normalize().negate()
                    .mul(forcePerThruster * thruster.throttle());
            orientation.transform(thrustLocal);
            totalThrust += thrustLocal.length();

            Vector3d impulse = new Vector3d(thrustLocal).mul(deltaTime);
            linearImpulse.add(impulse);

            pointLocal.set(thruster.mountPoint()).sub(centreOfMassLocal);
            orientation.transform(pointLocal);
            // JOML's cross(a, dest) takes the second operand as the receiver and the
            // destination last, so the receiver must be the lever arm, not a scratch vector.
            pointLocal.cross(impulse, leverArm);
            angularImpulse.add(leverArm);
        }

        float propellantMb = propellantFor(impulseMagnitude(linearImpulse), specificImpulse);
        double thrustToWeight = thrustToWeight(totalThrust, massKg, gravityScale);

        return new Burn(linearImpulse, angularImpulse, propellantMb, thrustToWeight);
    }

    /**
     * World-space thrust force, in newtons, for a single thruster. Multiply by
     * the physics step length to get an impulse.
     */
    public static Vector3d forceFor(Thruster thruster, Quaterniond vesselQuaternion) {
        return forceFor(thruster, vesselQuaternion, NorthstarConfigs.server().thrusterPower.getF());
    }

    /**
     * {@link #forceFor(Thruster, Quaterniond)} with the engine force supplied
     * explicitly.
     */
    public static Vector3d forceFor(Thruster thruster, Quaterniond vesselQuaternion, double forcePerThruster) {
        if (!thruster.isFiring() || forcePerThruster <= 0d) {
            return new Vector3d();
        }
        Vector3d thrust = new Vector3d(thruster.exhaustAxis()).normalize().negate()
                .mul(forcePerThruster * thruster.throttle());
        new Quaterniond(vesselQuaternion).transform(thrust);
        return thrust;
    }

    /**
     * Propellant required to produce the given impulse, in mB. The configured
     * {@code propellantSpecificImpulse} is newton-seconds of impulse per mB, so
     * a higher value burns less propellant for the same push.
     */
    public static float propellantFor(double impulseNewtonSeconds) {
        return propellantFor(impulseNewtonSeconds, NorthstarConfigs.server().propellantSpecificImpulse.getF());
    }

    /**
     * {@link #propellantFor(double)} with the specific impulse supplied
     * explicitly.
     */
    public static float propellantFor(double impulseNewtonSeconds, double specificImpulse) {
        if (impulseNewtonSeconds <= 0d) {
            return 0f;
        }
        return (float) (impulseNewtonSeconds / Math.max(specificImpulse, 1e-6d));
    }

    /**
     * Magnitude of an impulse vector, tolerating a null.
     */
    public static double impulseMagnitude(Vector3dc impulse) {
        return impulse == null ? 0d : impulse.length();
    }

    /**
     * Computes the thrust-to-weight ratio of a burn, expressed in g. A value
     * above 1 means the vessel can climb under its own power in the gravity it
     * is currently subject to.
     *
     * @param thrustNewtons total thrust magnitude, newtons
     * @param massKg        vessel mass, kilograms
     * @param gravityScale  local gravity multiplier, 1 for Earth-like
     */
    public static double thrustToWeight(double thrustNewtons, double massKg, double gravityScale) {
        if (massKg <= 0d || thrustNewtons <= 0d) {
            return 0d;
        }
        double gravity = STANDARD_GRAVITY * Math.max(gravityScale, 0d);
        if (gravity <= 0d) {
            return Double.POSITIVE_INFINITY;
        }
        return thrustNewtons / (massKg * gravity);
    }

    /**
     * Minimum number of thrusters needed to leave a gravity field.
     *
     * @param massKg       vessel mass, kilograms
     * @param gravityScale local gravity multiplier
     */
    public static int thrustersRequiredToLift(double massKg, double gravityScale) {
        return thrustersRequiredToLift(massKg, gravityScale,
                NorthstarConfigs.server().thrusterPower.getF());
    }

    /**
     * {@link #thrustersRequiredToLift(double, double)} with the engine force
     * supplied explicitly.
     */
    public static int thrustersRequiredToLift(double massKg, double gravityScale, double power) {
        if (power <= 0d || gravityScale <= 0d) {
            return gravityScale <= 0d ? 0 : 1;
        }
        double required = Math.ceil(massKg * STANDARD_GRAVITY * gravityScale / power);
        return Math.max(1, (int) required);
    }

    /**
     * Converts a world-space speed in blocks/tick to m/s for readouts.
     */
    public static double toMetersPerSecond(double blocksPerTick) {
        return blocksPerTick * BLOCKS_PER_TICK_TO_METERS_PER_SECOND;
    }

    /**
     * Converts a speed in m/s back to blocks/tick, for handing values to
     * Minecraft-facing motion APIs.
     */
    public static double toBlocksPerTick(double metersPerSecond) {
        return metersPerSecond / BLOCKS_PER_TICK_TO_METERS_PER_SECOND;
    }

    /**
     * Nozzle position of a thruster mounted on {@code facing}: the block centre
     * offset half a block along the mounting face. The half-block offset is what
     * puts the lever arm that produces torque.
     */
    public static Vec3 mountPointFor(Direction facing) {
        return new Vec3(
                facing.getStepX() * 0.5d,
                facing.getStepY() * 0.5d,
                facing.getStepZ() * 0.5d);
    }
}
