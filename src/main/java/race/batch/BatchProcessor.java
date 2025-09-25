package race.batch;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Система батчинга операций для оптимизации производительности
 */
public final class BatchProcessor {
    private static final Map<String, Queue<Runnable>> batchQueues = new ConcurrentHashMap<>();
    private static final Map<String, Integer> batchSizes = new ConcurrentHashMap<>();
    private static final int DEFAULT_BATCH_SIZE = 10;
    
    static {
        // Настраиваем размеры батчей для разных типов операций
        batchSizes.put("network", 5);
        batchSizes.put("world", 3);
        batchSizes.put("player", 8);
        batchSizes.put("cleanup", 15);
    }
    
    /**
     * Добавляет операцию в батч
     */
    public static void addToBatch(String batchType, Runnable operation) {
        batchQueues.computeIfAbsent(batchType, k -> new ConcurrentLinkedQueue<>())
                  .offer(operation);
    }
    
    /**
     * Выполняет все операции в батче
     */
    public static void processBatch(String batchType) {
        Queue<Runnable> queue = batchQueues.get(batchType);
        if (queue == null || queue.isEmpty()) return;
        
        int batchSize = batchSizes.getOrDefault(batchType, DEFAULT_BATCH_SIZE);
        int processed = 0;
        
        while (!queue.isEmpty() && processed < batchSize) {
            Runnable operation = queue.poll();
            if (operation != null) {
                try {
                    operation.run();
                    processed++;
                } catch (Throwable ignored) {}
            }
        }
    }
    
    /**
     * Выполняет операции для всех игроков батчами
     */
    public static void processPlayersBatch(MinecraftServer server, Consumer<ServerPlayerEntity> operation) {
        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
        int batchSize = batchSizes.getOrDefault("player", DEFAULT_BATCH_SIZE);
        
        for (int i = 0; i < players.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, players.size());
            List<ServerPlayerEntity> batch = players.subList(i, endIndex);
            
            for (ServerPlayerEntity player : batch) {
                try {
                    operation.accept(player);
                } catch (Throwable ignored) {}
            }
        }
    }
    
    /**
     * Очищает все батчи
     */
    public static void clearAllBatches() {
        batchQueues.clear();
    }
    
    /**
     * Получает статистику батчей
     */
    public static Map<String, Integer> getBatchStats() {
        Map<String, Integer> stats = new HashMap<>();
        for (Map.Entry<String, Queue<Runnable>> entry : batchQueues.entrySet()) {
            stats.put(entry.getKey(), entry.getValue().size());
        }
        return stats;
    }
}
