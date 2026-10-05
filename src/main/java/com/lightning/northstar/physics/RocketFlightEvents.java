package com.lightning.northstar.physics;

import com.lightning.northstar.Northstar;
import com.lightning.northstar.accessor.NorthstarLevel;
import com.lightning.northstar.config.NorthstarConfigs;
import com.lightning.northstar.contraption.rocket.LaunchStatus;
import com.lightning.northstar.contraption.rocket.RocketDestination;
import com.lightning.northstar.contraption.rocket.packet.RocketSyncPacket;
import com.lightning.northstar.planet.data.PlanetDimension;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;
import java.util.UUID;

/** Server lifecycle for rocket sublevels: dimension handoff and lightweight HUD snapshots. */
@EventBusSubscriber(modid = Northstar.MOD_ID)
public final class RocketFlightEvents {

    /**
     * Ticks to wait before retrying a transfer that was rejected.
     *
     * <p>Working out a transfer reads the whole hull, which is far too expensive to repeat
     * every tick while a rocket is climbing past its boundary and cannot make it across.
     * A failed attempt therefore backs off, and the reason is logged so the cause is never
     * silent again.
     */
    private static final int RETRY_COOLDOWN_TICKS = 20;

    private static final Object2LongOpenHashMap<UUID> RETRY_AFTER = new Object2LongOpenHashMap<>();

    private static long tick;

    private RocketFlightEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Pre event) {
        sweep(event.getServer(), true);
    }

    /**
     * Runs the handoff again after entity ticks.
     *
     * <p>A vessel usually crosses its boundary part way through the entity phase, which is
     * <em>after</em> the pre-tick sweep has already read its height. Without this second
     * sweep the transfer would only happen on the following tick, leaving a window in which
     * the rocket is above its boundary but still in the dimension it left.
     */
    @SubscribeEvent
    public static void onServerTickEnd(ServerTickEvent.Post event) {
        sweep(event.getServer(), false);
    }

    private static void sweep(MinecraftServer server, boolean sendHud) {
        boolean broadcastHud = sendHud && ++tick % 5L == 0L;
        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer container = (ServerSubLevelContainer) SubLevelContainer.getContainer(level);
            if (container == null) continue;
            for (ServerSubLevel subLevel : List.copyOf(container.getAllSubLevels())) {
                if (!RocketSublevelState.isRocket(subLevel)) continue;
                RocketShipState state = RocketSublevelState.get(subLevel);
                if (state.phase() == LaunchStatus.ASCENDING) {
                    tryDimensionTransition(subLevel, state);
                }
                if (!broadcastHud) continue;

                RocketSyncPacket packet = RocketSyncPacket.of(subLevel, state);
                for (UUID playerId : subLevel.getTrackingPlayers()) {
                    if (level.getPlayerByUUID(playerId) instanceof ServerPlayer player) {
                        CatnipServices.NETWORK.sendToClient(player, packet);
                    }
                }
            }
        }
    }

    private static void tryDimensionTransition(ServerSubLevel subLevel, RocketShipState state) {
        RocketDestination destination = state.destination();
        if (!NorthstarConfigs.server().allowDimensionTraversal.get() || destination == null) return;

        ServerLevel source = subLevel.getLevel();
        ServerLevel target = source.getServer().getLevel(destination.dimKey());
        if (target == null) {
            Northstar.LOGGER.warn("Rocket is aimed at {}, which is not loaded", destination.dim());
            return;
        }

        double y = subLevel.logicalPose().position().y();
        boolean ascending = y > source.getMaxBuildHeight() + NorthstarConfigs.server().getCombinedTeleportHeight();
        boolean descending = y < source.getMinBuildHeight() + 20;
        if (!ascending && !descending) return;

        PlanetDimension dimension = ((NorthstarLevel) source).northstar$dimension();
        ResourceKey<Level> above = dimension == null ? null : dimension.dimensionAbove();
        ResourceKey<Level> below = dimension == null ? null : dimension.dimensionBelow();
        ResourceKey<Level> wanted = destination.dimKey();
        // A waypoint left over from an earlier leg must not drag the vessel back out of the
        // dimension it is already in, so the target has to be the one the rocket is flying
        // towards before anything is transferred.
        if (ascending ? !wanted.equals(above) : !wanted.equals(below)) {
            RETRY_AFTER.remove(subLevel.getUniqueId());
            return;
        }
        if (target == source && destination.pos() == null) return;

        UUID id = subLevel.getUniqueId();
        if (tick < RETRY_AFTER.get(id)) return;

        BlockPos targetAnchor = destination.pos();
if (targetAnchor == null) {
            // A sub-level's own pose sits inside the plotgrid, around block 20 million, so
            // its X and Z are no use as a destination: the hull would be staged in the grid
            // and the client would throw the whole region away. Arrive above the launch
            // site's own coordinates instead, which is where the crew expects to be.
            targetAnchor = BlockPos.containing(subLevel.getPlot().plotPos.x, y, subLevel.getPlot().plotPos.z)
                    .atY(target.getMaxBuildHeight() - 128);
        }

        RocketSublevelWarper.Result result = RocketSublevelWarper.warp(subLevel, target, targetAnchor);
        if (result.success()) {
            RETRY_AFTER.remove(id);
            return;
        }

        RETRY_AFTER.put(id, tick + RETRY_COOLDOWN_TICKS);
        Northstar.LOGGER.warn("Could not transfer vessel {} into {}: {} ({})",
                id, target.dimension().location(), result.outcome(), result.detail());
    }
}