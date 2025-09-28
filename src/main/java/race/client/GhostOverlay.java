package race.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.util.math.MathHelper;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * ОПТИМИЗИРОВАННЫЙ Призрачный силуэт:
 * - Полый контур вместо плотной заливки
 * - Адаптивное количество частиц по FPS и дистанции
 * - Максимум 15-20 частиц на призрака вместо 50-80
 * - Импульсная анимация для экономии ресурсов
 */
public final class GhostOverlay {
    // Точки и время жизни
    private static final Map<String, Deque<double[]>> TRAILS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Long> TTL = new java.util.concurrent.ConcurrentHashMap<>();

    // Доп. состояние для анимации
    private static final Map<String, double[]> LAST = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, String> STATE = new java.util.concurrent.ConcurrentHashMap<>();

    // Качество
    private static volatile int ghostQualityLevel = 2;
    private static volatile boolean adaptiveQuality = true;

    // Параметры LOD - увеличенная дальность
    private static final float LOD_NEAR = 64f;
    private static final float LOD_MID  = 128f;
    private static final float LOD_FAR  = 256f;

    // Счетчик для импульсной анимации
    private static long lastRenderTick = 0;
    private static final long RENDER_INTERVAL = 100L; // миллисекунды между полными отрисовками

    public static int getGhostQualityLevel() { return ghostQualityLevel; }
    public static void setGhostQualityLevel(int level) { ghostQualityLevel = Math.max(0, Math.min(3, level)); }
    public static boolean isAdaptiveQuality() { return adaptiveQuality; }
    public static void setAdaptiveQuality(boolean enabled) { adaptiveQuality = enabled; }

