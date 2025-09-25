package race;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import race.net.StartRacePayload;
import race.net.RaceBoardPayload;
import race.net.SeedHandshakeC2SPayload;
import race.net.SeedAckS2CPayload;
import race.net.PlayerProgressPayload;
import race.net.GhostTrailPayload;
import race.net.ParallelPlayersPayload;
import race.net.SeedLobbyListPayload;
import race.net.JoinRequestStatusPayload;
import race.net.RaceTimeSyncS2CPayload;
import race.server.world.ServerRaceConfig;
import race.config.RaceConfig;
import race.hub.HubManager;
import race.server.world.DimensionTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;


public final class FabricRaceMod implements ModInitializer {
    @Override public void onInitialize() {
        // Регистрация сущности хомяка

        ServerRaceConfig.initializeFromSystem();
        RaceConfig.load();
        
        // Регистрируем кастомные dimension types
        DimensionTypeRegistry.register();
        PayloadTypeRegistry.playS2C().register(StartRacePayload.ID, StartRacePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(RaceBoardPayload.ID, RaceBoardPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SeedLobbyListPayload.ID, SeedLobbyListPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SeedHandshakeC2SPayload.ID, SeedHandshakeC2SPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SeedAckS2CPayload.ID, SeedAckS2CPayload.CODEC);
        // Клиент -> Сервер: активность и прогресс игрока
        PayloadTypeRegistry.playC2S().register(PlayerProgressPayload.ID, PlayerProgressPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(GhostTrailPayload.ID, GhostTrailPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ParallelPlayersPayload.ID, ParallelPlayersPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(JoinRequestStatusPayload.ID, JoinRequestStatusPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(RaceTimeSyncS2CPayload.ID, RaceTimeSyncS2CPayload.CODEC);
        
        // Инициализируем хаб при подключении игроков
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!HubManager.isHubActive()) {
                HubManager.initializeHub(server);
            }
        });
    }
}
