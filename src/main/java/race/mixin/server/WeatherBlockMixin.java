package race.mixin.server;

import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Блокирует стандартную логику погоды для персональных миров
 */
@Mixin(ServerWorld.class)
public class WeatherBlockMixin {
    
    /**
     * Блокирует стандартное обновление погоды для персональных миров
     */
    @Inject(method = "tickWeather", at = @At("HEAD"), cancellable = true)
    private void race$blockWeatherForPersonalWorlds(CallbackInfo ci) {
        ServerWorld world = (ServerWorld)(Object)this;
        String worldName = world.getRegistryKey().getValue().toString();
        
        if (worldName.startsWith("fabric_race:")) {
            // Полностью блокируем стандартную логику погоды для персональных миров
            ci.cancel();
        }
    }
}
