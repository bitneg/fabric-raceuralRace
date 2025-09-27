package race.server;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.world.GameRules;
import net.minecraft.util.ActionResult;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Служба виртуального времени для персональных миров.
 * Полностью изолирует время каждого слота от ванильного world.setTimeOfDay.
 */
public final class SlotTimeService {
    private static final Map<RegistryKey<World>, Long> SLOT_TIME = new ConcurrentHashMap<>();
    private static final Map<RegistryKey<World>, Long> SLOT_SPEED = new ConcurrentHashMap<>();
    
    // Плавный рассвет
    private static final Map<RegistryKey<World>, Long> TRANSITION_TARGET = new ConcurrentHashMap<>();
    private static final Map<RegistryKey<World>, Long> TRANSITION_SPEED = new ConcurrentHashMap<>();
    private static final long MORNING_TICKS = 1000L; // ванильное "раннее утро"
    
    // Отслеживание инициализации структур
    private static final Set<RegistryKey<World>> STRUCTURES_INITIALIZED = ConcurrentHashMap.newKeySet();
    private static final Map<RegistryKey<World>, Integer> WORLD_TICK_COUNT = new ConcurrentHashMap<>();

    public static void init() {
        // Тик: удерживаем DO_DAYLIGHT_CYCLE=false и тикаем виртуальное время
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            for (ServerWorld w : server.getWorlds()) {
                if (isRaceWorld(w) && w.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).get()) {
                    w.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, server);
                }
            }
            tickAndSync(server);
        });

        // Сон только по SLOT_TIME и только в текущем мире
        EntitySleepEvents.ALLOW_SLEEP_TIME.register((player, pos, vanilla) -> {
            ServerWorld w = (ServerWorld) player.getWorld();
            if (!isRaceWorld(w)) {
                return vanilla ? ActionResult.SUCCESS : ActionResult.FAIL;
            }
            long t = getTime(w.getRegistryKey());
            long tod = t % 24000L;
            boolean night = tod >= 13000L && tod <= 23000L;
            return night ? ActionResult.SUCCESS : ActionResult.FAIL;
        });

        EntitySleepEvents.START_SLEEPING.register((entity, pos) -> {
            if (entity instanceof ServerPlayerEntity player) {
                ServerWorld w = (ServerWorld) player.getWorld();
                if (!isRaceWorld(w)) return;
                
                // Переходим к утру следующего дня (как в ваниле)
                long cur = getTime(w.getRegistryKey());
                long dayBase = (cur / 24000L) * 24000L;
                long nextMorning = dayBase + 24000L + 1000L; // утро следующего дня
                
                // Мгновенно устанавливаем время и синхронизируем с клиентами
                setTimeAndSnap(w, nextMorning);
                
                // Будим только игроков этого мира
                for (ServerPlayerEntity p : w.getPlayers()) {
                    if (p.isSleeping()) {
                        p.wakeUp(false, true);
                    }
                }
                
                System.out.println("[SlotTime] Сон: переход к утру " + w.getRegistryKey().getValue() + " = " + nextMorning);
            }
        });
    }

    /**
     * Инициализация виртуального времени для мира (идемпотентно)
     */
    public static void initIfAbsent(ServerWorld w, long initial) {
        if (!isRaceWorld(w)) return;
        
        RegistryKey<World> key = w.getRegistryKey();
        boolean wasInitialized = SLOT_TIME.containsKey(key);
        
        SLOT_TIME.putIfAbsent(key, initial);
        SLOT_SPEED.putIfAbsent(key, 1L);
        
        // Гарантируем отключение DO_DAYLIGHT_CYCLE
        if (w.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).get()) {
            w.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, w.getServer());
        }
        
        if (!wasInitialized) {
            System.out.println("[SlotTime] Инициализация виртуального времени: " + 
                key.getValue() + " = " + initial);
        } else {
            System.out.println("[SlotTime] Время уже инициализировано для: " + 
                key.getValue() + " = " + SLOT_TIME.get(key));
        }
    }

    /**
     * Проверка, является ли мир персональным (fabric_race)
     */
    public static boolean isRaceWorld(ServerWorld w) {
        return "fabric_race".equals(w.getRegistryKey().getValue().getNamespace());
    }

    /**
     * Получение виртуального времени мира
     */
    public static long getTime(RegistryKey<World> key) {
        return SLOT_TIME.getOrDefault(key, 0L);
    }

    /**
     * Установка виртуального времени мира
     */
    public static void setTime(RegistryKey<World> key, long t) {
        SLOT_TIME.put(key, t);
        System.out.println("[SlotTime] Установка времени: " + key.getValue() + " = " + t);
    }

    /**
     * Установка виртуального времени с мгновенной синхронизацией клиентов
     */
    public static void setTimeAndSnap(ServerWorld world, long t) {
        if (!isRaceWorld(world)) return;
        
        RegistryKey<World> key = world.getRegistryKey();
        SLOT_TIME.put(key, t);
        
        // Мгновенно отправляем новое время всем игрокам этого мира
        world.getPlayers().forEach(p -> {
            try {
                ServerPlayNetworking.send(p, race.net.RaceTimeSyncS2CPayload.of(
                    key.getValue().toString(), t
                ));
            } catch (Exception ex) {
                System.err.println("[SlotTime] Ошибка мгновенной синхронизации: " + ex.getMessage());
            }
        });
        
        System.out.println("[SlotTime] Установка времени с синхронизацией: " + key.getValue() + " = " + t);
    }

    /**
     * Установка скорости времени для мира
     */
    public static void setSpeed(RegistryKey<World> key, long tpt) {
        SLOT_SPEED.put(key, Math.max(0, tpt));
        System.out.println("[SlotTime] Установка скорости: " + key.getValue() + " = " + tpt + " ticks/tick");
    }

    /**
     * Плавный переход к утру (опционально)
     */
    public static void skipToMorningSmooth(ServerWorld w, int durationTicks) {
        if (!isRaceWorld(w)) return;
        
        RegistryKey<World> key = w.getRegistryKey();
        long cur = getTime(key);
        long base = (cur / 24000L) * 24000L;
        long target = base + 24000L + MORNING_TICKS;
        long delta = target - cur;
        long spd = Math.max(1L, delta / Math.max(1, durationTicks)); // шаг за тик

        TRANSITION_TARGET.put(key, target);
        TRANSITION_SPEED.put(key, spd);
        setTimeAndSnap(w, cur); // зафиксировать базу и разослать якорь
        
        System.out.println("[SlotTime] Плавный рассвет: " + key.getValue() + " за " + durationTicks + " тиков");
    }

    /**
     * Тик виртуального времени и синхронизация с клиентами
     */
    private static void tickAndSync(MinecraftServer server) {
        for (var e : SLOT_TIME.entrySet()) {
            RegistryKey<World> key = e.getKey();
            ServerWorld w = server.getWorld(key);
            if (w == null) continue;
            if (!isRaceWorld(w)) continue;
            
            // Дополнительная проверка на валидность мира
            try {
                if (w.getChunkManager() == null) continue;
            } catch (Exception ex) {
                System.err.println("[SlotTime] World " + key.getValue() + " is invalid, skipping: " + ex.getMessage());
                continue;
            }
            
            // Инициализируем структуры при первом тике мира
            if (!STRUCTURES_INITIALIZED.contains(key)) {
                // Увеличиваем счетчик тиков для мира
                int tickCount = WORLD_TICK_COUNT.getOrDefault(key, 0) + 1;
                WORLD_TICK_COUNT.put(key, tickCount);
                
                // Инициализируем структуры только после 200 тиков
                if (tickCount >= 200) {
                    try {
                        initializeStructuresForWorld(w);
                        STRUCTURES_INITIALIZED.add(key);
                        WORLD_TICK_COUNT.remove(key); // Очищаем счетчик
                        System.out.println("[SlotTime] Structures initialized for world: " + key.getValue());
                    } catch (Exception ex) {
                        System.err.println("[SlotTime] Failed to initialize structures for world " + key.getValue() + ": " + ex.getMessage());
                    }
                }
            }
            
            long cur = e.getValue();
            long next;
            
            // Проверяем, есть ли активный переход к утру
            Long target = TRANSITION_TARGET.get(key);
            Long accel = TRANSITION_SPEED.get(key);
            
            if (target != null && accel != null) {
                // Плавный переход к утру
                long step = Math.min(accel, Math.max(1, target - cur));
                next = cur + step;
                
                if (next >= target) {
                    next = target;
                    TRANSITION_TARGET.remove(key);
                    TRANSITION_SPEED.remove(key);
                    // Мгновенная синхронизация по достижении цели
                    setTimeAndSnap(w, next);
                    continue;
                }
            } else {
                // Обычный тик времени
                long spd = SLOT_SPEED.getOrDefault(key, 1L);
                next = cur + spd;
            }
            
            e.setValue(next);
            
            // Отправляем виртуальное время всем игрокам этого мира
            long finalNext = next;
            w.getPlayers().forEach(p -> {
                try {
                    ServerPlayNetworking.send(p, race.net.RaceTimeSyncS2CPayload.of(
                        key.getValue().toString(), finalNext
                    ));
                } catch (Exception ex) {
                    System.err.println("[SlotTime] Ошибка отправки времени: " + ex.getMessage());
                }
            });
            
            // Отладочная информация каждые 20 секунд
            if (finalNext % 400 == 0) { // каждые 20 секунд игрового времени
                System.out.println("[SlotTime] Время в мире " + key.getValue() + ": " + finalNext + " (игроков: " + w.getPlayers().size() + ")");
            }
        }
    }
    
    /**
     * Инициализирует структуры для мира при первом тике
     */
    private static void initializeStructuresForWorld(ServerWorld world) {
        try {
            // Получаем позицию спавна
            var spawnPos = world.getSpawnPos();
            int cx = spawnPos.getX() >> 4;
            int cz = spawnPos.getZ() >> 4;
            
            // Загружаем чанки вокруг спавна для инициализации структур
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    try {
                        // Загружаем чанк с полной генерацией для инициализации структур
                        var chunk = world.getChunk(cx + x, cz + z, net.minecraft.world.chunk.ChunkStatus.FULL, true);
                        if (chunk != null) {
                            // Принудительно инициализируем структуры в чанке
                            var structureAccessor = world.getStructureAccessor();
                            if (structureAccessor != null) {
                                // Проверяем, что структуры доступны
                                var chunkPos = chunk.getPos();
                                // ИСПРАВЛЕНИЕ: Используем правильный API для инициализации структур
                                try {
                                    // Принудительно загружаем структуры для этого чанка
                                    // Это заставит Minecraft инициализировать StructureHolder для чанка
                                    // Используем Predicate<Structure> который принимает все структуры
                                    structureAccessor.getStructureStarts(chunkPos, structure -> true);
                                } catch (Exception e) {
                                    // Логируем ошибки инициализации структур для отладки
                                    System.out.println("[Race] StructureAccessor error in SlotTimeService: " + e.getMessage());
                                    // Игнорируем ошибки инициализации структур
                                }
                            } else {
                                // Если StructureAccessor null, пропускаем инициализацию
                                System.out.println("[Race] StructureAccessor is null in SlotTimeService for world: " + world.getRegistryKey().getValue());
                            }
                        }
                    } catch (Exception ignored) {
                        // Игнорируем ошибки инициализации отдельных чанков
                    }
                }
            }
            
            // ИСПРАВЛЕНИЕ: Включаем спаун мобов после полной инициализации структур
            var server = world.getServer();
            // Упрощенная логика включения спавна мобов
            server.execute(() -> {
                // Проверяем, что мир все еще загружен
                if (server.getWorld(world.getRegistryKey()) != null) {
                    // Включаем спавн мобов сразу после инициализации структур
                    world.getGameRules().get(net.minecraft.world.GameRules.DO_MOB_SPAWNING).set(true, server);
                    System.out.println("[SlotTime] Enabled mob spawning for world: " + world.getRegistryKey().getValue());
                }
            });
            
        } catch (Exception e) {
            System.err.println("[SlotTime] Error initializing structures: " + e.getMessage());
        }
    }

    private SlotTimeService() {}
}
