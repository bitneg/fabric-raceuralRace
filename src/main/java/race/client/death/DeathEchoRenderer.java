package race.client.death;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Рендер дальнобойных маркеров смерти и длинных шлейфов без падения FPS
 */
public final class DeathEchoRenderer {
    private DeathEchoRenderer() {}
    
    // Активные маркеры смерти
    private static final Map<String, ActiveMarker> ACTIVE_MARKERS = new ConcurrentHashMap<>();
    private static final int MAX_MARKERS = 8; // Ограничение для производительности
    private static final long MARKER_LIFETIME_MS = 10000L; // 10 секунд
    
    // Активные шлейфы
    private static final Map<String, TrailData> ACTIVE_TRAILS = new ConcurrentHashMap<>();
    private static final int MAX_TRAIL_POINTS = 48;
    private static final float TRAIL_WIDTH = 0.4f;
    private static final long TRAIL_LIFETIME_MS = 15000L; // 15 секунд
    
    // Кэш геометрии для производительности
    private static final Map<String, CachedGeometry> GEOMETRY_CACHE = new ConcurrentHashMap<>();
    private static long lastGeometryUpdate = 0;
    private static final long GEOMETRY_UPDATE_INTERVAL = 100; // 10 раз в секунду
    
    // Клиентский эмиттер дымки
    private static long lastSmokeUpdate = 0;
    private static final long SMOKE_UPDATE_INTERVAL = 100; // 10 раз в секунду
    private static final float SMOKE_DISTANCE_LIMIT = 128f; // Максимальное расстояние для дымки
    private static final int MAX_SMOKE_PARTICLES_PER_PLAYER = 20; // Максимум частиц на игрока
    private static final int MAX_TOTAL_SMOKE_PARTICLES = 240; // Общий лимит частиц в секунду
    private static int currentSmokeParticles = 0;
    
    public static class ActiveMarker {
        public final Vec3d pos;
        public final float r, g, b;
        public final long startTime;
        public final long endTime;
        public final String id;
        
        public ActiveMarker(Vec3d pos, float r, float g, float b, float durationSec) {
            this.pos = pos;
            this.r = r;
            this.g = g;
            this.b = b;
            this.startTime = System.currentTimeMillis();
            this.endTime = startTime + (long)(durationSec * 1000);
            this.id = UUID.randomUUID().toString();
        }
        
        public float getAlpha() {
            long now = System.currentTimeMillis();
            if (now >= endTime) return 0f;
            
            float progress = (now - startTime) / (float)(endTime - startTime);
            // Синус-затухание для плавности
            return MathHelper.sin(progress * MathHelper.PI) * 0.8f;
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() >= endTime;
        }
    }
    
    public static class TrailData {
        public final String playerId;
        public final String cause;
        public final List<Vec3d> points;
        public final long startTime;
        public final long endTime;
        
        public TrailData(String playerId, String cause, List<Vec3d> points) {
            this.playerId = playerId;
            this.cause = cause;
            this.points = new ArrayList<>(points);
            this.startTime = System.currentTimeMillis();
            this.endTime = startTime + TRAIL_LIFETIME_MS;
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() >= endTime;
        }
    }
    
    public static class CachedGeometry {
        public final List<Vector3f> vertices;
        public final List<Integer> indices;
        public final long lastUpdate;
        
        public CachedGeometry(List<Vector3f> vertices, List<Integer> indices) {
            this.vertices = vertices;
            this.indices = indices;
            this.lastUpdate = System.currentTimeMillis();
        }
    }
    

    
    public static void addTrail(String playerId, String cause, List<race.net.GhostTrailPayload.Point> points) {
        List<Vec3d> vecPoints = points.stream()
                .map(p -> new Vec3d(p.x(), p.y(), p.z()))
                .toList();
        
        TrailData trail = new TrailData(playerId, cause, vecPoints);
        ACTIVE_TRAILS.put(playerId, trail);
    }
    
