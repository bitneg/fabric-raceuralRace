package race.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record TpsPayload(double tps, boolean enabled) implements CustomPayload {
    public static final Id<TpsPayload> ID = new Id<>(Identifier.of("fabric_race", "tps"));
    public static final PacketCodec<RegistryByteBuf, TpsPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.DOUBLE, TpsPayload::tps,
                    PacketCodecs.BOOL, TpsPayload::enabled,
                    TpsPayload::new
            );

    @Override 
    public Id<? extends CustomPayload> getId() { 
        return ID; 
    }
}
