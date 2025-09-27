package race.net;

import net.minecraft.server.network.ServerPlayerEntity;
import java.util.*;

public final class RaceNetwork {
    // last payload hash per player per channel
    private static final Map<UUID, int[]> lastHashes = new HashMap<>();
    // индекс 0 - progress, 1 - board, 2 - parallel
    private static final int CHANNELS = 3;

    private static int[] buf(UUID id) {
        return lastHashes.computeIfAbsent(id, k -> new int[CHANNELS]);
    }

    public static void updateProgress1Hz(ServerPlayerEntity p) {
        try {
            // Упрощенная версия без мониторинга производительности
            String playerName = p.getGameProfile().getName();
            long rtaMs = System.currentTimeMillis() - race.server.RaceServerInit.getT0Ms();
            String currentStage = "исследует";
            Map<String, Long> milestoneTimes = new HashMap<>();
            String worldName = p.getServerWorld().getRegistryKey().getValue().toString();
            String activity = "исследует";
            
            // PlayerProgressPayload удален - больше не отправляем
        } catch (Throwable ignored) {}
    }

    public static void updateParallelPlayers2Hz(ServerPlayerEntity p) {
        try {
            var payload = ParallelPlayersPayload.buildBucketed(p);
            int h = payload.stableHash();
            int[] arr = buf(p.getUuid());
            if (arr[2] != h) {
                arr[2] = h;
                payload.sendTo(p);
            }
        } catch (Throwable ignored) {}
    }
}
