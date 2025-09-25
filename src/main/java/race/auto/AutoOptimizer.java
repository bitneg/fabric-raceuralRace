package race.auto;

import net.minecraft.server.MinecraftServer;
import race.memory.MemoryManager;
import race.monitor.PerformanceMonitor;
import race.cache.PerformanceCache;
import race.batch.BatchProcessor;

/**
 * Автоматическая система оптимизации
 */
public final class AutoOptimizer {
    private static long lastOptimization = 0;
    private static final long OPTIMIZATION_INTERVAL = 300000; // 5 минут
    private static int optimizationLevel = 1; // 1-5, где 5 = максимальная оптимизация
    
    /**
     * Проверяет и выполняет автоматическую оптимизацию
     */
    public static void checkAndOptimize(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (now - lastOptimization < OPTIMIZATION_INTERVAL) return;
        
        lastOptimization = now;
        
        try {
            // Анализируем производительность
            double avgTickTime = getAverageTickTime();
            boolean memoryPressure = MemoryManager.isMemoryPressure();
            int playerCount = server.getPlayerManager().getPlayerList().size();
            
            // Определяем уровень оптимизации на основе нагрузки
            int newLevel = determineOptimizationLevel(avgTickTime, memoryPressure, playerCount);
            
            if (newLevel != optimizationLevel) {
                applyOptimizationLevel(newLevel);
                optimizationLevel = newLevel;
                System.out.println("[Race] Auto-optimization level changed to: " + optimizationLevel);
            }
            
        } catch (Throwable ignored) {}
    }
    
    private static double getAverageTickTime() {
        try {
            // Получаем среднее время выполнения serverTick
            return PerformanceMonitor.getAverageTime("serverTick");
        } catch (Throwable ignored) {
            return 50.0; // Значение по умолчанию
        }
    }
    
    private static int determineOptimizationLevel(double avgTickTime, boolean memoryPressure, int playerCount) {
        // Уровень 1: Низкая нагрузка
        if (avgTickTime < 20.0 && !memoryPressure && playerCount < 10) {
            return 1;
        }
        
        // Уровень 2: Средняя нагрузка
        if (avgTickTime < 40.0 && !memoryPressure && playerCount < 20) {
            return 2;
        }
        
        // Уровень 3: Высокая нагрузка
        if (avgTickTime < 60.0 && playerCount < 30) {
            return 3;
        }
        
        // Уровень 4: Очень высокая нагрузка
        if (avgTickTime < 80.0 && playerCount < 50) {
            return 4;
        }
        
        // Уровень 5: Критическая нагрузка
        return 5;
    }
    
    private static void applyOptimizationLevel(int level) {
        try {
            switch (level) {
                case 1 -> {
                    // Минимальная оптимизация
                    PerformanceCache.clearExpired();
                }
                case 2 -> {
                    // Легкая оптимизация
                    PerformanceCache.clearExpired();
                    BatchProcessor.clearAllBatches();
                }
                case 3 -> {
                    // Средняя оптимизация
                    PerformanceCache.clearExpired();
                    BatchProcessor.clearAllBatches();
                    MemoryManager.adaptiveCleanup();
                }
                case 4 -> {
                    // Агрессивная оптимизация
                    PerformanceCache.clearAll();
                    BatchProcessor.clearAllBatches();
                    MemoryManager.adaptiveCleanup();
                }
                case 5 -> {
                    // Максимальная оптимизация
                    PerformanceCache.clearAll();
                    BatchProcessor.clearAllBatches();
                    MemoryManager.forceCleanupAll();
                    PerformanceMonitor.resetStats();
                    System.gc(); // Принудительная сборка мусора
                }
            }
        } catch (Throwable ignored) {}
    }
    
    /**
     * Получает текущий уровень оптимизации
     */
    public static int getOptimizationLevel() {
        return optimizationLevel;
    }
    
    /**
     * Принудительно устанавливает уровень оптимизации
     */
    public static void setOptimizationLevel(int level) {
        if (level >= 1 && level <= 5) {
            applyOptimizationLevel(level);
            optimizationLevel = level;
        }
    }
}
