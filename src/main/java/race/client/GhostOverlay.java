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
 * Призрачный силуэт поверх мира:
 * - Процедурная «скелетная» фигура из частиц (голова/торс/руки/ноги).
 * - Анимация шага, дыхания, поворот головы, состояния sprint/sneak/jump.
 * - LOD по дистанции и плавный fade по TTL.
 * - Без роста очередей: одна актуальная live-точка на игрока.
 */
public final class GhostOverlay {
    // Точки и время жизни
    private static final Map<String, Deque<double[]>> TRAILS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Long> TTL = new java.util.concurrent.ConcurrentHashMap<>();

    // Доп. состояние для анимации
    private static final Map<String, double[]> LAST = new java.util.concurrent.ConcurrentHashMap<>();   // последняя позиция
    private static final Map<String, String> STATE = new java.util.concurrent.ConcurrentHashMap<>();    // sprint|sneak|jump|...

    // Качество
    private static volatile int ghostQualityLevel = 2; // 0..3
    private static volatile boolean adaptiveQuality = true;

    // Параметры LOD
    private static final float LOD_NEAR = 48f;
    private static final float LOD_MID  = 64f;
    private static final float LOD_FAR  = 96f;

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
            return 3;
        } catch (Exception e) {
            return ghostQualityLevel;
        }
    }

    // «Смертельный» трек (лента из точек) — без изменений
    public static void addTrail(String playerName, String cause, java.util.List<race.net.GhostTrailPayload.Point> pts) {
        Deque<double[]> dq = TRAILS.computeIfAbsent("__death:" + playerName, k -> new ArrayDeque<>());
        dq.clear();
        for (var p : pts) dq.addLast(new double[]{p.x(), p.y(), p.z()});
        TTL.put("__death:" + playerName, System.currentTimeMillis() + 15000L);
        try {
            var mc = MinecraftClient.getInstance();
            if (mc.player != null && cause != null && !cause.isEmpty()) {
                mc.player.sendMessage(net.minecraft.text.Text.literal("Призрак: " + cause)
                        .styled(s -> s.withColor(0xDD5555)), false);
            }
        } catch (Throwable ignored) {}
    }

    // Живые позиции параллельных игроков — одна актуальная точка, но сохраняем «last» и «state»
    public static void addLive(String key, java.util.List<race.net.ParallelPlayersPayload.Point> pts) {
        long ttl = System.currentTimeMillis() + 12000L;
        
        // Группируем по имени игрока
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
            
            // Сохраняем «last» перед очисткой (если была точка)
            double[] prev = dq.peekLast();
            if (prev != null) LAST.put(k, new double[]{prev[0], prev[1], prev[2]});

            // Обновляем текущее состояние из последней точки
            byte typeByte = playerPoints.get(playerPoints.size() - 1).type();
            String st = convertTypeToString(typeByte);
            if (st != null) STATE.put(k, st);

            // Храним только одну актуальную точку
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
        TRAILS.entrySet().removeIf(e -> now > TTL.getOrDefault(e.getKey(), 0L));

        for (var e : TRAILS.entrySet()) {
            String key = e.getKey();
            Deque<double[]> dq = e.getValue();

            if (dq.isEmpty()) continue;

            if (key.startsWith("__live:")) {
                double[] p = dq.peekLast();
                if (p == null) continue;

                // Дистанционный LOD
                float dist = 0f;
                if (mc.player != null) {
                    double dx = mc.player.getX() - p[0];
                    double dy = mc.player.getY() - p[1];
                    double dz = mc.player.getZ() - p[2];
                    dist = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
                }
                float lodMul = dist > LOD_FAR ? 0.5f : dist > LOD_MID ? 0.75f : dist > LOD_NEAR ? 0.9f : 1.0f;
                int silhouetteQuality = Math.max(1, Math.round((quality) * lodMul));

                // Параметры анимации
                double[] last = LAST.get(key);
                String st = STATE.getOrDefault(key, "");
                long ttlEnd = TTL.getOrDefault(key, now + 1L);

                drawSilhouetteAnimated(mc, p[0], p[1], p[2], silhouetteQuality, last, st, now, ttlEnd, dist);
            } else if (key.startsWith("__death:")) {
                // Упрощённый «шлейф смерти» (как было)
                int step = switch (quality) {
                    case 1 -> Math.max(1, dq.size() / 10);
                    case 2 -> Math.max(1, dq.size() / 20);
                    case 3 -> 1;
                    default -> Math.max(1, dq.size() / 20);
                };
                int idx = 0;
                for (double[] p : dq) {
                    if ((idx++ % step) != 0) continue;
                    float alpha = 0.2f + 0.8f * (idx / (float) Math.max(1, dq.size()));
                    DustParticleEffect ghost = new DustParticleEffect(new Vector3f(0.9f * alpha, 0.2f * alpha, 0.2f * alpha), 1.2f);
                    mc.world.addParticle(ghost, p[0], p[1] + 0.02, p[2], 0.0, 0.0, 0.0);
                }
            } else {
                // Прочие ключи — fallback
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

    // Анимированный силуэт
    private static void drawSilhouetteAnimated(MinecraftClient mc,
                                               double x, double y, double z,
                                               int quality,
                                               double[] last, String state,
                                               long now, long ttlEnd, float dist) {
        // Fade по TTL и дистанции
        long lifespan = Math.max(1L, ttlEnd - now);
        float fade = MathHelper.clamp(lifespan / 12000f, 0f, 1f);
        if (dist > LOD_FAR) fade *= 0.85f;
        if (fade <= 0.02f) return;

        // Базовые пропорции
        final double neck = 1.5;
        final double chest = 1.2;
        final double pelvis = 0.9;
        final double knee = 0.45;
        final double foot = 0.05;

        final double shoulder = 0.30;
        final double hip = 0.22;
        final double armX = 0.42;
        final double forearmX = 0.46;
        final double legX = 0.18;

        // Цвета (модулируем fade)
        float bodyR = 0.70f * (0.6f + 0.4f * fade), bodyG = 0.72f * (0.6f + 0.4f * fade), bodyB = 0.95f;
        float limbR = 0.65f * (0.6f + 0.4f * fade), limbG = 0.70f * (0.6f + 0.4f * fade), limbB = 0.95f;
        float headR = 0.80f * (0.6f + 0.4f * fade), headG = 0.78f * (0.6f + 0.4f * fade), headB = 0.98f;

        // Качество → размеры
        float baseSize = switch (quality) { case 1 -> 0.09f; case 2 -> 0.11f; default -> 0.13f; };
        int segDensity = switch (quality) { case 1 -> 2; case 2 -> 3; default -> 4; };
        int headRings = 6;

        // Направление и скорость
        double dirX = 0, dirZ = 1;
        double speed = 0.0;
        if (last != null) {
            dirX = x - last[0];
            dirZ = z - last[2];
            speed = Math.sqrt(dirX*dirX + dirZ*dirZ);
            if (speed > 1e-4) { dirX /= speed; dirZ /= speed; }
        }
        float headYaw = (float) Math.atan2(dirX, dirZ);

        // Анимация
        float walkBase = (now % 900L) / 900.0f; // 0..1
        float walkPhase = (float) (walkBase * Math.PI * 2.0);
        float breath = (float) Math.sin((now % 2400L) / 2400.0 * Math.PI * 2.0) * 0.04f;

        // Амплитуды по состоянию
        float swingMul = 1.0f;
        float torsoLean = 0.0f;
        boolean isSneak = false, isJump = false;
        if (state != null) {
            String s = state.toLowerCase(java.util.Locale.ROOT);
            if (s.contains("sprint")) { swingMul = 1.3f; torsoLean = 0.06f; }
            if (s.contains("sneak"))  { swingMul = 0.6f; isSneak = true; }
            if (s.contains("jump"))   { isJump = true; }
        }
        float armSwing = (0.10f + (float) Math.min(0.08, speed * 0.15)) * swingMul;
        float legSwing = (0.08f + (float) Math.min(0.06, speed * 0.12)) * swingMul;

        // Смещения
        double chestAnim = chest + (isSneak ? -0.08 : 0.0) + breath;
        double pelvisAnim = pelvis + (isSneak ? -0.10 : 0.0);
        double neckAnim = neck + breath * 0.6;

        // Торс (кольца грудь→таз) с квадратичным сужением к талии
        int rings = 3 + segDensity;
        for (int i = 0; i <= rings; i++) {
            double t = i / (double) rings;
            double yy = MathHelper.lerp((float) t, (float) chestAnim, (float) pelvisAnim);
            
            // Квадратичное сужение к талии для более реалистичных пропорций
            double tt = t * t; // усиливаем сужение к тазу
            double half = MathHelper.lerp((float) tt, (float) shoulder, (float) hip);
            
            // Уменьшаем количество точек для верхних колец (плечи)
            int ringPts = 6 + segDensity * 2 - (dist > LOD_MID ? 2 : 0);
            if (i <= 1) ringPts = Math.max(4, ringPts - 1); // меньше точек для плеч
            ringPts = Math.max(4, ringPts);
            
            // Уменьшаем радиус верхних колец на 5%
            double radiusMultiplier = (i <= 1) ? 0.95 : 0.9;
            
            for (int k = 0; k < ringPts; k++) {
                double ang = (Math.PI * 2 * k) / ringPts;
                double rx = Math.cos(ang) * half * radiusMultiplier;
                double rz = Math.sin(ang) * half * radiusMultiplier + torsoLean;
                mc.world.addParticle(new DustParticleEffect(new Vector3f(bodyR, bodyG, bodyB), 1.05f),
                        x + rx, y + yy, z + rz, 0.0, 0.0, 0.0);
            }
        }

        // Голова (поворот по headYaw)
        for (int i = 0; i < headRings; i++) {
            double ang = (Math.PI * 2 * i) / headRings;
            double rx0 = Math.cos(ang) * 0.16;
            double rz0 = Math.sin(ang) * 0.16;
            double rx =  rx0 * Math.cos(headYaw) - rz0 * Math.sin(headYaw);
            double rz =  rx0 * Math.sin(headYaw) + rz0 * Math.cos(headYaw);
            mc.world.addParticle(new DustParticleEffect(new Vector3f(headR, headG, headB), 1.2f),
                    x + rx, y + neckAnim + 0.12, z + rz, 0.0, 0.0, 0.0);
        }
        mc.world.addParticle(new DustParticleEffect(new Vector3f(headR, headG, headB), 1.25f),
                x, y + neckAnim + 0.18, z, 0.0, 0.0, 0.0);

        // Фазы конечностей (противофаза L/R)
        double armSwingOffR = Math.sin(walkPhase) * armSwing;
        double armSwingOffL = Math.sin(walkPhase + Math.PI) * armSwing;
        double legSwingOffR = Math.sin(walkPhase + Math.PI) * legSwing;
        double legSwingOffL = Math.sin(walkPhase) * legSwing;

        // Подъём рук при прыжке
        double jumpArmY = isJump ? 0.10 : 0.0;

        // Руки
        drawLimb(mc, x + shoulder, y + chestAnim, z,
                x + armX, y + 1.05 + jumpArmY, z + armSwingOffR,
                limbR, limbG, limbB, baseSize, segDensity, dist);
        drawLimb(mc, x + armX, y + 1.05 + jumpArmY, z + armSwingOffR,
                x + forearmX, y + 0.78 + jumpArmY, z + armSwingOffR * 0.6,
                limbR, limbG, limbB, baseSize, segDensity, dist);

        drawLimb(mc, x - shoulder, y + chestAnim, z,
                x - armX, y + 1.05 + jumpArmY, z + armSwingOffL,
                limbR, limbG, limbB, baseSize, segDensity, dist);
        drawLimb(mc, x - armX, y + 1.05 + jumpArmY, z + armSwingOffL,
                x - forearmX, y + 0.78 + jumpArmY, z + armSwingOffL * 0.6,
                limbR, limbG, limbB, baseSize, segDensity, dist);

        // Ноги
        drawLimb(mc, x + hip * 0.6, y + pelvisAnim, z,
                x + legX, y + knee, z + legSwingOffR,
                limbR, limbG, limbB, baseSize, segDensity, dist);
        drawLimb(mc, x + legX, y + knee, z + legSwingOffR,
                x + legX, y + foot, z + 0.05 + legSwingOffR * 0.5,
                limbR, limbG, limbB, baseSize, segDensity, dist);

        drawLimb(mc, x - hip * 0.6, y + pelvisAnim, z,
                x - legX, y + knee, z + legSwingOffL,
                limbR, limbG, limbB, baseSize, segDensity, dist);
        drawLimb(mc, x - legX, y + knee, z + legSwingOffL,
                x - legX, y + foot, z + 0.05 + legSwingOffL * 0.5,
                limbR, limbG, limbB, baseSize, segDensity, dist);
    }

    private static void drawLimb(MinecraftClient mc,
                                 double x1, double y1, double z1,
                                 double x2, double y2, double z2,
                                 float r, float g, float b, float baseSize, int segDensity,
                                 float dist) {
        int stepsBase = 2 + segDensity * 2 - (dist > LOD_MID ? 1 : 0);
        int steps = Math.max(2, stepsBase);
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double px = MathHelper.lerp((float) t, (float) x1, (float) x2);
            double py = MathHelper.lerp((float) t, (float) y1, (float) y2);
            double pz = MathHelper.lerp((float) t, (float) z1, (float) z2);
            // Более сильное сужение к кисти/ступне для лучшей читаемости суставов
            float s = baseSize * (0.95f - 0.45f * (float) t); // было 0.35f, стало 0.45f
            mc.world.addParticle(new DustParticleEffect(new Vector3f(r, g, b), 1.0f),
                    px, py, pz, 0.0, 0.0, 0.0);
        }
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