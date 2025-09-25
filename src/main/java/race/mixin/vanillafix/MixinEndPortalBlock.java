package race.mixin.vanillafix;

import net.minecraft.block.EndPortalBlock;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import race.hub.WorldManager;
import race.server.phase.PhaseState;

/**
 * Исправляет телепортацию через End портал в кастомных мирах
 */
@Mixin(EndPortalBlock.class)
public class MixinEndPortalBlock {
    
    @Inject(method = "onEntityCollision", at = @At("HEAD"), cancellable = true)
    private void onEntityCollision(net.minecraft.block.BlockState state, World world, 
                                  BlockPos pos, Entity entity, CallbackInfo ci) {
        
        if (PhaseState.isRaceActive() && entity instanceof ServerPlayerEntity player) {
            if (world instanceof ServerWorld serverWorld) {
                // Проверяем, что игрок в кастомном End мире
                if (WorldManager.isEndDimension(serverWorld)) {
                    String worldName = serverWorld.getRegistryKey().getValue().getPath();
                    
                    // Если это кастомный End мир игрока
                    if (worldName.contains("end") && !worldName.equals("the_end")) {
                        // Телепортируем в персональный Overworld игрока
                        teleportToPlayerOverworld(player);
                        
                        // Отменяем ванильную телепортацию
                        ci.cancel();
                        
                        System.out.println("✓ Redirected End portal teleportation to player's Overworld");
                    }
                }
            }
        }
    }
    
    /**
     * Телепортирует игрока в его персональный Overworld
     */
    private void teleportToPlayerOverworld(ServerPlayerEntity player) {
        try {
            // Получаем персональный Overworld игрока
            ServerWorld playerOverworld = WorldManager.getPlayerWorld(player.getUuid());
            
            if (playerOverworld != null) {
                // Телепортируем на спавн в персональном мире
                BlockPos spawnPos = playerOverworld.getSpawnPos();
                player.teleport(playerOverworld, 
                    spawnPos.getX() + 0.5, spawnPos.getY() + 1, spawnPos.getZ() + 0.5, 0, 0);
                
                player.sendMessage(
                    net.minecraft.text.Text.literal("Возвращаемся в ваш персональный мир!")
                        .formatted(net.minecraft.util.Formatting.GREEN), false);
                        
                System.out.println("✓ Player teleported to personal Overworld: " + 
                                 playerOverworld.getRegistryKey().getValue());
            } else {
                // Fallback: телепортируем в хаб
                race.hub.HubManager.teleportToHub(player);
                
                player.sendMessage(
                    net.minecraft.text.Text.literal("Персональный мир не найден. Возвращаемся в хаб!")
                        .formatted(net.minecraft.util.Formatting.YELLOW), false);
            }
            
        } catch (Exception e) {
            System.err.println("Failed to teleport player from End portal: " + e.getMessage());
            
            // Emergency fallback: телепортируем в хаб
            race.hub.HubManager.teleportToHub(player);
        }
    }
}
