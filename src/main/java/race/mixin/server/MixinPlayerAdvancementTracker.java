package race.mixin.server;

import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerAdvancementTracker.class)
public class MixinPlayerAdvancementTracker {
    
    @Shadow private ServerPlayerEntity owner;

    /**
     * Логирование всех полученных достижений в кастомных мирах
     */
    @Inject(method = "grantCriterion", at = @At("HEAD"))
    private void race$logAdvancementProgress(AdvancementEntry advancement, String criterionName, CallbackInfoReturnable<Boolean> cir) {
        if (owner != null && owner.getServerWorld().getRegistryKey().getValue().toString().startsWith("fabric_race:")) {
            System.out.println("[Race] Advancement progress: " + owner.getName().getString() + 
                             " completed '" + criterionName + "' for advancement '" + 
                             advancement.id() + "' in world " + owner.getServerWorld().getRegistryKey().getValue());
        }
    }
    
    /**
     * Отслеживаем когда игрок получает достижение полностью
     */
    @Inject(method = "onStatusUpdate", at = @At("HEAD"))
    private void race$onAdvancementComplete(AdvancementEntry advancement, CallbackInfo ci) {
        if (owner != null && owner.getServerWorld().getRegistryKey().getValue().toString().startsWith("fabric_race:")) {
            System.out.println("[Race] Achievement status update for " + owner.getName().getString() + 
                             ": " + advancement.id() + " in world " + owner.getServerWorld().getRegistryKey().getValue());
        }
    }
    
    /**
     * Отслеживаем начало отслеживания достижений
     */
    @Inject(method = "beginTracking", at = @At("HEAD"))
    private void race$onBeginTracking(AdvancementEntry advancement, CallbackInfo ci) {
        if (owner != null && owner.getServerWorld().getRegistryKey().getValue().toString().startsWith("fabric_race:")) {
            System.out.println("[Race] Begin tracking advancement: " + advancement.id() + 
                             " for " + owner.getName().getString());
        }
    }
    
    /**
     * Отслеживаем завершение отслеживания достижений
     */
    @Inject(method = "endTrackingCompleted", at = @At("HEAD"))
    private void race$onEndTrackingCompleted(AdvancementEntry advancement, CallbackInfo ci) {
        if (owner != null && owner.getServerWorld().getRegistryKey().getValue().toString().startsWith("fabric_race:")) {
            System.out.println("[Race] End tracking completed advancement: " + advancement.id() + 
                             " for " + owner.getName().getString());
        }
    }
}
