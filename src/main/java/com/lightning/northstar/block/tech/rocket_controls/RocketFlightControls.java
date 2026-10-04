package com.lightning.northstar.block.tech.rocket_controls;

import com.lightning.northstar.Northstar;
import com.lightning.northstar.compat.sable.NorthstarSable;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.IEventBus;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

/** Keyboard pilot controls for vessels assembled as Sable sublevels. */
public final class RocketFlightControls {

    private static final KeyMapping PITCH_UP = key("pitch_up", GLFW.GLFW_KEY_W);
    private static final KeyMapping PITCH_DOWN = key("pitch_down", GLFW.GLFW_KEY_S);
    private static final KeyMapping YAW_LEFT = key("yaw_left", GLFW.GLFW_KEY_A);
    private static final KeyMapping YAW_RIGHT = key("yaw_right", GLFW.GLFW_KEY_D);
    private static final KeyMapping ROLL_LEFT = key("roll_left", GLFW.GLFW_KEY_Q);
    private static final KeyMapping ROLL_RIGHT = key("roll_right", GLFW.GLFW_KEY_E);
    private static final KeyMapping THROTTLE_UP = key("throttle_up", GLFW.GLFW_KEY_R);
    private static final KeyMapping THROTTLE_DOWN = key("throttle_down", GLFW.GLFW_KEY_F);
    private static final KeyMapping DISEMBARK = key("disembark", GLFW.GLFW_KEY_B);

    private static boolean wasControlling;

    private RocketFlightControls() {
    }

    private static KeyMapping key(String name, int keyCode) {
        return new KeyMapping("key.northstar.rocket." + name, InputConstants.Type.KEYSYM, keyCode,
                "key.categories.northstar");
    }

    public static void register(IEventBus eventBus) {
        eventBus.addListener(RocketFlightControls::registerKeyMappings);
        NeoForge.EVENT_BUS.addListener(RocketFlightControls::clientTick);
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(PITCH_UP);
        event.register(PITCH_DOWN);
        event.register(YAW_LEFT);
        event.register(YAW_RIGHT);
        event.register(ROLL_LEFT);
        event.register(ROLL_RIGHT);
        event.register(THROTTLE_UP);
        event.register(THROTTLE_DOWN);
        event.register(DISEMBARK);
    }

    private static void clientTick(ClientTickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || Minecraft.getInstance().screen != null ||
                !NorthstarSable.isInsideSubLevel(player)) {
            if (wasControlling) send(0f, 0f, 0f, 0f, false);
            wasControlling = false;
            return;
        }

        if (DISEMBARK.consumeClick()) {
            send(0f, 0f, 0f, 0f, true);
            wasControlling = false;
            return;
        }

        float pitch = down(PITCH_UP) - down(PITCH_DOWN);
        float yaw = down(YAW_LEFT) - down(YAW_RIGHT);
        float roll = down(ROLL_LEFT) - down(ROLL_RIGHT);
        float throttle = down(THROTTLE_UP) - down(THROTTLE_DOWN);
        if (pitch != 0f || yaw != 0f || roll != 0f || throttle != 0f || wasControlling) {
            send(pitch, yaw, roll, throttle, false);
        }
        wasControlling = pitch != 0f || yaw != 0f || roll != 0f || throttle != 0f;
    }

    private static float down(KeyMapping mapping) {
        return mapping.isDown() ? 1f : 0f;
    }

    private static void send(float pitch, float yaw, float roll, float throttleStep, boolean disembark) {
        CatnipServices.NETWORK.sendToServer(new RocketControlPacket(pitch, yaw, roll, throttleStep, disembark));
    }
}
