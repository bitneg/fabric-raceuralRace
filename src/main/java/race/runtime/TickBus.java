package race.runtime;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class TickBus {
    private static final int TICK_2S = 40;
    private static int tick;
    private static final Queue<Runnable> nextTick = new ConcurrentLinkedQueue<>();

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(TickBus::onTick);
    }

    private static void onTick(MinecraftServer server) {
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
            
            // TPS информация каждые 20 тиков (1 секунда) для более точного отображения
            if (tick % 20 == 0) {
                try {
                    double currentTPS = race.server.RaceServerInit.getCurrentTPS();
                    boolean enabled = race.server.RaceServerInit.isTpsDisplayEnabled();
                    var payload = new race.net.TpsPayload(currentTPS, enabled);
                    for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, payload);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    public static <T> java.util.concurrent.CompletableFuture<T> submitNextTick(java.util.function.Supplier<T> task) {
        java.util.concurrent.CompletableFuture<T> future = new java.util.concurrent.CompletableFuture<>();
        nextTick.add(new Runnable() {
            @Override
            public void run() {
                try {
                    T result = task.get();
                    future.complete(result);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            }
        });
        return future;
    }
}