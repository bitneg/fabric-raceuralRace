package race.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import race.cache.PerformanceCache;
import race.memory.MemoryManager;
import race.monitor.PerformanceMonitor;
import race.batch.BatchProcessor;
import race.auto.AutoOptimizer;
import race.performance.JoinOptimizer;
import race.performance.WorldCreationOptimizer;

import java.util.Map;

/**
 * Команды для мониторинга производительности
 */
public class PerformanceCommands {
    
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(net.minecraft.server.command.CommandManager.literal("race")
            .then(net.minecraft.server.command.CommandManager.literal("perf")
                .then(net.minecraft.server.command.CommandManager.literal("stats")
                    .executes(PerformanceCommands::showStats))
                .then(net.minecraft.server.command.CommandManager.literal("memory")
                    .executes(PerformanceCommands::showMemory))
                .then(net.minecraft.server.command.CommandManager.literal("cache")
                    .executes(PerformanceCommands::showCache))
                .then(net.minecraft.server.command.CommandManager.literal("cleanup")
                    .executes(PerformanceCommands::forceCleanup))
                .then(net.minecraft.server.command.CommandManager.literal("auto")
                    .executes(PerformanceCommands::showAutoOptimization))
                .then(net.minecraft.server.command.CommandManager.literal("optimize")
                    .then(net.minecraft.server.command.CommandManager.argument("level", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 5))
                        .executes(PerformanceCommands::setOptimizationLevel)))
                .then(net.minecraft.server.command.CommandManager.literal("join")
                    .executes(PerformanceCommands::showJoinStats))
                .then(net.minecraft.server.command.CommandManager.literal("worlds")
                    .executes(PerformanceCommands::showWorldCreationStats))
            )
        );
    }
    
    private static int showStats(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        
        try {
            var report = PerformanceMonitor.generateReport();
            source.sendFeedback(() -> Text.literal("=== Race Performance Stats ===").formatted(net.minecraft.util.Formatting.GOLD), false);
            
            for (Map.Entry<String, Object> entry : report.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> opData = (Map<String, Object>) entry.getValue();
                    String operation = entry.getKey();
                    long count = (Long) opData.get("count");
                    double avgTime = (Double) opData.get("averageTimeMs");
                    
                    source.sendFeedback(() -> Text.literal(String.format("%s: %d calls, %.2f ms avg", 
                        operation, count, avgTime)).formatted(net.minecraft.util.Formatting.YELLOW), false);
                }
            }
            
            return 1;
        } catch (Exception e) {
            source.sendFeedback(() -> Text.literal("Error getting stats: " + e.getMessage())
                .formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
    }
    
    private static int showMemory(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        
        try {
            var stats = MemoryManager.getMemoryStats();
            source.sendFeedback(() -> Text.literal("=== Memory Usage ===").formatted(net.minecraft.util.Formatting.GOLD), false);
            
            for (Map.Entry<String, Long> entry : stats.entrySet()) {
                source.sendFeedback(() -> Text.literal(String.format("%s: %d entries", 
                    entry.getKey(), entry.getValue())).formatted(net.minecraft.util.Formatting.YELLOW), false);
            }
            
            boolean pressure = MemoryManager.isMemoryPressure();
            source.sendFeedback(() -> Text.literal("Memory pressure: " + (pressure ? "HIGH" : "NORMAL"))
                .formatted(pressure ? net.minecraft.util.Formatting.RED : net.minecraft.util.Formatting.GREEN), false);
            
            return 1;
        } catch (Exception e) {
            source.sendFeedback(() -> Text.literal("Error getting memory info: " + e.getMessage())
                .formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
    }
    
    private static int showCache(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        
        try {
            var batchStats = BatchProcessor.getBatchStats();
            source.sendFeedback(() -> Text.literal("=== Cache Status ===").formatted(net.minecraft.util.Formatting.GOLD), false);
            
            for (Map.Entry<String, Integer> entry : batchStats.entrySet()) {
                source.sendFeedback(() -> Text.literal(String.format("Batch %s: %d pending", 
                    entry.getKey(), entry.getValue())).formatted(net.minecraft.util.Formatting.YELLOW), false);
            }
            
            return 1;
        } catch (Exception e) {
            source.sendFeedback(() -> Text.literal("Error getting cache info: " + e.getMessage())
                .formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
    }
    
    private static int forceCleanup(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        
        try {
            MemoryManager.forceCleanupAll();
            PerformanceCache.clearAll();
            BatchProcessor.clearAllBatches();
            PerformanceMonitor.resetStats();
            
            source.sendFeedback(() -> Text.literal("All caches and statistics cleared!")
                .formatted(net.minecraft.util.Formatting.GREEN), false);
            
            return 1;
        } catch (Exception e) {
            source.sendFeedback(() -> Text.literal("Error during cleanup: " + e.getMessage())
                .formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
    }
    
    private static int showAutoOptimization(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        
        try {
            int level = AutoOptimizer.getOptimizationLevel();
            String description = getOptimizationDescription(level);
            
            source.sendFeedback(() -> Text.literal("=== Auto-Optimization Status ===")
                .formatted(net.minecraft.util.Formatting.GOLD), false);
            source.sendFeedback(() -> Text.literal("Current level: " + level + " - " + description)
                .formatted(net.minecraft.util.Formatting.YELLOW), false);
            
            return 1;
        } catch (Exception e) {
            source.sendFeedback(() -> Text.literal("Error getting auto-optimization info: " + e.getMessage())
                .formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
    }
    
    private static int setOptimizationLevel(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        
        try {
            int level = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "level");
            AutoOptimizer.setOptimizationLevel(level);
            
            String description = getOptimizationDescription(level);
            source.sendFeedback(() -> Text.literal("Optimization level set to " + level + " - " + description)
                .formatted(net.minecraft.util.Formatting.GREEN), false);
            
            return 1;
        } catch (Exception e) {
            source.sendFeedback(() -> Text.literal("Error setting optimization level: " + e.getMessage())
                .formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
    }
    
    private static String getOptimizationDescription(int level) {
        return switch (level) {
            case 1 -> "Minimal optimization (low load)";
            case 2 -> "Light optimization (medium load)";
            case 3 -> "Medium optimization (high load)";
            case 4 -> "Aggressive optimization (very high load)";
            case 5 -> "Maximum optimization (critical load)";
            default -> "Unknown level";
        };
    }
    
    private static int showJoinStats(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        
        try {
            var stats = JoinOptimizer.getQueueStats();
            source.sendFeedback(() -> Text.literal("=== Join Queue Status ===")
                .formatted(net.minecraft.util.Formatting.GOLD), false);
            
            source.sendFeedback(() -> Text.literal(String.format("Pending joins: %s", 
                stats.get("pending"))).formatted(net.minecraft.util.Formatting.YELLOW), false);
            source.sendFeedback(() -> Text.literal(String.format("Processing joins: %s", 
                stats.get("processing"))).formatted(net.minecraft.util.Formatting.YELLOW), false);
            source.sendFeedback(() -> Text.literal(String.format("Max concurrent: %s", 
                stats.get("maxConcurrent"))).formatted(net.minecraft.util.Formatting.YELLOW), false);
            
            return 1;
        } catch (Exception e) {
            source.sendFeedback(() -> Text.literal("Error getting join stats: " + e.getMessage())
                .formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
    }
}
