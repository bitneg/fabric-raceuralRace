package race.memory;

import net.minecraft.server.MinecraftServer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Менеджер памяти для оптимизации производительности
 */
public final class MemoryManager {
    private static final Map<String, AtomicLong> memoryCounters = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastCleanup = new ConcurrentHashMap<>();
    private static final long CLEANUP_INTERVAL = 30000; // 30 секунд
    private static final int MAX_ENTRIES_PER_CACHE = 1000;
    
    /**
     * Регистрирует использование памяти для отслеживания
     */
    public static void trackMemoryUsage(String cacheName, int entryCount) {
        memoryCounters.computeIfAbsent(cacheName, k -> new AtomicLong(0))
                     .set(entryCount);
    }
    
    /**
     * Проверяет, нужна ли очистка кеша
     */
    public static boolean shouldCleanup(String cacheName) {
        long now = System.currentTimeMillis();
        Long lastClean = lastCleanup.get(cacheName);
        
        if (lastClean == null || now - lastClean > CLEANUP_INTERVAL) {
            lastCleanup.put(cacheName, now);
            return true;
        }
        return false;
    }
    
    /**
     * Проверяет, превышен ли лимит записей в кеше
     */
    public static boolean isCacheOverflow(String cacheName) {
        AtomicLong counter = memoryCounters.get(cacheName);
        return counter != null && counter.get() > MAX_ENTRIES_PER_CACHE;
    }
    
    /**
     * Принудительная очистка всех кешей
     */
    public static void forceCleanupAll() {
        try {
            // Очищаем все кеши
            race.cache.PerformanceCache.clearAll();
            
            // Очищаем счетчики памяти
            memoryCounters.clear();
            lastCleanup.clear();
            
            // Принудительная сборка мусора
            System.gc();
        } catch (Throwable ignored) {}
    }
    
    /**
     * Получает статистику использования памяти
     */
    public static Map<String, Long> getMemoryStats() {
        Map<String, Long> stats = new HashMap<>();
        for (Map.Entry<String, AtomicLong> entry : memoryCounters.entrySet()) {
            stats.put(entry.getKey(), entry.getValue().get());
        }
        return stats;
    }
    
    /**
     * Проверяет общее состояние памяти сервера
     */
    public static boolean isMemoryPressure() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        // Если используется больше 80% памяти
        return (double) usedMemory / totalMemory > 0.8;
    }
    
    /**
     * Адаптивная очистка на основе давления памяти
     */
    public static void adaptiveCleanup() {
        if (isMemoryPressure()) {
            // Агрессивная очистка при нехватке памяти
            forceCleanupAll();
        } else {
            // Обычная очистка устаревших записей
            race.cache.PerformanceCache.clearExpired();
        }
    }
}