    public static void render(WorldRenderContext context) {
        MinecraftClient client = context.gameRenderer().getClient();
        if (client.world == null || client.player == null) return;
        
        Vec3d cameraPos = context.camera().getPos();
        MatrixStack matrices = context.matrixStack();
        
        // Очищаем устаревшие маркеры и шлейфы
        ACTIVE_MARKERS.entrySet().removeIf(entry -> entry.getValue().isExpired());
        ACTIVE_TRAILS.entrySet().removeIf(entry -> entry.getValue().isExpired());
        
        // Рендерим маркеры
        renderMarkers(context, cameraPos, matrices);
        
        // Рендерим шлейфы
        renderTrails(context, cameraPos, matrices);
    }
    
    private static void renderMarkers(WorldRenderContext context, Vec3d cameraPos, MatrixStack matrices) {
        if (ACTIVE_MARKERS.isEmpty()) return;
        
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        
        // Один BufferBuilder для всех маркеров
        BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        boolean hasVertices = false;
        
        for (ActiveMarker marker : ACTIVE_MARKERS.values()) {
            float distance = (float) cameraPos.distanceTo(marker.pos);
            
            // LOD: скрываем на большом расстоянии
            if (distance > 256f) continue;
            
            float alpha = marker.getAlpha();
            if (alpha <= 0f) continue;
            
            // Уменьшаем альфу и размер на расстоянии
            float lodAlpha = alpha;
            float height = 32f; // Базовая высота
            
            if (distance > 96f) {
                lodAlpha *= MathHelper.clamp(1f - (distance - 96f) / 160f, 0.4f, 1f);
                height *= MathHelper.clamp(1f - (distance - 96f) / 160f, 0.6f, 1f);
            }
            
            if (lodAlpha <= 0.01f) continue;
            
            renderMarkerBeamToBuffer(buffer, matrix, marker, height, lodAlpha);
            hasVertices = true;
        }
        
        // Рендерим только если есть вершины
        if (hasVertices) {
            RenderSystem.setShader(net.minecraft.client.render.GameRenderer::getPositionColorProgram);
            BufferRenderer.drawWithGlobalProgram(buffer.end());
        }
        
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }
    
    private static void renderMarkerBeamToBuffer(BufferBuilder buffer, Matrix4f matrix, ActiveMarker marker, float height, float alpha) {
        Vec3d pos = marker.pos;
        float r = marker.r;
        float g = marker.g;
        float b = marker.b;
        
        // Используем мировые координаты (матрица уже содержит трансформацию камеры)
        float x = (float) pos.x;
        float y = (float) pos.y;
        float z = (float) pos.z;
        
        // Упрощённый крест X без вычисления yaw - дешевле и стабильнее
        float halfWidth = 0.5f;
        float topAlpha = alpha;
        float bottomAlpha = alpha * 0.3f;
        
        // Первый билборд (вдоль X)
        buffer.vertex(matrix, x - halfWidth, y + height, z).color(r, g, b, topAlpha);
        buffer.vertex(matrix, x + halfWidth, y + height, z).color(r, g, b, topAlpha);
        buffer.vertex(matrix, x + halfWidth, y + height * 0.7f, z).color(r, g, b, topAlpha * 0.8f);
        buffer.vertex(matrix, x - halfWidth, y + height * 0.7f, z).color(r, g, b, topAlpha * 0.8f);
        
        buffer.vertex(matrix, x - halfWidth, y + height * 0.7f, z).color(r, g, b, topAlpha * 0.8f);
        buffer.vertex(matrix, x + halfWidth, y + height * 0.7f, z).color(r, g, b, topAlpha * 0.8f);
        buffer.vertex(matrix, x + halfWidth, y, z).color(r, g, b, bottomAlpha);
        buffer.vertex(matrix, x - halfWidth, y, z).color(r, g, b, bottomAlpha);
        
        // Второй билборд (вдоль Z)
        buffer.vertex(matrix, x, y + height, z - halfWidth).color(r, g, b, topAlpha);
        buffer.vertex(matrix, x, y + height, z + halfWidth).color(r, g, b, topAlpha);
        buffer.vertex(matrix, x, y + height * 0.7f, z + halfWidth).color(r, g, b, topAlpha * 0.8f);
        buffer.vertex(matrix, x, y + height * 0.7f, z - halfWidth).color(r, g, b, topAlpha * 0.8f);
        
        buffer.vertex(matrix, x, y + height * 0.7f, z - halfWidth).color(r, g, b, topAlpha * 0.8f);
        buffer.vertex(matrix, x, y + height * 0.7f, z + halfWidth).color(r, g, b, topAlpha * 0.8f);
        buffer.vertex(matrix, x, y, z + halfWidth).color(r, g, b, bottomAlpha);
        buffer.vertex(matrix, x, y, z - halfWidth).color(r, g, b, bottomAlpha);
    }
    
