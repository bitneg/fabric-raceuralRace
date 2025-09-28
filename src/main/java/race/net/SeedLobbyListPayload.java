package race.net;

import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.CustomPayload.Id;
import net.minecraft.util.Identifier;
import net.minecraft.network.PacketByteBuf;

import java.util.List;

public record SeedLobbyListPayload(List<SeedLobbyEntry> entries) implements CustomPayload {
    public static final Id<SeedLobbyListPayload> ID = new Id<>(Identifier.of("fabric_race", "seed_lobby"));
    public static final PacketCodec<PacketByteBuf, SeedLobbyListPayload> CODEC =
            PacketCodecs.collection((int size) -> new java.util.ArrayList<SeedLobbyEntry>(Math.min(size, 65536)), SeedLobbyEntry.PACKET_CODEC)
                    .xmap(list -> new SeedLobbyListPayload(list), payload -> new java.util.ArrayList<>(payload.entries()));

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}


