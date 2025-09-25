package race.server.phase;

import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import race.net.PlayerProgressPayload;
import race.net.RaceBoardPayload;
import race.server.world.EnhancedWorldManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Менеджер фаз гонки - отслеживает прогресс игроков и управляет переходами между этапами
 */
public final class RacePhaseManager {
    private static final Map<UUID, PlayerRaceData> playerData = new HashMap<>();
    
    // Ключевые достижения для отслеживания
    private static final String[] SPEEDRUN_ADVANCEMENTS = {
        "minecraft:story/enter_the_nether",      // Enter Nether
        "minecraft:nether/find_bastion",         // Enter Bastion  
        "minecraft:nether/find_fortress",        // Enter Fortress
        "minecraft:story/enter_the_end",         // Enter End
        "minecraft:end/kill_dragon"              // Complete
    };
    
    // Названия этапов
    private static final Map<String, String> STAGE_NAMES = Map.of(
        "minecraft:story/enter_the_nether", "Nether",
        "minecraft:nether/find_bastion", "Bastion", 
        "minecraft:nether/find_fortress", "Fortress",
        "minecraft:story/enter_the_end", "End",
        "minecraft:end/kill_dragon", "Complete"
    );
    
    /**
     * Обновляет прогресс игрока
     */
    public static void updatePlayerProgress(ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        PlayerRaceData data = playerData.computeIfAbsent(playerId, k -> new PlayerRaceData());
        
        // Обновляем текущий этап
        String currentStage = getCurrentStage(player);
        data.setCurrentStage(currentStage);
        
        // Проверяем новые достижения
        for (String advancementId : SPEEDRUN_ADVANCEMENTS) {
            var advancement = player.getServer().getAdvancementLoader().get(Identifier.tryParse(advancementId));
            if (advancement != null) {
                AdvancementProgress progress = player.getAdvancementTracker().getProgress(advancement);

                // ХАК для персональных измерений: "enter_the_nether" и "enter_the_end" не срабатывают
                // из-за нестандартных ключей миров (fabric_race:slot...). Если игрок находится в мире
                // с типом THE_NETHER/THE_END — дожимаем критерии вручную.
                try {
                    if (advancementId.equals("minecraft:story/enter_the_nether")
                            && player.getServerWorld().getDimensionEntry().matchesKey(net.minecraft.world.dimension.DimensionTypes.THE_NETHER)
                            && !progress.isDone()) {
                        for (String crit : progress.getUnobtainedCriteria()) {
                            player.getAdvancementTracker().grantCriterion(advancement, crit);
                        }
                    }
                    if (advancementId.equals("minecraft:story/enter_the_end")
                            && player.getServerWorld().getDimensionEntry().matchesKey(net.minecraft.world.dimension.DimensionTypes.THE_END)
                            && !progress.isDone()) {
                        for (String crit : progress.getUnobtainedCriteria()) {
                            player.getAdvancementTracker().grantCriterion(advancement, crit);
                        }
                    }
                } catch (Throwable ignored) {}
                boolean isCompleted = progress.isDone();
                
                // Если достижение только что завершено
                if (isCompleted && !data.isMilestoneCompleted(advancementId)) {
                    long currentTime = System.currentTimeMillis() - PhaseState.getRaceStartTime();
                    data.setMilestoneTime(advancementId, currentTime);
                    data.setMilestoneCompleted(advancementId, true);
                    
                    // Выполняем действия при завершении этапа
                    onMilestoneCompleted(player, advancementId);
                }
            }
        }
        
        // Обновляем время RTA
        data.setRtaMs(System.currentTimeMillis() - PhaseState.getRaceStartTime());
    }
    
    /**
     * Обработка завершения этапа
     */
    private static void onMilestoneCompleted(ServerPlayerEntity player, String advancementId) {
        switch (advancementId) {
            case "minecraft:story/enter_the_nether" -> {
                // Игрок вошел в Нижний мир - можно создать персональный Nether
                createPersonalNether(player);
            }
            case "minecraft:story/enter_the_end" -> {
                // Игрок вошел в Энд - можно создать персональный Энд
                createPersonalEnd(player);
            }
            case "minecraft:end/kill_dragon" -> {
                // Игрок завершил гонку
                onRaceCompleted(player);
            }
        }
    }
    
    /**
     * Создает персональный Нижний мир для игрока
     */
    private static void createPersonalNether(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        long seed = PhaseState.getRaceSeed();
        // Определяем группу по исходному слоту Overworld
        int slot = EnhancedWorldManager.slotFromWorld(player.getServerWorld());
        if (slot <= 0) slot = EnhancedWorldManager.getOrAssignSlotForPlayer(player.getUuid());
        // Весь состав с этим слотом будет отправляться в один и тот же Nether slotX
        var netherWorld = EnhancedWorldManager.getOrCreateWorldForGroup(server, slot, seed, net.minecraft.world.World.NETHER);
        EnhancedWorldManager.teleportToWorld(player, netherWorld);
    }
    
