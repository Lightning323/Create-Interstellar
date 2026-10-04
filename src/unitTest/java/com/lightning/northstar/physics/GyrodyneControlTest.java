package com.lightning.northstar.physics;

import net.minecraft.core.Direction;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the attitude control law. Like {@link RocketPropulsionTest} these stay off
 * Minecraft levels and Sable types — the block entity's only job is to read the
 * vessel's state and hand over the impulse, so the arithmetic is worth pinning down
 * on its own.
 */
class GyrodyneControlTest {

    private static final double EPSILON = 1e-6d;

    /** One tick, in seconds. */
    private static final double TICK = 1d / 20d;

    private static final double BASE_TORQUE = 2000d;

    private static final GyrodyneControl.Gains GAINS = new GyrodyneControl.Gains(4000d, 900d);

    private static GyrodyneControl.Attitude attitude(Quaterniond vesselOrientation,
                                                   Quaterniond mountOrientation,
                                                   Vector3dc angularVelocityWorld) {
        return new GyrodyneControl.Attitude(vesselOrientation, mountOrientation, angularVelocityWorld);
    }

    /** Level vessel, gyrodyne bolted to the underside pointing down. */
    private static GyrodyneControl.Attitude levelShip(Vector3dc angularVelocityWorld) {
        return attitude(new Quaterniond(),
                GyrodyneControl.mountOrientation(Direction.DOWN),
                angularVelocityWorld);
    }

    private static Vector3d worldX() {
        return new Vector3d(1, 0, 0);
    }

    @Test
    void mountAxisFollowsTheMountingFace() {
        for (Direction facing : Direction.values()) {
            Vector3d axis = GyrodyneControl.mountOrientation(facing)
                    .transform(new Vector3d(0, 1, 0));
            assertEquals(facing.getStepX(), axis.x, EPSILON, "x for " + facing);
            assertEquals(facing.getStepY(), axis.y, EPSILON, "y for " + facing);
            assertEquals(facing.getStepZ(), axis.z, EPSILON, "z for " + facing);
        }
    }

    @Test
    void torqueCapacityScalesWithTheSquareRootOfMass() {
        assertEquals(BASE_TORQUE, GyrodyneControl.torqueCapacity(BASE_TORQUE, 30d), EPSILON);
        assertEquals(BASE_TORQUE, GyrodyneControl.torqueCapacity(BASE_TORQUE, 1d), EPSILON,
                "light vessels are not penalised below the reference mass");
        assertEquals(BASE_TORQUE * 2d, GyrodyneControl.torqueCapacity(BASE_TORQUE, 120d), EPSILON);
    }

    @Test
    void torqueCapacityIsZeroWithoutTorque() {
        assertEquals(0d, GyrodyneControl.torqueCapacity(0d, 500d), EPSILON);
        assertEquals(0d, GyrodyneControl.torqueCapacity(-1d, 500d), EPSILON);
    }

    @Test
    void satsStillVesselStaysStill() {
        GyrodyneControl.Response response = GyrodyneControl.dampRotation(
                levelShip(new Vector3d()), TICK, GAINS, BASE_TORQUE);
        assertFalse(response.isFiring());
        assertEquals(0d, response.angularImpulseWorld().length(), EPSILON);
        assertEquals(0d, response.tiltXDegrees(), EPSILON);
        assertEquals(0d, response.tiltZDegrees(), EPSILON);
    }

    @Test
    void sasTorquesOppositeTheCurrentRotation() {
        // Spinning about world +X while mounted on the underside, whose local +Y is
        // world -Y, so local angular velocity is (0, 0, +w) under a 180 degree flip.
        Vector3d response = GyrodyneControl.dampRotation(levelShip(worldX().mul(0.5)),
                TICK, GAINS, BASE_TORQUE).angularImpulseWorld();

        assertFalse(response.x > 0d, "must torque against the spin, not with it");
        assertEquals(0d, response.y, EPSILON);
        assertEquals(0d, response.z, EPSILON);
    }

