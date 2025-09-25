package race.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FluidBlock.class)
public class FluidBlockMixin {
    
    /**
     * Замедляем случайные обновления жидкостей в гоночных мирах
     */
    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void raceSlowFluidRandomTick(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        String worldName = world.getRegistryKey().getValue().toString();
        if (!worldName.startsWith("fabric_race:slot")) return;
        
        // Замедляем случайные обновления жидкостей в 10 раз
        if (random.nextInt(10) != 0) {
            ci.cancel();
        }
    }
}