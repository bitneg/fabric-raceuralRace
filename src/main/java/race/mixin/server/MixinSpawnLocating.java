package race.mixin.server;

import net.minecraft.server.network.SpawnLocating;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SpawnLocating.class)
public class MixinSpawnLocating {

    @Inject(method = "findOverworldSpawn", at = @At("HEAD"), cancellable = true)
    private static void race$fastOverworldSpawn(ServerWorld world, int x, int z, CallbackInfoReturnable<BlockPos> cir) {
        String key = world.getRegistryKey().getValue().toString();
        if (!key.startsWith("fabric_race:")) return;
        try {
            var gen = world.getChunkManager().getChunkGenerator();
            var noise = world.getChunkManager().getNoiseConfig();
            int y = gen.getHeight(x, z, net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, world, noise) + 1;
            cir.setReturnValue(new BlockPos(x, Math.max(y, world.getBottomY() + 1), z));
        } catch (Throwable t) {
            cir.setReturnValue(new BlockPos(x, 200, z));
        }
    }
}


