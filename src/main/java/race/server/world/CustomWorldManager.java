package race.server.world;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Создание именованных (командных) миров без рандомных UUID.
 * - Парсит slot из worldName "slotX_overworld_s<seed>" и использует getOrCreateWorldForGroup
 * - Прогревает чанки до FULL перед любыми чтениями
 * - Кэширует имя -> ServerWorld
 */
public final class CustomWorldManager {
    private static final Map<String, ServerWorld> customWorlds = new ConcurrentHashMap<>();

    public static ServerWorld getOrCreateCustomWorld(MinecraftServer server, String worldName, long seed) {
        ServerWorld existing = customWorlds.get(worldName);
        if (existing != null) return existing;

        int slot = extractSlot(worldName);
        RegistryKey<World> dimKey = extractDimension(worldName);
        if (slot <= 0) {
            slot = EnhancedWorldManager.findFirstFreeSlotForSeed(server, seed);
            System.out.println("⚠ Slot not found in name, assigned free slot " + slot + " for " + worldName);
        }

        ServerWorld w = EnhancedWorldManager.getOrCreateWorldForGroup(server, slot, seed, dimKey);
        if (w == null) return server.getOverworld();

        // Прогрев спавн-чанков до FULL
        var spawn = w.getSpawnPos();
        int cx = spawn.getX() >> 4, cz = spawn.getZ() >> 4;
        w.getChunk(cx, cz, net.minecraft.world.chunk.ChunkStatus.FULL, true);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                w.getChunk(cx + dx, cz + dz, net.minecraft.world.chunk.ChunkStatus.FULL, true);
            }
        }

        customWorlds.put(worldName, w);
        return w;
    }

    public static ServerWorld getOrCreatePersonalWorld(MinecraftServer server, UUID playerId, long seed) {
        // Важно: привязать слот к seed
        int slot = EnhancedWorldManager.getOrAssignSlot(server, playerId, seed);
        if (slot <= 0) {
            slot = EnhancedWorldManager.findFirstFreeSlotForSeed(server, seed);
        }
        ServerWorld w = EnhancedWorldManager.getOrCreateWorldForGroup(server, slot, seed, World.OVERWORLD);
        if (w == null) return server.getOverworld();

        // Прогрев спавн-чанков до FULL
        var spawn = w.getSpawnPos();
        int cx = spawn.getX() >> 4, cz = spawn.getZ() >> 4;
        w.getChunk(cx, cz, net.minecraft.world.chunk.ChunkStatus.FULL, true);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                w.getChunk(cx + dx, cz + dz, net.minecraft.world.chunk.ChunkStatus.FULL, true);
            }
        }
        return w;
    }

    public static void cleanupWorld(String worldName) {
        customWorlds.remove(worldName);
    }

    public static Map<String, ServerWorld> getAllCustomWorlds() {
        return new ConcurrentHashMap<>(customWorlds);
    }

    private static int extractSlot(String name) {
        try {
            int i = name.indexOf("slot");
            if (i >= 0) {
                int j = i + 4, k = j;
                while (k < name.length() && Character.isDigit(name.charAt(k))) k++;
                return Integer.parseInt(name.substring(j, k));
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private static RegistryKey<World> extractDimension(String name) {
        if (name.contains("_nether_")) return World.NETHER;
        if (name.contains("_end_")) return World.END;
        return World.OVERWORLD;
    }

    private CustomWorldManager() {}
}