    /**
     * Создает персональный Энд для игрока
     */
    private static void createPersonalEnd(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        long seed = PhaseState.getRaceSeed();
        int slot = EnhancedWorldManager.slotFromWorld(player.getServerWorld());
        if (slot <= 0) slot = EnhancedWorldManager.getOrAssignSlotForPlayer(player.getUuid());
        var endWorld = EnhancedWorldManager.getOrCreateWorldForGroup(server, slot, seed, net.minecraft.world.World.END);
        EnhancedWorldManager.teleportToWorld(player, endWorld);
    }
    
    /**
     * Обработка завершения гонки
     */
    private static void onRaceCompleted(ServerPlayerEntity player) {
        // Можно добавить логику для завершения гонки
        // Например, отправка уведомлений, сохранение результатов и т.д.
    }
    
    /**
     * Получает текущий этап игрока
     */
    private static String getCurrentStage(ServerPlayerEntity player) {
        // Проверяем достижения в обратном порядке
        for (int i = SPEEDRUN_ADVANCEMENTS.length - 1; i >= 0; i--) {
            String advancementId = SPEEDRUN_ADVANCEMENTS[i];
            var advancement = player.getServer().getAdvancementLoader().get(Identifier.tryParse(advancementId));
            if (advancement != null) {
                AdvancementProgress progress = player.getAdvancementTracker().getProgress(advancement);
                if (progress.isDone()) {
                    return STAGE_NAMES.get(advancementId);
                }
            }
        }
        return "Overworld";
    }
    
    /**
     * Получает данные игрока для отправки другим игрокам
     */
    public static List<RaceBoardPayload.Row> getRaceBoardData(MinecraftServer server) {
        return server.getPlayerManager().getPlayerList().stream()
                .map(player -> {
                    PlayerRaceData data = playerData.get(player.getUuid());
                    var prog = race.hub.ProgressSyncManager.getPlayerProgress(player.getUuid());
                    String activity = prog != null ? prog.getActivity() : "";
                    if (data == null) {
                        return new RaceBoardPayload.Row(
                                player.getGameProfile().getName(),
                                0L,
                                "Overworld",
                                activity,
                                player.getServerWorld().getRegistryKey().getValue().toString()
                        );
                    }

                    return new RaceBoardPayload.Row(
                            player.getGameProfile().getName(),
                            data.getRtaMs(),
                            data.getCurrentStage(),
                            activity,
                            player.getServerWorld().getRegistryKey().getValue().toString()
                    );
                })
                .toList();
    }
    
    /**
     * Получает детальный прогресс игрока
     */
    public static PlayerProgressPayload getPlayerProgress(ServerPlayerEntity player) {
        PlayerRaceData data = playerData.get(player.getUuid());
        if (data == null) {
            return new PlayerProgressPayload(
                player.getGameProfile().getName(),
                0L,
                "Overworld",
                Map.of(),
                getCurrentWorldName(player),
                ""
            );
        }
        
        return new PlayerProgressPayload(
            player.getGameProfile().getName(),
            data.getRtaMs(),
            data.getCurrentStage(),
            data.getMilestoneTimes(),
            getCurrentWorldName(player),
            (race.hub.ProgressSyncManager.getPlayerProgress(player.getUuid()) != null ?
                    race.hub.ProgressSyncManager.getPlayerProgress(player.getUuid()).getActivity() : "")
        );
    }
    
    /**
     * Получает название текущего мира
     */
    private static String getCurrentWorldName(ServerPlayerEntity player) {
        String worldKey = player.getServerWorld().getRegistryKey().getValue().toString();
        if (worldKey.contains("the_end")) return "End";
        if (worldKey.contains("nether")) return "Nether";
        return "Overworld";
    }
    
    /**
     * Сбрасывает данные гонки
     */
    public static void resetRace() {
        playerData.clear();
    }
    
    /**
     * Данные игрока в гонке
     */
    private static class PlayerRaceData {
        private String currentStage = "Overworld";
        private long rtaMs = 0L;
        private final Map<String, Long> milestoneTimes = new HashMap<>();
        private final Map<String, Boolean> milestoneCompleted = new HashMap<>();
        
        public String getCurrentStage() { return currentStage; }
        public void setCurrentStage(String stage) { this.currentStage = stage; }
        
        public long getRtaMs() { return rtaMs; }
        public void setRtaMs(long rtaMs) { this.rtaMs = rtaMs; }
        
        public Map<String, Long> getMilestoneTimes() { return new HashMap<>(milestoneTimes); }
        public void setMilestoneTime(String milestone, long time) { milestoneTimes.put(milestone, time); }
        
        public boolean isMilestoneCompleted(String milestone) { 
            return milestoneCompleted.getOrDefault(milestone, false); 
        }
        public void setMilestoneCompleted(String milestone, boolean completed) { 
            milestoneCompleted.put(milestone, completed); 
        }
    }
}
