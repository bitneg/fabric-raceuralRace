package race.server.world;

import com.google.gson.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
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
 * Хранит предпочитаемые миры для игроков (где появляться при входе).
 * Отдельно от ReturnPoint - это не временная точка возврата.
 */
public final class PreferredWorldRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(PreferredWorldRegistry.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path dataFile;
    
    private static final ConcurrentHashMap<UUID, RegistryKey<World>> PREF = new ConcurrentHashMap<>();

    // Инициализация при запуске сервера
    public static void initialize(MinecraftServer server) {
        // Используем путь к папке мира через session
        try {
            var session = ((race.mixin.MinecraftServerSessionAccessor) server).getSession_FAB();
            dataFile = session.getWorldDirectory(net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, net.minecraft.util.Identifier.of("minecraft", "overworld"))).resolve("race_preferred_worlds.json");
        } catch (Exception e) {
            // Fallback: используем папку run/saves
            dataFile = java.nio.file.Paths.get("run", "saves", "race_preferred_worlds.json");
        }
        loadFromFile();
        
        // Сохранение при остановке сервера
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> saveToFile());
        
        LOGGER.info("[Race] PreferredWorldRegistry initialized with data file: {}", dataFile);
    }

    /**
     * Устанавливает предпочитаемый мир для игрока
     */
    public static void setPreferred(UUID id, RegistryKey<World> key) {
        PREF.put(id, key);
        saveToFileAsync();
        LOGGER.info("[Race] Set preferred world for {}: {}", id, key.getValue());
    }

    /**
     * Получает предпочитаемый мир игрока
     */
    public static RegistryKey<World> getPreferred(UUID id) {
        return PREF.get(id);
    }

    /**
     * Проверяет, есть ли предпочитаемый мир
     */
    public static boolean hasPreferred(UUID id) {
        return PREF.containsKey(id);
    }

    /**
     * Очищает предпочитаемый мир
     */
    public static void clear(UUID id) {
        PREF.remove(id);
        saveToFileAsync();
        LOGGER.info("[Race] Cleared preferred world for {}", id);
    }

    // Сохранение данных в JSON файл
    private static void saveToFile() {
        if (dataFile == null) return;
        
        try {
            JsonObject root = new JsonObject();
            for (var entry : PREF.entrySet()) {
                root.addProperty(entry.getKey().toString(), entry.getValue().getValue().toString());
            }
            
            Files.createDirectories(dataFile.getParent());
            try (FileWriter writer = new FileWriter(dataFile.toFile())) {
                GSON.toJson(root, writer);
            }
            
            LOGGER.debug("[Race] Saved {} preferred worlds to file", PREF.size());
        } catch (IOException e) {
            LOGGER.error("[Race] Failed to save preferred worlds: {}", e.getMessage());
        }
    }
    
    // Асинхронное сохранение
    private static void saveToFileAsync() {
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
                    String worldStr = entry.getValue().getAsString();
                    Identifier worldId = Identifier.tryParse(worldStr);
                    if (worldId != null) {
                        RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, worldId);
                        PREF.put(playerId, worldKey);
                        loaded++;
                    }
                } catch (Exception e) {
                    LOGGER.warn("[Race] Failed to load preferred world for player {}: {}", entry.getKey(), e.getMessage());
                }
            }
            
            LOGGER.info("[Race] Loaded {} preferred worlds from file", loaded);
        } catch (Exception e) {
            LOGGER.error("[Race] Failed to load preferred worlds: {}", e.getMessage());
        }
    }

    private PreferredWorldRegistry() {}
}