    private static void renderBillboard(WorldRenderContext context, float height, float r, float g, float b, float alpha, MatrixStack matrices) {
        BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        RenderLayer renderLayer = RenderLayer.getTranslucent();
        
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        
        // Создаем градиентный прямоугольник
        float halfWidth = 0.5f;
        float topAlpha = alpha;
        float bottomAlpha = alpha * 0.3f;
        
        // Верхняя часть
        buffer.vertex(matrix, -halfWidth, height, 0).color(r, g, b, topAlpha);
        buffer.vertex(matrix, halfWidth, height, 0).color(r, g, b, topAlpha);
        buffer.vertex(matrix, halfWidth, height * 0.7f, 0).color(r, g, b, topAlpha * 0.8f);
        buffer.vertex(matrix, -halfWidth, height * 0.7f, 0).color(r, g, b, topAlpha * 0.8f);
        
        // Нижняя часть
        buffer.vertex(matrix, -halfWidth, height * 0.7f, 0).color(r, g, b, topAlpha * 0.8f);
        buffer.vertex(matrix, halfWidth, height * 0.7f, 0).color(r, g, b, topAlpha * 0.8f);
        buffer.vertex(matrix, halfWidth, 0, 0).color(r, g, b, bottomAlpha);
        buffer.vertex(matrix, -halfWidth, 0, 0).color(r, g, b, bottomAlpha);
        
        BufferRenderer.drawWithGlobalProgram(buffer.end());
    }
    
    private static void renderTrails(WorldRenderContext context, Vec3d cameraPos, MatrixStack matrices) {
        if (ACTIVE_TRAILS.isEmpty()) return;
        
        long now = System.currentTimeMillis();
        boolean needsUpdate = (now - lastGeometryUpdate) > GEOMETRY_UPDATE_INTERVAL;
        
        if (needsUpdate) {
            lastGeometryUpdate = now;
        }
        
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        
        // Один BufferBuilder для всех шлейфов
        BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        boolean hasVertices = false;
        
        for (TrailData trail : ACTIVE_TRAILS.values()) {
            if (trail.points.size() < 2) continue;
            
            // Используем минимальную дистанцию по упрощённой полилинии
            List<Vec3d> simplified = simplifyTrail(trail.points);
            float minDistance = minDistanceToTrail(cameraPos, simplified);
            if (minDistance > 128f) continue; // LOD для шлейфов
            
            if (renderTrailToBuffer(buffer, matrix, trail, needsUpdate, cameraPos)) {
                hasVertices = true;
            }
        }
        
        // Рендерим только если есть вершины
        if (hasVertices) {
            RenderSystem.setShader(net.minecraft.client.render.GameRenderer::getPositionColorProgram);
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
            
            // Упрощаем траекторию
            List<Vec3d> simplified = simplifyTrail(trail.points);
            
            if (simplified.size() < 2) return false;
            
            // Создаем полосы между точками
            for (int i = 0; i < simplified.size() - 1; i++) {
                Vec3d current = simplified.get(i);
                Vec3d next = simplified.get(i + 1);
                
                // Вычисляем направление к камере для камера-фейсинга
                Vec3d toCamera = cameraPos.subtract(current).normalize();
                Vec3d direction = next.subtract(current).normalize();
                Vec3d right = direction.crossProduct(toCamera).normalize().multiply(TRAIL_WIDTH * 0.5);
                
                float alpha = 1f - (i / (float)(simplified.size() - 1));
                alpha *= 0.6f; // Общая прозрачность шлейфа
                
                // Добавляем вершины для полосы
                vertices.add(new Vector3f((float)(current.x - right.x), (float)current.y, (float)(current.z - right.z)));
                vertices.add(new Vector3f((float)(current.x + right.x), (float)current.y, (float)(current.z + right.z)));
                vertices.add(new Vector3f((float)(next.x + right.x), (float)next.y, (float)(next.z + right.z)));
                vertices.add(new Vector3f((float)(next.x - right.x), (float)next.y, (float)(next.z - right.z)));
            }
            
            cached = new CachedGeometry(vertices, new ArrayList<>());
            GEOMETRY_CACHE.put(cacheKey, cached);
        }
        
        // Рендерим кэшированную геометрию - 6 вершин на сегмент (2 треугольника)
        boolean hasVertices = false;
        if (!cached.vertices.isEmpty()) {
            for (int i = 0; i < cached.vertices.size() - 3; i += 4) {
                if (i + 3 < cached.vertices.size()) {
                    Vector3f v1 = cached.vertices.get(i);
                    Vector3f v2 = cached.vertices.get(i + 1);
                    Vector3f v3 = cached.vertices.get(i + 2);
                    Vector3f v4 = cached.vertices.get(i + 3);
                    
                    float alpha = 1f - (i / 4f) / (cached.vertices.size() / 4f);
                    alpha *= 0.6f;
                    
                    // Первый треугольник (мировые координаты)
                    buffer.vertex(matrix, v1.x, v1.y, v1.z).color(0.9f, 0.3f, 0.3f, alpha);
                    buffer.vertex(matrix, v2.x, v2.y, v2.z).color(0.9f, 0.3f, 0.3f, alpha);
                    buffer.vertex(matrix, v3.x, v3.y, v3.z).color(0.9f, 0.3f, 0.3f, alpha);
                    
                    // Второй треугольник (мировые координаты)
                    buffer.vertex(matrix, v1.x, v1.y, v1.z).color(0.9f, 0.3f, 0.3f, alpha);
                    buffer.vertex(matrix, v3.x, v3.y, v3.z).color(0.9f, 0.3f, 0.3f, alpha);
                    buffer.vertex(matrix, v4.x, v4.y, v4.z).color(0.9f, 0.3f, 0.3f, alpha);
                    
                    hasVertices = true;
                }
            }
        }
        
        return hasVertices;
    }
    
