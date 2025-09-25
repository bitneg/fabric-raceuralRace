package race.spatial;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import java.util.*;

public final class PlayerBuckets {
    // world -> chunk -> players
    public static Map<ServerWorld, Map<Long, List<ServerPlayerEntity>>> index(MinecraftServer server) {
        Map<ServerWorld, Map<Long, List<ServerPlayerEntity>>> out = new HashMap<>();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            ServerWorld w = (ServerWorld) p.getWorld();
            out.computeIfAbsent(w, k -> new HashMap<>());
            ChunkPos cp = new ChunkPos(p.getBlockPos());
            long key = cp.toLong();
            out.get(w).computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
        return out;
    }

    public static List<ServerPlayerEntity> nearby(ServerWorld w, ChunkPos center, int rChunks,
                                                  Map<ServerWorld, Map<Long, List<ServerPlayerEntity>>> idx) {
        Map<Long, List<ServerPlayerEntity>> map = idx.getOrDefault(w, Map.of());
        List<ServerPlayerEntity> res = new ArrayList<>();
        for (int dx = -rChunks; dx <= rChunks; dx++) {
            for (int dz = -rChunks; dz <= rChunks; dz++) {
                long key = ChunkPos.toLong(center.x + dx, center.z + dz);
                List<ServerPlayerEntity> l = map.get(key);
                if (l != null) res.addAll(l);
            }
        }
        return res;
    }
}
