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
        
        if (entity instanceof ServerPlayerEntity player) {
            if (world instanceof ServerWorld serverWorld) {
                // ИСПРАВЛЕНИЕ: Проверяем, что игрок в персональном мире (любом)
                String worldName = serverWorld.getRegistryKey().getValue().toString();
                if (worldName.startsWith("fabric_race:")) {
                    // Игрок в персональном мире - перенаправляем в персональный энд
                    teleportToPlayerEnd(player, serverWorld);
                    
                    // Отменяем ванильную телепортацию
                    ci.cancel();
                    
                    System.out.println("✓ Redirected End portal teleportation from personal world to personal End");
                }
            }
        }
    }
    
    /**
     * Телепортирует игрока в его персональный End
     */
    private void teleportToPlayerEnd(ServerPlayerEntity player, ServerWorld currentWorld) {
        try {
            // Извлекаем слот и сид из текущего мира
            String worldName = currentWorld.getRegistryKey().getValue().toString();
            int slot = extractSlotFromWorldName(worldName);
            long seed = extractSeedFromWorldName(worldName);
            
            if (slot > 0 && seed != -1) {
                // Создаем персональный End мир
                ServerWorld personalEndWorld = race.server.world.EnhancedWorldManager.getOrCreateWorldForGroup(
                    player.getServer(), slot, seed, net.minecraft.world.World.END);
                
                if (personalEndWorld != null) {
                    // Телепортируем в персональный End
                    BlockPos spawnPos = personalEndWorld.getSpawnPos();
                    player.teleport(personalEndWorld, 
                        spawnPos.getX() + 0.5, spawnPos.getY() + 1, spawnPos.getZ() + 0.5, 0, 0);
                    
                    player.sendMessage(
                        net.minecraft.text.Text.literal("Телепортация в ваш персональный End!")
                            .formatted(net.minecraft.util.Formatting.GREEN), false);
                            
                    System.out.println("✓ Player teleported to personal End: " + 
                                     personalEndWorld.getRegistryKey().getValue());
                } else {
                    // Fallback: телепортируем в хаб
                    race.hub.HubManager.teleportToHub(player);
                    player.sendMessage(
                        net.minecraft.text.Text.literal("Персональный End не найден. Возвращаемся в хаб!")
                            .formatted(net.minecraft.util.Formatting.YELLOW), false);
                }
            } else {
                // Fallback: телепортируем в хаб
                race.hub.HubManager.teleportToHub(player);
                player.sendMessage(
                    net.minecraft.text.Text.literal("Не удалось определить слот/сид. Возвращаемся в хаб!")
                        .formatted(net.minecraft.util.Formatting.YELLOW), false);
            }
            
        } catch (Exception e) {
            System.err.println("Failed to teleport player to personal End: " + e.getMessage());
            
            // Emergency fallback: телепортируем в хаб
            race.hub.HubManager.teleportToHub(player);
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
    
    private long extractSeedFromWorldName(String worldName) {
        if (worldName.contains("_s")) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("_s(\\d+)");
            java.util.regex.Matcher matcher = pattern.matcher(worldName);
            if (matcher.find()) {
                return Long.parseLong(matcher.group(1));
            }
        }
        return -1;
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
