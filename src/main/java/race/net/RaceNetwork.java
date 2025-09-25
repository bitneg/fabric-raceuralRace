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
        long startTime = race.monitor.PerformanceMonitor.startOperation("updateProgress1Hz");
        try {
            // Проверяем кеш перед созданием payload
            var cached = race.cache.PerformanceCache.getPlayerCache(p.getUuid());
            if (cached != null && !cached.isExpired()) {
                // Используем кешированные данные
                String playerName = p.getGameProfile().getName();
                long rtaMs = System.currentTimeMillis() - race.server.RaceServerInit.getT0Ms();
                String currentStage = "исследует";
                Map<String, Long> milestoneTimes = new HashMap<>();
                String worldName = cached.worldKey;
                String activity = cached.activity;
                
                // PlayerProgressPayload удален - больше не отправляем
                return;
            }
            
            // Собираем модель прогресса
            String playerName = p.getGameProfile().getName();
            long rtaMs = System.currentTimeMillis() - race.server.RaceServerInit.getT0Ms();
            String currentStage = "исследует";
            Map<String, Long> milestoneTimes = new HashMap<>();
            String worldName = p.getServerWorld().getRegistryKey().getValue().toString();
            String activity = "исследует";
            
            // Кешируем данные
            race.cache.PerformanceCache.setPlayerCache(p.getUuid(), worldName, -1L, activity);
            
            // PlayerProgressPayload удален - больше не отправляем
        } catch (Throwable ignored) {}
        finally {
            race.monitor.PerformanceMonitor.endOperation("updateProgress1Hz", startTime);
        }
    }

    public static void updateParallelPlayers2Hz(ServerPlayerEntity p) {
        long startTime = race.monitor.PerformanceMonitor.startOperation("updateParallelPlayers2Hz");
        try {
            var payload = ParallelPlayersPayload.buildBucketed(p);
            int h = payload.stableHash();
            int[] arr = buf(p.getUuid());
            if (arr[2] != h) {
                arr[2] = h;
                payload.sendTo(p);
            }
        } catch (Throwable ignored) {}
        finally {
            race.monitor.PerformanceMonitor.endOperation("updateParallelPlayers2Hz", startTime);
        }
    }
}