    private static int getAdaptiveQuality() {
        if (!adaptiveQuality) return ghostQualityLevel;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            int fps = mc.getCurrentFps();
            if (fps < 15) return 0; // полное отключение при критично низком FPS
            if (fps < 25) return 1;
            if (fps < 40) return 2;
            return Math.min(3, ghostQualityLevel);
        } catch (Exception e) {
            return Math.min(2, ghostQualityLevel);
        }
    }

    // Смертельный трек - упрощенная версия
    public static void addTrail(String playerName, String cause, java.util.List<race.net.GhostTrailPayload.Point> pts) {
        Deque<double[]> dq = TRAILS.computeIfAbsent("__death:" + playerName, k -> new ArrayDeque<>());
        dq.clear();
        // Берем только каждую 3-ю точку для экономии
        for (int i = 0; i < pts.size(); i += 3) {
            var p = pts.get(i);
            dq.addLast(new double[]{p.x(), p.y(), p.z()});
        }
        TTL.put("__death:" + playerName, System.currentTimeMillis() + 12000L); // сократили время жизни
        
        try {
            var mc = MinecraftClient.getInstance();
            if (mc.player != null && cause != null && !cause.isEmpty()) {
                mc.player.sendMessage(net.minecraft.text.Text.literal("Призрак: " + cause)
                        .styled(s -> s.withColor(0xDD5555)), false);
            }
        } catch (Throwable ignored) {}
    }

    // Живые позиции - без изменений
    public static void addLive(String key, java.util.List<race.net.ParallelPlayersPayload.Point> pts) {
        long ttl = System.currentTimeMillis() + 10000L; // сократили TTL
        
        java.util.Map<String, java.util.List<race.net.ParallelPlayersPayload.Point>> byPlayer = new java.util.HashMap<>();
        for (var p : pts) {
            String playerName = p.name() == null ? "?" : p.name();
            byPlayer.computeIfAbsent(playerName, k -> new java.util.ArrayList<>()).add(p);
        }
        
        for (var entry : byPlayer.entrySet()) {
            String playerName = entry.getKey();
            var playerPoints = entry.getValue();
            String k = "__live:" + playerName;
            
            Deque<double[]> dq = TRAILS.computeIfAbsent(k, x -> new ArrayDeque<>());
            
            double[] prev = dq.peekLast();
            if (prev != null) LAST.put(k, new double[]{prev[0], prev[1], prev[2]});

            byte typeByte = playerPoints.get(playerPoints.size() - 1).type();
            String st = convertTypeToString(typeByte);
            if (st != null) STATE.put(k, st);

            dq.clear();
            for (var p : playerPoints) {
                dq.addLast(new double[]{p.x(), p.y(), p.z(), p.type()});
            }
            while (dq.size() > 1) dq.removeFirst();
            
            TTL.put(k, ttl);
        }
    }

    public static void render(DrawContext ctx, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;

        int quality = getAdaptiveQuality();
        if (quality == 0) return;

        long now = System.currentTimeMillis();
        
        // Импульсная анимация - пропускаем кадры при низком FPS
        boolean shouldSkipFrame = false;
        if (mc.getCurrentFps() < 30 && (now - lastRenderTick) < RENDER_INTERVAL) {
            shouldSkipFrame = true;
        }
        
        TRAILS.entrySet().removeIf(e -> now > TTL.getOrDefault(e.getKey(), 0L));

        for (var e : TRAILS.entrySet()) {
            String key = e.getKey();
            Deque<double[]> dq = e.getValue();

            if (dq.isEmpty()) continue;

            if (key.startsWith("__live:")) {
                double[] p = dq.peekLast();
                if (p == null) continue;

                // Агрессивный LOD
                float dist = 0f;
                if (mc.player != null) {
                    double dx = mc.player.getX() - p[0];
                    double dy = mc.player.getY() - p[1];
                    double dz = mc.player.getZ() - p[2];
                    dist = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
                }
                
                // Пропускаем только очень дальние призраки (более 300 блоков)
                if (dist > 300f) continue;
                
                // Пропускаем кадры для дальних призраков
                if (shouldSkipFrame && dist > LOD_NEAR) continue;

                float lodMul = dist > LOD_FAR ? 0.5f : dist > LOD_MID ? 0.7f : dist > LOD_NEAR ? 0.85f : 1.0f;
                int silhouetteQuality = Math.max(1, Math.round(quality * lodMul));

                double[] last = LAST.get(key);
                String st = STATE.getOrDefault(key, "");
                long ttlEnd = TTL.getOrDefault(key, now + 1L);

                drawHollowSilhouette(mc, p[0], p[1], p[2], silhouetteQuality, last, st, now, ttlEnd, dist);
                
            } else if (key.startsWith("__death:")) {
                // Упрощенный шлейф смерти
                int step = Math.max(2, dq.size() / 8); // еще реже
                int idx = 0;
                for (double[] p : dq) {
                    if ((idx++ % step) != 0) continue;
                    float alpha = 0.3f + 0.5f * (idx / (float) Math.max(1, dq.size()));
                    DustParticleEffect ghost = new DustParticleEffect(
                        new Vector3f(0.9f * alpha, 0.2f * alpha, 0.2f * alpha), 1.5f);
                    mc.world.addParticle(ghost, p[0], p[1] + 0.02, p[2], 0.0, 0.0, 0.0);
                }
            }
        }
        
        lastRenderTick = now;
    }

    // ГЛАВНОЕ ИЗМЕНЕНИЕ: Полый силуэт вместо плотного
    private static void drawHollowSilhouette(MinecraftClient mc,
                                               double x, double y, double z,
                                               int quality,
                                               double[] last, String state,
                                               long now, long ttlEnd, float dist) {
        
        long lifespan = Math.max(1L, ttlEnd - now);
        float fade = MathHelper.clamp(lifespan / 10000f, 0f, 1f);
        if (dist > LOD_FAR) fade *= 0.8f; // менее агрессивное затухание
        if (fade <= 0.1f) return; // более мягкое отключение

        // Анимация
        float walkBase = (now % 1200L) / 1200.0f;
        float walkPhase = (float) (walkBase * Math.PI * 2.0);
        float breath = (float) Math.sin((now % 3000L) / 3000.0 * Math.PI * 2.0) * 0.03f;

        // Движение
        double dirX = 0, dirZ = 1;
        double speed = 0.0;
        if (last != null) {
            dirX = x - last[0];
            dirZ = z - last[2];
            speed = Math.sqrt(dirX*dirX + dirZ*dirZ);
            if (speed > 1e-4) { dirX /= speed; dirZ /= speed; }
        }

        // Состояние
        float swingMul = 1.0f;
        boolean isSneak = false, isJump = false;
        if (state != null) {
            String s = state.toLowerCase(java.util.Locale.ROOT);
            if (s.contains("sprint")) swingMul = 1.3f;
            if (s.contains("sneak")) { swingMul = 0.6f; isSneak = true; }
            if (s.contains("jump")) isJump = true;
        }

        // Цвета с fade
        float r = 0.7f * fade, g = 0.72f * fade, b = 0.95f;
        float headR = 0.8f * fade, headG = 0.78f * fade, headB = 0.98f;

        // Размеры по качеству и дистанции
        float baseSize = switch (quality) {
            case 1 -> 0.12f;
            case 2 -> 0.15f;
            default -> 0.18f;
        };
        if (dist > LOD_MID) baseSize *= 0.85f;

        // Позиции частей тела
        double headY = y + 1.6 + (isSneak ? -0.1 : 0.0) + breath;
        double chestY = y + 1.2 + (isSneak ? -0.08 : 0.0) + breath * 0.5;
        double pelvisY = y + 0.9 + (isSneak ? -0.12 : 0.0);
        
        float armSwing = (0.08f + (float) Math.min(0.06, speed * 0.1)) * swingMul;
        float legSwing = (0.06f + (float) Math.min(0.05, speed * 0.08)) * swingMul;

        // ПОЛЫЙ КОНТУР: только ключевые точки
        
        // 1. ГОЛОВА (4-6 точек по кругу)
        int headPoints = quality >= 3 ? 6 : 4;
        for (int i = 0; i < headPoints; i++) {
            double ang = (Math.PI * 2 * i) / headPoints;
            double rx = Math.cos(ang) * 0.15;
            double rz = Math.sin(ang) * 0.15;
            mc.world.addParticle(new DustParticleEffect(new Vector3f(headR, headG, headB), baseSize * 1.1f),
                    x + rx, headY, z + rz, 0.0, 0.0, 0.0);
        }

        // 2. ТОРС (только контур - 4 угла плеч + 4 угла таза)
        double shoulderW = 0.25, hipW = 0.18;
        
        // Плечи (4 точки)
        mc.world.addParticle(new DustParticleEffect(new Vector3f(r, g, b), baseSize),
                x + shoulderW, chestY, z, 0.0, 0.0, 0.0);
        mc.world.addParticle(new DustParticleEffect(new Vector3f(r, g, b), baseSize),
                x - shoulderW, chestY, z, 0.0, 0.0, 0.0);
        mc.world.addParticle(new DustParticleEffect(new Vector3f(r, g, b), baseSize),
                x, chestY, z + 0.12, 0.0, 0.0, 0.0);
        mc.world.addParticle(new DustParticleEffect(new Vector3f(r, g, b), baseSize),
                x, chestY, z - 0.12, 0.0, 0.0, 0.0);

        // Таз (4 точки)
        mc.world.addParticle(new DustParticleEffect(new Vector3f(r, g, b), baseSize),
                x + hipW, pelvisY, z, 0.0, 0.0, 0.0);
        mc.world.addParticle(new DustParticleEffect(new Vector3f(r, g, b), baseSize),
                x - hipW, pelvisY, z, 0.0, 0.0, 0.0);
        mc.world.addParticle(new DustParticleEffect(new Vector3f(r, g, b), baseSize),
                x, pelvisY, z + 0.10, 0.0, 0.0, 0.0);
        mc.world.addParticle(new DustParticleEffect(new Vector3f(r, g, b), baseSize),
                x, pelvisY, z - 0.10, 0.0, 0.0, 0.0);

        // 3. КОНЕЧНОСТИ (только суставы + концы)
        double armSwingR = Math.sin(walkPhase) * armSwing;
        double armSwingL = Math.sin(walkPhase + Math.PI) * armSwing;
        double legSwingR = Math.sin(walkPhase + Math.PI) * legSwing;
        double legSwingL = Math.sin(walkPhase) * legSwing;

        double jumpArmY = isJump ? 0.08 : 0.0;

        // Правая рука (плечо -> локоть -> кисть)
        mc.world.addParticle(new DustParticleEffect(new Vector3f(r, g, b), baseSize * 0.9f),
                x + 0.38, y + 1.0 + jumpArmY, z + armSwingR, 0.0, 0.0, 0.0); // локоть
        mc.world.addParticle(new DustParticleEffect(new Vector3f(r, g, b), baseSize * 0.8f),
                x + 0.42, y + 0.75 + jumpArmY, z + armSwingR * 0.6, 0.0, 0.0, 0.0); // кисть

        // Левая рука
        mc.world.addParticle(new DustParticleEffect(new Vector3f(r, g, b), baseSize * 0.9f),
                x - 0.38, y + 1.0 + jumpArmY, z + armSwingL, 0.0, 0.0, 0.0);
        mc.world.addParticle(new DustParticleEffect(new Vector3f(r, g, b), baseSize * 0.8f),
                x - 0.42, y + 0.75 + jumpArmY, z + armSwingL * 0.6, 0.0, 0.0, 0.0);

        // Правая нога (бедро -> колено -> стопа)
        mc.world.addParticle(new DustParticleEffect(new Vector3f(r, g, b), baseSize * 0.9f),
                x + 0.15, y + 0.45, z + legSwingR, 0.0, 0.0, 0.0); // колено
        mc.world.addParticle(new DustParticleEffect(new Vector3f(r, g, b), baseSize * 0.8f),
                x + 0.15, y + 0.05, z + legSwingR * 0.5, 0.0, 0.0, 0.0); // стопа

        // Левая нога
        mc.world.addParticle(new DustParticleEffect(new Vector3f(r, g, b), baseSize * 0.9f),
                x - 0.15, y + 0.45, z + legSwingL, 0.0, 0.0, 0.0);
        mc.world.addParticle(new DustParticleEffect(new Vector3f(r, g, b), baseSize * 0.8f),
                x - 0.15, y + 0.05, z + legSwingL * 0.5, 0.0, 0.0, 0.0);

        // Итого: 6 (голова) + 8 (торс) + 8 (конечности) = максимум 22 частицы
        // При низком качестве: 4 + 8 + 8 = 20 частиц
        // Это в 2-3 раза меньше оригинала!
    }

    private static String convertTypeToString(byte type) {
        return switch (type) {
            case 0 -> "idle";
            case 1 -> "mining";
            case 2 -> "walking";
            case 3 -> "running";
            case 4 -> "sprint";
            case 5 -> "sneak";
            case 6 -> "jump";
            case 7 -> "using";
            default -> "unknown";
        };
    }

    private GhostOverlay() {}
}