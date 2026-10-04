package com.lightning.northstar.physics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The old flight model could only fly straight up. These tests pin the replacement:
 * a pilot's pitch/yaw/roll command has to reach the thrusters that can actually
 * produce that moment, and must not reach the ones that would rotate the ship the
 * wrong way.
 */
class RocketFlightControlTest {

    private static final float EPSILON = 1e-6f;

    private static final RocketFlightControl.Axes LEVEL = RocketFlightControl.Axes.LEVEL;

    private static final RocketFlightControl.Command NEUTRAL =
            new RocketFlightControl.Command(1f, 0f, 0f, 0f);

    private static final Vec3 CENTRE = Vec3.ZERO;

    private static RocketFlightControl.Thruster underside(float throttle) {
        return RocketFlightControl.Thruster.of(Direction.DOWN, Vec3.atCenterOf(BlockPos.ZERO), throttle);
    }

    private static RocketFlightControl.Command command(float pitch, float yaw, float roll) {
        return new RocketFlightControl.Command(1f, pitch, yaw, roll);
    }

    @Test
    void baseThrottleIsTheFloorWithNoCommand() {
        RocketFlightControl.Demand demand = RocketFlightControl.resolve(
                underside(0.5f), NEUTRAL, LEVEL, CENTRE, 1d);

        assertEquals(0.5f, demand.throttle(), EPSILON);
    }

    @Test
    void anUncommandedThrusterNeverFires() {
        assertFalse(RocketFlightControl.resolve(underside(0f), command(1f, 0f, 0f), LEVEL, CENTRE, 1d)
                .isFiring());
    }

    @Test
    void steeringSaturatesAtFullThrottleRatherThanOvercommitting() {
        // An absurd command must not ask a thruster for more than it can deliver.
        RocketFlightControl.Demand demand = RocketFlightControl.resolve(
                underside(1f), command(50f, 0f, 0f), LEVEL, CENTRE, 1d);

        assertTrue(demand.throttle() <= 1f, "throttle was " + demand.throttle());
    }

    @Test
    void steeringIsDifferentialThrustRatherThanOnOrOff() {
        // A thruster on its own cannot be steered on or off, because the same thruster
        // also has to push the ship forward. Steering has to work by raising the ones
        // that help the command and lowering the ones that oppose it.
        RocketFlightControl.Thruster thruster = underside(1f);

        float neutral = RocketFlightControl.resolve(thruster, NEUTRAL, LEVEL, CENTRE, 1d).throttle();
        float pitchUp = RocketFlightControl.resolve(thruster, command(1f, 0f, 0f), LEVEL, CENTRE, 1d).throttle();
        float pitchDown = RocketFlightControl.resolve(thruster, command(-1f, 0f, 0f), LEVEL, CENTRE, 1d).throttle();

        assertTrue(pitchUp >= neutral - EPSILON,
                "a thruster helping the command must not be throttled down");
        assertTrue(pitchDown < neutral,
                "a thruster opposing the command must be throttled down, was " + pitchDown);
    }

    @Test
    void anOpposedPairRotatesTheShipWithoutEitherThrusterStopping() {
        // This is the load bearing test: belly and roof have opposite pitch moments,
        // so a pitch command must produce a net moment even though both thrusters
        // stay lit. If this regresses the ship can only fly straight up again.
        RocketFlightControl.Thruster belly0 = underside(0.5f);
        RocketFlightControl.Thruster roof0 = RocketFlightControl.Thruster.of(
                Direction.UP, Vec3.atCenterOf(BlockPos.ZERO), 0.5f);

        RocketFlightControl.Command pitchUp = command(1f, 0f, 0f);

        // A gain low enough that neither thruster is driven to a stop, so the whole
        // redistribution is visible.
        double gain = 10d;
        double belly = RocketFlightControl.authorityAbout(
                List.of(belly0), pitchUp, LEVEL, CENTRE, 1000d, LEVEL.pitchAxis(), gain);
        double roof = RocketFlightControl.authorityAbout(
                List.of(roof0), pitchUp, LEVEL, CENTRE, 1000d, LEVEL.pitchAxis(), gain);
        double paired = RocketFlightControl.authorityAbout(
                List.of(belly0, roof0), pitchUp, LEVEL, CENTRE, 1000d, LEVEL.pitchAxis(), gain);

        assertTrue(belly > 0d, "belly thruster drives the commanded pitch");
        assertTrue(roof < 0d, "roof thruster still pushes the other way");
        assertTrue(paired > 0d, "the pair must produce a net pitch moment, was " + paired);
        assertTrue(paired < belly, "the net must be smaller than either thruster alone");
        assertEquals(belly + roof, paired, 1e-6d, "moments must add linearly across the pair");
    }

