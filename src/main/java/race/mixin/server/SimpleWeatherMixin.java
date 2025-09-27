package race.mixin.server;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.HashMap;
import java.util.Map;

/**
 * Умное управление погодой для персональных миров
 */
@Mixin(ServerWorld.class)
public class SimpleWeatherMixin {
    
    // Храним время начала дождя для каждого мира
    private static final Map<String, Long> rainStartTime = new HashMap<>();
    // Храним независимое время для каждого мира
    private static final Map<String, Long> worldTime = new HashMap<>();
    // Храним независимые генераторы случайных чисел для каждого мира
    private static final Map<String, Random> worldRandom = new HashMap<>();
    
    /**
     * Простое управление погодой в персональных мирах
     */
    @Inject(method = "tickTime", at = @At("HEAD"))
    private void race$simpleWeather(CallbackInfo ci) {
        ServerWorld world = (ServerWorld)(Object)this;
        String worldName = world.getRegistryKey().getValue().toString();
        
        if (worldName.startsWith("fabric_race:")) {
            // Используем независимое время для каждого мира
            long currentTime = worldTime.computeIfAbsent(worldName, k -> 0L);
            worldTime.put(worldName, currentTime + 1);
            
            // Создаем независимый генератор случайных чисел для каждого мира
            Random random = worldRandom.computeIfAbsent(worldName, k -> Random.create(worldName.hashCode()));
            
            // Очень простая логика - проверяем каждые 5 минут
            if (currentTime % 6000 == 0) { // Каждые 5 минут
                boolean shouldRain = random.nextFloat() < 0.1f; // 10% шанс
                
                if (shouldRain && !world.getLevelProperties().isRaining()) {
                    world.getLevelProperties().setRaining(true);
                    System.out.println("[Race] Rain started in world " + worldName);
                } else if (!shouldRain && world.getLevelProperties().isRaining()) {
                    world.getLevelProperties().setRaining(false);
                    System.out.println("[Race] Rain stopped in world " + worldName);
                }
            }
        }
    }
}
