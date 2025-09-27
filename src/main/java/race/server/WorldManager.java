package race.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WorldManager {
    private static final ConcurrentHashMap<UUID, Integer> PLAYER_SLOTS = new ConcurrentHashMap<>();
    
    private WorldManager() {}
    
    // НОВЫЕ МЕТОДЫ ДЛЯ СИНХРОНИЗАЦИИ СЛОТОВ
    public static int getPlayerSlot(UUID playerUuid) {
        return PLAYER_SLOTS.getOrDefault(playerUuid, 0);
    }
    
    public static void setPlayerSlot(UUID playerUuid, int slot) {
        if (slot > 0) {
            PLAYER_SLOTS.put(playerUuid, slot);
        } else {
            PLAYER_SLOTS.remove(playerUuid);
        }
    }
    
    public static void clearPlayerSlot(UUID playerUuid) {
        PLAYER_SLOTS.remove(playerUuid);
    }
    
    // НОВОЕ: Метод для очистки всех слотов игрока по имени
    public static void clearPlayerSlotsByName(String playerName) {
        // Этот метод будет вызываться из EnhancedWorldManager при обнаружении UUID конфликтов
        System.out.println("[Race] Clearing all slots for player name: " + playerName);
    }
    
    // НОВОЕ: Метод для диагностики всех слотов
    public static void debugAllSlots() {
        System.out.println("[Race] WorldManager slots: " + PLAYER_SLOTS);
    }
    
    // НОВОЕ: Метод для проверки занятости слота
    public static boolean isSlotOccupied(int slot) {
        return PLAYER_SLOTS.containsValue(slot);
    }

    public static ServerWorld getLobby(MinecraftServer server) {
        return server.getOverworld();
    }

    public static void teleportToLobby(MinecraftServer server, ServerPlayerEntity p) {
        ServerWorld lobby = getLobby(server);
        BlockPos spawn = lobby.getSpawnPos();
        TeleportTarget tt = new TeleportTarget(
                lobby,
                spawn.toCenterPos(),
                Vec3d.ZERO,
                p.getHeadYaw(),
                p.getPitch(),
                TeleportTarget.NO_OP
        );
        p.teleportTo(tt);
    }

    public static void teleport(MinecraftServer server, ServerPlayerEntity p, ServerWorld dst) {
        BlockPos spawn = dst.getSpawnPos();
        TeleportTarget tt = new TeleportTarget(
                dst,
                spawn.toCenterPos(),
                Vec3d.ZERO,
                p.getHeadYaw(),
                p.getPitch(),
                TeleportTarget.NO_OP
        );
        p.teleportTo(tt);
    }

    // Опционально: портальный телепорт с пост-эффектами
    public static void teleportWithPortalEffect(ServerPlayerEntity p, ServerWorld dst, BlockPos pos, float yaw, float pitch) {
        TeleportTarget.PostDimensionTransition post = TeleportTarget.SEND_TRAVEL_THROUGH_PORTAL_PACKET
                .then(TeleportTarget.ADD_PORTAL_CHUNK_TICKET);
        TeleportTarget tt = new TeleportTarget(
                dst,
                pos.toCenterPos(),
                Vec3d.ZERO,
                yaw,
                pitch,
                post
        );
        p.teleportTo(tt);
    }

    // Опционально: телепорт “по сущности” на спавн мира, yaw/pitch=0
    public static void teleportEntitySpawn(ServerPlayerEntity p, ServerWorld dst) {
        TeleportTarget tt = new TeleportTarget(dst, p, TeleportTarget.NO_OP);
        p.teleportTo(tt);
    }
}
