package race.mixin;

import net.minecraft.fluid.FlowableFluid;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FlowableFluid.class)
public class FlowableFluidMixin {
    
    private static final int RACE_FLOW_DELAY = 5; // тиков задержки (было 1 тик)
    
    /**
     * Замедляем скорость обновления жидкостей в гоночных мирах
     */
    @Inject(method = "onScheduledTick", at = @At("HEAD"), cancellable = true)
    private void raceSlowFluidTick(World world, BlockPos pos, net.minecraft.fluid.FluidState fluidState, CallbackInfo ci) {
        if (!(world instanceof ServerWorld)) return;
        
        // Проверяем, это гоночный мир
        String worldName = world.getRegistryKey().getValue().toString();
        if (!worldName.startsWith("fabric_race:slot")) return;
        
        // ИСПРАВЛЕНИЕ: Используем хеш позиции для стабильности между мирами
        int positionHash = (pos.getX() + pos.getY() * 31 + pos.getZ() * 961) % RACE_FLOW_DELAY;
        long worldTime = world.getTime();
        
        if ((worldTime + positionHash) % RACE_FLOW_DELAY != 0) {
            ci.cancel(); // Пропускаем этот тик
            
            // Планируем следующее обновление
            world.scheduleFluidTick(pos, ((FlowableFluid)(Object)this), RACE_FLOW_DELAY);
        }
    }
}