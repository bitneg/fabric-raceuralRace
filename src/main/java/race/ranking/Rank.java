package race.ranking;

/**
 * Ранги игроков в системе ранжирования
 */
public enum Rank {
    BRONZE(0, 400, "Бронза", 0xFFCD7F32),
    SILVER(400, 800, "Серебро", 0xFFC0C0C0),
    GOLD(800, 1200, "Золото", 0xFFFFD700),
    PLATINUM(1200, 1600, "Платина", 0xFFE5E4E2),
    DIAMOND(1600, 2000, "Алмаз", 0xFFB9F2FF),
    MASTER(2000, 2400, "Мастер", 0xFF8A2BE2),
    GRANDMASTER(2400, 2800, "Гроссмейстер", 0xFFFF4500),
    CHALLENGER(2800, Integer.MAX_VALUE, "Претендент", 0xFF000000);
    
    private final int minElo;
    private final int maxElo;
    private final String displayName;
    private final int color;
    
    Rank(int minElo, int maxElo, String displayName, int color) {
        this.minElo = minElo;
        this.maxElo = maxElo;
        this.displayName = displayName;
        this.color = color;
    }
    
    public int getMinElo() { return minElo; }
    public int getMaxElo() { return maxElo; }
    public String getDisplayName() { return displayName; }
    public int getColor() { return color; }
    
    /**
     * Получает ранг на основе ELO
     */
    public static Rank fromElo(int elo) {
        for (Rank rank : values()) {
            if (elo >= rank.minElo && elo < rank.maxElo) {
                return rank;
            }
        }
        return CHALLENGER;
    }
    
    /**
     * Получает прогресс до следующего ранга
     */
    public double getProgressToNext(int elo) {
        if (this == CHALLENGER) return 1.0;
        
        Rank nextRank = getNextRank();
        if (nextRank == null) return 1.0;
        
        int currentRange = this.maxElo - this.minElo;
        int progress = elo - this.minElo;
        
        return Math.min(1.0, (double) progress / currentRange);
    }
    
    /**
     * Получает следующий ранг
     */
    public Rank getNextRank() {
        Rank[] ranks = values();
        int nextIndex = this.ordinal() + 1;
        return nextIndex < ranks.length ? ranks[nextIndex] : null;
    }
    
    /**
     * Получает предыдущий ранг
     */
    public Rank getPreviousRank() {
        int prevIndex = this.ordinal() - 1;
        return prevIndex >= 0 ? values()[prevIndex] : null;
    }
}
