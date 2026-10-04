package com.lightning.northstar.physics;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the pure force and propellant model. These exercise the unit algebra
 * that the thruster block entity relies on, so they deliberately avoid any
 * Minecraft level or Sable type.
 */
class RocketPropulsionTest {

    private static final double EPSILON = 1e-6d;

    /** Stand-in for the configured engine force, so no loaded config is needed. */
    private static final double THRUSTER_FORCE = 500d;

    /** Stand-in for the configured specific impulse, in N*s per mB. */
    private static final double SPECIFIC_IMPULSE = 1200d;

    private static RocketPropulsion.Thruster thruster(Direction facing, double throttle) {
        Vec3 mount = RocketPropulsion.mountPointFor(facing);
        return RocketPropulsion.Thruster.of(facing, mount, (float) throttle);
    }

    private static Vector3d force(RocketPropulsion.Thruster thruster, Quaterniond orientation) {
        return RocketPropulsion.forceFor(thruster, orientation, THRUSTER_FORCE);
    }

    @Test
    void mountPointSitsHalfABlockAlongTheMountingFace() {
        Vec3 down = RocketPropulsion.mountPointFor(Direction.DOWN);
        assertEquals(0d, down.x, EPSILON);
        assertEquals(-0.5d, down.y, EPSILON);
        assertEquals(0d, down.z, EPSILON);

        Vec3 east = RocketPropulsion.mountPointFor(Direction.EAST);
        assertEquals(0.5d, east.x, EPSILON);
        assertEquals(0d, east.y, EPSILON);
        assertEquals(0d, east.z, EPSILON);
    }

    @Test
    void throttleIsClampedToUnitRange() {
        assertEquals(0f, thruster(Direction.DOWN, -5d).throttle());
        assertEquals(1f, thruster(Direction.DOWN, 5d).throttle());
        assertEquals(0.5f, thruster(Direction.DOWN, 0.5d).throttle(), EPSILON);
    }

    @Test
    void thrusterOnTheUndersidePushesTheVesselUp() {
        Quaterniond upright = new Quaterniond();
        Vector3d thrust = force(thruster(Direction.DOWN, 1d), upright);

        assertEquals(0d, thrust.x, EPSILON);
        assertTrue(thrust.y > 0d, "exhaust points down, so thrust must point up");
        assertEquals(0d, thrust.z, EPSILON);
    }

    @Test
    void zeroThrottleProducesNoForce() {
        Vector3d thrust = force(thruster(Direction.DOWN, 0d), new Quaterniond());
        assertEquals(0d, thrust.length(), EPSILON);
    }

    @Test
    void emptyThrottleListResolvesToEmptyBurn() {
        RocketPropulsion.Burn burn = RocketPropulsion.resolve(
                List.of(), new Quaterniond(), new Vector3d(), 0.05d, 1000d, 1d,
                THRUSTER_FORCE, SPECIFIC_IMPULSE);
        assertFalse(burn.isFiring());
        assertEquals(0d, burn.linearImpulse().length(), EPSILON);
        assertEquals(0f, burn.propellantMb(), EPSILON);
    }

    @Test
    void symmetricThrustersCancelTheirTorque() {
        // Two thrusters on the same face at mirrored mount points. Their forces
        // add, their levers cancel, so the vessel translates without rotating.
        // Note this is not the same as pointing them at each other: opposed
        // nozzles would cancel the thrust too and produce no burn at all.
        Quaterniond upright = new Quaterniond();
        List<RocketPropulsion.Thruster> thrusters = List.of(
                RocketPropulsion.Thruster.of(Direction.DOWN, new Vec3(-1d, -0.5d, 0d), 1f),
                RocketPropulsion.Thruster.of(Direction.DOWN, new Vec3(1d, -0.5d, 0d), 1f));

        RocketPropulsion.Burn burn = RocketPropulsion.resolve(
                thrusters, upright, new Vector3d(), 0.05d, 1000d, 1d,
                THRUSTER_FORCE, SPECIFIC_IMPULSE);

        assertTrue(burn.isFiring());
        assertEquals(2d * THRUSTER_FORCE * 0.05d, burn.linearImpulse().y(), 1e-6d,
                "both thrusters push the vessel up");
        assertEquals(0d, burn.angularImpulse().length(), EPSILON,
                "mirrored mount points must produce no net torque");
    }

