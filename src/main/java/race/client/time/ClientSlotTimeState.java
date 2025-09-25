package race.client.time;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кэш виртуального времени на клиенте.
 * Хранит время для каждого мира отдельно, обеспечивая синхронизацию
 * между всеми игроками в одном и том же мире.
 */
public final class ClientSlotTimeState {
    private static final Map<String, Long> TIME = new ConcurrentHashMap<>();
    
    /**
     * Сохранить виртуальное время для мира
     */
    public static void put(String worldId, long t) {
        TIME.put(worldId, t);
    }
    
    /**
     * Получить виртуальное время для мира
     */
    public static long get(String worldId, long def) {
        return TIME.getOrDefault(worldId, def);
    }
    
    /**
     * Очистить кэш (при отключении от сервера)
     */
    public static void clear() {
        TIME.clear();
    }
    
    private ClientSlotTimeState() {}
}
