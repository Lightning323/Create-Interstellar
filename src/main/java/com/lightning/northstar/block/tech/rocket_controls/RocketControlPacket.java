package com.lightning.northstar.block.tech.rocket_controls;

import com.lightning.northstar.compat.sable.NorthstarSable;
import com.lightning.northstar.content.NorthstarPackets;
import com.lightning.northstar.physics.RocketShipState;
import com.lightning.northstar.physics.RocketSublevelState;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import io.netty.buffer.ByteBuf;
import net.createmod.catnip.net.base.ServerboundPacketPayload;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

/** Continuous flight input from a pilot currently inside a Sable vessel. */
public record RocketControlPacket(float pitch, float yaw, float roll, float throttleStep, boolean disembark)
        implements ServerboundPacketPayload {

    public static final StreamCodec<ByteBuf, RocketControlPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, RocketControlPacket::pitch,
            ByteBufCodecs.FLOAT, RocketControlPacket::yaw,
            ByteBufCodecs.FLOAT, RocketControlPacket::roll,
            ByteBufCodecs.FLOAT, RocketControlPacket::throttleStep,
            ByteBufCodecs.BOOL, RocketControlPacket::disembark,
            RocketControlPacket::new
    );

    @Override
    public void handle(@NotNull ServerPlayer player) {
        SubLevelAccess access = NorthstarSable.containing(player.level(), player.blockPosition());
        if (!(access instanceof ServerSubLevel subLevel) || !RocketSublevelState.isRocket(subLevel)) return;

        if (disembark) {
            SubLevelHelper.popEntityLocal(subLevel, player);
            return;
        }

        RocketShipState state = RocketSublevelState.get(subLevel);
        state.setAttitude(pitch, yaw, roll);
        state.setThrottle(state.throttle() + throttleStep * 0.02f);
        RocketSublevelState.save(subLevel);
    }

    @Override
    public PacketTypeProvider getTypeProvider() {
        return NorthstarPackets.ROCKET_CONTROL;
    }
}
