package race.monitor;

import net.minecraft.server.MinecraftServer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Мониторинг производительности системы
 */
public final class PerformanceMonitor {
    private static final Map<String, AtomicLong> operationCounters = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> operationTimes = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastReportTime = new ConcurrentHashMap<>();
    private static final long REPORT_INTERVAL = 60000; // 1 минута
    
    /**
     * Начинает отслеживание операции
     */
    public static long startOperation(String operationName) {
        return System.nanoTime();
    }
    
    /**
     * Завершает отслеживание операции
     */
    public static void endOperation(String operationName, long startTime) {
        long duration = System.nanoTime() - startTime;
        operationCounters.computeIfAbsent(operationName, k -> new AtomicLong(0)).incrementAndGet();
        operationTimes.computeIfAbsent(operationName, k -> new AtomicLong(0)).addAndGet(duration);
    }
    
    /**
     * Получает среднее время выполнения операции в миллисекундах
     */
    public static double getAverageTime(String operationName) {
        AtomicLong count = operationCounters.get(operationName);
        AtomicLong totalTime = operationTimes.get(operationName);
        
        if (count == null || totalTime == null || count.get() == 0) {
            return 0.0;
        }
        
        return (totalTime.get() / 1_000_000.0) / count.get();
    }
    
    /**
     * Получает количество выполнений операции
     */
    public static long getOperationCount(String operationName) {
        AtomicLong count = operationCounters.get(operationName);
        return count != null ? count.get() : 0;
    }
    
    /**
     * Генерирует отчет о производительности
     */
    public static Map<String, Object> generateReport() {
        Map<String, Object> report = new HashMap<>();
        
        for (String operation : operationCounters.keySet()) {
            Map<String, Object> opReport = new HashMap<>();
            opReport.put("count", getOperationCount(operation));
            opReport.put("averageTimeMs", getAverageTime(operation));
            report.put(operation, opReport);
        }
        
        // Добавляем информацию о сервере
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> memoryInfo = new HashMap<>();
        memoryInfo.put("totalMemoryMB", runtime.totalMemory() / 1024 / 1024);
        memoryInfo.put("freeMemoryMB", runtime.freeMemory() / 1024 / 1024);
        memoryInfo.put("usedMemoryMB", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024);
        report.put("memory", memoryInfo);
        
        return report;
    }
    
    /**
     * Проверяет, нужно ли генерировать отчет
     */
    public static boolean shouldGenerateReport(String reportType) {
        long now = System.currentTimeMillis();
        Long lastReport = lastReportTime.get(reportType);
        
        if (lastReport == null || now - lastReport > REPORT_INTERVAL) {
            lastReportTime.put(reportType, now);
            return true;
        }
        return false;
    }
    
    /**
     * Сбрасывает статистику
     */
    public static void resetStats() {
        operationCounters.clear();
        operationTimes.clear();
        lastReportTime.clear();
    }
    
    /**
     * Получает статистику TPS сервера
     */
    public static double getServerTPS(MinecraftServer server) {
        try {
            // Используем правильные методы из MinecraftServer
            float avgTickTime = server.getAverageTickTime();
            if (avgTickTime > 0) {
                return 1000.0 / avgTickTime; // Конвертируем миллисекунды в TPS
            }
            return 20.0; // Возвращаем значение по умолчанию
        } catch (Throwable ignored) {
            return 20.0; // Возвращаем значение по умолчанию
        }
    }
}
