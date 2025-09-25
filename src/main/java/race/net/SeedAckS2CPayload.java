package race.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SeedAckS2CPayload(boolean accepted, String reason, long seed) implements CustomPayload {
    public static final CustomPayload.Id<SeedAckS2CPayload> ID =
            new CustomPayload.Id<>(Identifier.of("fabric_race", "seed_ack"));
    public static final PacketCodec<PacketByteBuf, SeedAckS2CPayload> CODEC =
            PacketCodec.ofStatic(
                    (buf, p) -> { buf.writeBoolean(p.accepted); buf.writeString(p.reason); buf.writeLong(p.seed); },
                    buf -> new SeedAckS2CPayload(buf.readBoolean(), buf.readString(), buf.readLong())
            );
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
