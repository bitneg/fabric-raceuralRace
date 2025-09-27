package race.client.death;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Рендер дальнобойных маркеров смерти и длинных шлейфов без падения FPS.
 * Изменения:
 * - LOD-дальность шлейфа увеличена до 256.
 * - Добавлен merge траекторий (не перезатираем сразу).
 * - Небольшие оптимизации и защиты.
 */
public final class DeathEchoRenderer {
    private DeathEchoRenderer() {}

    private static final Map<String, ActiveMarker> ACTIVE_MARKERS = new ConcurrentHashMap<>();
    private static final int MAX_MARKERS = 8;
    private static final long MARKER_LIFETIME_MS = 10000L;

    // Шлейфы
    private static final Map<String, TrailData> ACTIVE_TRAILS = new ConcurrentHashMap<>();
    private static final int MAX_TRAIL_POINTS = 48;
    private static final float TRAIL_WIDTH = 0.4f;
    private static final long TRAIL_LIFETIME_MS = 15000L;

    // Кэш геометрии
    private static final Map<String, CachedGeometry> GEOMETRY_CACHE = new ConcurrentHashMap<>();
    private static long lastGeometryUpdate = 0;
    private static final long GEOMETRY_UPDATE_INTERVAL = 100;

    // Порог видимости шлейфа по расстоянию (было 128f)
    private static final float TRAIL_LOD_DISTANCE = 256f;

    public static class ActiveMarker {
        public final Vec3d pos;
        public final float r, g, b;
        public final long startTime;
        public final long endTime;
        public final String id;

        public ActiveMarker(Vec3d pos, float r, float g, float b, float durationSec) {
            this.pos = pos; this.r = r; this.g = g; this.b = b;
            this.startTime = System.currentTimeMillis();
            this.endTime = startTime + (long)(durationSec * 1000);
            this.id = UUID.randomUUID().toString();
        }

        public float getAlpha() {
            long now = System.currentTimeMillis();
            if (now >= endTime) return 0f;
            float progress = (now - startTime) / (float)(endTime - startTime);
            return MathHelper.sin(progress * MathHelper.PI) * 0.8f;
        }

        public boolean isExpired() { return System.currentTimeMillis() >= endTime; }
    }

    public static class TrailData {
        public final String playerId;
        public final String cause;
        public final List<Vec3d> points;
        public final long startTime;
        public final long endTime;

        public TrailData(String playerId, String cause, List<Vec3d> points) {
            this.playerId = playerId; this.cause = cause;
            this.points = new ArrayList<>(points);
            this.startTime = System.currentTimeMillis();
            this.endTime = startTime + TRAIL_LIFETIME_MS;
        }

        public boolean isExpired() { return System.currentTimeMillis() >= endTime; }
    }

    public static class CachedGeometry {
        public final List<Vector3f> vertices;
        public final long lastUpdate;

        public CachedGeometry(List<Vector3f> vertices) {
            this.vertices = vertices;
            this.lastUpdate = System.currentTimeMillis();
        }
    }

    // Добавление/слияние шлейфа
    public static void addTrail(String playerId, String cause, List<race.net.GhostTrailPayload.Point> points) {
        if (playerId == null || points == null || points.isEmpty()) return;

        List<Vec3d> incoming = points.stream()
                .map(p -> new Vec3d(p.x(), p.y(), p.z()))
                .toList();

        // ИСПРАВЛЕНИЕ: Показываем только текущее положение (не создаем траекторию)
        ACTIVE_TRAILS.put(playerId, new TrailData(playerId, cause, clampPoints(incoming, 1))); // Только 1 точка
        // Сбрасываем кэш геометрии этого игрока, чтобы обновить полосы
        GEOMETRY_CACHE.remove(playerId);
    }

    private static List<Vec3d> clampPoints(List<Vec3d> pts, int max) {
        if (pts.size() <= max) return pts;
        return pts.subList(pts.size() - max, pts.size());
    }

    public static void render(WorldRenderContext context) {
        MinecraftClient client = context.gameRenderer().getClient();
        if (client.world == null || client.player == null) return;

        Vec3d cameraPos = context.camera().getPos();
        MatrixStack matrices = context.matrixStack();

        ACTIVE_MARKERS.entrySet().removeIf(e -> e.getValue().isExpired());
        ACTIVE_TRAILS.entrySet().removeIf(e -> e.getValue().isExpired());

        renderMarkers(context, cameraPos, matrices);
        renderTrails(context, cameraPos, matrices);
    }

