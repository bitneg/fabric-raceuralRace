package race.server;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import race.net.RaceBoardPayload;
import race.net.SeedAckS2CPayload;
import race.net.SeedHandshakeC2SPayload;
import race.net.StartRacePayload;
import race.net.PlayerProgressPayload;
import race.net.SeedLobbyListPayload;
import race.net.SeedLobbyEntry;
import race.server.phase.NoCollisionUtil;
import race.server.phase.RacePhaseManager;
import race.server.phase.PhaseState;
import race.server.world.PersonalWorlds;
import race.server.world.ServerRaceConfig;
import race.hub.HubManager;
import race.server.world.EnhancedWorldManager;
import race.server.death.DeathEchoManager;
import race.server.AchievementManager;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.world.chunk.ChunkStatus;
import race.server.commands.RaceCommands;
import race.replay.ReplayManager;
import race.ranking.RankingSystem;
import race.config.RaceConfig;
import race.server.world.EnhancedWorldManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class RaceServerInit implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(RaceServerInit.class);
    private static volatile boolean active = false;
    private static volatile long seed = -1L;
    private static volatile long t0ms = -1L;
    private static int tick = 0;
    // Запоминаем уже объявленные команды по конкретному миру (ключ мира)
    private static final java.util.Map<String, java.util.Set<java.util.UUID>> announcedTeams = new java.util.HashMap<>();
    
    // ЕДИНАЯ СИСТЕМА ВРЕМЕНИ ПРИВЯЗАННОГО К МИРУ-СЛОТУ
    private static final ConcurrentHashMap<RegistryKey<World>, Long> WORLD_TIME = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<RegistryKey<World>, Long> WORLD_TIME_SPEED = new ConcurrentHashMap<>();
    // Грейс на анти-чит полёта в личных мирах (ticks)
    // Отключено: временный грейс на полёт
    private static final java.util.Map<java.util.UUID, Integer> floatGrace = java.util.Collections.emptyMap();
    // Персональный старт таймера и заморозка до старта
    public static final java.util.Set<java.util.UUID> personalStarted = new java.util.HashSet<>();
    public static final java.util.Set<java.util.UUID> frozenUntilStart = new java.util.HashSet<>();
    private static final java.util.Map<java.util.UUID, net.minecraft.util.math.BlockPos> freezePos = new java.util.HashMap<>();
    // Отображать ли параллельных игроков (призраки/следы) — настраивается хостом
    private static volatile boolean displayParallelPlayers = true;
    // Стабилизатор времени суток: не позволяем шагу времени прыгать больше чем на 1 тик
    private static final java.util.Map<String, Long> lastTimeOfDay = new java.util.concurrent.ConcurrentHashMap<>();
    // Отложенные Join-запросы: источник -> (цель, тики)
    private static final java.util.Map<java.util.UUID, PendingJoin> pendingJoins = new java.util.concurrent.ConcurrentHashMap<>();
    private record PendingJoin(java.util.UUID target, int ticksLeft) {}
    
    // Отслеживание оригинальных миров игроков
    private static final java.util.Map<java.util.UUID, String> playerOriginalWorlds = new java.util.concurrent.ConcurrentHashMap<>();
    
    // Кэш для оптимизации производительности
    private static final java.util.Map<java.util.UUID, Long> playerSeedCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, String> playerWorldCache = new java.util.concurrent.ConcurrentHashMap<>();
    
    // Интервал автосохранения (в тиках)
    private static final int saveInterval = 200; // 10 секунд при 20 TPS
    
    // TPS отслеживание
    private static final java.util.Queue<Long> tickTimes = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static volatile double currentTPS = 20.0;
    private static volatile boolean tpsDisplayEnabled = false;
    
    // Оптимизация производительности
    private static final java.util.Map<java.util.UUID, Long> lastPlayerUpdate = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Long> lastMobCheck = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Long> lastParallelUpdate = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger performanceLevel = new java.util.concurrent.atomic.AtomicInteger(0); // 0=normal, 1=medium, 2=high load
    
    // Система автоматической оптимизации
    private static final java.util.concurrent.atomic.AtomicInteger autoOptimizationLevel = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.atomic.AtomicLong lastGcTime = new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicLong lastMemoryCheck = new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicInteger consecutiveLowTps = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.atomic.AtomicInteger consecutiveHighTps = new java.util.concurrent.atomic.AtomicInteger(0);
    private static volatile boolean autoOptimizationEnabled = true;
    
    // Оптимизация призраков
    private static volatile int ghostQualityLevel = 2; // 0=отключено, 1=низкое, 2=среднее, 3=высокое
    private static volatile boolean adaptiveGhostQuality = true;

    public static boolean isDisplayParallelPlayers() { return displayParallelPlayers; }
    public static void setDisplayParallelPlayers(boolean v) { displayParallelPlayers = v; }

    public static boolean isActive() { return active; }
    public static long getSeed() { return seed; }
    
    // TPS методы
    public static double getCurrentTPS() { return currentTPS; }
    public static boolean isTpsDisplayEnabled() { return tpsDisplayEnabled; }
    public static void setTpsDisplayEnabled(boolean enabled) { tpsDisplayEnabled = enabled; }
    
    // Методы для мониторинга производительности
    public static int getPerformanceLevel() { return performanceLevel.get(); }
    public static String getPerformanceStatus() {
        int level = performanceLevel.get();
        return switch (level) {
            case 0 -> "Нормальная";
            case 1 -> "Средняя нагрузка";
            case 2 -> "Высокая нагрузка";
            default -> "Неизвестно";
        };
    }
    
    // Методы для автоматической оптимизации
    public static int getAutoOptimizationLevel() { return autoOptimizationLevel.get(); }
    public static boolean isAutoOptimizationEnabled() { return autoOptimizationEnabled; }
    public static void setAutoOptimizationEnabled(boolean enabled) { autoOptimizationEnabled = enabled; }
    
    // Методы для оптимизации призраков
    public static int getGhostQualityLevel() { return ghostQualityLevel; }
    public static void setGhostQualityLevel(int level) { ghostQualityLevel = Math.max(0, Math.min(3, level)); }
    public static boolean isAdaptiveGhostQuality() { return adaptiveGhostQuality; }
    public static void setAdaptiveGhostQuality(boolean enabled) { adaptiveGhostQuality = enabled; }
    
    /**
     * Получает адаптивное качество призраков на основе производительности
     */
    public static int getAdaptiveGhostQuality() {
        if (!adaptiveGhostQuality) return ghostQualityLevel;
        
        // Адаптивное качество на основе TPS и количества игроков
        if (currentTPS < 15.0) return 0; // Отключаем при очень низком TPS
        if (currentTPS < 18.0) return 1; // Низкое качество при среднем TPS
        if (currentTPS < 19.5) return 2; // Среднее качество при хорошем TPS
        return 3; // Высокое качество при отличном TPS
    }
    
    /**
     * Получает количество частиц для призраков на основе качества
     */
    public static int getGhostParticleCount(int baseCount) {
        int quality = getAdaptiveGhostQuality();
        return switch (quality) {
            case 0 -> 0; // Отключено
            case 1 -> baseCount / 4; // 25% частиц
            case 2 -> baseCount / 2; // 50% частиц
            case 3 -> baseCount; // 100% частиц
            default -> baseCount / 2;
        };
    }
    
    /**
     * Получает интервал обновления призраков на основе качества
     */
    public static int getGhostUpdateInterval() {
        int quality = getAdaptiveGhostQuality();
        return switch (quality) {
            case 0 -> 0; // Отключено
            case 1 -> 40; // Каждые 2 секунды
            case 2 -> 20; // Каждую секунду
            case 3 -> 10; // Каждые 0.5 секунды
            default -> 20;
        };
    }
    
    /**
     * Автоматическая оптимизация производительности
     */
    private static void performAutoOptimization(MinecraftServer server) {
        if (!autoOptimizationEnabled) return;
        
        long now = System.currentTimeMillis();
        double tps = currentTPS;
        
        // Анализируем тренд TPS
        if (tps < 16.0) {
            consecutiveLowTps.incrementAndGet();
            consecutiveHighTps.set(0);
        } else if (tps > 19.0) {
            consecutiveHighTps.incrementAndGet();
            consecutiveLowTps.set(0);
        } else {
            consecutiveLowTps.set(0);
            consecutiveHighTps.set(0);
        }
        
        // Применяем автоматические оптимизации
        if (consecutiveLowTps.get() >= 3) { // 3 тика подряд с низким TPS
            applyAutoOptimizations(server, true);
        } else if (consecutiveHighTps.get() >= 5) { // 5 тиков подряд с высоким TPS
            applyAutoOptimizations(server, false);
        }
        
        // Периодическая проверка памяти и GC
        if (now - lastMemoryCheck.get() > 10000) { // Каждые 10 секунд
            checkAndOptimizeMemory(server);
            lastMemoryCheck.set(now);
        }
    }
    
    /**
     * Применяет автоматические оптимизации
     */
    private static void applyAutoOptimizations(MinecraftServer server, boolean increaseResources) {
        if (increaseResources) {
            int currentLevel = autoOptimizationLevel.get();
            if (currentLevel < 3) { // Максимум 3 уровня
                autoOptimizationLevel.incrementAndGet();
                LOGGER.info("[Race] Автоматическая оптимизация: Уровень {} (TPS: {})", 
                    autoOptimizationLevel.get(), String.format("%.2f", currentTPS));
                
                // Применяем оптимизации
                applyResourceOptimizations(server, autoOptimizationLevel.get());
            }
        } else {
            int currentLevel = autoOptimizationLevel.get();
            if (currentLevel > 0) {
                autoOptimizationLevel.decrementAndGet();
                LOGGER.info("[Race] Автоматическая оптимизация: Уровень {} (TPS: {})", 
                    autoOptimizationLevel.get(), String.format("%.2f", currentTPS));
                
                // Снижаем оптимизации
                applyResourceOptimizations(server, autoOptimizationLevel.get());
            }
        }
    }
    
    /**
     * Применяет оптимизации ресурсов на основе уровня
     */
    private static void applyResourceOptimizations(MinecraftServer server, int level) {
        try {
            switch (level) {
                case 1 -> {
                    // Уровень 1: Базовая оптимизация
                    System.gc(); // Принудительная сборка мусора
                    LOGGER.info("[Race] Применена базовая оптимизация: GC");
                }
                case 2 -> {
                    // Уровень 2: Средняя оптимизация
                    System.gc();
                    // Очищаем кэши
                    playerSeedCache.clear();
                    playerWorldCache.clear();
                    LOGGER.info("[Race] Применена средняя оптимизация: GC + очистка кэшей");
                }
                case 3 -> {
                    // Уровень 3: Агрессивная оптимизация
                    System.gc();
                    playerSeedCache.clear();
                    playerWorldCache.clear();
                    // Очищаем дополнительные кэши
                    lastPlayerUpdate.clear();
                    lastMobCheck.clear();
                    lastParallelUpdate.clear();
                    LOGGER.info("[Race] Применена агрессивная оптимизация: GC + полная очистка кэшей");
                }
                default -> {
                    // Уровень 0: Нормальная работа
                    LOGGER.info("[Race] Оптимизации отключены, нормальная работа");
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[Race] Ошибка применения оптимизаций: {}", e.getMessage());
        }
    }
    
    /**
     * Проверяет и оптимизирует использование памяти
     */
    private static void checkAndOptimizeMemory(MinecraftServer server) {
        try {
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            long maxMemory = runtime.maxMemory();
            
            double memoryUsagePercent = (double) usedMemory / maxMemory * 100;
            
            // Если использование памяти > 80%, применяем агрессивную оптимизацию
            if (memoryUsagePercent > 80.0) {
                LOGGER.warn("[Race] Высокое использование памяти: {:.1f}%", memoryUsagePercent);
                if (autoOptimizationLevel.get() < 3) {
                    autoOptimizationLevel.set(3);
                    applyResourceOptimizations(server, 3);
                }
            }
            // Если использование памяти < 50%, снижаем оптимизации
            else if (memoryUsagePercent < 50.0 && autoOptimizationLevel.get() > 0) {
                LOGGER.info("[Race] Низкое использование памяти: {:.1f}%, снижаем оптимизации", memoryUsagePercent);
                autoOptimizationLevel.decrementAndGet();
                applyResourceOptimizations(server, autoOptimizationLevel.get());
            }
            
        } catch (Exception e) {
            LOGGER.warn("[Race] Ошибка проверки памяти: {}", e.getMessage());
        }
    }
    
    /**
     * Обновляет кэш игроков для оптимизации производительности
     */
    private static void updatePlayerCache(MinecraftServer server) {
        try {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                java.util.UUID playerId = player.getUuid();
                
                // Кэшируем сид игрока
                Long seed = race.hub.HubManager.getPlayerSeedChoice(playerId);
                if (seed != null && seed > 0) {
                    playerSeedCache.put(playerId, seed);
                }
                
                // Кэшируем мир игрока
                String worldKey = player.getServerWorld().getRegistryKey().getValue().toString();
                playerWorldCache.put(playerId, worldKey);
            }
        } catch (Throwable t) {
            LOGGER.warn("[Race] Error updating player cache: {}", t.getMessage());
        }
    }
    
    /**
     * Обновляет TPS на основе времени выполнения тиков
     */
    private static void updateTPS(long tickTimeMs) {
        long currentTime = System.currentTimeMillis();
        tickTimes.offer(currentTime);
        
        // Ограничиваем размер очереди до последних 20 секунд
        while (!tickTimes.isEmpty() && currentTime - tickTimes.peek() > 20000) {
            tickTimes.poll();
        }
        
        // Вычисляем TPS на основе количества тиков за последние 20 секунд
        if (tickTimes.size() > 1) {
            long timeSpan = currentTime - tickTimes.peek();
            if (timeSpan > 0) {
                currentTPS = Math.min(20.0, (tickTimes.size() - 1) * 1000.0 / timeSpan);
            }
        }
        
        // Адаптивная оптимизация на основе TPS
        if (currentTPS < 15.0) {
            performanceLevel.set(2); // Высокая нагрузка
        } else if (currentTPS < 18.0) {
            performanceLevel.set(1); // Средняя нагрузка
        } else {
            performanceLevel.set(0); // Нормальная нагрузка
        }
    }
    
    /**
     * Получает адаптивный интервал для обновлений на основе производительности
     */
    private static int getAdaptiveInterval(int baseInterval, int playerCount) {
        int level = performanceLevel.get();
        int multiplier = 1;
        
        // Увеличиваем интервалы при высокой нагрузке
        if (level == 2) {
            multiplier = 3; // В 3 раза реже
        } else if (level == 1) {
            multiplier = 2; // В 2 раза реже
        }
        
        // Дополнительная оптимизация для большого количества игроков
        if (playerCount > 8) {
            multiplier *= 2;
        }
        
        return baseInterval * multiplier;
    }
    
    /**
     * Проверяет, нужно ли обновлять игрока (адаптивная оптимизация)
     */
    private static boolean shouldUpdatePlayer(java.util.UUID playerId, int baseInterval) {
        long now = System.currentTimeMillis();
        Long lastUpdate = lastPlayerUpdate.get(playerId);
        
        if (lastUpdate == null) {
            lastPlayerUpdate.put(playerId, now);
            return true;
        }
        
        int interval = getAdaptiveInterval(baseInterval, 1); // Базовый интервал
        return (now - lastUpdate) >= interval;
    }
    
    /**
     * Отчет о производительности системы
     */
    private static void reportPerformanceMetrics(MinecraftServer server) {
        try {
            int playerCount = server.getPlayerManager().getPlayerList().size();
            long memoryUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            long memoryMax = Runtime.getRuntime().maxMemory();
            
            // Логируем метрики производительности
            LOGGER.info("[Race] Performance Report - Players: {}, Memory: {}/{} MB, Cache Size: {}", 
                playerCount, 
                memoryUsed / 1024 / 1024, 
                memoryMax / 1024 / 1024,
                playerSeedCache.size() + playerWorldCache.size());
                
            // Очищаем кэш если он слишком большой
            if (playerSeedCache.size() > 100 || playerWorldCache.size() > 100) {
                playerSeedCache.clear();
                playerWorldCache.clear();
                LOGGER.info("[Race] Cleared performance cache due to size");
            }
        } catch (Throwable t) {
            LOGGER.warn("[Race] Error reporting performance metrics: {}", t.getMessage());
        }
    }
    public static long getT0ms() { return t0ms; }

    public static void grantFloatGrace(ServerPlayerEntity p, int ticks) { }

    public static boolean isFrozen(java.util.UUID id) { return frozenUntilStart.contains(id); }
    
    // API: принудительное размораживание игрока (для команд администратора)
    public static void forceUnfreezePlayer(ServerPlayerEntity p) {
        java.util.UUID id = p.getUuid();
        frozenUntilStart.remove(id);
        freezePos.remove(id);
        try { p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL); } catch (Throwable ignored) {}
        LOGGER.info("[Race] FORCE UNFREEZE: Player {} forcefully unfrozen", p.getName().getString());
    }
    
    // API: принудительное размораживание всех игроков (для команд администратора)
    public static void forceUnfreezeAllPlayers(MinecraftServer server) {
        int count = 0;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (frozenUntilStart.contains(p.getUuid())) {
                forceUnfreezePlayer(p);
                count++;
            }
        }
        LOGGER.info("[Race] FORCE UNFREEZE ALL: {} players unfrozen", count);
    }
    
    // ========== СИСТЕМА НЕЗАВИСИМОГО ВРЕМЕНИ ==========
    
    
    /**
     * Получает текущее время в race-мире
     */

    
    // ========== ПЕРСОНАЛЬНАЯ СИСТЕМА ВРЕМЕНИ ==========
    
    /**
     * Проверяет, является ли мир персональным (fabric_race)
     */
    private static boolean isPersonal(ServerWorld world) {
        return world.getRegistryKey().getValue().getNamespace().equals("fabric_race");
    }
    
    /**
     * Инициализирует время мира если его еще нет
     */
    public static void initWorldIfAbsent(ServerWorld world, long initial) {
        if (!isPersonal(world)) return;
        RegistryKey<World> key = world.getRegistryKey();
        
        // Идемпотентная инициализация: устанавливаем время только если мир впервые увиден
        Long prev = WORLD_TIME.putIfAbsent(key, initial);
        if (prev == null) {
            // Мир впервые инициализируется
            WORLD_TIME_SPEED.putIfAbsent(key, 1L);
            // НЕ вызываем world.setTimeOfDay - теперь используется виртуальное время
            System.out.println("[Race] TIME SET world=" + key.getValue() + " src=initWorldIfAbsent val=" + initial);
        }
        // Если мир уже инициализирован, не трогаем время
        
        // Убеждаемся, что DO_DAYLIGHT_CYCLE отключён
        if (world.getGameRules().get(net.minecraft.world.GameRules.DO_DAYLIGHT_CYCLE).get()) {
            world.getGameRules().get(net.minecraft.world.GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
        }
    }
    
    /**
     * Получает время мира-слота
     */
    public static long getWorldTime(RegistryKey<World> key) {
        Long t = WORLD_TIME.get(key);
        return t != null ? t : 0L; // Убираем дефолт 1000L, чтобы избежать ложного "утра"
    }
    
    /**
     * Устанавливает время мира-слота
     */
    public static void setWorldTime(RegistryKey<World> key, long time, ServerWorld world) {
        WORLD_TIME.put(key, time);
        // НЕ вызываем world.setTimeOfDay - теперь используется виртуальное время
        System.out.println("[Race] TIME SET world=" + key.getValue() + " src=setWorldTime val=" + time);
    }
    
    /**
     * Устанавливает скорость времени для мира-слота
     */
    public static void setWorldTimeSpeed(RegistryKey<World> key, long speed) {
        if (speed < 0) speed = 0;
        WORLD_TIME_SPEED.put(key, speed);
    }
    
    /**
     * Получает скорость времени для мира-слота
     */
    public static long getWorldTimeSpeed(RegistryKey<World> key) {
        return WORLD_TIME_SPEED.getOrDefault(key, 1L);
    }
    
    // СТАРЫЙ ТИКЕР УДАЛЕН - теперь используется SlotTimeService
    
    /**
     * Получает текущее время мира игрока
     */
    private static long getCurrentWorldTime(UUID playerId) {
        // Найти игрока и получить время его мира
        MinecraftServer server = getCurrentServer();
        if (server != null) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player != null) {
                RegistryKey<World> key = player.getServerWorld().getRegistryKey();
                return getWorldTime(key);
            }
        }
        return 0L;
    }
    
    /**
     * Получает текущий сервер (для доступа к игрокам)
     */
    private static MinecraftServer getCurrentServer() {
        // Простой способ получить сервер - через статическую переменную или другой метод
        // Пока что возвращаем null, нужно будет передавать сервер в методы
        return null;
    }
    
    /**
     * Проверяет, ночное ли время для игрока (для спавна мобов)
     */
    public static boolean isNightTimeForPlayer(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        String key = world.getRegistryKey().getValue().toString();
        
        if (!key.startsWith("fabric_race:")) {
            // Для обычных миров используем стандартное время
            long timeOfDay = world.getTimeOfDay() % 24000L;
            return timeOfDay >= 13000L && timeOfDay <= 23000L;
        }
        
        // Для гоночных миров используем время мира-слота
        RegistryKey<World> worldKey = world.getRegistryKey();
        long worldTime = getWorldTime(worldKey);
        long timeOfDay = worldTime % 24000L;
        return timeOfDay >= 13000L && timeOfDay <= 23000L;
    }
    
    /**
     * Обрабатывает сон в гоночном мире-слоте
     */
    private static void handleWorldSleep(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        String id = world.getRegistryKey().getValue().toString();
        if (!id.startsWith("fabric_race:")) return;

        RegistryKey<World> key = world.getRegistryKey();
        long current = getWorldTime(key);

        // Ночь по ваниле ~ 13000..23000
        long timeOfDay = current % 24000L;
        boolean isNight = (timeOfDay >= 13000L && timeOfDay < 23000L);

        if (!isNight) return;

        long nextMorningBase = current - timeOfDay + 24000L; // начало следующего дня (0)
        long target = nextMorningBase + 1000L; // сдвиг к утру

        setWorldTime(key, target, world);

        // Разбудить только игроков этого мира
        for (ServerPlayerEntity p : world.getPlayers()) {
            if (p.isSleeping()) p.wakeUp(false, true);
        }
    }
    

    // Поиск безопасной опоры под игроком (твёрдый блок снизу, 2 блока воздуха)
    private static net.minecraft.util.math.BlockPos findSafeGround(ServerPlayerEntity p) {
        var w = p.getServerWorld();
        net.minecraft.util.math.BlockPos pos = p.getBlockPos();
        int bottom = w.getBottomY() + 1;
        for (int y = pos.getY(); y >= Math.max(bottom, pos.getY() - 24); y--) {
            net.minecraft.util.math.BlockPos feet = new net.minecraft.util.math.BlockPos(pos.getX(), y, pos.getZ());
            net.minecraft.util.math.BlockPos below = feet.down();
            var belowSt = w.getBlockState(below);
            if ((w.isAir(feet) || w.getFluidState(feet).isEmpty()) &&
                (w.isAir(feet.up()) || w.getFluidState(feet.up()).isEmpty()) &&
                belowSt.isSolidBlock(w, below) && belowSt.getFluidState().isEmpty()) {
                return feet;
            }
        }
        // фолбэк: уровень моря + 1
        return new net.minecraft.util.math.BlockPos(pos.getX(), Math.max(bottom, w.getSeaLevel() + 1), pos.getZ());
    }

    // API: заморозить игрока до личного старта
    public static void freezePlayerUntilStart(ServerPlayerEntity p) {
        java.util.UUID id = p.getUuid();
        String worldKey = p.getServerWorld().getRegistryKey().getValue().toString();
        
        LOGGER.info("[Race] FREEZE REQUEST: Player {} in world {} - analyzing...", 
            p.getName().getString(), worldKey);
        
        // ИСПРАВЛЕННАЯ логика анализа мира
        boolean isPersonalWorld = worldKey.startsWith("fabric_race:p_");
        boolean isSlotWorld = worldKey.contains("slot") && worldKey.startsWith("fabric_race:");
        boolean hasStarted = personalStarted.contains(id);
        boolean globalActive = active;
        
        // НОВАЯ ЛОГИКА: Замораживаем игроков в слотовых мирах если они не начали гонку
        boolean shouldFreeze = (isSlotWorld || isPersonalWorld) && !hasStarted && !globalActive;
        
        LOGGER.info("[Race] FREEZE ANALYSIS: personal={}, slot={}, started={}, globalActive={}, shouldFreeze={}", 
            isPersonalWorld, isSlotWorld, hasStarted, globalActive, shouldFreeze);
        
        if (!shouldFreeze) {
            LOGGER.info("[Race] FREEZE SKIPPED: Player {} should not be frozen in world {} (personal={}, slot={}, started={}, globalActive={})", 
                p.getName().getString(), worldKey, isPersonalWorld, isSlotWorld, hasStarted, globalActive);
            return;
        }
        
        // Разрешим повторные старты после новой подготовки
        personalStarted.remove(id);
        frozenUntilStart.add(id);
        net.minecraft.util.math.BlockPos base = findSafeGround(p);
        freezePos.put(id, base);
        p.changeGameMode(net.minecraft.world.GameMode.ADVENTURE);
        try {
            p.setVelocity(0, 0, 0);
            p.requestTeleport(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
        } catch (Throwable ignored) {}
        
        // ДИАГНОСТИКА: Логируем заморозку для отладки
        LOGGER.info("[Race] FREEZE: Player {} frozen until start at position ({}, {}, {}) in world {}", 
            p.getName().getString(), base.getX(), base.getY(), base.getZ(), worldKey);
    }

    private static long parseSeedFromWorldKey(String key) {
        try {
            int i = key.lastIndexOf('_');
            String s = key.substring(i + 2).replace("s", "");
            return Long.parseLong(s);
        } catch (Throwable ignored) { return -1L; }
    }

    // Базовая эвристика активности для параллельных силуэтов
    private static byte detectActivityType(ServerPlayerEntity p) {
        try {
            var main = p.getMainHandStack();
            if (p.isUsingItem()) return 7; // craft/use
            if (p.isSprinting()) return 4;
            if (p.isSwimming() || p.isSubmergedInWater()) return 4;
            // грубая проверка "копает": держит кирку/лопату и жмёт атаковать?
            // API сервера не даёт прямого ввода — вернём 0, визуал на клиенте будет одинаковый
            return 0;
        } catch (Throwable ignored) { return 0; }
    }

    // Метод для проверки, можно ли стартовать новую гонку
    public static boolean canStartNewRace(ServerPlayerEntity player) {
        java.util.UUID playerId = player.getUuid();
        
        // ИЗМЕНЕНИЕ: НЕ блокируем создание изолированного мира
        // Блокируем только повторный старт для уже начавших игроков
        if (personalStarted.contains(playerId)) {
            LOGGER.debug("[Race] Player {} already started personal race", player.getName().getString());
            return false;
        }
        
        // Глобальная гонка НЕ блокирует создание изолированных миров
        return true;
    }
    
    // Сохраняем оригинальный мир при создании
    public static void savePlayerOriginalWorld(java.util.UUID playerId, String worldKey) {
        playerOriginalWorlds.put(playerId, worldKey);
        LOGGER.debug("[Race] Saved original world for {}: {}", playerId, worldKey);
    }
    
    // Получаем оригинальный мир
    public static String getPlayerOriginalWorld(java.util.UUID playerId) {
        return playerOriginalWorlds.get(playerId);
    }
    
    // Извлекаем номер слота из ключа мира
    public static int extractSlotFromWorldKey(String worldKey) {
        if (worldKey == null) return -1;
        
        try {
            // Формат: fabric_race:slot2_overworld_s17640951512
            if (worldKey.contains("slot") && worldKey.contains("overworld")) {
                String slotPart = worldKey.substring(worldKey.indexOf("slot") + 4);
                String slotNumber = slotPart.substring(0, slotPart.indexOf("_"));
                return Integer.parseInt(slotNumber);
            }
        } catch (Exception e) {
            LOGGER.warn("[Race] Failed to extract slot from world key {}: {}", worldKey, e.getMessage());
        }
        
        return -1;
    }

    // API: личный старт — разморозка и отправка сигнала клиенту о старте таймера
    public static void personalStart(ServerPlayerEntity p, long seed) {
        java.util.UUID id = p.getUuid();
        
        // КЛЮЧЕВАЯ ПРОВЕРКА: не стартуем повторно
        if (personalStarted.contains(id)) {
            LOGGER.info("[Race] Player {} already started race - skipping personal start", 
                p.getName().getString());
            
            // Если игрок уже стартовал, просто размораживаем без повторного старта
            frozenUntilStart.remove(id);
            freezePos.remove(id);
            try {
                p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
            } catch (Throwable ignored) {}
            
            p.sendMessage(net.minecraft.text.Text.literal("Продолжаете гонку!")
                .formatted(net.minecraft.util.Formatting.GREEN), false);
            return;
        }
        
        // Проверяем, активна ли глобальная гонка
        if (active) {
            // ИСПРАВЛЕНИЕ: Проверяем, участвует ли игрок уже в гонке
            String worldKey = p.getServerWorld().getRegistryKey().getValue().toString();
            boolean isInRaceWorld = worldKey.startsWith("fabric_race:");
            
            if (isInRaceWorld) {
                LOGGER.info("[Race] Global race is active - player {} continues in their race world", 
                    p.getName().getString());
                
                // Размораживаем игрока для продолжения гонки
                frozenUntilStart.remove(id);
                freezePos.remove(id);
                try {
                    p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
                } catch (Throwable ignored) {}
                
                p.sendMessage(net.minecraft.text.Text.literal("Продолжаете гонку в своем мире!")
                    .formatted(net.minecraft.util.Formatting.GREEN), false);
                return;
            } else {
                LOGGER.info("[Race] Global race is active - player {} joins existing race from hub", 
                    p.getName().getString());
                
                // Размораживаем игрока для участия в активной гонке
                frozenUntilStart.remove(id);
                freezePos.remove(id);
                try {
                    p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
                } catch (Throwable ignored) {}
                
                p.sendMessage(net.minecraft.text.Text.literal("Присоединились к активной гонке!")
                    .formatted(net.minecraft.util.Formatting.GREEN), false);
                return;
            }
        }
        
        // Стандартная логика старта новой гонки
        personalStarted.add(id);
        frozenUntilStart.remove(id);
        freezePos.remove(id);
        try { p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL); } catch (Throwable ignored) {}
        try { ServerPlayNetworking.send(p, new StartRacePayload(seed, System.currentTimeMillis())); } catch (Throwable ignored) {}
        
        // ДИАГНОСТИКА: Логируем размораживание для отладки
        LOGGER.info("[Race] UNFREEZE: Player {} started personal race with seed {}", 
            p.getName().getString(), seed);
    }

    public static void startRace(long s, long t0) {
        active = true;
        seed = s;
        t0ms = t0;
        try {
            race.server.phase.PhaseState.setRaceActive(true);
            race.server.phase.PhaseState.setRaceSeed(s);
            race.server.phase.PhaseState.setRaceStartTime(t0);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onInitialize() {
        // Инициализируем ReturnPointRegistry с персистентным хранением
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            race.server.world.ReturnPointRegistry.initialize(server);
            race.server.world.PreferredWorldRegistry.initialize(server);
        });

        // Обработчик входа игрока - телепорт в предпочитаемый мир
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var player = handler.player;
            var preferred = race.server.world.PreferredWorldRegistry.getPreferred(player.getUuid());
            if (preferred != null) {
                var dst = server.getWorld(preferred);
                if (dst != null) {
                    // Ваниль сама учтёт кровать/якорь для respawn; здесь лишь отправка в мир
                    var spawnPos = dst.getSpawnPos();
                    player.teleport(dst, spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, player.getYaw(), player.getPitch());
                    LOGGER.info("[Race] Teleported {} to preferred world: {}", player.getName().getString(), preferred.getValue());
                }
            }
        });
        
        // Страховка: регистрируем типы payload до регистрации приёмников,
        // т.к. порядок entrypoint'ов не гарантирован
        try {
            net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(race.net.SeedHandshakeC2SPayload.ID, race.net.SeedHandshakeC2SPayload.CODEC);
        } catch (Throwable ignored) {}
        try {
            net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(race.net.PlayerProgressPayload.ID, race.net.PlayerProgressPayload.CODEC);
        } catch (Throwable ignored) {}
        try {
            net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(race.net.JoinRequestStatusPayload.ID, race.net.JoinRequestStatusPayload.CODEC);
        } catch (Throwable ignored) {}
        try {
            net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(race.net.TpsPayload.ID, race.net.TpsPayload.CODEC);
        } catch (Throwable ignored) {}
        // Сброс бюджета синхронной догрузки на каждый тик
        final ChunkSyncHelper.SyncBudget syncBudget = new ChunkSyncHelper.SyncBudget();
        final long[] tickStartTime = new long[1];
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            tickStartTime[0] = System.currentTimeMillis();
            // Можно вынести в конфиг
            syncBudget.reset(20, 1); // 20 мс и 1 чанк на тик
            // Нормализуем randomTickSpeed в персональных мирах (иногда мир создаётся с некорректным значением)
            try {
                boolean hasPersonal = false;
                for (net.minecraft.server.world.ServerWorld w : server.getWorlds()) {
                    int cur = w.getGameRules().get(net.minecraft.world.GameRules.RANDOM_TICK_SPEED).get();
                    if (cur != 3) {
                        w.getGameRules().get(net.minecraft.world.GameRules.RANDOM_TICK_SPEED).set(3, server);
                    }
                    
                    if (isPersonal(w)) {
                        hasPersonal = true;
                        // Для fabric_race миров: отключаем DODAYLIGHTCYCLE
                        if (w.getGameRules().get(net.minecraft.world.GameRules.DO_DAYLIGHT_CYCLE).get()) {
                            w.getGameRules().get(net.minecraft.world.GameRules.DO_DAYLIGHT_CYCLE).set(false, server);
                        }
                    } else {
                        // Для обычных миров - стандартная логика
                        if (!w.getGameRules().get(net.minecraft.world.GameRules.DO_DAYLIGHT_CYCLE).get()) {
                            w.getGameRules().get(net.minecraft.world.GameRules.DO_DAYLIGHT_CYCLE).set(true, server);
                        }
                        // Стабилизируем шаг времени только для обычных миров (не fabric_race)
                        try {
                            if (w.getGameRules().get(net.minecraft.world.GameRules.DO_DAYLIGHT_CYCLE).get()) {
                                String k = w.getRegistryKey().getValue().toString();
                                long prev = lastTimeOfDay.getOrDefault(k, w.getTimeOfDay());
                                long now = w.getTimeOfDay();
                                long step = now - prev;
                                if (step > 1 && step < 200) {
                                    // Ограничиваем до +1 тик для обычных миров
                                    w.setTimeOfDay(prev + 1);
                                    now = prev + 1;
                                }
                                lastTimeOfDay.put(k, now);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                
                // Время fabric_race миров теперь управляется SlotTimeService
            } catch (Throwable ignored) {}
        });
        // JOIN: если глобальный сид уже задан, создаём персональный мир сразу;
        // иначе подсказываем игроку как выбрать сид
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var p = handler.player;
            // На всякий случай нормализуем randomTickSpeed во всех мирах при подключении игрока
            try {
                for (net.minecraft.server.world.ServerWorld w : server.getWorlds()) {
                    String worldKey = w.getRegistryKey().getValue().toString();
                    int cur = w.getGameRules().get(net.minecraft.world.GameRules.RANDOM_TICK_SPEED).get();
                    if (cur != 3) {
                        w.getGameRules().get(net.minecraft.world.GameRules.RANDOM_TICK_SPEED).set(3, server);
                    }
                    
                    if (worldKey.startsWith("fabric_race:")) {
                        // Для fabric_race миров: отключаем DODAYLIGHTCYCLE
                        if (w.getGameRules().get(net.minecraft.world.GameRules.DO_DAYLIGHT_CYCLE).get()) {
                            w.getGameRules().get(net.minecraft.world.GameRules.DO_DAYLIGHT_CYCLE).set(false, server);
                        }
                    } else {
                        // Для обычных миров: включаем DODAYLIGHTCYCLE
                        if (!w.getGameRules().get(net.minecraft.world.GameRules.DO_DAYLIGHT_CYCLE).get()) {
                            w.getGameRules().get(net.minecraft.world.GameRules.DO_DAYLIGHT_CYCLE).set(true, server);
                        }
                    }
                }
            } catch (Throwable ignored) {}
            // Проверяем, есть ли у игрока активный персональный мир
            try {
                // Сначала проверяем последний использованный мир
                long lastWorldSeed = race.hub.HubManager.getLastWorldSeed(p.getUuid());
                long playerSeed = lastWorldSeed >= 0 ? lastWorldSeed : race.hub.HubManager.getPlayerSeedChoice(p.getUuid());
                if (playerSeed >= 0) {
                    LOGGER.info("[Race] Player {} returning to world with seed: {} (lastWorld: {}, chosen: {})", 
                        p.getName().getString(), playerSeed, lastWorldSeed, race.hub.HubManager.getPlayerSeedChoice(p.getUuid()));
                    
                    // Даем игроку выбор: вернуться в персональный мир или пойти в хаб
                    p.sendMessage(net.minecraft.text.Text.literal("У вас есть активный персональный мир (сид: " + playerSeed + ")").formatted(net.minecraft.util.Formatting.YELLOW), false);
                    p.sendMessage(net.minecraft.text.Text.literal("Используйте /race lobby для возврата в хаб").formatted(net.minecraft.util.Formatting.AQUA), false);
                    p.sendMessage(net.minecraft.text.Text.literal("Или подождите 5 секунд для автоматического возврата в персональный мир").formatted(net.minecraft.util.Formatting.GRAY), false);
                    
                    // ИСПРАВЛЕНИЕ: НЕ телепортируем автоматически - игрок должен остаться в том мире, где был
                    // Проверяем, в каком мире находится игрок при входе
                    String currentWorldKey = p.getServerWorld().getRegistryKey().getValue().toString();
                    LOGGER.info("[Race] Player {} entered game in world: {}", p.getName().getString(), currentWorldKey);
                    
                    if (currentWorldKey.startsWith("fabric_race:")) {
                        // Игрок уже в race мире - НЕ телепортируем его
                        LOGGER.info("[Race] Player {} already in race world - NO teleportation needed", p.getName().getString());
                        p.sendMessage(net.minecraft.text.Text.literal("Вы находитесь в своем race мире!").formatted(net.minecraft.util.Formatting.GREEN), false);
                    } else {
                        // Игрок в ванильном мире - предлагаем вернуться в race мир
                        p.sendMessage(net.minecraft.text.Text.literal("Используйте /race lobby для возврата в хаб").formatted(net.minecraft.util.Formatting.AQUA), false);
                        p.sendMessage(net.minecraft.text.Text.literal("Или подождите 5 секунд для автоматического возврата в персональный мир").formatted(net.minecraft.util.Formatting.GRAY), false);
                        
                        // Автоматический возврат в персональный мир через 5 секунд ТОЛЬКО если игрок в ванильном мире
                        server.execute(() -> {
                            try {
                                Thread.sleep(5000); // 5 секунд задержки
                                if (!p.isDisconnected()) { // Проверяем, что игрок все еще подключен
                                    // У игрока есть выбранный сид - пытаемся вернуть в его персональный мир
                                    try {
                                        // Проверяем, есть ли сохранённая позиция возврата
                                        race.server.world.ReturnPointRegistry.ReturnPoint returnPoint = race.server.world.ReturnPointRegistry.get(p);
                                        LOGGER.info("[Race] Return point for {}: {}", p.getName().getString(), returnPoint != null ? "exists" : "null");
                                    
                                    net.minecraft.server.world.ServerWorld personalWorld = null;
                                    
                                    if (returnPoint != null) {
                                        // Проверяем, что сохранённая позиция в персональном мире
                                        String returnWorldNamespace = returnPoint.worldKey.getValue().getNamespace();
                                        LOGGER.info("[Race] Return point namespace: {}, position: ({}, {}, {})", returnWorldNamespace, returnPoint.x, returnPoint.y, returnPoint.z);
                                        
                                        if ("hub".equals(returnWorldNamespace)) {
                                            // Игрок был в хабе - возвращаем в хаб
                                            LOGGER.info("[Race] Player {} was in hub, returning to hub", p.getName().getString());
                                            race.hub.HubManager.teleportToHub(p);
                                            p.sendMessage(net.minecraft.text.Text.literal("Добро пожаловать в хаб. Откройте меню (R) или используйте /race seed, /race ready.").formatted(net.minecraft.util.Formatting.YELLOW), false);
                                            return; // Выходим из обработчика
                                        } else if ("fabric_race".equals(returnWorldNamespace)) {
                                            // Определяем тип мира из сохраненной позиции
                                            String worldPath = returnPoint.worldKey.getValue().getPath();
                                            net.minecraft.registry.RegistryKey<net.minecraft.world.World> targetDimension;
                                            
                                            if (worldPath.contains("nether")) {
                                                targetDimension = net.minecraft.world.World.NETHER;
                                                LOGGER.info("[Race] Player was in Nether, returning to Nether");
                                            } else if (worldPath.contains("end")) {
                                                targetDimension = net.minecraft.world.World.END;
                                                LOGGER.info("[Race] Player was in End, returning to End");
                                            } else {
                                                targetDimension = net.minecraft.world.World.OVERWORLD;
                                                LOGGER.info("[Race] Player was in Overworld, returning to Overworld");
                                            }
                                            
                                            // Создаем правильный мир
                                            personalWorld = race.server.world.EnhancedWorldManager.getOrCreateWorld(server, p.getUuid(), playerSeed, targetDimension);
                                        }
                                    }
                                    
                                    // Если не удалось определить мир из сохраненной позиции, создаем Overworld
                                    if (personalWorld == null) {
                                        personalWorld = race.server.world.EnhancedWorldManager.getOrCreateWorld(server, p.getUuid(), playerSeed, net.minecraft.world.World.OVERWORLD);
                                    }
                                    
                                    if (personalWorld != null) {
                                        // Создаем финальную копию для использования в лямбда-выражении
                                        final net.minecraft.server.world.ServerWorld finalPersonalWorld = personalWorld;
                                        
                                        if (returnPoint != null) {
                                            String returnWorldNamespace = returnPoint.worldKey.getValue().getNamespace();
                                            if ("fabric_race".equals(returnWorldNamespace)) {
                                                // Возвращаем игрока в сохранённую позицию с безопасной телепортацией
                                                try {
                                                    LOGGER.info("[Race] Teleporting {} to saved position ({}, {}, {})", p.getName().getString(), returnPoint.x, returnPoint.y, returnPoint.z);
                                                    
                                                    // Безопасная телепортация с задержкой для инициализации мира
                                                    server.execute(() -> {
                                                        try {
                                                            // Убеждаемся, что мир полностью загружен
                                                            if (finalPersonalWorld.getChunkManager() != null) {
                                                                p.teleport(finalPersonalWorld, returnPoint.x, returnPoint.y, returnPoint.z, returnPoint.yaw, returnPoint.pitch);
                                                                p.changeGameMode(returnPoint.gameMode);
                                                                p.sendMessage(net.minecraft.text.Text.literal("Возвращение в ваш персональный мир (сид: " + playerSeed + ") в сохранённую позицию").formatted(net.minecraft.util.Formatting.GREEN), false);
                                                            } else {
                                                                throw new RuntimeException("World not ready");
                                                            }
                                                        } catch (Throwable t2) {
                                                            // Если не удалось вернуться в сохранённую позицию - идём на спавн
                                                            p.teleport(finalPersonalWorld, finalPersonalWorld.getSpawnPos().getX() + 0.5, finalPersonalWorld.getSpawnPos().getY(), finalPersonalWorld.getSpawnPos().getZ() + 0.5, 0, 0);
                                                            p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
                                                            p.sendMessage(net.minecraft.text.Text.literal("Возвращение в ваш персональный мир (сид: " + playerSeed + ") на спавн").formatted(net.minecraft.util.Formatting.YELLOW), false);
                                                        }
                                                    });
                                                } catch (Throwable t) {
                                                    // Если не удалось вернуться в сохранённую позицию - идём на спавн
                                                    p.teleport(finalPersonalWorld, finalPersonalWorld.getSpawnPos().getX() + 0.5, finalPersonalWorld.getSpawnPos().getY(), finalPersonalWorld.getSpawnPos().getZ() + 0.5, 0, 0);
                                                    p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
                                                    p.sendMessage(net.minecraft.text.Text.literal("Возвращение в ваш персональный мир (сид: " + playerSeed + ") на спавн").formatted(net.minecraft.util.Formatting.YELLOW), false);
                                                }
                                            } else {
                                                // Сохранённая позиция не в персональном мире - идём на спавн
                                                server.execute(() -> {
                                                    try {
                                                        if (finalPersonalWorld.getChunkManager() != null) {
                                                            p.teleport(finalPersonalWorld, finalPersonalWorld.getSpawnPos().getX() + 0.5, finalPersonalWorld.getSpawnPos().getY(), finalPersonalWorld.getSpawnPos().getZ() + 0.5, 0, 0);
                                                            p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
                                                            p.sendMessage(net.minecraft.text.Text.literal("Возвращение в ваш персональный мир (сид: " + playerSeed + ") на спавн").formatted(net.minecraft.util.Formatting.YELLOW), false);
                                                        }
                                                    } catch (Throwable t2) {
                                                        LOGGER.warn("[Race] Failed to teleport to spawn: {}", t2.getMessage());
                                                    }
                                                });
                                            }
                                        } else {
                                            // Нет сохранённой позиции - возвращаем игрока на спавн персонального мира
                                            server.execute(() -> {
                                                try {
                                                    if (finalPersonalWorld.getChunkManager() != null) {
                                                        p.teleport(finalPersonalWorld, finalPersonalWorld.getSpawnPos().getX() + 0.5, finalPersonalWorld.getSpawnPos().getY(), finalPersonalWorld.getSpawnPos().getZ() + 0.5, 0, 0);
                                                        p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
                                                    }
                                                } catch (Throwable t2) {
                                                    LOGGER.warn("[Race] Failed to teleport to spawn: {}", t2.getMessage());
                                                }
                                            });
                                            
                                            // Стабилизируем состояние игрока после телепортации
                                            p.setHealth(p.getMaxHealth());
                                            p.getHungerManager().setFoodLevel(20);
                                            p.getHungerManager().setSaturationLevel(20.0f);
                                            p.setAir(p.getMaxAir());
                                            p.setVelocity(0, 0, 0);
                                            p.fallDistance = 0;
                                            p.sendAbilitiesUpdate();
                                            
                                            p.sendMessage(net.minecraft.text.Text.literal("Возвращение в ваш персональный мир (сид: " + playerSeed + ")").formatted(net.minecraft.util.Formatting.GREEN), false);
                                        }
                                        return; // Не идём в хаб
                                    }
                                } catch (Throwable t) {
                                    LOGGER.warn("[Race] Error during personal world teleportation: {}", t.getMessage());
                                }
                            }
                        } catch (Throwable t) {
                            LOGGER.warn("[Race] Error during delayed teleportation: {}", t.getMessage());
                        }
                    });
                    } // Закрываем блок else для ванильного мира
                } else {
                    // Если персонального мира нет или не удалось его загрузить - идём в хаб
                    race.hub.HubManager.teleportToHub(p);
                    p.sendMessage(net.minecraft.text.Text.literal("Добро пожаловать в хаб. Откройте меню (R) или используйте /race seed, /race ready.").formatted(net.minecraft.util.Formatting.YELLOW), false);
                }
            } catch (Throwable t) {
                LOGGER.warn("[Race] Error during player login processing: {}", t.getMessage());
            }
        }); // [1]

        // C2S: игрок прислал выбранный сид — фиксируем для игрока, ACK, создаём личный мир и телепортируем
        ServerPlayNetworking.registerGlobalReceiver(SeedHandshakeC2SPayload.ID, (payload, ctx) -> {
            long requested = payload.seed();
            ctx.server().execute(() -> {
                ServerPlayerEntity p = ctx.player();
                boolean isDedicated = p.getServer() instanceof net.minecraft.server.dedicated.MinecraftDedicatedServer;
                if (!isDedicated) {
                    p.sendMessage(net.minecraft.text.Text.literal("Race: личные миры доступны только на мультиплеер-сервере.").formatted(net.minecraft.util.Formatting.RED), false);
                    ServerPlayNetworking.send(p, new SeedAckS2CPayload(false, "singleplayer_not_supported", requested));
                    return;
                }
                // Сохраняем выбор игрока для группировки (опционально)
                HubManager.setPlayerSeedChoice(p.getUuid(), requested);
                // Подтверждаем именно запрошенный сид
                ServerPlayNetworking.send(p, new SeedAckS2CPayload(true, "", requested));
                // Создаём/получаем персональный мир с этим сидом
                var dst = EnhancedWorldManager.getOrCreateWorld(p.getServer(), p.getUuid(), requested, net.minecraft.world.World.OVERWORLD);
                EnhancedWorldManager.teleportToWorld(p, dst);
                
                // Обновляем последний использованный мир игрока
                race.hub.HubManager.setLastWorldSeed(p.getUuid(), requested);
                
                // Сохраняем точку возврата в персональном мире сразу после телепортации
                try {
                    race.server.world.ReturnPointRegistry.saveCurrent(p);
                    LOGGER.info("[Race] Saved return point for player {} in world {}", p.getName().getString(), dst.getRegistryKey().getValue());
                } catch (Throwable t) {
                    LOGGER.warn("[Race] Failed to save return point for player {}: {}", p.getName().getString(), t.getMessage());
                }
                // Коррекция против спавна в блоках
                try {
                    var bp = p.getBlockPos();
                    if (!p.getServerWorld().isAir(bp) || !p.getServerWorld().isAir(bp.up())) {
                        for (int i = 0; i < 6; i++) {
                            bp = bp.up();
                            if (p.getServerWorld().isAir(bp) && p.getServerWorld().isAir(bp.up())) break;
                        }
                        p.requestTeleport(bp.getX() + 0.5, bp.getY(), bp.getZ() + 0.5);
                    }
                } catch (Throwable ignored) {}
                // Отправляем сигнал клиенту для старта таймера
                ServerPlayNetworking.send(p, new StartRacePayload(requested, System.currentTimeMillis()));
            });
        }); // [1]

        // Регистрируем новые команды
        RaceCommands.register();
        // Регистрируем правила фазы (например, запрет ломать блоки в хабе)
        try { race.server.phase.PhaseEvents.register(); } catch (Throwable ignored) {}

        // Обновление времени гоночных миров теперь происходит в START_SERVER_TICK
        
        // Периодический борд и обновление прогресса
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Обновляем TPS каждый тик
            long tickTime = System.currentTimeMillis() - tickStartTime[0];
            updateTPS(tickTime);
            
            // Автоматическая оптимизация производительности
            performAutoOptimization(server);
            
            if (!active) return;
            
            int playerCount = server.getPlayerManager().getPlayerList().size();
            int adaptiveInterval = getAdaptiveInterval(10, playerCount);
            
            // Адаптивное обновление прогресса игроков
            if (++tick % adaptiveInterval == 0) {
                // Обновляем прогресс всех игроков
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    if (shouldUpdatePlayer(player.getUuid(), 1000)) { // 1 секунда базовый интервал
                        RacePhaseManager.updatePlayerProgress(player);
                        try { race.server.death.DeathEchoManager.recordTick(player); } catch (Throwable ignored) {}
                        lastPlayerUpdate.put(player.getUuid(), System.currentTimeMillis());
                    }
                }
            }
            
            // Убрано: периодический авто-сейв позиций
            // Теперь позиции сохраняются только при явных событиях (spectate/join/return)
            
            // Удаляем временные призрачные фигуры (armor stand) со сроком жизни - реже при высокой нагрузке
            int ghostCleanupInterval = getAdaptiveInterval(100, playerCount);
            if (server.getTicks() % ghostCleanupInterval == 0) {
                try {
                    for (var w : server.getWorlds()) {
                        java.util.List<net.minecraft.entity.decoration.ArmorStandEntity> list = w.getEntitiesByClass(net.minecraft.entity.decoration.ArmorStandEntity.class, new net.minecraft.util.math.Box(-3.0E7, -3.0E7, -3.0E7, 3.0E7, 3.0E7, 3.0E7), as -> as.getCommandTags().contains("race_ghost_fig"));
                        for (var e : list) {
                            if (e.age > 100) e.discard();
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // Отправляем обновленный борд - реже при высокой нагрузке
            int boardInterval = getAdaptiveInterval(20, playerCount);
            if (server.getTicks() % boardInterval == 0) {
                var rows = RacePhaseManager.getRaceBoardData(server);
                var payload = new RaceBoardPayload(rows);
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    ServerPlayNetworking.send(p, payload);
                }
            }
            
            // Отправляем TPS информацию если включено - реже при высокой нагрузке
            if (tpsDisplayEnabled) {
                int tpsInterval = getAdaptiveInterval(20, playerCount);
                if (server.getTicks() % tpsInterval == 0) {
                    var tpsPayload = new race.net.TpsPayload(currentTPS, true);
                    for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                        ServerPlayNetworking.send(p, tpsPayload);
                    }
                }
            }
            
            // Периодические призрачные фигуры после смерти - реже при высокой нагрузке
            int deathEchoInterval = getAdaptiveInterval(20, playerCount);
            if (server.getTicks() % deathEchoInterval == 0) {
                try { race.server.death.DeathEchoManager.tickGhosts(server); } catch (Throwable ignored) {}
            }

            // Лайв‑точки параллельных игроков - значительно реже при высокой нагрузке
            int parallelInterval = getAdaptiveInterval(40, playerCount);
            if (server.getTicks() % parallelInterval == 0 && race.server.RaceServerInit.isDisplayParallelPlayers()) {
                for (ServerPlayerEntity viewer : server.getPlayerManager().getPlayerList()) {
                    String viewerKey = viewer.getServerWorld().getRegistryKey().getValue().toString();
                    if (!viewerKey.startsWith("fabric_race:")) continue;
                    long viewerSeed = parseSeedFromWorldKey(viewerKey);
                    boolean viewerNether = viewer.getServerWorld().getDimensionEntry().matchesKey(net.minecraft.world.dimension.DimensionTypes.THE_NETHER);
                    boolean viewerEnd = viewer.getServerWorld().getDimensionEntry().matchesKey(net.minecraft.world.dimension.DimensionTypes.THE_END);
                    java.util.ArrayList<race.net.ParallelPlayersPayload.Point> pts = new java.util.ArrayList<>();
                    for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
                        if (other == viewer) continue;
                        String otherKey = other.getServerWorld().getRegistryKey().getValue().toString();
                        if (!otherKey.startsWith("fabric_race:")) continue;
                        long otherSeed = parseSeedFromWorldKey(otherKey);
                        if (otherSeed != viewerSeed) continue;
                        boolean otherNether = other.getServerWorld().getDimensionEntry().matchesKey(net.minecraft.world.dimension.DimensionTypes.THE_NETHER);
                        boolean otherEnd = other.getServerWorld().getDimensionEntry().matchesKey(net.minecraft.world.dimension.DimensionTypes.THE_END);
                        if (viewerNether != otherNether || viewerEnd != otherEnd) continue; // разное измерение
                        // Если мы уже в одном и том же инстансе мира — это союзник рядом, призрак не рисуем
                        if (other.getServerWorld().getRegistryKey().equals(viewer.getServerWorld().getRegistryKey())) continue;
                        byte type = detectActivityType(other);
                        String name = other.getGameProfile().getName();
                        pts.add(new race.net.ParallelPlayersPayload.Point(name, other.getX(), other.getY(), other.getZ(), type));
                    }
                    if (!pts.isEmpty()) {
                        race.net.ParallelPlayersPayload pp = new race.net.ParallelPlayersPayload(pts);
                        ServerPlayNetworking.send(viewer, pp);
                    }
                }
            }
        }); // [1]

        // Обработка отложенных join-запросов (каждый тик)
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (pendingJoins.isEmpty()) return;
            
            // ДОПОЛНИТЕЛЬНАЯ ДИАГНОСТИКА: Логируем все pending joins
            LOGGER.info("[Race] Processing pending joins, count: {}", pendingJoins.size());
            for (var entry : pendingJoins.entrySet()) {
                java.util.UUID srcId = entry.getKey();
                PendingJoin pj = entry.getValue();
                ServerPlayerEntity src = server.getPlayerManager().getPlayer(srcId);
                ServerPlayerEntity dst = server.getPlayerManager().getPlayer(pj.target());
                
                if (src != null && dst != null) {
                    String srcWorld = src.getServerWorld().getRegistryKey().getValue().toString();
                    String dstWorld = dst.getServerWorld().getRegistryKey().getValue().toString();
                    LOGGER.info("[Race] Pending join: {} (in {}) -> {} (in {}), ticks left: {}", 
                        src.getName().getString(), srcWorld, 
                        dst.getName().getString(), dstWorld, 
                        pj.ticksLeft());
                }
            }
            
            java.util.ArrayList<java.util.UUID> done = new java.util.ArrayList<>();
            for (var e : pendingJoins.entrySet()) {
                java.util.UUID srcId = e.getKey();
                PendingJoin pj = e.getValue();
                int left = pj.ticksLeft - 1;
                if (left <= 0) {
                    ServerPlayerEntity src = server.getPlayerManager().getPlayer(srcId);
                    ServerPlayerEntity dst = server.getPlayerManager().getPlayer(pj.target());
                    if (src != null && dst != null && dst.isAlive() && !dst.isDead()) {
                        try {
                            if (race.server.world.ReturnPointRegistry.get(src) == null) {
                                race.server.world.ReturnPointRegistry.saveCurrent(src);
                            }
                        } catch (Throwable ignored) {}
                        src.teleport(dst.getServerWorld(), dst.getX(), dst.getY(), dst.getZ(), dst.getYaw(), dst.getPitch());
                        // ИСПРАВЛЕННАЯ логика присоединения с учетом типа мира
                        try {
                            String targetWorldKey = dst.getServerWorld().getRegistryKey().getValue().toString();
                            String sourceWorldKey = src.getServerWorld().getRegistryKey().getValue().toString();
                            
                            LOGGER.info("[Race] JOIN: {} -> {}, target world: {}, same world: {}", 
                                src.getName().getString(), dst.getName().getString(), 
                                targetWorldKey, targetWorldKey.equals(sourceWorldKey));

                            // КЛЮЧЕВОЕ ИСПРАВЛЕНИЕ: Проверяем тип мира и состояние гонки
                            boolean isPersonalWorld = targetWorldKey.startsWith("fabric_race:p_");
                            boolean isSlotWorld = targetWorldKey.contains("slot") && targetWorldKey.startsWith("fabric_race:");
                            boolean isRaceWorld = targetWorldKey.startsWith("fabric_race:");
                            boolean sameWorld = targetWorldKey.equals(sourceWorldKey);
                            
                            boolean dstFrozen = isFrozen(dst.getUuid());
                            boolean dstStarted = personalStarted.contains(dst.getUuid());
                            boolean srcStarted = personalStarted.contains(src.getUuid());
                            boolean raceActive = active;
                            
                            LOGGER.info("[Race] World analysis - personal: {}, slot: {}, race: {}, same: {}, dst frozen: {}, dst started: {}, src started: {}, race active: {}", 
                                isPersonalWorld, isSlotWorld, isRaceWorld, sameWorld, dstFrozen, dstStarted, srcStarted, raceActive);

                            // ПРИОРИТЕТНАЯ ЛОГИКА: Проверяем состояние присоединяющегося игрока
                            if (srcStarted && raceActive) {
                                // Присоединяющийся игрок уже начал гонку - НЕ ЗАМОРАЖИВАЕМ
                                frozenUntilStart.remove(src.getUuid());
                                freezePos.remove(src.getUuid());
                                src.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
                                src.sendMessage(net.minecraft.text.Text.literal("Присоединились к игроку во время активной гонки!")
                                    .formatted(net.minecraft.util.Formatting.GREEN), false);
                                LOGGER.info("[Race] Player {} joined {} without freezing (src already started race)", 
                                    src.getName().getString(), dst.getName().getString());
                                    
                            } else if (sameWorld || isPersonalWorld || isSlotWorld) {
                                // В персональных/слотовых мирах или том же мире - НЕ ЗАМОРАЖИВАЕМ
                                frozenUntilStart.remove(src.getUuid());
                                freezePos.remove(src.getUuid());
                                src.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
                                
                                if (sameWorld) {
                                    src.sendMessage(net.minecraft.text.Text.literal("Присоединились к игроку в том же мире!")
                                        .formatted(net.minecraft.util.Formatting.GREEN), false);
                                } else if (isPersonalWorld) {
                                    src.sendMessage(net.minecraft.text.Text.literal("Присоединились к персональному миру игрока!")
                                        .formatted(net.minecraft.util.Formatting.GREEN), false);
                                } else {
                                    src.sendMessage(net.minecraft.text.Text.literal("Присоединились к групповому миру!")
                                        .formatted(net.minecraft.util.Formatting.GREEN), false);
                                }
                                
                                LOGGER.info("[Race] Player {} joined {} without freezing (world-based logic)", 
                                    src.getName().getString(), dst.getName().getString());
                                    
                            } else if (isRaceWorld && (dstStarted || raceActive)) {
                                // Присоединение к активной гонке в любом race-мире - НЕ ЗАМОРАЖИВАЕМ
                                frozenUntilStart.remove(src.getUuid());
                                freezePos.remove(src.getUuid());
                                src.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
                                src.sendMessage(net.minecraft.text.Text.literal("Присоединились к активной гонке!")
                                    .formatted(net.minecraft.util.Formatting.GREEN), false);
                                LOGGER.info("[Race] Player {} joined active race in {} without freezing", 
                                    src.getName().getString(), targetWorldKey);
                                    
                            } else if (dstStarted && raceActive) {
                                // Стандартная логика для активной гонки
                                frozenUntilStart.remove(src.getUuid());
                                freezePos.remove(src.getUuid());
                                src.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
                                src.sendMessage(net.minecraft.text.Text.literal("Присоединились к активному гонщику!")
                                    .formatted(net.minecraft.util.Formatting.GREEN), false);
                                LOGGER.info("[Race] Player {} joined active racer {} without freezing", 
                                    src.getName().getString(), dst.getName().getString());
                                    
                            } else if (dstFrozen && !raceActive) {
                                // Замораживаем только если цель заморожена и гонка не активна
                                freezePlayerUntilStart(src);
                                src.sendMessage(net.minecraft.text.Text.literal("Присоединились к ожидающему игроку. Ждите начала гонки.")
                                    .formatted(net.minecraft.util.Formatting.YELLOW), false);
                                LOGGER.info("[Race] Player {} joined frozen player {} and frozen too", 
                                    src.getName().getString(), dst.getName().getString());
                                    
                            } else {
                                // По умолчанию НЕ замораживаем
                                frozenUntilStart.remove(src.getUuid());
                                freezePos.remove(src.getUuid());
                                src.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
                                src.sendMessage(net.minecraft.text.Text.literal("Присоединились к игроку.")
                                    .formatted(net.minecraft.util.Formatting.YELLOW), false);
                                LOGGER.info("[Race] Player {} joined player {} without freezing (default case)", 
                                    src.getName().getString(), dst.getName().getString());
                            }
                            
                        } catch (Throwable ignored) {}
                        try { src.sendMessage(net.minecraft.text.Text.literal("Присоединились к " + dst.getName().getString()).formatted(net.minecraft.util.Formatting.GREEN), false); } catch (Throwable ignored) {}
                        try { dst.sendMessage(net.minecraft.text.Text.literal(src.getName().getString() + " присоединился."), false); } catch (Throwable ignored) {}
                        
                        // Отправляем статус завершения join-запроса
                        try {
                            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(src, new race.net.JoinRequestStatusPayload(false, ""));
                        } catch (Throwable ignored) {}
                    }
                    done.add(srcId);
                } else {
                    pendingJoins.put(srcId, new PendingJoin(pj.target(), left));
                }
            }
            for (var id : done) pendingJoins.remove(id);
        });

        // Ограничитель кол-ва агрессивных мобов вокруг игроков в персональных мирах
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int playerCount = server.getPlayerManager().getPlayerList().size();
            int mobCheckInterval = getAdaptiveInterval(100, playerCount); // Адаптивный интервал
            
            if (server.getTicks() % mobCheckInterval != 0) return;
            
            for (ServerPlayerEntity viewer : server.getPlayerManager().getPlayerList()) {
                String key = viewer.getServerWorld().getRegistryKey().getValue().toString();
                if (!key.startsWith("fabric_race:")) continue;
                
                // Адаптивный радиус в зависимости от нагрузки
                int radius = performanceLevel.get() == 2 ? 32 : 48; // Меньший радиус при высокой нагрузке
                var world = viewer.getServerWorld();
                net.minecraft.util.math.Box box = new net.minecraft.util.math.Box(
                        viewer.getX() - radius, viewer.getY() - 32, viewer.getZ() - radius,
                        viewer.getX() + radius, viewer.getY() + 32, viewer.getZ() + radius
                );
                
                // Ограничиваем поиск мобов при высокой нагрузке
                if (performanceLevel.get() == 2 && playerCount > 8) {
                    continue; // Пропускаем проверку мобов при высокой нагрузке
                }
                
                java.util.List<net.minecraft.entity.mob.MobEntity> mobs = world.getEntitiesByClass(net.minecraft.entity.mob.MobEntity.class, box, m -> m.isAlive() && m.getType().getSpawnGroup() == net.minecraft.entity.SpawnGroup.MONSTER);
                int limit = performanceLevel.get() == 2 ? 16 : 24; // Меньший лимит при высокой нагрузке
                if (mobs.size() > limit) {
                    int toCull = mobs.size() - limit;
                    // Удаляем самых дальних и взрослых, сохраняем боссов и рейдовых
                    mobs.sort(java.util.Comparator.comparingDouble(m -> m.squaredDistanceTo(viewer)));
                    for (int i = mobs.size() - 1; i >= 0 && toCull > 0; i--) {
                        var m = mobs.get(i);
                        if (m instanceof net.minecraft.entity.boss.WitherEntity || m instanceof net.minecraft.entity.boss.dragon.EnderDragonEntity) continue;
                        if (m.hasVehicle() || m.hasPassengers()) continue;
                        if (m.getCommandTags().contains("race_protect")) continue;
                        m.discard();
                        toCull--;
                    }
                }
            }
        });

        // Лайв‑силуэты параллельных игроков — всегда (в персональных мирах), не только во время гонки
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int playerCount = server.getPlayerManager().getPlayerList().size();
            int parallelInterval = getAdaptiveInterval(40, playerCount); // Адаптивный интервал
            
            // Дополнительная оптимизация: пропускаем если слишком много игроков
            if (playerCount > 10 && performanceLevel.get() == 2) {
                return; // Полностью отключаем при высокой нагрузке и большом количестве игроков
            }
            
            if (server.getTicks() % parallelInterval != 0) return;
            if (!race.server.RaceServerInit.isDisplayParallelPlayers()) return;
            
            for (ServerPlayerEntity viewer : server.getPlayerManager().getPlayerList()) {
                String viewerKey = viewer.getServerWorld().getRegistryKey().getValue().toString();
                if (!viewerKey.startsWith("fabric_race:")) continue;
                long viewerSeed = parseSeedFromWorldKey(viewerKey);
                boolean viewerNether = viewer.getServerWorld().getDimensionEntry().matchesKey(net.minecraft.world.dimension.DimensionTypes.THE_NETHER);
                boolean viewerEnd = viewer.getServerWorld().getDimensionEntry().matchesKey(net.minecraft.world.dimension.DimensionTypes.THE_END);
                java.util.ArrayList<race.net.ParallelPlayersPayload.Point> pts = new java.util.ArrayList<>();
                
                // Ограничиваем количество проверяемых игроков при высокой нагрузке
                int maxChecks = performanceLevel.get() == 2 ? 5 : Integer.MAX_VALUE;
                int checks = 0;
                
                for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
                    if (other == viewer) continue;
                    if (checks >= maxChecks) break; // Ограничиваем количество проверок
                    checks++;
                    
                    String otherKey = other.getServerWorld().getRegistryKey().getValue().toString();
                    if (!otherKey.startsWith("fabric_race:")) continue;
                    long otherSeed = parseSeedFromWorldKey(otherKey);
                    if (otherSeed != viewerSeed) continue;
                    boolean otherNether = other.getServerWorld().getDimensionEntry().matchesKey(net.minecraft.world.dimension.DimensionTypes.THE_NETHER);
                    boolean otherEnd = other.getServerWorld().getDimensionEntry().matchesKey(net.minecraft.world.dimension.DimensionTypes.THE_END);
                    if (viewerNether != otherNether || viewerEnd != otherEnd) continue; // разное измерение
                    // Уже в одном инстансе мира → союзник, дымку не рисуем
                    if (other.getServerWorld().getRegistryKey().equals(viewer.getServerWorld().getRegistryKey())) continue;
                    byte type = detectActivityType(other);
                    String name = other.getGameProfile().getName();
                    pts.add(new race.net.ParallelPlayersPayload.Point(name, other.getX(), other.getY(), other.getZ(), type));
                }
                if (!pts.isEmpty()) {
                    race.net.ParallelPlayersPayload pp = new race.net.ParallelPlayersPayload(pts);
                    ServerPlayNetworking.send(viewer, pp);
                }
            }
        });

        // Вспомогательная: парсим сид из ключа мира fabric_race:slotX_overworld_s<seed>
        

        // Принудительная заморозка игроков в персональных мирах до личного старта таймера
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                String key = p.getServerWorld().getRegistryKey().getValue().toString();
                if (!key.startsWith("fabric_race:")) continue;
                java.util.UUID id = p.getUuid();
                if (!frozenUntilStart.contains(id)) continue;
                
                // ДОПОЛНИТЕЛЬНАЯ ПРОВЕРКА: Убеждаемся, что игрок действительно должен быть заморожен
                // Проверяем, что игрок не начал свою гонку
                if (personalStarted.contains(id)) {
                    // Игрок уже начал гонку, но почему-то все еще в frozenUntilStart - исправляем
                    frozenUntilStart.remove(id);
                    freezePos.remove(id);
                    LOGGER.warn("[Race] FIXED: Player {} was frozen but already started race - removing from freeze list", p.getName().getString());
                    continue;
                }
                
                net.minecraft.util.math.BlockPos base = freezePos.getOrDefault(id, p.getBlockPos());
                p.setVelocity(0, 0, 0);
                p.requestTeleport(base.getX() + 0.5, Math.max(base.getY(), p.getServerWorld().getBottomY() + 1), base.getZ() + 0.5);
            }
        });

        // Быстрый детектор зависаний: печатаем стеки без ожидания watchdog
        final long[] lastTickStart = new long[]{System.currentTimeMillis()};
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            lastTickStart[0] = System.currentTimeMillis();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long dt = System.currentTimeMillis() - lastTickStart[0];
            // Порог 3000 мс: печатаем стеки один раз в 10 секунд максимум
            if (dt >= 3000 && server.getTicks() % 200 == 0) {
                System.err.println("[Race][HangDetector] Tick took " + dt + " ms — dumping stacks:");
                // Снимаем дамп всех потоков
                for (java.util.Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                    Thread t = e.getKey();
                    if (t == null) continue;
                    // Интересны: Server thread, Server Watchdog, Netty IO, Worker-Main
                    String name = t.getName();
                    if (!(name.equals("Server thread") || name.contains("Watchdog") || name.contains("Netty Server IO") || name.contains("Worker-Main"))) continue;
                    System.err.println("-- Thread: " + name + " (" + t.getState() + ")");
                    for (StackTraceElement el : e.getValue()) {
                        System.err.println("    at " + el);
                    }
                }
            }
        });

        // Приём активности и прогресса от клиента (обновляем activity в менеджере)
        ServerPlayNetworking.registerGlobalReceiver(PlayerProgressPayload.ID, (payload, ctx) -> {
            ctx.server().execute(() -> {
                var p = ctx.player();
                var data = race.hub.ProgressSyncManager.getPlayerProgress(p.getUuid());
                if (data != null) {
                    data.setCurrentTime(System.currentTimeMillis());
                    data.setCurrentStage(payload.currentStage());
                    data.setActivity(payload.activity());
                }
            });
        });

        // Призраки смерти: запись на смерть и восстановление в мирах
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.ALLOW_DEATH.register((player, source, amount) -> {
            try { DeathEchoManager.onPlayerDeath(player, source); } catch (Throwable ignored) {}
            return true; // не отменяем смерть
        });
        
        // Отслеживание достижений при смерти мобов
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity instanceof net.minecraft.entity.boss.dragon.EnderDragonEntity dragon) {
                // Проверяем, есть ли игроки рядом с драконом
                for (net.minecraft.entity.player.PlayerEntity player : entity.getWorld().getPlayers()) {
                    if (player instanceof ServerPlayerEntity serverPlayer) {
                        String worldKey = serverPlayer.getServerWorld().getRegistryKey().getValue().toString();
                        if (worldKey.startsWith("fabric_race:")) {
                            double distance = serverPlayer.squaredDistanceTo(dragon);
                            if (distance < 100.0) { // В радиусе 10 блоков
                                AchievementManager.checkAndGrantAchievements(serverPlayer, "kill_dragon", serverPlayer.getBlockPos());
                            }
                        }
                    }
                }
            }
            return true; // не отменяем смерть
        });
        
        // Перехватываем respawn для возврата в персональный мир
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            try {
                // Проверяем, был ли игрок в кастомном мире
                String oldWorldKey = oldPlayer.getServerWorld().getRegistryKey().getValue().toString();
                if (oldWorldKey.startsWith("fabric_race:")) {
                    // ИСПРАВЛЕНИЕ: Сохраняем оригинальный мир игрока
                    savePlayerOriginalWorld(newPlayer.getUuid(), oldWorldKey);
                    
                    // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Всегда возвращаем в основной персональный мир (Overworld)
                    // НЕ в End или Nether, даже если игрок умер там
                    long playerSeed = race.hub.HubManager.getPlayerSeedChoice(newPlayer.getUuid());
                    if (playerSeed >= 0) {
                        // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Используем оригинальный слот игрока
                        String originalWorldKey = getPlayerOriginalWorld(newPlayer.getUuid());
                        int originalSlot = extractSlotFromWorldKey(originalWorldKey);
                        
                        LOGGER.info("[Race] Player {} died in {} world, original slot: {}", 
                            newPlayer.getName().getString(), oldWorldKey, originalSlot);
                        
                        net.minecraft.server.world.ServerWorld mainPersonalWorld = null;
                        
                        if (originalSlot > 0) {
                            // Возвращаем в ОРИГИНАЛЬНЫЙ слот
                            mainPersonalWorld = race.server.world.EnhancedWorldManager
                                .getOrCreateWorldForGroup(newPlayer.getServer(), originalSlot, playerSeed, net.minecraft.world.World.OVERWORLD);
                        } else {
                            // Фолбэк - используем стандартный метод
                            mainPersonalWorld = race.server.world.EnhancedWorldManager.getOrCreateWorld(
                                newPlayer.getServer(), newPlayer.getUuid(), playerSeed, net.minecraft.world.World.OVERWORLD);
                        }
                        
                        if (mainPersonalWorld != null) {
                            LOGGER.info("[Race] Player {} died in {} world, setting respawn to slot {} world: {}", 
                                newPlayer.getName().getString(), oldWorldKey, originalSlot, mainPersonalWorld.getRegistryKey().getValue());
                            
                            // Устанавливаем spawn point в основной персональный мир
                            newPlayer.setSpawnPoint(mainPersonalWorld.getRegistryKey(), mainPersonalWorld.getSpawnPos(), 0.0f, false, true);
                        }
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("[Race] Error during respawn setup: {}", t.getMessage());
            }
        });
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            for (net.minecraft.server.world.ServerWorld w : server.getWorlds()) {
                try { DeathEchoManager.populateWorld(w); } catch (Throwable ignored) {}
            }
        });

        // После респавна гарантируем корректный режим игры и возврат в персональный мир
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            String key = newPlayer.getServerWorld().getRegistryKey().getValue().getNamespace();
            String worldKey = newPlayer.getServerWorld().getRegistryKey().getValue().toString();
            
            // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Проверяем, что игрок в основном персональном мире (Overworld)
            if ("fabric_race".equals(key) && !worldKey.contains("nether") && !worldKey.contains("end")) {
                // Игрок в основном персональном мире - только восстанавливаем здоровье
                try { newPlayer.changeGameMode(net.minecraft.world.GameMode.SURVIVAL); } catch (Throwable ignored) {}
                try {
                    newPlayer.setHealth(newPlayer.getMaxHealth());
                    newPlayer.getHungerManager().setFoodLevel(20);
                    newPlayer.getHungerManager().setSaturationLevel(20);
                    newPlayer.extinguish();
                } catch (Throwable ignored) {}
                LOGGER.info("[Race] Player {} respawned in main personal world, restored health", newPlayer.getName().getString());
            } else if ("fabric_race".equals(key) && (worldKey.contains("nether") || worldKey.contains("end"))) {
                // Игрок в End/Nether персональном мире - возвращаем в основной
                try {
                    long playerSeed = race.hub.HubManager.getPlayerSeedChoice(newPlayer.getUuid());
                    if (playerSeed >= 0) {
                        // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Используем оригинальный слот игрока
                        String originalWorldKey = getPlayerOriginalWorld(newPlayer.getUuid());
                        int originalSlot = extractSlotFromWorldKey(originalWorldKey);
                        
                        LOGGER.info("[Race] Player {} died in {} world, original slot: {}", 
                            newPlayer.getName().getString(), worldKey, originalSlot);
                        
                        net.minecraft.server.world.ServerWorld mainPersonalWorld = null;
                        
                        if (originalSlot > 0) {
                            // Возвращаем в ОРИГИНАЛЬНЫЙ слот
                            mainPersonalWorld = race.server.world.EnhancedWorldManager
                                .getOrCreateWorldForGroup(newPlayer.getServer(), originalSlot, playerSeed, net.minecraft.world.World.OVERWORLD);
                        } else {
                            // Фолбэк - используем стандартный метод
                            mainPersonalWorld = race.server.world.EnhancedWorldManager.getOrCreateWorld(
                                newPlayer.getServer(), newPlayer.getUuid(), playerSeed, net.minecraft.world.World.OVERWORLD);
                        }
                        
                        if (mainPersonalWorld != null) {
                            race.server.world.EnhancedWorldManager.teleportToWorld(newPlayer, mainPersonalWorld);
                            newPlayer.sendMessage(net.minecraft.text.Text.literal("Возвращение в основной персональный мир после смерти в " + 
                                (worldKey.contains("end") ? "End" : "Nether")).formatted(net.minecraft.util.Formatting.GREEN), false);
                            LOGGER.info("[Race] Player {} returned to main personal world from {} to slot {}", 
                                newPlayer.getName().getString(), worldKey, originalSlot);
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.warn("[Race] Error returning player to main world: {}", t.getMessage());
                }
            } else {
                // Если игрок respawn в стандартном мире, но у него есть персональный мир - возвращаем его туда
                try {
                    long playerSeed = race.hub.HubManager.getPlayerSeedChoice(newPlayer.getUuid());
                    if (playerSeed >= 0) {
                        LOGGER.info("[Race] Player {} respawned in standard world, returning to personal world with seed {}", 
                            newPlayer.getName().getString(), playerSeed);
                        
                        // КЛЮЧЕВОЕ ИСПРАВЛЕНИЕ: Используем оригинальный слот игрока
                        String originalWorldKey = getPlayerOriginalWorld(newPlayer.getUuid());
                        int originalSlot = extractSlotFromWorldKey(originalWorldKey);
                        
                        LOGGER.info("[Race] Player {} original world: {}, slot: {}", 
                            newPlayer.getName().getString(), originalWorldKey, originalSlot);
                        
                        net.minecraft.server.world.ServerWorld personalWorld = null;
                        
                        if (originalSlot > 0) {
                            // Возвращаем в ОРИГИНАЛЬНЫЙ слот
                            personalWorld = race.server.world.EnhancedWorldManager
                                .getOrCreateWorldForGroup(newPlayer.getServer(), originalSlot, playerSeed, net.minecraft.world.World.OVERWORLD);
                            
                            LOGGER.info("[Race] Player {} returned to original slot {} world {}", 
                                newPlayer.getName().getString(), originalSlot, personalWorld.getRegistryKey().getValue());
                        } else {
                            // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: НЕ ищем по seed без проверки слота!
                            // Это может привести к попаданию игрока в чужой слот
                            LOGGER.warn("[Race] No original slot found for player {}, NOT searching by seed alone to avoid slot mismatch", 
                                newPlayer.getName().getString());
                            LOGGER.warn("[Race] Will create new world with correct slot assignment for player {}", 
                                newPlayer.getName().getString());
                        }
                        
                        if (personalWorld != null) {
                            // Телепортируем игрока в правильный персональный мир
                            race.server.world.EnhancedWorldManager.teleportToWorld(newPlayer, personalWorld);
                            newPlayer.sendMessage(net.minecraft.text.Text.literal("Возвращение в ваш персональный мир после смерти").formatted(net.minecraft.util.Formatting.GREEN), false);
                            LOGGER.info("[Race] Teleported player {} to personal world: {}", 
                                newPlayer.getName().getString(), personalWorld.getRegistryKey().getValue());
                        } else {
                            LOGGER.warn("[Race] No existing personal world found for player {} with seed {}", 
                                newPlayer.getName().getString(), playerSeed);
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.warn("[Race] Error during respawn to personal world: {}", t.getMessage());
                }
            }
        });

        // Периодическая рассылка списка сидов и миров игрокам
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Адаптивный интервал для рассылки списка
            int playerCount = server.getPlayerManager().getPlayerList().size();
            int listInterval = playerCount > 4 ? 80 : 40; // Реже обновляем при большем количестве игроков
            if (server.getTicks() % listInterval != 0) return;
            java.util.ArrayList<SeedLobbyEntry> list = new java.util.ArrayList<>();
            // Собираем инфо и формируем группы игроков по ключу мира
            java.util.Map<String, java.util.List<ServerPlayerEntity>> groups = new java.util.HashMap<>();
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                long s = race.hub.HubManager.getPlayerSeedChoice(p.getUuid());
                String key = p.getServerWorld().getRegistryKey().getValue().toString();
                list.add(new SeedLobbyEntry(p.getName().getString(), s, key));
                groups.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(p);
            }
            var payload = new SeedLobbyListPayload(java.util.List.copyOf(list));
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                ServerPlayNetworking.send(p, payload);
            }

            // Объявления команд по конкретному инстансу мира fabric_race:slot*
            for (var e : groups.entrySet()) {
                String worldKey = e.getKey();
                if (!worldKey.startsWith("fabric_race:slot")) continue;
                java.util.List<ServerPlayerEntity> players = e.getValue();
                if (players.size() < 2) continue;
                java.util.Set<java.util.UUID> current = new java.util.HashSet<>();
                for (ServerPlayerEntity p : players) current.add(p.getUuid());
                java.util.Set<java.util.UUID> prev = announcedTeams.get(worldKey);
                if (prev != null && prev.equals(current)) continue; // уже объявляли этот состав
                // Формируем сообщение
                String names = String.join(", ", players.stream().map(pl -> pl.getName().getString()).toList());
                net.minecraft.text.Text msg = net.minecraft.text.Text.literal("Сформирована команда: " + names)
                        .formatted(net.minecraft.util.Formatting.GREEN);
                for (ServerPlayerEntity p : players) {
                    p.sendMessage(msg, false);
                }
                announcedTeams.put(worldKey, current);
            }
            // Очистка записей для миров, где больше нет игроков
            announcedTeams.entrySet().removeIf(en -> !groups.containsKey(en.getKey()));
        });

        // Перед остановкой сервера выгружаем личные миры, чтобы избежать падения в тикет-менеджере
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            // Останавливаем фоновые выгрузки и синхронно очищаем все личные миры
            EnhancedWorldManager.beginShutdownAndFlush(server);
            
            // Сохраняем данные хаба перед остановкой
            try {
                race.hub.HubManager.forceSaveHubData();
                LOGGER.info("[Race] Hub data saved before server shutdown");
            } catch (Exception e) {
                LOGGER.warn("[Race] Failed to save hub data on shutdown: {}", e.getMessage());
            }
        });

        // Освобождаем слот игрока при выходе
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            try {
                if (handler.player != null) {
                    // Проверяем, что это реальное отключение, а не смерть
                    boolean isAlive = handler.player.isAlive() && !handler.player.isDead();
                    LOGGER.info("[Race] Player {} disconnected, alive: {}, world: {}",
                            handler.player.getName().getString(), isAlive,
                            handler.player.getServerWorld().getRegistryKey().getValue());

                    // Сохраняем мир перед освобождением слота
                    ServerWorld playerWorld = handler.player.getServerWorld();
                    if (playerWorld != null && playerWorld.getRegistryKey().getValue().getNamespace().equals("fabric_race")) {
                        // Сохраняем позицию игрока перед отключением
                        try {
                            race.server.world.ReturnPointRegistry.saveCurrent(handler.player);
                            LOGGER.info("[Race] Saved player position before disconnect: {} at ({}, {}, {})",
                                    handler.player.getName().getString(),
                                    handler.player.getX(), handler.player.getY(), handler.player.getZ());
                        } catch (Throwable t) {
                            LOGGER.warn("[Race] Failed to save player position: {}", t.getMessage());
                        }
                    } else if (playerWorld != null && playerWorld.getRegistryKey() == net.minecraft.world.World.OVERWORLD) {
                        // Игрок был в хабе (ванильный Overworld) - сохраняем специальную метку
                        try {
                            race.server.world.ReturnPointRegistry.saveHubPoint(handler.player);
                            LOGGER.info("[Race] Player {} was in hub, marked for hub return", handler.player.getName().getString());
                        } catch (Throwable t) {
                            LOGGER.warn("[Race] Failed to mark player for hub return: {}", t.getMessage());
                        }
                    }

                    try {
                        // Принудительно сохраняем чанки мира
                        playerWorld.save(null, true, true);
                        LOGGER.info("[Race] Saved world {} for player {} before disconnect",
                                playerWorld.getRegistryKey().getValue(), handler.player.getName().getString());
                    } catch (Throwable t) {
                        LOGGER.warn("[Race] Failed to save world {} for player {}: {}",
                                playerWorld.getRegistryKey().getValue(), handler.player.getName().getString(), t.getMessage());
                    }
                }

                // ИСПРАВЛЕНИЕ: НЕ освобождаем слоты для персональных миров
                // Слоты должны оставаться за игроками даже после отключения
                // EnhancedWorldManager.releasePlayerSlot(server, handler.player.getUuid());
                LOGGER.info("[Race] Player {} disconnected, keeping slot for personal world", handler.player.getName().getString());

            } catch (Throwable ignored) {
            }
        });

        // После смены измерения (портал в Нижний мир) — защита от крыши ада
        // Защита от "второго" телепорта в Нижнем мире и от крыши ада
        final java.util.Map<java.util.UUID, Long> netherAdjustCooldown = new java.util.concurrent.ConcurrentHashMap<>();
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
            // Отслеживаем достижения при смене мира
            String originKey = origin.getRegistryKey().getValue().toString();
            String destinationKey = destination.getRegistryKey().getValue().toString();
            
            // Проверяем переходы в кастомные миры
            if (destinationKey.startsWith("fabric_race:")) {
                // Инициализируем время мира если его еще нет (используем фиксированное время утра)
                initWorldIfAbsent(destination, 1000L);
                
                // Переход в кастомный Nether
                if (destination.getDimensionEntry().matchesKey(net.minecraft.world.dimension.DimensionTypes.THE_NETHER)) {
                    AchievementManager.checkAndGrantAchievements(player, "enter_nether", player.getBlockPos());
                }
                // Переход в кастомный End
                else if (destination.getDimensionEntry().matchesKey(net.minecraft.world.dimension.DimensionTypes.THE_END)) {
                    AchievementManager.checkAndGrantAchievements(player, "enter_end", player.getBlockPos());
                }
            }
            
            if (!destination.getDimensionEntry().matchesKey(net.minecraft.world.dimension.DimensionTypes.THE_NETHER)) return;
            
            // Пропускаем персональные миры - они обрабатываются EnhancedWorldManager
            String worldKey = destination.getRegistryKey().getValue().toString();
            if (worldKey.startsWith("fabric_race:")) {
                return; // Персональные миры уже правильно обрабатываются
            }
            
            var world = (net.minecraft.server.world.ServerWorld) destination;
            long now = System.currentTimeMillis();
            Long last = netherAdjustCooldown.get(player.getUuid());
            if (last != null && now - last < 2000L) return; // анти-дребезг: не чаще раза в 2 сек
            netherAdjustCooldown.put(player.getUuid(), now);

            net.minecraft.util.math.BlockPos pos = player.getBlockPos();
            // Если стоим внутри портала (или рядом), не трогаем — ваниль сама доведёт
            try {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 2; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            net.minecraft.util.math.BlockPos p = pos.add(dx, dy, dz);
                            if (world.getBlockState(p).isOf(net.minecraft.block.Blocks.NETHER_PORTAL)) return;
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // Если уже на нормальной высоте и в безопасном пространстве/портале — ничего не делаем
            try {
                var bsHere = world.getBlockState(pos);
                var bsUp = world.getBlockState(pos.up());
                boolean hereSafe = bsHere.isAir() || bsHere.isOf(net.minecraft.block.Blocks.NETHER_PORTAL);
                boolean upSafe = bsUp.isAir() || bsUp.isOf(net.minecraft.block.Blocks.NETHER_PORTAL);
                if (pos.getY() < 123 && hereSafe && upSafe) return;
            } catch (Throwable ignored) {}

            // Иначе ищем безопасную высоту вниз от текущей (до 32)
            int targetY = -1;
            int cap = Math.min(120, Math.max(32, pos.getY()));
            for (int y = cap; y >= 32; y--) {
                net.minecraft.util.math.BlockPos p = new net.minecraft.util.math.BlockPos(pos.getX(), y, pos.getZ());
                var below = p.down();
                var belowState = world.getBlockState(below);
                if (world.isAir(p) && world.isAir(p.up()) && belowState.isSolidBlock(world, below) && belowState.getFluidState().isEmpty()) {
                    targetY = y;
                    break;
                }
            }
            if (targetY == -1) targetY = Math.min(100, Math.max(40, pos.getY()));
            net.minecraft.util.math.BlockPos base = new net.minecraft.util.math.BlockPos(pos.getX(), targetY, pos.getZ());
            // Не создаём новый портал, просто мягко сносим игрока на безопасную высоту
            player.requestTeleport(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
        });
        
        // ========== ПЕРСОНАЛЬНАЯ СИСТЕМА СНА ==========
        
        // СТАРЫЕ ОБРАБОТЧИКИ СНА УДАЛЕНЫ - теперь используется SlotTimeService
        
        // S2C payload не нужно регистрировать на сервере - он только отправляется
        
        // Инициализируем службу виртуального времени
        race.server.SlotTimeService.init();
        
        // Регистрируем команды времени
        race.server.commands.RaceTimeCommands.register();
    }

}