    @Test
    void sasIsPureRateDampingAndIgnoresHeading() {
        // Same rate, two different headings: a stabiliser must react identically.
        Quaterniond rolled = new Quaterniond().rotateZ(Math.PI / 3d);
        Vector3d rate = new Vector3d(0.3d, 0.2d, -0.1d);

        Vector3d level = GyrodyneControl.dampRotation(levelShip(rate), TICK, GAINS, BASE_TORQUE)
                .angularImpulseWorld();
        Vector3d rolledShip = GyrodyneControl.dampRotation(
                attitude(rolled, GyrodyneControl.mountOrientation(Direction.DOWN), rate),
                TICK, GAINS, BASE_TORQUE).angularImpulseWorld();

        // Both are pure damping in their own mounting frames, so magnitudes match even
        // though the world-space axes differ.
        assertEquals(level.length(), rolledShip.length(), EPSILON);
    }

    @Test
    void dampingGainSetsTheTorquePerRadianPerSecond() {
        double rate = 0.5d;
        Vector3d impulse = GyrodyneControl.dampRotation(levelShip(worldX().mul(rate)),
                TICK, GAINS, BASE_TORQUE).angularImpulseWorld();

        // torque = -kd * rate, impulse = torque * dt
        assertEquals(GAINS.damping() * rate * TICK, impulse.length(), EPSILON);
    }

    @Test
    void torqueIsClampedToWhatOneWheelCanDeliver() {
        // An absurd rate would demand kd * rate = 45000 N*m, far past the 2000 limit.
        Vector3d impulse = GyrodyneControl.dampRotation(levelShip(worldX().mul(100d)),
                TICK, GAINS, BASE_TORQUE).angularImpulseWorld();

        assertEquals(BASE_TORQUE * TICK, impulse.length(), EPSILON);
    }

    @Test
    void angularImpulseScalesWithThePhysicsStep() {
        Vector3d oneTick = GyrodyneControl.dampRotation(levelShip(worldX().mul(0.5)),
                TICK, GAINS, BASE_TORQUE).angularImpulseWorld();
        Vector3d fourTicks = GyrodyneControl.dampRotation(levelShip(worldX().mul(0.5)),
                4d * TICK, GAINS, BASE_TORQUE).angularImpulseWorld();

        assertEquals(4d, fourTicks.length() / oneTick.length(), EPSILON);
    }

    @Test
    void aNonPositiveTimeStepProducesNothing() {
        assertEquals(GyrodyneControl.Response.IDLE,
                GyrodyneControl.dampRotation(levelShip(worldX()), 0d, GAINS, BASE_TORQUE));
        assertEquals(GyrodyneControl.Response.IDLE,
                GyrodyneControl.dampRotation(levelShip(worldX()), TICK, GAINS, 0d));
    }

    @Test
    void holdReturnsAVesselToTheHeldAttitude() {
        Quaterniond held = new Quaterniond();
        // Tilted 10 degrees about world Z and spinning gently about it.
        Quaterniond tilted = new Quaterniond().rotateZ(Math.toRadians(10d));

        Vector3d response = GyrodyneControl.holdOrientation(
                attitude(tilted, GyrodyneControl.mountOrientation(Direction.DOWN), new Vector3d(0, 0, 0.2)),
                held, TICK, GAINS, BASE_TORQUE).angularImpulseWorld();

        // The error is about world +Z, so the correcting torque is too, modulo the
        // 180 degree flip the underside mounting introduces about X and Y.
        assertTrue(Math.abs(response.z) > EPSILON, "expected torque about Z, got " + response);
    }

    @Test
    void holdOnTheHeldAttitudeOnlyDamps() {
        // Already sitting at the held attitude, so the proportional term is zero and
        // the rate damping term is all that is left.
        Quaterniond held = new Quaterniond();
        Vector3d rate = new Vector3d(0.1d, 0, 0);

        Vector3d response = GyrodyneControl.holdOrientation(levelShip(rate), held,
                TICK, GAINS, BASE_TORQUE).angularImpulseWorld();

        assertEquals(GAINS.damping() * rate.length() * TICK, response.length(), EPSILON);
    }

