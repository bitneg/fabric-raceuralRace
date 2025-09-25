package race.mixin.standardrng;

import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.WorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import race.server.phase.PhaseState;

/**
 * Стандартизация спавна мобов для честной игры
 * В Minecraft 1.21.1 используем миксин для MobEntity
 */
@Mixin(MobEntity.class)
public class MixinSpawnHelper {
    
    @Inject(method = "canMobSpawn", at = @At("HEAD"), cancellable = true)
    private static void onCanMobSpawn(net.minecraft.entity.EntityType<? extends MobEntity> type, WorldAccess world, 
                                     SpawnReason spawnReason, BlockPos pos, Random random, CallbackInfoReturnable<Boolean> cir) {
        
        // Полностью отдаём спавн ваниле. Ничего не меняем.
        return;
    }
}
