package race.client.hub;

/**
 * Глобальное клиентское состояние для хаба и выбранного сида.
 */
public final class RaceHubState {
    private static volatile long pendingSeed = -1L;

    private RaceHubState() {}

    public static void setPendingSeed(long seed) {
        pendingSeed = seed;
    }

    public static long getPendingSeed() {
        return pendingSeed;
    }

    public static boolean hasPendingSeed() {
        return pendingSeed >= 0L;
    }

    public static long consumePendingSeed() {
        long v = pendingSeed;
        pendingSeed = -1L;
        return v;
    }
}


