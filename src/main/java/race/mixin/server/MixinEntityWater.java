package race.mixin.server;

import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class MixinEntityWater {

    @Inject(method = "updateWaterState", at = @At("HEAD"), cancellable = true)
    private void race$skipUpdateWaterState(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (!(self.getWorld() instanceof ServerWorld sw)) return;
        String key = sw.getRegistryKey().getValue().toString();
        if (key.startsWith("fabric_race:")) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "checkWaterState", at = @At("HEAD"), cancellable = true)
    private void race$skipCheckWaterState(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self.getWorld() instanceof ServerWorld sw)) return;
        String key = sw.getRegistryKey().getValue().toString();
        if (key.startsWith("fabric_race:")) {
            ci.cancel();
        }
    }
}


