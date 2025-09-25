package race.runtime;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import race.net.RaceNetwork;
import race.world.RaceWorldMaintenance;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

public final class TickBus {
    private static final int TICK_1S = 20;
    private static final int TICK_2S = 40;
    private static final int TICK_5S = 100;

    private static int tick;
    private static final Queue<Runnable> nextTick = new ConcurrentLinkedQueue<>();

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(TickBus::onTick);
    }

    private static void onTick(MinecraftServer server) {
        long tickStartTime = race.monitor.PerformanceMonitor.startOperation("serverTick");
        tick++;
        
        try {
            // исполняем отложенные задачи
            for (int i = 0, s = nextTick.size(); i < s; i++) {
                Runnable r = nextTick.poll();
                if (r != null) r.run();
            }
        
        // Обновление борда каждые 2 секунды
        if (tick % TICK_2S == 0) {
            try {
                var rows = race.server.phase.RacePhaseManager.getRaceBoardData(server);
                var payload = new race.net.RaceBoardPayload(rows);
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, payload);
                }
            } catch (Throwable ignored) {}
        }
        
        // TPS информация каждые 2 секунды
        if (tick % TICK_2S == 0) {
            try {
                double currentTPS = race.server.RaceServerInit.getCurrentTPS();
                boolean tpsEnabled = race.server.RaceServerInit.isTpsDisplayEnabled();
                if (tpsEnabled) {
                    var tpsPayload = new race.net.TpsPayload(currentTPS, true);
                    for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, tpsPayload);
                    }
                }
            } catch (Throwable ignored) {}
        }
        
        // Призрачные фигуры каждые 2 секунды
        if (tick % TICK_2S == 0) {
            try { 
                race.server.death.DeathEchoManager.tickGhosts(server); 
            } catch (Throwable ignored) {}
        }
        
        // Очистка призрачных фигур каждые 5 секунд
        if (tick % TICK_5S == 0) {
            try {
                for (var w : server.getWorlds()) {
                    java.util.List<net.minecraft.entity.decoration.ArmorStandEntity> list = w.getEntitiesByClass(
                        net.minecraft.entity.decoration.ArmorStandEntity.class, 
                        new net.minecraft.util.math.Box(-3.0E7, -3.0E7, -3.0E7, 3.0E7, 3.0E7, 3.0E7), 
                        as -> as.getCommandTags().contains("race_ghost_fig"));
                    for (var e : list) {
                        if (e.age > 100) e.discard();
                    }
                }
            } catch (Throwable ignored) {}
        }
        
        // Обновление прогресса игроков каждую секунду
        if (tick % TICK_1S == 0) {
            race.batch.BatchProcessor.processPlayersBatch(server, p -> RaceNetwork.updateProgress1Hz(p));
        }
        
        // Параллельные игроки каждые 2 секунды
        if (tick % TICK_2S == 0) {
            race.batch.BatchProcessor.processPlayersBatch(server, p -> RaceNetwork.updateParallelPlayers2Hz(p));
        }
        
        // Низкоприоритетные задачи каждые 5 секунд
        if (tick % TICK_5S == 0) {
            RaceWorldMaintenance.lowPriority5s(server);
        }
        
        // Оптимизированная система freeze - проверка раз в 10 тиков
        if (tick % 10 == 0) {
            try {
                race.control.Freeze.tick(server.getPlayerManager().getPlayerList(), tick);
            } catch (Throwable ignored) {}
        }
        
        // Очистка кешей каждые 10 секунд
        if (tick % (TICK_5S * 2) == 0) {
            try {
                race.cache.PerformanceCache.clearExpired();
            } catch (Throwable ignored) {}
        }
        
        // Адаптивное управление памятью каждые 30 секунд
        if (tick % (TICK_5S * 6) == 0) {
            try {
                race.memory.MemoryManager.adaptiveCleanup();
            } catch (Throwable ignored) {}
        }
        
        // Генерация отчета о производительности каждую минуту
        if (tick % (TICK_5S * 12) == 0) {
            try {
                if (race.monitor.PerformanceMonitor.shouldGenerateReport("main")) {
                    var report = race.monitor.PerformanceMonitor.generateReport();
                    System.out.println("[Race] Performance Report: " + report);
                }
            } catch (Throwable ignored) {}
        }
        
        // Автоматическая оптимизация каждые 5 минут
        if (tick % (TICK_5S * 60) == 0) {
            try {
                race.auto.AutoOptimizer.checkAndOptimize(server);
            } catch (Throwable ignored) {}
        }
        
        // Обработка очереди входов каждые 2 тика (для снижения нагрузки)
        if (tick % 2 == 0) {
            try {
                race.performance.JoinOptimizer.processNextPendingJoin();
            } catch (Throwable ignored) {}
        }
        
        // Очистка старых задач предзагрузки каждые 10 секунд
        if (tick % (TICK_5S * 2) == 0) {
            try {
                race.performance.ChunkPreloader.cleanupOldTasks();
            } catch (Throwable ignored) {}
        }
        
        // Очистка старых задач создания миров каждые 30 секунд
        if (tick % (TICK_5S * 6) == 0) {
            try {
                race.performance.WorldCreationOptimizer.cleanupOldTasks();
            } catch (Throwable ignored) {}
        }
        
        } finally {
            race.monitor.PerformanceMonitor.endOperation("serverTick", tickStartTime);
        }
    }

    private static void runPlayers(MinecraftServer server, Consumer<ServerPlayerEntity> task) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            task.accept(p);
        }
    }

    public static void submitNextTick(Runnable r) { 
        nextTick.add(r); 
    }
}
