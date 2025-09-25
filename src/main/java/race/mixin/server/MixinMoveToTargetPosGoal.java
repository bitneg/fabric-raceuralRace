package race.mixin.server;

import net.minecraft.entity.ai.goal.MoveToTargetPosGoal;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MoveToTargetPosGoal.class)
public abstract class MixinMoveToTargetPosGoal {

    @Shadow protected PathAwareEntity mob;

    @Inject(method = "canStart", at = @At("HEAD"), cancellable = true)
    private void race$skipMoveToTargetInRaceWorlds(CallbackInfoReturnable<Boolean> cir) {
        if (!(this.mob.getWorld() instanceof ServerWorld serverWorld)) return;
        String key = serverWorld.getRegistryKey().getValue().toString();
        if (key.startsWith("fabric_race:")) {
            cir.setReturnValue(false);
        }
    }
}


