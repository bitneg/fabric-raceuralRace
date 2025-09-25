package race.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Миксин для переопределения времени в ClientWorld.Properties.
 * Обеспечивает отображение виртуального времени для персональных миров.
 */
@Mixin(ClientWorld.Properties.class)
public class ClientWorldPropertiesMixin {

    /**
     * Переопределяем getTimeOfDay() для персональных миров.
     * Возвращает виртуальное время из ClientSlotTimeState.
     */
    @Inject(method = "getTimeOfDay", at = @At("HEAD"), cancellable = true)
    private void racePersonalTimeOfDay(CallbackInfoReturnable<Long> cir) {
        var mc = MinecraftClient.getInstance();
        if (mc == null || mc.world == null) return;
        
        var id = mc.world.getRegistryKey().getValue().toString();
        if (!id.startsWith("fabric_race:")) return;
        
        // Получаем виртуальное время из кэша клиента
        // НЕ вызываем mc.world.getTimeOfDay() - это создаёт рекурсию!
        long cur = race.client.time.ClientSlotTimeState.get(id, 1000L); // Утро по умолчанию
        cir.setReturnValue(cur);
    }
}
