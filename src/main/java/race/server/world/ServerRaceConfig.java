package race.server.world;

import java.util.UUID;

public final class ServerRaceConfig {
    public static volatile long GLOBAL_SEED = -1L;

    public static void initializeFromSystem() {
        String s = System.getProperty("race.seed", "-1");
        try { GLOBAL_SEED = Long.parseLong(s); } catch (Exception ignored) {}
    }

    public static long seedFor(UUID ignored) { return GLOBAL_SEED; }

    private ServerRaceConfig() {}
}
