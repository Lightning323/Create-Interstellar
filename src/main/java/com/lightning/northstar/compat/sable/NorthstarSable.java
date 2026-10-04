package com.lightning.northstar.compat.sable;

import com.lightning.northstar.world.sealer.transform.TransformProviders;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

/**
 * Northstar's single entry point into Sable.
 *
 * <p>Everything the mod needs from the physics library goes through here, so there
 * is one place that knows how to find a sublevel and one place that knows which
 * Sable version this code was written against.
 */
public final class NorthstarSable {

    /**
     * The Sable version whose API and internals this code targets.
     *
     * <p>Sable exposes {@code MixinModVersionConstraint} for gating mixins that
     * reach into its internals. See {@code ServerBoundPunchSubLevelPacketMixin}.
     */
    public static final String TARGET_SABLE_VERSION = "2.0.3";

    /**
     * Cache of {@link RigidBodyHandle}s, keyed weakly by sublevel so a vessel that
     * is destroyed does not pin its handle in memory.
     *
     * <p>{@code RigidBodyHandle.of} is not free and the handle for a given sublevel
     * never changes, so actors on the same vessel should share one.
     */
    private static final Map<ServerSubLevel, RigidBodyHandle> HANDLES = Collections.synchronizedMap(
            new WeakHashMap<>());

    private NorthstarSable() {
    }

    public static void init() {
        TransformProviders.registerToWorld((level, pos) -> {
            SubLevelAccess subLevel = SableCompanion.INSTANCE.getContaining(level, pos);
            return subLevel == null ? null : subLevel.logicalPose().transformPosition(pos);
        });
        TransformProviders.registerFromWorld((level, pos) -> {
            SubLevelAccess subLevel = SableCompanion.INSTANCE.getContaining(level, pos);
            return (l, p) -> subLevel == null ? null : subLevel.logicalPose().transformPositionInverse(p);
        });
    }

    /**
     * The rigid body for a sublevel, cached so that every actor aboard the same
     * vessel resolves the same handle instance.
     */
    public static RigidBodyHandle handleFor(ServerSubLevel subLevel) {
        return HANDLES.computeIfAbsent(subLevel, RigidBodyHandle::of);
    }

    /**
     * Drops the cached handle for a sublevel. Called when a vessel is removed, so a
     * long session does not accumulate entries for hulls that are gone.
     */
    public static void forget(ServerSubLevel subLevel) {
        HANDLES.remove(subLevel);
    }

    /**
     * Everything an actor needs to know about the vessel it belongs to for one
     * physics step, read once.
     *
     * <p>Pose and mass are the same for every block entity on a vessel, so reading
     * them once per step rather than once per actor keeps the cost of a large ship
     * proportional to its block count instead of its actor count times its
     * component count.
     *
     * @param subLevel    the vessel
     * @param body        its rigid body
     * @param orientation vessel orientation, local to world
     * @param position    vessel origin in world space
     * @param massKg      vessel mass in kilograms, 0 if unknown
     */
    public record VesselFrame(ServerSubLevel subLevel, RigidBodyHandle body,
                              Quaterniond orientation, Vector3d position, double massKg) {

        /**
         * Whether the vessel has a usable mass. A zero or negative mass means the
         * mass tracker has not been built, in which case no impulse may be applied.
         */
        public boolean hasMass() {
            return massKg > 0d;
        }

        public boolean isUsable() {
            return body != null && body.isValid() && hasMass();
        }
    }

    /**
     * Snapshots the vessel's state for this step.
     *
     * @param subLevel the vessel
     * @param body     its rigid body, as handed to {@code sable$physicsTick}
     */
    public static VesselFrame frame(ServerSubLevel subLevel, @Nullable RigidBodyHandle body) {
        Quaterniond orientation = new Quaterniond(subLevel.logicalPose().orientation());
        Vector3d position = new Vector3d(subLevel.logicalPose().position());
        double mass = subLevel.getMassTracker() == null ? 0d : subLevel.getMassTracker().getMass();
        return new VesselFrame(subLevel, body, orientation, position, mass);
    }
}