package race.mixin.standardrng;

import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import race.server.phase.PhaseState;

/**
 * Стандартизация поведения дракона для честной игры
 */
@Mixin(EnderDragonEntity.class)
public class MixinEnderDragonEntity {
    
    @Inject(method = "tickMovement", at = @At("HEAD"))
    private void onTickMovement(CallbackInfo ci) {
        EnderDragonEntity dragon = (EnderDragonEntity) (Object) this;
        World world = dragon.getWorld();
        
        // Если гонка активна, используем детерминированное поведение
        if (PhaseState.isRaceActive()) {
            long raceSeed = PhaseState.getRaceSeed();
            long raceTime = (System.currentTimeMillis() - PhaseState.getRaceStartTime()) / 50; // тики
            
            // Синхронизируем поведение дракона на основе сида и времени гонки
            dragon.getRandom().setSeed(raceSeed + raceTime);
        }
    }
    
    @Inject(method = "tickWithEndCrystals", at = @At("HEAD"))
    private void onTickWithEndCrystals(CallbackInfo ci) {
        EnderDragonEntity dragon = (EnderDragonEntity) (Object) this;
        
        if (PhaseState.isRaceActive()) {
            long raceSeed = PhaseState.getRaceSeed();
            long raceTime = (System.currentTimeMillis() - PhaseState.getRaceStartTime()) / 50;
            
            // Синхронизируем регенерацию кристаллов
            dragon.getRandom().setSeed(raceSeed + raceTime + 1000);
        }
    }
}
