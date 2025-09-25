package race.server.world;

import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Регистр персональных сидов на время конструирования {@link net.minecraft.server.world.ServerWorld}.
 */
public final class WorldSeedRegistry {
    private static final ConcurrentHashMap<RegistryKey<World>, Long> WORLD_TO_SEED = new ConcurrentHashMap<>();

    private WorldSeedRegistry() {}

    public static void put(RegistryKey<World> key, long seed) {
        WORLD_TO_SEED.put(key, seed);
    }

    public static Long get(RegistryKey<World> key) {
        return WORLD_TO_SEED.get(key);
    }

    public static void remove(RegistryKey<World> key) {
        WORLD_TO_SEED.remove(key);
    }
}


