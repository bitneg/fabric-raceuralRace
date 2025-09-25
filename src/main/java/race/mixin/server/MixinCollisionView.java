package race.mixin.server;

import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.world.CollisionView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CollisionView.class)
public interface MixinCollisionView {

    @Inject(method = "isSpaceEmpty(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/Box;)Z", at = @At("HEAD"), cancellable = true)
    private void race$spaceAlwaysFree(Entity entity, Box box, CallbackInfoReturnable<Boolean> cir) {
        CollisionView self = (CollisionView) this;
        if (!(self instanceof ServerWorld world)) return;
        String key = world.getRegistryKey().getValue().toString();
        if (key.startsWith("fabric_race:")) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isSpaceEmpty(Lnet/minecraft/util/math/Box;)Z", at = @At("HEAD"), cancellable = true)
    private void race$spaceAlwaysFreeBox(Box box, CallbackInfoReturnable<Boolean> cir) {
        CollisionView self = (CollisionView) this;
        if (!(self instanceof ServerWorld world)) return;
        String key = world.getRegistryKey().getValue().toString();
        if (key.startsWith("fabric_race:")) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isSpaceEmpty(Lnet/minecraft/entity/Entity;)Z", at = @At("HEAD"), cancellable = true)
    private void race$spaceAlwaysFreeEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        CollisionView self = (CollisionView) this;
        if (!(self instanceof ServerWorld world)) return;
        String key = world.getRegistryKey().getValue().toString();
        if (key.startsWith("fabric_race:")) {
            cir.setReturnValue(true);
        }
    }
}


