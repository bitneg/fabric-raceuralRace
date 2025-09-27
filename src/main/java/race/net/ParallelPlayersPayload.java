package race.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import race.spatial.PlayerBuckets;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Пакет параллельных игроков поблизости.
 * Изменения:
 * - buildBucketed теперь отдаёт мини‑траекторию для каждого игрока (не 1 точку),
 *   используя серверный кратковременный кэш последних позиций, чтобы клиент сразу рисовал шлейф (>=2 точки).
 */
public record ParallelPlayersPayload(List<ParallelPlayersPayload.Point> points) implements CustomPayload {
    public static final Id<ParallelPlayersPayload> ID = new Id<>(Identifier.of("fabric_race", "parallel_players"));

    public static final PacketCodec<RegistryByteBuf, Point> POINT_CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, Point::name,
            PacketCodecs.DOUBLE, Point::x,
            PacketCodecs.DOUBLE, Point::y,
            PacketCodecs.DOUBLE, Point::z,
            PacketCodecs.BYTE, Point::type,
            Point::new
    );

    public static final PacketCodec<RegistryByteBuf, ParallelPlayersPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.collection(ArrayList::new, POINT_CODEC), ParallelPlayersPayload::points,
            ParallelPlayersPayload::new
    );

    // type: 0 default, 1 mine, 2 place, 3 fight, 4 move, 5 chest, 6 portal, 7 craft
    public record Point(String name, double x, double y, double z, byte type) {}

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }

    // --- Серверный кэш последних позиций для мини‑траекторий ---
    // ключ: player UUID string -> фиксированная очередь последних позиций (до 12)
    private static final Map<String, ArrayDeque<double[]>> LIVE_TRAILS = new ConcurrentHashMap<>();
    private static final int LIVE_TRAIL_MAX = 12; // 6–12 точек достаточно для короткого шлейфа
    // ограничение дистанции — такие же 96 блоков как в nearby
    private static final double MAX_DIST_SQ = 96.0 * 96.0;

    /**
     * Создает payload с использованием чанковых бакетов и добавляет мини‑траектории,
     * чтобы на клиенте сразу было >= 2 точек для шлейфа.
     */
    public static ParallelPlayersPayload buildBucketed(ServerPlayerEntity me) {
        var server = me.getServer();
        var w = (ServerWorld) me.getWorld();
        var idx = PlayerBuckets.index(server);
        var cp = new ChunkPos(me.getBlockPos());
        var near = PlayerBuckets.nearby(w, cp, 3, idx); // ~96 блоков

        // Готовим точки пачками по игрокам
        ArrayList<Point> out = new ArrayList<>();

        long now = System.currentTimeMillis();
        for (ServerPlayerEntity p : near) {
            if (p == me) continue;
            if (p.getPos().squaredDistanceTo(me.getPos()) > MAX_DIST_SQ) continue;

            String name = p.getGameProfile().getName();
            double x = p.getX(), y = p.getY(), z = p.getZ();
            byte type = detectActivityType(p);

            // Накапливаем в серверном буфере мини‑траекторию по имени
            ArrayDeque<double[]> dq = LIVE_TRAILS.computeIfAbsent(name, k -> new ArrayDeque<>(LIVE_TRAIL_MAX + 4));
            // Добавляем текущую точку (не дублируем, если та же)
            if (dq.isEmpty() || distSq(dq.peekLast(), x, y, z) > 0.1) {
                dq.addLast(new double[]{x, y, z, type, now});
                while (dq.size() > LIVE_TRAIL_MAX) dq.removeFirst();
            }

            // Копируем 6–12 последних позиций в payload (с конца к началу)
            int take = Math.min(LIVE_TRAIL_MAX, dq.size());
            // Разрежаем, чтобы не забивать пакет
            int step = Math.max(1, take / 6); // целим в ~6 точек
            int i = 0;
            for (double[] pt : dq) {
                if ((i++ % step) != 0) continue;
                out.add(new Point(name, pt[0], pt[1], pt[2], (byte) pt[3]));
            }
        }

        // Ограничиваем общую длину пакета
        if (out.size() > 16 * 6) { // до 16 игроков * ~6 точек
            out = new ArrayList<>(out.subList(0, 16 * 6));
        }

        return new ParallelPlayersPayload(out);
    }

    private static double distSq(double[] a, double x, double y, double z) {
        double dx = a[0] - x, dy = a[1] - y, dz = a[2] - z;
        return dx*dx + dy*dy + dz*dz;
    }

    private static byte detectActivityType(ServerPlayerEntity p) {
        try {
            var main = p.getMainHandStack();
            if (p.isUsingItem()) return 7;
            if (p.isSprinting()) return 4;
            if (p.isSwimming() || p.isSubmergedInWater()) return 4;
            String m = main.getItem().toString();
            if (m.contains("pickaxe") || m.contains("shovel")) return 1;
            return 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public int stableHash() {
        return java.util.Objects.hash(points.size(),
                points.stream().mapToInt(p -> java.util.Objects.hash(p.name(), p.x(), p.y(), p.z(), p.type())).sum());
    }

    public void sendTo(ServerPlayerEntity player) {
        try {
            ServerPlayNetworking.send(player, this);
        } catch (Exception ignored) {}
    }
}