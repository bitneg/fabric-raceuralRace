package race.performance;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import race.cache.PerformanceCache;
import race.memory.MemoryManager;
import race.runtime.TickBus;
import race.async.IOExec;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Оптимизатор для входа игроков - предотвращает падение TPS
 */
public final class JoinOptimizer {
    private static final Map<UUID, JoinTask> pendingJoins = new ConcurrentHashMap<>();
    private static final Set<UUID> processingJoins = ConcurrentHashMap.newKeySet();
    private static final int MAX_CONCURRENT_JOINS = 3; // Максимум 3 игрока одновременно
    private static final long JOIN_COOLDOWN_MS = 1000; // 1 секунда между входами
    private static final long[] lastJoinTimes = new long[MAX_CONCURRENT_JOINS];
    private static int lastJoinIndex = 0;
    
    private static class JoinTask {
        final ServerPlayerEntity player;
        final long timestamp;
        final CompletableFuture<Void> future;
        
        JoinTask(ServerPlayerEntity player, CompletableFuture<Void> future) {
            this.player = player;
            this.timestamp = System.currentTimeMillis();
            this.future = future;
        }
    }
    
    /**
     * Оптимизированный вход игрока с защитой от падения TPS
     */
    public static CompletableFuture<Void> optimizedJoin(ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        
        // Проверяем, не обрабатывается ли уже этот игрок
        if (processingJoins.contains(playerId)) {
            return CompletableFuture.completedFuture(null);
        }
        
        // Проверяем лимит одновременных входов
        if (processingJoins.size() >= MAX_CONCURRENT_JOINS) {
            // Откладываем вход
            CompletableFuture<Void> future = new CompletableFuture<>();
            pendingJoins.put(playerId, new JoinTask(player, future));
            return future;
        }
        
        return processJoin(player);
    }
    
    private static CompletableFuture<Void> processJoin(ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        processingJoins.add(playerId);
        
        // Проверяем кулдаун между входами
        long now = System.currentTimeMillis();
        long lastJoin = lastJoinTimes[lastJoinIndex];
        if (now - lastJoin < JOIN_COOLDOWN_MS) {
            // Откладываем на следующий тик
            CompletableFuture<Void> future = new CompletableFuture<>();
            TickBus.submitNextTick(() -> {
                processJoinDelayed(player).whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        future.completeExceptionally(throwable);
                    } else {
                        future.complete(result);
                    }
                });
            });
            return future;
        }
        
        lastJoinTimes[lastJoinIndex] = now;
        lastJoinIndex = (lastJoinIndex + 1) % MAX_CONCURRENT_JOINS;
        
        return processJoinDelayed(player);
    }
    
    private static CompletableFuture<Void> processJoinDelayed(ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        
        try {
            // Асинхронная подготовка данных игрока
            return IOExec.submit(() -> {
                // Подготавливаем данные в фоне
                preparePlayerData(player);
            }).thenCompose(v -> {
                // Основная логика на серверном тике
                CompletableFuture<Void> future = new CompletableFuture<>();
                TickBus.submitNextTick(() -> {
                    try {
                        executeJoinLogic(player);
                        future.complete(null);
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                    }
                });
                return future;
            }).whenComplete((result, throwable) -> {
                // Очищаем состояние
                processingJoins.remove(playerId);
                pendingJoins.remove(playerId);
                
                // Обрабатываем следующих в очереди
                processNextPendingJoin();
            });
        } catch (Throwable t) {
            processingJoins.remove(playerId);
            throw t;
        }
    }
    
    private static void preparePlayerData(ServerPlayerEntity player) {
        try {
            // Кешируем данные игрока заранее
            String worldName = player.getServerWorld().getRegistryKey().getValue().toString();
            PerformanceCache.setPlayerCache(player.getUuid(), worldName, -1L, "подключается");
            
            // Предварительная загрузка чанков
            preloadPlayerChunks(player);
            
        } catch (Throwable ignored) {}
    }
    
    private static void preloadPlayerChunks(ServerPlayerEntity player) {
        try {
            ServerWorld world = player.getServerWorld();
            BlockPos pos = player.getBlockPos();
            
            // Используем оптимизированную предзагрузку
            race.performance.ChunkPreloader.preloadChunks(player.getUuid(), world, pos);
        } catch (Throwable ignored) {}
    }
    
    private static void executeJoinLogic(ServerPlayerEntity player) {
        try {
            // Основная логика входа (телепортация, инициализация и т.д.)
            var preferred = race.server.world.PreferredWorldRegistry.getPreferred(player.getUuid());
            if (preferred != null) {
                var dst = player.getServer().getWorld(preferred);
                if (dst != null) {
                    race.tp.SafeTeleport.toWorldSpawn(player, dst);
                }
            }
            
            // Инициализация хаба в фоне
            initializeHubAsync(player.getServer());
            
        } catch (Throwable t) {
            System.err.println("[Race] Error in join logic: " + t.getMessage());
        }
    }
    
    private static void initializeHubAsync(MinecraftServer server) {
        if (!race.hub.HubManager.isHubActive()) {
            IOExec.submit(() -> {
                // Фоновая инициализация хаба
                try {
                    // Подготовка файлов хаба
                    System.out.println("[Race] Preparing hub files in background...");
                } catch (Throwable t) {
                    System.err.println("[Race] Error preparing hub files: " + t.getMessage());
                }
            }).thenRun(() -> {
                TickBus.submitNextTick(() -> {
                    try {
                        if (race.hub.HubManager.isHubActive()) {
                            var hubWorld = server.getWorld(net.minecraft.world.World.OVERWORLD);
                            if (hubWorld != null) {
                                race.server.world.SpawnCache.warmupAndCache(hubWorld, hubWorld.getSpawnPos(), 2);
                            }
                        }
                    } catch (Throwable t) {
                        System.err.println("[Race] Error initializing hub world: " + t.getMessage());
                    }
                });
            });
        }
    }
    
    
    /**
     * Принудительная очистка очереди
     */
    public static void clearQueue() {
        for (JoinTask task : pendingJoins.values()) {
            task.future.completeExceptionally(new RuntimeException("Queue cleared"));
        }
        pendingJoins.clear();
        processingJoins.clear();
    }
    
    /**
     * Получает статистику очереди
     */
    public static Map<String, Object> getQueueStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("pending", pendingJoins.size());
        stats.put("processing", processingJoins.size());
        stats.put("maxConcurrent", MAX_CONCURRENT_JOINS);
        return stats;
    }
    
    /**
     * Обрабатывает следующий вход в очереди (вызывается из TickBus)
     */
    public static void processNextPendingJoin() {
        if (pendingJoins.isEmpty() || processingJoins.size() >= MAX_CONCURRENT_JOINS) {
            return;
        }
        
        // Находим самого старого в очереди
        JoinTask oldest = null;
        UUID oldestId = null;
        long oldestTime = Long.MAX_VALUE;
        
        for (Map.Entry<UUID, JoinTask> entry : pendingJoins.entrySet()) {
            if (entry.getValue().timestamp < oldestTime) {
                oldest = entry.getValue();
                oldestId = entry.getKey();
                oldestTime = entry.getValue().timestamp;
            }
        }
        
        if (oldest != null) {
            final JoinTask finalOldest = oldest;
            final UUID finalOldestId = oldestId;
            pendingJoins.remove(finalOldestId);
            processJoin(finalOldest.player).whenComplete((result, throwable) -> {
                if (throwable != null) {
                    finalOldest.future.completeExceptionally(throwable);
                } else {
                    finalOldest.future.complete(result);
                }
            });
        }
    }
}