    @Test
    void anAsymmetricShipRotatesTowardItsHeavySide() {
        // Only a belly thruster: it can pitch the nose up, and no amount of stick can
        // pitch it back down because nothing pushes from above.
        RocketFlightControl.Thruster belly = underside(0.5f);
        Vector3d axis = LEVEL.pitchAxis();

        double up = RocketFlightControl.authorityAbout(
                List.of(belly), command(1f, 0f, 0f), LEVEL, CENTRE, 1000d, axis);
        double down = RocketFlightControl.authorityAbout(
                List.of(belly), command(-1f, 0f, 0f), LEVEL, CENTRE, 1000d, axis);

        assertTrue(up > 0d, "can pitch nose up");
        assertEquals(0d, down, 1e-9d,
                "a one sided ship has no authority to pitch back down");
    }

    @Test
    void anOverwhelmingOpposingDemandStillStopsTheThruster() {
        // Saturating the differential must be able to shut a thruster right off,
        // otherwise a ship can never give up thrust to brake.
        RocketFlightControl.Thruster thruster = underside(1f);

        RocketFlightControl.Demand demand = RocketFlightControl.resolve(
                thruster, command(-1f, 0f, 0f), LEVEL, CENTRE, 0.1d);

        assertEquals(0f, demand.throttle(), EPSILON);
    }

    @Test
    void aThrusterOnTheCentreLinePushesButCannotSteer() {
        // Nozzle exactly at the centre of mass: the lever arm is zero, so the moment
        // is zero and steering has nothing to work with.
        RocketFlightControl.Thruster centred = new RocketFlightControl.Thruster(
                new Vec3(0d, 0d, 0d), new Vector3d(0, -1, 0), 1f);

        RocketFlightControl.Demand demand = RocketFlightControl.resolve(
                centred, command(1f, 1f, 1f), LEVEL, CENTRE, 1d);

        assertEquals(0d, Math.abs(demand.pitch()) + Math.abs(demand.yaw()) + Math.abs(demand.roll()), EPSILON);
        assertEquals(1f, demand.throttle(), EPSILON, "it still pushes straight forward");
    }

    @Test
    void anOpposedPairProducesOppositeMoments() {
        // One thruster on the belly, one on the roof, both firing. Their moments
        // about the pitch axis must be equal and opposite, which is what lets a
        // player mount wheels and thrusters wherever there is room.
        RocketFlightControl.Thruster belly = underside(1f);
        RocketFlightControl.Thruster roof = RocketFlightControl.Thruster.of(
                Direction.UP, Vec3.atCenterOf(BlockPos.ZERO), 1f);

        RocketFlightControl.Demand bellyDemand = RocketFlightControl.resolve(
                belly, command(1f, 0f, 0f), LEVEL, CENTRE, 1d);
        RocketFlightControl.Demand roofDemand = RocketFlightControl.resolve(
                roof, command(1f, 0f, 0f), LEVEL, CENTRE, 1d);

        assertEquals(bellyDemand.pitch(), -roofDemand.pitch(), 1e-9d,
                "opposed thrusters must oppose each other's moment");
    }

    @Test
    void steeringAuthorityGrowsWithThrottledThrusters() {
        RocketFlightControl.Command pitch = command(1f, 0f, 0f);
        RocketFlightControl.Thruster thruster = underside(1f);

        double oneThruster = RocketFlightControl.totalMoment(
                List.of(thruster), pitch, LEVEL, CENTRE, 1000d).length();
        double threeThrusters = RocketFlightControl.totalMoment(
                List.of(thruster, thruster, thruster), pitch, LEVEL, CENTRE, 1000d).length();

        assertEquals(3d * oneThruster, threeThrusters, 1e-6d);
    }

    @Test
    void anUnsteerableVesselReportsNoAuthority() {
        // Every thruster on the centre line: it can translate but never rotate.
        RocketFlightControl.Thruster centred = new RocketFlightControl.Thruster(
                new Vec3(0d, 0d, 0d), new Vector3d(0, -1, 0), 1f);

        for (Vector3dc axis : List.of(LEVEL.pitchAxis(), LEVEL.yawAxis(), LEVEL.rollAxis())) {
            assertEquals(0d, RocketFlightControl.authorityAbout(
                    List.of(centred), command(1f, 1f, 1f), LEVEL, CENTRE, 1000d, axis), 1e-9d);
        }
    }

    @Test
    void zeroGainProducesNoThrustRatherThanDivideByZero() {
        RocketFlightControl.Demand demand = RocketFlightControl.resolve(
                underside(1f), command(1f, 0f, 0f), LEVEL, CENTRE, 0d);

        assertFalse(demand.isFiring());
    }

    @Test
    void thrusterBaseThrottleIsClampedOnConstruction() {
        assertEquals(1f, underside(5f).baseThrottle(), EPSILON);
        assertEquals(0f, underside(-5f).baseThrottle(), EPSILON);
    }
}