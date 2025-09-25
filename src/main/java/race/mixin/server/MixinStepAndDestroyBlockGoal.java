package race.mixin.server;

import net.minecraft.entity.ai.goal.StepAndDestroyBlockGoal;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StepAndDestroyBlockGoal.class)
public class MixinStepAndDestroyBlockGoal {

    @Inject(method = "isTargetPos", at = @At("HEAD"), cancellable = true)
    private void race$fastIsTargetPos(WorldView world, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (world instanceof ServerWorld serverWorld) {
            String key = serverWorld.getRegistryKey().getValue().toString();
            if (key.startsWith("fabric_race:")) {
                cir.setReturnValue(false);
            }
        }
    }
}


