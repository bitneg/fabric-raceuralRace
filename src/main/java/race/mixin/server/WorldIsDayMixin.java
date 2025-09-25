package race.mixin.server;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Миксин для привязки World.isDay к виртуальному времени SLOT_TIME.
 * Обеспечивает системную синхронизацию всех серверных гейтов дня/ночи.
 */
@Mixin(World.class)
public abstract class WorldIsDayMixin {

    @Inject(method = "isDay", at = @At("HEAD"), cancellable = true)
    private void race$isDayBySlotTime(CallbackInfoReturnable<Boolean> cir) {
        World self = (World) (Object) this;
        if (!(self instanceof ServerWorld w)) return;
        
        String id = w.getRegistryKey().getValue().toString();
        if (!id.startsWith("fabric_race:")) return;
        
        // Получаем виртуальное время из SlotTimeService
        long t = race.server.SlotTimeService.getTime(w.getRegistryKey());
        long tod = t % 24000L;
        
        // День: вне диапазона 13000-23000 (ночь)
        boolean isDay = tod < 13000L || tod > 23000L;
        cir.setReturnValue(isDay);
        
        // Логируем только при изменении состояния для уменьшения спама
        if (System.currentTimeMillis() % 5000 < 100) { // каждые ~5 секунд
            System.out.println("[SlotTime] isDay: " + w.getRegistryKey().getValue() + 
                             " time=" + tod + " day=" + isDay);
        }
    }
}
