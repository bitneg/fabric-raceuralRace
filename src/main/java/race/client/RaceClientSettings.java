package race.client;

/**
 * Настройки клиента для Race мода
 */
public final class RaceClientSettings {
    private static boolean showSmoke = true;
    
    /**
     * Включена ли дымка от параллельных игроков
     */
    public static boolean isShowSmoke() {
        return showSmoke;
    }
    
    /**
     * Переключить дымку
     */
    public static void toggleSmoke() {
        showSmoke = !showSmoke;
    }
    
    /**
     * Установить состояние дымки
     */
    public static void setShowSmoke(boolean enabled) {
        showSmoke = enabled;
    }
}