    @Test
    void holdDrivesTheVesselBackOntoTheHeldAttitude() {
        // 20 degrees off, with no spin at all: the correcting torque is proportional
        // to the angle error and nothing damps it away.
        Quaterniond held = new Quaterniond();
        Quaterniond tilted = new Quaterniond().rotateZ(Math.toRadians(20d));

        Vector3d response = GyrodyneControl.holdOrientation(
                attitude(tilted, GyrodyneControl.mountOrientation(Direction.DOWN), new Vector3d()),
                held, TICK, GAINS, BASE_TORQUE).angularImpulseWorld();

        assertEquals(Math.toRadians(20d) * GAINS.proportional() * TICK, response.length(), EPSILON);
    }

    @Test
    void aligningWithTheCurrentHeadingIsInert() {
        // Mounted on the underside, the mount axis is world -Y, so aim HORIZON's +Y at -Y.
        GyrodyneControl.Response response = GyrodyneControl.alignTo(levelShip(new Vector3d()),
                new Vector3d(0, -1, 0), TICK, GAINS, BASE_TORQUE);

        assertFalse(response.isFiring(), "already aligned, so no torque");
    }

    @Test
    void aligningToTheOppositeOfTheMountAxisTakesTheShortestArc() {
        // Underside mount points at world -Y; asking for +Y is a 180 degree flip about
        // any perpendicular axis, not a 540 degree one.
        Vector3d error = GyrodyneControl.alignmentError(new Vector3d(0, -1, 0), new Vector3d(0, 1, 0));

        assertEquals(Math.PI, error.length(), EPSILON);
        assertEquals(0d, new Vector3d(0, -1, 0).dot(error.normalize()), EPSILON,
                "the rotation axis must be perpendicular to the mount axis");
    }

    @Test
    void alignmentErrorIsPerpendicularToBothDirections() {
        Vector3d from = new Vector3d(1, 2, 3);
        Vector3d to = new Vector3d(-2, 0.5, 1);
        Vector3d error = GyrodyneControl.alignmentError(from, to);

        assertEquals(0d, from.normalize().dot(error.normalize()), EPSILON);
        assertEquals(0d, to.normalize().dot(error.normalize()), EPSILON);
    }

    @Test
    void alignmentErrorForOrthogonalDirectionsIsAHalfPiTurn() {
        Vector3d error = GyrodyneControl.alignmentError(new Vector3d(0, 1, 0), new Vector3d(1, 0, 0));
        assertEquals(Math.PI / 2d, error.length(), EPSILON);
    }

    @Test
    void undefinedDirectionsAreRejected() {
        assertEquals(0d, GyrodyneControl.alignmentError(new Vector3d(), new Vector3d(1, 0, 0)).length(), EPSILON);
        assertEquals(0d, GyrodyneControl.alignmentError(new Vector3d(0, 1, 0), new Vector3d()).length(), EPSILON);
    }

    @Test
    void alignToOrIdleSwallowsUndefinedTargets() {
        assertEquals(GyrodyneControl.Response.IDLE,
                GyrodyneControl.alignToOrIdle(levelShip(worldX()), null, TICK, GAINS, BASE_TORQUE));
        assertEquals(GyrodyneControl.Response.IDLE,
                GyrodyneControl.alignToOrIdle(levelShip(worldX()), new Vector3d(), TICK, GAINS, BASE_TORQUE));
    }

    @Test
    void alignToOrIdleStillWorksForRealTargets() {
        assertTrue(GyrodyneControl.alignToOrIdle(levelShip(new Vector3d()),
                new Vector3d(1, 0, 0), TICK, GAINS, BASE_TORQUE).isFiring());
    }

    @Test
    void rotationVectorRoundTripsThroughAQuaternion() {
        Quaterniond rotation = new Quaterniond()
                .rotateX(0.7d)
                .rotateY(-0.3d)
                .rotateZ(1.2d);

        Vector3d vector = GyrodyneControl.rotationVector(rotation);

        // Rebuild the quaternion the way rotation vector is defined: axis sin(a/2),
        // scalar cos(a/2).
        double angle = vector.length();
        Vector3d axis = new Vector3d(vector).div(angle);
        Quaterniond asQuaternion = new Quaterniond(
                axis.x * Math.sin(angle / 2d),
                axis.y * Math.sin(angle / 2d),
                axis.z * Math.sin(angle / 2d),
                Math.cos(angle / 2d));

        assertEquals(0d, vector.sub(GyrodyneControl.rotationVector(asQuaternion)).length(), EPSILON);
    }

