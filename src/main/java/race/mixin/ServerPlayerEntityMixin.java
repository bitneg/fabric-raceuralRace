package race.mixin;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.TeleportTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import race.server.world.EnhancedWorldManager;

@Mixin(ServerPlayerEntity.class)
public class ServerPlayerEntityMixin {
    
    @Inject(method = "teleportTo", at = @At("HEAD"), cancellable = true)
    private void onTeleportTo(TeleportTarget target, CallbackInfoReturnable<net.minecraft.entity.Entity> cir) {
        ServerPlayerEntity player = (ServerPlayerEntity)(Object)this;
        ServerWorld targetWorld = target.world();
        
        // Перехватываем телепортацию в ванильный End для игроков в персональных мирах
        if (targetWorld.getRegistryKey().getValue().toString().equals("minecraft:the_end") && 
            player.getServerWorld().getRegistryKey().getValue().getNamespace().equals("fabric_race")) {
            try {
                // Получаем слот игрока из его текущего мира
                String currentWorldName = player.getServerWorld().getRegistryKey().getValue().toString();
                int playerSlot = extractSlotFromWorldName(currentWorldName);
                
                if (playerSlot > 0) {
                    // Переадресуем в кастомный End мир
                    long seed = EnhancedWorldManager.getSeedForPlayer(player.getUuid());
                    ServerWorld customEndWorld = EnhancedWorldManager.getOrCreateWorldForGroup(
                        player.getServer(), playerSlot, seed, net.minecraft.world.World.END
                    );
                    
                    // Создаем новый TeleportTarget для кастомного мира
                    TeleportTarget customTarget = new TeleportTarget(
                        customEndWorld,
                        target.pos(),
                        target.velocity(),
                        target.yaw(),
                        target.pitch(),
                        target.postDimensionTransition()
                    );
                    
                    // Выполняем телепортацию в кастомный мир
                    net.minecraft.entity.Entity result = player.teleportTo(customTarget);
                    cir.setReturnValue(result);
                    cir.cancel();
                    
                    System.out.println("[Race] Redirected vanilla End teleport to custom world for slot " + playerSlot);
                    return;
                }
            } catch (Throwable t) {
                System.err.println("[Race] Failed to redirect End teleport: " + t.getMessage());
            }
        }
    }
    
    private int extractSlotFromWorldName(String worldName) {
        if (worldName.startsWith("fabric_race:slot")) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("slot(\\d+)_");
            java.util.regex.Matcher matcher = pattern.matcher(worldName);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        return -1;
    }
}
