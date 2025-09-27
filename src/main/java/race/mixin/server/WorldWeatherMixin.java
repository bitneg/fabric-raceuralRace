package race.mixin.server;

import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public class WorldWeatherMixin {
    
    /**
     * ИСПРАВЛЕНИЕ: Возвращаем погоду в гоночные миры
     */
    @Inject(method = "isRaining", at = @At("HEAD"), cancellable = true)
    private void raceNoRain(CallbackInfoReturnable<Boolean> cir) {
        // Убираем блокировку погоды - пусть работает как обычно
        // World world = (World)(Object)this;
        // String worldName = world.getRegistryKey().getValue().toString();
        // if (worldName.startsWith("fabric_race:")) {
        //     cir.setReturnValue(false);
        // }
    }
    
    /**
     * ИСПРАВЛЕНИЕ: Возвращаем грозу в гоночные миры
     */
    @Inject(method = "isThundering", at = @At("HEAD"), cancellable = true)
    private void raceNoThunder(CallbackInfoReturnable<Boolean> cir) {
        // Убираем блокировку грозы - пусть работает как обычно
        // World world = (World)(Object)this;
        // String worldName = world.getRegistryKey().getValue().toString();
        // if (worldName.startsWith("fabric_race:")) {
        //     cir.setReturnValue(false);
        // }
    }
    
    /**
     * ИСПРАВЛЕНИЕ: Возвращаем градиент дождя в гоночные миры
     */
    @Inject(method = "getRainGradient", at = @At("HEAD"), cancellable = true)
    private void raceNoRainGradient(float delta, CallbackInfoReturnable<Float> cir) {
        // Убираем блокировку градиента дождя - пусть работает как обычно
        // World world = (World)(Object)this;
        // String worldName = world.getRegistryKey().getValue().toString();
        // if (worldName.startsWith("fabric_race:")) {
        //     cir.setReturnValue(0.0f);
        // }
    }
    
    /**
     * ИСПРАВЛЕНИЕ: Возвращаем градиент грозы в гоночные миры
     */
    @Inject(method = "getThunderGradient", at = @At("HEAD"), cancellable = true)
    private void raceNoThunderGradient(float delta, CallbackInfoReturnable<Float> cir) {
        // Убираем блокировку градиента грозы - пусть работает как обычно
        // World world = (World)(Object)this;
        // String worldName = world.getRegistryKey().getValue().toString();
        // if (worldName.startsWith("fabric_race:")) {
        //     cir.setReturnValue(0.0f);
        // }
    }
}
