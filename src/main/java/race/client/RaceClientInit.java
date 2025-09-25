package race.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import race.net.StartRacePayload;
import race.net.TpsPayload;
import race.net.RaceBoardPayload;
import race.net.RaceTimeSyncS2CPayload;

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

        // Тикер RTA/прогресса
        RaceClientEvents.hookClientTick();
    }
}
