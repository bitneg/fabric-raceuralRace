package race.mixin.vanillafix;

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
 * Исправляет телепортацию игрока между измерениями в кастомных мирах
 */
@Mixin(ServerPlayerEntity.class)
public class MixinServerPlayerTeleport {
    
    @Inject(method = "teleport(Lnet/minecraft/server/world/ServerWorld;DDDFF)V", 
            at = @At("HEAD"), cancellable = true)
    private void onTeleport(ServerWorld targetWorld, double x, double y, double z, 
                           float yaw, float pitch, CallbackInfo ci) {
        
        if (PhaseState.isRaceActive()) {
            ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
            ServerWorld currentWorld = player.getServerWorld();
            
            // Проверяем телепортацию из кастомного End в ванильный Overworld
            if (WorldManager.isEndDimension(currentWorld) && 
                targetWorld.getRegistryKey() == World.OVERWORLD) {
                
                String currentWorldName = currentWorld.getRegistryKey().getValue().getPath();
                
                // Если текущий мир - кастомный End игрока
                if (currentWorldName.contains("end") && !currentWorldName.equals("the_end")) {
                    
                    // Получаем персональный Overworld игрока
                    ServerWorld playerOverworld = WorldManager.getPlayerWorld(player.getUuid());
                    
                    if (playerOverworld != null && playerOverworld != targetWorld) {
                        // Перенаправляем телепортацию в персональный Overworld
                        BlockPos spawnPos = playerOverworld.getSpawnPos();
                        player.teleport(playerOverworld, 
                            spawnPos.getX() + 0.5, spawnPos.getY() + 1, spawnPos.getZ() + 0.5, 0, 0);
                        
                        ci.cancel(); // Отменяем оригинальную телепортацию
                        
                        System.out.println("✓ Redirected teleportation from custom End to player's Overworld");
                        return;
                    }
                }
            }
        }
    }
}
