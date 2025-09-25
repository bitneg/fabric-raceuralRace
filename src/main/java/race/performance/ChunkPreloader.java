package race.performance;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;
import race.async.IOExec;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Предзагрузчик чанков для оптимизации входов игроков
 */
public final class ChunkPreloader {
    private static final Map<UUID, ChunkPreloadTask> activePreloads = new ConcurrentHashMap<>();
    private static final int MAX_PRELOAD_RADIUS = 2; // Радиус предзагрузки
    private static final int MAX_CONCURRENT_PRELOADS = 5; // Максимум одновременных предзагрузок
    
    private static class ChunkPreloadTask {
        final UUID playerId;
        final ServerWorld world;
        final BlockPos center;
        final CompletableFuture<Void> future;
        final long timestamp;
        
        ChunkPreloadTask(UUID playerId, ServerWorld world, BlockPos center) {
            this.playerId = playerId;
            this.world = world;
            this.center = center;
            this.future = new CompletableFuture<>();
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    /**
     * Предзагружает чанки вокруг позиции игрока
     */
    public static CompletableFuture<Void> preloadChunks(UUID playerId, ServerWorld world, BlockPos pos) {
        // Проверяем лимит одновременных предзагрузок
        if (activePreloads.size() >= MAX_CONCURRENT_PRELOADS) {
            return CompletableFuture.completedFuture(null);
        }
        
        ChunkPreloadTask task = new ChunkPreloadTask(playerId, world, pos);
        activePreloads.put(playerId, task);
        
        // Выполняем предзагрузку асинхронно
        IOExec.submit(() -> {
            try {
                performChunkPreload(task);
                task.future.complete(null);
            } catch (Throwable t) {
                task.future.completeExceptionally(t);
            } finally {
                activePreloads.remove(playerId);
            }
        });
        
        return task.future;
    }
    
    private static void performChunkPreload(ChunkPreloadTask task) {
        try {
            ChunkPos centerChunk = new ChunkPos(task.center);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            
            // Предзагружаем чанки в радиусе
            for (int x = -MAX_PRELOAD_RADIUS; x <= MAX_PRELOAD_RADIUS; x++) {
                for (int z = -MAX_PRELOAD_RADIUS; z <= MAX_PRELOAD_RADIUS; z++) {
                    ChunkPos chunkPos = new ChunkPos(centerChunk.x + x, centerChunk.z + z);
                    
                    // Загружаем чанк с минимальным статусом
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        try {
                            var chunk = task.world.getChunkManager()
                                .getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, true);
                            // Дополнительная обработка чанка если нужно
                        } catch (Throwable t) {
                            // Игнорируем ошибки загрузки чанков
                        }
                    });
                    
                    futures.add(future);
                }
            }
            
            // Ждем загрузки всех чанков
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
        } catch (Throwable t) {
            System.err.println("[Race] Error preloading chunks: " + t.getMessage());
        }
    }
    
    /**
     * Предзагружает чанки вокруг спавна мира
     */
    public static CompletableFuture<Void> preloadSpawnChunks(ServerWorld world, BlockPos spawnPos) {
        return preloadChunks(UUID.randomUUID(), world, spawnPos);
    }
    
    /**
     * Очищает старые задачи предзагрузки
     */
    public static void cleanupOldTasks() {
        long now = System.currentTimeMillis();
        long maxAge = 30000; // 30 секунд
        
        activePreloads.entrySet().removeIf(entry -> {
            ChunkPreloadTask task = entry.getValue();
            if (now - task.timestamp > maxAge) {
                task.future.completeExceptionally(new RuntimeException("Task timeout"));
                return true;
            }
            return false;
        });
    }
    
    /**
     * Получает статистику предзагрузки
     */
    public static Map<String, Object> getPreloadStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("activePreloads", activePreloads.size());
        stats.put("maxConcurrent", MAX_CONCURRENT_PRELOADS);
        stats.put("maxRadius", MAX_PRELOAD_RADIUS);
        return stats;
    }
    
    /**
     * Принудительная очистка всех задач
     */
    public static void clearAllTasks() {
        for (ChunkPreloadTask task : activePreloads.values()) {
            task.future.completeExceptionally(new RuntimeException("Tasks cleared"));
        }
        activePreloads.clear();
    }
}
