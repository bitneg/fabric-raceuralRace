package race.tp;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import race.server.world.SpawnCache;

public final class SafeTeleport {
    public static void toWorldSpawn(ServerPlayerEntity p, ServerWorld target) {
        BlockPos pos = SpawnCache.getSafe(target);
        // без ensureLoaded FULL; чанки уже прогреты заранее
        p.teleport(target, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                   p.getYaw(), p.getPitch());
    }
}
