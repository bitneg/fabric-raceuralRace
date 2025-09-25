package race.server;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.concurrent.TimeUnit;

public final class ChunkSyncHelper {
    private ChunkSyncHelper() {}

    public static final class SyncBudget {
        public int remainingMs;
        public int maxChunks;

        public void reset(int ms, int chunks) {
            this.remainingMs = ms;
            this.maxChunks = chunks;
        }

        public boolean consume(int ms) {
            if (ms <= 0) return false;
            if (remainingMs <= 0 || maxChunks <= 0) return false;
            remainingMs -= ms;
            maxChunks -= 1;
            return true;
        }
    }

    public static WorldChunk trySyncLoad(ServerWorld world, int chunkX, int chunkZ, SyncBudget budget, int timeoutMs) {
        if (budget == null || budget.remainingMs <= 0 || budget.maxChunks <= 0) return null;
        WorldChunk wc = world.getChunkManager().getWorldChunk(chunkX, chunkZ);
        if (wc != null) return wc;
        long start = System.nanoTime();
        try {
            var fut = world.getChunkManager().getChunkFutureSyncOnMainThread(chunkX, chunkZ, ChunkStatus.FULL, true);
            fut.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
        int spent = (int) ((System.nanoTime() - start) / 1_000_000);
        if (!budget.consume(spent)) return null;
        return world.getChunkManager().getWorldChunk(chunkX, chunkZ);
    }
}


