package com.lightning.northstar.physics;

import com.lightning.northstar.contraption.rocket.LaunchStatus;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flight state is the authoritative record of what a ship is doing, and it outlives
 * the sublevel it belongs to. These tests pin the clamping and the NBT round trip,
 * because a bad tag read must never fly a ship on its own.
 */
class RocketShipStateTest {

    private static final float EPSILON = 1e-6f;

    @Test
    void throttleIsClampedToUnitRange() {
        RocketShipState state = new RocketShipState();

        state.setThrottle(0.5f);
        assertEquals(0.5f, state.throttle(), EPSILON);

        state.setThrottle(-3f);
        assertEquals(0f, state.throttle(), EPSILON);

        state.setThrottle(7f);
        assertEquals(1f, state.throttle(), EPSILON);
    }

    @Test
    void attitudeRatesAreClampedAndRejectNaN() {
        RocketShipState state = new RocketShipState();

        state.setAttitude(2f, -9f, 0.25f);
        assertEquals(1f, state.pitch(), EPSILON);
        assertEquals(-1f, state.yaw(), EPSILON);
        assertEquals(0.25f, state.roll(), EPSILON);

        // A NaN reaching here would propagate into every thruster demand and quietly
        // stop the ship responding, so it is treated as "no input".
        state.setAttitude(Float.NaN, Float.NaN, Float.NaN);
        assertEquals(0f, state.pitch(), EPSILON);
        assertEquals(0f, state.yaw(), EPSILON);
        assertEquals(0f, state.roll(), EPSILON);
    }

    @Test
    void releasingTheControlsStopsTheShipRotating() {
        RocketShipState state = new RocketShipState();
        state.setAttitude(1f, 1f, 1f);
        assertTrue(state.hasCommand());

        state.clearAttitude();
        assertFalse(state.hasCommand());
    }

    @Test
    void fuelNeverGoesNegative() {
        RocketShipState state = new RocketShipState();

        state.setFuelRemainingGj(-100d);
        assertEquals(0d, state.fuelRemainingGj());

        state.setFuelRemainingGj(42d);
        assertEquals(42d, state.fuelRemainingGj(), 1e-9d);
    }

    @Test
    void countdownOnlyRunsWhileArmed() {
        RocketShipState state = new RocketShipState();
        state.setPhase(LaunchStatus.COUNTDOWN);

        // Not armed: the countdown cannot start, and reaching for the launch button
        // after the fact must not skip the wait.
        state.setArmed(false);
        assertEquals(LaunchStatus.WAITING, state.tickLaunch(RocketShipState.COUNTDOWN_TICKS + 10));

        state.setPhase(LaunchStatus.COUNTDOWN);
        state.setArmed(true);
        assertEquals(LaunchStatus.COUNTDOWN,
                state.tickLaunch(RocketShipState.COUNTDOWN_TICKS - 1),
                "still counting down one tick early");
        assertEquals(LaunchStatus.ASCENDING, state.tickLaunch(RocketShipState.COUNTDOWN_TICKS));
    }

    @Test
    void aWaitingShipIgnoresTheCountdown() {
        RocketShipState state = new RocketShipState();
        state.setArmed(true);

        assertEquals(LaunchStatus.WAITING, state.tickLaunch(1_000));
    }

    @Test
    void resetClearsEverythingNeededToRelaunch() {
        RocketShipState state = new RocketShipState();
        state.setPhase(LaunchStatus.ASCENDING);
        state.setThrottle(1f);
        state.setAttitude(1f, 0f, 0f);
        state.setArmed(true);

        state.reset();

        assertEquals(LaunchStatus.WAITING, state.phase());
        assertEquals(0f, state.throttle(), EPSILON);
        assertFalse(state.hasCommand());
        assertFalse(state.isArmed());
    }

    @Test
    void survivesAnNbtRoundTrip() {
        RocketShipState state = new RocketShipState();
        state.setThrottle(0.75f);
        state.setAttitude(0.5f, -0.25f, 1f);
        state.setFuelRemainingGj(1234.5d);
        state.setArmed(true);
        state.setPhase(LaunchStatus.ASCENDING);

        CompoundTag tag = new CompoundTag();
        state.write(tag);

        RocketShipState restored = new RocketShipState();
        restored.read(tag);

        assertEquals(0.75f, restored.throttle(), EPSILON);
        assertEquals(0.5f, restored.pitch(), EPSILON);
        assertEquals(-0.25f, restored.yaw(), EPSILON);
        assertEquals(1f, restored.roll(), EPSILON);
        assertEquals(1234.5d, restored.fuelRemainingGj(), 1e-9d);
        assertTrue(restored.isArmed());
        assertEquals(LaunchStatus.ASCENDING, restored.phase());
        assertNull(restored.destination(), "no waypoint was written, so none is restored");
    }

    @Test
    void anUnknownPhaseFallsBackToWaitingRatherThanFlying() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Phase", "SOMETHING_FROM_A_NEWER_VERSION");

        RocketShipState state = new RocketShipState();
        state.read(tag);

        assertEquals(LaunchStatus.WAITING, state.phase(),
                "a ship must never launch itself because of a tag it did not write");
    }

    @Test
    void outOfRangeValuesInATagAreRepairedOnRead() {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("Throttle", 99f);
        tag.putFloat("Pitch", -99f);
        tag.putDouble("FuelRemaining", -5d);

        RocketShipState state = new RocketShipState();
        state.read(tag);

        assertEquals(1f, state.throttle(), EPSILON);
        assertEquals(-1f, state.pitch(), EPSILON);
        assertEquals(0d, state.fuelRemainingGj());
    }
}