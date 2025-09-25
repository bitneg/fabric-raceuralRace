package race.server.world;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkStatus;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class SpawnCache {
    private static final Map<ServerWorld, BlockPos> safeSpawn = new ConcurrentHashMap<>();

    public static void warmupAndCache(ServerWorld world, BlockPos approx, int radiusChunks) {
        // выполнять НЕ в момент телепорта; запланировать заранее
        int cx = approx.getX() >> 4, cz = approx.getZ() >> 4;
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                world.getChunkManager().getChunk(cx + dx, cz + dz, ChunkStatus.SURFACE, true);
            }
        }
        BlockPos safe = findSafeSpot(world, approx);
        safeSpawn.put(world, safe);
    }

    public static BlockPos getSafe(ServerWorld world) {
        return safeSpawn.getOrDefault(world, world.getSpawnPos());
    }

    private static BlockPos findSafeSpot(ServerWorld w, BlockPos near) {
        // дёшево: опуститься до твёрдого блока и подняться на 1–2
        BlockPos pos = near;
        int y = Math.min(near.getY(), w.getTopY());
        while (y > w.getBottomY()+2 && w.isAir(pos.withY(y))) y--;
        return new BlockPos(near.getX(), y + 2, near.getZ());
    }
}
