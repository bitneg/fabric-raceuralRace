package race.replay;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Система записи и воспроизведения повторов гонок
 */
public final class ReplayManager {
    private static final Path REPLAY_DIR = Paths.get("replays");
    private static final Map<UUID, ReplayRecorder> activeRecordings = new ConcurrentHashMap<>();
    
    /**
     * Начинает запись гонки для игрока
     */
    public static void startRecording(ServerPlayerEntity player, long raceSeed, long raceStartTime) {
        UUID playerId = player.getUuid();
        
        if (activeRecordings.containsKey(playerId)) {
            stopRecording(playerId);
        }
        
        ReplayRecorder recorder = new ReplayRecorder(playerId, raceSeed, raceStartTime);
        activeRecordings.put(playerId, recorder);
        
        // Записываем начальное состояние
        recorder.recordFrame(new ReplayFrame(
            System.currentTimeMillis(),
            player.getPos(),
            player.getYaw(),
            player.getPitch(),
            player.getHealth(),
            player.getHungerManager().getFoodLevel(),
            player.experienceLevel
        ));
    }
    
    /**
     * Останавливает запись гонки
     */
    public static void stopRecording(UUID playerId) {
        ReplayRecorder recorder = activeRecordings.remove(playerId);
        if (recorder != null) {
            saveReplay(recorder);
        }
    }
    
    /**
     * Записывает кадр повтора
     */
    public static void recordFrame(ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        ReplayRecorder recorder = activeRecordings.get(playerId);
        
        if (recorder != null) {
            recorder.recordFrame(new ReplayFrame(
                System.currentTimeMillis(),
                player.getPos(),
                player.getYaw(),
                player.getPitch(),
                player.getHealth(),
                player.getHungerManager().getFoodLevel(),
                player.experienceLevel
            ));
        }
    }
    
    /**
     * Сохраняет повтор в файл
     */
    private static void saveReplay(ReplayRecorder recorder) {
        try {
            // Создаем директорию если не существует
            if (!Files.exists(REPLAY_DIR)) {
                Files.createDirectories(REPLAY_DIR);
            }
            
            // Генерируем имя файла
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            String filename = String.format("replay_%s_%s.replay", timestamp, recorder.getPlayerId().toString().substring(0, 8));
            Path filePath = REPLAY_DIR.resolve(filename);
            
            // Сохраняем повтор
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(filePath))) {
                out.writeObject(recorder.getReplayData());
            }
            
            System.out.println("Replay saved: " + filename);
            
        } catch (IOException e) {
            System.err.println("Failed to save replay: " + e.getMessage());
        }
    }
    
    /**
     * Загружает повтор из файла
     */
    public static ReplayData loadReplay(String filename) {
        try {
            Path filePath = REPLAY_DIR.resolve(filename);
            if (!Files.exists(filePath)) {
                return null;
            }
            
            try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(filePath))) {
                return (ReplayData) in.readObject();
            }
            
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("Failed to load replay: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Получает список доступных повторов
     */
    public static List<String> getAvailableReplays() {
        List<String> replays = new ArrayList<>();
        
        if (Files.exists(REPLAY_DIR)) {
            try {
                Files.list(REPLAY_DIR)
                    .filter(path -> path.toString().endsWith(".replay"))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .forEach(replays::add);
            } catch (IOException e) {
                System.err.println("Failed to list replays: " + e.getMessage());
            }
        }
        
        return replays;
    }
    
    /**
     * Удаляет старые повторы
     */
    public static void cleanupOldReplays(int maxReplays) {
        List<String> replays = getAvailableReplays();
        
        if (replays.size() > maxReplays) {
            int toDelete = replays.size() - maxReplays;
            
            for (int i = 0; i < toDelete; i++) {
                try {
                    Files.delete(REPLAY_DIR.resolve(replays.get(i)));
                } catch (IOException e) {
                    System.err.println("Failed to delete old replay: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Записыватель повторов
     */
    private static class ReplayRecorder {
        private final UUID playerId;
        private final long raceSeed;
        private final long raceStartTime;
        private final List<ReplayFrame> frames = new ArrayList<>();
        
        public ReplayRecorder(UUID playerId, long raceSeed, long raceStartTime) {
            this.playerId = playerId;
            this.raceSeed = raceSeed;
            this.raceStartTime = raceStartTime;
        }
        
        public void recordFrame(ReplayFrame frame) {
            frames.add(frame);
        }
        
        public ReplayData getReplayData() {
            return new ReplayData(playerId, raceSeed, raceStartTime, new ArrayList<>(frames));
        }
        
        public UUID getPlayerId() { return playerId; }
    }
    
    /**
     * Данные повтора
     */
    public static class ReplayData implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private final UUID playerId;
        private final long raceSeed;
        private final long raceStartTime;
        private final List<ReplayFrame> frames;
        
        public ReplayData(UUID playerId, long raceSeed, long raceStartTime, List<ReplayFrame> frames) {
            this.playerId = playerId;
            this.raceSeed = raceSeed;
            this.raceStartTime = raceStartTime;
            this.frames = frames;
        }
        
        public UUID getPlayerId() { return playerId; }
        public long getRaceSeed() { return raceSeed; }
        public long getRaceStartTime() { return raceStartTime; }
        public List<ReplayFrame> getFrames() { return frames; }
    }
    
    /**
     * Кадр повтора
     */
    public static class ReplayFrame implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private final long timestamp;
        private final Vec3d position;
        private final float yaw;
        private final float pitch;
        private final float health;
        private final int foodLevel;
        private final int experienceLevel;
        
        public ReplayFrame(long timestamp, Vec3d position, float yaw, float pitch, 
                          float health, int foodLevel, int experienceLevel) {
            this.timestamp = timestamp;
            this.position = position;
            this.yaw = yaw;
            this.pitch = pitch;
            this.health = health;
            this.foodLevel = foodLevel;
            this.experienceLevel = experienceLevel;
        }
        
        public long getTimestamp() { return timestamp; }
        public Vec3d getPosition() { return position; }
        public float getYaw() { return yaw; }
        public float getPitch() { return pitch; }
        public float getHealth() { return health; }
        public int getFoodLevel() { return foodLevel; }
    }
}
