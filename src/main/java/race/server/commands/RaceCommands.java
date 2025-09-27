package race.server.commands;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import race.server.phase.PhaseState;
import race.server.phase.RacePhaseManager;
import race.server.world.EnhancedWorldManager;
import race.server.world.ServerRaceConfig;
import race.hub.HubManager;
import race.hub.WorldManager;
import race.hub.ProgressSyncManager;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.minecraft.server.command.CommandManager.*;

/**
 * Команды для управления гонкой
 */
public final class RaceCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger(RaceCommands.class);
    
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("race")
                // Основные команды
                .then(literal("start").executes(RaceCommands::startRace))
                .then(literal("stop").executes(RaceCommands::stopRace))
                .then(literal("lobby")
                    .then(literal("all").executes(RaceCommands::returnAllToLobby))
                    .executes(RaceCommands::returnToLobby))
                .then(literal("status").executes(RaceCommands::showStatus))
                .then(literal("lobbylist").executes(RaceCommands::showLobbyList))
                
                // Настройка
                .then(literal("setup")
                    .then(argument("seed", LongArgumentType.longArg())
                        .executes(ctx -> setupRace(ctx, LongArgumentType.getLong(ctx, "seed")))))
                
                // Управление игроками
                .then(literal("spectate")
                    .then(argument("player", StringArgumentType.string())
                        .executes(ctx -> spectatePlayer(ctx, StringArgumentType.getString(ctx, "player")))))
                .then(literal("return").executes(RaceCommands::returnBack))
                .then(literal("returnpoint").executes(RaceCommands::showReturnPoint))
                .then(literal("teleport")
                    .then(argument("player", StringArgumentType.string())
                        .executes(ctx -> teleportToPlayer(ctx, StringArgumentType.getString(ctx, "player")))))
                .then(literal("join")
                    .then(argument("player", StringArgumentType.string())
                        .executes(ctx -> delayedJoin(ctx, StringArgumentType.getString(ctx, "player")))))
                .then(literal("parallel")
                    .then(argument("player", StringArgumentType.string())
                        .executes(ctx -> parallelRace(ctx, StringArgumentType.getString(ctx, "player")))))
                .then(literal("cancel").executes(RaceCommands::cancelJoin))
                .then(literal("reset").executes(RaceCommands::resetSeed))
                .then(literal("clear").executes(RaceCommands::clearReturnPoint))
                .then(literal("setpreferred").executes(RaceCommands::setPreferredWorld))
                .then(literal("clearpreferred").executes(RaceCommands::clearPreferredWorld))
                // Группы/команды в хабе
                .then(literal("team")
                    .then(literal("invite").then(argument("player", StringArgumentType.string())
                        .executes(ctx -> teamInvite(ctx, StringArgumentType.getString(ctx, "player")))))
                    .then(literal("accept").executes(RaceCommands::teamAccept))
                    .then(literal("disband").executes(RaceCommands::teamDisband))
                    .then(literal("leave").executes(RaceCommands::teamLeave))
                )
                
                // Информация
                .then(literal("leaderboard").executes(RaceCommands::showLeaderboard))
                .then(literal("help").executes(RaceCommands::showHelp))
                .then(literal("debug-return").executes(RaceCommands::debugReturnPoint))
                .then(literal("clear-return").executes(RaceCommands::clearReturnPoint))
                
                // Команды времени перенесены в RaceTimeCommands
                
                // Команды хаба
                .then(literal("hub").executes(RaceCommands::goToHub))
                .then(literal("hubinfo").executes(RaceCommands::showHubInfo))
                .then(literal("hubsave").executes(RaceCommands::saveHubData))
                .then(literal("perf").executes(RaceCommands::showPerformance))
                .then(literal("seed")
                    .then(argument("seed", LongArgumentType.longArg())
                        .executes(ctx -> setSeed(ctx, LongArgumentType.getLong(ctx, "seed"))))
                    .then(literal("random").executes(RaceCommands::setRandomSeed)))
                .then(literal("ready").executes(RaceCommands::readyForRace))
                .then(literal("group").executes(RaceCommands::showGroupInfo))
                .then(literal("ghosts")
                    .then(literal("on").executes(ctx -> toggleGhosts(ctx, true)))
                    .then(literal("off").executes(ctx -> toggleGhosts(ctx, false)))
                    .then(literal("force").executes(RaceCommands::forceEnableGhosts))
                    .then(literal("status").executes(RaceCommands::showGhostStatus))
                    .then(literal("test").executes(RaceCommands::testGhostTrail))
                )
                .then(literal("unfreeze").executes(RaceCommands::unfreezePlayer))
                .then(literal("unfreeze-all").executes(RaceCommands::unfreezeAllPlayers))
                .then(literal("tps")
                    .then(literal("on").executes(ctx -> toggleTpsDisplay(ctx, true)))
                    .then(literal("off").executes(ctx -> toggleTpsDisplay(ctx, false)))
                    .executes(RaceCommands::showTpsInfo)
                )
                .then(literal("auto")
                    .then(literal("on").executes(ctx -> toggleAutoOptimization(ctx, true)))
                    .then(literal("off").executes(ctx -> toggleAutoOptimization(ctx, false)))
                    .executes(RaceCommands::showAutoOptimizationInfo)
                )
                .then(literal("unfreeze")
                    .then(literal("all").executes(RaceCommands::forceUnfreezeAll))
                    .executes(RaceCommands::forceUnfreezeSelf)
                )
                .then(literal("clear")
                    .then(literal("all").executes(RaceCommands::clearAllRaceState))
                    .executes(RaceCommands::clearRaceState)
                )
                .then(literal("ghost")
                    .then(literal("quality")
                        .then(literal("0").executes(ctx -> setGhostQuality(ctx, 0)))
                        .then(literal("1").executes(ctx -> setGhostQuality(ctx, 1)))
                        .then(literal("2").executes(ctx -> setGhostQuality(ctx, 2)))
                        .then(literal("3").executes(ctx -> setGhostQuality(ctx, 3)))
                        .executes(RaceCommands::showGhostQualityInfo)
                    )
                    .then(literal("adaptive")
                        .then(literal("on").executes(ctx -> toggleGhostAdaptive(ctx, true)))
                        .then(literal("off").executes(ctx -> toggleGhostAdaptive(ctx, false)))
                        .executes(RaceCommands::showGhostAdaptiveInfo)
                    )
                    .executes(RaceCommands::showGhostInfo)
                )
                .then(literal("go").requires(src -> true).executes(RaceCommands::personalGo))
            );
        });
    }
    
    private static int startRace(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        if (PhaseState.isRaceActive()) {
            source.sendFeedback(() -> Text.literal("Гонка уже активна!").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        long seed = ServerRaceConfig.GLOBAL_SEED >= 0 ? ServerRaceConfig.GLOBAL_SEED : -1;
        if (seed < 0) {
            source.sendFeedback(() -> Text.literal("Сначала установите сид командой /race setup <seed>").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        // Запускаем гонку
        PhaseState.setRaceActive(true);
        PhaseState.setRaceStartTime(System.currentTimeMillis());
        PhaseState.setRaceSeed(seed);
        RacePhaseManager.resetRace();
        
        // ИСПРАВЛЕНИЕ: НЕ телепортируем всех игроков автоматически
        // Вместо этого уведомляем игроков о начале гонки и даем им выбор
        for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
            // Только уведомляем игроков о начале гонки
            player.sendMessage(Text.literal("🏁 Гонка началась на сиде: " + seed).formatted(net.minecraft.util.Formatting.GOLD), false);
            player.sendMessage(Text.literal("Используйте /race ready чтобы присоединиться к гонке").formatted(net.minecraft.util.Formatting.YELLOW), false);
            player.sendMessage(Text.literal("Или используйте /race join <игрок> чтобы присоединиться к другому игроку").formatted(net.minecraft.util.Formatting.AQUA), false);
        }
        
        source.sendFeedback(() -> Text.literal("Гонка началась на сиде: " + seed).formatted(net.minecraft.util.Formatting.GREEN), true);
        return 1;
    }
    
    private static int stopRace(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        if (!PhaseState.isRaceActive()) {
            source.sendFeedback(() -> Text.literal("Гонка не активна!").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        // Останавливаем гонку
        PhaseState.setRaceActive(false);
        RacePhaseManager.resetRace();
        
        // Возвращаем всех игроков в лобби
        returnToLobby(ctx);
        
        source.sendFeedback(() -> Text.literal("Гонка остановлена").formatted(net.minecraft.util.Formatting.YELLOW), true);
        return 1;
    }
    
    private static int returnToLobby(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendFeedback(() -> Text.literal("Только игрок может вернуться в хаб").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        // Сохраняем точку возврата перед переходом в хаб (если игрок в персональном мире)
        String worldNamespace = player.getServerWorld().getRegistryKey().getValue().getNamespace();
        if ("fabric_race".equals(worldNamespace)) {
            race.server.world.ReturnPointRegistry.saveCurrent(player);
        }
        
        // Безопасный переход в хаб
        try {
            // Сначала стабилизируем состояние игрока
            player.setHealth(player.getMaxHealth());
            player.getHungerManager().setFoodLevel(20);
            player.getHungerManager().setSaturationLevel(20.0f);
            player.setAir(player.getMaxAir());
            player.setVelocity(0, 0, 0);
            player.fallDistance = 0;
            
            // ИСПРАВЛЕНИЕ: Очищаем состояние гонки игрока при возвращении в хаб
            race.hub.HubManager.clearPlayerChoice(player.getUuid());
            source.sendFeedback(() -> Text.literal("Состояние гонки очищено").formatted(net.minecraft.util.Formatting.YELLOW), false);
            
            // Прямая телепортация в хаб
            race.hub.HubManager.teleportToHub(player);
            source.sendFeedback(() -> Text.literal("Возвращение в хаб").formatted(net.minecraft.util.Formatting.GREEN), false);
        } catch (Throwable t) {
            source.sendFeedback(() -> Text.literal("Ошибка при переходе в хаб: " + t.getMessage()).formatted(net.minecraft.util.Formatting.RED), false);
        }
        return 1;
    }

    private static int teamInvite(CommandContext<ServerCommandSource> ctx, String playerName) {
        ServerCommandSource source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayerEntity inviter)) {
            source.sendFeedback(() -> Text.literal("Только игроки могут приглашать в команду").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        ServerPlayerEntity target = source.getServer().getPlayerManager().getPlayer(playerName);
        if (target == null) {
            source.sendFeedback(() -> Text.literal("Игрок не найден: " + playerName).formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        race.hub.HubManager.inviteToTeam(inviter, target);
        return 1;
    }

    private static int teamAccept(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendFeedback(() -> Text.literal("Только игроки могут принять приглашение").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        race.hub.HubManager.acceptTeamInvite(player);
        return 1;
    }
    private static int returnAllToLobby(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        // ИСПРАВЛЕНИЕ: НЕ телепортируем всех игроков принудительно
        // Вместо этого уведомляем игроков и даем им выбор
        final int[] playersInPersonalWorlds = {0};
        for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
            String worldNamespace = player.getServerWorld().getRegistryKey().getValue().getNamespace();
            if ("fabric_race".equals(worldNamespace)) {
                playersInPersonalWorlds[0]++;
                // Сохраняем точку возврата перед переходом в хаб
                race.server.world.ReturnPointRegistry.saveCurrent(player);
                
                // Уведомляем игрока о возможности вернуться в хаб
                player.sendMessage(Text.literal("🏠 Администратор запросил возврат в хаб").formatted(net.minecraft.util.Formatting.YELLOW), false);
                player.sendMessage(Text.literal("Используйте /race lobby чтобы вернуться в хаб").formatted(net.minecraft.util.Formatting.AQUA), false);
            }
        }
        
        if (playersInPersonalWorlds[0] > 0) {
            source.sendFeedback(() -> Text.literal("Уведомлены " + playersInPersonalWorlds[0] + " игроков о возврате в хаб").formatted(net.minecraft.util.Formatting.GREEN), true);
        } else {
            source.sendFeedback(() -> Text.literal("Нет игроков в персональных мирах").formatted(net.minecraft.util.Formatting.YELLOW), true);
        }
        return 1;
    }
    
    private static int showStatus(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        if (!PhaseState.isRaceActive()) {
            source.sendFeedback(() -> Text.literal("Гонка не активна").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        long seed = PhaseState.getRaceSeed();
        long startTime = PhaseState.getRaceStartTime();
        long currentTime = System.currentTimeMillis();
        long raceTime = currentTime - startTime;
        
        source.sendFeedback(() -> Text.literal("=== Статус гонки ===").formatted(net.minecraft.util.Formatting.GOLD), false);
        source.sendFeedback(() -> Text.literal("Сид: " + seed).formatted(net.minecraft.util.Formatting.WHITE), false);
        source.sendFeedback(() -> Text.literal("Время гонки: " + formatTime(raceTime)).formatted(net.minecraft.util.Formatting.WHITE), false);
        source.sendFeedback(() -> Text.literal("Игроков: " + source.getServer().getPlayerManager().getPlayerList().size()).formatted(net.minecraft.util.Formatting.WHITE), false);
        
        return 1;
    }
    
    private static int setupRace(CommandContext<ServerCommandSource> ctx, long seed) {
        ServerCommandSource source = ctx.getSource();
        
        ServerRaceConfig.GLOBAL_SEED = seed;
        source.sendFeedback(() -> Text.literal("Сид установлен: " + seed).formatted(net.minecraft.util.Formatting.GREEN), true);
        return 1;
    }
    
    private static int spectatePlayer(CommandContext<ServerCommandSource> ctx, String playerName) {
        ServerCommandSource source = ctx.getSource();
        
        if (!(source.getEntity() instanceof ServerPlayerEntity spectator)) {
            source.sendFeedback(() -> Text.literal("Только игроки могут наблюдать").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        ServerPlayerEntity target = source.getServer().getPlayerManager().getPlayer(playerName);
        if (target == null) {
            source.sendFeedback(() -> Text.literal("Игрок не найден: " + playerName).formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        // Запрет: нельзя телепортироваться к мёртвому игроку (на экране смерти/респавна)
        try {
            if (!target.isAlive() || target.isDead()) {
                source.sendFeedback(() -> Text.literal("Нельзя телепортироваться к мёртвому игроку").formatted(net.minecraft.util.Formatting.RED), false);
                return 0;
            }
        } catch (Throwable ignored) {}
        if (target.getUuid().equals(spectator.getUuid())) {
            source.sendFeedback(() -> Text.literal("Нельзя наблюдать за собой").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        // ИСПРАВЛЕНИЕ: ПРИНУДИТЕЛЬНО сохраняем текущую позицию
        try {
            race.server.world.ReturnPointRegistry.forceSetReturnPoint(spectator);
            
            String currentWorld = spectator.getServerWorld().getRegistryKey().getValue().toString();
            source.sendFeedback(() -> Text.literal("Сохранена точка возврата: " + currentWorld).formatted(net.minecraft.util.Formatting.GRAY), false);
            
        } catch (Exception e) {
            source.sendFeedback(() -> Text.literal("Ошибка сохранения точки возврата: " + e.getMessage()).formatted(net.minecraft.util.Formatting.RED), false);
        }
        
        try { spectator.changeGameMode(net.minecraft.world.GameMode.SPECTATOR); } catch (Throwable ignored) {}
        spectator.teleport(target.getServerWorld(), target.getX(), target.getY(), target.getZ(), 
                          target.getYaw(), target.getPitch());
        
        source.sendFeedback(() -> Text.literal("Наблюдаете за игроком: " + playerName).formatted(net.minecraft.util.Formatting.GREEN), false);
        source.sendFeedback(() -> Text.literal("Используйте /race return для возврата к исходной позиции").formatted(net.minecraft.util.Formatting.GRAY), false);
        return 1;
    }

    private static int returnBack(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendFeedback(() -> Text.literal("Только игроки могут возвращаться").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        // Проверяем, есть ли сохраненная позиция
        boolean hasReturnPoint = false;
        try { 
            hasReturnPoint = (race.server.world.ReturnPointRegistry.get(player) != null);
        } catch (Throwable ignored) {}
        
        if (!hasReturnPoint) {
            source.sendFeedback(() -> Text.literal("Нет сохранённой точки возврата").formatted(net.minecraft.util.Formatting.RED), false);
            source.sendFeedback(() -> Text.literal("Используйте /race lobby из своего мира или /race spectate <player> для сохранения позиции").formatted(net.minecraft.util.Formatting.YELLOW), false);
            return 0;
        }
        
        boolean ok = false;
        try { 
            ok = race.server.world.ReturnPointRegistry.returnPlayer(player); 
        } catch (Throwable ignored) {}
        
        if (!ok) {
            source.sendFeedback(() -> Text.literal("Ошибка возврата к сохранённой позиции").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        source.sendFeedback(() -> Text.literal("Возвращение к исходной позиции...").formatted(net.minecraft.util.Formatting.GREEN), false);
        return 1;
    }
    
    /**
     * Показывает информацию о сохраненной точке возврата
     */
    private static int showReturnPoint(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendFeedback(() -> Text.literal("Только игроки могут проверять точку возврата").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        try {
            race.server.world.ReturnPointRegistry.ReturnPoint returnPoint = race.server.world.ReturnPointRegistry.get(player);
            if (returnPoint == null) {
                source.sendFeedback(() -> Text.literal("Нет сохранённой точки возврата").formatted(net.minecraft.util.Formatting.RED), false);
                source.sendFeedback(() -> Text.literal("Используйте /race lobby или /race spectate <player> для сохранения позиции").formatted(net.minecraft.util.Formatting.YELLOW), false);
                return 0;
            }
            
            source.sendFeedback(() -> Text.literal("=== Сохранённая точка возврата ===").formatted(net.minecraft.util.Formatting.GOLD), false);
            source.sendFeedback(() -> Text.literal("Мир: " + returnPoint.worldKey.getValue()).formatted(net.minecraft.util.Formatting.WHITE), false);
            source.sendFeedback(() -> Text.literal("Позиция: " + String.format("%.1f, %.1f, %.1f", returnPoint.x, returnPoint.y, returnPoint.z)).formatted(net.minecraft.util.Formatting.WHITE), false);
            source.sendFeedback(() -> Text.literal("Поворот: " + String.format("%.1f°, %.1f°", returnPoint.yaw, returnPoint.pitch)).formatted(net.minecraft.util.Formatting.WHITE), false);
            source.sendFeedback(() -> Text.literal("Режим игры: " + returnPoint.gameMode.name()).formatted(net.minecraft.util.Formatting.WHITE), false);
            source.sendFeedback(() -> Text.literal("Используйте /race return для возврата к этой позиции").formatted(net.minecraft.util.Formatting.GRAY), false);
            
            return 1;
        } catch (Exception e) {
            source.sendFeedback(() -> Text.literal("Ошибка получения информации о точке возврата: " + e.getMessage()).formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
    }
    
    private static int teleportToPlayer(CommandContext<ServerCommandSource> ctx, String playerName) {
        ServerCommandSource source = ctx.getSource();
        
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendFeedback(() -> Text.literal("Только игроки могут телепортироваться").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        ServerPlayerEntity target = source.getServer().getPlayerManager().getPlayer(playerName);
        if (target == null) {
            source.sendFeedback(() -> Text.literal("Игрок не найден: " + playerName).formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        // ПРОВЕРКА: Целевой игрок должен быть в кастомном мире
        String targetWorld = target.getServerWorld().getRegistryKey().getValue().toString();
        if (!targetWorld.startsWith("fabric_race:")) {
            source.sendFeedback(() -> Text.literal("Целевой игрок должен быть в кастомном мире!").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        // ИСПРАВЛЕНИЕ: ПРИНУДИТЕЛЬНО сохраняем текущую позицию
        try {
            race.server.world.ReturnPointRegistry.forceSetReturnPoint(player);
            
            String currentWorld = player.getServerWorld().getRegistryKey().getValue().toString();
            source.sendFeedback(() -> Text.literal("Сохранена точка возврата: " + currentWorld).formatted(net.minecraft.util.Formatting.GRAY), false);
            
            // Принудительно загружаем чанк перед телепортацией
            try {
                var world = target.getServerWorld();
                var chunkPos = new net.minecraft.util.math.ChunkPos(
                    (int) Math.floor(target.getX()) >> 4,
                    (int) Math.floor(target.getZ()) >> 4
                );
                
                // Загружаем чанк с полной генерацией
                world.getChunk(chunkPos.x, chunkPos.z, net.minecraft.world.chunk.ChunkStatus.FULL, true);
                
                // Дополнительно загружаем соседние чанки для стабильности
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        world.getChunk(chunkPos.x + dx, chunkPos.z + dz, net.minecraft.world.chunk.ChunkStatus.FULL, true);
                    }
                }
            } catch (Exception e) {
                source.sendFeedback(() -> Text.literal("Предупреждение: не удалось предзагрузить чанки").formatted(net.minecraft.util.Formatting.YELLOW), false);
            }
            
        } catch (Exception e) {
            source.sendFeedback(() -> Text.literal("Ошибка сохранения точки возврата: " + e.getMessage()).formatted(net.minecraft.util.Formatting.RED), false);
        }

        // ИСПРАВЛЕНИЕ: Принудительно загружаем чанк перед телепортацией
        try {
            var world = target.getServerWorld();
            var pos = target.getBlockPos();
            var chunk = world.getChunk(pos.getX() >> 4, pos.getZ() >> 4, net.minecraft.world.chunk.ChunkStatus.FULL, true);
            if (chunk != null) {
                LOGGER.info("[Race] Chunk loaded successfully for teleportation");
            }
        } catch (Exception e) {
            LOGGER.warn("[Race] Failed to preload chunk for teleportation: {}", e.getMessage());
        }
        
        // Телепортируем игрока к цели
        player.teleport(target.getServerWorld(), target.getX(), target.getY(), target.getZ(), 
                       target.getYaw(), target.getPitch());
        
        source.sendFeedback(() -> Text.literal("Телепортированы к игроку: " + playerName).formatted(net.minecraft.util.Formatting.GREEN), false);
        return 1;
    }

    // Отложенный join с уведомлением цели (5 секунд)
    private static int delayedJoin(CommandContext<ServerCommandSource> ctx, String playerName) {
        ServerCommandSource source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendFeedback(() -> Text.literal("Только игроки могут присоединяться").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        ServerPlayerEntity target = source.getServer().getPlayerManager().getPlayer(playerName);
        if (target == null) {
            source.sendFeedback(() -> Text.literal("Игрок не найден: " + playerName).formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        // ПРОВЕРКА: Целевой игрок должен быть в кастомном мире
        String targetWorld = target.getServerWorld().getRegistryKey().getValue().toString();
        if (!targetWorld.startsWith("fabric_race:")) {
            source.sendFeedback(() -> Text.literal("Целевой игрок должен быть в кастомном мире!").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        // Отправляем уведомление цели
        try {
            target.sendMessage(Text.literal("⚠ " + player.getName().getString() + " хочет присоединиться к вам через 5 секунд!").formatted(net.minecraft.util.Formatting.YELLOW), false);
            target.sendMessage(Text.literal("Используйте /race cancel, чтобы отменить").formatted(net.minecraft.util.Formatting.GRAY), false);
        } catch (Throwable ignored) {}
        
        // 5 сек = ~100 тиков
        try {
            // Используем публичный API вместо рефлексии
            var pendingJoins = race.server.RaceServerInit.getPendingJoins();
            var pendingJoin = new race.server.RaceServerInit.PendingJoin(target.getUuid(), 100);
            pendingJoins.put(player.getUuid(), pendingJoin);
            
            // Отправляем статус join-запроса клиенту
            try {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, new race.net.JoinRequestStatusPayload(true, playerName));
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            // Фолбэк — мгновенный join
            teleportToPlayer(ctx, playerName);
            return 1;
        }
        source.sendFeedback(() -> Text.literal("Ожидаем 5 сек перед присоединением к " + playerName).formatted(net.minecraft.util.Formatting.GRAY), false);
        return 1;
    }
    
    private static int cancelJoin(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendFeedback(() -> Text.literal("Только игроки могут отменять запросы").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        try {
            // Используем публичный API вместо рефлексии
            var pendingJoins = race.server.RaceServerInit.getPendingJoins();
            var removed = pendingJoins.remove(player.getUuid());
            if (removed != null) {
                // Уведомляем цель о том, что запрос отменён
                try {
                    ServerPlayerEntity target = source.getServer().getPlayerManager().getPlayer(removed.target());
                    if (target != null) {
                        target.sendMessage(Text.literal("❌ " + player.getName().getString() + " отменил запрос на присоединение").formatted(net.minecraft.util.Formatting.RED), false);
                    }
                } catch (Throwable ignored) {}
                
                // Отправляем статус отмены join-запроса клиенту
                try {
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, new race.net.JoinRequestStatusPayload(false, ""));
                } catch (Throwable ignored) {}
                
                source.sendFeedback(() -> Text.literal("Запрос на присоединение отменён").formatted(net.minecraft.util.Formatting.GREEN), false);
                return 1;
            } else {
                source.sendFeedback(() -> Text.literal("Нет активных запросов на присоединение").formatted(net.minecraft.util.Formatting.YELLOW), false);
                return 0;
            }
        } catch (Throwable t) {
            source.sendFeedback(() -> Text.literal("Ошибка при отмене запроса").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
    }
    
    private static int showLeaderboard(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        source.sendFeedback(() -> Text.literal("=== Таблица лидеров ===").formatted(net.minecraft.util.Formatting.GOLD), false);
        
        var players = RacePhaseManager.getRaceBoardData(source.getServer());
        for (int i = 0; i < players.size(); i++) {
            var player = players.get(i);
            final String position = (i + 1) + ". "; // Делаем final для лямбда-выражения
            final String time = formatTime(player.rtaMs()); // Делаем final для лямбда-выражения
            final String stage = player.stage(); // Делаем final для лямбда-выражения
            final String playerName = player.name(); // Делаем final для лямбда-выражения
            
            source.sendFeedback(() -> Text.literal(position + playerName + " - " + time + " [" + stage + "]")
                .formatted(net.minecraft.util.Formatting.WHITE), false);
        }
        
        return 1;
    }
    
    private static int showHelp(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        source.sendFeedback(() -> Text.literal("=== Команды гонки ===").formatted(net.minecraft.util.Formatting.GOLD), false);
        source.sendFeedback(() -> Text.literal("/race start - Начать гонку").formatted(net.minecraft.util.Formatting.WHITE), false);
        source.sendFeedback(() -> Text.literal("/race stop - Остановить гонку").formatted(net.minecraft.util.Formatting.WHITE), false);
        source.sendFeedback(() -> Text.literal("/race lobby - Вернуться в лобби").formatted(net.minecraft.util.Formatting.WHITE), false);
        source.sendFeedback(() -> Text.literal("/race setup <seed> - Установить сид").formatted(net.minecraft.util.Formatting.WHITE), false);
        source.sendFeedback(() -> Text.literal("/race status - Показать статус").formatted(net.minecraft.util.Formatting.WHITE), false);
        source.sendFeedback(() -> Text.literal("/race spectate <player> - Наблюдать за игроком (режим наблюдателя)").formatted(net.minecraft.util.Formatting.WHITE), false);
        source.sendFeedback(() -> Text.literal("/race return - Вернуться в свой мир/точку").formatted(net.minecraft.util.Formatting.WHITE), false);
        source.sendFeedback(() -> Text.literal("/race returnpoint - Показать сохранённую точку возврата").formatted(net.minecraft.util.Formatting.WHITE), false);
        source.sendFeedback(() -> Text.literal("/race teleport <player> - Телепортироваться к игроку").formatted(net.minecraft.util.Formatting.WHITE), false);
        source.sendFeedback(() -> Text.literal("/race leaderboard - Показать таблицу лидеров").formatted(net.minecraft.util.Formatting.WHITE), false);
        source.sendFeedback(() -> Text.literal("/race replays - Показать доступные повторы").formatted(net.minecraft.util.Formatting.WHITE), false);
        source.sendFeedback(() -> Text.literal("/race ranking - Показать ваш рейтинг").formatted(net.minecraft.util.Formatting.WHITE), false);
        source.sendFeedback(() -> Text.literal("/race tps - Показать информацию о TPS").formatted(net.minecraft.util.Formatting.WHITE), false);
        source.sendFeedback(() -> Text.literal("/race auto - Управление автоматической оптимизацией").formatted(net.minecraft.util.Formatting.WHITE), false);
        source.sendFeedback(() -> Text.literal("/race ghost - Управление качеством призраков").formatted(net.minecraft.util.Formatting.WHITE), false);
        
        return 1;
    }
    

    

    
    private static String formatTime(long milliseconds) {
        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        long hours = minutes / 60;
        minutes = minutes % 60;
        
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%d:%02d", minutes, seconds);
        }
    }

    private static int toggleGhosts(CommandContext<ServerCommandSource> ctx, boolean enable) {
        ServerCommandSource source = ctx.getSource();
        // ИСПРАВЛЕНИЕ: Убираем проверку на оператора - команда доступна всем игрокам
        race.server.RaceServerInit.setDisplayParallelPlayers(enable);
        source.sendFeedback(() -> Text.literal("Параллельные игроки: " + (enable ? "ON" : "OFF")).formatted(net.minecraft.util.Formatting.GREEN), true);
        return 1;
    }
    
    // === КОМАНДЫ ХАБА ===
    
    /**
     * Телепортирует игрока в хаб
     */
    private static int goToHub(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendFeedback(() -> Text.literal("Только игроки могут войти в хаб").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        HubManager.teleportToHub(player);
        return 1;
    }
    
    /**
     * Устанавливает сид для игрока
     */
    private static int setSeed(CommandContext<ServerCommandSource> ctx, long seed) {
        ServerCommandSource source = ctx.getSource();
        
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendFeedback(() -> Text.literal("Только игроки могут выбирать сид").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        // Если у игрока есть команда — назначаем сид всей команде (по лидеру)
        try {
            race.hub.HubManager.setTeamSeedChoice(source.getServer(), player.getUuid(), seed);
        } catch (Throwable t) {
            HubManager.setPlayerSeedChoice(player.getUuid(), seed);
        }
        // Также обновим глобальный сид, чтобы JOIN-код мог сразу создавать мир
        race.server.world.ServerRaceConfig.GLOBAL_SEED = seed;
        source.sendFeedback(() -> Text.literal("Сид установлен: " + seed).formatted(net.minecraft.util.Formatting.GREEN), false);
        source.sendFeedback(() -> Text.literal("Игроков с этим сидом: " + HubManager.getSeedPlayerCount(seed)).formatted(net.minecraft.util.Formatting.AQUA), false);
        source.sendFeedback(() -> Text.literal("Теперь выполните /race ready для создания личного мира").formatted(net.minecraft.util.Formatting.YELLOW), false);
        
        return 1;
    }
    
    /**
     * Устанавливает случайный сид
     */
    private static int setRandomSeed(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendFeedback(() -> Text.literal("Только игроки могут выбирать сид").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        long randomSeed = System.currentTimeMillis() % 1000000; // Простой случайный сид
        return setSeed(ctx, randomSeed);
    }
    
    /**
     * Отмечает игрока как готового к гонке
     */
    private static int readyForRace(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendFeedback(() -> Text.literal("Только игроки могут готовиться к гонке").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        UUID playerId = player.getUuid();
        long seed = HubManager.getPlayerSeedChoice(playerId);
        
        // ИСПРАВЛЕНИЕ: Разрешаем отрицательные сиды, но блокируем только -1 (не выбран)
        if (seed == -1) {
            source.sendFeedback(() -> Text.literal("Сначала выберите сид командой /race seed <число>").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        // ИСПРАВЛЕНИЕ: Проверяем, находится ли игрок уже в персональном мире
        boolean alreadyInRaceWorld = player.getServerWorld().getRegistryKey().getValue().toString().startsWith("fabric_race:");
        
        if (alreadyInRaceWorld) {
            source.sendFeedback(() -> Text.literal("Вы уже в личном мире! Используйте /race join <игрок> для присоединения к другим игрокам.").formatted(net.minecraft.util.Formatting.YELLOW), false);
            return 1;
        }
        
        // Проверяем, есть ли другие игроки с этим сидом
        int playerCount = HubManager.getSeedPlayerCount(seed);
        // ИСПРАВЛЕНИЕ: Разрешаем старт гонки даже для первого игрока
        if (playerCount >= 0) {
            // помечаем игрока как готового (для определения лидера на dedicated)
            race.hub.HubManager.markReady(player.getUuid());
            
            // ТЕЛЕПОРТАЦИЯ: Создаем и прогреваем мир, затем телепортируем
            try {
                var personalWorld = race.server.world.EnhancedWorldManager.getOrCreateWorld(
                    source.getServer(), player.getUuid(), seed, net.minecraft.world.World.OVERWORLD);
            if (personalWorld != null) {
                // Прогреваем чанки перед телепортацией
                race.server.world.SpawnCache.warmupAndCache(personalWorld, personalWorld.getSpawnPos(), 2);
                
                // УПРОЩЕНИЕ: Миксин обработает null чанки безопасно

                // Телепортируем в персональный мир
                race.tp.SafeTeleport.toWorldSpawn(player, personalWorld);
                    race.hub.HubManager.setLastWorldSeed(player.getUuid(), seed);
                    
                    // Сохраняем точку возврата
                    race.server.world.ReturnPointRegistry.saveCurrent(player);
                    
                    source.sendFeedback(() -> Text.literal("Вы телепортированы в персональный мир! Используйте /race go для старта гонки.").formatted(net.minecraft.util.Formatting.GREEN), false);
                    LOGGER.info("[Race] Player ready - teleported to personal world: {} -> {}", 
                              player.getName().getString(), seed);
                } else {
                    source.sendFeedback(() -> Text.literal("Ошибка создания персонального мира").formatted(net.minecraft.util.Formatting.RED), false);
                }
            } catch (Throwable t) {
                source.sendFeedback(() -> Text.literal("Ошибка при готовности: " + t.getMessage()).formatted(net.minecraft.util.Formatting.RED), false);
                LOGGER.warn("[Race] Error during ready teleport: {}", t.getMessage());
            }
        } else {
            source.sendFeedback(() -> Text.literal("Ожидаем других игроков с сидом " + seed + "...").formatted(net.minecraft.util.Formatting.YELLOW), false);
        }
        
        return 1;
    }

    // Глобальный старт: запускает всех игроков, готовых в персональных мирах, одновременно
    private static int personalGo(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity issuer;
        try { issuer = source.getPlayer(); } catch (Exception e) { issuer = null; }

        // Если инициатор известен — запускаем глобально для всех в персональных мирах
        if (issuer != null) {
            java.util.UUID leaderId = race.hub.HubManager.getLeader(issuer.getUuid());
            java.util.List<java.util.UUID> members = race.hub.HubManager.getTeamMembers(leaderId);
            if (members == null || members.isEmpty()) members = java.util.List.of(leaderId);

            long seed = race.hub.HubManager.getPlayerSeedChoice(leaderId);
            // ИСПРАВЛЕНИЕ: Разрешаем отрицательные сиды, но блокируем только -1 (не выбран)
            if (seed == -1) seed = race.server.world.ServerRaceConfig.GLOBAL_SEED;
            if (seed == -1) {
                source.sendFeedback(() -> Text.literal("Сначала выберите сид (/race seed) и нажмите Ready").formatted(net.minecraft.util.Formatting.YELLOW), false);
                return 0;
            }

            // На всякий случай — синхронизируем сид у всей команды
            try { race.hub.HubManager.setTeamSeedChoice(source.getServer(), leaderId, seed); } catch (Throwable ignored) {}
            // Список всех игроков в персональных мирах — глобальный старт
            java.util.List<ServerPlayerEntity> targets = new java.util.ArrayList<>();
            for (ServerPlayerEntity p : source.getServer().getPlayerManager().getPlayerList()) {
                String ns = p.getServerWorld().getRegistryKey().getValue().getNamespace();
                if (!"fabric_race".equals(ns)) continue;
                // Нормализуем состояние перед стартом
                try { p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL); } catch (Throwable ignored) {}
                try { p.sendAbilitiesUpdate(); } catch (Throwable ignored) {}
                targets.add(p);
            }
            if (targets.isEmpty()) {
                source.sendFeedback(() -> Text.literal("Нет игроков в персональных мирах для старта").formatted(net.minecraft.util.Formatting.YELLOW), false);
                return 0;
            }
            // ИСПРАВЛЕНИЕ: НЕ замораживаем игроков, которые присоединились к другим игрокам
            // Замораживаем только тех, кто находится на спавне своего мира
            for (ServerPlayerEntity p : targets) { 
                // Проверяем, находится ли игрок рядом со спавном своего мира
                net.minecraft.util.math.BlockPos spawnPos = p.getServerWorld().getSpawnPos();
                double distanceToSpawn = p.getPos().distanceTo(net.minecraft.util.math.Vec3d.ofCenter(spawnPos));
                
                // Замораживаем только если игрок находится рядом со спавном (в радиусе 50 блоков)
                if (distanceToSpawn <= 50.0) {
                    race.server.RaceServerInit.freezePlayerUntilStart(p);
                } else {
                    // Игрок далеко от спавна - возможно, он присоединился к другому игроку
                    p.sendMessage(Text.literal("🏁 Глобальный старт! Вы можете продолжать играть.").formatted(net.minecraft.util.Formatting.GREEN), false);
                }
            }
            for (ServerPlayerEntity p : targets) { race.server.RaceServerInit.personalStart(p, seed); }
            try { race.server.RaceServerInit.startRace(seed, System.currentTimeMillis()); } catch (Throwable ignored) {}
            source.sendFeedback(() -> Text.literal("Глобальный старт!").formatted(net.minecraft.util.Formatting.GREEN), true);
            return 1;
        }

        // Фолбэк: если инициатора нет, прежняя глобальная логика
        java.util.List<ServerPlayerEntity> targets = new java.util.ArrayList<>();
        for (ServerPlayerEntity p : source.getServer().getPlayerManager().getPlayerList()) {
            String key = p.getServerWorld().getRegistryKey().getValue().getNamespace();
            if ("fabric_race".equals(key)) targets.add(p);
        }
        if (targets.isEmpty()) {
            source.sendFeedback(() -> Text.literal("Нет игроков, готовых к старту в персональных мирах").formatted(net.minecraft.util.Formatting.YELLOW), false);
            return 0;
        }
        // ИСПРАВЛЕНИЕ: НЕ замораживаем игроков, которые присоединились к другим игрокам
        for (ServerPlayerEntity p : targets) {
            // Проверяем, находится ли игрок рядом со спавном своего мира
            net.minecraft.util.math.BlockPos spawnPos = p.getServerWorld().getSpawnPos();
            double distanceToSpawn = p.getPos().distanceTo(net.minecraft.util.math.Vec3d.ofCenter(spawnPos));
            
            // Замораживаем только если игрок находится рядом со спавном (в радиусе 50 блоков)
            if (distanceToSpawn <= 50.0) {
                race.server.RaceServerInit.freezePlayerUntilStart(p);
            } else {
                // Игрок далеко от спавна - возможно, он присоединился к другому игроку
                p.sendMessage(Text.literal("🏁 Глобальный старт! Вы можете продолжать играть.").formatted(net.minecraft.util.Formatting.GREEN), false);
            }
        }
        for (ServerPlayerEntity p : targets) {
            long s = race.hub.HubManager.getPlayerSeedChoice(p.getUuid());
            if (s < 0) s = race.server.world.ServerRaceConfig.GLOBAL_SEED;
            
            // ВАЛИДАЦИЯ: проверяем сид перед стартом
            if (s <= 0) {
                p.sendMessage(net.minecraft.text.Text.literal("Сначала выберите сид: /race seed <число> или /race setup <seed>")
                    .formatted(net.minecraft.util.Formatting.RED), false);
                continue; // пропускаем этого игрока
            }
            
            race.server.RaceServerInit.personalStart(p, s);
        }
        try { race.server.RaceServerInit.startRace(race.server.world.ServerRaceConfig.GLOBAL_SEED, System.currentTimeMillis()); } catch (Throwable ignored) {}
        source.sendFeedback(() -> Text.literal("Глобальный старт для всех!").formatted(net.minecraft.util.Formatting.GREEN), true);
        return 1;
    }
    
    /**
     * Показывает информацию о группе игроков
     */
    private static int showGroupInfo(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendFeedback(() -> Text.literal("Только игроки могут просматривать группу").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        UUID playerId = player.getUuid();
        long seed = HubManager.getPlayerSeedChoice(playerId);
        
        // ИСПРАВЛЕНИЕ: Разрешаем отрицательные сиды, но блокируем только -1 (не выбран)
        if (seed == -1) {
            source.sendFeedback(() -> Text.literal("Сначала выберите сид командой /race seed <число>").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        source.sendFeedback(() -> Text.literal("=== Информация о группе ===").formatted(net.minecraft.util.Formatting.GOLD), false);
        source.sendFeedback(() -> Text.literal("Сид: " + seed).formatted(net.minecraft.util.Formatting.WHITE), false);
        source.sendFeedback(() -> Text.literal("Игроков в группе: " + HubManager.getSeedPlayerCount(seed)).formatted(net.minecraft.util.Formatting.WHITE), false);
        
        // Показываем список игроков в группе
        var groupPlayers = HubManager.getPlayersForSeed(seed);
        if (!groupPlayers.isEmpty()) {
            source.sendFeedback(() -> Text.literal("Игроки:").formatted(net.minecraft.util.Formatting.YELLOW), false);
            int i = 1;
            for (UUID groupPlayerId : groupPlayers.keySet()) {
                ServerPlayerEntity groupPlayer = source.getServer().getPlayerManager().getPlayer(groupPlayerId);
                if (groupPlayer != null) {
                    final int index = i;
                    final String playerName = groupPlayer.getName().getString();
                    source.sendFeedback(() -> Text.literal(index + ". " + playerName).formatted(net.minecraft.util.Formatting.WHITE), false);
                    i++;
                }
            }
        }
        
        return 1;
    }
    
    private static int resetSeed(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendFeedback(() -> Text.literal("Только игроки могут сбрасывать сид").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        // Сбрасываем выбранный сид игрока
        race.hub.HubManager.setPlayerSeedChoice(player.getUuid(), -1);
        source.sendFeedback(() -> Text.literal("Сид сброшен. Теперь вы будете возвращаться в хаб при перезаходе").formatted(net.minecraft.util.Formatting.GREEN), false);
        return 1;
    }
    
    private static int clearReturnPoint(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendFeedback(() -> Text.literal("Только игроки могут очищать точку возврата").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        // Очищаем точку возврата игрока
        race.server.world.ReturnPointRegistry.clear(player);
        source.sendFeedback(() -> Text.literal("Точка возврата очищена. При следующем переходе в хаб будет сохранена новая позиция").formatted(net.minecraft.util.Formatting.GREEN), false);
        return 1;
    }
    
    /**
     * Расформировывает команду (только для лидера)
     */
    private static int teamDisband(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendFeedback(() -> Text.literal("Только игроки могут расформировывать команды").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        // Получаем всех участников команды
        long seed = race.hub.HubManager.getPlayerSeedChoice(player.getUuid());
        // ИСПРАВЛЕНИЕ: Разрешаем отрицательные сиды, но блокируем только -1 (не выбран)
        if (seed == -1) {
            source.sendFeedback(() -> Text.literal("Вы не состоите в команде").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        // Проверяем, является ли игрок лидером команды
        UUID leaderId = race.hub.HubManager.getSeedLeader(seed);
        if (leaderId == null || !leaderId.equals(player.getUuid())) {
            source.sendFeedback(() -> Text.literal("Только лидер команды может расформировать команду").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        // Уведомляем всех участников команды
        for (ServerPlayerEntity teamPlayer : source.getServer().getPlayerManager().getPlayerList()) {
            long playerSeed = race.hub.HubManager.getPlayerSeedChoice(teamPlayer.getUuid());
            if (playerSeed == seed) {
                teamPlayer.sendMessage(Text.literal("Команда расформирована лидером " + player.getName().getString()).formatted(net.minecraft.util.Formatting.YELLOW), false);
                // Сбрасываем выбор сида для всех участников
                race.hub.HubManager.setPlayerSeedChoice(teamPlayer.getUuid(), -1);
            }
        }
        
        source.sendFeedback(() -> Text.literal("Команда успешно расформирована").formatted(net.minecraft.util.Formatting.GREEN), false);
        return 1;
    }
    
    /**
     * Покидает команду (для участников)
     */
    private static int teamLeave(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendFeedback(() -> Text.literal("Только игроки могут покидать команды").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        // Проверяем, состоит ли игрок в команде
        long seed = race.hub.HubManager.getPlayerSeedChoice(player.getUuid());
        // ИСПРАВЛЕНИЕ: Разрешаем отрицательные сиды, но блокируем только -1 (не выбран)
        if (seed == -1) {
            source.sendFeedback(() -> Text.literal("Вы не состоите в команде").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        // Проверяем, является ли игрок лидером
        UUID leaderId = race.hub.HubManager.getSeedLeader(seed);
        if (leaderId != null && leaderId.equals(player.getUuid())) {
            source.sendFeedback(() -> Text.literal("Лидер не может покинуть команду. Используйте /race team disband для расформирования").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        // Уведомляем лидера команды
        if (leaderId != null) {
            ServerPlayerEntity leader = source.getServer().getPlayerManager().getPlayer(leaderId);
            if (leader != null) {
                leader.sendMessage(Text.literal("Игрок " + player.getName().getString() + " покинул команду").formatted(net.minecraft.util.Formatting.YELLOW), false);
            }
        }
        
        // Уведомляем других участников команды
        for (ServerPlayerEntity teamPlayer : source.getServer().getPlayerManager().getPlayerList()) {
            if (teamPlayer == player) continue;
            long playerSeed = race.hub.HubManager.getPlayerSeedChoice(teamPlayer.getUuid());
            if (playerSeed == seed) {
                teamPlayer.sendMessage(Text.literal("Игрок " + player.getName().getString() + " покинул команду").formatted(net.minecraft.util.Formatting.YELLOW), false);
            }
        }
        
        // Убираем игрока из команды
        race.hub.HubManager.setPlayerSeedChoice(player.getUuid(), -1);
        source.sendFeedback(() -> Text.literal("Вы покинули команду").formatted(net.minecraft.util.Formatting.GREEN), false);
        return 1;
    }
    
    /**
     * Показывает информацию о хабе
     */
    private static int showHubInfo(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        try {
            String hubInfo = race.hub.HubManager.getHubInfo();
            source.sendFeedback(() -> Text.literal("Информация о хабе:").formatted(net.minecraft.util.Formatting.GOLD), false);
            source.sendFeedback(() -> Text.literal(hubInfo).formatted(net.minecraft.util.Formatting.AQUA), false);
            
            // Дополнительная информация
            if (race.hub.HubManager.isHubActive()) {
                source.sendFeedback(() -> Text.literal("Статус: Активен").formatted(net.minecraft.util.Formatting.GREEN), false);
            } else {
                source.sendFeedback(() -> Text.literal("Статус: Неактивен").formatted(net.minecraft.util.Formatting.RED), false);
            }
            
            return 1;
        } catch (Exception e) {
            source.sendFeedback(() -> Text.literal("Ошибка получения информации о хабе: " + e.getMessage()).formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
    }
    
    /**
     * Принудительно сохраняет данные хаба
     */
    private static int saveHubData(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        try {
            race.hub.HubManager.forceSaveHubData();
            source.sendFeedback(() -> Text.literal("Данные хаба сохранены").formatted(net.minecraft.util.Formatting.GREEN), false);
            return 1;
        } catch (Exception e) {
            source.sendFeedback(() -> Text.literal("Ошибка сохранения данных хаба: " + e.getMessage()).formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
    }
    
    /**
     * Показывает информацию о производительности системы
     */
    private static int showPerformance(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        try {
            MinecraftServer server = source.getServer();
            int playerCount = server.getPlayerManager().getPlayerList().size();
            
            // Информация о памяти
            long memoryUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            long memoryMax = Runtime.getRuntime().maxMemory();
            long memoryFree = Runtime.getRuntime().freeMemory();
            
            // Информация о производительности
            double memoryUsagePercent = (double) memoryUsed / memoryMax * 100;
            
            source.sendFeedback(() -> Text.literal("=== Производительность системы ===").formatted(net.minecraft.util.Formatting.GOLD), false);
            source.sendFeedback(() -> Text.literal("Игроков онлайн: " + playerCount).formatted(net.minecraft.util.Formatting.WHITE), false);
            source.sendFeedback(() -> Text.literal("Память: " + (memoryUsed / 1024 / 1024) + "MB / " + (memoryMax / 1024 / 1024) + "MB (" + String.format("%.1f", memoryUsagePercent) + "%)").formatted(net.minecraft.util.Formatting.WHITE), false);
            source.sendFeedback(() -> Text.literal("Свободно: " + (memoryFree / 1024 / 1024) + "MB").formatted(net.minecraft.util.Formatting.WHITE), false);
            
            // Рекомендации по оптимизации
            if (memoryUsagePercent > 80) {
                source.sendFeedback(() -> Text.literal("⚠️ Высокое использование памяти! Рекомендуется перезапуск сервера").formatted(net.minecraft.util.Formatting.RED), false);
            } else if (playerCount > 8) {
                source.sendFeedback(() -> Text.literal("ℹ️ Много игроков - система использует адаптивные интервалы обновлений").formatted(net.minecraft.util.Formatting.YELLOW), false);
            } else {
                source.sendFeedback(() -> Text.literal("✅ Производительность в норме").formatted(net.minecraft.util.Formatting.GREEN), false);
            }
            
            return 1;
        } catch (Exception e) {
            source.sendFeedback(() -> Text.literal("Ошибка получения информации о производительности: " + e.getMessage()).formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
    }
    
    /**
     * Переключает отображение TPS
     */
    private static int toggleTpsDisplay(CommandContext<ServerCommandSource> ctx, boolean enable) {
        ServerCommandSource source = ctx.getSource();
        
        try {
            ServerPlayerEntity p = source.getPlayer();
            if (p != null && source.getServer().getPlayerManager().isOperator(p.getGameProfile())) {
                race.server.RaceServerInit.setTpsDisplayEnabled(enable);
                source.sendFeedback(() -> Text.literal("Отображение TPS: " + (enable ? "ВКЛЮЧЕНО" : "ВЫКЛЮЧЕНО")).formatted(net.minecraft.util.Formatting.GREEN), true);
                return 1;
            }
        } catch (Throwable ignored) {}
        
        race.server.RaceServerInit.setTpsDisplayEnabled(enable);
        source.sendFeedback(() -> Text.literal("Отображение TPS: " + (enable ? "ВКЛЮЧЕНО" : "ВЫКЛЮЧЕНО")).formatted(net.minecraft.util.Formatting.GREEN), true);
        return 1;
    }
    
    /**
     * Показывает информацию о TPS
     */
    private static int showTpsInfo(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        try {
            double currentTPS = race.server.RaceServerInit.getCurrentTPS();
            boolean tpsEnabled = race.server.RaceServerInit.isTpsDisplayEnabled();
            String performanceStatus = race.server.RaceServerInit.getPerformanceStatus();
            int performanceLevel = race.server.RaceServerInit.getPerformanceLevel();
            
            source.sendFeedback(() -> Text.literal("=== Информация о TPS ===").formatted(net.minecraft.util.Formatting.GOLD), false);
            source.sendFeedback(() -> Text.literal("Текущий TPS: " + String.format("%.2f", currentTPS)).formatted(net.minecraft.util.Formatting.WHITE), false);
            source.sendFeedback(() -> Text.literal("Отображение: " + (tpsEnabled ? "ВКЛЮЧЕНО" : "ВЫКЛЮЧЕНО")).formatted(net.minecraft.util.Formatting.WHITE), false);
            source.sendFeedback(() -> Text.literal("Статус производительности: " + performanceStatus).formatted(net.minecraft.util.Formatting.WHITE), false);
            source.sendFeedback(() -> Text.literal("Уровень нагрузки: " + performanceLevel).formatted(net.minecraft.util.Formatting.WHITE), false);
            
            // Информация об автоматической оптимизации
            boolean autoEnabled = race.server.RaceServerInit.isAutoOptimizationEnabled();
            int autoLevel = race.server.RaceServerInit.getAutoOptimizationLevel();
            source.sendFeedback(() -> Text.literal("Авто-оптимизация: " + (autoEnabled ? "ВКЛЮЧЕНА" : "ВЫКЛЮЧЕНА") + " (уровень " + autoLevel + ")").formatted(net.minecraft.util.Formatting.WHITE), false);
            
            // Оценка производительности
            if (currentTPS >= 19.5) {
                source.sendFeedback(() -> Text.literal("✅ Отличная производительность").formatted(net.minecraft.util.Formatting.GREEN), false);
            } else if (currentTPS >= 18.0) {
                source.sendFeedback(() -> Text.literal("⚠️ Хорошая производительность").formatted(net.minecraft.util.Formatting.YELLOW), false);
            } else if (currentTPS >= 15.0) {
                source.sendFeedback(() -> Text.literal("⚠️ Средняя производительность").formatted(net.minecraft.util.Formatting.YELLOW), false);
            } else {
                source.sendFeedback(() -> Text.literal("❌ Низкая производительность - возможны лаги").formatted(net.minecraft.util.Formatting.RED), false);
            }
            
            // Дополнительная информация о оптимизации
            if (performanceLevel == 2) {
                source.sendFeedback(() -> Text.literal("⚠️ Включены агрессивные оптимизации для высокой нагрузки").formatted(net.minecraft.util.Formatting.RED), false);
            } else if (performanceLevel == 1) {
                source.sendFeedback(() -> Text.literal("⚠️ Включены умеренные оптимизации").formatted(net.minecraft.util.Formatting.YELLOW), false);
            } else {
                source.sendFeedback(() -> Text.literal("✅ Оптимизации не активны, нормальная работа").formatted(net.minecraft.util.Formatting.GREEN), false);
            }
            
            source.sendFeedback(() -> Text.literal("Используйте /race tps on/off для управления отображением").formatted(net.minecraft.util.Formatting.GRAY), false);
            
            return 1;
        } catch (Exception e) {
            source.sendFeedback(() -> Text.literal("Ошибка получения информации о TPS: " + e.getMessage()).formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
    }
    
    /**
     * Переключает автоматическую оптимизацию
     */
    private static int toggleAutoOptimization(CommandContext<ServerCommandSource> ctx, boolean enable) {
        ServerCommandSource source = ctx.getSource();
        
        try {
            ServerPlayerEntity p = source.getPlayer();
            if (p != null && source.getServer().getPlayerManager().isOperator(p.getGameProfile())) {
                race.server.RaceServerInit.setAutoOptimizationEnabled(enable);
                source.sendFeedback(() -> Text.literal("Автоматическая оптимизация: " + (enable ? "ВКЛЮЧЕНА" : "ВЫКЛЮЧЕНА")).formatted(net.minecraft.util.Formatting.GREEN), true);
                return 1;
            }
        } catch (Throwable ignored) {}
        
        race.server.RaceServerInit.setAutoOptimizationEnabled(enable);
        source.sendFeedback(() -> Text.literal("Автоматическая оптимизация: " + (enable ? "ВКЛЮЧЕНА" : "ВЫКЛЮЧЕНА")).formatted(net.minecraft.util.Formatting.GREEN), true);
        return 1;
    }
    
    /**
     * Показывает информацию об автоматической оптимизации
     */
    private static int showAutoOptimizationInfo(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        try {
            boolean autoEnabled = race.server.RaceServerInit.isAutoOptimizationEnabled();
            int autoLevel = race.server.RaceServerInit.getAutoOptimizationLevel();
            double currentTPS = race.server.RaceServerInit.getCurrentTPS();
            String performanceStatus = race.server.RaceServerInit.getPerformanceStatus();
            
            source.sendFeedback(() -> Text.literal("=== Автоматическая оптимизация ===").formatted(net.minecraft.util.Formatting.GOLD), false);
            source.sendFeedback(() -> Text.literal("Статус: " + (autoEnabled ? "ВКЛЮЧЕНА" : "ВЫКЛЮЧЕНА")).formatted(net.minecraft.util.Formatting.WHITE), false);
            source.sendFeedback(() -> Text.literal("Текущий уровень: " + autoLevel).formatted(net.minecraft.util.Formatting.WHITE), false);
            source.sendFeedback(() -> Text.literal("Текущий TPS: " + String.format("%.2f", currentTPS)).formatted(net.minecraft.util.Formatting.WHITE), false);
            source.sendFeedback(() -> Text.literal("Статус производительности: " + performanceStatus).formatted(net.minecraft.util.Formatting.WHITE), false);
            
            // Описание уровней оптимизации
            source.sendFeedback(() -> Text.literal("=== Уровни оптимизации ===").formatted(net.minecraft.util.Formatting.YELLOW), false);
            source.sendFeedback(() -> Text.literal("Уровень 0: Нормальная работа").formatted(net.minecraft.util.Formatting.GREEN), false);
            source.sendFeedback(() -> Text.literal("Уровень 1: Базовая оптимизация (GC)").formatted(net.minecraft.util.Formatting.YELLOW), false);
            source.sendFeedback(() -> Text.literal("Уровень 2: Средняя оптимизация (GC + очистка кэшей)").formatted(net.minecraft.util.Formatting.LIGHT_PURPLE), false);
            source.sendFeedback(() -> Text.literal("Уровень 3: Агрессивная оптимизация (полная очистка)").formatted(net.minecraft.util.Formatting.RED), false);
            
            // Рекомендации
            if (currentTPS < 16.0) {
                source.sendFeedback(() -> Text.literal("⚠️ Низкий TPS - система автоматически применит оптимизации").formatted(net.minecraft.util.Formatting.RED), false);
            } else if (currentTPS > 19.0) {
                source.sendFeedback(() -> Text.literal("✅ Высокий TPS - система может снизить оптимизации").formatted(net.minecraft.util.Formatting.GREEN), false);
            } else {
                source.sendFeedback(() -> Text.literal("✅ TPS в норме - система работает стабильно").formatted(net.minecraft.util.Formatting.GREEN), false);
            }
            
            source.sendFeedback(() -> Text.literal("Используйте /race auto on/off для управления").formatted(net.minecraft.util.Formatting.GRAY), false);
            
            return 1;
        } catch (Exception e) {
            source.sendFeedback(() -> Text.literal("Ошибка получения информации об автоматической оптимизации: " + e.getMessage()).formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
    }
    
    /**
     * Устанавливает качество призраков
     */
    private static int setGhostQuality(CommandContext<ServerCommandSource> ctx, int level) {
        ServerCommandSource source = ctx.getSource();
        
        try {
            race.server.RaceServerInit.setGhostQualityLevel(level);
            String qualityName = switch (level) {
                case 0 -> "Отключено";
                case 1 -> "Низкое";
                case 2 -> "Среднее";
                case 3 -> "Высокое";
                default -> "Неизвестно";
            };
            source.sendFeedback(() -> Text.literal("Качество призраков установлено: " + qualityName + " (уровень " + level + ")").formatted(net.minecraft.util.Formatting.GREEN), true);
            return 1;
        } catch (Exception e) {
            source.sendFeedback(() -> Text.literal("Ошибка установки качества призраков: " + e.getMessage()).formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
    }
    
    /**
     * Переключает адаптивное качество призраков
     */
    private static int toggleGhostAdaptive(CommandContext<ServerCommandSource> ctx, boolean enable) {
        ServerCommandSource source = ctx.getSource();
        
        try {
            race.server.RaceServerInit.setAdaptiveGhostQuality(enable);
            source.sendFeedback(() -> Text.literal("Адаптивное качество призраков: " + (enable ? "ВКЛЮЧЕНО" : "ВЫКЛЮЧЕНО")).formatted(net.minecraft.util.Formatting.GREEN), true);
            return 1;
        } catch (Exception e) {
            source.sendFeedback(() -> Text.literal("Ошибка переключения адаптивного качества: " + e.getMessage()).formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
    }
    
    /**
     * Показывает информацию о качестве призраков
     */
    private static int showGhostQualityInfo(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        try {
            int currentLevel = race.server.RaceServerInit.getGhostQualityLevel();
            boolean adaptive = race.server.RaceServerInit.isAdaptiveGhostQuality();
            int adaptiveLevel = race.server.RaceServerInit.getAdaptiveGhostQuality();
            
            source.sendFeedback(() -> Text.literal("=== Качество призраков ===").formatted(net.minecraft.util.Formatting.GOLD), false);
            source.sendFeedback(() -> Text.literal("Текущий уровень: " + currentLevel).formatted(net.minecraft.util.Formatting.WHITE), false);
            source.sendFeedback(() -> Text.literal("Адаптивное качество: " + (adaptive ? "ВКЛЮЧЕНО" : "ВЫКЛЮЧЕНО")).formatted(net.minecraft.util.Formatting.WHITE), false);
            source.sendFeedback(() -> Text.literal("Активный уровень: " + adaptiveLevel).formatted(net.minecraft.util.Formatting.WHITE), false);
            
            // Описание уровней качества
            source.sendFeedback(() -> Text.literal("=== Уровни качества ===").formatted(net.minecraft.util.Formatting.YELLOW), false);
            source.sendFeedback(() -> Text.literal("Уровень 0: Отключено (0% частиц)").formatted(net.minecraft.util.Formatting.RED), false);
            source.sendFeedback(() -> Text.literal("Уровень 1: Низкое (25% частиц)").formatted(net.minecraft.util.Formatting.YELLOW), false);
            source.sendFeedback(() -> Text.literal("Уровень 2: Среднее (50% частиц)").formatted(net.minecraft.util.Formatting.LIGHT_PURPLE), false);
            source.sendFeedback(() -> Text.literal("Уровень 3: Высокое (100% частиц)").formatted(net.minecraft.util.Formatting.GREEN), false);
            
            source.sendFeedback(() -> Text.literal("Используйте /race ghost quality <0-3> для установки").formatted(net.minecraft.util.Formatting.GRAY), false);
            
            return 1;
        } catch (Exception e) {
            source.sendFeedback(() -> Text.literal("Ошибка получения информации о качестве призраков: " + e.getMessage()).formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
    }
    
    /**
     * Показывает информацию об адаптивном качестве призраков
     */
    private static int showGhostAdaptiveInfo(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        try {
            boolean adaptive = race.server.RaceServerInit.isAdaptiveGhostQuality();
            int adaptiveLevel = race.server.RaceServerInit.getAdaptiveGhostQuality();
            double currentTPS = race.server.RaceServerInit.getCurrentTPS();
            
            source.sendFeedback(() -> Text.literal("=== Адаптивное качество призраков ===").formatted(net.minecraft.util.Formatting.GOLD), false);
            source.sendFeedback(() -> Text.literal("Статус: " + (adaptive ? "ВКЛЮЧЕНО" : "ВЫКЛЮЧЕНО")).formatted(net.minecraft.util.Formatting.WHITE), false);
            source.sendFeedback(() -> Text.literal("Текущий уровень: " + adaptiveLevel).formatted(net.minecraft.util.Formatting.WHITE), false);
            source.sendFeedback(() -> Text.literal("Текущий TPS: " + String.format("%.2f", currentTPS)).formatted(net.minecraft.util.Formatting.WHITE), false);
            
            // Логика адаптивного качества
            source.sendFeedback(() -> Text.literal("=== Логика адаптации ===").formatted(net.minecraft.util.Formatting.YELLOW), false);
            source.sendFeedback(() -> Text.literal("TPS < 15.0: Уровень 0 (отключено)").formatted(net.minecraft.util.Formatting.RED), false);
            source.sendFeedback(() -> Text.literal("TPS < 18.0: Уровень 1 (низкое)").formatted(net.minecraft.util.Formatting.YELLOW), false);
            source.sendFeedback(() -> Text.literal("TPS < 19.5: Уровень 2 (среднее)").formatted(net.minecraft.util.Formatting.LIGHT_PURPLE), false);
            source.sendFeedback(() -> Text.literal("TPS >= 19.5: Уровень 3 (высокое)").formatted(net.minecraft.util.Formatting.GREEN), false);
            
            source.sendFeedback(() -> Text.literal("Используйте /race ghost adaptive on/off для управления").formatted(net.minecraft.util.Formatting.GRAY), false);
            
            return 1;
        } catch (Exception e) {
            source.sendFeedback(() -> Text.literal("Ошибка получения информации об адаптивном качестве: " + e.getMessage()).formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
    }
    
    /**
     * Показывает общую информацию о призраках
     */
    private static int showGhostInfo(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        try {
            int currentLevel = race.server.RaceServerInit.getGhostQualityLevel();
            boolean adaptive = race.server.RaceServerInit.isAdaptiveGhostQuality();
            int adaptiveLevel = race.server.RaceServerInit.getAdaptiveGhostQuality();
            double currentTPS = race.server.RaceServerInit.getCurrentTPS();
            
            source.sendFeedback(() -> Text.literal("=== Информация о призраках ===").formatted(net.minecraft.util.Formatting.GOLD), false);
            source.sendFeedback(() -> Text.literal("Базовый уровень: " + currentLevel).formatted(net.minecraft.util.Formatting.WHITE), false);
            source.sendFeedback(() -> Text.literal("Адаптивное качество: " + (adaptive ? "ВКЛЮЧЕНО" : "ВЫКЛЮЧЕНО")).formatted(net.minecraft.util.Formatting.WHITE), false);
            source.sendFeedback(() -> Text.literal("Активный уровень: " + adaptiveLevel).formatted(net.minecraft.util.Formatting.WHITE), false);
            source.sendFeedback(() -> Text.literal("Текущий TPS: " + String.format("%.2f", currentTPS)).formatted(net.minecraft.util.Formatting.WHITE), false);
            
            // Рекомендации
            if (currentTPS < 15.0) {
                source.sendFeedback(() -> Text.literal("⚠️ Низкий TPS - призраки отключены для экономии производительности").formatted(net.minecraft.util.Formatting.RED), false);
            } else if (currentTPS < 18.0) {
                source.sendFeedback(() -> Text.literal("⚠️ Средний TPS - призраки работают в режиме низкого качества").formatted(net.minecraft.util.Formatting.YELLOW), false);
            } else if (currentTPS < 19.5) {
                source.sendFeedback(() -> Text.literal("✅ Хороший TPS - призраки работают в среднем качестве").formatted(net.minecraft.util.Formatting.LIGHT_PURPLE), false);
            } else {
                source.sendFeedback(() -> Text.literal("✅ Отличный TPS - призраки работают в высоком качестве").formatted(net.minecraft.util.Formatting.GREEN), false);
            }
            
            source.sendFeedback(() -> Text.literal("Используйте /race ghost quality и /race ghost adaptive для настройки").formatted(net.minecraft.util.Formatting.GRAY), false);
            
            return 1;
        } catch (Exception e) {
            source.sendFeedback(() -> Text.literal("Ошибка получения информации о призраках: " + e.getMessage()).formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
    }
    
    /**
     * Принудительное размораживание себя
     */
    private static int forceUnfreezeSelf(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        if (!(source.getEntity() instanceof ServerPlayerEntity)) {
            source.sendFeedback(() -> Text.literal("Эта команда доступна только игрокам").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        ServerPlayerEntity player = (ServerPlayerEntity) source.getEntity();
        
        try {
            if (race.server.RaceServerInit.isFrozen(player.getUuid())) {
                race.server.RaceServerInit.forceUnfreezePlayer(player);
                source.sendFeedback(() -> Text.literal("Вы были принудительно разморожены").formatted(net.minecraft.util.Formatting.GREEN), false);
                return 1;
            } else {
                source.sendFeedback(() -> Text.literal("Вы не заморожены").formatted(net.minecraft.util.Formatting.YELLOW), false);
                return 0;
            }
        } catch (Exception e) {
            source.sendFeedback(() -> Text.literal("Ошибка размораживания: " + e.getMessage()).formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
    }
    
    /**
     * Принудительное размораживание всех игроков
     */
    private static int forceUnfreezeAll(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        try {
            MinecraftServer server = source.getServer();
            race.server.RaceServerInit.forceUnfreezeAllPlayers(server);
            source.sendFeedback(() -> Text.literal("Все замороженные игроки были принудительно разморожены").formatted(net.minecraft.util.Formatting.GREEN), false);
            return 1;
        } catch (Exception e) {
            source.sendFeedback(() -> Text.literal("Ошибка размораживания всех игроков: " + e.getMessage()).formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
    }
    
    // ========== КОМАНДЫ УПРАВЛЕНИЯ ВРЕМЕНЕМ ==========
    
    
    /**
     * Очищает состояние гонки для текущего игрока
     */
    private static int clearRaceState(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendFeedback(() -> Text.literal("Только игрок может очистить свое состояние").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        try {
            // Очищаем состояние гонки игрока
            race.hub.HubManager.clearPlayerChoice(player.getUuid());
            
            source.sendFeedback(() -> Text.literal("Состояние гонки очищено для " + player.getName().getString())
                .formatted(net.minecraft.util.Formatting.GREEN), false);
            
            player.sendMessage(Text.literal("Ваше состояние гонки очищено! Теперь вы можете начать новую гонку.")
                .formatted(net.minecraft.util.Formatting.GREEN), false);
                
        } catch (Throwable t) {
            source.sendFeedback(() -> Text.literal("Ошибка при очистке состояния: " + t.getMessage())
                .formatted(net.minecraft.util.Formatting.RED), false);
        }
        return 1;
    }
    
    /**
     * Очищает состояние гонки для всех игроков
     */
    private static int clearAllRaceState(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        try {
            int clearedCount = 0;
            
            // Очищаем состояние для всех игроков
            for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
                race.hub.HubManager.clearPlayerChoice(player.getUuid());
                clearedCount++;
            }

            int finalClearedCount = clearedCount;
            source.sendFeedback(() -> Text.literal("Состояние гонки очищено для " + finalClearedCount + " игроков")
                .formatted(net.minecraft.util.Formatting.GREEN), true);
                
        } catch (Throwable t) {
            source.sendFeedback(() -> Text.literal("Ошибка при очистке состояния: " + t.getMessage())
                .formatted(net.minecraft.util.Formatting.RED), false);
        }
        return 1;
    }
    
    private static int debugReturnPoint(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            return 0;
        }

        race.server.world.ReturnPointRegistry.ReturnPoint rp = 
            race.server.world.ReturnPointRegistry.peek(player.getUuid());
        
        if (rp == null) {
            source.sendFeedback(() -> Text.literal("Нет сохраненной точки возврата").formatted(net.minecraft.util.Formatting.RED), false);
        } else {
            source.sendFeedback(() -> Text.literal("Точка возврата: " + rp.worldKey.getValue() + 
                                                  " (" + String.format("%.1f, %.1f, %.1f", rp.x, rp.y, rp.z) + ")").formatted(net.minecraft.util.Formatting.GREEN), false);
            source.sendFeedback(() -> Text.literal("Режим игры: " + rp.gameMode.name()).formatted(net.minecraft.util.Formatting.WHITE), false);
        }
        
        return 1;
    }
    
    // Старые команды времени удалены - теперь используются RaceTimeCommands
    
    /**
     * Устанавливает текущий мир как предпочитаемый для входа
     */
    private static int setPreferredWorld(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        ServerWorld world = player.getServerWorld();
        
        if (!world.getRegistryKey().getValue().getNamespace().equals("fabric_race")) {
            ctx.getSource().sendError(Text.literal("Можно установить только персональный мир как предпочитаемый"));
            return 0;
        }
        
        race.server.world.PreferredWorldRegistry.setPreferred(player.getUuid(), world.getRegistryKey());
        ctx.getSource().sendFeedback(() -> Text.literal("Установлен предпочитаемый мир: " + world.getRegistryKey().getValue()), true);
        return 1;
    }
    
    /**
     * Очищает предпочитаемый мир
     */
    private static int clearPreferredWorld(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        
        race.server.world.PreferredWorldRegistry.clear(player.getUuid());
        ctx.getSource().sendFeedback(() -> Text.literal("Предпочитаемый мир очищен"), true);
        return 1;
    }
    
    /**
     * Принудительно включает дымку параллельных игроков
     */
    private static int forceEnableGhosts(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        // Принудительно включаем дымку
        race.server.RaceServerInit.setDisplayParallelPlayers(true);
        race.server.RaceServerInit.setGhostQualityLevel(3); // Максимальное качество
        race.server.RaceServerInit.setAdaptiveGhostQuality(false); // Отключаем адаптивность
        
        source.sendFeedback(() -> Text.literal("Дымка параллельных игроков принудительно включена (качество: максимальное)").formatted(net.minecraft.util.Formatting.GREEN), true);
        source.sendFeedback(() -> Text.literal("Адаптивное качество отключено для стабильной видимости").formatted(net.minecraft.util.Formatting.YELLOW), false);
        
        return 1;
    }
    
    /**
     * Показывает статус дымки параллельных игроков
     */
    private static int showGhostStatus(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        boolean enabled = race.server.RaceServerInit.isDisplayParallelPlayers();
        int quality = race.server.RaceServerInit.getGhostQualityLevel();
        boolean adaptive = race.server.RaceServerInit.isAdaptiveGhostQuality();
        double tps = race.server.RaceServerInit.getCurrentTPS();
        
        source.sendFeedback(() -> Text.literal("=== Статус дымки параллельных игроков ===").formatted(net.minecraft.util.Formatting.GOLD), false);
        source.sendFeedback(() -> Text.literal("Включена: " + (enabled ? "ДА" : "НЕТ")).formatted(enabled ? net.minecraft.util.Formatting.GREEN : net.minecraft.util.Formatting.RED), false);
        source.sendFeedback(() -> Text.literal("Качество: " + quality + " (0=отключено, 1=низкое, 2=среднее, 3=высокое)").formatted(net.minecraft.util.Formatting.YELLOW), false);
        source.sendFeedback(() -> Text.literal("Адаптивное: " + (adaptive ? "ДА" : "НЕТ")).formatted(adaptive ? net.minecraft.util.Formatting.GREEN : net.minecraft.util.Formatting.RED), false);
        source.sendFeedback(() -> Text.literal("TPS: " + String.format("%.2f", tps)).formatted(net.minecraft.util.Formatting.AQUA), false);
        
        // Дополнительная диагностика
        int playerCount = source.getServer().getPlayerManager().getPlayerList().size();
        source.sendFeedback(() -> Text.literal("Игроков на сервере: " + playerCount).formatted(net.minecraft.util.Formatting.AQUA), false);
        
        if (source.getEntity() instanceof ServerPlayerEntity player) {
            String worldKey = player.getServerWorld().getRegistryKey().getValue().toString();
            source.sendFeedback(() -> Text.literal("Ваш мир: " + worldKey).formatted(net.minecraft.util.Formatting.AQUA), false);
            source.sendFeedback(() -> Text.literal("В персональном мире: " + (worldKey.startsWith("fabric_race:") ? "ДА" : "НЕТ")).formatted(worldKey.startsWith("fabric_race:") ? net.minecraft.util.Formatting.GREEN : net.minecraft.util.Formatting.RED), false);
        }
        
        if (enabled && quality > 0) {
            source.sendFeedback(() -> Text.literal("✅ Дымка должна работать!").formatted(net.minecraft.util.Formatting.GREEN), false);
        } else {
            source.sendFeedback(() -> Text.literal("❌ Дымка отключена или качество = 0").formatted(net.minecraft.util.Formatting.RED), false);
        }
        
        return 1;
    }
    
    /**
     * Тестирует отправку дымки хосту
     */
    private static int testGhostTrail(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendFeedback(() -> Text.literal("Только игроки могут тестировать дымку").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        // Создаем тестовый след
        var testPoints = new java.util.ArrayList<race.net.GhostTrailPayload.Point>();
        testPoints.add(new race.net.GhostTrailPayload.Point(player.getX(), player.getY(), player.getZ()));
        testPoints.add(new race.net.GhostTrailPayload.Point(player.getX() + 1, player.getY(), player.getZ()));
        testPoints.add(new race.net.GhostTrailPayload.Point(player.getX() + 2, player.getY(), player.getZ()));
        
        var payload = new race.net.GhostTrailPayload("test_player", "Тест дымки", testPoints);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
        
        source.sendFeedback(() -> Text.literal("Отправлен тестовый след дымки").formatted(net.minecraft.util.Formatting.GREEN), true);
        source.sendFeedback(() -> Text.literal("Если вы видите дымку - система работает!").formatted(net.minecraft.util.Formatting.YELLOW), false);
        
        return 1;
    }

    private static int unfreezePlayer(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendFeedback(() -> Text.literal("Только игроки могут использовать эту команду").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        // Принудительно размораживаем игрока
        race.server.RaceServerInit.forceUnfreezePlayer(player);
        
        source.sendFeedback(() -> Text.literal("Вы разморожены! Теперь можете ломать блоки").formatted(net.minecraft.util.Formatting.GREEN), true);
        
        return 1;
    }
    
    private static int unfreezeAllPlayers(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        // Принудительно размораживаем всех игроков
        race.server.RaceServerInit.forceUnfreezeAllPlayers(source.getServer());
        
        source.sendFeedback(() -> Text.literal("Все игроки разморожены!").formatted(net.minecraft.util.Formatting.GREEN), true);
        
        return 1;
    }
    
    private static int showLobbyList(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = source.getPlayer();
        
        if (player == null) {
            source.sendFeedback(() -> Text.literal("Команда доступна только игрокам!").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        // Отправляем список лобби игроку
        race.server.RaceServerInit.invokeSendLobbyList(player);
        source.sendFeedback(() -> Text.literal("Список лобби отправлен!").formatted(net.minecraft.util.Formatting.GREEN), false);
        
        return 1;
    }

    // Параллельная гонка - создаем новый мир с тем же сидом но на свободном слоте
    private static int parallelRace(CommandContext<ServerCommandSource> ctx, String targetPlayerName) {
        ServerCommandSource source = ctx.getSource();
        
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendFeedback(() -> Text.literal("Только игроки могут создавать параллельные гонки").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        // Проверяем, не находится ли игрок уже в персональном мире
        boolean alreadyInRaceWorld = player.getServerWorld().getRegistryKey().getValue().toString().startsWith("fabric_race:");
        if (alreadyInRaceWorld) {
            source.sendFeedback(() -> Text.literal("Вы уже в персональном мире! Используйте /race join для присоединения к другим игрокам.").formatted(net.minecraft.util.Formatting.YELLOW), false);
            return 1;
        }
        
        // Находим целевого игрока
        ServerPlayerEntity target = source.getServer().getPlayerManager().getPlayer(targetPlayerName);
        if (target == null) {
            source.sendFeedback(() -> Text.literal("Игрок " + targetPlayerName + " не найден").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        // Получаем сид целевого игрока
        long targetSeed = race.hub.HubManager.getPlayerSeedChoice(target.getUuid());
        if (targetSeed < 0) {
            source.sendFeedback(() -> Text.literal("У игрока " + targetPlayerName + " не установлен сид").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        // Проверяем, что целевой игрок в персональном мире
        String targetWorldName = target.getServerWorld().getRegistryKey().getValue().toString();
        if (!targetWorldName.startsWith("fabric_race:")) {
            source.sendFeedback(() -> Text.literal("Игрок " + targetPlayerName + " не в персональном мире").formatted(net.minecraft.util.Formatting.RED), false);
            return 0;
        }
        
        try {
            // Устанавливаем тот же сид для текущего игрока
            race.hub.HubManager.setPlayerSeedChoice(player.getUuid(), targetSeed);
            
            // Находим свободный слот
            int freeSlot = race.server.world.EnhancedWorldManager.findFirstFreeSlotForSeed(source.getServer(), targetSeed);
            
            // Создаем параллельный мир с тем же сидом но на свободном слоте
            var parallelWorld = race.server.world.EnhancedWorldManager.getOrCreateWorldForParallelRace(
                source.getServer(), player.getUuid(), freeSlot, targetSeed, net.minecraft.world.World.OVERWORLD);
                
            if (parallelWorld != null) {
                // Прогреваем чанки перед телепортацией
                race.server.world.SpawnCache.warmupAndCache(parallelWorld, parallelWorld.getSpawnPos(), 2);
                
                // УПРОЩЕНИЕ: Миксин обработает null чанки безопасно
                
                // Телепортируем в параллельный мир
                race.tp.SafeTeleport.toWorldSpawn(player, parallelWorld);
                race.hub.HubManager.setLastWorldSeed(player.getUuid(), targetSeed);
                
                // Сохраняем точку возврата
                race.server.world.ReturnPointRegistry.saveCurrent(player);
                
                source.sendFeedback(() -> Text.literal("Вы телепортированы в параллельный мир (слот " + freeSlot + ") с сидом " + targetSeed + "!").formatted(net.minecraft.util.Formatting.GREEN), false);
                LOGGER.info("[Race] Parallel race created: {} -> slot {} with seed {} (target: {})", 
                          player.getName().getString(), freeSlot, targetSeed, targetPlayerName);
            } else {
                source.sendFeedback(() -> Text.literal("Ошибка создания параллельного мира").formatted(net.minecraft.util.Formatting.RED), false);
            }
        } catch (Exception e) {
            source.sendFeedback(() -> Text.literal("Ошибка при создании параллельной гонки: " + e.getMessage()).formatted(net.minecraft.util.Formatting.RED), false);
            LOGGER.warn("[Race] Error during parallel race creation: {}", e.getMessage());
        }
        
        return 1;
    }


}
