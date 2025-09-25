package race.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.particle.DustParticleEffect;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

public final class GhostOverlay {
    private static final Map<String, Deque<double[]>> TRAILS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Long> TTL = new java.util.concurrent.ConcurrentHashMap<>();
    
    // Адаптивное качество призраков
    private static volatile int ghostQualityLevel = 2; // 0=отключено, 1=низкое, 2=среднее, 3=высокое
    private static volatile boolean adaptiveQuality = true;
    
    // Методы для управления качеством призраков
    public static int getGhostQualityLevel() { return ghostQualityLevel; }
    public static void setGhostQualityLevel(int level) { ghostQualityLevel = Math.max(0, Math.min(3, level)); }
    public static boolean isAdaptiveQuality() { return adaptiveQuality; }
    public static void setAdaptiveQuality(boolean enabled) { adaptiveQuality = enabled; }
    
    /**
     * Получает адаптивное качество призраков на основе FPS
     */
    private static int getAdaptiveQuality() {
        if (!adaptiveQuality) return ghostQualityLevel;
        
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.getCurrentFps() < 30) return 0; // Отключаем при очень низком FPS
            if (mc.getCurrentFps() < 45) return 0; // Низкое качество при среднем FPS
            if (mc.getCurrentFps() < 90) return 1; // Среднее качество при хорошем FPS
            return 2; // Высокое качество при отличном FPS
        } catch (Exception e) {
            return ghostQualityLevel;
        }
    }

    public static void addTrail(String playerName, String cause, java.util.List<race.net.GhostTrailPayload.Point> pts) {
        Deque<double[]> dq = TRAILS.computeIfAbsent("__death:" + playerName, k -> new ArrayDeque<>());
        dq.clear();
        for (var p : pts) dq.addLast(new double[]{p.x(), p.y(), p.z()});
        TTL.put("__death:" + playerName, System.currentTimeMillis() + 6000L);
        // Однократно показать всплывающий тост/чаты о причине смерти
        try {
            var mc = MinecraftClient.getInstance();
            if (mc.player != null && cause != null && !cause.isEmpty()) {
                mc.player.sendMessage(net.minecraft.text.Text.literal("Призрак: " + cause).styled(s -> s.withColor(0xDD5555)), false);
            }
        } catch (Throwable ignored) {}
    }

    // Приём лайв‑точек от ParallelPlayersPayload
    public static void addLive(String key, java.util.List<race.net.ParallelPlayersPayload.Point> pts) {
        // key игнорируем, хранить будем по имени чтобы не слипались силуэты
        long ttl = System.currentTimeMillis() + 1500L;
        for (var p : pts) {
            String k = "__live:" + (p.name() == null ? "?" : p.name());
            Deque<double[]> dq = TRAILS.computeIfAbsent(k, x -> new ArrayDeque<>());
            dq.clear();
            dq.addLast(new double[]{p.x(), p.y(), p.z(), p.type()});
            TTL.put(k, ttl);
        }
    }

    public static void render(DrawContext ctx, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        
        // Проверяем качество призраков
        int quality = getAdaptiveQuality();
        if (quality == 0) return; // Отключено при низкой производительности
        
        long now = System.currentTimeMillis();
        TRAILS.entrySet().removeIf(e -> now > TTL.getOrDefault(e.getKey(), 0L));
        
        for (var e : TRAILS.entrySet()) {
            var key = e.getKey();
            var dq = e.getValue();
            
            // Отрисовка: если это лайв‑метки, рисуем "силуэт" из частиц
            if (key.startsWith("__live:")) {
                // Адаптивное качество для силуэтов
                int silhouetteQuality = Math.max(1, quality - 1); // Силуэты всегда рисуем, но с разным качеством
                for (double[] p : dq) {
                    byte type = (byte)(p.length >= 4 ? p[3] : 0);
                    drawSilhouette(mc, p[0], p[1], p[2], silhouetteQuality);
                    
                    // Искры только при высоком качестве
                    if (quality >= 3) {
                        if (type == 3) addSpark(mc, p[0], p[1] + 1.2, p[2], 1f, 0.2f, 0.2f); // fight (красный)
                        else if (type == 4) addSpark(mc, p[0], p[1] + 0.8, p[2], 0.6f, 0.6f, 1f); // move (синий)
                        else if (type == 7) addSpark(mc, p[0], p[1] + 1.0, p[2], 1f, 1f, 0.4f); // craft/use (жёлтый)
                    }
                }
            } else if (key.startsWith("__death:")) {
                // Адаптивное качество для следов смерти
                int step = switch (quality) {
                    case 1 -> Math.max(1, dq.size() / 20); // Низкое качество: 20 точек
                    case 2 -> Math.max(1, dq.size() / 40); // Среднее качество: 40 точек
                    case 3 -> 1; // Высокое качество: все точки
                    default -> Math.max(1, dq.size() / 40);
                };
                
                int idx = 0;
                for (double[] p : dq) {
                    if ((idx++ % step) != 0) continue;
                    float alpha = 0.2f + 0.8f * (idx / (float)Math.max(1, dq.size()));
                    DustParticleEffect ghost = new DustParticleEffect(new Vector3f(0.9f * alpha, 0.2f * alpha, 0.2f * alpha), 1.2f);
                    mc.world.addParticle(ghost, p[0], p[1] + 0.02, p[2], 0.0, 0.0, 0.0);
                }
            } else {
                // Адаптивное качество для обычных следов
                int step = switch (quality) {
                    case 1 -> Math.max(1, dq.size() / 20); // Низкое качество: 20 точек
                    case 2 -> Math.max(1, dq.size() / 40); // Среднее качество: 40 точек
                    case 3 -> 1; // Высокое качество: все точки
                    default -> Math.max(1, dq.size() / 40);
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
        // Цвет бледно‑голубой
        DustParticleEffect ghost = new DustParticleEffect(new Vector3f(0.65f, 0.75f, 0.95f), 1.2f);
        
        // Адаптивное качество силуэта
        switch (quality) {
            case 1 -> {
                // Низкое качество: только основные части
                mc.world.addParticle(ghost, x, y + 0.5, z, 0.0, 0.0, 0.0);
                mc.world.addParticle(ghost, x, y + 1.0, z, 0.0, 0.0, 0.0);
                mc.world.addParticle(ghost, x, y + 1.5, z, 0.0, 0.0, 0.0);
            }
            case 2 -> {
                // Среднее качество: туловище + голова
                for (double dy = 0.2; dy <= 1.6; dy += 0.4) {
                    mc.world.addParticle(ghost, x, y + dy, z, 0.0, 0.0, 0.0);
                }
                // Голова (упрощенная)
                mc.world.addParticle(ghost, x, y + 1.8, z, 0.0, 0.0, 0.0);
            }
            case 3 -> {
                // Высокое качество: полный силуэт
                // Туловище столбиком
                for (double dy = 0.2; dy <= 1.6; dy += 0.2) {
                    mc.world.addParticle(ghost, x, y + dy, z, 0.0, 0.0, 0.0);
                }
                // Голова (небольшая сфера)
                for (int i = 0; i < 8; i++) {
                    double ang = (Math.PI * 2 * i) / 8.0;
                    double rx = Math.cos(ang) * 0.18;
                    double rz = Math.sin(ang) * 0.18;
                    mc.world.addParticle(ghost, x + rx, y + 1.8, z + rz, 0.0, 0.0, 0.0);
                }
                // Руки
                mc.world.addParticle(ghost, x - 0.35, y + 1.2, z, 0.0, 0.0, 0.0);
                mc.world.addParticle(ghost, x + 0.35, y + 1.2, z, 0.0, 0.0, 0.0);
            }
        }
    }

    private static void addSpark(MinecraftClient mc, double x, double y, double z, float r, float g, float b) {
        DustParticleEffect spark = new DustParticleEffect(new Vector3f(r, g, b), 1.0f);
        mc.world.addParticle(spark, x, y, z, 0.0, 0.01, 0.0);
    }
}