    private static List<Vec3d> simplifyTrail(List<Vec3d> points) {
        if (points.size() <= 2) return points;
        
        List<Vec3d> simplified = new ArrayList<>();
        simplified.add(points.get(0));
        
        float minDistance = 2f; // Минимальное расстояние между точками
        Vec3d lastAdded = points.get(0);
        
        for (int i = 1; i < points.size() - 1; i++) {
            Vec3d current = points.get(i);
            if (current.distanceTo(lastAdded) >= minDistance) {
                simplified.add(current);
                lastAdded = current;
            }
        }
        
        // Всегда добавляем последнюю точку
        if (points.size() > 1) {
            simplified.add(points.get(points.size() - 1));
        }
        
        return simplified;
    }
    
    /**
     * Вычисляет минимальную дистанцию от камеры до шлейфа
     */
    private static float minDistanceToTrail(Vec3d cameraPos, List<Vec3d> points) {
        if (points.isEmpty()) return Float.MAX_VALUE;
        
        float minDistance = Float.MAX_VALUE;
        int step = Math.max(1, points.size() / 16); // До 16 выборок для производительности
        
        for (int i = 0; i < points.size(); i += step) {
            float distance = (float) cameraPos.distanceTo(points.get(i));
            if (distance < minDistance) {
                minDistance = distance;
            }
        }
        
        return minDistance;
    }
    
    private static void renderTrailGeometry(WorldRenderContext context, CachedGeometry geometry, MatrixStack matrices) {
        BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
        RenderLayer renderLayer = RenderLayer.getTranslucent();
        
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        
        // Рендерим вершины с красноватым цветом
        for (Vector3f vertex : geometry.vertices) {
            buffer.vertex(matrix, vertex.x, vertex.y, vertex.z)
                    .color(0.9f, 0.3f, 0.3f, 0.4f);
        }
        
        // Рендерим индексы
        for (int index : geometry.indices) {
            buffer.vertex(matrix, geometry.vertices.get(index).x, geometry.vertices.get(index).y, geometry.vertices.get(index).z)
                    .color(0.9f, 0.3f, 0.3f, 0.4f);
        }
        
        BufferRenderer.drawWithGlobalProgram(buffer.end());
    }
    
