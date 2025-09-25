package race.hub;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import race.net.PlayerProgressPayload;
import race.server.phase.PhaseState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Менеджер синхронизации прогресса между игроками
 */
public class ProgressSyncManager {
    private static final Map<UUID, PlayerProgressData> playerProgress = new ConcurrentHashMap<>();
    private static final Map<Long, java.util.Set<UUID>> seedGroups = new ConcurrentHashMap<>();
    
    /**
     * Данные прогресса игрока
     */
    public static class PlayerProgressData {
        private final UUID playerId;
        private final String playerName;
        private final long seed;
        private final long startTime;
        private long currentTime;
        private String currentStage;
        private Map<String, Long> advancementTimes;
        private boolean isFinished;
        private long finishTime;
        private String activity = "";
        
        public PlayerProgressData(UUID playerId, String playerName, long seed) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.seed = seed;
            this.startTime = System.currentTimeMillis();
            this.currentTime = startTime;
            this.currentStage = "Начало";
            this.advancementTimes = new HashMap<>();
            this.isFinished = false;
            this.finishTime = -1;
        }
        
        // Getters and setters
        public UUID getPlayerId() { return playerId; }
        public String getPlayerName() { return playerName; }
        public long getSeed() { return seed; }
        public long getStartTime() { return startTime; }
        public long getCurrentTime() { return currentTime; }
        public String getCurrentStage() { return currentStage; }
        public Map<String, Long> getAdvancementTimes() { return advancementTimes; }
        public boolean isFinished() { return isFinished; }
        public long getFinishTime() { return finishTime; }
        public String getActivity() { return activity; }
        
        public void setCurrentTime(long currentTime) { this.currentTime = currentTime; }
        public void setCurrentStage(String currentStage) { this.currentStage = currentStage; }
        public void setFinished(boolean finished) { this.isFinished = finished; }
        public void setFinishTime(long finishTime) { this.finishTime = finishTime; }
        public void setActivity(String activity) { this.activity = activity; }
        
        public void addAdvancement(String advancement, long time) {
            advancementTimes.put(advancement, time);
        }
        
