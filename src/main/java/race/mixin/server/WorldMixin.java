package race.mixin.server;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public class WorldMixin {
    
    /**
     * Безопасно обрабатываем null чанки в гоночных мирах
     */
    @Inject(method = "getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;", 
            at = @At("HEAD"), cancellable = true)
    private void safeGetBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        World world = (World) (Object) this;
        
        // Проверяем, что это гоночный мир
        if (world instanceof ServerWorld serverWorld) {
            String worldName = serverWorld.getRegistryKey().getValue().toString();
            if (worldName.startsWith("fabric_race:")) {
                // Получаем чанк с дополнительной защитой
                try {
                    var chunk = world.getChunk(pos);
                    if (chunk == null) {
                        // Если чанк null, возвращаем воздух
                        cir.setReturnValue(Blocks.AIR.getDefaultState());
                        return;
                    }
                } catch (Exception e) {
                    // Дополнительная защита от любых исключений
                    cir.setReturnValue(Blocks.AIR.getDefaultState());
                    return;
                }
            }
        }
    }
}
