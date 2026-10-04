package com.lightning.northstar.contraption.rocket.packet;

import com.lightning.northstar.content.NorthstarPackets;
import com.lightning.northstar.contraption.rocket.LaunchStatus;
import com.lightning.northstar.physics.RocketShipState;
import com.lightning.northstar.physics.RocketFuelStore;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import io.netty.buffer.ByteBuf;
import net.createmod.catnip.net.base.ClientboundPacketPayload;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Client HUD snapshot; Sable independently synchronizes the sublevel pose. */
public record RocketSyncPacket(UUID subLevelId, Pose pose, float throttle, LaunchStatus status, float fuelFraction)
        implements ClientboundPacketPayload {

    private static final Map<UUID, RocketSyncPacket> CLIENT_SNAPSHOTS = new ConcurrentHashMap<>();

    public static final StreamCodec<ByteBuf, Position> POSITION_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Position::x,
            ByteBufCodecs.DOUBLE, Position::y,
            ByteBufCodecs.DOUBLE, Position::z,
            Position::new
    );

    public static final StreamCodec<ByteBuf, Rotation> ROTATION_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Rotation::x,
            ByteBufCodecs.DOUBLE, Rotation::y,
            ByteBufCodecs.DOUBLE, Rotation::z,
            ByteBufCodecs.DOUBLE, Rotation::w,
            Rotation::new
    );

    public static final StreamCodec<ByteBuf, Pose> POSE_CODEC = StreamCodec.composite(
            POSITION_CODEC, Pose::position,
            ROTATION_CODEC, Pose::rotation,
            Pose::new
    );

    public static final StreamCodec<ByteBuf, RocketSyncPacket> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, RocketSyncPacket::subLevelId,
            POSE_CODEC, RocketSyncPacket::pose,
            ByteBufCodecs.FLOAT, RocketSyncPacket::throttle,
            LaunchStatus.STREAM_CODEC, RocketSyncPacket::status,
            ByteBufCodecs.FLOAT, RocketSyncPacket::fuelFraction,
            RocketSyncPacket::new
    );

    public static RocketSyncPacket of(ServerSubLevel subLevel, RocketShipState state) {
        Vector3dc position = subLevel.logicalPose().position();
        Quaterniondc orientation = subLevel.logicalPose().orientation();
        return new RocketSyncPacket(subLevel.getUniqueId(), new Pose(
                new Position(position.x(), position.y(), position.z()),
                new Rotation(orientation.x(), orientation.y(), orientation.z(), orientation.w())),
                state.throttle(), state.phase(), RocketFuelStore.fraction(subLevel));
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void handle(LocalPlayer player) {
        CLIENT_SNAPSHOTS.put(subLevelId, this);
    }

    @OnlyIn(Dist.CLIENT)
    public static RocketSyncPacket latest(UUID subLevelId) {
        return CLIENT_SNAPSHOTS.get(subLevelId);
    }

    @Override
    public PacketTypeProvider getTypeProvider() {
        return NorthstarPackets.ROCKET_SYNC;
    }

    public record Pose(Position position, Rotation rotation) {
    }

    public record Position(double x, double y, double z) {
    }

    public record Rotation(double x, double y, double z, double w) {
    }
}