    @Test
    void rotationVectorOfIdentityIsZero() {
        assertEquals(0d, GyrodyneControl.rotationVector(new Quaterniond()).length(), EPSILON);
    }

    @Test
    void rotationVectorTakesTheShortestArc() {
        // -q and q are the same rotation, so both must map to the same short vector.
        Quaterniond q = new Quaterniond().rotateY(0.4d);
        Quaterniond negated = new Quaterniond(-q.x(), -q.y(), -q.z(), -q.w());

        assertEquals(GyrodyneControl.rotationVector(q), GyrodyneControl.rotationVector(negated));
        assertEquals(0.4d, GyrodyneControl.rotationVector(q).length(), EPSILON);
    }

    @Test
    void rotationVectorOfAHalfTurnIsAPiRadianAxis() {
        Vector3d vector = GyrodyneControl.rotationVector(new Quaterniond().rotateX(Math.PI));

        assertEquals(Math.PI, vector.length(), EPSILON);
        assertEquals(Math.PI, Math.abs(vector.x()), EPSILON,
                "a half turn about X is a rotation vector of pi radians along X");
    }

    @Test
    void angularVelocityBetweenIsTheRateOfChangeOfOrientation() {
        Quaterniond from = new Quaterniond();
        Quaterniond to = new Quaterniond().rotateY(0.5d);

        Vector3d rate = GyrodyneControl.angularVelocityBetween(from, to, 0.5d);

        assertEquals(1d, rate.length(), EPSILON, "0.5 rad in 0.5 s is 1 rad/s");
        assertEquals(0d, rate.x(), EPSILON);
        assertEquals(1d, rate.y(), EPSILON);
        assertEquals(0d, rate.z(), EPSILON);
    }

    @Test
    void angularVelocityBetweenRejectsNonPositiveElapsedTime() {
        assertEquals(0d, GyrodyneControl.angularVelocityBetween(
                new Quaterniond(), new Quaterniond().rotateY(1d), 0d).length(), EPSILON);
    }

    @Test
    void tiltSaturatesAtTheAdvertisedAngle() {
        GyrodyneControl.Response response = GyrodyneControl.dampRotation(
                levelShip(new Vector3d(0, 0, 1000d)), TICK, GAINS, BASE_TORQUE);

        assertEquals(GyrodyneControl.MAX_TILT_DEGREES, Math.abs(response.tiltXDegrees()), EPSILON,
                "a saturated wheel should read full deflection");
        assertTrue(response.isFiring());
    }

    @Test
    void tiltIsProportionalToTheCommandedTorque() {
        Function<Double, Double> tiltAtRate = rate -> GyrodyneControl.dampRotation(
                levelShip(worldX().mul(rate)), TICK, GAINS, BASE_TORQUE).tiltZDegrees();

        // Half the rate commands half the torque, and the tilt follows it linearly.
        assertEquals(tiltAtRate.apply(2d) / 2d, tiltAtRate.apply(1d), EPSILON);
    }

    @Test
    void twoWheelsOnOppositeFacesDoNotFightEachOther() {
        // Opposed mounts with the same gains must produce world-space torques that
        // cancel, which is what lets a player mount wheels wherever there is room.
        Quaterniond up = GyrodyneControl.mountOrientation(Direction.UP);
        Quaterniond down = GyrodyneControl.mountOrientation(Direction.DOWN);
        Vector3d rate = new Vector3d(0.1d, 0.2d, 0.3d);

        Vector3d a = GyrodyneControl.dampRotation(attitude(new Quaterniond(), up, rate),
                TICK, GAINS, BASE_TORQUE).angularImpulseWorld();
        Vector3d b = GyrodyneControl.dampRotation(attitude(new Quaterniond(), down, rate),
                TICK, GAINS, BASE_TORQUE).angularImpulseWorld();

        // Each wheel resists the rotation in its own mounting frame, so both push the
        // same way in world space and add rather than cancel, at equal magnitude.
        assertEquals(a.length(), b.length(), EPSILON);
        assertNotEquals(0d, a.add(b).length(), EPSILON);
    }
}