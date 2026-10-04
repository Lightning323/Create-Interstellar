package com.lightning.northstar.client.renderer;

import com.lightning.northstar.compat.sable.NorthstarSable;
import com.lightning.northstar.contraption.rocket.packet.RocketSyncPacket;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

public final class RocketFlightOverlay implements LayeredDraw.Layer {

    public static final RocketFlightOverlay INSTANCE = new RocketFlightOverlay();

    private RocketFlightOverlay() {
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (minecraft.options.hideGui || player == null) return;

        SubLevelAccess subLevel = NorthstarSable.containing(player.level(), player.blockPosition());
        if (subLevel == null) return;
        RocketSyncPacket snapshot = RocketSyncPacket.latest(subLevel.getUniqueId());
        if (snapshot == null) return;

        int centerX = graphics.guiWidth() / 2;
        graphics.drawCenteredString(minecraft.font,
                Component.translatable("northstar.hud.rocket.status", snapshot.status().getSerializedName()),
                centerX, 8, 0xFFFFFF);
        graphics.drawCenteredString(minecraft.font,
                Component.translatable("northstar.hud.rocket.throttle", Mth.floor(snapshot.throttle() * 100f)),
                centerX, 20, 0xD8E8F5);
        graphics.drawCenteredString(minecraft.font,
                Component.translatable("northstar.hud.rocket.fuel", Mth.floor(snapshot.fuelFraction() * 100f)),
                centerX, 32, 0xD8E8F5);
    }
}
