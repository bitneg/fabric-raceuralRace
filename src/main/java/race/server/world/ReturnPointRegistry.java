package race.server.world;

import com.google.gson.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Хранит точки возврата игроков перед наблюдением/телепортом.
 * Теперь с персистентным хранением в JSON файлах.
 */
public final class ReturnPointRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReturnPointRegistry.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path dataFile;
    
    public static final class ReturnPoint {
        public final RegistryKey<World> worldKey;
        public final double x;
        public final double y;
        public final double z;
        public final float yaw;
        public final float pitch;
        public final GameMode gameMode;

        public ReturnPoint(RegistryKey<World> worldKey, double x, double y, double z, float yaw, float pitch, GameMode gameMode) {
            this.worldKey = worldKey;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.gameMode = gameMode;
        }
        
        // Конвертация для JSON
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("world", worldKey.getValue().toString());
            obj.addProperty("x", x);
            obj.addProperty("y", y);
            obj.addProperty("z", z);
            obj.addProperty("yaw", yaw);
            obj.addProperty("pitch", pitch);
            obj.addProperty("gameMode", gameMode.name());
            return obj;
        }
        
        public static ReturnPoint fromJson(JsonObject obj) {
            try {
                Identifier worldId = Identifier.tryParse(obj.get("world").getAsString());
                RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, worldId);
                double x = obj.get("x").getAsDouble();
                double y = obj.get("y").getAsDouble();
                double z = obj.get("z").getAsDouble();
                float yaw = obj.get("yaw").getAsFloat();
                float pitch = obj.get("pitch").getAsFloat();
                GameMode gameMode = GameMode.valueOf(obj.get("gameMode").getAsString());
                return new ReturnPoint(worldKey, x, y, z, yaw, pitch, gameMode);
            } catch (Exception e) {
                LOGGER.warn("Failed to parse ReturnPoint from JSON: {}", e.getMessage());
                return null;
            }
        }
    }

    private static final ConcurrentHashMap<UUID, ReturnPoint> MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Boolean> LOCK = new ConcurrentHashMap<>();

    // Инициализация при запуске сервера
    public static void initialize(MinecraftServer server) {
        // Используем путь к папке мира через session
        try {
            var session = ((race.mixin.MinecraftServerSessionAccessor) server).getSession_FAB();
            dataFile = session.getWorldDirectory(net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, net.minecraft.util.Identifier.of("minecraft", "overworld"))).resolve("race_return_points.json");
        } catch (Exception e) {
            // Fallback: используем папку run/saves
            dataFile = java.nio.file.Paths.get("run", "saves", "race_return_points.json");
        }
        loadFromFile();
        
        // Сохранение при остановке сервера
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> saveToFile());
        
        LOGGER.info("[Race] ReturnPointRegistry initialized with data file: {}", dataFile);
    }

    /**
     * Управление блокировкой точек возврата
     */
    public static void setLocked(UUID id, boolean locked) {
        if (locked) {
            LOCK.put(id, Boolean.TRUE);
        } else {
            LOCK.remove(id);
        }
    }

    public static void saveCurrent(ServerPlayerEntity player) {
        try {
            // 1) не сохраняем при наблюдении
            if (player.interactionManager.getGameMode() == GameMode.SPECTATOR) return;

            // 2) не сохраняем, если точка «зафиксирована» на время сессии
            if (Boolean.TRUE.equals(LOCK.get(player.getUuid()))) return;

            RegistryKey<World> key = player.getServerWorld().getRegistryKey();
            String worldName = key.getValue().toString();
            
            // Логирование для отладки
            LOGGER.info("[Race] Saving return point for {}: world={}, pos=({}, {}, {})", 
                       player.getName().getString(), worldName, player.getX(), player.getY(), player.getZ());
            
            ReturnPoint rp = new ReturnPoint(
                    key,
                    player.getX(), player.getY(), player.getZ(),
                    player.getYaw(), player.getPitch(),
                    player.interactionManager.getGameMode()
            );
            
            MAP.put(player.getUuid(), rp);
            
            // Асинхронное сохранение на диск
            saveToFileAsync();
            
        } catch (Exception e) {
            LOGGER.error("[Race] Failed to save return point for {}: {}", player.getName().getString(), e.getMessage());
        }
    }

    /**
     * Автосейв: «только если отсутствует», без перезаписи
     */
    public static void saveIfAbsent(ServerPlayerEntity player) {
        if (player.interactionManager.getGameMode() == GameMode.SPECTATOR) return;
        if (Boolean.TRUE.equals(LOCK.get(player.getUuid()))) return;
        
        MAP.computeIfAbsent(player.getUuid(), id -> {
            RegistryKey<World> key = player.getServerWorld().getRegistryKey();
            ReturnPoint rp = new ReturnPoint(
                key, player.getX(), player.getY(), player.getZ(),
                player.getYaw(), player.getPitch(),
                player.interactionManager.getGameMode()
            );
            saveToFileAsync();
            return rp;
        });
    }

    public static void clear(ServerPlayerEntity player) {
        MAP.remove(player.getUuid());
        setLocked(player.getUuid(), false);
        saveToFileAsync();
    }

    public static ReturnPoint get(ServerPlayerEntity player) {
        return MAP.get(player.getUuid());
    }
    
    /**
     * ПРИНУДИТЕЛЬНО сохраняет точку возврата (перезаписывает существующую)
     */
    public static void forceSetReturnPoint(ServerPlayerEntity player) {
        try {
            // Убираем старую точку возврата
            MAP.remove(player.getUuid());
            
            // Сохраняем новую
            saveCurrent(player);
            
            // Зафиксировать на время наблюдения/джойна
            setLocked(player.getUuid(), true);
            
            LOGGER.info("[Race] FORCED save return point for {}", player.getName().getString());
        } catch (Exception e) {
            LOGGER.error("[Race] Failed to force save return point: {}", e.getMessage());
        }
    }
    
    /**
     * Проверяет, есть ли сохраненная точка возврата
     */
    public static boolean hasReturnPoint(UUID playerId) {
        return MAP.containsKey(playerId);
    }
    
    /**
     * Получает информацию о точке возврата без удаления
     */
    public static ReturnPoint peek(UUID playerId) {
        return MAP.get(playerId);
    }
    
    /**
     * Возвращает игрока БЕЗ удаления точки возврата (для отладки)
     */
    public static boolean returnPlayerKeepPoint(ServerPlayerEntity player) {
        ReturnPoint rp = MAP.get(player.getUuid()); // НЕ удаляем
        if (rp == null) return false;
        
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        
        ServerWorld dst = server.getWorld(rp.worldKey);
        if (dst == null) {
            dst = server.getOverworld(); // fallback
        }
        
        boolean isPersonal = rp.worldKey.getValue().getNamespace().equals("fabric_race");
        GameMode mode = isPersonal ? GameMode.SURVIVAL : rp.gameMode;
        
        try { player.changeGameMode(mode); } catch (Throwable ignored) {}
        player.teleport(dst, rp.x, rp.y, rp.z, rp.yaw, rp.pitch);
        
        LOGGER.info("[Race] Returned player {} (kept return point)", player.getName().getString());
        return true;
    }
    
    /**
     * Очищает точку возврата игрока (для использования после завершения spectate сессии)
     */
    public static void clearReturnPoint(ServerPlayerEntity player) {
        ReturnPoint removed = MAP.remove(player.getUuid());
        setLocked(player.getUuid(), false);
        if (removed != null) {
            saveToFileAsync();
            LOGGER.info("[Race] Cleared return point for {}", player.getName().getString());
        }
    }
    
    /**
     * Возвращает игрока и УДАЛЯЕТ точку возврата (старое поведение)
     */
    public static boolean returnPlayerAndClear(ServerPlayerEntity player) {
        boolean success = returnPlayer(player); // возвращаем БЕЗ удаления
        if (success) {
            clearReturnPoint(player); // ЗАТЕМ удаляем
        }
        return success;
    }

    public static boolean returnPlayer(ServerPlayerEntity player) {
        ReturnPoint rp = MAP.get(player.getUuid()); // НЕ УДАЛЯЕМ! Используем get() вместо remove()
        if (rp == null) return false;
        
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        
        ServerWorld dst = server.getWorld(rp.worldKey);
        if (dst == null) {
            // Если персональный мир выгружен, попробуем восстановить по сиду игрока
            long seed = race.hub.HubManager.getPlayerSeedChoice(player.getUuid());
            if (seed >= 0) {
                try {
                    // ИСПРАВЛЕНИЕ: Используем сохраненный ключ мира напрямую
                    // Это предотвращает ошибки с неизвестными типами измерений
                    LOGGER.info("[Race] ReturnPointRegistry: Restoring world {} for player", rp.worldKey.getValue());
                    dst = server.getWorld(rp.worldKey);
                    
                    // Если мир не найден, попробуем создать его заново
                    if (dst == null) {
                        LOGGER.warn("[Race] ReturnPointRegistry: World {} not found, attempting to recreate", rp.worldKey.getValue());
                        // Используем базовый тип мира для создания
                        net.minecraft.registry.RegistryKey<World> baseWorldKey;
                        String worldPath = rp.worldKey.getValue().getPath();
                        if (worldPath.contains("nether")) {
                            baseWorldKey = World.NETHER;
                        } else if (worldPath.contains("end")) {
                            baseWorldKey = World.END;
                        } else {
                            baseWorldKey = World.OVERWORLD;
                        }
                        dst = race.server.world.EnhancedWorldManager.getOrCreateWorld(server, player.getUuid(), seed, baseWorldKey);
                    }
                } catch (Throwable ignored) {}
            }
            if (dst == null) dst = server.getOverworld();
        }
        
        // Если возвращаемся в персональный мир, принудительно SURVIVAL
        boolean isPersonal = rp.worldKey.getValue().getNamespace().equals("fabric_race");
        GameMode mode = isPersonal ? GameMode.SURVIVAL : rp.gameMode;
        
        try { player.changeGameMode(mode); } catch (Throwable ignored) {}
        player.teleport(dst, rp.x, rp.y, rp.z, rp.yaw, rp.pitch);
        
        try { player.sendAbilitiesUpdate(); } catch (Throwable ignored) {}
        
        // Снять фиксацию при возврате
        setLocked(player.getUuid(), false);
        
        // Сохраняем изменения после удаления позиции
        saveToFileAsync();
        
        LOGGER.info("[Race] Returned player {} to saved position ({}, {}, {}) in world {} [KEPT RETURN POINT]", 
                    player.getName().getString(), rp.x, rp.y, rp.z, rp.worldKey.getValue());
        return true;
    }

    // Сохранение данных в JSON файл
    private static void saveToFile() {
        if (dataFile == null) return;
        
        try {
            JsonObject root = new JsonObject();
            for (var entry : MAP.entrySet()) {
                root.add(entry.getKey().toString(), entry.getValue().toJson());
            }
            
            Files.createDirectories(dataFile.getParent());
            try (FileWriter writer = new FileWriter(dataFile.toFile())) {
                GSON.toJson(root, writer);
            }
            
            LOGGER.debug("[Race] Saved {} return points to file", MAP.size());
        } catch (IOException e) {
            LOGGER.error("[Race] Failed to save return points: {}", e.getMessage());
        }
    }
    
    // Асинхронное сохранение
    private static void saveToFileAsync() {
        // Сохраняем в отдельном потоке чтобы не блокировать игру
        Thread.startVirtualThread(() -> {
            try {
                saveToFile();
            } catch (Throwable t) {
                LOGGER.warn("[Race] Async save failed: {}", t.getMessage());
            }
        });
    }

    // Загрузка данных из JSON файла
    private static void loadFromFile() {
        if (dataFile == null || !Files.exists(dataFile)) return;
        
        try (FileReader reader = new FileReader(dataFile.toFile())) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            
            int loaded = 0;
            for (var entry : root.entrySet()) {
                try {
                    UUID playerId = UUID.fromString(entry.getKey());
                    ReturnPoint point = ReturnPoint.fromJson(entry.getValue().getAsJsonObject());
                    if (point != null) {
                        MAP.put(playerId, point);
                        loaded++;
                    }
                } catch (Exception e) {
                    LOGGER.warn("[Race] Failed to load return point for player {}: {}", entry.getKey(), e.getMessage());
                }
            }
            
            LOGGER.info("[Race] Loaded {} return points from file", loaded);
        } catch (Exception e) {
            LOGGER.error("[Race] Failed to load return points: {}", e.getMessage());
        }
    }

    /**
     * Сохраняет точку возврата для хаба
     */
    public static void saveHubPoint(ServerPlayerEntity player) {
        try {
            ReturnPoint hubPoint = new ReturnPoint(
                net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, 
                    net.minecraft.util.Identifier.of("hub", "world")),
                player.getX(), player.getY(), player.getZ(),
                player.getYaw(), player.getPitch(),
                player.interactionManager.getGameMode()
            );
            
            MAP.put(player.getUuid(), hubPoint);
            saveToFile();
            
            LOGGER.info("[Race] Saved hub return point for {}", player.getName().getString());
        } catch (Exception e) {
            LOGGER.error("[Race] Failed to save hub point for {}", player.getName().getString(), e);
        }
    }

    private ReturnPointRegistry() {}
}