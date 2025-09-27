package race.server.world;

import com.mojang.logging.LogUtils;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.dimension.DimensionTypes;
import net.minecraft.world.GameRules;
import net.minecraft.world.GameMode;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.MultiNoiseBiomeSource;
import net.minecraft.world.biome.source.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.biome.source.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.biome.source.TheEndBiomeSource;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.Heightmap;
import net.minecraft.util.Util;
import net.minecraft.world.level.ServerWorldProperties;
import net.minecraft.world.level.storage.LevelStorage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

    /**
     * Улучшенный менеджер миров с поддержкой всех измерений
     */
    public final class EnhancedWorldManager {
        
        // Утилитный метод для гарантии загрузки чанка перед чтением блоков
        private static void ensureFullChunk(ServerWorld world, BlockPos pos) {
            try {
                int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
                world.getChunk(cx, cz, net.minecraft.world.chunk.ChunkStatus.FULL, true);
            } catch (Throwable t) {
                LOGGER.warn("[Race] Failed to ensure chunk loading at {}: {}", pos, t.getMessage());
            }
        }
        
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ConcurrentHashMap<String, RegistryKey<World>> WORLD_KEYS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<java.util.UUID, Integer> PLAYER_SLOT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<java.util.UUID, Long> PLAYER_SEEDS = new ConcurrentHashMap<>();
    private static final int MAX_SLOTS = 8;
    private static final java.util.concurrent.ConcurrentHashMap<RegistryKey<World>, java.util.concurrent.ExecutorService> WORLD_EXECUTORS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<RegistryKey<World>, Boolean> WORLD_CREATING = new java.util.concurrent.ConcurrentHashMap<>();
    // Единая очередь последовательной выгрузки миров (не блокирует главный поток)
    private static final java.util.concurrent.ScheduledExecutorService UNLOAD_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Race-UnloadQueue");
                t.setDaemon(true);
                return t;
            });
    private static volatile boolean SHUTTING_DOWN = false;
    // Чтобы не планировать выгрузку одного и того же мира дважды
    private static final java.util.concurrent.ConcurrentHashMap<RegistryKey<World>, Boolean> PENDING_UNLOAD = new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * Создает или получает мир для игрока с указанным сидом
     */
    public static ServerWorld getOrCreateWorld(MinecraftServer server, UUID playerUuid, long seed, RegistryKey<World> worldKey) {
        // Инициализируем сервер для доступа к игрокам
        setCurrentServer(server);
        
        // БЛОКИРОВКА: проверяем, что игрок не в промежуточном ванильном мире
        try {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUuid);
            if (player != null) {
                String currentWorldName = player.getServerWorld().getRegistryKey().getValue().toString();
                if (isVanillaWorld(currentWorldName)) {
                    LOGGER.warn("[Race] PORTAL-TRANSITION: Player {} currently in vanilla world {}, preserving existing slot", playerUuid, currentWorldName);
                    
                    // Принудительно сохраняем существующий слот
                    Integer existingSlot = PLAYER_SLOT.get(playerUuid);
                    int worldManagerSlot = race.server.WorldManager.getPlayerSlot(playerUuid);
                    
                    if (existingSlot != null) {
                        LOGGER.info("[Race] PORTAL-PRESERVE: Using existing slot {} from Enhanced cache", existingSlot);
                    } else if (worldManagerSlot > 0) {
                        PLAYER_SLOT.put(playerUuid, worldManagerSlot);
                        existingSlot = worldManagerSlot;
                        LOGGER.info("[Race] PORTAL-PRESERVE: Using existing slot {} from WorldManager", worldManagerSlot);
                    } else {
                        LOGGER.error("[Race] PORTAL-EMERGENCY: No existing slot found for player {} in vanilla world {} — using fallback slot assignment", playerUuid, currentWorldName);
                        // Не принудительно назначаем slot1, а используем обычную логику назначения слота
                        // Это позволит getOrAssignSlot вернуть -1 и инициирующая логика решит, что делать
                    }
                }
            }
        } catch (Throwable ignored) {}
        
        // Кешируем seed игрока
        setPlayerSeed(playerUuid, seed);
        
        int slot = getOrAssignSlot(playerUuid);
        Identifier slotId = getSlotId(worldKey, slot, seed);
        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, slotId);
        String worldId = slotId.getPath();
        LOGGER.info("[Race] getOrCreateWorld: player={}, dim={}, seed={}, id={}", playerUuid, worldKey.getValue(), seed, worldId);
        
        // ИСПРАВЛЕНИЕ: Кешируем слот игрока в WorldManager
        if (slot > 0) {
                    race.server.WorldManager.setPlayerSlot(playerUuid, slot);
        }
        
        // Отладочная информация
        debugSlotState(playerUuid, "getOrCreateWorld");
        WORLD_KEYS.putIfAbsent(worldId, key);

        // Очистим старые миры этого слота c другим сидом
        cleanupOldSlotWorlds(server, slot, worldKey, worldId);
        
        // Проверяем, существует ли уже мир
        ServerWorld existing = server.getWorld(key);
        if (existing != null) {
            // Проверяем, что мир не помечен для выгрузки
            if (PENDING_UNLOAD.containsKey(key)) {
                LOGGER.info("[Race] World {} is pending unload, removing from pending list", key.getValue());
                PENDING_UNLOAD.remove(key);
            }
            
            // Инициализируем время для существующего мира, если еще не инициализировано
            try {
                race.server.SlotTimeService.initIfAbsent(existing, 1000L);
            } catch (Throwable t) {
                LOGGER.warn("[Race] Failed to initialize time for existing world {}: {}", key.getValue(), t.getMessage());
            }
            
            LOGGER.info("[Race] Found existing world {} for player {}", key.getValue(), playerUuid);
            return existing;
        }
        
        // Попробуем загрузить существующий мир из файловой системы
        // Пока что пропускаем проверку файловой системы, так как основная функциональность работает
        // TODO: В будущем можно добавить проверку файловой системы для загрузки существующих миров
        
        LOGGER.info("[Race] World {} not found, creating new one for player {}", key.getValue(), playerUuid);
        
        try {
            // Для соло гонок создаем мир с уникальным сидом
            ServerWorld created = createNewWorldWithSeed(server, key, seed, worldKey);
            LOGGER.info("[Race] Created world {} for player {} with seed {}", key.getValue(), playerUuid, seed);
            return created;
        } catch (Throwable t) {
            LOGGER.error("[Race] Failed to create world {} for player {} (seed {})", key.getValue(), playerUuid, seed, t);
            throw t;
        }
    }

    static synchronized int getOrAssignSlot(UUID playerUuid) {
        debugSlotState(playerUuid, "getOrAssignSlot-start");
        
        // ПРИОРИТЕТ 1: Локальный кеш (САМЫЙ НАДЕЖНЫЙ)
        Integer cachedSlot = PLAYER_SLOT.get(playerUuid);
        if (cachedSlot != null && cachedSlot > 0) {
            syncPlayerSlotWithWorldManager(playerUuid, cachedSlot);
            LOGGER.info("[Race] [CACHE-HIT] Using Enhanced cached slot {} for player {}", cachedSlot, playerUuid);
            return cachedSlot;
        }
        
        // ПРИОРИТЕТ 2: WorldManager кеш
        int worldManagerSlot = race.server.WorldManager.getPlayerSlot(playerUuid);
        if (worldManagerSlot > 0) {
            PLAYER_SLOT.put(playerUuid, worldManagerSlot);
            LOGGER.info("[Race] [WM-HIT] Using WorldManager slot {} for player {}", worldManagerSlot, playerUuid);
            return worldManagerSlot;
        }
        
        // ПРИОРИТЕТ 3: Текущий мир (НО НЕ ДЛЯ ВАНИЛЬНЫХ!)
        int currentSlot = getCurrentPlayerSlot(playerUuid);
        if (currentSlot > 0) {
            PLAYER_SLOT.put(playerUuid, currentSlot);
            syncPlayerSlotWithWorldManager(playerUuid, currentSlot);
            LOGGER.info("[Race] [WORLD-HIT] Using current world slot {} for player {}", currentSlot, playerUuid);
            return currentSlot;
        }
        
        // КРИТИЧЕСКОЕ: НЕ НАЗНАЧАЕМ НОВЫЙ СЛОТ ДЛЯ ВАНИЛЬНЫХ МИРОВ!
        try {
            MinecraftServer server = getCurrentServer();
            if (server != null) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUuid);
                if (player != null) {
                    String worldName = player.getServerWorld().getRegistryKey().getValue().toString();
                    if (isVanillaWorld(worldName)) {
                        LOGGER.warn("[Race] Player {} in vanilla world {} but no cached slot found! Assigning new slot", playerUuid, worldName);
                        // НЕ возвращаем -1, а назначаем новый слот
                    }
                }
            }
        } catch (Throwable ignored) {}
        
        // ТОЛЬКО ДЛЯ НОВЫХ ИГРОКОВ: назначаем новый слот
        java.util.HashSet<Integer> usedSlots = new java.util.HashSet<>(PLAYER_SLOT.values());
        for (int i = 1; i <= MAX_SLOTS; i++) {
            if (!usedSlots.contains(i)) {
                PLAYER_SLOT.put(playerUuid, i);
                syncPlayerSlotWithWorldManager(playerUuid, i);
                LOGGER.info("[Race] [NEW] Assigned slot {} to player {} (used: {})", i, playerUuid, usedSlots);
                return i;
            }
        }
        
        // Fallback
        PLAYER_SLOT.put(playerUuid, 1);
        syncPlayerSlotWithWorldManager(playerUuid, 1);
        LOGGER.warn("[Race] [FALLBACK] Using slot1 for player {}", playerUuid);
        return 1;
    }

    // Публичный доступ к слоту игрока (создаёт при отсутствии)
    public static int getOrAssignSlotForPlayer(UUID playerUuid) { return getOrAssignSlot(playerUuid); }
    
    // Перегрузка с сервером и сидом для CustomWorldManager
    public static int getOrAssignSlot(MinecraftServer server, UUID playerUuid, long seed) {
        // Сначала пробуем получить существующий слот
        int existingSlot = getOrAssignSlot(playerUuid);
        if (existingSlot > 0) {
            // Сохраняем сид для игрока
            setPlayerSeed(playerUuid, seed);
            return existingSlot;
        }
        
        // Если слот не найден, назначаем новый с учетом сида
        setPlayerSeed(playerUuid, seed);
        return getOrAssignSlot(playerUuid);
    }
    
    // Поиск первого свободного слота для сида

    
    // Методы для работы с seed игрока
    public static long getSeedForPlayer(UUID playerUuid) {
        return PLAYER_SEEDS.getOrDefault(playerUuid, System.currentTimeMillis());
    }
    
    public static void setPlayerSeed(UUID playerUuid, long seed) {
        PLAYER_SEEDS.put(playerUuid, seed);
        LOGGER.info("[Race] Cached seed {} for player {}", seed, playerUuid);
    }
    
    // Синхронизация слота с WorldManager
    private static void syncPlayerSlotWithWorldManager(UUID playerUuid, int slot) {
        try {
            race.server.WorldManager.setPlayerSlot(playerUuid, slot);
            LOGGER.info("[Race] Synced slot {} for player {} with WorldManager", slot, playerUuid);
        } catch (Throwable t) {
            LOGGER.warn("[Race] Failed to sync slot for player {}: {}", playerUuid, t.getMessage());
        }
    }
    
    // Получение игрока по UUID
    private static ServerPlayerEntity getPlayerByUuid(UUID playerUuid) {
        try {
            // Нужно получить сервер из контекста
            return null; // Будет реализовано в вызывающем коде
        } catch (Throwable ignored) {
            return null;
        }
    }
    
    // Получение текущего сервера (нужно для доступа к игрокам)
    private static volatile MinecraftServer currentServer = null;
    
    public static void setCurrentServer(MinecraftServer server) {
        currentServer = server;
    }
    
    private static MinecraftServer getCurrentServer() {
        return currentServer;
    }
    
    // Извлечение слота из текущего мира игрока
    private static int getCurrentPlayerSlot(UUID playerUuid) {
        try {
            MinecraftServer server = getCurrentServer();
            if (server != null) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUuid);
                if (player != null) {
                    String worldName = player.getServerWorld().getRegistryKey().getValue().toString();
                    LOGGER.info("[Race] Checking current world for player {}: {}", playerUuid, worldName);
                    
                    // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: правильный regex для всех форматов
                    if (worldName.startsWith("fabric_race:slot")) {
                        // Ищем: fabric_race:slot2_overworld_s123, fabric_race:slot2_nether_s123, fabric_race:slot2_end_s123
                        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("fabric_race:slot(\\d+)_");
                        java.util.regex.Matcher matcher = pattern.matcher(worldName);
                        if (matcher.find()) {
                            int slot = Integer.parseInt(matcher.group(1));
                            LOGGER.info("[Race] Found slot {} from world: {}", slot, worldName);
                            return slot;
                        }
                    }
                    
                    // ДОПОЛНИТЕЛЬНО: проверяем ванильные миры - НЕ ДОЛЖНЫ сбрасывать слот!
                    if (isVanillaWorld(worldName)) {
                        LOGGER.warn("[Race] Player {} in vanilla world {}, checking caches instead of assigning new slot", playerUuid, worldName);
                        
                        // НЕ возвращаем -1, а проверяем кеши!
                        Integer cachedSlot = PLAYER_SLOT.get(playerUuid);
                        if (cachedSlot != null) {
                            LOGGER.info("[Race] Using cached slot {} for player in vanilla world", cachedSlot);
                            return cachedSlot;
                        }
                        
                        int worldManagerSlot = race.server.WorldManager.getPlayerSlot(playerUuid);
                        if (worldManagerSlot > 0) {
                            LOGGER.info("[Race] Using WorldManager slot {} for player in vanilla world", worldManagerSlot);
                            return worldManagerSlot;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[Race] Error getting current player slot: {}", t.getMessage());
        }
        return -1;
    }
    
    // Проверка ванильных миров
    private static boolean isVanillaWorld(String worldName) {
        return "minecraft:overworld".equals(worldName) || 
               "minecraft:the_nether".equals(worldName) || 
               "minecraft:the_end".equals(worldName);
    }
    
    // Отладочная информация о слотах
    public static void debugSlotState(UUID playerUuid, String context) {
        Integer enhancedSlot = PLAYER_SLOT.get(playerUuid);
        int worldManagerSlot = race.server.WorldManager.getPlayerSlot(playerUuid);
        int currentWorldSlot = getCurrentPlayerSlot(playerUuid);
        
        // ДОПОЛНИТЕЛЬНАЯ ДИАГНОСТИКА: Проверяем все UUID в кешах
        LOGGER.info("[Race] SLOT DEBUG [{}]: player={}, Enhanced={}, WorldManager={}, CurrentWorld={}", 
                   context, playerUuid, enhancedSlot, worldManagerSlot, currentWorldSlot);
        
        // НОВОЕ: Диагностика UUID конфликтов
        try {
            MinecraftServer server = getCurrentServer();
            if (server != null) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUuid);
                if (player != null) {
                    String playerName = player.getName().getString();
                    String currentWorldName = player.getServerWorld().getRegistryKey().getValue().toString();
                    LOGGER.info("[Race] UUID DIAGNOSTIC: name={}, world={}", playerName, currentWorldName);
                    
                    // Проверяем, есть ли другие UUID для этого игрока в кешах
                    for (java.util.Map.Entry<UUID, Integer> entry : PLAYER_SLOT.entrySet()) {
                        if (!entry.getKey().equals(playerUuid)) {
                            ServerPlayerEntity otherPlayer = server.getPlayerManager().getPlayer(entry.getKey());
                            if (otherPlayer != null && otherPlayer.getName().getString().equals(playerName)) {
                                LOGGER.warn("[Race] UUID CONFLICT DETECTED: Player {} has multiple UUIDs: {} and {}", 
                                           playerName, playerUuid, entry.getKey());
                                LOGGER.warn("[Race] Cleaning up old UUID {} with slot {}", entry.getKey(), entry.getValue());
                                PLAYER_SLOT.remove(entry.getKey());
                                race.server.WorldManager.clearPlayerSlot(entry.getKey());
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[Race] Error in UUID diagnostic: {}", t.getMessage());
        }
        
        // Автокоррекция если есть текущий мир со слотом
        if (currentWorldSlot > 0 && (enhancedSlot == null || !enhancedSlot.equals(currentWorldSlot))) {
            LOGGER.warn("[Race] AUTO-CORRECTING slot mismatch: using current world slot {}", currentWorldSlot);
            PLAYER_SLOT.put(playerUuid, currentWorldSlot);
            race.server.WorldManager.setPlayerSlot(playerUuid, currentWorldSlot);
        }
        
        if (enhancedSlot != null && worldManagerSlot > 0 && !enhancedSlot.equals(worldManagerSlot)) {
            LOGGER.warn("[Race] SLOT MISMATCH detected in context: {}", context);
            // АВТОИСПРАВЛЕНИЕ: Принудительно синхронизируем
            race.server.WorldManager.setPlayerSlot(playerUuid, enhancedSlot);
            LOGGER.info("[Race] Auto-fixed slot mismatch: forced WorldManager to use slot{}", enhancedSlot);
        }
    }

    // Извлекаем номер слота из ключа мира вида fabric_race:slotX_overworld_s<seed>
    public static int slotFromWorld(ServerWorld world) {
        try {
            String path = world.getRegistryKey().getValue().getPath();
            int i = path.indexOf("slot");
            if (i < 0) return -1;
            int u = path.indexOf('_', i);
            String num = path.substring(i + 4, u);
            return Integer.parseInt(num);
        } catch (Throwable ignored) { return -1; }
    }

    private static Identifier getSlotId(RegistryKey<World> worldKey, int slot, long seed) {
        String suffix = switch (worldKey.getValue().getPath()) {
            case "the_nether" -> "nether";
            case "the_end" -> "end";
            default -> "overworld";
        };
        return Identifier.of("fabric_race", "slot" + slot + "_" + suffix + "_s" + Long.toUnsignedString(seed));
    }

    // Получить/создать мир по фиксированному слоту группы (чтобы вся команда шла в один инстанс)
    public static ServerWorld getOrCreateWorldForGroup(MinecraftServer server, int slot, long seed, RegistryKey<World> worldKey) {
        // Инициализируем сервер для доступа к игрокам
        setCurrentServer(server);
        
        // Убрано массовое переприсваивание слотов - оставляем только инициатора
        
        Identifier slotId = getSlotId(worldKey, slot, seed);
        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, slotId);
        String worldId = slotId.getPath();
        WORLD_KEYS.putIfAbsent(worldId, key);
        
        // Отладочная информация для всех игроков
        try {
            var playerManager = server.getPlayerManager();
            if (playerManager != null) {
                var playerList = playerManager.getPlayerList();
                if (playerList != null) {
                    for (ServerPlayerEntity player : playerList) {
                        if (player != null) {
                            debugSlotState(player.getUuid(), "getOrCreateWorldForGroup");
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[Race] Failed to debug slot state: {}", e.getMessage());
        }
        
        ServerWorld existing = server.getWorld(key);
        if (existing != null) {
            // Инициализируем время для существующего мира группы
            try {
                race.server.SlotTimeService.initIfAbsent(existing, 1000L);
            } catch (Throwable t) {
                LOGGER.warn("[Race] Failed to initialize time for existing group world {}: {}", key.getValue(), t.getMessage());
            }
            return existing;
        }
        return createNewWorld(server, key, seed, worldKey);
    }

    private static void cleanupOldSlotWorlds(MinecraftServer server, int slot, RegistryKey<World> baseKey, String currentId) {
        String suffix = switch (baseKey.getValue().getPath()) {
            case "the_nether" -> "nether";
            case "the_end" -> "end";
            default -> "overworld";
        };
        String prefix = "slot" + slot + "_" + suffix + "_s";
        java.util.ArrayList<String> toRemove = new java.util.ArrayList<>();
        for (var e : WORLD_KEYS.entrySet()) {
            String id = e.getKey();
            if (id.startsWith(prefix) && !id.equals(currentId)) {
                // Проверяем, что мир действительно существует и не используется
                RegistryKey<World> key = e.getValue();
                ServerWorld world = server.getWorld(key);
                if (world != null) {
                    // Проверяем, есть ли игроки в этом мире
                    boolean hasPlayers = false;
                    try {
                        var playerManager = server.getPlayerManager();
                        if (playerManager != null) {
                            var playerList = playerManager.getPlayerList();
                            if (playerList != null) {
                                for (var p : playerList) {
                                    if (p != null && p.getServerWorld() == world) {
                                        hasPlayers = true;
                                        break;
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                    
                    // Удаляем только если нет игроков И мир старше 5 минут
                    if (!hasPlayers) {
                        // Проверяем возраст мира - удаляем только очень старые миры
                        long worldAge = System.currentTimeMillis() - (world.getTime() * 50); // примерный возраст
                        if (worldAge > 300000) { // 5 минут
                            toRemove.add(id);
                            LOGGER.info("[Race] Removing old world {} (age: {}ms)", key.getValue(), worldAge);
                        } else {
                            LOGGER.info("[Race] Keeping world {} - too recent (age: {}ms)", key.getValue(), worldAge);
                        }
                    } else {
                        LOGGER.info("[Race] Keeping world {} - has players", key.getValue());
                    }
                } else {
                    // Мир уже выгружен, можно удалить из реестра
                    toRemove.add(id);
                }
            }
        }
        for (String id : toRemove) {
            RegistryKey<World> key = WORLD_KEYS.remove(id);
            if (key != null) {
                LOGGER.info("[Race] Removing old slot world {}", key.getValue());
                // Мягкая отложенная выгрузка, чтобы не конкурировать с активной генерацией
                enqueueUnload(server, key, 5000L, true);
            }
        }
    }
    
    /**
     * Телепортирует игрока в указанный мир
     */
    public static void teleportToWorld(ServerPlayerEntity player, ServerWorld targetWorld) {
        LOGGER.info("[Race] teleportToWorld called for {} to {}", player.getName().getString(), targetWorld.getRegistryKey().getValue());
        if (player.getServerWorld() == targetWorld) return;
        
        // Выбираем безопасный спавн
        LOGGER.info("[Race] Target world dimension: {}", targetWorld.getDimensionEntry().value());
        LOGGER.info("[Race] Target world key: {}", targetWorld.getRegistryKey().getValue());
        LOGGER.info("[Race] Is Nether? {}", targetWorld.getDimensionEntry().matchesKey(DimensionTypes.THE_NETHER));
        LOGGER.info("[Race] Is personal Nether? {}", targetWorld.getRegistryKey().getValue().getPath().contains("nether"));
        
        BlockPos spawnPos;
        // ИСПРАВЛЕНИЕ: Сначала проверяем персональный Nether, потом обычные миры
        if (targetWorld.getRegistryKey().getValue().getPath().contains("nether")) {
            LOGGER.info("[Race] Using personal Nether portal logic");
            // ИСПРАВЛЕНИЕ: Используем стандартную механику порталов
            spawnPos = findNetherPortalLocation(player, targetWorld);
            LOGGER.info("[Race] Using portal location for Nether: {}", spawnPos);
        } else if (targetWorld.getDimensionEntry().matchesKey(DimensionTypes.OVERWORLD)) {
            LOGGER.info("[Race] Using Overworld spawn logic");
            spawnPos = chooseOverworldSpawn(targetWorld);
        } else if (targetWorld.getDimensionEntry().matchesKey(DimensionTypes.THE_NETHER)) {
            LOGGER.info("[Race] Using standard Nether portal logic");
            spawnPos = findNetherPortalLocation(player, targetWorld);
            LOGGER.info("[Race] Using portal location for Nether: {}", spawnPos);
        } else if (targetWorld.getDimensionEntry().matchesKey(net.minecraft.world.dimension.DimensionTypes.THE_END)) {
            LOGGER.info("[Race] Using End spawn logic for End world: {}", 
                        targetWorld.getRegistryKey().getValue());
            
            // ИСПРАВЛЕНИЕ: Очищаем неправильных мобов в End мире
            race.hub.WorldManager.clearInvalidEndMobs(targetWorld);
            
            // Игрок спавнится на острове End (вне главного острова с драконом)
            spawnPos = new BlockPos(100, 50, 0); // Стандартная позиция острова End
            // Создаем платформу для игрока на острове End
            createEndIslandPlatform(targetWorld, spawnPos);
        } else {
            LOGGER.info("[Race] Using default spawn logic");
            spawnPos = new BlockPos(0, 200, 0);
        }
        
        // Нормализуем правила времени/тиков перед телепортом (на случай старых миров)
        try {
            // Для fabric_race миров: отключаем DODAYLIGHTCYCLE, для остальных - включаем
            if (targetWorld.getRegistryKey().getValue().getNamespace().equals("fabric_race")) {
                targetWorld.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, player.getServer());
            } else {
                targetWorld.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(true, player.getServer());
            }
            targetWorld.getGameRules().get(GameRules.RANDOM_TICK_SPEED).set(1, player.getServer());
            // Отключаем защиту спавна, чтобы можно было ломать блоки в личном мире
            try { targetWorld.getGameRules().get(GameRules.SPAWN_RADIUS).set(0, player.getServer()); } catch (Throwable ignored) {}
            // Уменьшаем давление от агрессивных мобов в персональных мирах
            try { targetWorld.getGameRules().get(GameRules.DO_INSOMNIA).set(false, player.getServer()); } catch (Throwable ignored) {}
            try { targetWorld.getGameRules().get(GameRules.DO_PATROL_SPAWNING).set(false, player.getServer()); } catch (Throwable ignored) {}
            try { targetWorld.getGameRules().get(GameRules.DISABLE_RAIDS).set(true, player.getServer()); } catch (Throwable ignored) {}
            // Время для fabric_race миров управляется через initWorldIfAbsent
            // Не устанавливаем setTimeOfDay напрямую для fabric_race миров
        } catch (Throwable ignored) {}

        // Быстрый расчёт высоты без принудительной загрузки чанков
        try {
            LOGGER.info("[Race] Before computeHeightNoLoad check: spawnPos={}", spawnPos);
            LOGGER.info("[Race] Dimension check: matchesKey(THE_NETHER) = {}", targetWorld.getDimensionEntry().matchesKey(DimensionTypes.THE_NETHER));
            LOGGER.info("[Race] Personal Nether check: {}", targetWorld.getRegistryKey().getValue().getPath().contains("nether"));
            
            // Для Нижнего мира (включая персональный) НЕ используем computeHeightNoLoad - он возвращает Y=128 (крыша)
            if (!targetWorld.getDimensionEntry().matchesKey(DimensionTypes.THE_NETHER) && 
                !targetWorld.getRegistryKey().getValue().getPath().contains("nether")) {
                LOGGER.info("[Race] Calling computeHeightNoLoad for non-Nether world");
                spawnPos = computeHeightNoLoad(targetWorld, spawnPos);
                LOGGER.info("[Race] After computeHeightNoLoad: {}", spawnPos);
            } else {
                LOGGER.info("[Race] Skipping computeHeightNoLoad for Nether, using portal logic: {}", spawnPos);
            }
        } catch (Throwable ignored) {}

        // Подгружаем один чанк спавна синхронно и поправляем точку на сухую поверхность
        try {
            ensureSpawnChunkLoaded(targetWorld, spawnPos);
            // Для Nether не используем findLocalSafeSpot, чтобы не изменить высоту
            if (!targetWorld.getDimensionEntry().matchesKey(DimensionTypes.THE_NETHER)) {
                spawnPos = findLocalSafeSpot(targetWorld, spawnPos);
            }
        } catch (Throwable ignored) {}

        // Телепортируем игрока
        try {
            LOGGER.info("[Race] Final teleport position: {}", spawnPos);
            // Сначала сбрасываем скорость и делаем предзапрос позиции
            player.setVelocity(0, 0, 0);
            
            // Безопасная телепортация с проверкой мира
            if (player.getServerWorld() != targetWorld) {
                // Используем более безопасный метод телепортации
                player.teleport(targetWorld, spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, player.getYaw(), player.getPitch());
            } else {
                // Если уже в нужном мире, просто перемещаем
                player.networkHandler.requestTeleport(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, player.getYaw(), player.getPitch());
            }
            // Убедимся, что игрок в режиме выживания (в хабе могли ставить ADVENTURE)
            try {
                player.changeGameMode(GameMode.SURVIVAL);
            } catch (Throwable ignored) {}
            try { player.sendAbilitiesUpdate(); } catch (Throwable ignored) {}
            // Сброс водных флагов/позы, чтобы не "застрять" в состоянии плавания
            try {
                player.setSwimming(false);
                player.updateSwimming();
                player.setSprinting(false);
                player.setPose(net.minecraft.entity.EntityPose.STANDING);
                player.setVelocity(0, 0, 0);
                player.extinguish();
                player.setOnFire(false);
            } catch (Throwable ignored) {}
            // Грейс на полёт отключён по запросу
            // Ставим точку возрождения в персональном мире, чтобы после смерти не кидало в лобби
            try {
                player.setSpawnPoint(targetWorld.getRegistryKey(), spawnPos, player.getYaw(), true, false);
            } catch (Throwable ignored) {}
            // Убедимся, что игрок в режиме выживания (в хабе мы ставили ADVENTURE)
            try {
                player.changeGameMode(GameMode.SURVIVAL);
            } catch (Throwable ignored) {}
            try { player.setAir(player.getMaxAir()); } catch (Throwable ignored) {}
            // Полное исцеление при входе в персональный мир
            try {
                player.setHealth(player.getMaxHealth());
                player.getHungerManager().setFoodLevel(20);
                player.getHungerManager().setSaturationLevel(20);
                player.setFrozenTicks(0);
                player.extinguish();
            } catch (Throwable ignored) {}
            LOGGER.info("[Race] Teleported {} to {} at {}", player.getGameProfile().getName(), targetWorld.getRegistryKey().getValue(), spawnPos);
        } catch (Throwable t) {
            LOGGER.error("[Race] Teleport failed for {} to {}", player.getGameProfile().getName(), targetWorld.getRegistryKey().getValue(), t);
        }
    }

    // Поиск безопасного спавна в Нижнем мире
    private static BlockPos findNetherSafeSpawn(ServerWorld world) {
        // Расширенный список кандидатов для поиска безопасного места
        int[][] candidates = new int[][]{
            {0, 0}, {8, 0}, {-8, 0}, {0, 8}, {0, -8},
            {16, 0}, {-16, 0}, {0, 16}, {0, -16},
            {16, 16}, {-16, 16}, {16, -16}, {-16, -16},
            {24, 0}, {-24, 0}, {0, 24}, {0, -24},
            {32, 32}, {-32, 32}, {32, -32}, {-32, -32}
        };
        
        for (int[] candidate : candidates) {
            int x = candidate[0];
            int z = candidate[1];
            
            // Загружаем чанк
            try {
                var chunkManager = world.getChunkManager();
                if (chunkManager != null) {
                    chunkManager.getChunk(x >> 4, z >> 4, net.minecraft.world.chunk.ChunkStatus.FULL, true);
                }
            } catch (Throwable ignored) {}
            
            // Поиск подходящей высоты снизу вверх
            for (int y = 32; y <= 100; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockPos below = pos.down();
                BlockPos above = pos.up();
                
                try {
                    // Проверяем: твердый блок снизу, воздух на уровне ног и головы
                    if (world.getBlockState(below).isSolidBlock(world, below) &&
                        world.isAir(pos) && 
                        world.isAir(above) &&
                        world.getFluidState(below).isEmpty() &&
                        world.getFluidState(pos).isEmpty() &&
                        world.getFluidState(above).isEmpty()) {
                        
                        ensureSpawnChunkLoaded(world, pos);
                        LOGGER.info("[Race] Found safe Nether spawn at: {}", pos);
                        return pos;
                    }
                } catch (Exception e) {
                    continue;
                }
            }
        }
        
        // Фолбэк: создаем платформу на Y=90 (ниже крыши)
        BlockPos fallback = new BlockPos(0, 90, 0);
        LOGGER.warn("[Race] No safe Nether spawn found, using fallback: {}", fallback);
        return fallback;
    }

    // Поиск места портала в Нижнем мире
    private static BlockPos findNetherPortalLocation(ServerPlayerEntity player, ServerWorld netherWorld) {
        // Используем координаты игрока из Обычного мира для расчета позиции портала
        BlockPos overworldPos = player.getBlockPos();
        LOGGER.info("[Race] Player overworld position: {}", overworldPos);
        
        // Стандартная формула Minecraft: координаты Нижнего мира = координаты Обычного мира / 8
        int netherX = overworldPos.getX() / 8;
        int netherZ = overworldPos.getZ() / 8;
        LOGGER.info("[Race] Calculated nether portal coords: ({}, {})", netherX, netherZ);
        
        // Ищем безопасное место рядом с этими координатами
        BlockPos result = findSafePortalSpot(netherWorld, netherX, netherZ);
        LOGGER.info("[Race] Found portal location: {}", result);
        return result;
    }

    private static BlockPos findSafePortalSpot(ServerWorld world, int centerX, int centerZ) {
        LOGGER.info("[Race] Searching for safe portal spot around ({}, {})", centerX, centerZ);
        
        // Поиск в радиусе 16 блоков от расчетных координат портала
        for (int radius = 0; radius <= 16; radius += 4) {
            for (int dx = -radius; dx <= radius; dx += 4) {
                for (int dz = -radius; dz <= radius; dz += 4) {
                    int x = centerX + dx;
                    int z = centerZ + dz;
                    
                    // Загружаем чанк
                    try {
                        var chunkManager = world.getChunkManager();
                        if (chunkManager != null) {
                            chunkManager.getChunk(x >> 4, z >> 4, net.minecraft.world.chunk.ChunkStatus.FULL, true);
                        }
                    } catch (Throwable ignored) {}
                    
                    // Поиск подходящей высоты (диапазон где обычно генерируются порталы)
                    for (int y = 32; y <= 100; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (isValidPortalSpot(world, pos)) {
                            LOGGER.info("[Race] Found portal spot at {}", pos);
                            // Проверяем, есть ли уже портал в этом месте
                            if (!hasExistingPortal(world, pos)) {
                                LOGGER.info("[Race] No existing portal found, creating new one");
                                createNetherPortal(world, pos);
                            } else {
                                LOGGER.info("[Race] Existing portal found, using it");
                            }
                            return pos;
                        }
                    }
                }
            }
        }
        
        // Фолбэк: создаем платформу и портал на разумной высоте
        BlockPos fallback = new BlockPos(centerX, 64, centerZ);
        LOGGER.warn("[Race] No portal spot found, creating fallback portal at: {}", fallback);
        createNetherPortal(world, fallback);
        return fallback;
    }

    private static boolean isValidPortalSpot(ServerWorld world, BlockPos pos) {
        BlockPos below = pos.down();
        BlockPos above = pos.up();
        
        try {
            // ИСПРАВЛЕНИЕ: Прогреваем чанк перед проверкой блоков
            ensureFullChunk(world, pos);
            ensureFullChunk(world, below);
            ensureFullChunk(world, above);
            
            // Проверяем: твердый блок снизу, воздух на уровне игрока и выше, нет лавы
            boolean basicCheck = world.getBlockState(below).isSolidBlock(world, below) &&
                   world.isAir(pos) && 
                   world.isAir(above) &&
                   world.getFluidState(below).isEmpty() &&  // нет лавы под ногами
                   world.getFluidState(pos).isEmpty() &&   // нет лавы на уровне игрока
                   world.getFluidState(above).isEmpty();   // нет лавы над головой
            
            if (!basicCheck) return false;
            
                // Дополнительная проверка: убеждаемся, что под порталом есть достаточно места для обсидиана
                // Проверяем, что на 2 блока ниже есть место для платформы
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        BlockPos platformPos = pos.add(dx, -2, dz);
                        // ИСПРАВЛЕНИЕ: Прогреваем чанк перед проверкой блоков
                        ensureFullChunk(world, platformPos);
                        // Проверяем, что место под платформой не занято лавой или другими опасными блоками
                        if (world.getFluidState(platformPos).isOf(net.minecraft.fluid.Fluids.LAVA)) {
                        LOGGER.debug("[Race] Portal spot invalid: lava at platform position {}", platformPos);
                        return false;
                    }
                }
            }
            
            return true;
        } catch (Exception e) {
            LOGGER.warn("[Race] Error checking portal spot at {}: {}", pos, e.getMessage());
            return false;
        }
    }
    
    /**
     * Создает портал в Нижнем мире (рамка из обсидиана) как в ванильном Minecraft
     * Структура: 4x5 рамка с 2x3 порталом внутри
     */
    public static void createNetherPortal(ServerWorld world, BlockPos center) {
        LOGGER.info("[Race] Creating Nether portal at {}", center);
        
        // Создаем платформу под порталом
        createPortalPlatform(world, center);
        
        // Выбираем оптимальную ориентацию портала
        net.minecraft.util.math.Direction.Axis portalAxis = pickPortalAxis(world, center);
        
        // Создаем рамку портала (4x5) в соответствии с выбранной осью
        // Структура: 4x5 рамка с 2x3 порталом внутри
        BlockPos bottomLeft = center.down(); // Нижний левый угол рамки
        
        // Определяем направление построения рамки в зависимости от оси
        net.minecraft.util.math.Direction right = (portalAxis == net.minecraft.util.math.Direction.Axis.X) 
            ? net.minecraft.util.math.Direction.EAST 
            : net.minecraft.util.math.Direction.SOUTH;
        
        // Строим рамку 4x5 в выбранном направлении
        createFrame(world, bottomLeft, right);
        
        // Создаем портал внутри рамки (2x3) с правильной осью
        race.server.world.PortalHelper.fillPortalWithAxis(world, center, portalAxis);
        
        // Проверяем, что обсидиан действительно создался под порталом
        verifyPortalStructure(world, center);
        
        LOGGER.info("[Race] Nether portal created at {} (2x3 portal in 4x5 frame) with axis {}", center, portalAxis);
    }
    
    /**
     * Выбирает оптимальную ориентацию портала (X или Z) на основе свободного места
     * Axis.X для рамки шириной по X (east/up), Axis.Z для рамки шириной по Z (south/up)
     */
    private static net.minecraft.util.math.Direction.Axis pickPortalAxis(ServerWorld world, BlockPos center) {
        boolean spaceX = hasSpaceForPortal(world, center, net.minecraft.util.math.Direction.Axis.X);
        boolean spaceZ = hasSpaceForPortal(world, center, net.minecraft.util.math.Direction.Axis.Z);
        
        // Приоритет: выбираем доступную ориентацию
        if (spaceX && !spaceZ) {
            // Рамка шириной по X (east/up) → ось портала X
            return net.minecraft.util.math.Direction.Axis.X;
        }
        if (!spaceX && spaceZ) {
            // Рамка шириной по Z (south/up) → ось портала Z
            return net.minecraft.util.math.Direction.Axis.Z;
        }
        
        // Если оба варианта доступны, предпочитаем X (стандартная ориентация)
        if (spaceX && spaceZ) {
            return net.minecraft.util.math.Direction.Axis.X;
        }
        
        // Если оба недоступны, принудительно используем X (создадим место)
        return net.minecraft.util.math.Direction.Axis.X;
    }
    
    /**
     * Проверяет, есть ли достаточно места для портала в заданной ориентации
     * Axis.X: рамка шириной по X (east/up), Axis.Z: рамка шириной по Z (south/up)
     */
    private static boolean hasSpaceForPortal(ServerWorld world, BlockPos center, net.minecraft.util.math.Direction.Axis axis) {
        BlockPos bottomLeft = center.down();
        
        // Проверяем рамку 4x5 в зависимости от ориентации
        for (int width = 0; width < 4; width++) {
            for (int height = 0; height < 6; height++) {
                BlockPos pos;
                if (axis == net.minecraft.util.math.Direction.Axis.X) {
                    // Рамка шириной по X: east() для ширины, up() для высоты
                    pos = bottomLeft.east(width).up(height);
                } else {
                    // Рамка шириной по Z: south() для ширины, up() для высоты
                    pos = bottomLeft.south(width).up(height);
                }
                
                // Проверяем, что блок не является препятствием
                if (!world.getBlockState(pos).isAir() && !world.getBlockState(pos).isReplaceable()) {
                    return false;
                }
            }
        }
        return true;
    }
    
    /**
     * Создает платформу под порталом
     */
    private static void createPortalPlatform(ServerWorld world, BlockPos center) {
        LOGGER.info("[Race] Creating portal platform at {}", center);
        
        // Создаем платформу 6x6 из обсидиана под порталом (больше чем рамка 4x5)
        // Платформа должна быть на 2 блока ниже портала, чтобы не мешать рамке
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                BlockPos platformPos = center.add(dx, -2, dz);
                try {
                    world.setBlockState(platformPos, net.minecraft.block.Blocks.OBSIDIAN.getDefaultState());
                } catch (Exception e) {
                    LOGGER.warn("[Race] Failed to place obsidian at {}: {}", platformPos, e.getMessage());
                }
            }
        }
        
        // Дополнительно создаем опорные блоки под углами рамки портала
        // Это гарантирует, что рамка портала будет стоять на обсидиане
        BlockPos[] supportPositions = {
            center.down(2), // центр
            center.down(2).east(), // справа от центра
            center.down(2).east(2), // еще правее
            center.down(2).east(3), // крайний правый
            center.down(2).west(), // слева от центра
            center.down(2).west(2), // еще левее
            center.down(2).west(3), // крайний левый
        };
        
        for (BlockPos supportPos : supportPositions) {
            try {
                world.setBlockState(supportPos, net.minecraft.block.Blocks.OBSIDIAN.getDefaultState());
            } catch (Exception e) {
                LOGGER.warn("[Race] Failed to place support obsidian at {}: {}", supportPos, e.getMessage());
            }
        }
        
        LOGGER.info("[Race] Portal platform created successfully");
    }
    
    /**
     * Проверяет структуру портала и исправляет проблемы с обсидианом
     */
    private static void verifyPortalStructure(ServerWorld world, BlockPos center) {
        LOGGER.info("[Race] Verifying portal structure at {}", center);
        
        // Проверяем, что под порталом есть обсидиан
        boolean hasObsidianBelow = false;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos checkPos = center.add(dx, -2, dz);
                if (world.getBlockState(checkPos).isOf(net.minecraft.block.Blocks.OBSIDIAN)) {
                    hasObsidianBelow = true;
                    break;
                }
            }
            if (hasObsidianBelow) break;
        }
        
        if (!hasObsidianBelow) {
            LOGGER.warn("[Race] No obsidian found under portal, creating emergency platform");
            // Создаем экстренную платформу прямо под порталом
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos platformPos = center.add(dx, -1, dz);
                    try {
                        world.setBlockState(platformPos, net.minecraft.block.Blocks.OBSIDIAN.getDefaultState());
                    } catch (Exception e) {
                        LOGGER.warn("[Race] Failed to create emergency platform at {}: {}", platformPos, e.getMessage());
                    }
                }
            }
        }
        
        // Проверяем, что рамка портала из обсидиана на месте
        BlockPos bottomLeft = center.down();
        boolean frameIntact = true;
        
        // Проверяем нижнюю часть рамки
        for (int i = 0; i < 4; i++) {
            BlockPos framePos = bottomLeft.east(i);
            if (!world.getBlockState(framePos).isOf(net.minecraft.block.Blocks.OBSIDIAN)) {
                LOGGER.warn("[Race] Missing obsidian in frame at {}", framePos);
                frameIntact = false;
                try {
                    world.setBlockState(framePos, net.minecraft.block.Blocks.OBSIDIAN.getDefaultState());
                } catch (Exception e) {
                    LOGGER.warn("[Race] Failed to fix frame at {}: {}", framePos, e.getMessage());
                }
            }
        }
        
        if (frameIntact) {
            LOGGER.info("[Race] Portal structure verified successfully");
        } else {
            LOGGER.warn("[Race] Portal structure had issues, attempted to fix");
        }
    }
    
    /**
     * Проверяет, есть ли уже портал в окрестности (ищет портальные блоки в радиусе)
     */
    private static boolean hasExistingPortal(ServerWorld world, BlockPos center) {
        try {
            // Простая проверка: ищем любые портальные блоки в радиусе 32 блоков
            int radius = 32;
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    for (int y = -16; y <= 16; y++) {
                        BlockPos pos = center.add(x, y, z);
                        if (world.getBlockState(pos).isOf(net.minecraft.block.Blocks.NETHER_PORTAL)) {
                            LOGGER.info("[Race] Found existing portal block at {}", pos);
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (Exception e) {
            LOGGER.warn("[Race] Error checking for existing portal: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Проверяет, является ли блок частью портальной рамки
     */
    private static boolean isPortalFrame(ServerWorld world, BlockPos pos) {
        try {
            // ИСПРАВЛЕНИЕ: Прогреваем чанк перед проверкой блоков
            ensureFullChunk(world, pos);
            var blockState = world.getBlockState(pos);
            // Проверяем, что это обсидиан (рамка портала)
            if (blockState.isOf(net.minecraft.block.Blocks.OBSIDIAN)) {
                // Проверяем, что рядом есть портал (в пределах 2 блоков)
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            BlockPos nearby = pos.add(dx, dy, dz);
                            // ИСПРАВЛЕНИЕ: Прогреваем чанк перед проверкой блоков
                            ensureFullChunk(world, nearby);
                            if (world.getBlockState(nearby).isOf(net.minecraft.block.Blocks.NETHER_PORTAL)) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // Поиск безопасной высоты в Нижнем мире
    private static int findNetherSafeY(ServerWorld world, int x, int z) {
        // Сначала загружаем чанк
        try {
            var chunkManager = world.getChunkManager();
            if (chunkManager != null) {
                chunkManager.getChunk(x >> 4, z >> 4, net.minecraft.world.chunk.ChunkStatus.FULL, true);
            }
        } catch (Throwable ignored) {}
        
        // Поиск снизу вверх в диапазоне где обычно генерируются порталы
        for (int y = 32; y <= 100; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockPos below = pos.down();
            BlockPos above = pos.up();
            
            try {
                // ИСПРАВЛЕНИЕ: Прогреваем чанк перед проверкой блоков
                ensureFullChunk(world, pos);
                ensureFullChunk(world, below);
                ensureFullChunk(world, above);
                
                // Проверяем: твердый блок снизу, воздух на уровне игрока и выше
                if (world.getBlockState(below).isSolidBlock(world, below) &&
                    world.isAir(pos) && 
                    world.isAir(above) &&
                    world.getFluidState(below).isEmpty() &&
                    world.getFluidState(pos).isEmpty() &&
                    world.getFluidState(above).isEmpty()) {
                    
                    LOGGER.info("[Race] Found safe Nether Y={} at ({}, {})", y, x, z);
                    return y;
                }
            } catch (Exception e) {
                continue;
            }
        }
        
        // ВАЖНО: НЕ возвращаем Y=64, а возвращаем Y=100 (ниже крыши бедрока)
        LOGGER.warn("[Race] No safe Nether Y found, using fallback Y=100 for ({}, {})", x, z);
        return 100;  // Изменили с 64 на 100
    }

    // Быстрый расчёт безопасной высоты без принудительной загрузки чанков
    private static BlockPos computeHeightNoLoad(ServerWorld world, BlockPos approx) {
        int x = approx.getX();
        int z = approx.getZ();
        int y;
        
        // Убираем специальную обработку для Нижнего мира - пусть работает как обычно
        var chunkManager = world.getChunkManager();
        if (chunkManager == null) {
            LOGGER.warn("[Race] ChunkManager is null, using default height");
            return new BlockPos(x, world.getBottomY() + 64, z);
        }
        
        var gen = chunkManager.getChunkGenerator();
        var noiseCfg = chunkManager.getNoiseConfig();
        if (gen == null || noiseCfg == null) {
            LOGGER.warn("[Race] ChunkGenerator or NoiseConfig is null, using default height");
            return new BlockPos(x, world.getBottomY() + 64, z);
        }
        
        y = gen.getHeight(x, z, Heightmap.Type.MOTION_BLOCKING, world, noiseCfg);
        y = Math.max(y, world.getBottomY() + 1);
        
        return new BlockPos(x, y, z);
    }

    // Синхронно подгружает один чанк спавна
    private static void ensureSpawnChunkLoaded(ServerWorld world, BlockPos pos) {
        try {
            var chunkManager = world.getChunkManager();
            if (chunkManager != null) {
                chunkManager.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, true);
            }
        } catch (Throwable ignored) {}
    }

    // Локальный поиск безопасного места: твёрдый блок под ногами, 2 блока воздуха, без жидкости, 3x3 очистка
    private static BlockPos findLocalSafeSpot(ServerWorld world, BlockPos base) {
        int[] rings = new int[]{0,1,2,3,4,5,6};
        int bottomY = world.getBottomY() + 1;
        for (int r : rings) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int x = base.getX() + dx;
                    int z = base.getZ() + dz;
                    // ИСПРАВЛЕНИЕ: Прогреваем чанк до FULL перед любыми проверками
                    int pcx = x >> 4;
                    int pcz = z >> 4;
                    world.getChunk(pcx, pcz, ChunkStatus.FULL, true);
                    int startY;
                    try { startY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z); } catch (Throwable t) { startY = base.getY(); }
                    int maxY = Math.max(startY + 6, base.getY());
                    int minY = Math.max(bottomY, base.getY() - 24);
                    for (int y = maxY; y >= minY; y--) {
                        BlockPos feet = new BlockPos(x, y, z);
                        BlockPos head = feet.up();
                        BlockPos below = feet.down();
                        var belowState = world.getBlockState(below);
                        if (!belowState.isSolidBlock(world, below) || !belowState.getFluidState().isEmpty()) continue;
                        // 3x3 свободно на уровне ног и головы, без жидкости
                        boolean clear = true;
                        for (int ox = -1; ox <= 1 && clear; ox++) {
                            for (int oz = -1; oz <= 1 && clear; oz++) {
                                BlockPos f2 = feet.add(ox, 0, oz);
                                BlockPos h2 = head.add(ox, 0, oz);
                                // ИСПРАВЛЕНИЕ: Прогреваем чанк для каждой проверяемой позиции
                                int f2cx = f2.getX() >> 4;
                                int f2cz = f2.getZ() >> 4;
                                world.getChunk(f2cx, f2cz, ChunkStatus.FULL, true);
                                int h2cx = h2.getX() >> 4;
                                int h2cz = h2.getZ() >> 4;
                                world.getChunk(h2cx, h2cz, ChunkStatus.FULL, true);
                                if (!world.isAir(f2) || !world.isAir(h2)) { clear = false; break; }
                                if (!world.getFluidState(f2).isEmpty() || !world.getFluidState(h2).isEmpty()) { clear = false; break; }
                            }
                        }
                        if (!clear) continue;
                        return feet;
                    }
                }
            }
        }
        // Если вокруг только вода/растительность — создаём платформу
        try { ensureSpawnChunkLoaded(world, base); } catch (Throwable ignored) {}
        return buildDryPlatform(world, new BlockPos(base.getX(), Math.max(base.getY(), bottomY), base.getZ()));
    }

    private static boolean isDrySpot(ServerWorld world, BlockPos feet) {
        BlockPos head = feet.up();
        BlockPos below = feet.down();
        // ИСПРАВЛЕНИЕ: Прогреваем чанк до FULL перед любыми проверками
        int pcx = feet.getX() >> 4;
        int pcz = feet.getZ() >> 4;
        world.getChunk(pcx, pcz, ChunkStatus.FULL, true);
        var belowState = world.getBlockState(below);
        if (!belowState.isSolidBlock(world, below) || !belowState.getFluidState().isEmpty()) return false;
        if (!world.isAir(feet) || !world.isAir(head)) return false;
        if (!world.getFluidState(feet).isEmpty() || !world.getFluidState(head).isEmpty()) return false;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                BlockPos f2 = feet.add(ox, 0, oz);
                BlockPos h2 = head.add(ox, 0, oz);
                // ИСПРАВЛЕНИЕ: Прогреваем чанк для каждой проверяемой позиции
                int f2cx = f2.getX() >> 4;
                int f2cz = f2.getZ() >> 4;
                world.getChunk(f2cx, f2cz, ChunkStatus.FULL, true);
                int h2cx = h2.getX() >> 4;
                int h2cz = h2.getZ() >> 4;
                world.getChunk(h2cx, h2cz, ChunkStatus.FULL, true);
                if (!world.isAir(f2) || !world.isAir(h2)) return false;
                if (!world.getFluidState(f2).isEmpty() || !world.getFluidState(h2).isEmpty()) return false;
            }
        }
        return true;
    }

    private static BlockPos buildDryPlatform(ServerWorld world, BlockPos near) {
        int y = near.getY();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos p = new BlockPos(near.getX() + dx, y - 1, near.getZ() + dz);
                // ИСПРАВЛЕНИЕ: Прогреваем чанк до FULL перед любыми операциями
                int pcx = p.getX() >> 4;
                int pcz = p.getZ() >> 4;
                world.getChunk(pcx, pcz, ChunkStatus.FULL, true);
                try { world.setBlockState(p, net.minecraft.block.Blocks.STONE.getDefaultState()); } catch (Throwable ignored) {}
            }
        }
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos p = new BlockPos(near.getX() + dx, y + dy, near.getZ() + dz);
                    // ИСПРАВЛЕНИЕ: Прогреваем чанк до FULL перед любыми операциями
                    int pcx = p.getX() >> 4;
                    int pcz = p.getZ() >> 4;
                    world.getChunk(pcx, pcz, ChunkStatus.FULL, true);
                    try { world.setBlockState(p, net.minecraft.block.Blocks.AIR.getDefaultState()); } catch (Throwable ignored) {}
                }
            }
        }
        // ИСПРАВЛЕНИЕ: Прогреваем чанк до FULL перед любыми операциями
        int pcx = near.getX() >> 4;
        int pcz = near.getZ() >> 4;
        world.getChunk(pcx, pcz, ChunkStatus.FULL, true);
        try { world.setBlockState(new BlockPos(near.getX(), y, near.getZ()), net.minecraft.block.Blocks.TORCH.getDefaultState()); } catch (Throwable ignored) {}
        return new BlockPos(near.getX(), y, near.getZ());
    }
    

    /**
     * Создает новый мир с уникальным сидом
     */
    private static ServerWorld createNewWorldWithSeed(MinecraftServer server, RegistryKey<World> key, long seed, RegistryKey<World> baseWorldKey) {
        // Используем стандартный ключ мира, но с уникальным сидом
        // Это избежит проблем с реестром измерений
        return createNewWorld(server, key, seed, baseWorldKey, true);
    }

    /**
     * Создает новый мир
     */
    private static ServerWorld createNewWorld(MinecraftServer server, RegistryKey<World> key, long seed, RegistryKey<World> baseWorldKey) {
        return createNewWorld(server, key, seed, baseWorldKey, true);
    }

    private static ServerWorld createNewWorld(MinecraftServer server, RegistryKey<World> key, long seed, RegistryKey<World> baseWorldKey, boolean warmSpawn) {
        // Получаем базовый мир для клонирования настроек
        ServerWorld baseWorld = server.getWorld(baseWorldKey);
        if (baseWorld == null) {
            throw new IllegalStateException("Base world not found: " + baseWorldKey);
        }
        
        // ИСПРАВЛЕНИЕ: Создаем генератор чанков с правильным сидом
        ChunkGenerator chunkGenerator = createSeededGenerator(server, baseWorld, seed);
        if (chunkGenerator == null) {
            LOGGER.error("[Race] Failed to create chunk generator, using base world generator");
            chunkGenerator = baseWorld.getChunkManager().getChunkGenerator();
        }
        
        // Создаем тип измерения - определяем на основе ключа мира
        RegistryEntry<DimensionType> dimensionType;
        try {
            var registryManager = server.getRegistryManager();
            if (registryManager == null) {
                LOGGER.error("[Race] RegistryManager is null, using base world dimension type");
                dimensionType = baseWorld.getDimensionEntry();
            } else {
                var dimensionTypeRegistry = registryManager.get(RegistryKeys.DIMENSION_TYPE);
                if (dimensionTypeRegistry == null) {
                    LOGGER.error("[Race] DimensionType registry is null, using base world dimension type");
                    dimensionType = baseWorld.getDimensionEntry();
                } else {
                    // Определяем тип измерения на основе ключа мира
                    if (key.getValue().getPath().contains("nether")) {
                        dimensionType = dimensionTypeRegistry.entryOf(DimensionTypes.THE_NETHER);
                        System.out.println("✓ Using dimension type: " + dimensionType.getKey() + " for Nether world");
                    } else if (key.getValue().getPath().contains("end")) {
                        dimensionType = dimensionTypeRegistry.entryOf(DimensionTypes.THE_END);
                        System.out.println("✓ Using dimension type: " + dimensionType.getKey() + " for End world");
                    } else {
                        dimensionType = dimensionTypeRegistry.entryOf(DimensionTypes.OVERWORLD);
                        System.out.println("✓ Using dimension type: " + dimensionType.getKey() + " for Overworld");
                    }
                    
                    System.out.println("✓ Dimension type value: " + dimensionType.value());
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to get dimension type, falling back to base world: " + e.getMessage());
            dimensionType = baseWorld.getDimensionEntry();
        }
        
        if (dimensionType == null) {
            LOGGER.error("[Race] DimensionType is null, using base world dimension type");
            dimensionType = baseWorld.getDimensionEntry();
        }
        
        // Создаем опции измерения для нашего мира
        DimensionOptions dimensionOptions = new DimensionOptions(dimensionType, chunkGenerator);
        
        // Получаем сессию хранения
        LevelStorage.Session session = ((race.mixin.MinecraftServerSessionAccessor) server).getSession_FAB();
        if (session == null) {
            LOGGER.error("[Race] Session is null, cannot create world");
            throw new IllegalStateException("Session is null");
        }
        
        // Для стандартных ключей миров не создаем отдельные директории
        // Используем стандартную директорию мира
        Path worldDir = session.getWorldDirectory(key);
        try {
            if (Files.exists(worldDir)) {
                // Для кастомных измерений проверяем наличие region файлов вместо level.dat
                // level.dat существует только в корне мира, а не в папках измерений
                Path regionDir = worldDir.resolve("region");
                boolean hasWorldData = false;
                
                if (Files.exists(regionDir) && Files.isDirectory(regionDir)) {
                    // Проверяем, есть ли .mca файлы (сохраненные чанки)
                    try (var stream = Files.list(regionDir)) {
                        hasWorldData = stream.anyMatch(p -> p.toString().endsWith(".mca"));
                    } catch (Exception ignored) {}
                }
                
                // Дополнительная проверка: ищем другие признаки существующего мира
                if (!hasWorldData) {
                    // Проверяем наличие файлов данных игроков или других файлов мира
                    Path dataDir = worldDir.resolve("data");
                    Path statsDir = worldDir.resolve("stats");
                    Path advancementsDir = worldDir.resolve("advancements");
                    
                    hasWorldData = (Files.exists(dataDir) && Files.isDirectory(dataDir)) ||
                                  (Files.exists(statsDir) && Files.isDirectory(statsDir)) ||
                                  (Files.exists(advancementsDir) && Files.isDirectory(advancementsDir));
                }
                
                if (hasWorldData) {
                    LOGGER.info("[Race] Found existing world data for {} at {}, loading existing world", key.getValue(), worldDir);
                    // НЕ удаляем существующий мир - загружаем его
                } else {
                    // Если нет region файлов, то это действительно неполная директория
                    LOGGER.info("[Race] Found incomplete world directory for {} at {}, cleaning up", key.getValue(), worldDir);
                    java.nio.file.Files.walk(worldDir)
                            .sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> {
                                try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {}
                            });
                }
            }
            Files.createDirectories(worldDir);
        } catch (Exception ignored) {}
        
        // Создаем свойства мира
        ServerWorldProperties properties = server.getSaveProperties().getMainWorldProperties();
        
        // Создаем слушатель прогресса
        net.minecraft.server.WorldGenerationProgressListener progressListener = 
                net.minecraft.server.WorldGenerationProgressTracker.noSpawnChunks();
        
        // Передаём сид через WorldSeedRegistry
        WorldSeedRegistry.put(key, seed);
        
        // Проверяем, существует ли уже мир в сервере
        ServerWorld existingWorld = server.getWorld(key);
        if (existingWorld != null) {
            LOGGER.info("[Race] World {} already exists in server, returning existing world", key.getValue());
            return existingWorld;
        }
        
        // Синхронное создание мира (главный поток)
        Executor executor = net.minecraft.util.Util.getMainWorkerExecutor();
        ServerWorld world;
        try {
            // Создаем мир с правильными параметрами
            world = new ServerWorld(
                    server,
                    executor,
                    session,
                    properties,
                    key,
                    dimensionOptions,
                    progressListener,
                    baseWorld.isDebugWorld(),
                    seed,
                    List.of(),
                    true,
                    null
            );
            
            // ИСПРАВЛЕНИЕ: Регистрируем мир в сервере через WorldRegistrar
            try {
                WorldRegistrar.put(server, key, world);
                LOGGER.info("[Race] World {} registered in server via WorldRegistrar", key.getValue());
            } catch (Exception e) {
                LOGGER.error("[Race] Failed to register world {} via WorldRegistrar: {}", key.getValue(), e.getMessage());
                // Fallback: проверяем, что мир доступен через server.getWorld()
                ServerWorld registeredWorld = server.getWorld(key);
                if (registeredWorld != null) {
                    LOGGER.info("[Race] World {} found in server registry despite registration failure", key.getValue());
                } else {
                    LOGGER.warn("[Race] World {} not found in server registry after creation", key.getValue());
                }
            }
            
            // ИСПРАВЛЕНИЕ: Убираем проблемную инициализацию структур
            // Структуры будут инициализированы автоматически при первой загрузке чанков
            LOGGER.info("[Race] World created, structures will be initialized on first chunk load: {}", key.getValue());
            
            // Инициализируем виртуальное время мира
            race.server.SlotTimeService.initIfAbsent(world, 1000L);
        } catch (Exception e) {
            LOGGER.error("[Race] Failed to create world {}: {}", key.getValue(), e.getMessage());
            throw new RuntimeException(e);
        }

        try {
            var chunkManager = world.getChunkManager();
            if (chunkManager != null) {
                var gen = chunkManager.getChunkGenerator();
                var noiseCfg = chunkManager.getNoiseConfig();
                if (gen != null && noiseCfg != null) {
                    int h00 = gen.getHeight(0, 0, Heightmap.Type.MOTION_BLOCKING, world, noiseCfg);
                    LOGGER.info("[Race] World created: key={}, seed={}, gen={}, sampleHeight(0,0)={}",
                            key.getValue(), seed, gen.getClass().getSimpleName(), h00);
                } else {
                    LOGGER.warn("[Race] ChunkGenerator or NoiseConfig is null for world {}", key.getValue());
                }
            } else {
                LOGGER.warn("[Race] ChunkManager is null for world {}", key.getValue());
            }
            // Базовые gamerule'ы для нормального выживания в персональном мире
            try {
                world.getGameRules().get(GameRules.DO_TILE_DROPS).set(true, server);
                world.getGameRules().get(GameRules.SPECTATORS_GENERATE_CHUNKS).set(false, server);
                world.getGameRules().get(GameRules.DO_FIRE_TICK).set(true, server);
                
                // ИСПРАВЛЕНИЕ: Включаем спавн мобов сразу с защитой от NPE
                world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(true, server);
                System.out.println("[Race] Enabled mob spawning for world: " + key.getValue());
                
                // Для fabric_race миров: отключаем DODAYLIGHTCYCLE
                if (world.getRegistryKey().getValue().getNamespace().equals("fabric_race")) {
                    world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, server);
                } else {
                    world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(true, server);
                }
                world.getGameRules().get(GameRules.RANDOM_TICK_SPEED).set(3, server);
                world.getGameRules().get(GameRules.SPAWN_RADIUS).set(0, server);
                // ПРИМЕЧАНИЕ: Спавн мобов включен сразу с защитой от NPE в миксинах
                try { world.getGameRules().get(GameRules.DO_INSOMNIA).set(false, server); } catch (Throwable ignored) {}
                try { world.getGameRules().get(GameRules.DO_PATROL_SPAWNING).set(false, server); } catch (Throwable ignored) {}
                try { world.getGameRules().get(GameRules.DISABLE_RAIDS).set(true, server); } catch (Throwable ignored) {}
                // Время для fabric_race миров управляется через initWorldIfAbsent
                // Не устанавливаем setTimeOfDay напрямую для fabric_race миров
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            LOGGER.warn("[Race] Post-create diagnostics failed for {}: {}", key.getValue(), t.toString());
        }
        
        // Настраиваем границы мира (скопируем настройки, центр перенесём на будущий спавн)
        WorldBorder.Properties borderProps = baseWorld.getWorldBorder().write();
        world.getWorldBorder().load(borderProps);

        // Портальные связи будут использованы ванильными правилами на основе ключей миров
        
        // Мир уже зарегистрирован выше
        
        // Настраиваем спавн: Overworld — поиск суши; Nether — используем наши методы; End — специальная платформа
        LOGGER.info("[Race] Setting spawn for world: {}", world.getRegistryKey().getValue());
        BlockPos spawnPos;
        if (world.getDimensionEntry().matchesKey(DimensionTypes.OVERWORLD)) {
            spawnPos = chooseOverworldSpawn(world);
            ensureSpawnChunkLoaded(world, spawnPos);
            spawnPos = findLocalSafeSpot(world, spawnPos);
        } else if (world.getDimensionEntry().matchesKey(DimensionTypes.THE_NETHER)) {
            // ИСПРАВЛЕНИЕ: Используем наши специальные методы для Nether
            spawnPos = findNetherSafeSpawn(world);
            LOGGER.info("[Race] Nether spawn set in createNewWorld: {}", spawnPos);
        } else if (baseWorldKey == World.END || world.getDimensionEntry().matchesKey(net.minecraft.world.dimension.DimensionTypes.THE_END)) {
            // НОВОЕ: Специальная обработка для End миров (включая кастомные)
            BlockPos fightOrigin = new BlockPos(0, 64, 0);
            var dragonFightData = net.minecraft.entity.boss.dragon.EnderDragonFight.Data.DEFAULT;
            var dragonFight = new net.minecraft.entity.boss.dragon.EnderDragonFight(world, seed, dragonFightData, fightOrigin);
            
            setEnderDragonFightInWorld(world, dragonFight);
            LOGGER.info("✓ Created EnderDragonFight for End world: {}", key.getValue());
            
            // ИСПРАВЛЕНИЕ: Разрешаем спавн эндерменов и других End существ, но отключаем только Overworld мобов
            world.getGameRules().get(GameRules.DO_PATROL_SPAWNING).set(false, server);
            world.getGameRules().get(GameRules.DO_TRADER_SPAWNING).set(false, server);
            // НЕ отключаем DO_MOB_SPAWNING - это позволит спавниться эндерменам и другим End существам
            LOGGER.info("✓ Configured End world mob spawning (Endermen allowed): {}", key.getValue());
            
            // Создаем платформу для игрока на острове End (вне главного острова)
            spawnPos = new BlockPos(100, 50, 0);
            createEndIslandPlatform(world, spawnPos);
            LOGGER.info("[Race] End island platform created at: {}", spawnPos);
        } else {
            spawnPos = new BlockPos(0, 200, 0);
            ensureSpawnChunkLoaded(world, spawnPos);
            spawnPos = findLocalSafeSpot(world, spawnPos);
        }
        world.setSpawnPos(spawnPos, 0.0F);
        
        // ИСПРАВЛЕНИЕ: Прогреваем чанки после установки спавна
        int cx = spawnPos.getX() >> 4;
        int cz = spawnPos.getZ() >> 4;
        world.getChunk(cx, cz, net.minecraft.world.chunk.ChunkStatus.FULL, true);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.getChunk(cx + dx, cz + dz, net.minecraft.world.chunk.ChunkStatus.FULL, true);
            }
        }
        
        // ИСПРАВЛЕНИЕ: Откладываем инициализацию структур до первого тика мира
        // Это предотвращает NPE в StructureAccessor при создании мира
        LOGGER.info("[Race] World created, structures will be initialized on first tick: {}", world.getRegistryKey().getValue());
        
        try {
            world.getWorldBorder().setCenter(spawnPos.getX(), spawnPos.getZ());
        } catch (Throwable ignored) {}
        // Настройка безопасной высоты спавна в Нижнем мире: используем высокую позицию
        try {
            if (world.getRegistryKey() == World.NETHER) {
                // Используем высокую позицию для Нижнего мира
                BlockPos safe = new BlockPos(0, 120, 0);
                world.setSpawnPos(safe, 0.0F);
            }
        } catch (Throwable ignored) {}
        
        // Мягко запросим чанк спавна без принудительной загрузки (не блокирует главный поток)
        try {
            world.getChunkManager().getChunk(spawnPos.getX() >> 4, spawnPos.getZ() >> 4, ChunkStatus.SURFACE, false);
        } catch (Throwable t) {
            LOGGER.warn("[Race] Non-blocking spawn chunk hint for {}: {}", key.getValue(), t.toString());
        }
        
        // ИСПРАВЛЕНИЕ: Убираем проблемную инициализацию структур
        // Структуры будут инициализированы автоматически при первой загрузке чанков
        LOGGER.info("[Race] World spawn set, structures will be initialized on first chunk load: {}", key.getValue());
        
        // ИНИЦИАЛИЗИРУЕМ ВИРТУАЛЬНОЕ ВРЕМЯ ДЛЯ RACE-МИРОВ
        try {
            race.server.SlotTimeService.initIfAbsent(world, 1000L);
        } catch (Throwable t) {
            LOGGER.warn("[Race] Failed to initialize virtual time for world {}: {}", key.getValue(), t.getMessage());
        }
        
        return world;
    }
    
    
    /**
     * Создает генератор чанков
     */
    private static ChunkGenerator createChunkGenerator(ServerWorld baseWorld, long seed) {
        if (baseWorld == null) {
            LOGGER.error("[Race] BaseWorld is null in createChunkGenerator");
            return null;
        }
        
        var chunkManager = baseWorld.getChunkManager();
        if (chunkManager == null) {
            LOGGER.error("[Race] ChunkManager is null in createChunkGenerator");
            return null;
        }
        
        ChunkGenerator base = chunkManager.getChunkGenerator();
        if (base == null) {
            LOGGER.error("[Race] Base chunk generator is null in createChunkGenerator");
            return null;
        }
        
        if (base instanceof NoiseChunkGenerator noise) {
            // End использует особый источник биомов
            if (baseWorld.getRegistryKey() == World.END) {
                var registryManager = baseWorld.getRegistryManager();
                if (registryManager == null) {
                    LOGGER.error("[Race] RegistryManager is null in createChunkGenerator");
                    return base;
                }
                
                var biomeLookup = registryManager.getOptionalWrapper(RegistryKeys.BIOME).orElse(null);
                if (biomeLookup == null) {
                    LOGGER.error("[Race] Biome registry is null in createChunkGenerator");
                    return base;
                }
                
                BiomeSource endBiomeSource = TheEndBiomeSource.createVanilla(biomeLookup);
                if (endBiomeSource == null) {
                    LOGGER.error("[Race] Failed to create EndBiomeSource");
                    return base;
                }
                
                return new NoiseChunkGenerator(endBiomeSource, noise.getSettings());
            }

            // Overworld/Nether — MultiNoise на параметр-листах
            var lookup = baseWorld.getRegistryManager().getOptionalWrapper(RegistryKeys.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).orElseThrow();
            var paramKey = (baseWorld.getRegistryKey() == World.NETHER)
                    ? MultiNoiseBiomeSourceParameterLists.NETHER
                    : MultiNoiseBiomeSourceParameterLists.OVERWORLD;
            var entry = lookup.getOrThrow(paramKey);
            BiomeSource biomeSource = MultiNoiseBiomeSource.create(entry);
            return new NoiseChunkGenerator(biomeSource, noise.getSettings());
        }
        return base;
    }

    // Создание платформы спавна в End мире и спавн дракона
    private static BlockPos setupEndSpawnPlatform(ServerWorld world) {
        try {
            // Координаты спавна в End (пользовательская позиция)
            BlockPos spawnPos = new BlockPos(-100, 50, -30);
            
            // Подгружаем чанк спавна
            ensureSpawnChunkLoaded(world, spawnPos);
            
            // Создаем платформу из энд-камня (как в ванильном End)
            createEndSpawnPlatform(world, spawnPos);
            
            // Спавним дракона End
            spawnEndDragon(world);
            
            LOGGER.info("[Race] End spawn platform created at {} with dragon", spawnPos);
            return spawnPos;
        } catch (Throwable t) {
            LOGGER.error("[Race] Failed to setup End spawn platform: {}", t.getMessage());
            return new BlockPos(-100, 50, -30);
        }
    }
    
    // Создание платформы из энд-камня в End мире
    private static void createEndSpawnPlatform(ServerWorld world, BlockPos center) {
        try {
            // Создаем большую платформу 15x15 из энд-камня для безопасности
            for (int x = -7; x <= 7; x++) {
                for (int z = -7; z <= 7; z++) {
                    BlockPos pos = center.add(x, -1, z);
                    world.setBlockState(pos, net.minecraft.block.Blocks.END_STONE.getDefaultState());
                }
            }
            
            // Создаем центральную платформу для игрока (3x3)
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos pos = center.add(x, 0, z);
                    world.setBlockState(pos, net.minecraft.block.Blocks.END_STONE.getDefaultState());
                }
            }
            
            // Создаем безопасную зону вокруг центра (5x5)
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos pos = center.add(x, 0, z);
                    if (world.getBlockState(pos).isAir()) {
                        world.setBlockState(pos, net.minecraft.block.Blocks.END_STONE.getDefaultState());
                    }
                }
            }
            
            LOGGER.info("[Race] End spawn platform created at {} (15x15 base, 5x5 safe zone)", center);
        } catch (Throwable t) {
            LOGGER.error("[Race] Failed to create End platform: {}", t.getMessage());
        }
    }
    
    // Спавн дракона End
    private static void spawnEndDragon(ServerWorld world) {
        try {
            // Проверяем, есть ли уже дракон
            var existingDragons = world.getEntitiesByType(
                net.minecraft.entity.EntityType.ENDER_DRAGON, 
                net.minecraft.util.math.Box.of(new net.minecraft.util.math.Vec3d(-100, 50, -30), 200, 200, 200),
                entity -> true
            );
            
            if (existingDragons.isEmpty()) {
                // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Создаем правильную боевую систему для дракона
                net.minecraft.util.math.BlockPos fightOrigin = new net.minecraft.util.math.BlockPos(-100, 50, -30);
                
                // Получаем или создаем EnderDragonFight
                var dragonFight = world.getEnderDragonFight();
                if (dragonFight == null) {
                    // ПРАВИЛЬНОЕ ИСПРАВЛЕНИЕ: Создаем правильный Data объект для EnderDragonFight
                    net.minecraft.entity.boss.dragon.EnderDragonFight.Data defaultData = net.minecraft.entity.boss.dragon.EnderDragonFight.Data.DEFAULT;
                    dragonFight = new net.minecraft.entity.boss.dragon.EnderDragonFight(world, world.getSeed(), defaultData, fightOrigin);
                    LOGGER.info("[Race] Created new EnderDragonFight with default data for world {}", world.getRegistryKey().getValue());
                }
                
                // ОКОНЧАТЕЛЬНОЕ ИСПРАВЛЕНИЕ: Создаем дракона End напрямую через EntityType.create()
                net.minecraft.entity.boss.dragon.EnderDragonEntity dragon = 
                    (net.minecraft.entity.boss.dragon.EnderDragonEntity) 
                    net.minecraft.entity.EntityType.ENDER_DRAGON.create(world);
                
                if (dragon != null) {
                    // Устанавливаем боевую систему ПЕРЕД установкой позиции
                    dragon.setFight(dragonFight);
                    dragon.setFightOrigin(fightOrigin);
                    
                    // Устанавливаем фазу поведения дракона
                    dragon.getPhaseManager().setPhase(net.minecraft.entity.boss.dragon.phase.PhaseType.HOLDING_PATTERN);
                    
                    // Устанавливаем позицию дракона ВНЕ платформы игрока
                    dragon.refreshPositionAndAngles(
                        -50.0, 30.0, 0.0, 
                        world.random.nextFloat() * 360.0F, 
                        0.0F
                    );
                    
                    // Спавним дракона в мир
                    world.spawnEntity(dragon);
                    
                    LOGGER.info("[Race] End dragon spawned at (-50, 50, 0) with proper fight system");
                } else {
                    LOGGER.error("[Race] Failed to create EnderDragonEntity using EntityType.create()");
                }
                
                // Кристаллы и портал создаются автоматически ванильной игрой
                
                LOGGER.info("[Race] End dragon spawned at (-50, 50, 0) with proper fight system, crystals and portal - away from player platform");
            } else {
                LOGGER.info("[Race] End dragon already exists, skipping spawn");
            }
        } catch (Throwable t) {
            LOGGER.error("[Race] Failed to spawn End dragon: {}", t.getMessage());
        }
    }
    
    // УДАЛЕНО: createEndPortal - портал создается автоматически ванильной игрой
    
    // УДАЛЕНО: createEndPortalPlatform - платформа создается автоматически ванильной игрой
    
    // УДАЛЕНО: createEndCrystals - кристаллы создаются автоматически ванильной игрой
    
    /**
     * Создает платформу для игрока на острове End (вне главного острова)
     */
    private static void createEndIslandPlatform(ServerWorld world, BlockPos playerPos) {
        try {
            // Создаем платформу 5x5 из энд-камня для игрока
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos pos = playerPos.add(x, -1, z);
                    world.setBlockState(pos, net.minecraft.block.Blocks.END_STONE.getDefaultState());
                }
            }
            
            // Создаем безопасную зону для игрока (3x3)
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos pos = playerPos.add(x, 0, z);
                    if (world.getBlockState(pos).getBlock() != net.minecraft.block.Blocks.AIR) {
                        world.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState());
                    }
                }
            }
            
            LOGGER.info("[Race] Created End island platform for player at {}", playerPos);
        } catch (Throwable t) {
            LOGGER.error("[Race] Failed to create End island platform: {}", t.getMessage());
        }
    }
    
    /**
     * Создает чистую область вокруг End портала и платформу для игрока
     */
    private static void createEndPortalArea(ServerWorld world, BlockPos portalPos) {
        try {
            // Очищаем большую область вокруг портала (15x15x15)
            for (int x = -7; x <= 7; x++) {
                for (int y = -7; y <= 7; y++) {
                    for (int z = -7; z <= 7; z++) {
                        BlockPos pos = portalPos.add(x, y, z);
                        if (world.getBlockState(pos).getBlock() != net.minecraft.block.Blocks.AIR) {
                            world.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState());
                        }
                    }
                }
            }
            
            // Создаем платформу для игрока на уровне 62 (как в ванилле)
            BlockPos playerPlatform = new BlockPos(0, 62, 0);
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos pos = playerPlatform.add(x, -1, z);
                    world.setBlockState(pos, net.minecraft.block.Blocks.END_STONE.getDefaultState());
                }
            }
            
            // Создаем безопасную зону для игрока (3x3)
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos pos = playerPlatform.add(x, 0, z);
                    if (world.getBlockState(pos).getBlock() != net.minecraft.block.Blocks.AIR) {
                        world.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState());
                    }
                }
            }
            
            LOGGER.info("[Race] Created clean End portal area at {} with player platform", portalPos);
        } catch (Throwable t) {
            LOGGER.error("[Race] Failed to create End portal area: {}", t.getMessage());
        }
    }
    
    /**
     * Устанавливает EnderDragonFight в мир через рефлексию
     */
    private static void setEnderDragonFightInWorld(ServerWorld world, net.minecraft.entity.boss.dragon.EnderDragonFight fight) {
        try {
            var field = ServerWorld.class.getDeclaredField("enderDragonFight");
            field.setAccessible(true);
            field.set(world, fight);
            LOGGER.info("✓ Set EnderDragonFight in custom world: {}", 
                       world.getRegistryKey().getValue());
        } catch (Exception e) {
            LOGGER.error("Failed to set EnderDragonFight: {}", e.getMessage());
        }
    }

    // Поиск безопасной суши поблизости от (0,0), избегая океанов/рек и воды под ногами
    private static BlockPos chooseOverworldSpawn(ServerWorld world) {
        // Быстрый сухой выбор по фиксированным кандидатам
        int[][] candidates = new int[][]{
                {0,0}, {192,0}, {-192,0}, {0,192}, {0,-192},
                {256,256}, {-256,256}, {256,-256}, {-256,-256},
                {384,0}, {-384,0}, {0,384}, {0,-384}
        };
        var chunkManager = world.getChunkManager();
        if (chunkManager == null) {
            LOGGER.warn("[Race] ChunkManager is null, using fallback spawn");
            return new BlockPos(0, world.getBottomY() + 64, 0);
        }
        
        var gen = chunkManager.getChunkGenerator();
        var noiseCfg = chunkManager.getNoiseConfig();
        if (gen == null || noiseCfg == null) {
            LOGGER.warn("[Race] ChunkGenerator or NoiseConfig is null, using fallback spawn");
            return new BlockPos(0, world.getBottomY() + 64, 0);
        }
        
        int bottom = world.getBottomY() + 1;
        for (int[] c : candidates) {
            int x = c[0]; int z = c[1];
            int y = gen.getHeight(x, z, Heightmap.Type.MOTION_BLOCKING, world, noiseCfg);
            if (y <= bottom) continue;
            BlockPos guess = new BlockPos(x, Math.max(y, bottom), z);
            // ИСПРАВЛЕНИЕ: Прогреваем чанк до FULL перед любыми проверками
            int cx = guess.getX() >> 4;
            int cz = guess.getZ() >> 4;
            world.getChunk(cx, cz, ChunkStatus.FULL, true);
            // Прогреваем 3x3 область для безопасности
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    world.getChunk(cx + dx, cz + dz, ChunkStatus.FULL, true);
                }
            }
            BlockPos spot = findLocalSafeSpot(world, guess);
            if (isDrySpot(world, spot)) return spot;
        }
        // Жёсткий фолбэк: построим сухую платформу
        int x = 0, z = 0;  // Используем (0,0) вместо (128,128)
        int y = gen != null && noiseCfg != null ? gen.getHeight(x, z, Heightmap.Type.MOTION_BLOCKING, world, noiseCfg) : world.getBottomY() + 64;
        // Для Nether используем безопасную высоту вместо seaLevel
        int safeY = world.getDimensionEntry().matchesKey(DimensionTypes.THE_NETHER) ? 64 : Math.max(y, world.getSeaLevel() + 1);
        BlockPos near = new BlockPos(x, safeY, z);
        // ИСПРАВЛЕНИЕ: Прогреваем чанк до FULL перед любыми проверками
        int cx = near.getX() >> 4;
        int cz = near.getZ() >> 4;
        world.getChunk(cx, cz, ChunkStatus.FULL, true);
        // Прогреваем 3x3 область для безопасности
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.getChunk(cx + dx, cz + dz, ChunkStatus.FULL, true);
            }
        }
        return buildDryPlatform(world, near);
    }

    private static BlockPos evalSpawnCandidate(ServerWorld world, int x, int z) {
        try {
            world.getChunkManager().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);
            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
            if (y <= world.getBottomY()) return null;
            BlockPos feet = new BlockPos(x, y, z);
            // не океан/река
            try {
                var biome = world.getBiome(feet);
                if (biome.isIn(net.minecraft.registry.tag.BiomeTags.IS_OCEAN) ||
                    biome.isIn(net.minecraft.registry.tag.BiomeTags.IS_DEEP_OCEAN) ||
                    biome.isIn(net.minecraft.registry.tag.BiomeTags.IS_RIVER)) return null;
            } catch (Throwable ignored) {}
            // нет жидкости под ногами и на уровне головы, твёрдый блок снизу, 2 блока воздуха
            BlockPos below = feet.down();
            // ИСПРАВЛЕНИЕ: Прогреваем чанк перед проверкой блоков
            ensureFullChunk(world, feet);
            ensureFullChunk(world, below);
            ensureFullChunk(world, feet.up());
            
            var belowSt = world.getBlockState(below);
            if (!belowSt.isSolidBlock(world, below)) return null;
            if (!world.getFluidState(below).isEmpty()) return null;
            if (!world.isAir(feet) || !world.isAir(feet.up())) feet = feet.up();
            if (!world.isAir(feet) || !world.isAir(feet.up())) return null;
            if (!world.getFluidState(feet).isEmpty() || !world.getFluidState(feet.up()).isEmpty()) return null;
            return feet;
        } catch (Throwable ignored) {
            return null;
        }
    }
    
    
    /**
     * Находит безопасную позицию для спавна
     */
    private static BlockPos findSafeSpawnPosition(ServerWorld world) {
		long seed = world.getSeed();
		// Большой, но безопасный разнос центра спавна. Ограничиваемся границами мира
		// и жёстким лимитом < 29_000_000, чтобы не провоцировать переполнения в генераторе.
		double wbSize = 0.0;
		try { wbSize = world.getWorldBorder().getSize(); } catch (Throwable ignored) {}
		long borderHalf = (long)Math.floor(wbSize > 0.0 ? wbSize / 2.0 : 30_000_000.0);
		long safety = 2048L; // запас до границы
		long hardLimit = 8_000_000L - safety;
		long limit = Math.max(2048L, Math.min(borderHalf - safety, hardLimit));
        long desiredSpread = Math.min(2048L, limit); // ближе к (0,0), чтобы быстрее стартовать
        long centerXl = 0L;
        long centerZl = 0L;
        int centerX = (int)Math.max(Math.min(centerXl, Integer.MAX_VALUE), Integer.MIN_VALUE);
        int centerZ = (int)Math.max(Math.min(centerZl, Integer.MAX_VALUE), Integer.MIN_VALUE);
		// Радиус поиска: 256..2048 блоков, зависит от сида, ограничен мировым бордером
		long borderBound = Math.max(512L, Math.min(limit, borderHalf - safety));
        int maxR = (int)Math.min(512, borderBound); // компактный радиус
        int step = 128; // редкая выборка, сильно меньше тикетов
        for (int r = 0; r <= maxR; r += step) {
			for (int dx = -r; dx <= r; dx += step) {
				for (int dz = -r; dz <= r; dz += step) {
					int x = centerX + dx;
					int z = centerZ + dz;
					// Безопасный запрос высоты: если чанки не готовы или генератор бросит исключение,
					// перехватываем и пропускаем точку
					int y;
                    try {
                        // Не форсируем загрузку чанка — чтобы не создавать тысячи тикетов при поиске спавна
                        world.getChunk(x >> 4, z >> 4, net.minecraft.world.chunk.ChunkStatus.SURFACE, false);
                        var chunkManager = world.getChunkManager();
                        if (chunkManager != null) {
                            var gen = chunkManager.getChunkGenerator();
                            var noiseCfg = chunkManager.getNoiseConfig();
                            if (gen != null && noiseCfg != null) {
                                // Чтение высоты через генератор, что устойчивее чем getTopY в моменты генерации
                                y = gen.getHeight(x, z, net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, world, noiseCfg);
                            } else {
                                y = world.getBottomY() + 64;
                            }
                        } else {
                            y = world.getBottomY() + 64;
                        }
					} catch (Throwable t) {
						continue;
					}
					if (y <= world.getBottomY()) continue;
                    BlockPos pos = new BlockPos(x, y, z);
                    // Специальная корректировка для Нижнего мира: не выше 120 и не на крыше
                    if (world.getRegistryKey() == World.NETHER) {
                        int cap = Math.min(120, Math.max(32, y));
                        int safeY = -1;
                        for (int yy = cap; yy >= 32; yy--) {
                            BlockPos p = new BlockPos(x, yy, z);
                            var below = p.down();
                            var belowState = world.getBlockState(below);
                            if (world.isAir(p) && world.isAir(p.up()) && belowState.isSolidBlock(world, below) && belowState.getFluidState().isEmpty()) {
                                safeY = yy;
                                break;
                            }
                        }
                        if (safeY != -1) {
                            pos = new BlockPos(x, safeY, z);
                        } else {
                            // Если не найдено безопасное место, принудительно ставим на Y=120
                            pos = new BlockPos(x, 120, z);
                        }
                    }
                    var state = world.getBlockState(pos);
                    // отклоняем океаны/реки/пляжи
                    try {
                        var biomeEntry = world.getBiome(pos);
                        if (biomeEntry.isIn(net.minecraft.registry.tag.BiomeTags.IS_OCEAN) ||
                                biomeEntry.isIn(net.minecraft.registry.tag.BiomeTags.IS_DEEP_OCEAN) ||
                                biomeEntry.isIn(net.minecraft.registry.tag.BiomeTags.IS_RIVER)) {
                            continue;
                        }
                    } catch (Throwable ignored) {}
                    // поверхность не должна быть жидкостью, под ногами твёрдый блок
                    if (!state.getFluidState().isEmpty()) continue; // вода/лава
                    BlockPos below = pos.down();
                    var belowState = world.getBlockState(below);
                    if (!belowState.getFluidState().isEmpty()) continue; // под ногами не жидкость
                    if (!belowState.isSolidBlock(world, below)) continue; // нужен твёрдый блок
                    // гарантируем 2 блока воздуха над ногами
                    if (!world.isAir(pos)) pos = pos.up();
                    if (!world.isAir(pos) || !world.isAir(pos.up())) continue;
                    return pos;
				}
			}
		}

        // Фолбэк: ищем ближайшую сушу по квадратной спирали, избегая воды
        for (int r = maxR + step; r <= Math.min(maxR + 512, (int)borderBound); r += step) {
            for (int dx = -r; dx <= r; dx += step) {
                for (int dz = -r; dz <= r; dz += step) {
                    int x = centerX + dx;
                    int z = centerZ + dz;
                    int y = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                    if (y <= world.getBottomY()) continue;
                    BlockPos pos = new BlockPos(x, y, z);
                    var below = pos.down();
                    var belowState = world.getBlockState(below);
                    if (!belowState.getFluidState().isEmpty()) continue;
                    if (!belowState.isSolidBlock(world, below)) continue;
                    if (!world.isAir(pos) || !world.isAir(pos.up())) continue;
                    return pos;
                }
            }
        }
        int y = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, centerX, centerZ);
        return new BlockPos(centerX, Math.max(y, world.getBottomY() + 1), centerZ);
    }
    
    /**
     * Генерирует уникальный ID мира
     */
    private static String getWorldId(UUID playerUuid, long seed, RegistryKey<World> worldKey) {
        String envSuffix = switch (worldKey.getValue().getPath()) {
            case "the_nether" -> "nether";
            case "the_end" -> "end";
            default -> "overworld";
        };
        return "p_" + playerUuid.toString().replace("-", "") + "_" + seed + "_" + envSuffix;
    }
    
    /**
     * Получает мир по ID
     */
    public static ServerWorld getWorldById(MinecraftServer server, String worldId) {
        RegistryKey<World> key = WORLD_KEYS.get(worldId);
        return key != null ? server.getWorld(key) : null;
    }
    
    /**
     * Очищает кэш миров
     */
    public static void clearWorldCache() {
        WORLD_KEYS.clear();
    }

    /**
     * Аккуратно выгружает все созданные личные миры (для предотвращения падений при остановке сервера).
     */
    public static void unloadAllWorlds(MinecraftServer server) {
        for (var entry : new java.util.ArrayList<>(WORLD_KEYS.entrySet())) {
            try {
                var key = entry.getValue();
                ServerWorld w = server.getWorld(key);
                if (w != null) {
                    LOGGER.info("[Race] Unloading personal world {}", key.getValue());
                    // Планируем выгрузку без сохранения немедленно, но асинхронно
                    enqueueUnload(server, key, 0L, true);
                }
            } catch (Throwable t) {
                LOGGER.error("[Race] Failed to unload world {}", entry.getValue(), t);
            }
        }
        WORLD_KEYS.clear();
        PLAYER_SLOT.clear();
    }

    public static void releasePlayerSlot(MinecraftServer server, UUID playerUuid) {
        Integer slot = PLAYER_SLOT.remove(playerUuid);
        if (slot == null) return;
        String[] suffixes = {"overworld", "nether", "end"};
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        for (var e : WORLD_KEYS.entrySet()) {
            String id = e.getKey();
            for (String sfx : suffixes) {
                String prefix = "slot" + slot + "_" + sfx + "_s";
                if (id.startsWith(prefix)) {
                    ids.add(id);
                    break;
                }
            }
        }
        for (String id : ids) {
            RegistryKey<World> key = WORLD_KEYS.remove(id);
            if (key != null) {
                // Не уничтожаем миры, которые ещё находятся в фазе создания/прогрева
                if (Boolean.TRUE.equals(WORLD_CREATING.get(key))) {
                    LOGGER.info("[Race] Skip destroying {} for player {}: world still creating", key.getValue(), playerUuid);
                    continue;
                }
                LOGGER.info("[Race] Keeping slot{} world {} for reuse (player {})", slot, key.getValue(), playerUuid);
                // НЕ выгружаем мир автоматически - оставляем его для повторного использования
                // Мир будет выгружен только при очистке старых слотов или принудительно
            }
        }
    }

    private static void destroyWorld(MinecraftServer server, RegistryKey<World> key) {
        // По умолчанию используем отложенную выгрузку без сохранения (без блокировок)
        enqueueUnload(server, key, 5000L, true);
    }

    /**
     * Планирует безопасную выгрузку мира: перенос игроков → (опционально) сохранение → закрытие → удаление реестра.
     * Все операции выполняются в главном треде, но планируются через однопоточную очередь, чтобы
     * не конкурировать между собой и не блокировать тики долгими IO-операциями.
     */
    private static void enqueueUnload(MinecraftServer server, RegistryKey<World> key, long delayMs, boolean skipSave) {
        if (PENDING_UNLOAD.putIfAbsent(key, Boolean.TRUE) != null) return; // уже запланировано
        Runnable task = () -> server.execute(() -> {


            try {
                    ServerWorld w = server.getWorld(key);
                    if (w == null) { 
                        LOGGER.info("[Race] World {} already unloaded, skipping", key.getValue());
                        PENDING_UNLOAD.remove(key); 
                        return; 
                    }
                    
                    // Проверяем, что мир ещё не был уничтожен
                    try {
                        w.getChunkManager();
                    } catch (Throwable t) {
                        LOGGER.info("[Race] World {} already destroyed, skipping", key.getValue());
                        PENDING_UNLOAD.remove(key);
                        return;
                    }
                    
                    // Переносим игроков в Overworld
                    try {
                        for (var p : server.getPlayerManager().getPlayerList()) {
                            if (p.getServerWorld() == w) {
                                var overworld = server.getOverworld();
                                p.teleport(overworld, overworld.getSpawnPos().getX() + 0.5, overworld.getSpawnPos().getY(), overworld.getSpawnPos().getZ() + 0.5, p.getYaw(), p.getPitch());
                            }
                        }
                    } catch (Throwable ignored) {}
                    // Останавливаем персональный исполнитель (если использовался) без ожиданий
                    java.util.concurrent.ExecutorService es = WORLD_EXECUTORS.remove(key);
                    if (es != null) {
                        try { es.shutdownNow(); } catch (Throwable ignored) {}
                    }
                    // Сохраняем по желанию (без flush, чтобы не блокировать IO)
                    if (!skipSave) {
                        try { w.save(null, false, false); } catch (Throwable t) { LOGGER.warn("[Race] save (optional) failed for {}: {}", key.getValue(), t.toString()); }
                    }
                    // Закрываем и убираем из реестра
                    try { w.close(); } catch (Throwable t) { LOGGER.warn("[Race] close failed for {}: {}", key.getValue(), t.toString()); }
                    WorldRegistrar.remove(server, key);
                    // Удаляем папку мира (безопасно)
                    try {
                        LevelStorage.Session session = ((race.mixin.MinecraftServerSessionAccessor) server).getSession_FAB();
                        Path worldDir = session.getWorldDirectory(key);
                        if (java.nio.file.Files.exists(worldDir)) {
                            java.nio.file.Files.walk(worldDir)
                                    .sorted(java.util.Comparator.reverseOrder())
                                    .forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {} });
                        }
                    } catch (Exception ignored) {}
                } finally {
                    PENDING_UNLOAD.remove(key);

                }
            });
        };

    public static void beginShutdownAndFlush(MinecraftServer server) {
        SHUTTING_DOWN = true;
        try { EnhancedWorldManager.UNLOAD_EXECUTOR.shutdownNow(); } catch (Throwable ignored) {}
        // Синхронно выгружаем все оставшиеся миры
        EnhancedWorldManager.unloadAllWorlds(server);
    }
    
    /**
     * Создает рамку портала 4x5 в заданном направлении
     */
    private static void createFrame(ServerWorld world, BlockPos bottomLeft, net.minecraft.util.math.Direction right) {
        // Нижняя горизонтальная часть рамки (4 блока)
        for (int i = 0; i < 4; i++) {
            world.setBlockState(bottomLeft.offset(right, i), net.minecraft.block.Blocks.OBSIDIAN.getDefaultState());
        }
        
        // Левая вертикальная часть рамки (5 блоков)
        for (int y = 1; y <= 4; y++) {
            world.setBlockState(bottomLeft.up(y), net.minecraft.block.Blocks.OBSIDIAN.getDefaultState());
        }
        
        // Правая вертикальная часть рамки (5 блоков)
        for (int y = 1; y <= 4; y++) {
            world.setBlockState(bottomLeft.offset(right, 3).up(y), net.minecraft.block.Blocks.OBSIDIAN.getDefaultState());
        }
        
        // Верхняя горизонтальная часть рамки (4 блока)
        for (int i = 0; i < 4; i++) {
            world.setBlockState(bottomLeft.up(5).offset(right, i), net.minecraft.block.Blocks.OBSIDIAN.getDefaultState());
        }
    }
    
    /**
     * Находит первый свободный слот для указанного сида
     */
    public static int findFirstFreeSlotForSeed(MinecraftServer server, long seed) {
        // Получаем все занятые слоты
        java.util.Set<Integer> usedSlots = new java.util.HashSet<>();
        
        // Добавляем слоты из карты игроков
        for (Integer slot : PLAYER_SLOT.values()) {
            if (slot != null && slot > 0) {
                usedSlots.add(slot);
            }
        }
        
        // Также проверяем WorldManager (используем новый метод)
        for (int i = 1; i <= MAX_SLOTS; i++) {
            if (race.server.WorldManager.isSlotOccupied(i)) {
                usedSlots.add(i);
            }
        }
        
        // Ищем первый свободный слот
        for (int slot = 1; slot <= MAX_SLOTS; slot++) {
            if (!usedSlots.contains(slot)) {
                LOGGER.info("[Race] Found free slot {} for seed {}", slot, seed);
                return slot;
            }
        }
        
        LOGGER.warn("[Race] All slots are occupied, using slot 1 as fallback");
        return 1; // Fallback
    }
    
    /**
     * Создает или получает мир для игрока с указанным слотом (для параллельных гонок)
     */
    public static ServerWorld getOrCreateWorldForParallelRace(MinecraftServer server, UUID playerUuid, int slot, long seed, RegistryKey<World> worldKey) {
        setCurrentServer(server);
        
        // Принудительно назначаем указанный слот
        PLAYER_SLOT.put(playerUuid, slot);
        race.server.WorldManager.setPlayerSlot(playerUuid, slot);
        
        // Кешируем seed игрока
        setPlayerSeed(playerUuid, seed);
        
        Identifier slotId = getSlotId(worldKey, slot, seed);
        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, slotId);
        String worldId = slotId.getPath();
        
        LOGGER.info("[Race] getOrCreateWorldForParallelRace: player={}, slot={}, dim={}, seed={}, id={}", 
                   playerUuid, slot, worldKey.getValue(), seed, worldId);
        
        WORLD_KEYS.putIfAbsent(worldId, key);
        
        // Очистим старые миры этого слота c другим сидом
        cleanupOldSlotWorlds(server, slot, worldKey, worldId);
        
        // Получаем или создаем мир
        ServerWorld existing = server.getWorld(key);
        if (existing != null) {
            LOGGER.info("[Race] Using existing parallel world: {}", key.getValue());
            return existing;
        }
        
        return createNewWorld(server, key, seed, worldKey);
    }

    /**
     * Создает ChunkGenerator для кастомного мира с правильным сидом
     * ИСПРАВЛЕНИЕ: Генератор создается с сидом с самого начала, без поздней подмены
     */
    private static ChunkGenerator createSeededGenerator(MinecraftServer server, ServerWorld baseWorld, long seed) {
        try {
            if (server == null) {
                LOGGER.error("[Race] Server is null in createSeededGenerator");
                return null;
            }
            
            if (baseWorld == null) {
                LOGGER.error("[Race] BaseWorld is null in createSeededGenerator");
                return null;
            }
            
            var drm = server.getRegistryManager();
            if (drm == null) {
                LOGGER.error("[Race] RegistryManager is null in createSeededGenerator");
                return baseWorld.getChunkManager().getChunkGenerator();
            }
            
            var base = baseWorld.getChunkManager().getChunkGenerator();
            if (base == null) {
                LOGGER.error("[Race] Base chunk generator is null in createSeededGenerator");
                return null;
            }
            
            if (base instanceof NoiseChunkGenerator noiseGen) {
                var settings = noiseGen.getSettings(); // RegistryEntry<ChunkGeneratorSettings>
                if (settings == null) {
                    LOGGER.error("[Race] ChunkGeneratorSettings is null in createSeededGenerator");
                    return base;
                }
                
                var src0 = noiseGen.getBiomeSource();
                if (src0 == null) {
                    LOGGER.error("[Race] BiomeSource is null in createSeededGenerator");
                    return base;
                }
                
                BiomeSource src = src0;
                
                // Проверяем тип источника биомов и создаем новый с сидом
                if (src0 instanceof net.minecraft.world.biome.source.MultiNoiseBiomeSource) {
                    try { 
                        // MultiNoise → новый источник с сидом
                        var params = drm.get(net.minecraft.registry.RegistryKeys.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
                        if (params == null) {
                            LOGGER.warn("[Race] MultiNoiseBiomeSourceParameterList registry is null, using base source");
                            src = src0;
                        } else {
                            var entry = params.entryOf(net.minecraft.world.biome.source.MultiNoiseBiomeSourceParameterLists.OVERWORLD);
                            if (entry == null) {
                                LOGGER.warn("[Race] OVERWORLD parameter list is null, using base source");
                                src = src0;
                            } else {
                                src = net.minecraft.world.biome.source.MultiNoiseBiomeSource.create(entry);
                            }
                        }
                    } catch (Throwable ignored) {
                        // Если создание нового источника не удалось, используем базовый источник
                        src = src0;
                    }
                } else if (src0 instanceof net.minecraft.world.biome.source.TheEndBiomeSource) {
                    // Для TheEndBiomeSource используем базовый источник (он уже правильный для End)
                    src = src0;
                } else {
                    // Для других типов источников используем базовый источник
                    src = src0;
                }
                
                if (src == null) {
                    LOGGER.error("[Race] Final BiomeSource is null, using base generator");
                    return base;
                }
                
                return new NoiseChunkGenerator(src, settings);
            }
            
            return base;
        } catch (Exception e) {
            LOGGER.error("[Race] Failed to create seeded generator: {}", e.getMessage());
            return baseWorld.getChunkManager().getChunkGenerator();
        }
    }
}


