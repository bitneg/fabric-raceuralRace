package race.ranking;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Система ранжирования и ELO для спидран гонок
 */
public final class RankingSystem {
    private static final Map<UUID, PlayerRank> playerRanks = new ConcurrentHashMap<>();
    private static final Map<UUID, PlayerStatistics> playerStats = new ConcurrentHashMap<>();
    
    // Константы ELO
    private static final int INITIAL_ELO = 1000;
    private static final int K_FACTOR = 32;
    private static final int MIN_ELO = 100;
    private static final int MAX_ELO = 3000;
    
    /**
     * Получает ранг игрока
     */
    public static PlayerRank getPlayerRank(UUID playerId) {
        return playerRanks.computeIfAbsent(playerId, k -> new PlayerRank(INITIAL_ELO, Rank.BRONZE));
    }
    
    /**
     * Получает статистику игрока
     */
    public static PlayerStatistics getPlayerStatistics(UUID playerId) {
        return playerStats.computeIfAbsent(playerId, k -> new PlayerStatistics());
    }
    
    /**
     * Обновляет ELO игрока после завершения гонки
     */
    public static void updateElo(UUID playerId, long raceTime, List<UUID> allPlayers, List<Long> allTimes) {
        PlayerRank rank = getPlayerRank(playerId);
        PlayerStatistics stats = getPlayerStatistics(playerId);
        
        // Вычисляем позицию игрока
        int position = calculatePosition(raceTime, allTimes);
        int totalPlayers = allPlayers.size();
        
        // Вычисляем ожидаемый результат
        double expectedScore = calculateExpectedScore(rank.getElo(), allPlayers, allTimes);
        
        // Вычисляем фактический результат (1.0 за первое место, 0.0 за последнее)
        double actualScore = 1.0 - (double) (position - 1) / (totalPlayers - 1);
        
        // Обновляем ELO
        int newElo = calculateNewElo(rank.getElo(), expectedScore, actualScore);
        rank.setElo(newElo);
        rank.setRank(Rank.fromElo(newElo));
        
        // Обновляем статистику
        stats.addRace(raceTime, position, totalPlayers);
        
        // Сохраняем изменения
        playerRanks.put(playerId, rank);
        playerStats.put(playerId, stats);
    }
    
    /**
     * Вычисляет позицию игрока
     */
    private static int calculatePosition(long playerTime, List<Long> allTimes) {
        int position = 1;
        for (Long time : allTimes) {
            if (time < playerTime) {
                position++;
            }
        }
        return position;
    }
    
    /**
     * Вычисляет ожидаемый результат на основе ELO
     */
    private static double calculateExpectedScore(int playerElo, List<UUID> allPlayers, List<Long> allTimes) {
        double expectedScore = 0.0;
        int playerIndex = 0;
        
        for (int i = 0; i < allPlayers.size(); i++) {
            if (i != playerIndex) {
                int opponentElo = getPlayerRank(allPlayers.get(i)).getElo();
                expectedScore += 1.0 / (1.0 + Math.pow(10.0, (opponentElo - playerElo) / 400.0));
            }
        }
        
        return expectedScore / (allPlayers.size() - 1);
    }
    
    /**
     * Вычисляет новый ELO
     */
    private static int calculateNewElo(int currentElo, double expectedScore, double actualScore) {
        int newElo = (int) (currentElo + K_FACTOR * (actualScore - expectedScore));
        return Math.max(MIN_ELO, Math.min(MAX_ELO, newElo));
    }
    
    /**
     * Получает топ игроков
     */
    public static List<PlayerRank> getTopPlayers(int count) {
        return playerRanks.values().stream()
                .sorted(Comparator.comparingInt(PlayerRank::getElo).reversed())
                .limit(count)
                .toList();
    }
    
    /**
     * Получает лидерборд
     */
    public static List<LeaderboardEntry> getLeaderboard(int count) {
        return playerRanks.entrySet().stream()
                .sorted(Map.Entry.<UUID, PlayerRank>comparingByValue(
                        Comparator.comparingInt(PlayerRank::getElo).reversed()))
                .limit(count)
                .map(entry -> new LeaderboardEntry(entry.getKey(), entry.getValue(), getPlayerStatistics(entry.getKey())))
                .toList();
    }
    
    /**
     * Сбрасывает рейтинг игрока
     */
    public static void resetPlayerRank(UUID playerId) {
        playerRanks.put(playerId, new PlayerRank(INITIAL_ELO, Rank.BRONZE));
        playerStats.put(playerId, new PlayerStatistics());
    }
    
    /**
     * Ранг игрока
     */
    public static class PlayerRank {
        private int elo;
        private Rank rank;
        
        public PlayerRank(int elo, Rank rank) {
            this.elo = elo;
            this.rank = rank;
        }
        
        public int getElo() { return elo; }
        public void setElo(int elo) { this.elo = elo; }
        
        public Rank getRank() { return rank; }
        public void setRank(Rank rank) { this.rank = rank; }
    }
    
    /**
     * Статистика игрока
     */
    public static class PlayerStatistics {
        private int totalRaces = 0;
        private int wins = 0;
        private int top3 = 0;
        private long bestTime = Long.MAX_VALUE;
        private long totalTime = 0;
        private int currentWinStreak = 0;
        private int longestWinStreak = 0;
        
        public void addRace(long time, int position, int totalPlayers) {
            totalRaces++;
            totalTime += time;
            
            if (position == 1) {
                wins++;
                currentWinStreak++;
                longestWinStreak = Math.max(longestWinStreak, currentWinStreak);
            } else {
                currentWinStreak = 0;
            }
            
            if (position <= 3) {
                top3++;
            }
            
            if (time < bestTime) {
                bestTime = time;
            }
        }
        
        // Геттеры
        public int getTotalRaces() { return totalRaces; }
        public int getWins() { return wins; }
        public int getTop3() { return top3; }
        public long getBestTime() { return bestTime; }
        public long getAverageTime() { return totalRaces > 0 ? totalTime / totalRaces : 0; }
        public int getCurrentWinStreak() { return currentWinStreak; }
        public int getLongestWinStreak() { return longestWinStreak; }
        public double getWinRate() { return totalRaces > 0 ? (double) wins / totalRaces : 0.0; }
    }
    
    /**
     * Запись лидерборда
     */
    public static class LeaderboardEntry {
        private final UUID playerId;
        private final PlayerRank rank;
        private final PlayerStatistics stats;
        
        public LeaderboardEntry(UUID playerId, PlayerRank rank, PlayerStatistics stats) {
            this.playerId = playerId;
            this.rank = rank;
            this.stats = stats;
        }
        
        public UUID getPlayerId() { return playerId; }
        public PlayerRank getRank() { return rank; }
        public PlayerStatistics getStats() { return stats; }
    }
}