    public static void clearAll() {
        ACTIVE_MARKERS.clear();
        ACTIVE_TRAILS.clear();
        GEOMETRY_CACHE.clear();
        currentSmokeParticles = 0;
    }
    
    /**
     * Клиентский тик для спавна дымки рядом с камерой
     */
    public static void clientTick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null || ACTIVE_TRAILS.isEmpty()) {
            return;
        }
        
        long now = System.currentTimeMillis();
        if (now - lastSmokeUpdate < SMOKE_UPDATE_INTERVAL) {
            return;
        }
        lastSmokeUpdate = now;
        
        // Сбрасываем счетчик частиц каждую секунду
        if (now % 1000 < SMOKE_UPDATE_INTERVAL) {
            currentSmokeParticles = 0;
        }
        
        if (currentSmokeParticles >= MAX_TOTAL_SMOKE_PARTICLES) {
            return; // Достигнут лимит частиц
        }
        
        Vec3d cameraPos = client.player.getPos();
        
        // Ограничиваем количество обрабатываемых треков для производительности
        List<TrailData> sortedTrails = ACTIVE_TRAILS.values().stream()
            .filter(trail -> !trail.points.isEmpty())
            .sorted((a, b) -> {
                float distA = minDistanceToTrail(cameraPos, simplifyTrail(a.points));
                float distB = minDistanceToTrail(cameraPos, simplifyTrail(b.points));
                return Float.compare(distA, distB);
            })
            .limit(8) // Максимум 8 ближайших треков за тик
            .collect(java.util.stream.Collectors.toList());
        
        // Спавним дымку для ближайших треков
        for (TrailData trail : sortedTrails) {
            if (trail.points.isEmpty()) continue;
            
            // Используем упрощённую траекторию для производительности
            List<Vec3d> path = simplifyTrail(trail.points);
            
            // Проходим по сегментам траектории
            for (int i = 0; i < path.size() - 1; i++) {
                Vec3d a = path.get(i);
                Vec3d b = path.get(i + 1);
                double segLen = a.distanceTo(b);
                if (segLen < 0.5) continue;
                
                // Проверяем расстояние до сегмента
                double camD = Math.min(cameraPos.distanceTo(a), cameraPos.distanceTo(b));
                if (camD > SMOKE_DISTANCE_LIMIT) continue;
                
                // Плотность от расстояния до камеры
                int k = (camD < 32 ? 6 : camD < 64 ? 4 : camD < 128 ? 2 : 1);
                k = Math.min(k, 8);
                
                for (int j = 0; j < k; j++) {
                    if (currentSmokeParticles >= MAX_TOTAL_SMOKE_PARTICLES) break;
                    
                    double t = (j + 0.25) / k;
                    double px = a.x + (b.x - a.x) * t;
                    double py = a.y + (b.y - a.y) * t + 0.05;
                    double pz = a.z + (b.z - a.z) * t;
                    
                    // Размер и альфа от расстояния
                    float size = (float)(camD < 32 ? 1.2 : camD < 64 ? 1.0 : camD < 128 ? 0.8 : 0.6);
                    float alpha = (float)(camD < 64 ? 1.0 : camD < 128 ? 0.7 : 0.45);
                    
                    // Цвет градиентом от красного к оранжевому к концу
                    float tcol = (float)(i / (float)(path.size() - 1));
                    float r = 1.0f;
                    float g = MathHelper.lerp(tcol, 0.2f, 0.6f);
                    float bcol = 0.2f;
                    
                    client.world.addParticle(
                        new net.minecraft.particle.DustParticleEffect(
                            new org.joml.Vector3f(r, g, bcol), size
                        ),
                        px, py, pz,
                        0.0, 0.01, 0.0
                    );
                    currentSmokeParticles++;
                }
                
                if (currentSmokeParticles >= MAX_TOTAL_SMOKE_PARTICLES) break;
            }
        }
    }
}
