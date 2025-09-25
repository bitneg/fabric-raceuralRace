package race.mixin.server;

import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public class WorldWeatherMixin {
    
    /**
     * В гоночных мирах нет дождя
     */
    @Inject(method = "isRaining", at = @At("HEAD"), cancellable = true)
    private void raceNoRain(CallbackInfoReturnable<Boolean> cir) {
        World world = (World)(Object)this;
        String worldName = world.getRegistryKey().getValue().toString();
        
        if (worldName.startsWith("fabric_race:")) {
            cir.setReturnValue(false);
        }
    }
    
    /**
     * В гоночных мирах нет грозы
     */
    @Inject(method = "isThundering", at = @At("HEAD"), cancellable = true)
    private void raceNoThunder(CallbackInfoReturnable<Boolean> cir) {
        World world = (World)(Object)this;
        String worldName = world.getRegistryKey().getValue().toString();
        
        if (worldName.startsWith("fabric_race:")) {
            cir.setReturnValue(false);
        }
    }
    
    /**
     * В гоночных мирах нет дождя (градиент)
     */
    @Inject(method = "getRainGradient", at = @At("HEAD"), cancellable = true)
    private void raceNoRainGradient(float delta, CallbackInfoReturnable<Float> cir) {
        World world = (World)(Object)this;
        String worldName = world.getRegistryKey().getValue().toString();
        
        if (worldName.startsWith("fabric_race:")) {
            cir.setReturnValue(0.0f);
        }
    }
    
    /**
     * В гоночных мирах нет грозы (градиент)
     */
    @Inject(method = "getThunderGradient", at = @At("HEAD"), cancellable = true)
    private void raceNoThunderGradient(float delta, CallbackInfoReturnable<Float> cir) {
        World world = (World)(Object)this;
        String worldName = world.getRegistryKey().getValue().toString();
        
        if (worldName.startsWith("fabric_race:")) {
            cir.setReturnValue(0.0f);
        }
    }
}
