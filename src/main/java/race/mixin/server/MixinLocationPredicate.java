package race.mixin.server;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.predicate.entity.LocationPredicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocationPredicate.class)
public class MixinLocationPredicate {

    @Inject(method = "test", at = @At("HEAD"), cancellable = true)
    private void race$skipLocationChecks(ServerWorld world, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        String key = world.getRegistryKey().getValue().toString();
        if (key.startsWith("fabric_race:")) {
            // В персональных мирах пропускаем локационные проверки (биомы/структуры),
            // чтобы исключить синхронную загрузку чанков
            cir.setReturnValue(true);
        }
    }
}


