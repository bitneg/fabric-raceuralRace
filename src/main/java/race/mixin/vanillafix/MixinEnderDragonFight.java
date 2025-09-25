package race.mixin.vanillafix;

import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.EnderDragonFight;
import net.minecraft.util.math.BlockPos;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import race.server.phase.PhaseState;
import race.mixin.EnderDragonFightAccessor;

/**
 * Исправления багов в битве с драконом для честной игры
 */
@Mixin(EnderDragonFight.class)
public class MixinEnderDragonFight {
    
    @Inject(method = "updateFight", at = @At("HEAD"))
    private void onUpdateFight(EnderDragonEntity dragon, CallbackInfo ci) {
        
        // Если гонка активна, исправляем известные баги
        if (PhaseState.isRaceActive()) {
            if (dragon.getWorld() instanceof ServerWorld world) {
                // Исправление бага с кристаллами Энда
                if (dragon.getHealth() <= 0.0F) {
                    // Убеждаемся, что дракон действительно мертв
                    if (!dragon.isDead()) {
                        dragon.kill();
                    }
                }
            }
        }
    }
    
    // УДАЛЕНЫ методы создания портала - они работают автоматически в кастомных мирах
    
}
