package race.cache;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кеш для часто используемых данных с автоматической очисткой
 */
public final class PerformanceCache {
    private static final Map<UUID, PlayerCache> playerCache = new ConcurrentHashMap<>();
    private static final Map<String, WorldCache> worldCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL = 5000; // 5 секунд
    private static final int MAX_CACHE_SIZE = 100;
    
    public static class PlayerCache {
        public final String worldKey;
        public final long seed;
        public final String activity;
        public final long timestamp;
        
        public PlayerCache(String worldKey, long seed, String activity) {
            this.worldKey = worldKey;
            this.seed = seed;
            this.activity = activity;
            this.timestamp = System.currentTimeMillis();
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL;
        }
    }
    
    public static class WorldCache {
        public final int playerCount;
        public final boolean isPersonal;
        public final long timestamp;
        
        public WorldCache(int playerCount, boolean isPersonal) {
            this.playerCount = playerCount;
            this.isPersonal = isPersonal;
            this.timestamp = System.currentTimeMillis();
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL;
        }
    }
    
    public static PlayerCache getPlayerCache(UUID playerId) {
        PlayerCache cache = playerCache.get(playerId);
        if (cache != null && cache.isExpired()) {
            playerCache.remove(playerId);
            return null;
        }
        return cache;
    }
    
    public static void setPlayerCache(UUID playerId, String worldKey, long seed, String activity) {
        if (playerCache.size() >= MAX_CACHE_SIZE) {
            // Очищаем старые записи
            playerCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        }
        playerCache.put(playerId, new PlayerCache(worldKey, seed, activity));
    }
    
    public static WorldCache getWorldCache(String worldKey) {
        WorldCache cache = worldCache.get(worldKey);
        if (cache != null && cache.isExpired()) {
            worldCache.remove(worldKey);
            return null;
        }
        return cache;
    }
    
    public static void setWorldCache(String worldKey, int playerCount, boolean isPersonal) {
        if (worldCache.size() >= MAX_CACHE_SIZE) {
            // Очищаем старые записи
            worldCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        }
        worldCache.put(worldKey, new WorldCache(playerCount, isPersonal));
    }
    
    public static void clearExpired() {
        playerCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        worldCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }
    
    public static void clearAll() {
        playerCache.clear();
        worldCache.clear();
    }
}
