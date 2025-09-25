package race.mixin.vanillafix;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import race.server.phase.PhaseState;

/**
 * Исправления багов с порталами Нижнего мира для честной игры
 */
@Mixin(NetherPortalBlock.class)
public class MixinNetherPortal {
    
    @Inject(method = "randomTick", at = @At("HEAD"))
    private void onRandomTick(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        
        // Если гонка активна, исправляем баги с порталами
        if (PhaseState.isRaceActive()) {
            
            // Исправление бага с исчезновением порталов
            if (state.getBlock() == Blocks.NETHER_PORTAL) {
                // Проверяем, что портал не исчезнет из-за бага
                if (!world.getBlockState(pos.up()).isOf(Blocks.NETHER_PORTAL) && 
                    !world.getBlockState(pos.down()).isOf(Blocks.NETHER_PORTAL)) {
                    
                    // Восстанавливаем портал если он должен существовать
                    world.setBlockState(pos, Blocks.NETHER_PORTAL.getDefaultState());
                }
            }
        }
    }
}
