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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    
    /**
     * Создает payload с использованием чанковых бакетов
     */
    public static ParallelPlayersPayload buildBucketed(ServerPlayerEntity me) {
        var server = me.getServer();
        var w = (ServerWorld) me.getWorld();
        var idx = PlayerBuckets.index(server); // построено 1–2 раза в секунду
        var cp = new ChunkPos(me.getBlockPos());
        var near = PlayerBuckets.nearby(w, cp, 3, idx); // ~96 блоков
        
        List<Point> points = new ArrayList<>();
        for (ServerPlayerEntity p : near) {
            if (p == me) continue; // не включаем себя
            if (p.getPos().squaredDistanceTo(me.getPos()) > 96 * 96) continue; // ограничиваем дистанцию
            
            String name = p.getGameProfile().getName();
            double x = p.getX();
            double y = p.getY();
            double z = p.getZ();
            byte type = detectActivityType(p);
            
            points.add(new Point(name, x, y, z, type));
        }
        
        // Ограничиваем до 16 ближайших игроков
        if (points.size() > 16) {
            points = points.subList(0, 16);
        }
        
        return new ParallelPlayersPayload(points);
    }
    
    /**
     * Определяет тип активности игрока
     */
    private static byte detectActivityType(ServerPlayerEntity p) {
        try {
            var main = p.getMainHandStack();
            if (p.isUsingItem()) return 7; // craft/use
            if (p.isSprinting()) return 4; // move
            if (p.isSwimming() || p.isSubmergedInWater()) return 4; // move
            // грубая проверка "копает": держит кирку/лопату
            if (main.getItem().toString().contains("pickaxe") || 
                main.getItem().toString().contains("shovel")) return 1; // mine
            return 0; // default
        } catch (Throwable ignored) { 
            return 0; 
        }
    }
    
    /**
     * Детерминированный хэш для дедупликации
     */
    public int stableHash() {
        return java.util.Objects.hash(points.size(), 
            points.stream().mapToInt(p -> java.util.Objects.hash(p.name(), p.x(), p.y(), p.z(), p.type())).sum());
    }
    
    /**
     * Отправляет payload игроку
     */
    public void sendTo(ServerPlayerEntity player) {
        try {
            ServerPlayNetworking.send(player, this);
        } catch (Exception e) {
            // Игнорируем ошибки отправки
        }
    }
}


