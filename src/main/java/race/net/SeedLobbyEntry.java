package race.net;

import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.PacketByteBuf;

public record SeedLobbyEntry(String playerName, long seed, String worldKey) {
    public static final PacketCodec<PacketByteBuf, SeedLobbyEntry> PACKET_CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, SeedLobbyEntry::playerName,
            PacketCodecs.VAR_LONG, SeedLobbyEntry::seed,
            PacketCodecs.STRING, SeedLobbyEntry::worldKey,
            SeedLobbyEntry::new
    );
}


