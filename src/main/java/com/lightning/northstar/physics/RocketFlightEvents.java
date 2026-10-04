package com.lightning.northstar.physics;

import com.lightning.northstar.Northstar;
import com.lightning.northstar.accessor.NorthstarLevel;
import com.lightning.northstar.config.NorthstarConfigs;
import com.lightning.northstar.contraption.rocket.RocketDestination;
import com.lightning.northstar.contraption.rocket.LaunchStatus;
import com.lightning.northstar.contraption.rocket.packet.RocketSyncPacket;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.List;
import java.util.UUID;

/** Server lifecycle for rocket sublevels: dimension handoff and lightweight HUD snapshots. */
@EventBusSubscriber(modid = Northstar.MOD_ID)
public final class RocketFlightEvents {

    private static long tick;

    private RocketFlightEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Pre event) {
        boolean sendHud = ++tick % 5L == 0L;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            ServerSubLevelContainer container = (ServerSubLevelContainer) SubLevelContainer.getContainer(level);
            if (container == null) continue;
            for (ServerSubLevel subLevel : List.copyOf(container.getAllSubLevels())) {
                if (!RocketSublevelState.isRocket(subLevel)) continue;
                RocketShipState state = RocketSublevelState.get(subLevel);
                if (state.phase() == LaunchStatus.ASCENDING) {
                    tryDimensionTransition(subLevel, state);
                }
                if (!sendHud) continue;

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
        if (target == null) return;

        double y = subLevel.logicalPose().position().y();
        boolean ascendingThroughBoundary = y > source.getMaxBuildHeight()
                + NorthstarConfigs.server().getCombinedTeleportHeight();
        var dimension = source.northstar$dimension();
        boolean descendingToBelow = dimension != null && dimension.dimensionBelow() != null
                && dimension.dimensionBelow().equals(destination.dimKey())
                && y < source.getMinBuildHeight() + 20;
        if (!ascendingThroughBoundary && !descendingToBelow) return;
        if (target == source && destination.pos() == null) return;

        BlockPos targetAnchor = destination.pos();
        if (targetAnchor == null) {
            targetAnchor = BlockPos.containing(subLevel.logicalPose().position().x(),
                            subLevel.logicalPose().position().y(), subLevel.logicalPose().position().z())
                    .atY(target.getMaxBuildHeight() - 128);
        }
        RocketSublevelWarper.warp(subLevel, target, targetAnchor);
    }
}