    private static void renderMarkers(WorldRenderContext context, Vec3d cameraPos, MatrixStack matrices) {
        if (ACTIVE_MARKERS.isEmpty()) return;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();

        BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        boolean hasVertices = false;

        for (ActiveMarker marker : ACTIVE_MARKERS.values()) {
            float distance = (float) cameraPos.distanceTo(marker.pos);
            if (distance > 256f) continue;

            float alpha = marker.getAlpha();
            if (alpha <= 0f) continue;

            float lodAlpha = alpha;
            float height = 32f;
            if (distance > 96f) {
                float k = MathHelper.clamp(1f - (distance - 96f) / 160f, 0.4f, 1f);
                lodAlpha *= k;
                height *= MathHelper.clamp(k, 0.6f, 1f);
            }
            if (lodAlpha <= 0.01f) continue;

            renderMarkerBeamToBuffer(buffer, matrix, marker, height, lodAlpha);
            hasVertices = true;
        }

        if (hasVertices) {
            RenderSystem.setShader(GameRenderer::getPositionColorProgram);
            BufferRenderer.drawWithGlobalProgram(buffer.end());
        }

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void renderMarkerBeamToBuffer(BufferBuilder buffer, Matrix4f matrix, ActiveMarker marker, float height, float alpha) {
        Vec3d pos = marker.pos;
        float r = marker.r, g = marker.g, b = marker.b;

        float x = (float) pos.x;
        float y = (float) pos.y;
        float z = (float) pos.z;

        float halfWidth = 0.5f;
        float topAlpha = alpha;
        float bottomAlpha = alpha * 0.3f;

        // вдоль X
        buffer.vertex(matrix, x - halfWidth, y + height, z).color(r, g, b, topAlpha);
        buffer.vertex(matrix, x + halfWidth, y + height, z).color(r, g, b, topAlpha);
        buffer.vertex(matrix, x + halfWidth, y + height * 0.7f, z).color(r, g, b, topAlpha * 0.8f);
        buffer.vertex(matrix, x - halfWidth, y + height * 0.7f, z).color(r, g, b, topAlpha * 0.8f);

        buffer.vertex(matrix, x - halfWidth, y + height * 0.7f, z).color(r, g, b, topAlpha * 0.8f);
        buffer.vertex(matrix, x + halfWidth, y + height * 0.7f, z).color(r, g, b, topAlpha * 0.8f);
        buffer.vertex(matrix, x + halfWidth, y, z).color(r, g, b, bottomAlpha);
        buffer.vertex(matrix, x - halfWidth, y, z).color(r, g, b, bottomAlpha);

        // вдоль Z
        buffer.vertex(matrix, x, y + height, z - halfWidth).color(r, g, b, topAlpha);
        buffer.vertex(matrix, x, y + height, z + halfWidth).color(r, g, b, topAlpha);
        buffer.vertex(matrix, x, y + height * 0.7f, z + halfWidth).color(r, g, b, topAlpha * 0.8f);
        buffer.vertex(matrix, x, y + height * 0.7f, z - halfWidth).color(r, g, b, topAlpha * 0.8f);

        buffer.vertex(matrix, x, y + height * 0.7f, z - halfWidth).color(r, g, b, topAlpha * 0.8f);
        buffer.vertex(matrix, x, y + height * 0.7f, z + halfWidth).color(r, g, b, topAlpha * 0.8f);
        buffer.vertex(matrix, x, y, z + halfWidth).color(r, g, b, bottomAlpha);
        buffer.vertex(matrix, x, y, z - halfWidth).color(r, g, b, bottomAlpha);
    }

    private static void renderTrails(WorldRenderContext context, Vec3d cameraPos, MatrixStack matrices) {
        if (ACTIVE_TRAILS.isEmpty()) return;

        long now = System.currentTimeMillis();
        boolean needsUpdate = (now - lastGeometryUpdate) > GEOMETRY_UPDATE_INTERVAL;
        if (needsUpdate) lastGeometryUpdate = now;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();

        BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        boolean hasVertices = false;

        for (TrailData trail : ACTIVE_TRAILS.values()) {
            if (trail.points.size() < 2) continue;

            List<Vec3d> simplified = simplifyTrail(trail.points);
            float minDistance = minDistanceToTrail(cameraPos, simplified);
            if (minDistance > TRAIL_LOD_DISTANCE) continue;

            if (renderTrailToBuffer(buffer, matrix, trail, needsUpdate, cameraPos)) {
                hasVertices = true;
            }
        }

        if (hasVertices) {
            RenderSystem.setShader(GameRenderer::getPositionColorProgram);
            BufferRenderer.drawWithGlobalProgram(buffer.end());
        }

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static boolean renderTrailToBuffer(BufferBuilder buffer, Matrix4f matrix, TrailData trail, boolean updateGeometry, Vec3d cameraPos) {
        String cacheKey = trail.playerId;
        CachedGeometry cached = GEOMETRY_CACHE.get(cacheKey);

        if (cached == null || updateGeometry) {
            List<Vector3f> vertices = new ArrayList<>();
            List<Vec3d> simplified = simplifyTrail(trail.points);
            if (simplified.size() < 2) return false;

            for (int i = 0; i < simplified.size() - 1; i++) {
                Vec3d current = simplified.get(i);
                Vec3d next = simplified.get(i + 1);

                Vec3d toCamera = cameraPos.subtract(current).normalize();
                Vec3d direction = next.subtract(current).normalize();
                Vec3d right = direction.crossProduct(toCamera).normalize().multiply(TRAIL_WIDTH * 0.5);

                // четыре вершины полосы
                vertices.add(new Vector3f((float)(current.x - right.x), (float)current.y, (float)(current.z - right.z)));
                vertices.add(new Vector3f((float)(current.x + right.x), (float)current.y, (float)(current.z + right.z)));
                vertices.add(new Vector3f((float)(next.x + right.x), (float)next.y, (float)(next.z + right.z)));
                vertices.add(new Vector3f((float)(next.x - right.x), (float)next.y, (float)(next.z - right.z)));
            }
            cached = new CachedGeometry(vertices);
            GEOMETRY_CACHE.put(cacheKey, cached);
        }

        boolean hasVertices = false;
        if (!cached.vertices.isEmpty()) {
            for (int i = 0; i < cached.vertices.size() - 3; i += 4) {
                Vector3f v1 = cached.vertices.get(i);
                Vector3f v2 = cached.vertices.get(i + 1);
                Vector3f v3 = cached.vertices.get(i + 2);
                Vector3f v4 = cached.vertices.get(i + 3);

                float alpha = 1f - (i / 4f) / (cached.vertices.size() / 4f);
                alpha *= 0.6f;

                buffer.vertex(matrix, v1.x, v1.y, v1.z).color(0.9f, 0.3f, 0.3f, alpha);
                buffer.vertex(matrix, v2.x, v2.y, v2.z).color(0.9f, 0.3f, 0.3f, alpha);
                buffer.vertex(matrix, v3.x, v3.y, v3.z).color(0.9f, 0.3f, 0.3f, alpha);

                buffer.vertex(matrix, v1.x, v1.y, v1.z).color(0.9f, 0.3f, 0.3f, alpha);
                buffer.vertex(matrix, v3.x, v3.y, v3.z).color(0.9f, 0.3f, 0.3f, alpha);
                buffer.vertex(matrix, v4.x, v4.y, v4.z).color(0.9f, 0.3f, 0.3f, alpha);

                hasVertices = true;
            }
        }
        return hasVertices;
    }

    private static List<Vec3d> simplifyTrail(List<Vec3d> points) {
        if (points.size() <= 2) return points;
        ArrayList<Vec3d> simplified = new ArrayList<>();
        simplified.add(points.get(0));
        float minDistance = 2f;
        Vec3d lastAdded = points.get(0);

        for (int i = 1; i < points.size() - 1; i++) {
            Vec3d current = points.get(i);
            if (current.distanceTo(lastAdded) >= minDistance) {
                simplified.add(current);
                lastAdded = current;
            }
        }
        simplified.add(points.get(points.size() - 1));
        return simplified;
    }

    private static float minDistanceToTrail(Vec3d cameraPos, List<Vec3d> points) {
        if (points.isEmpty()) return Float.MAX_VALUE;
        float min = Float.MAX_VALUE;
        int step = Math.max(1, points.size() / 16);
        for (int i = 0; i < points.size(); i += step) {
            float d = (float) cameraPos.distanceTo(points.get(i));
            if (d < min) min = d;
        }
        return min;
    }

    public static void clearAll() {
        ACTIVE_MARKERS.clear();
        ACTIVE_TRAILS.clear();
        GEOMETRY_CACHE.clear();
    }
}