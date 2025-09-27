package race.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.particle.DustParticleEffect;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Легаси-«дымка» поверх мира. Изменения:
 * - SMOKE_DISTANCE_LIMIT увеличен до 192f.
 * - MAX_TOTAL_SMOKE_PARTICLES увеличен до 320.
 * - Отладочный println убран или снижен.
 */
public final class GhostOverlay {
    private static final Map<String, Deque<double[]>> TRAILS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Long> TTL = new java.util.concurrent.ConcurrentHashMap<>();

    private static volatile int ghostQualityLevel = 2; // 0..3
    private static volatile boolean adaptiveQuality = true;

    public static int getGhostQualityLevel() { return ghostQualityLevel; }
    public static void setGhostQualityLevel(int level) { ghostQualityLevel = Math.max(0, Math.min(3, level)); }
    public static boolean isAdaptiveQuality() { return adaptiveQuality; }
    public static void setAdaptiveQuality(boolean enabled) { adaptiveQuality = enabled; }

    private static int getAdaptiveQuality() {
        if (!adaptiveQuality) return ghostQualityLevel;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            int fps = mc.getCurrentFps();
            if (fps < 10) return 1;
            if (fps < 20) return 1;
            if (fps < 35) return 2;
            if (fps < 50) return 2;
            return 3; // позволим 3 при высоком FPS
        } catch (Exception e) {
            return ghostQualityLevel;
        }
    }

    public static void addTrail(String playerName, String cause, java.util.List<race.net.GhostTrailPayload.Point> pts) {
        Deque<double[]> dq = TRAILS.computeIfAbsent("__death:" + playerName, k -> new ArrayDeque<>());
        dq.clear();
        for (var p : pts) dq.addLast(new double[]{p.x(), p.y(), p.z()});
        TTL.put("__death:" + playerName, System.currentTimeMillis() + 15000L);
        try {
            var mc = MinecraftClient.getInstance();
            if (mc.player != null && cause != null && !cause.isEmpty()) {
                mc.player.sendMessage(net.minecraft.text.Text.literal("Призрак: " + cause).styled(s -> s.withColor(0xDD5555)), false);
            }
        } catch (Throwable ignored) {}
    }

    public static void addLive(String key, java.util.List<race.net.ParallelPlayersPayload.Point> pts) {
        long ttl = System.currentTimeMillis() + 12000L;
        
        // Группируем точки по игрокам для создания траекторий
        java.util.Map<String, java.util.List<race.net.ParallelPlayersPayload.Point>> byPlayer = new java.util.HashMap<>();
        for (var p : pts) {
            String playerName = p.name() == null ? "?" : p.name();
            byPlayer.computeIfAbsent(playerName, k -> new java.util.ArrayList<>()).add(p);
        }
        
        for (var entry : byPlayer.entrySet()) {
            String playerName = entry.getKey();
            java.util.List<race.net.ParallelPlayersPayload.Point> playerPoints = entry.getValue();
            String k = "__live:" + playerName;
            
            Deque<double[]> dq = TRAILS.computeIfAbsent(k, x -> new ArrayDeque<>());
            
            // ИСПРАВЛЕНИЕ: Показываем только текущее положение (очищаем старые точки)
            dq.clear(); // Очищаем старые точки
            for (var p : playerPoints) {
                dq.addLast(new double[]{p.x(), p.y(), p.z(), p.type()});
            }
            
            // Ограничиваем размер до 1 точки (только текущее положение)
            while (dq.size() > 1) {
                dq.removeFirst();
            }
            
            TTL.put(k, ttl);
            System.out.println("[Race] GhostOverlay.addLive: добавлено " + playerPoints.size() + " точек для " + playerName + " (всего в траектории: " + dq.size() + ")");
        }
    }

    public static void render(DrawContext ctx, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;

        int quality = getAdaptiveQuality();
        if (quality == 0) {
            return;
        }
        
        // Убраны debug сообщения - они спамят консоль

        long now = System.currentTimeMillis();
        TRAILS.entrySet().removeIf(e -> now > TTL.getOrDefault(e.getKey(), 0L));

        for (var e : TRAILS.entrySet()) {
            var key = e.getKey();
            var dq = e.getValue();

            if (key.startsWith("__live:")) {
                int silhouetteQuality = Math.max(1, quality - 1);
                for (double[] p : dq) {
                    drawSilhouette(mc, p[0], p[1], p[2], silhouetteQuality);
                }
            } else if (key.startsWith("__death:")) {
                int step = switch (quality) {
                    case 1 -> Math.max(1, dq.size() / 10);
                    case 2 -> Math.max(1, dq.size() / 20);
                    case 3 -> 1;
                    default -> Math.max(1, dq.size() / 20);
                };
                int idx = 0;
                for (double[] p : dq) {
                    if ((idx++ % step) != 0) continue;
                    float alpha = 0.2f + 0.8f * (idx / (float)Math.max(1, dq.size()));
                    DustParticleEffect ghost = new DustParticleEffect(new Vector3f(0.9f * alpha, 0.2f * alpha, 0.2f * alpha), 1.2f);
                    mc.world.addParticle(ghost, p[0], p[1] + 0.02, p[2], 0.0, 0.0, 0.0);
                }
            } else {
                int step = switch (quality) {
                    case 1 -> Math.max(1, dq.size() / 10);
                    case 2 -> Math.max(1, dq.size() / 20);
                    case 3 -> 1;
                    default -> Math.max(1, dq.size() / 20);
                };
                int i = 0;
                for (double[] p : dq) {
                    if ((i++ % step) != 0) continue;
                    DustParticleEffect ghost = new DustParticleEffect(new Vector3f(0.7f, 0.7f, 0.95f), 1.0f);
                    mc.world.addParticle(ghost, p[0], p[1] + 0.05, p[2], 0.0, 0.0, 0.0);
                }
            }
        }
    }

    private static void drawSilhouette(MinecraftClient mc, double x, double y, double z, int quality) {
        DustParticleEffect ghost = new DustParticleEffect(new Vector3f(0.65f, 0.75f, 0.95f), 1.2f);
        switch (quality) {
            case 1 -> {
                for (double dy = 0.3; dy <= 1.7; dy += 0.3) mc.world.addParticle(ghost, x, y + dy, z, 0.0, 0.0, 0.0);
                mc.world.addParticle(ghost, x, y + 1.8, z, 0.0, 0.0, 0.0);
            }
            case 2 -> {
                for (double dy = 0.2; dy <= 1.6; dy += 0.4) mc.world.addParticle(ghost, x, y + dy, z, 0.0, 0.0, 0.0);
                mc.world.addParticle(ghost, x, y + 1.8, z, 0.0, 0.0, 0.0);
            }
            case 3 -> {
                for (double dy = 0.2; dy <= 1.6; dy += 0.2) mc.world.addParticle(ghost, x, y + dy, z, 0.0, 0.0, 0.0);
                for (int i = 0; i < 8; i++) {
                    double ang = (Math.PI * 2 * i) / 8.0;
                    double rx = Math.cos(ang) * 0.18, rz = Math.sin(ang) * 0.18;
                    mc.world.addParticle(ghost, x + rx, y + 1.8, z + rz, 0.0, 0.0, 0.0);
                }
                mc.world.addParticle(ghost, x - 0.35, y + 1.2, z, 0.0, 0.0, 0.0);
                mc.world.addParticle(ghost, x + 0.35, y + 1.2, z, 0.0, 0.0, 0.0);
            }
        }
    }
}