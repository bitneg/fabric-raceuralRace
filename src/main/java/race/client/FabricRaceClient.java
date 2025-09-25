package race.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import race.client.hub.RaceHubState;
import race.net.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import race.client.ui.RaceMenuScreen;
import org.lwjgl.glfw.GLFW;

public final class FabricRaceClient implements ClientModInitializer {
    private static volatile boolean hudEnabled = true;
    private static KeyBinding openMenuKey;
    private static KeyBinding toggleHudKey;
    private static KeyBinding openAchievementsKey;

    @Override
    public void onInitializeClient() {
        // Инициализируем основную логику клиента
        new RaceClientInit().onInitializeClient();

        // Если сид был выбран в главном меню (RaceHubState), отправляем его сразу после JOIN
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                client.execute(() -> {
                    hudEnabled = true;
                    if (RaceHubState.hasPendingSeed()) {
                        long seed = RaceHubState.consumePendingSeed();
                        ClientPlayNetworking.send(new SeedHandshakeC2SPayload(seed));
                    }
                })
        ); // [1][2]

        // ACK о сиде: запускаем таймер при успехе
        ClientPlayNetworking.registerGlobalReceiver(SeedAckS2CPayload.ID, (payload, ctx) ->
                ctx.client().execute(() -> {
                    if (payload.accepted()) {
                        RaceClientEvents.setStartFromServer(payload.seed(), System.currentTimeMillis());
                    }
                })
        ); // [1]

        // Периодическая отправка своей активности и прогресса (через ProgressSyncManager)
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null || client.getNetworkHandler() == null) return;
            String activity = EnhancedRaceHud.inferActivityPublic(client);
            var payload = new PlayerProgressPayload(
                    client.getSession().getUsername(),
                    RaceClientEvents.getRtaMs(),
                    RaceProgressTracker.getCurrentStage(),
                    java.util.Map.of(),
                    client.world.getRegistryKey().getValue().toString(),
                    activity
            );
            try {
                ClientPlayNetworking.send(payload);
            } catch (Throwable ignored) {}
        });

        // Список сидов/миров для меню
        ClientPlayNetworking.registerGlobalReceiver(SeedLobbyListPayload.ID, (payload, ctx) ->
                ctx.client().execute(() -> {
                    if (MinecraftClient.getInstance().currentScreen instanceof RaceMenuScreen screen) {
                        screen.setLobby(payload.entries());
                    }
                })
        );

        HudRenderCallback.EVENT.register(FabricRaceClient::onHudRender); // [3]
        // Приём призрачных шлейфов
        ClientPlayNetworking.registerGlobalReceiver(GhostTrailPayload.ID, (payload, ctx) ->
                ctx.client().execute(() -> GhostOverlay.addTrail(payload.playerName(), payload.cause(), payload.points()))
        );
        // Приём лайв‑точек параллельных игроков
        ClientPlayNetworking.registerGlobalReceiver(ParallelPlayersPayload.ID, (payload, ctx) ->
                ctx.client().execute(() -> GhostOverlay.addLive("__live__", payload.points()))
        );
        
        // Приём статуса join-запроса
        ClientPlayNetworking.registerGlobalReceiver(JoinRequestStatusPayload.ID, (payload, ctx) ->
                ctx.client().execute(() -> {
                    if (MinecraftClient.getInstance().currentScreen instanceof RaceMenuScreen screen) {
                        screen.setJoinRequestStatus(payload.hasActiveRequest(), payload.targetPlayer());
                    }
                })
        );

        // Горячая клавиша для открытия меню гонки без ESC
        openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.fabric_race.open_menu",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                "category.fabric_race"
        ));
        toggleHudKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.fabric_race.toggle_hud",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                "category.fabric_race"
        ));
        openAchievementsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.fabric_race.open_achievements",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "category.fabric_race"
        ));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            while (openMenuKey.wasPressed()) {
                client.setScreen(new RaceMenuScreen(null));
            }
            while (toggleHudKey.wasPressed()) {
                hudEnabled = !hudEnabled;
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("Race HUD: " + (hudEnabled ? "ON" : "OFF")), true);
                }
            }
            while (openAchievementsKey.wasPressed()) {
                client.setScreen(new race.client.ui.AchievementsScreen(client.currentScreen));
            }
        });
    }

    private static void onHudRender(DrawContext ctx, RenderTickCounter tickCounter) {
        if (!hudEnabled) return;
        
        // Используем новый улучшенный HUD
        EnhancedRaceHud.render(ctx, tickCounter);
        GhostOverlay.render(ctx, tickCounter);
    }
    
}
