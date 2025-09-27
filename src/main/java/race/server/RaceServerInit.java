package race.server;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.text.Text;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import race.net.*;
import race.server.phase.NoCollisionUtil;
import race.server.phase.RacePhaseManager;
import race.server.phase.PhaseState;
import race.server.world.PersonalWorlds;
import race.server.world.ServerRaceConfig;
import race.hub.HubManager;
import race.server.world.EnhancedWorldManager;
import race.server.death.DeathEchoManager;
import race.server.AchievementManager;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.world.chunk.ChunkStatus;
import java.util.UUID;
import race.server.commands.RaceCommands;
import race.config.RaceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static race.server.death.DeathEchoManager.tryParseSeed;

public final class RaceServerInit implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(RaceServerInit.class);
    
    private static volatile boolean active = false;
    private static volatile long seed = -1L;
    private static volatile long t0ms = -1L;
    private static int tick = 0;
    
    private static final java.util.Map<String, java.util.Set<java.util.UUID>> announcedTeams = new java.util.HashMap<>();
    private static final ConcurrentHashMap<RegistryKey<World>, Long> WORLDTIME = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<RegistryKey<World>, Long> WORLDTIMESPEED = new ConcurrentHashMap<>();
    
    private static final java.util.Map<java.util.UUID, Integer> floatGrace = java.util.Collections.emptyMap();
    public static final java.util.Set<java.util.UUID> personalStarted = new java.util.HashSet<>();
    public static final java.util.Set<java.util.UUID> frozenUntilStart = new java.util.HashSet<>();
    private static final java.util.Map<java.util.UUID, net.minecraft.util.math.BlockPos> freezePos = new java.util.HashMap<>();
    
    private static volatile boolean displayParallelPlayers = true;
    private static final java.util.Map<String, Long> lastTimeOfDay = new java.util.concurrent.ConcurrentHashMap<>();
    
    // Join-запросы
    private static final java.util.Map<java.util.UUID, PendingJoin> pendingJoins = new java.util.concurrent.ConcurrentHashMap<>();
    public record PendingJoin(java.util.UUID target, int ticksLeft) {}
    
    // Кэш для производительности
    private static final java.util.Map<java.util.UUID, String> playerOriginalWorlds = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Long> playerSeedCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, String> playerWorldCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int saveInterval = 200; // 10 сек при 20 TPS
    
    // TPS мониторинг
    private static final java.util.Queue<Long> tickTimes = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static volatile double currentTPS = 20.0;
    private static volatile boolean tpsDisplayEnabled = false;
    
    // Производительность
    private static final java.util.Map<java.util.UUID, Long> lastPlayerUpdate = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Long> lastMobCheck = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Long> lastParallelUpdate = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<java.util.UUID> BASTIONSEEN = new java.util.HashSet<>();
    
    // Адаптивные настройки
    private static final java.util.concurrent.atomic.AtomicInteger performanceLevel = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.atomic.AtomicInteger autoOptimizationLevel = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.atomic.AtomicLong lastGcTime = new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicLong lastMemoryCheck = new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicInteger consecutiveLowTps = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.atomic.AtomicInteger consecutiveHighTps = new java.util.concurrent.atomic.AtomicInteger(0);
    
    private static volatile boolean autoOptimizationEnabled = true;
    private static volatile int ghostQualityLevel = 2; // 0, 1, 2, 3
    private static volatile boolean adaptiveGhostQuality = true;

    // Публичные методы API
    public static boolean isDisplayParallelPlayers() { return displayParallelPlayers; }
    public static void setDisplayParallelPlayers(boolean v) { displayParallelPlayers = v; }
    public static boolean isActive() { return active; }
    public static long getSeed() { return seed; }
    public static long getT0ms() { return t0ms; }
    
    // TPS методы
    public static double getCurrentTPS() { return currentTPS; }
    public static boolean isTpsDisplayEnabled() { return tpsDisplayEnabled; }
    public static void setTpsDisplayEnabled(boolean enabled) { tpsDisplayEnabled = enabled; }
    
    // Производительность
    public static int getPerformanceLevel() { return performanceLevel.get(); }
    public static String getPerformanceStatus() {
        int level = performanceLevel.get();
        return switch (level) {
            case 0 -> "Нормальная";
            case 1 -> "Средняя нагрузка";
            case 2 -> "Высокая нагрузка";
            default -> "Критическая";
        };
    }
    
    public static int getAutoOptimizationLevel() { return autoOptimizationLevel.get(); }
    public static boolean isAutoOptimizationEnabled() { return autoOptimizationEnabled; }
    public static void setAutoOptimizationEnabled(boolean enabled) { autoOptimizationEnabled = enabled; }
    
    // Ghost качество
    public static int getGhostQualityLevel() { return ghostQualityLevel; }
    public static void setGhostQualityLevel(int level) { ghostQualityLevel = Math.max(0, Math.min(3, level)); }
    public static boolean isAdaptiveGhostQuality() { return adaptiveGhostQuality; }
    public static void setAdaptiveGhostQuality(boolean enabled) { adaptiveGhostQuality = enabled; }
    
    public static int getAdaptiveGhostQuality() {
        if (!adaptiveGhostQuality) return ghostQualityLevel;
        double tps = currentTPS;
        if (tps < 15.0) return 1;
        if (tps < 18.0) return 2;
        return 3;
    }
    
    public static int getGhostParticleCount(int baseCount) {
        int quality = getAdaptiveGhostQuality();
        return switch (quality) {
            case 0 -> 0; // отключено
            case 1 -> baseCount / 4; // 25%
            case 2 -> baseCount / 2; // 50%  
            case 3 -> baseCount; // 100%
            default -> baseCount / 2;
        };
    }
    
    public static int getGhostUpdateInterval() {
        int quality = getAdaptiveGhostQuality();
        return switch (quality) {
            case 0 -> 0; // отключено
            case 1 -> 40; // 2 сек
            case 2 -> 20; // 1 сек
            case 3 -> 10; // 0.5 сек
            default -> 20;
        };
    }

    @Override
    public void onInitialize() {
        // Инициализация при запуске сервера
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            race.server.world.ReturnPointRegistry.initialize(server);
            race.server.world.PreferredWorldRegistry.initialize(server);
            race.server.SlotTimeService.init();
        });
        
        // Подключение игрока
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var player = handler.player;
            LOGGER.info("[Race] Player joined: {}", player.getName().getString());
            
            try {
                // Проверяем, есть ли сохранённая точка возврата в race мире
                boolean hasReturnPoint = race.server.world.ReturnPointRegistry.hasReturnPoint(player.getUuid());
                long playerSeed = race.hub.HubManager.getPlayerSeedChoice(player.getUuid());
                
                if (hasReturnPoint && playerSeed > 0) {
                    // Возвращаем игрока в его персональный мир, если он был там ранее
                    var personalWorld = race.server.world.EnhancedWorldManager.getOrCreateWorld(
                        server, player.getUuid(), playerSeed, net.minecraft.world.World.OVERWORLD);
                    if (personalWorld != null) {
                        // Телепортируем в персональный мир
                        race.tp.SafeTeleport.toWorldSpawn(player, personalWorld);
                        LOGGER.info("[Race] Player returned to saved position in race world: {}", player.getName().getString());
                    }
                } else {
                    // ИСПРАВЛЕНИЕ: Если у игрока нет сохраненной позиции в race мире, телепортируем в хаб
                    if (race.hub.HubManager.isHubActive()) {
                        race.hub.HubManager.teleportToHub(player);
                        LOGGER.info("[Race] Player teleported to hub: {}", player.getName().getString());
                    } else {
                        LOGGER.warn("[Race] Hub is not active, player will spawn in default world: {}", player.getName().getString());
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("[Race] Error handling player spawn: {}", t.getMessage());
                // Fallback: пытаемся телепортировать в хаб при ошибке
                try {
                    if (race.hub.HubManager.isHubActive()) {
                        race.hub.HubManager.teleportToHub(player);
                        LOGGER.info("[Race] Player teleported to hub as fallback: {}", player.getName().getString());
                    }
                } catch (Throwable fallbackError) {
                    LOGGER.error("[Race] Failed to teleport player to hub: {}", fallbackError.getMessage());
                }
            }
            
            // Отправляем список лобби при входе
            sendLobbyListTo(player);
        });
        
        // Регистрация пакетов
        try {
            net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(
                race.net.SeedHandshakeC2SPayload.ID, race.net.SeedHandshakeC2SPayload.CODEC);
        } catch (Throwable ignored) {}
        
        try {
            net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(
                race.net.SeedAckS2CPayload.ID, race.net.SeedAckS2CPayload.CODEC);
        } catch (Throwable ignored) {}
        
        try {
            net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(
                race.net.StartRacePayload.ID, race.net.StartRacePayload.CODEC);
        } catch (Throwable ignored) {}
        
        try {
            net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(
                race.net.JoinRequestStatusPayload.ID, race.net.JoinRequestStatusPayload.CODEC);
        } catch (Throwable ignored) {}
        
        try {
            net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(
                race.net.TpsPayload.ID, race.net.TpsPayload.CODEC);
        } catch (Throwable ignored) {}
        
        try {
            net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(
                race.net.SeedLobbyListPayload.ID, race.net.SeedLobbyListPayload.CODEC);
        } catch (Throwable ignored) {}
        
        // Включаем TPS отображение
        setTpsDisplayEnabled(true);
        
        // Обработчик C2S пакета выбора сида
        ServerPlayNetworking.registerGlobalReceiver(SeedHandshakeC2SPayload.ID, (payload, ctx) -> {
            long requested = payload.seed();
            ctx.server().execute(() -> {
                ServerPlayerEntity p = ctx.player();
                boolean isDedicated = p.getServer() instanceof net.minecraft.server.dedicated.MinecraftDedicatedServer;
                
                if (!isDedicated) {
                    p.sendMessage(net.minecraft.text.Text.literal("Race - работает только на dedicated серверах!")
                        .formatted(net.minecraft.util.Formatting.RED), false);
                    ServerPlayNetworking.send(p, new SeedAckS2CPayload(false, "singleplayer_not_supported", requested));
                    return;
                }
                
                if (requested <= 0) {
                    p.sendMessage(net.minecraft.text.Text.literal("Недопустимый сид: " + requested + ". Выберите корректный race seed.")
                        .formatted(net.minecraft.util.Formatting.RED), false);
                    return;
                }
                
                // ТОЛЬКО сохраняем выбор сида, НЕ телепортируем
                HubManager.setPlayerSeedChoice(p.getUuid(), requested);
                ServerPlayNetworking.send(p, new SeedAckS2CPayload(true, "", requested));
                
                // Предварительно создаем и прогреваем мир, но не телепортируем
                try {
                    var dst = EnhancedWorldManager.getOrCreateWorld(p.getServer(), p.getUuid(), requested, net.minecraft.world.World.OVERWORLD);
                    race.server.world.SpawnCache.warmupAndCache(dst, dst.getSpawnPos(), 2);
                    LOGGER.info("[Race] Preloaded world for player with seed: {} -> {}", p.getName().getString(), requested);
                } catch (Throwable t) {
                    LOGGER.warn("[Race] Failed to preload world: {}", t.getMessage());
                }
                
                // Отправляем подтверждение готовности к старту (без телепортации)
                p.sendMessage(net.minecraft.text.Text.literal("Сид " + requested + " выбран! Нажмите 'Готов' для входа в мир.")
                    .formatted(net.minecraft.util.Formatting.GREEN), false);
            });
        });
        
        // Регистрация команд
        RaceCommands.register();
        race.server.commands.RaceTimeCommands.register();
        
        try {
            race.server.phase.PhaseEvents.register();
        } catch (Throwable ignored) {}
        
        // Серверные тики - START
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            // Населяем персональные миры эхо смерти
            try {
                for (ServerWorld world : server.getWorlds()) {
                    DeathEchoManager.populateWorld(world);
                }
            } catch (Throwable ignored) {}
        });
        
        // Серверные тики - END
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tick++;
            
            // TPS мониторинг
            updateTPS(System.currentTimeMillis());
            
            // Периодическая отправка ParallelPlayersPayload
            if (tick % 40 == 0 && displayParallelPlayers) { // каждые 2 секунды
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    ServerWorld playerWorld = player.getServerWorld();
                    if (isPersonal(playerWorld)) {
                        try {
                            // ИСПРАВЛЕНИЕ: Отправляем данные только о игроках из ДРУГИХ миров с тем же сидом
                            race.net.ParallelPlayersPayload payload = race.net.ParallelPlayersPayload.buildBucketed(player);
                            
                            // Фильтруем только игроков из других миров с тем же сидом
                            var filteredPoints = payload.points().stream()
                                .filter(point -> {
                                    // Проверяем, что игрок находится в ДРУГОМ мире с тем же сидом
                                    for (ServerPlayerEntity otherPlayer : server.getPlayerManager().getPlayerList()) {
                                        if (otherPlayer.getGameProfile().getName().equals(point.name()) && 
                                            otherPlayer.getServerWorld() != playerWorld) {
                                            // Проверяем, что у них одинаковый сид
                                            long playerSeed = tryParseSeed(playerWorld.getRegistryKey());
                                            long otherSeed = tryParseSeed(otherPlayer.getServerWorld().getRegistryKey());
                                            if (playerSeed == otherSeed && playerSeed >= 0) {
                                                return true;
                                            }
                                        }
                                    }
                                    return false;
                                })
                                .toList();
                            
                            if (!filteredPoints.isEmpty()) {
                                var filteredPayload = new race.net.ParallelPlayersPayload(filteredPoints);
                                filteredPayload.sendTo(player);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
            
            // Заморозка игроков
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (frozenUntilStart.contains(player.getUuid())) {
                    if (freezePos.containsKey(player.getUuid())) {
                        var pos = freezePos.get(player.getUuid());
                        if (player.getBlockPos().getSquaredDistance(pos) > 4) {
                            player.requestTeleport(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                        }
                    }
                }
            }
            
            // Обновление join requests
            pendingJoins.entrySet().removeIf(entry -> {
                PendingJoin pj = entry.getValue();
                if (pj.ticksLeft() <= 0) {
                    // Выполняем телепортацию
                    try {
                        ServerPlayerEntity src = server.getPlayerManager().getPlayer(entry.getKey());
                        ServerPlayerEntity dst = server.getPlayerManager().getPlayer(pj.target());
                        
                        if (src != null && dst != null && dst.isAlive()) {
                            // Принудительно загружаем чанк перед телепортацией
                            try {
                                var world = dst.getServerWorld();
                                var chunkPos = new net.minecraft.util.math.ChunkPos(
                                    (int) Math.floor(dst.getX()) >> 4,
                                    (int) Math.floor(dst.getZ()) >> 4
                                );
                                
                                // Загружаем чанк с полной генерацией
                                world.getChunk(chunkPos.x, chunkPos.z, net.minecraft.world.chunk.ChunkStatus.FULL, true);
                                
                                // Дополнительно загружаем соседние чанки для стабильности
                                for (int dx = -1; dx <= 1; dx++) {
                                    for (int dz = -1; dz <= 1; dz++) {
                                        world.getChunk(chunkPos.x + dx, chunkPos.z + dz, net.minecraft.world.chunk.ChunkStatus.FULL, true);
                                    }
                                }
                            } catch (Exception e) {
                                LOGGER.warn("[Race] Failed to preload chunks for join: {}", e.getMessage());
                            }
                            
                            // Телепортируем к целевому игроку
                            src.teleport(dst.getServerWorld(), dst.getX(), dst.getY(), dst.getZ(), dst.getYaw(), dst.getPitch());
                            
                            // Замораживаем до начала гонки, если гонка не активна
                            if (!isRaceActive()) {
                                freezePlayerUntilStart(src);
                            }
                            
                            src.sendMessage(Text.literal("Вы присоединились к " + dst.getName().getString() + "!")
                                .formatted(net.minecraft.util.Formatting.GREEN), false);
                            
                            LOGGER.info("[Race] Player {} joined {} successfully", src.getName().getString(), dst.getName().getString());
                        }
                    } catch (Throwable t) {
                        LOGGER.warn("[Race] Error processing join request: {}", t.getMessage());
                    }
                    return true;
                } else {
                    pendingJoins.put(entry.getKey(), new PendingJoin(pj.target(), pj.ticksLeft() - 1));
                    return false;
                }
            });
            
            // Запись последних позиций для death echoes
            try {
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    DeathEchoManager.recordTick(p);
                }
            } catch (Throwable ignored) {}
            
            // Тик призраков
            try {
                DeathEchoManager.tickGhosts(server);
            } catch (Throwable ignored) {}
        });
        
        // События смерти и респавна
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.ALLOW_DEATH.register((player, source, amount) -> {
            try {
                DeathEchoManager.onPlayerDeath(player, source);
            } catch (Throwable ignored) {}
            return true;
        });
        
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity instanceof net.minecraft.entity.boss.dragon.EnderDragonEntity dragon) {
                for (net.minecraft.entity.player.PlayerEntity player : entity.getWorld().getPlayers()) {
                    if (player instanceof ServerPlayerEntity serverPlayer) {
                        String worldKey = serverPlayer.getServerWorld().getRegistryKey().getValue().toString();
                        if (worldKey.startsWith("fabric_race:")) {
                            double distance = serverPlayer.squaredDistanceTo(dragon);
                            if (distance <= 100.0) { // 10 блоков
                                AchievementManager.checkAndGrantAchievements(serverPlayer, "kill_dragon", serverPlayer.getBlockPos());
                            }
                        }
                    }
                }
            }
            return true;
        });
        
        // Копирование после смерти
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            try {
                String oldWorldKey = oldPlayer.getServerWorld().getRegistryKey().getValue().toString();
                if (oldWorldKey.startsWith("fabric_race:")) {
                    savePlayerOriginalWorld(newPlayer.getUuid(), oldWorldKey);
                    
                    // Устанавливаем точку респавна в личном мире
                    long playerSeed = race.hub.HubManager.getPlayerSeedChoice(newPlayer.getUuid());
                    if (playerSeed > 0) {
                        String originalWorldKey = getPlayerOriginalWorld(newPlayer.getUuid());
                        int originalSlot = extractSlotFromWorldKey(originalWorldKey);
                        LOGGER.info("[Race] Player died in world: {} -> original slot: {}", 
                                  newPlayer.getName().getString(), oldWorldKey, originalSlot);
                        
                        net.minecraft.server.world.ServerWorld mainPersonalWorld = null;
                        if (originalSlot > 0) {
                            mainPersonalWorld = race.server.world.EnhancedWorldManager
                                .getOrCreateWorldForGroup(newPlayer.getServer(), originalSlot, playerSeed, net.minecraft.world.World.OVERWORLD);
                        } else {
                            mainPersonalWorld = race.server.world.EnhancedWorldManager.getOrCreateWorld(
                                newPlayer.getServer(), newPlayer.getUuid(), playerSeed, net.minecraft.world.World.OVERWORLD);
                        }
                        
                        if (mainPersonalWorld != null) {
                            LOGGER.info("[Race] Player died in world: {} -> setting respawn to slot {} world: {}", 
                                      newPlayer.getName().getString(), oldWorldKey, originalSlot, mainPersonalWorld.getRegistryKey().getValue());
                            newPlayer.setSpawnPoint(mainPersonalWorld.getRegistryKey(), mainPersonalWorld.getSpawnPos(), 0.0f, false, true);
                        }
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("[Race] Error during respawn setup: {}", t.getMessage());
            }
        });
        
        // После респавна
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            String key = newPlayer.getServerWorld().getRegistryKey().getValue().getNamespace();
            String worldKey = newPlayer.getServerWorld().getRegistryKey().getValue().toString();
            
            if ("fabric_race".equals(key) && !worldKey.contains("nether") && !worldKey.contains("end")) {
                // Overworld
                try {
                    newPlayer.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
                } catch (Throwable ignored) {}
                
                try {
                    newPlayer.setHealth(newPlayer.getMaxHealth());
                    newPlayer.getHungerManager().setFoodLevel(20);
                    newPlayer.getHungerManager().setSaturationLevel(20);
                    newPlayer.extinguish();
                } catch (Throwable ignored) {}
                
                LOGGER.info("[Race] Player respawned in main personal world, restored health: {}", newPlayer.getName().getString());
            } else if ("fabric_race".equals(key) && (worldKey.contains("nether") || worldKey.contains("end"))) {
                // End/Nether - возвращаем в основной мир
                try {
                    long playerSeed = race.hub.HubManager.getPlayerSeedChoice(newPlayer.getUuid());
                    if (playerSeed > 0) {
                        String originalWorldKey = getPlayerOriginalWorld(newPlayer.getUuid());
                        int originalSlot = extractSlotFromWorldKey(originalWorldKey);
                        
                        net.minecraft.server.world.ServerWorld mainPersonalWorld = null;
                        if (originalSlot > 0) {
                            mainPersonalWorld = race.server.world.EnhancedWorldManager
                                .getOrCreateWorldForGroup(newPlayer.getServer(), originalSlot, playerSeed, net.minecraft.world.World.OVERWORLD);
                        } else {
                            mainPersonalWorld = race.server.world.EnhancedWorldManager.getOrCreateWorld(
                                newPlayer.getServer(), newPlayer.getUuid(), playerSeed, net.minecraft.world.World.OVERWORLD);
                        }
                        
                        if (mainPersonalWorld != null) {
                            race.server.world.EnhancedWorldManager.teleportToWorld(newPlayer, mainPersonalWorld);
                            newPlayer.sendMessage(net.minecraft.text.Text.literal("Вы вернулись в основной мир после смерти в " + 
                                (worldKey.contains("end") ? "End" : "Nether")).formatted(net.minecraft.util.Formatting.GREEN), false);
                            LOGGER.info("[Race] Player returned to main personal world from {} to slot: {}", 
                                      newPlayer.getName().getString(), worldKey, originalSlot);
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.warn("[Race] Error returning player to main world: {}", t.getMessage());
                }
            } else {
                // Обычный respawn - возвращаем в личный мир
                try {
                    long playerSeed = race.hub.HubManager.getPlayerSeedChoice(newPlayer.getUuid());
                    if (playerSeed > 0) {
                        LOGGER.info("[Race] Player respawned in standard world, returning to personal world with seed: {} -> {}", 
                                  newPlayer.getName().getString(), playerSeed);
                        
                        String originalWorldKey = getPlayerOriginalWorld(newPlayer.getUuid());
                        int originalSlot = extractSlotFromWorldKey(originalWorldKey);
                        
                        net.minecraft.server.world.ServerWorld personalWorld = null;
                        if (originalSlot > 0) {
                            personalWorld = race.server.world.EnhancedWorldManager
                                .getOrCreateWorldForGroup(newPlayer.getServer(), originalSlot, playerSeed, net.minecraft.world.World.OVERWORLD);
                            LOGGER.info("[Race] Player returned to original slot world: {} -> {} -> {}", 
                                      newPlayer.getName().getString(), originalSlot, personalWorld.getRegistryKey().getValue());
                        } else {
                            LOGGER.warn("[Race] No original slot found for player: {}, NOT searching by seed alone to avoid slot mismatch", 
                                      newPlayer.getName().getString());
                            LOGGER.warn("[Race] Will create new world with correct slot assignment for player: {}", 
                                      newPlayer.getName().getString());
                        }
                        
                        if (personalWorld != null) {
                            race.server.world.EnhancedWorldManager.teleportToWorld(newPlayer, personalWorld);
                            newPlayer.sendMessage(net.minecraft.text.Text.literal("Добро пожаловать обратно в ваш личный мир!")
                                .formatted(net.minecraft.util.Formatting.GREEN), false);
                            LOGGER.info("[Race] Teleported player to personal world: {} -> {}", 
                                      newPlayer.getName().getString(), personalWorld.getRegistryKey().getValue());
                        } else {
                            LOGGER.warn("[Race] No existing personal world found for player with seed: {} -> {}", 
                                      newPlayer.getName().getString(), playerSeed);
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.warn("[Race] Error during respawn to personal world: {}", t.getMessage());
                }
            }
        });
        
        // Остановка сервера
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            EnhancedWorldManager.beginShutdownAndFlush(server);
            try {
                race.hub.HubManager.forceSaveHubData();
                LOGGER.info("[Race] Hub data saved before server shutdown");
            } catch (Exception e) {
                LOGGER.warn("[Race] Failed to save hub data on shutdown: {}", e.getMessage());
            }
        });
        
        // Отключение игрока  
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            try {
                if (handler.player != null) {
                    boolean isAlive = handler.player.isAlive() && !handler.player.isDead();
                    LOGGER.info("[Race] Player disconnected, alive: {} -> world: {}", 
                              handler.player.getName().getString(), isAlive, handler.player.getServerWorld().getRegistryKey().getValue());
                    
                    ServerWorld playerWorld = handler.player.getServerWorld();
                    if (playerWorld != null && playerWorld.getRegistryKey().getValue().getNamespace().equals("fabric_race")) {
                        try {
                            race.server.world.ReturnPointRegistry.saveCurrent(handler.player);
                            LOGGER.info("[Race] Saved player position before disconnect at: {} {} {}", 
                                      handler.player.getName().getString(), handler.player.getX(), handler.player.getY(), handler.player.getZ());
                        } catch (Throwable t) {
                            LOGGER.warn("[Race] Failed to save player position: {}", t.getMessage());
                        }
                        
                        EnhancedWorldManager.releasePlayerSlot(server, handler.player.getUuid());
                        LOGGER.info("[Race] Player disconnected, keeping slot for personal world: {}", handler.player.getName().getString());
                    } else if (playerWorld != null && playerWorld.getRegistryKey() == net.minecraft.world.World.OVERWORLD) {
                        // Overworld
                        try {
                            race.server.world.ReturnPointRegistry.saveHubPoint(handler.player);
                            LOGGER.info("[Race] Player was in hub, marked for hub return: {}", handler.player.getName().getString());
                        } catch (Throwable t) {
                            LOGGER.warn("[Race] Failed to mark player for hub return: {}", t.getMessage());
                        }
                    }
                    
                    try {
                        playerWorld.save(null, true, true);
                        LOGGER.info("[Race] Saved world for player before disconnect: {} -> {}", 
                                  playerWorld.getRegistryKey().getValue(), handler.player.getName().getString());
                    } catch (Throwable t) {
                        LOGGER.warn("[Race] Failed to save world for player: {} -> {}", 
                                  playerWorld.getRegistryKey().getValue(), handler.player.getName().getString(), t.getMessage());
                    }
                    
                    // ИСПРАВЛЕНИЕ: Очищаем сид игрока при отключении
                    try {
                        long playerSeed = race.hub.HubManager.getPlayerSeedChoice(handler.player.getUuid());
                        if (playerSeed >= 0) {
                            race.hub.HubManager.clearPlayerSeedChoice(handler.player.getUuid());
                            LOGGER.info("[Race] Cleared seed choice for disconnected player: {}", handler.player.getName().getString());
                        }
                    } catch (Throwable t) {
                        LOGGER.warn("[Race] Failed to clear seed choice for player: {}", t.getMessage());
                    }
                }
            } catch (Throwable ignored) {}
        });
        
        LOGGER.info("[Race] RaceServerInit completed successfully");
    }
    
    // Вспомогательные методы
    
    private static boolean isPersonal(ServerWorld world) {
        return world.getRegistryKey().getValue().getNamespace().equals("fabric_race");
    }
    
    private static void updateTPS(long tickTimeMs) {
        long currentTime = System.currentTimeMillis();
        tickTimes.offer(currentTime);
        
        // Держим только последние 20 секунд
        while (!tickTimes.isEmpty() && (currentTime - tickTimes.peek()) > 20000) {
            tickTimes.poll();
        }
        
        // Вычисляем TPS за последние 20 секунд
        if (tickTimes.size() > 1) {
            long timeSpan = currentTime - tickTimes.peek();
            if (timeSpan > 0) {
                currentTPS = Math.min(20.0, (tickTimes.size() - 1) * 1000.0 / timeSpan);
            }
        }
        
        // Устанавливаем уровень производительности
        if (currentTPS < 15.0) {
            performanceLevel.set(2);
        } else if (currentTPS < 18.0) {
            performanceLevel.set(1);
        } else {
            performanceLevel.set(0);
        }
    }
    
    public static void personalStart(ServerPlayerEntity p, long seed) {
        java.util.UUID id = p.getUuid();
        
        if (seed <= 0) {
            p.sendMessage(net.minecraft.text.Text.literal("Недопустимый race seed! Настройте race setup перед стартом.")
                .formatted(net.minecraft.util.Formatting.RED), false);
            LOGGER.warn("[Race] Player tried to start with invalid seed: {} -> {}", p.getName().getString(), seed);
            return;
        }
        
        if (personalStarted.contains(id)) {
            LOGGER.info("[Race] Player already started race - skipping personal start: {}", p.getName().getString());
            frozenUntilStart.remove(id);
            freezePos.remove(id);
            try {
                p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
            } catch (Throwable ignored) {}
            p.sendMessage(net.minecraft.text.Text.literal("Гонка уже началась!")
                .formatted(net.minecraft.util.Formatting.GREEN), false);
            return;
        }
        
        personalStarted.add(id);
        frozenUntilStart.remove(id);
        freezePos.remove(id);
        
        try {
            p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
        } catch (Throwable ignored) {}
        
        try {
            ServerPlayNetworking.send(p, new StartRacePayload(seed, System.currentTimeMillis()));
        } catch (Throwable ignored) {}
        
        LOGGER.info("[Race] UNFREEZE: Player started personal race with seed: {} -> {}", p.getName().getString(), seed);
    }
    
    public static void startRace(long s, long t0) {
        active = true;
        seed = s;
        t0ms = t0;
        
        try {
            race.server.phase.PhaseState.setRaceActive(true);
            race.server.phase.PhaseState.setRaceSeed(s);
            race.server.phase.PhaseState.setRaceStartTime(t0);
        } catch (Throwable ignored) {}
    }
    
    public static void savePlayerOriginalWorld(java.util.UUID playerId, String worldKey) {
        playerOriginalWorlds.put(playerId, worldKey);
        LOGGER.debug("[Race] Saved original world for {}: {}", playerId, worldKey);
    }
    
    public static String getPlayerOriginalWorld(java.util.UUID playerId) {
        return playerOriginalWorlds.get(playerId);
    }
    
    public static int extractSlotFromWorldKey(String worldKey) {
        if (worldKey == null) return -1;
        
        try {
            if (worldKey.contains("slot") && worldKey.contains("overworld")) {
                String slotPart = worldKey.substring(worldKey.indexOf("slot") + 4);
                String slotNumber = slotPart.substring(0, slotPart.indexOf("_"));
                return Integer.parseInt(slotNumber);
            }
        } catch (Exception e) {
            LOGGER.warn("[Race] Failed to extract slot from world key: {} -> {}", worldKey, e.getMessage());
        }
        
        return -1;
    }
    
    // Freeze/Unfreeze методы
    public static void freezePlayerUntilStart(ServerPlayerEntity player) {
        frozenUntilStart.add(player.getUuid());
        freezePos.put(player.getUuid(), player.getBlockPos());
        LOGGER.info("[Race] Player frozen until start: {}", player.getName().getString());
    }

    public static boolean isFrozen(UUID playerId) {
        return frozenUntilStart.contains(playerId);
    }

    public static BlockPos getFreezePos(UUID playerId) {
        return freezePos.get(playerId);
    }

    public static void forceUnfreezePlayer(ServerPlayerEntity player) {
        frozenUntilStart.remove(player.getUuid());
        freezePos.remove(player.getUuid());
        try {
            player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
        } catch (Throwable ignored) {}
        LOGGER.info("[Race] Player unfrozen: {}", player.getName().getString());
    }

    public static void forceUnfreezeAllPlayers(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            forceUnfreezePlayer(player);
        }
        LOGGER.info("[Race] All players unfrozen");
    }

    // Race state методы
    public static boolean isRaceActive() {
        return active;
    }

    public static long getT0Ms() { // исправляем название метода
        return t0ms;
    }

    // TPS методы
    public static void setCurrentTPS(double tps) {
        currentTPS = tps;
    }

    // Join requests
    public static java.util.Map<java.util.UUID, PendingJoin> getPendingJoins() {
        return pendingJoins;
    }

    // Parallel players timing
    private static long lastParallelSync = 0;
    private static final long PARALLEL_SYNC_INTERVAL = 2000; // 2 секунды

    public static boolean shouldSyncParallelPlayers() {
        long now = System.currentTimeMillis();
        if (now - lastParallelSync >= PARALLEL_SYNC_INTERVAL) {
            lastParallelSync = now;
            return true;
        }
        return false;
    }
    
    // Методы для отправки списка лобби
    private static void sendLobbyListTo(ServerPlayerEntity dst) {
        try {
            java.util.List<race.net.SeedLobbyEntry> entries = new java.util.ArrayList<>();
            for (ServerPlayerEntity p : dst.getServer().getPlayerManager().getPlayerList()) {
                String name = p.getGameProfile().getName();
                long s = race.hub.HubManager.getPlayerSeedChoice(p.getUuid());
                String worldKey = p.getServerWorld().getRegistryKey().getValue().toString();
                entries.add(new race.net.SeedLobbyEntry(name, s, worldKey));
            }
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(dst, new race.net.SeedLobbyListPayload(entries));
        } catch (Throwable t) {
            LOGGER.warn("[Race] Failed to send lobby list: {}", t.getMessage());
        }
    }
    
    public static void invokeSendLobbyList(ServerPlayerEntity p) {
        sendLobbyListTo(p);
    }
}
