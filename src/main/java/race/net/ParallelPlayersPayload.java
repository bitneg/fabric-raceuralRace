package race.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record ParallelPlayersPayload(List<ParallelPlayersPayload.Point> points) implements CustomPayload {
    public static final Id<ParallelPlayersPayload> ID = new Id<>(Identifier.of("fabric_race", "parallel_players"));

    public static final PacketCodec<RegistryByteBuf, Point> POINT_CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, Point::name,
            PacketCodecs.DOUBLE, Point::x,
            PacketCodecs.DOUBLE, Point::y,
            PacketCodecs.DOUBLE, Point::z,
            PacketCodecs.BYTE, Point::type,
            Point::new
    );

    public static final PacketCodec<RegistryByteBuf, ParallelPlayersPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.collection(ArrayList::new, POINT_CODEC), ParallelPlayersPayload::points,
            ParallelPlayersPayload::new
    );

    // type: 0 default, 1 mine, 2 place, 3 fight, 4 move, 5 chest, 6 portal, 7 craft
    public record Point(String name, double x, double y, double z, byte type) {}

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}


