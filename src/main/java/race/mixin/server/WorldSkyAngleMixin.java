package race.mixin.server;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Миксин для привязки угла солнца к виртуальному времени SLOT_TIME.
 * Обеспечивает корректное горение нежити на солнце после сна.
 */
@Mixin(World.class)
public abstract class WorldSkyAngleMixin {

    @Inject(method = "getSkyAngleRadians(F)F", at = @At("HEAD"), cancellable = true)
    private void race$skyAngleBySlotTime(float tickDelta, CallbackInfoReturnable<Float> cir) {
        World self = (World) (Object) this;
        if (!(self instanceof ServerWorld w)) return;
        
        String id = w.getRegistryKey().getValue().toString();
        if (!id.startsWith("fabric_race:")) return;
        
        // Получаем виртуальное время из SlotTimeService
        long t = race.server.SlotTimeService.getTime(w.getRegistryKey());
        
        // Вычисляем угол солнца по виртуальному времени (как в ваниле)
        float f = ((float)(t % 24000L) + tickDelta) / 24000.0F;
        float g = f - 0.25F;
        if (g < 0) g += 1.0F;
        if (g > 1) g -= 1.0F;
        float h = 1.0F - ((float)Math.cos(g * (float)Math.PI) + 1.0F) / 2.0F;
        float angle = (g + (h - g) / 3.0F) * ((float)Math.PI * 2F);
        
        cir.setReturnValue(angle);
        
        // Логируем только при изменении состояния для уменьшения спама
        if (System.currentTimeMillis() % 5000 < 100) { // каждые ~5 секунд
            System.out.println("[SlotTime] Sky angle radians: " + w.getRegistryKey().getValue() + 
                             " time=" + (t % 24000L) + " angle=" + angle);
        }
    }
}
