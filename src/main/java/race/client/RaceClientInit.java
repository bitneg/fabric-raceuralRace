package race.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import race.net.StartRacePayload;
import race.net.TpsPayload;
import race.net.RaceBoardPayload;
import race.net.RaceTimeSyncS2CPayload;
import race.net.ParallelPlayersPayload;
import race.net.GhostTrailPayload;
import race.client.GhostOverlay;

public final class RaceClientInit implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Типы пакетов уже зарегистрированы в FabricRaceMod

        // Обработчик старта гонки — установить t0/seed и активировать таймеры
        ClientPlayNetworking.registerGlobalReceiver(StartRacePayload.ID, (payload, ctx) -> {
            ctx.client().execute(() -> {
                RaceClientEvents.onRaceStart(payload.seed(), payload.t0ms());
                System.out.println("[Race] Client received race start: seed=" + payload.seed() + " t0=" + payload.t0ms());
            });
        });

        // TPS для HUD
        ClientPlayNetworking.registerGlobalReceiver(TpsPayload.ID, (payload, ctx) -> {
            ctx.client().execute(() -> EnhancedRaceHud.setTpsInfo(payload.tps(), payload.enabled()));
        });

        // Табло гонки
        ClientPlayNetworking.registerGlobalReceiver(RaceBoardPayload.ID, (payload, ctx) -> {
            ctx.client().execute(() -> HudBoardState.setRows(payload.rows()));
        });

        // Виртуальное время
        ClientPlayNetworking.registerGlobalReceiver(RaceTimeSyncS2CPayload.ID, (payload, ctx) -> {
            ctx.client().execute(() -> race.client.time.ClientSlotTimeState.put(payload.worldId(), payload.time()));
        });

        // Приём лайв‑точек параллельных игроков — шлём в DeathEchoRenderer
        ClientPlayNetworking.registerGlobalReceiver(ParallelPlayersPayload.ID, (payload, ctx) ->
                ctx.client().execute(() -> {
                    System.out.println("[Race] Клиент получил ParallelPlayersPayload: " + payload.points().size() + " точек");
                    
                    // 1) Новый дальний шлейф
                    java.util.Map<String, java.util.List<race.net.GhostTrailPayload.Point>> byPlayer = new java.util.HashMap<>();
                    for (var p : payload.points()) {
                        byPlayer.computeIfAbsent(p.name(), k -> new java.util.ArrayList<>())
                                .add(new race.net.GhostTrailPayload.Point(p.x(), p.y(), p.z()));
                    }
                    byPlayer.forEach((name, pts) ->
                            race.client.death.DeathEchoRenderer.addTrail(name, "live", pts));

                    // 2) Legacy-дымка поверх HUD (если нужна)
                    race.client.GhostOverlay.addLive("__live__", payload.points());
                })
        );

        // Приём призрачных шлейфов
        ClientPlayNetworking.registerGlobalReceiver(race.net.GhostTrailPayload.ID, (payload, ctx) ->
                ctx.client().execute(() -> {
                    System.out.println("[Race] Клиент получил GhostTrailPayload: " + payload.playerName() + 
                                     " (точек: " + payload.points().size() + ")");
                    
                    // Обрабатываем через DeathEchoRenderer
                    race.client.death.DeathEchoRenderer.addTrail(payload.playerName(), payload.cause(), payload.points());
                    
                    // Также добавляем в GhostOverlay для совместимости
                    race.client.GhostOverlay.addTrail(payload.playerName(), payload.cause(), payload.points());
                })
        );



        // Приём подтверждения сида
        ClientPlayNetworking.registerGlobalReceiver(race.net.SeedAckS2CPayload.ID, (payload, ctx) ->
                ctx.client().execute(() -> {
                    if (payload.accepted()) {
                        race.client.RaceClientEvents.setStartFromServer(payload.seed(), System.currentTimeMillis());
                        System.out.println("[Race] Client received seed ack: seed=" + payload.seed());
                    }
                })
        );



        // Тикер RTA/прогресса
        RaceClientEvents.hookClientTick();
        
        // Клиентский эмиттер дымки (удален - не нужен в новой версии)
        
        // Пакет маркеров смерти уже зарегистрирован в FabricRaceMod
        
        // Рендер маркеров смерти и шлейфов
        WorldRenderEvents.END.register(race.client.death.DeathEchoRenderer::render);
        
        // Инициализируем HUD
        EnhancedRaceHud.initOnce();
        
        // Рендер HUD гонки
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            EnhancedRaceHud.render(drawContext);
        });
        
        // Очищаем кэш при отключении
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> 
            race.client.death.DeathEchoRenderer.clearAll());
    }
}
