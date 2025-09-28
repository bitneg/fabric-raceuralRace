package race.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.CustomPayload.Id;
import net.minecraft.util.Identifier;

public record JoinRequestStatusPayload(boolean hasActiveRequest, String targetPlayer) implements CustomPayload {
    public static final Id<JoinRequestStatusPayload> ID = new Id<>(Identifier.of("fabric_race", "join_request_status"));
    public static final PacketCodec<RegistryByteBuf, JoinRequestStatusPayload> CODEC = PacketCodec.of(
        (payload, buf) -> {
            buf.writeBoolean(payload.hasActiveRequest);
            buf.writeString(payload.targetPlayer);
        },
        buf -> new JoinRequestStatusPayload(buf.readBoolean(), buf.readString())
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