    @Test
    void opposedThrustersCancelOutEntirely() {
        // Nozzles pointing at each other push in opposite directions, so the net
        // burn is empty rather than a pure torque.
        Quaterniond upright = new Quaterniond();
        List<RocketPropulsion.Thruster> thrusters = List.of(
                thruster(Direction.DOWN, 1d),
                thruster(Direction.UP, 1d));

        RocketPropulsion.Burn burn = RocketPropulsion.resolve(
                thrusters, upright, new Vector3d(), 0.05d, 1000d, 1d,
                THRUSTER_FORCE, SPECIFIC_IMPULSE);

        assertFalse(burn.isFiring());
        assertEquals(0d, burn.linearImpulse().length(), EPSILON);
    }

    @Test
    void offAxisThrusterProducesTorque() {
        // A single thruster mounted below the centre of mass torques the vessel.
        Quaterniond upright = new Quaterniond();
        Vector3d centreOfMass = new Vector3d(2d, 0d, 0d);

        RocketPropulsion.Burn burn = RocketPropulsion.resolve(
                List.of(thruster(Direction.DOWN, 1d)), upright, centreOfMass, 0.05d, 1000d, 1d,
                THRUSTER_FORCE, SPECIFIC_IMPULSE);

        assertTrue(burn.angularImpulse().length() > 0d,
                "thrust applied away from the centre of mass must produce torque");
    }

    @Test
    void thrustScalesLinearlyWithThrottle() {
        Quaterniond upright = new Quaterniond();
        double full = force(thruster(Direction.DOWN, 1d), upright).length();
        double half = force(thruster(Direction.DOWN, 0.5d), upright).length();
        assertEquals(full / 2d, half, 1e-6d);
    }

    @Test
    void propellantScalesWithImpulse() {
        float single = RocketPropulsion.propellantFor(1000d, SPECIFIC_IMPULSE);
        float double_ = RocketPropulsion.propellantFor(2000d, SPECIFIC_IMPULSE);
        assertEquals(single * 2d, double_, 1e-4f);
        assertEquals(0f, RocketPropulsion.propellantFor(0d, SPECIFIC_IMPULSE), EPSILON);
    }

    @Test
    void thrustToWeightIsOneWhenThrustEqualsWeight() {
        double mass = 1000d;
        double weight = mass * RocketPropulsion.STANDARD_GRAVITY;
        assertEquals(1d, RocketPropulsion.thrustToWeight(weight, mass, 1d), 1e-9d);
    }

    @Test
    void thrustToWeightIsUnboundedInZeroGravity() {
        assertEquals(Double.POSITIVE_INFINITY,
                RocketPropulsion.thrustToWeight(1000d, 1000d, 0d));
    }

    @Test
    void heavierVesselsNeedMoreThrusters() {
        double light = RocketPropulsion.thrustersRequiredToLift(1000d, 1d, THRUSTER_FORCE);
        double heavy = RocketPropulsion.thrustersRequiredToLift(10000d, 1d, THRUSTER_FORCE);
        assertTrue(heavy > light, "a heavier vessel needs more thrusters");
        assertTrue(light >= 1, "at least one thruster is always required");
    }

    @Test
    void speedConversionsRoundTrip() {
        assertEquals(10d, RocketPropulsion.toMetersPerSecond(0.5d), 1e-9d);
        assertEquals(0.5d, RocketPropulsion.toBlocksPerTick(10d), 1e-9d);
    }
}
