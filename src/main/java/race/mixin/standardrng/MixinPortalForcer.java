package race.mixin.standardrng;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.feature.EndPortalFeature;
import net.minecraft.world.gen.feature.util.FeatureContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import race.server.phase.PhaseState;

/**
 * Стандартизация генерации порталов для честной игры
 */
@Mixin(EndPortalFeature.class)
public class MixinPortalForcer {
    
    @Inject(method = "generate", at = @At("HEAD"))
    private void onGenerate(FeatureContext context, CallbackInfoReturnable<Boolean> cir) {
        
        // Если гонка активна, используем детерминированную генерацию порталов
        if (PhaseState.isRaceActive()) {
            long raceSeed = PhaseState.getRaceSeed();
            BlockPos pos = context.getOrigin();
            
            // Синхронизируем генерацию портала на основе сида и позиции
            context.getWorld().getRandom().setSeed(raceSeed + pos.asLong());
        }
    }
}
