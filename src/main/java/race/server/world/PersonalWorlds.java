package race.server.world;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldGenerationProgressListener;
import net.minecraft.server.WorldGenerationProgressTracker;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.level.ServerWorldProperties;
import net.minecraft.world.level.storage.LevelStorage;
import race.mixin.MinecraftServerSessionAccessor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

public final class PersonalWorlds {
    private static final ConcurrentHashMap<String, RegistryKey<World>> KEYS = new ConcurrentHashMap<>();

    public static ServerWorld getOrCreate(MinecraftServer server, UUID uuid, long seed) {
        // Уникальный ключ мира для игрока+сида
        String worldId = "p_" + uuid.toString().replace("-", "") + "_" + seed;
        RegistryKey<World> key = KEYS.computeIfAbsent(
                worldId,
                k -> RegistryKey.of(RegistryKeys.WORLD, Identifier.of("fabric_race", k))
        ); // [6]

        // Уже создан ранее?
        ServerWorld existing = server.getWorld(key);
        if (existing != null) return existing; // [6]

        // База — оверворлд и тип измерения
        ServerWorld overworld = server.getOverworld();
        RegistryEntry<net.minecraft.world.dimension.DimensionType> dimType = overworld.getDimensionEntry();

        // Генератор: клонируем NoiseChunkGenerator или оставляем как есть
        ChunkGenerator clonedGen = cloneNoiseGenerator(overworld);
        DimensionOptions dimOpts = new DimensionOptions(dimType, clonedGen); // [8]

        // Достаём LevelStorage.Session через Mixin-аксессор
        LevelStorage.Session session = ((MinecraftServerSessionAccessor) server).getSession_FAB(); // [1][6]

        // (Опционально) отдельная директория внутри сейва
        Path dimDir = server.getSavePath(WorldSavePath.ROOT).resolve("DIM_FABRIC_RACE_" + key.getValue().getPath());
        try { Files.createDirectories(dimDir); } catch (Exception ignored) {}

        // Свойства, слушатель прогресса, executor
        ServerWorldProperties props = server.getSaveProperties().getMainWorldProperties();
        WorldGenerationProgressListener listener = WorldGenerationProgressTracker.noSpawnChunks();
        Executor worker = server;

        // Сохраняем настройки бордера и переносим в новый мир
        WorldBorder.Properties borderProps = overworld.getWorldBorder().write();

        // Создаём новый ServerWorld (конструктор требует LevelStorage.Session третьим параметром)
        ServerWorld world = new ServerWorld(
                server,
                worker,
                session,
                props,
                key,
                dimOpts,
                listener,
                overworld.isDebugWorld(),
                seed,
                List.of(),
                false, // Отключаем ванильный инкремент времени - только наш тикер
                null
        ); // [8]

        world.getWorldBorder().load(borderProps);

        // Регистрируем мир на сервере (см. WorldRegistrar.put)
        WorldRegistrar.put(server, key, world); // добавляет в карту миров сервера [6]

        // Инициализируем виртуальное время мира
        race.server.SlotTimeService.initIfAbsent(world, 1000L);

        // Находим безопасный спавн и прогреваем чанк
        BlockPos spawn = findSafeSpawn(world);
        world.setSpawnPos(spawn, 0.0F);
        world.getChunk(spawn.getX() >> 4, spawn.getZ() >> 4, ChunkStatus.FULL, true);

        return world;
    }

    private static ChunkGenerator cloneNoiseGenerator(ServerWorld overworld) {
        ChunkGenerator base = overworld.getChunkManager().getChunkGenerator();
        if (base instanceof NoiseChunkGenerator noise) {
            return new NoiseChunkGenerator(noise.getBiomeSource(), noise.getSettings()); // [8]
        }
        return base;
    }

    private static BlockPos findSafeSpawn(ServerWorld world) {
        int x = 0, z = 0;
        NoiseConfig noise = world.getChunkManager().getNoiseConfig();
        int y0 = world.getChunkManager().getChunkGenerator()
                .getHeight(x, z, net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, world, noise);
        BlockPos.Mutable m = new BlockPos.Mutable(x, y0, z);
        int[] off = {0, 4, -4, 8, -8, 12, -12, 16, -16};
        for (int dx : off) for (int dz : off) {
            int tx = x + dx, tz = z + dz;
            int ty = world.getChunkManager().getChunkGenerator()
                    .getHeight(tx, tz, net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, world, noise);
            BlockPos p = new BlockPos(tx, ty, tz);
            if (world.isAir(p) && world.isAir(p.up())) return p;
        }
        return m.toImmutable();
    }

    private PersonalWorlds() {}
}
