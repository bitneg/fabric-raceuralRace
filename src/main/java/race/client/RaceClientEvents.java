package race.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class RaceClientEvents {
    private static volatile boolean active = false;
    private static volatile long t0ms = -1L;
    private static volatile long worldSeed = -1L;
    private static long rtaMs = 0L;
    private static String stage = "OW";

    public static void onRaceStart(long seed, long t0) {
        worldSeed = seed;
        t0ms = t0 > 0 ? t0 : System.currentTimeMillis();
        active = true;
        rtaMs = 0L;
        stage = "OW";
        RaceProgressTracker.reset();
        System.out.println("[Race] Client race started: seed=" + seed + " t0=" + t0ms);
    }

    public static long getRtaMs() {
        if (!active || t0ms <= 0) return 0L;
        long now = System.currentTimeMillis();
        return Math.max(0L, now - t0ms);
    }

    public static long getWorldSeed() { return worldSeed; }
    public static String getStage() { return stage; }
    public static void setStage(String s) { stage = s; }
    public static boolean isRaceActive() { return active; }

    public static void hookClientTick() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (active) {
                rtaMs = getRtaMs();
                RaceProgressTracker.updateProgress();
            }
        });
    }

    public static void setStartFromServer(long seed, long t0ms) {
        onRaceStart(seed, t0ms);
    }

    public static void stopRace() {
        active = false;
        t0ms = -1L;
        worldSeed = -1L;
        rtaMs = 0L;
        stage = "OW";
        RaceProgressTracker.reset();
    }
}
