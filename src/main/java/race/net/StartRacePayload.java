package race.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record StartRacePayload(long seed, long t0ms) implements CustomPayload {
    public static final CustomPayload.Id<StartRacePayload> ID =
            new CustomPayload.Id<>(Identifier.of("fabric_race", "start_race"));
    public static final PacketCodec<PacketByteBuf, StartRacePayload> CODEC =
            PacketCodec.ofStatic(
                    (PacketByteBuf buf, StartRacePayload p) -> { buf.writeLong(p.seed); buf.writeLong(p.t0ms); },
                    (PacketByteBuf buf) -> new StartRacePayload(buf.readLong(), buf.readLong())
            );
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
