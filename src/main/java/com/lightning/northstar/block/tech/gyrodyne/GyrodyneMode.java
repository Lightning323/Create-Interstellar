package com.lightning.northstar.block.tech.gyrodyne;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.StringRepresentable;

import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.Nullable;

/**
 * What a {@link GyrodyneBlockEntity} is trying to do with its reaction wheel.
 *
 * <p>The modes split into two families. {@link #SAS} only damps rotation, so it is
 * safe to leave on all flight. The rest name a direction or an attitude to hold and
 * are resolved against the vessel's own state; see
 * {@link GyrodyneBlockEntity#sable$physicsTick}.
 */
@ParametersAreNonnullByDefault
public enum GyrodyneMode implements StringRepresentable {

    /**
     * Disabled. The wheel spins down and applies nothing.
     */
    OFF("off"),

    /**
     * Stabilisation: kills rotation without caring about heading. This is attitude
     * hold in the everyday sense — the vessel stops tumbling but keeps pointing
     * wherever it happened to be pointing.
     */
    SAS("sas"),

    /**
     * Holds the orientation captured when the mode was selected. Switch to another
     * mode and back to re-capture, which is how a pilot re-levels a ship.
     */
    HOLD("hold"),

    /**
     * Points the mounting axis along the vessel's velocity.
     */
    PROGRADE("prograde"),

    /**
     * Points the mounting axis against the vessel's velocity.
     */
    RETROGRADE("retrograde"),

    /**
     * Points perpendicular to both velocity and world up.
     */
    NORMAL("normal"),

    /**
     * The opposite of {@link #NORMAL}.
     */
    ANTINORMAL("antinormal"),

    /**
     * Points at the centre of the world, which for a planet is straight down.
     */
    RADIAL_IN("radial_in"),

    /**
     * Points away from the centre of the world.
     */
    RADIAL_OUT("radial_out"),

    /**
     * Levels the mounting axis with the horizontal plane.
     */
    HORIZON("horizon"),

    /**
     * Points at the world's sun.
     */
    SUN("sun");

    private final String name;

    GyrodyneMode(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    /**
     * Translation key for the mode's display name, shown in the scroll value box.
     */
    public String getTranslationKey() {
        return "northstar.gui.gyrodyne.mode." + name;
    }

    public MutableComponent getComponent() {
        return Component.translatable(getTranslationKey());
    }

    /**
     * True for the modes that name a direction rather than only damping rotation.
     * Those need a reference vector, which the block entity resolves per vessel.
     */
    public boolean isDirectional() {
        return switch (this) {
            case SAS, HOLD, OFF -> false;
            default -> true;
        };
    }

    /**
     * Looks up a mode by index, falling back to {@link #OFF} for anything out of
     * range. The index comes from a scroll value, so a stale saved value must not
     * throw.
     */
    public static GyrodyneMode byIndex(int index) {
        GyrodyneMode[] values = values();
        return index >= 0 && index < values.length ? values[index] : OFF;
    }

    @Nullable
    public static GyrodyneMode byName(@Nullable String name) {
        for (GyrodyneMode mode : values()) {
            if (mode.name.equals(name)) {
                return mode;
            }
        }
        return null;
    }
}