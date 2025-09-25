package race.client;

import net.minecraft.advancement.Advancement;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientAdvancementManager;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;

/**
 * Отслеживает прогресс игрока в спидранне
 */
public final class RaceProgressTracker {
    private static final Map<String, Long> milestoneTimes = new HashMap<>();
    private static final Map<String, Boolean> milestones = new HashMap<>();
    
    // Ключевые достижения для спидранна (упорядочены по прогрессу)
    private static final String[] SPEEDRUN_ADVANCEMENTS = {
        "minecraft:story/enter_the_nether",      // Enter Nether
        "minecraft:nether/find_bastion",         // Find Bastion
        "minecraft:nether/find_fortress",        // Find Fortress
        "minecraft:nether/obtain_blaze_rod",     // Into Fire (Blaze Rod) — если нет, будет проигнорировано
        "minecraft:story/follow_ender_eye",      // Eye Spy (Stronghold)
        "minecraft:story/enter_the_end",         // Enter End
        "minecraft:end/kill_dragon"              // Free the End (Complete)
    };
    
    // Названия этапов для отображения
    private static final Map<String, String> STAGE_NAMES = Map.of(
        "minecraft:story/enter_the_nether", "Nether",
        "minecraft:nether/find_bastion", "Bastion",
        "minecraft:nether/find_fortress", "Fortress",
        "minecraft:nether/obtain_blaze_rod", "Blaze Rod",
        "minecraft:story/follow_ender_eye", "Stronghold",
        "minecraft:story/enter_the_end", "End",
        "minecraft:end/kill_dragon", "Complete"
    );
    
    public static void updateProgress() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getNetworkHandler() == null) return;
        
        ClientAdvancementManager manager = client.getNetworkHandler().getAdvancementHandler();
        long currentTime = RaceClientEvents.getRtaMs();
        
        for (String advancementId : SPEEDRUN_ADVANCEMENTS) {
            AdvancementEntry advancement = manager.get(Identifier.tryParse(advancementId));
            if (advancement != null) {
                // Получаем прогресс из внутренней карты через рефлексию
                AdvancementProgress progress = getAdvancementProgress(manager, advancement);
                if (progress != null) {
                    boolean isCompleted = progress.isDone();
                    
                    // Если достижение только что завершено, записываем время
                    if (isCompleted && !milestones.getOrDefault(advancementId, false)) {
                        milestoneTimes.put(advancementId, currentTime);
                        milestones.put(advancementId, true);
                        System.out.println("[Race] Milestone completed: " + advancementId + " at " + currentTime + "ms");
                    }
                }
            }
        }
    }
    
    public static String getCurrentStage() {
        // Возвращаем последний завершенный этап
        for (int i = SPEEDRUN_ADVANCEMENTS.length - 1; i >= 0; i--) {
            String advancement = SPEEDRUN_ADVANCEMENTS[i];
            if (milestones.getOrDefault(advancement, false)) {
                return STAGE_NAMES.get(advancement);
            }
        }
        return "Overworld";
    }
    
    public static long getStageTime(String stageId) {
        return milestoneTimes.getOrDefault(stageId, -1L);
    }
    
    public static boolean isStageCompleted(String stageId) {
        // Используем кэшированные данные из milestones
        return milestones.getOrDefault(stageId, false);
    }
    
    public static void reset() {
        milestoneTimes.clear();
        milestones.clear();
    }
    
    public static Map<String, Long> getAllMilestoneTimes() {
        return new HashMap<>(milestoneTimes);
    }
    
    public static String getStageDisplayName(String stageId) {
        return STAGE_NAMES.getOrDefault(stageId, stageId);
    }
    
    /**
     * Получает прогресс достижения через рефлексию
     */
    private static AdvancementProgress getAdvancementProgress(ClientAdvancementManager manager, AdvancementEntry advancement) {
        try {
            // Получаем поле advancementProgresses через рефлексию
            var field = ClientAdvancementManager.class.getDeclaredField("advancementProgresses");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<AdvancementEntry, AdvancementProgress> progressMap = (Map<AdvancementEntry, AdvancementProgress>) field.get(manager);
            return progressMap.get(advancement);
        } catch (Exception e) {
            // Если рефлексия не работает, возвращаем null
            return null;
        }
    }
}
