package race.mixin.server;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Инициализирует случайную погоду для персональных миров
 */
@Mixin(ServerWorld.class)
public class WorldWeatherIsolationMixin {
    
    /**
     * Инициализирует случайную погоду для персональных миров
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void race$initRandomWeather(CallbackInfo ci) {
        ServerWorld world = (ServerWorld)(Object)this;
        String worldName = world.getRegistryKey().getValue().toString();
        
        if (worldName.startsWith("fabric_race:")) {
            // Устанавливаем случайную погоду для персонального мира
            Random random = world.getRandom();
            boolean isRaining = random.nextBoolean();
            
            world.getLevelProperties().setRaining(isRaining);
            
            System.out.println("[Race] Set random weather for world " + worldName + ": raining=" + isRaining);
        }
    }
}
