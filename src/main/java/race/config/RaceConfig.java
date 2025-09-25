package race.config;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Система настроек мода для спидран гонок
 */
public final class RaceConfig {
    private static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("fabric_race.properties");
    private static final Properties properties = new Properties();
    
    // HUD настройки
    public static boolean toggleHud = true;
    public static double scaleHud = 1.0;
    public static boolean showMyProgress = true;
    public static boolean showOtherPlayers = true;
    public static boolean showStageProgress = true;
    
    // Звуковые настройки
    public static double startSoundVolume = 1.0;
    public static double endSoundVolume = 1.0;
    public static boolean playSounds = true;
    
    // Игровые настройки
    public static boolean chatAvailable = true;
    public static boolean hideProfile = false;
    public static int maxWorldSave = 10;
    public static int maxReplaySave = 5;
    
    // Настройки гонки
    public static boolean autoStart = false;
    public static boolean showLeaderboard = true;
    public static boolean enableSpectator = true;
    
    // Настройки RNG
    public static boolean enableStandardRNG = true;
    public static boolean enableVanillaFixes = true;
    
    /**
     * Загружает настройки из файла
     */
    public static void load() {
        if (Files.exists(CONFIG_FILE)) {
            try {
                properties.load(Files.newInputStream(CONFIG_FILE));
                loadFromProperties();
            } catch (IOException e) {
                System.err.println("Failed to load config: " + e.getMessage());
            }
        } else {
            save(); // Создаем файл с настройками по умолчанию
        }
    }
    
    /**
     * Сохраняет настройки в файл
     */
    public static void save() {
        try {
            saveToProperties();
            properties.store(Files.newOutputStream(CONFIG_FILE), "Fabric Race Mod Configuration");
        } catch (IOException e) {
            System.err.println("Failed to save config: " + e.getMessage());
        }
    }
    
    /**
     * Загружает настройки из Properties
     */
    private static void loadFromProperties() {
        toggleHud = Boolean.parseBoolean(properties.getProperty("toggleHud", "true"));
        scaleHud = Double.parseDouble(properties.getProperty("scaleHud", "1.0"));
        showMyProgress = Boolean.parseBoolean(properties.getProperty("showMyProgress", "true"));
        showOtherPlayers = Boolean.parseBoolean(properties.getProperty("showOtherPlayers", "true"));
        showStageProgress = Boolean.parseBoolean(properties.getProperty("showStageProgress", "true"));
        
        startSoundVolume = Double.parseDouble(properties.getProperty("startSoundVolume", "1.0"));
        endSoundVolume = Double.parseDouble(properties.getProperty("endSoundVolume", "1.0"));
        playSounds = Boolean.parseBoolean(properties.getProperty("playSounds", "true"));
        
        chatAvailable = Boolean.parseBoolean(properties.getProperty("chatAvailable", "true"));
        hideProfile = Boolean.parseBoolean(properties.getProperty("hideProfile", "false"));
        maxWorldSave = Integer.parseInt(properties.getProperty("maxWorldSave", "10"));
        maxReplaySave = Integer.parseInt(properties.getProperty("maxReplaySave", "5"));
        
        autoStart = Boolean.parseBoolean(properties.getProperty("autoStart", "false"));
        showLeaderboard = Boolean.parseBoolean(properties.getProperty("showLeaderboard", "true"));
        enableSpectator = Boolean.parseBoolean(properties.getProperty("enableSpectator", "true"));
        
        enableStandardRNG = Boolean.parseBoolean(properties.getProperty("enableStandardRNG", "true"));
        enableVanillaFixes = Boolean.parseBoolean(properties.getProperty("enableVanillaFixes", "true"));
    }
    
    /**
     * Сохраняет настройки в Properties
     */
    private static void saveToProperties() {
        properties.setProperty("toggleHud", String.valueOf(toggleHud));
        properties.setProperty("scaleHud", String.valueOf(scaleHud));
        properties.setProperty("showMyProgress", String.valueOf(showMyProgress));
        properties.setProperty("showOtherPlayers", String.valueOf(showOtherPlayers));
        properties.setProperty("showStageProgress", String.valueOf(showStageProgress));
        
        properties.setProperty("startSoundVolume", String.valueOf(startSoundVolume));
        properties.setProperty("endSoundVolume", String.valueOf(endSoundVolume));
        properties.setProperty("playSounds", String.valueOf(playSounds));
        
        properties.setProperty("chatAvailable", String.valueOf(chatAvailable));
        properties.setProperty("hideProfile", String.valueOf(hideProfile));
        properties.setProperty("maxWorldSave", String.valueOf(maxWorldSave));
        properties.setProperty("maxReplaySave", String.valueOf(maxReplaySave));
        
        properties.setProperty("autoStart", String.valueOf(autoStart));
        properties.setProperty("showLeaderboard", String.valueOf(showLeaderboard));
        properties.setProperty("enableSpectator", String.valueOf(enableSpectator));
        
        properties.setProperty("enableStandardRNG", String.valueOf(enableStandardRNG));
        properties.setProperty("enableVanillaFixes", String.valueOf(enableVanillaFixes));
    }
    
    /**
     * Создает опции для настроек
     */
    public static class Options {
        public static final SimpleOption<Boolean> TOGGLE_HUD = SimpleOption.ofBoolean(
                "fabric_race.option.toggleHud",
                toggleHud,
                value -> {
                    toggleHud = value;
                    save();
                }
        );
        
