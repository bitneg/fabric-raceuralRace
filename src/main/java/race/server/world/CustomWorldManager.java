package race.server.world;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.World;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class CustomWorldManager {
    private static final Map<String, ServerWorld> customWorlds = new ConcurrentHashMap<>();
    
    public static ServerWorld getOrCreateCustomWorld(MinecraftServer server, String worldName, long seed) {
        try {
            // Проверяем существующий мир
            ServerWorld existingWorld = customWorlds.get(worldName);
            if (existingWorld != null) {
                System.out.println("✓ Reusing existing custom world: " + worldName);
                return existingWorld;
            }
            
            // Создаем ключ для мира
            Identifier worldId = Identifier.of("fabric_race", worldName);
            RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, worldId);
            
            System.out.println("=== CREATING CUSTOM WORLD ===");
            System.out.println("World name: " + worldName);
            System.out.println("World key: " + worldKey);
            System.out.println("Seed: " + seed);
            
            // ИСПРАВЛЕНИЕ: Используем существующий метод getOrCreateWorld
            // Это гарантирует правильную регистрацию и синхронизацию
            // Создаем фиктивный UUID для команды
            UUID teamId = UUID.randomUUID();
            ServerWorld customWorld = EnhancedWorldManager.getOrCreateWorld(
                server, 
                teamId, 
                seed, 
                World.OVERWORLD
            );
            
            if (customWorld == null) {
                System.err.println("Failed to create world with EnhancedWorldManager, falling back to overworld");
                return server.getOverworld();
            }
            
            // Кэшируем мир
            customWorlds.put(worldName, customWorld);
            
            System.out.println("✓ Custom world created and registered: " + worldName);
            System.out.println("✓ World registry key: " + customWorld.getRegistryKey());
            System.out.println("✓ Dimension type entry in world: " + customWorld.getDimensionEntry());
            
            return customWorld;
            
        } catch (Exception e) {
            System.err.println("Failed to create custom world '" + worldName + "': " + e.getMessage());
            e.printStackTrace();
            
            // Fallback к overworld
            System.out.println("Falling back to overworld");
            return server.getOverworld();
        }
    }
    
    public static ServerWorld getOrCreatePersonalWorld(MinecraftServer server, UUID playerId, long seed) {
        String worldName = "player_" + playerId.toString().replace("-", "") + "_s" + seed;
        return getOrCreateCustomWorld(server, worldName, seed);
    }
    
    public static void cleanupWorld(String worldName) {
        customWorlds.remove(worldName);
    }
    
    public static Map<String, ServerWorld> getAllCustomWorlds() {
        return new ConcurrentHashMap<>(customWorlds);
    }
}
