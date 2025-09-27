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
                // ИСПРАВЛЕНИЕ: Загружаем чанки до статуса FULL для предотвращения NPE
                world.getChunk(cx + dx, cz + dz, ChunkStatus.FULL, true);
            }
        }
        BlockPos safe = findSafeSpot(world, approx);
        safeSpawn.put(world, safe);
    }

    public static BlockPos getSafe(ServerWorld world) {
        return safeSpawn.getOrDefault(world, world.getSpawnPos());
    }

    private static BlockPos findSafeSpot(ServerWorld w, BlockPos near) {
        // ИСПРАВЛЕНИЕ: Принудительно загружаем чанк перед проверкой блоков
        try {
            int cx = near.getX() >> 4;
            int cz = near.getZ() >> 4;
            w.getChunk(cx, cz, ChunkStatus.FULL, true);
        } catch (Throwable t) {
            // Игнорируем ошибки загрузки чанков
        }
        
        // дёшево: опуститься до твёрдого блока и подняться на 1–2
        BlockPos pos = near;
        int y = Math.min(near.getY(), w.getTopY());
        while (y > w.getBottomY()+2) {
            BlockPos checkPos = pos.withY(y);
            // ИСПРАВЛЕНИЕ: Прогреваем чанк для каждой проверяемой позиции
            try {
                int checkCx = checkPos.getX() >> 4;
                int checkCz = checkPos.getZ() >> 4;
                w.getChunk(checkCx, checkCz, ChunkStatus.FULL, true);
            } catch (Throwable t) {
                // Игнорируем ошибки загрузки чанков
            }
            
            try {
                if (w.isAir(checkPos)) {
                    y--;
                } else {
                    break;
                }
            } catch (Throwable t) {
                // Игнорируем ошибки чтения блоков
                break;
            }
        }
        return new BlockPos(near.getX(), y + 2, near.getZ());
    }
}
