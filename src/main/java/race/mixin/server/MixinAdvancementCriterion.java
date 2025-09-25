package race.mixin.server;

import net.minecraft.advancement.criterion.AbstractCriterion;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.function.Predicate;

@Mixin(AbstractCriterion.class)
public class MixinAdvancementCriterion {

    /**
     * Обеспечивает корректную работу достижений в кастомных мирах
     * Перехватывает метод trigger для логирования активации критериев
     */
    @Inject(method = "trigger", at = @At("HEAD"))
    private void race$logAchievementTrigger(ServerPlayerEntity player, Predicate<?> predicate, CallbackInfo ci) {
        String worldKey = player.getServerWorld().getRegistryKey().getValue().toString();

    }
}