        public static final SimpleOption<Double> SCALE_HUD = new SimpleOption<>(
                "fabric_race.option.scaleHud",
                SimpleOption.emptyTooltip(),
                (optionText, value) -> Text.translatable("fabric_race.option.scaleHud.value", String.format("%.1f", value)),
                SimpleOption.DoubleSliderCallbacks.INSTANCE,
                scaleHud,
                value -> {
                    scaleHud = value;
                    save();
                }
        );
        
        public static final SimpleOption<Boolean> SHOW_MY_PROGRESS = SimpleOption.ofBoolean(
                "fabric_race.option.showMyProgress",
                showMyProgress,
                value -> {
                    showMyProgress = value;
                    save();
                }
        );
        
        public static final SimpleOption<Boolean> SHOW_OTHER_PLAYERS = SimpleOption.ofBoolean(
                "fabric_race.option.showOtherPlayers",
                showOtherPlayers,
                value -> {
                    showOtherPlayers = value;
                    save();
                }
        );
        
        public static final SimpleOption<Boolean> SHOW_STAGE_PROGRESS = SimpleOption.ofBoolean(
                "fabric_race.option.showStageProgress",
                showStageProgress,
                value -> {
                    showStageProgress = value;
                    save();
                }
        );
        
        public static final SimpleOption<Double> START_SOUND_VOLUME = new SimpleOption<>(
                "fabric_race.option.startSoundVolume",
                SimpleOption.emptyTooltip(),
                (optionText, value) -> Text.translatable("fabric_race.option.startSoundVolume.value", String.format("%.1f", value)),
                SimpleOption.DoubleSliderCallbacks.INSTANCE,
                startSoundVolume,
                value -> {
                    startSoundVolume = value;
                    save();
                }
        );
        
        public static final SimpleOption<Double> END_SOUND_VOLUME = new SimpleOption<>(
                "fabric_race.option.endSoundVolume",
                SimpleOption.emptyTooltip(),
                (optionText, value) -> Text.translatable("fabric_race.option.endSoundVolume.value", String.format("%.1f", value)),
                SimpleOption.DoubleSliderCallbacks.INSTANCE,
                endSoundVolume,
                value -> {
                    endSoundVolume = value;
                    save();
                }
        );
        
        public static final SimpleOption<Boolean> PLAY_SOUNDS = SimpleOption.ofBoolean(
                "fabric_race.option.playSounds",
                playSounds,
                value -> {
                    playSounds = value;
                    save();
                }
        );
        
        public static final SimpleOption<Boolean> CHAT_AVAILABLE = SimpleOption.ofBoolean(
                "fabric_race.option.chatAvailable",
                chatAvailable,
                value -> {
                    chatAvailable = value;
                    save();
                }
        );
        
        public static final SimpleOption<Boolean> HIDE_PROFILE = SimpleOption.ofBoolean(
                "fabric_race.option.hideProfile",
                hideProfile,
                value -> {
                    hideProfile = value;
                    save();
                }
        );
        
        public static final SimpleOption<Integer> MAX_WORLD_SAVE = new SimpleOption<>(
                "fabric_race.option.maxWorldSave",
                SimpleOption.emptyTooltip(),
                (optionText, value) -> Text.translatable("fabric_race.option.maxWorldSave.value", value),
                new SimpleOption.ValidatingIntSliderCallbacks(1, 50),
                maxWorldSave,
                value -> {
                    maxWorldSave = value;
                    save();
                }
        );
        
        public static final SimpleOption<Integer> MAX_REPLAY_SAVE = new SimpleOption<>(
                "fabric_race.option.maxReplaySave",
                SimpleOption.emptyTooltip(),
                (optionText, value) -> Text.translatable("fabric_race.option.maxReplaySave.value", value),
                new SimpleOption.ValidatingIntSliderCallbacks(1, 20),
                maxReplaySave,
                value -> {
                    maxReplaySave = value;
                    save();
                }
        );
        
        public static final SimpleOption<Boolean> AUTO_START = SimpleOption.ofBoolean(
                "fabric_race.option.autoStart",
                autoStart,
                value -> {
                    autoStart = value;
                    save();
                }
        );
        
        public static final SimpleOption<Boolean> SHOW_LEADERBOARD = SimpleOption.ofBoolean(
                "fabric_race.option.showLeaderboard",
                showLeaderboard,
                value -> {
                    showLeaderboard = value;
                    save();
                }
        );
        
        public static final SimpleOption<Boolean> ENABLE_SPECTATOR = SimpleOption.ofBoolean(
                "fabric_race.option.enableSpectator",
                enableSpectator,
                value -> {
                    enableSpectator = value;
                    save();
                }
        );
        
        public static final SimpleOption<Boolean> ENABLE_STANDARD_RNG = SimpleOption.ofBoolean(
                "fabric_race.option.enableStandardRNG",
                enableStandardRNG,
                value -> {
                    enableStandardRNG = value;
                    save();
                }
        );
        
        public static final SimpleOption<Boolean> ENABLE_VANILLA_FIXES = SimpleOption.ofBoolean(
                "fabric_race.option.enableVanillaFixes",
                enableVanillaFixes,
                value -> {
                    enableVanillaFixes = value;
                    save();
                }
        );
    }
}
