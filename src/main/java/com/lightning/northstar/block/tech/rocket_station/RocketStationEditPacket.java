package com.lightning.northstar.block.tech.rocket_station;

import com.lightning.northstar.accessor.NorthstarLevel;
import com.lightning.northstar.content.NorthstarPackets;
import com.lightning.northstar.contraption.rocket.RocketDestination;
import com.lightning.northstar.util.NorthstarCodecs;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import io.netty.buffer.ByteBuf;
import net.createmod.catnip.net.base.ServerboundPacketPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

public record RocketStationEditPacket(
        BlockPos pos,
        boolean flag,
        @Nullable RocketDestination destination
) implements ServerboundPacketPayload {

    public static final StreamCodec<ByteBuf, RocketStationEditPacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RocketStationEditPacket::pos,
            ByteBufCodecs.BOOL, RocketStationEditPacket::flag,
            NorthstarCodecs.nullableStream(RocketDestination.STREAM_CODEC), RocketStationEditPacket::destination,
            RocketStationEditPacket::new
    );

    @Override
    public void handle(ServerPlayer player) {
        if (!(player.level().getBlockEntity(pos) instanceof RocketStationBlockEntity be)) {
            return;
        }
        if (!RocketStationMenu.validateDestination(NorthstarLevel.SERVER_TRACKER, be.container.getItem(0), destination)) {
            return;
        }

        be.destination = destination;
        be.sendData();
        be.setChanged();

        if (flag) {
            be.assemble();
        }
    }

    @Override
    public PacketTypeProvider getTypeProvider() {
        return NorthstarPackets.UPDATE_ROCKET_STATION;
    }

}
