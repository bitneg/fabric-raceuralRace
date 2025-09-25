package race.server;

import net.minecraft.advancement.Advancement;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Управляет достижениями в кастомных мирах
 */
public final class AchievementManager {
    private static final Map<UUID, Map<String, Long>> playerAchievements = new HashMap<>();
    
    /**
     * Принудительно активирует достижение для игрока в кастомном мире
     */
    public static void grantAchievement(ServerPlayerEntity player, String advancementId) {
        try {
            MinecraftServer server = player.getServer();
            if (server == null) return;
            
            AdvancementEntry advancement = server.getAdvancementLoader().get(Identifier.tryParse(advancementId));
            if (advancement == null) {
                System.out.println("[Race] Advancement not found: " + advancementId);
                return;
            }
            
            // Получаем прогресс достижения
            AdvancementProgress progress = player.getAdvancementTracker().getProgress(advancement);
            if (progress.isDone()) {
                return;
            }
            
            // Принудительно завершаем достижение
            for (String criterion : progress.getUnobtainedCriteria()) {
                player.getAdvancementTracker().grantCriterion(advancement, criterion);
            }
            
            // Записываем время получения
            UUID playerUuid = player.getUuid();
            playerAchievements.computeIfAbsent(playerUuid, k -> new HashMap<>())
                .put(advancementId, Util.getMeasuringTimeMs());

            
        } catch (Exception e) {
            System.err.println("[Race] Error granting achievement " + advancementId + ": " + e.getMessage());
        }
    }
    
    /**
     * Проверяет и активирует достижения на основе действий игрока в кастомном мире
     */
    public static void checkAndGrantAchievements(ServerPlayerEntity player, String action, BlockPos pos) {
        String worldKey = player.getServerWorld().getRegistryKey().getValue().toString();
        if (!worldKey.startsWith("fabric_race:")) return;
        
        // Определяем достижения на основе действия
        switch (action.toLowerCase()) {
            case "enter_nether":
                grantAchievement(player, "minecraft:story/enter_the_nether");
                break;
            case "enter_end":
                grantAchievement(player, "minecraft:story/enter_the_end");
                break;
            case "kill_dragon":
                grantAchievement(player, "minecraft:end/kill_dragon");
                break;
            case "find_bastion":
                grantAchievement(player, "minecraft:nether/find_bastion");
                break;
            case "find_fortress":
                grantAchievement(player, "minecraft:nether/find_fortress");
                break;
            case "obtain_blaze_rod":
                grantAchievement(player, "minecraft:nether/obtain_blaze_rod");
                break;
            case "follow_ender_eye":
                grantAchievement(player, "minecraft:story/follow_ender_eye");
                break;
        }
    }
    
    /**
     * Получает время получения достижения
     */
    public static long getAchievementTime(UUID playerUuid, String advancementId) {
        Map<String, Long> achievements = playerAchievements.get(playerUuid);
        if (achievements == null) return -1;
        return achievements.getOrDefault(advancementId, -1L);
    }
    
    /**
     * Проверяет, получено ли достижение
     */
    public static boolean hasAchievement(UUID playerUuid, String advancementId) {
        Map<String, Long> achievements = playerAchievements.get(playerUuid);
        if (achievements == null) return false;
        return achievements.containsKey(advancementId);
    }
    
    /**
     * Очищает достижения игрока
     */
    public static void clearPlayerAchievements(UUID playerUuid) {
        playerAchievements.remove(playerUuid);
    }
    
    /**
     * Получает все достижения игрока
     */
    public static Map<String, Long> getPlayerAchievements(UUID playerUuid) {
        return new HashMap<>(playerAchievements.getOrDefault(playerUuid, new HashMap<>()));
    }
}
