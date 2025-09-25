package race.server.world;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.level.storage.LevelStorage;

import java.lang.reflect.Field;

public final class WorldSessionReflect {
    private static volatile Field SERVERWORLD_SESSION_FIELD = null;
    private static volatile Field SERVERWORLD_CHUNKMANAGER_FIELD = null;
    private static volatile Field CHUNKMANAGER_SESSION_FIELD = null;

    public static LevelStorage.Session of(ServerWorld world) {
        LevelStorage.Session s = tryDirect(world);
        if (s != null) return s;
        s = tryViaChunkManager(world);
        if (s != null) return s;
        throw new IllegalStateException("Could not obtain LevelStorage.Session from ServerWorld or its chunk manager");
    }

    private static LevelStorage.Session tryDirect(ServerWorld world) {
        try {
            if (SERVERWORLD_SESSION_FIELD == null) {
                for (Field f : ServerWorld.class.getDeclaredFields()) {
                    if (f.getType() == LevelStorage.Session.class) {
                        f.setAccessible(true);
                        SERVERWORLD_SESSION_FIELD = f;
                        break;
                    }
                }
            }
            if (SERVERWORLD_SESSION_FIELD != null) {
                return (LevelStorage.Session) SERVERWORLD_SESSION_FIELD.get(world);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static LevelStorage.Session tryViaChunkManager(ServerWorld world) {
        try {
            if (SERVERWORLD_CHUNKMANAGER_FIELD == null) {
                SERVERWORLD_CHUNKMANAGER_FIELD = ServerWorld.class.getDeclaredField("chunkManager");
                SERVERWORLD_CHUNKMANAGER_FIELD.setAccessible(true);
            }
            Object mgr = SERVERWORLD_CHUNKMANAGER_FIELD.get(world);
            if (mgr == null) return null;

            if (CHUNKMANAGER_SESSION_FIELD != null) {
                return (LevelStorage.Session) CHUNKMANAGER_SESSION_FIELD.get(mgr);
            }
            for (Field f : mgr.getClass().getDeclaredFields()) {
                if (f.getType() == LevelStorage.Session.class) {
                    f.setAccessible(true);
                    CHUNKMANAGER_SESSION_FIELD = f;
                    return (LevelStorage.Session) f.get(mgr);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private WorldSessionReflect() {}
}
