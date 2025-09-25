package race.mixin.server;

import net.minecraft.advancement.criterion.TickCriterion;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TickCriterion.class)
public class MixinTickCriterion {

    @Inject(method = "trigger", at = @At("HEAD"), cancellable = true)
    private void race$skipTickCriterion(ServerPlayerEntity player, CallbackInfo ci) {
        String key = player.getServerWorld().getRegistryKey().getValue().toString();
        if (key.startsWith("fabric_race:")) {
            // Пропускаем триггер, чтобы не вызывать вложенные LocationPredicate/StructureAccessor
            ci.cancel();
        }
    }
}


