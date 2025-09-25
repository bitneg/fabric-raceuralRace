package race.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record GhostTrailPayload(String playerName, String cause, List<GhostTrailPayload.Point> points)
        implements CustomPayload {
    public static final CustomPayload.Id<GhostTrailPayload> ID = new CustomPayload.Id<>(Identifier.of("fabric_race", "ghost_trail"));

    public static final PacketCodec<RegistryByteBuf, Point> POINT_CODEC = PacketCodec.tuple(
            PacketCodecs.DOUBLE, Point::x,
            PacketCodecs.DOUBLE, Point::y,
            PacketCodecs.DOUBLE, Point::z,
            Point::new
    );

    public static final PacketCodec<RegistryByteBuf, GhostTrailPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, GhostTrailPayload::playerName,
            PacketCodecs.STRING, GhostTrailPayload::cause,
            PacketCodecs.collection(ArrayList::new, POINT_CODEC), GhostTrailPayload::points,
            GhostTrailPayload::new
    );

    public record Point(double x, double y, double z) {}

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}


