package race.mixin.server;

import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = net.minecraft.advancement.criterion.Criterion.ConditionsContainer.class)
public class MixinCriterionConditionsContainer {

    /**
     * Отслеживаем когда критерий выполняется в кастомных мирах
     * Этот Mixin перехватывает выполнение критериев через ConditionsContainer
     */
    @Inject(method = "grant", at = @At("HEAD"))
    private void race$onCriterionGrant(PlayerAdvancementTracker tracker, CallbackInfo ci) {
        // Простое логирование без доступа к игроку
        System.out.println("[Race] Criterion granted in custom world (tracker: " + tracker.hashCode() + ")");
    }
}
