package race.mixin.server;

import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Миксин для отключения проверки погружения в воду в персональных мирах
 * чтобы избежать блокировки на getFluidState
 */
@Mixin(Entity.class)
public class MixinServerPlayerEntity {
    
    @Inject(method = "updateSubmergedInWaterState", at = @At("HEAD"), cancellable = true)
    private void onUpdateSubmergedInWaterState(CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        
        // Проверяем, что это ServerPlayerEntity и он в персональном мире
        if (entity instanceof ServerPlayerEntity player) {
            World world = player.getWorld();
            
            // Отключаем проверку погружения в воду для персональных миров
            if (world.getRegistryKey().getValue().toString().startsWith("fabric_race:")) {
                // Просто отменяем выполнение метода, чтобы избежать вызовов getFluidState
                ci.cancel();
            }
        }
    }

	@Inject(method = "isInsideWall", at = @At("HEAD"), cancellable = true)
	private void race$skipIsInsideWall(CallbackInfoReturnable<Boolean> cir) {
		Entity entity = (Entity) (Object) this;
		if (entity instanceof ServerPlayerEntity player) {
			World world = player.getWorld();
			if (world.getRegistryKey().getValue().toString().startsWith("fabric_race:")) {
				// Избегаем блокирующих getBlockState во время проверки застревания в блоках
				cir.setReturnValue(false);
			}
		}
	}
}