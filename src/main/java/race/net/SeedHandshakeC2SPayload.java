package race.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SeedHandshakeC2SPayload(long seed) implements CustomPayload {
    public static final CustomPayload.Id<SeedHandshakeC2SPayload> ID =
            new CustomPayload.Id<>(Identifier.of("fabric_race", "seed_handshake"));
    public static final PacketCodec<PacketByteBuf, SeedHandshakeC2SPayload> CODEC =
            PacketCodec.ofStatic(
                    (buf, p) -> buf.writeLong(p.seed),
                    buf -> new SeedHandshakeC2SPayload(buf.readLong())
            );
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
