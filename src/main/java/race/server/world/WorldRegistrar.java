package race.server.world;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.minecraft.registry.RegistryKey;
import race.mixin.server.MinecraftServerWorldsAccessor;

import java.util.Map;

final class WorldRegistrar {
    static void put(MinecraftServer server, RegistryKey<World> key, ServerWorld world) {
        try {
            Map<RegistryKey<World>, ServerWorld> worlds = 
                ((MinecraftServerWorldsAccessor) server).getWorlds_FAB();
            worlds.put(key, world);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to register world", t);
        }
    }

    static void remove(MinecraftServer server, RegistryKey<World> key) {
        try {
            Map<RegistryKey<World>, ServerWorld> worlds = 
                ((MinecraftServerWorldsAccessor) server).getWorlds_FAB();
            worlds.remove(key);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to unregister world", t);
        }
    }

    private WorldRegistrar() {}
}
