package race.net;

import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * S2C пакет для синхронизации виртуального времени между сервером и клиентом.
 * Отправляется всем игрокам конкретного мира при изменении SLOT_TIME.
 */
public record RaceTimeSyncS2CPayload(String worldId, long time) implements CustomPayload {
    public static final Id<RaceTimeSyncS2CPayload> ID =
        new Id<>(Identifier.of("fabric_race", "time_sync"));
    
    public static final PacketCodec<PacketByteBuf, RaceTimeSyncS2CPayload> CODEC =
        PacketCodec.tuple(
            PacketCodecs.STRING, RaceTimeSyncS2CPayload::worldId,
            PacketCodecs.VAR_LONG, RaceTimeSyncS2CPayload::time,
            RaceTimeSyncS2CPayload::new
        );
    
    public static RaceTimeSyncS2CPayload of(String worldId, long time) {
        return new RaceTimeSyncS2CPayload(worldId, time);
    }
    
    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
