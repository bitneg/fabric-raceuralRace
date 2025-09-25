package race.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Пакет для передачи детального прогресса игрока
 */
public record PlayerProgressPayload(String playerName, long rtaMs, String currentStage,
                                  Map<String, Long> milestoneTimes, String worldName, String activity) implements CustomPayload {
    
    public static final Id<PlayerProgressPayload> ID = new Id<>(Identifier.of("fabric_race", "player_progress"));
    
    public static final PacketCodec<RegistryByteBuf, PlayerProgressPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.STRING, PlayerProgressPayload::playerName,
                    PacketCodecs.VAR_LONG, PlayerProgressPayload::rtaMs,
                    PacketCodecs.STRING, PlayerProgressPayload::currentStage,
                    PacketCodecs.map(HashMap::new, PacketCodecs.STRING, PacketCodecs.VAR_LONG), PlayerProgressPayload::milestoneTimes,
                    PacketCodecs.STRING, PlayerProgressPayload::worldName,
                    PacketCodecs.STRING, PlayerProgressPayload::activity,
                    PlayerProgressPayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
