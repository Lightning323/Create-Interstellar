package com.lightning.northstar.physics;

import com.lightning.northstar.contraption.rocket.LaunchStatus;
import com.lightning.northstar.contraption.rocket.RocketDestination;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

/**
 * Flight state for one vessel, independent of how it is simulated.
 *
 * <p>This used to live inside the contraption, where it was reachable only through
 * the flight entity. Under rigid-body physics the vessel <em>is</em> a Sable
 * sublevel, so this is the authoritative record of what the ship is doing and it
 * survives the sublevel being unloaded, warped between dimensions, or rebuilt.
 *
 * <p>Holds no world state and no Sable types, so the launch rules can be tested
 * without a running level.
 */
@ParametersAreNonnullByDefault
public class RocketShipState {

    /** Seconds of countdown between arming and launch. */
    public static final double COUNTDOWN_SECONDS = 3d;

    /** Rate at which commanded attitude changes, in units per second. */
    public static final double SLEW_RATE = 0.6d;

    private RocketDestination destination;
    private RocketDestination origin;

    private LaunchStatus phase = LaunchStatus.WAITING;

    /** Commanded throttle, 0..1. */
    private float throttle;

    /** Commanded attitude rates, each -1..1. */
    private float pitch;
    private float yaw;
    private float roll;

    /** Propellant energy remaining across the vessel, in GJ. */
    private double fuelRemainingGj;

    /** Set once the launch rules have been satisfied and the countdown may run. */
    private boolean armed;

    public RocketDestination destination() {
        return destination;
    }

    public RocketDestination origin() {
        return origin;
    }

    public void setDestination(@Nullable RocketDestination destination) {
        this.destination = destination;
    }

    public void setOrigin(@Nullable RocketDestination origin) {
        this.origin = origin;
    }

    public LaunchStatus phase() {
        return phase;
    }

    public void setPhase(LaunchStatus phase) {
        this.phase = phase;
    }

    public float throttle() {
        return throttle;
    }

    /**
     * Clamps the throttle to a sane range, so a packet or a slider cannot command
     * a negative or super-thrust burn.
     */
    public void setThrottle(float throttle) {
        this.throttle = Math.max(0f, Math.min(1f, throttle));
        if (this.throttle > 0f) {
            phase = LaunchStatus.ASCENDING;
        } else if (phase == LaunchStatus.ASCENDING) {
            phase = LaunchStatus.WAITING;
        }
    }

    public float pitch() {
        return pitch;
    }

    public float yaw() {
        return yaw;
    }

    public float roll() {
        return roll;
    }

    /**
     * Sets the commanded attitude rates, clamping each to -1..1.
     */
    public void setAttitude(float pitch, float yaw, float roll) {
        this.pitch = clampRate(pitch);
        this.yaw = clampRate(yaw);
        this.roll = clampRate(roll);
    }

    /**
     * Zeroes the attitude command. Called when the controls are released so a
     * stuck key cannot keep a ship rotating after the pilot lets go.
     */
    public void clearAttitude() {
        pitch = 0f;
        yaw = 0f;
        roll = 0f;
    }

    public boolean isArmed() {
        return armed;
    }

    public void setArmed(boolean armed) {
        this.armed = armed;
    }

    public double fuelRemainingGj() {
        return fuelRemainingGj;
    }

    public void setFuelRemainingGj(double fuelRemainingGj) {
        this.fuelRemainingGj = Math.max(0d, fuelRemainingGj);
    }

    /**
     * Whether the pilot is asking for anything at all this tick.
     */
    public boolean hasCommand() {
        return throttle > 0f || pitch != 0f || yaw != 0f || roll != 0f;
    }

    /**
     * Advances the launch state machine by one server tick.
     *
     * <p>Under rigid-body physics this does not integrate anything: Rapier owns
     * the vessel's motion. It only decides <em>when</em> thrust is allowed to be
     * commanded, which is what the old entity used to gate on its own integrator.
     *
     * @param ticksSinceArming ticks elapsed since the vessel became armed
     * @return the phase after this tick
     */
    public LaunchStatus tickLaunch(int ticksSinceArming) {
        if (phase == LaunchStatus.WAITING) {
            return phase;
        }
        if (phase == LaunchStatus.COUNTDOWN) {
            if (!armed) {
                phase = LaunchStatus.WAITING;
            } else if (ticksSinceArming >= COUNTDOWN_TICKS) {
                phase = LaunchStatus.ASCENDING;
            }
        }
        return phase;
    }

    /** Countdown length in server ticks, derived from {@link #COUNTDOWN_SECONDS}. */
    public static final int COUNTDOWN_TICKS = (int) (COUNTDOWN_SECONDS * 20d);

    /**
     * Clears anything that would keep a landed ship from being re-armed.
     */
    public void reset() {
        phase = LaunchStatus.WAITING;
        throttle = 0f;
        clearAttitude();
        armed = false;
    }

    private static float clampRate(float rate) {
        if (Float.isNaN(rate)) {
            return 0f;
        }
        return Math.max(-1f, Math.min(1f, rate));
    }

    public void write(CompoundTag tag) {
        tag.putFloat("Throttle", throttle);
        tag.putFloat("Pitch", pitch);
        tag.putFloat("Yaw", yaw);
        tag.putFloat("Roll", roll);
        tag.putDouble("FuelRemaining", fuelRemainingGj);
        tag.putBoolean("Armed", armed);
        tag.putString("Phase", phase.name());
        if (destination != null) {
            tag.put("Destination", destination.toTag());
        }
        if (origin != null) {
            tag.put("Origin", origin.toTag());
        }
    }

    public void read(CompoundTag tag) {
        throttle = Math.max(0f, Math.min(1f, tag.getFloat("Throttle")));
        pitch = clampRate(tag.getFloat("Pitch"));
        yaw = clampRate(tag.getFloat("Yaw"));
        roll = clampRate(tag.getFloat("Roll"));
        fuelRemainingGj = Math.max(0d, tag.getDouble("FuelRemaining"));
        armed = tag.getBoolean("Armed");
        phase = readPhase(tag.getString("Phase"));
        // Guarded so that reading a state with no waypoint never touches the
        // destination codecs at all.
        destination = tag.contains("Destination", Tag.TAG_COMPOUND)
                ? RocketDestination.fromTag(tag.getCompound("Destination"))
                : null;
        origin = tag.contains("Origin", Tag.TAG_COMPOUND)
                ? RocketDestination.fromTag(tag.getCompound("Origin"))
                : null;
    }

    private static LaunchStatus readPhase(String name) {
        try {
            return LaunchStatus.valueOf(name);
        } catch (IllegalArgumentException e) {
            // A tag written by an older version, or corrupted. Waiting is the safe
            // fallback: it requires an explicit launch rather than flying on load.
            return LaunchStatus.WAITING;
        }
    }
}
