package race.mixin.server;

import net.minecraft.advancement.AdvancementRewards;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AdvancementRewards.class)
public class MixinAdvancementRewards {

    /**
     * Отслеживаем когда игрок получает награды за достижения в кастомных мирах
     */
    @Inject(method = "apply", at = @At("HEAD"))
    private void race$onAdvancementRewards(ServerPlayerEntity player, CallbackInfo ci) {
        if (player.getServerWorld().getRegistryKey().getValue().toString().startsWith("fabric_race:")) {
            System.out.println("[Race] Advancement rewards applied to " + player.getName().getString() + 
                             " in world " + player.getServerWorld().getRegistryKey().getValue());
        }
    }
}