        public long getRaceTime() {
            if (isFinished) {
                return finishTime - startTime;
            }
            return currentTime - startTime;
        }
    }
    
    /**
     * Регистрирует игрока в системе синхронизации
     */
    public static void registerPlayer(ServerPlayerEntity player, long seed) {
        UUID playerId = player.getUuid();
        String playerName = player.getName().getString();
        
        // Создаем данные прогресса
        PlayerProgressData progressData = new PlayerProgressData(playerId, playerName, seed);
        playerProgress.put(playerId, progressData);
        
        // Добавляем в группу по сиду
        seedGroups.computeIfAbsent(seed, k -> ConcurrentHashMap.newKeySet()).add(playerId);
        
        // Уведомляем других игроков в группе
        notifyGroupOfNewPlayer(player, seed);
    }
    
    /**
     * Уведомляет группу о новом игроке
     */
    private static void notifyGroupOfNewPlayer(ServerPlayerEntity newPlayer, long seed) {
        java.util.Set<UUID> groupPlayers = seedGroups.get(seed);
        if (groupPlayers != null) {
            for (UUID playerId : groupPlayers) {
                if (!playerId.equals(newPlayer.getUuid())) {
                    ServerPlayerEntity player = newPlayer.getServer().getPlayerManager().getPlayer(playerId);
                    if (player != null) {
                        player.sendMessage(Text.literal("Игрок " + newPlayer.getName().getString() + 
                            " присоединился к гонке!").formatted(Formatting.GREEN), false);
                    }
                }
            }
        }
    }
    
    /**
     * Обновляет прогресс игрока
     */
    public static void updatePlayerProgress(UUID playerId, String stage, Map<String, Long> advancements) {
        PlayerProgressData progressData = playerProgress.get(playerId);
        if (progressData != null) {
            progressData.setCurrentTime(System.currentTimeMillis());
            progressData.setCurrentStage(stage);
            
            // Обновляем достижения
            for (Map.Entry<String, Long> entry : advancements.entrySet()) {
                progressData.addAdvancement(entry.getKey(), entry.getValue());
            }
            
            // Уведомляем других игроков в группе
            notifyGroupOfProgress(playerId, progressData);
        }
    }
    
    /**
     * Уведомляет группу о прогрессе игрока
     */
    private static void notifyGroupOfProgress(UUID playerId, PlayerProgressData progressData) {
        java.util.Set<UUID> groupPlayers = seedGroups.get(progressData.getSeed());
        if (groupPlayers != null) {
            for (UUID otherPlayerId : groupPlayers) {
                if (!otherPlayerId.equals(playerId)) {
                    ServerPlayerEntity player = getPlayerById(otherPlayerId);
                    if (player != null) {
                        // Отправляем обновление прогресса
                        sendProgressUpdate(player, progressData);
                    }
                }
            }
        }
    }
    
    /**
     * Отправляет обновление прогресса игроку
     */
    private static void sendProgressUpdate(ServerPlayerEntity player, PlayerProgressData progressData) {
        // Создаем payload с данными прогресса
        PlayerProgressPayload payload = new PlayerProgressPayload(
            progressData.getPlayerName(),
            progressData.getRaceTime(),
            progressData.getCurrentStage(),
            progressData.getAdvancementTimes(),
			player.getWorld().getRegistryKey().getValue().toString(),
			progressData.getActivity()
        );
        
        // Отправляем через сеть (это будет реализовано в сетевом слое)
        // player.networkHandler.sendPacket(payload);
    }
    
    /**
     * Отмечает игрока как завершившего гонку
     */
    public static void markPlayerFinished(UUID playerId) {
        PlayerProgressData progressData = playerProgress.get(playerId);
        if (progressData != null) {
            long finishTime = System.currentTimeMillis();
            progressData.setFinished(true);
            progressData.setFinishTime(finishTime);
            
            // Уведомляем группу о завершении
            notifyGroupOfFinish(playerId, progressData);
        }
    }
    
    /**
     * Уведомляет группу о завершении гонки игроком
     */
    private static void notifyGroupOfFinish(UUID playerId, PlayerProgressData progressData) {
        java.util.Set<UUID> groupPlayers = seedGroups.get(progressData.getSeed());
        if (groupPlayers != null) {
            for (UUID otherPlayerId : groupPlayers) {
                if (!otherPlayerId.equals(playerId)) {
                    ServerPlayerEntity player = getPlayerById(otherPlayerId);
                    if (player != null) {
                        long raceTime = progressData.getRaceTime();
                        String timeStr = formatTime(raceTime);
                        
                        player.sendMessage(Text.literal("🏁 " + progressData.getPlayerName() + 
                            " завершил гонку за " + timeStr + "!").formatted(Formatting.GOLD), true);
                    }
                }
            }
        }
    }
    
    /**
     * Получает данные прогресса игрока
     */
    public static PlayerProgressData getPlayerProgress(UUID playerId) {
        return playerProgress.get(playerId);
    }
    
    /**
     * Получает всех игроков в группе по сиду
     */
    public static java.util.Set<UUID> getGroupPlayers(long seed) {
        return seedGroups.getOrDefault(seed, ConcurrentHashMap.newKeySet());
    }
    
    /**
     * Получает лидерборд для группы
     */
    public static java.util.List<PlayerProgressData> getGroupLeaderboard(long seed) {
        java.util.Set<UUID> groupPlayers = seedGroups.get(seed);
        if (groupPlayers == null) {
            return new java.util.ArrayList<>();
        }
        
        return groupPlayers.stream()
            .map(playerProgress::get)
            .filter(data -> data != null)
            .sorted((a, b) -> {
                if (a.isFinished() && b.isFinished()) {
                    return Long.compare(a.getRaceTime(), b.getRaceTime());
                } else if (a.isFinished()) {
                    return -1; // Завершившие гонку идут первыми
                } else if (b.isFinished()) {
                    return 1;
                } else {
                    // Сортируем по текущему времени
                    return Long.compare(a.getRaceTime(), b.getRaceTime());
                }
            })
            .collect(java.util.stream.Collectors.toList());
    }
    
    /**
     * Удаляет игрока из системы
     */
    public static void removePlayer(UUID playerId) {
        PlayerProgressData progressData = playerProgress.remove(playerId);
        if (progressData != null) {
            java.util.Set<UUID> groupPlayers = seedGroups.get(progressData.getSeed());
            if (groupPlayers != null) {
                groupPlayers.remove(playerId);
                if (groupPlayers.isEmpty()) {
                    seedGroups.remove(progressData.getSeed());
                }
            }
        }
    }
    
    /**
     * Получает игрока по ID
     */
    private static ServerPlayerEntity getPlayerById(UUID playerId) {
        // Это нужно будет реализовать через сервер
        // Пока возвращаем null
        return null;
    }
    
    /**
     * Форматирует время в читаемый вид
     */
    private static String formatTime(long milliseconds) {
        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        long hours = minutes / 60;
        minutes = minutes % 60;
        
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%d:%02d", minutes, seconds);
        }
    }
    
    /**
     * Получает статистику группы
     */
    public static GroupStatistics getGroupStatistics(long seed) {
        java.util.Set<UUID> groupPlayers = seedGroups.get(seed);
        if (groupPlayers == null) {
            return new GroupStatistics(0, 0, 0, 0);
        }
        
        int totalPlayers = groupPlayers.size();
        int finishedPlayers = 0;
        long totalTime = 0;
        long bestTime = Long.MAX_VALUE;
        
        for (UUID playerId : groupPlayers) {
            PlayerProgressData data = playerProgress.get(playerId);
            if (data != null) {
                if (data.isFinished()) {
                    finishedPlayers++;
                    long raceTime = data.getRaceTime();
                    totalTime += raceTime;
                    if (raceTime < bestTime) {
                        bestTime = raceTime;
                    }
                }
            }
        }
        
        return new GroupStatistics(totalPlayers, finishedPlayers, totalTime, bestTime);
    }
    
    /**
     * Статистика группы
     */
    public static class GroupStatistics {
        private final int totalPlayers;
        private final int finishedPlayers;
        private final long totalTime;
        private final long bestTime;
        
        public GroupStatistics(int totalPlayers, int finishedPlayers, long totalTime, long bestTime) {
            this.totalPlayers = totalPlayers;
            this.finishedPlayers = finishedPlayers;
            this.totalTime = totalTime;
            this.bestTime = bestTime;
        }
        
        public int getTotalPlayers() { return totalPlayers; }
        public int getFinishedPlayers() { return finishedPlayers; }
        public long getTotalTime() { return totalTime; }
        public long getBestTime() { return bestTime == Long.MAX_VALUE ? -1 : bestTime; }
    }
}
