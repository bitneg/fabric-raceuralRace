package race.net;

import java.util.List;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.network.packet.CustomPayload.Id;

public record RaceBoardPayload(List<Row> rows) implements CustomPayload {
    public static final Id<RaceBoardPayload> ID = new Id<>(Identifier.of("fabric_race", "board"));
    public static final PacketCodec<RegistryByteBuf, RaceBoardPayload> CODEC =
            PacketCodec.tuple(Row.CODEC.collect(PacketCodecs.toList()), RaceBoardPayload::rows, RaceBoardPayload::new);

    public record Row(String name, long rtaMs, String stage, String activity, String worldKey) {
        public static final PacketCodec<RegistryByteBuf, Row> CODEC =
                PacketCodec.tuple(
                        PacketCodecs.STRING, Row::name,
                        PacketCodecs.VAR_LONG, Row::rtaMs,
                        PacketCodecs.STRING, Row::stage,
                        PacketCodecs.STRING, Row::activity,
                        PacketCodecs.STRING, Row::worldKey,
                        Row::new
                );
    }

    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
