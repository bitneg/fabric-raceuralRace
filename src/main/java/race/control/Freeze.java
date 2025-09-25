package race.control;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import java.util.*;

public final class Freeze {
    private static final Map<UUID, Vec3d> locked = new HashMap<>();
    private static final double THRESH = 0.75;
    private static int cadence = 10; // проверка раз в 10 тиков

    public static void lock(ServerPlayerEntity p) { 
        locked.put(p.getUuid(), p.getPos()); 
    }
    
    public static void unlock(ServerPlayerEntity p) { 
        locked.remove(p.getUuid()); 
    }

    public static void tick(List<ServerPlayerEntity> players, int tick) {
        if (tick % cadence != 0) return;
        for (ServerPlayerEntity p : players) {
            Vec3d want = locked.get(p.getUuid());
            if (want == null) continue;
            if (p.getPos().squaredDistanceTo(want) > THRESH*THRESH) {
                p.networkHandler.requestTeleport(want.x, want.y, want.z, p.getYaw(), p.getPitch());
            }
        }
    }
}
