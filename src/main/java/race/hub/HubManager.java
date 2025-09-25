package race.hub;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import race.server.world.ServerRaceConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;

/**
 * Менеджер хаба для выбора сида и подключения игроков
 */
public class HubManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, Long> playerSeedChoices = new HashMap<>();
    private static final Map<Long, Integer> seedPlayerCount = new HashMap<>();
    private static final Map<UUID, Long> lastWorldSeeds = new HashMap<>();
    // Лидер старта для каждой группы по сиду
    private static final Map<Long, UUID> seedLeaders = new HashMap<>();
    // Время готовности игрока (для выбора лидера на dedicated)
    private static final Map<UUID, Long> readyTimestamps = new HashMap<>();
    // Отслеживание времени создания миров
    private static final Map<UUID, Long> worldCreationTimestamps = new HashMap<>();
    // Отслеживание времени последней обработки для предотвращения спама
    private static final Map<UUID, Long> lastProcessedTime = new HashMap<>();
    // Ссылка на сервер
    private static MinecraftServer serverInstance;
    // Приглашения в команду: target -> inviter
    private static final Map<UUID, UUID> pendingTeamInvites = new HashMap<>();
    // Членство в команде: player -> leader (leader может указывать на себя)
    private static final Map<UUID, UUID> teamLeaderOfPlayer = new HashMap<>();
    private static boolean hubHouseBuilt = false;
    private static BlockPos hubHouseBase = null; // внутренняя точка спавна в домике
    private static boolean hubActive = false;
    private static ServerWorld hubWorld;
    private static java.nio.file.Path hubDataFile;
    
    /**
     * Инициализирует хаб-мир
     */
    public static void initializeHub(MinecraftServer server) {
        // Инициализируем ссылку на сервер
        setServer(server);
        
        // Инициализируем путь к файлу данных хаба
        try {
            var session = ((race.mixin.MinecraftServerSessionAccessor) server).getSession_FAB();
            hubDataFile = session.getWorldDirectory(net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, net.minecraft.util.Identifier.of("minecraft", "overworld"))).resolve("hub_data.json");
        } catch (Exception e) {
            hubDataFile = java.nio.file.Paths.get("run", "saves", "hub_data.json");
        }
        
        // Загружаем сохраненные данные хаба
        loadHubData();
        
        // Создаем или получаем хаб-мир
        hubWorld = server.getWorld(server.getOverworld().getRegistryKey());
        hubActive = true;
        
        // Настраиваем хаб-мир
        setupHubWorld();
        // Переносим ванильный спавн на центр домика
        try {
            BlockPos spawn = hubWorld.getSpawnPos();
            int top = Math.max(spawn.getY(), hubWorld.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, spawn.getX(), spawn.getZ()));
            hubWorld.setSpawnPos(new BlockPos(spawn.getX(), top, spawn.getZ()), 0.0F);
        } catch (Throwable ignored) {}
        
        // Сохраняем данные хаба
        saveHubData();
    }
    
    /**
     * Настраивает хаб-мир (спавн, блоки, NPC и т.д.)
     */
    private static void setupHubWorld() {
        if (hubWorld == null || hubHouseBuilt) return;
        try {
            // Базовая точка — реальный верхний уровень спавна
            BlockPos spawn = hubWorld.getSpawnPos();
            int top = Math.max(spawn.getY(), hubWorld.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, spawn.getX(), spawn.getZ()));
            BlockPos base = new BlockPos(spawn.getX(), top, spawn.getZ());

            // Очищаем пространство 15x9x15 (ш x в x г)
            for (int dx = -7; dx <= 7; dx++) {
                for (int dy = -1; dy <= 7; dy++) {
                    for (int dz = -7; dz <= 7; dz++) {
                        hubWorld.setBlockState(base.add(dx, dy, dz), net.minecraft.block.Blocks.AIR.getDefaultState());
                    }
                }
            }

            // Пол из полированного андезита 13x13 на уровне base.y - 1
            for (int dx = -6; dx <= 6; dx++) {
                for (int dz = -6; dz <= 6; dz++) {
                    hubWorld.setBlockState(base.add(dx, -1, dz), net.minecraft.block.Blocks.POLISHED_ANDESITE.getDefaultState());
                }
            }

            // Колонны по углам (бревно) высотой 4
            int[][] corners = {{-6,-6},{-6,6},{6,-6},{6,6}};
            for (int[] c : corners) {
                for (int h = 0; h < 4; h++) {
                    hubWorld.setBlockState(base.add(c[0], h, c[1]), net.minecraft.block.Blocks.SPRUCE_LOG.getDefaultState());
                }
            }

            // Стены из досок с окнами из стекла (полные блоки, чтобы не разваливались)
            for (int x = -6; x <= 6; x++) {
                for (int h = 0; h < 3; h++) {
                    if (x == 0) continue; // проём под дверь с юга/севера обработаем отдельно
                    // северная стена (z = -6)
                    net.minecraft.block.Block wall = (Math.abs(x) % 3 == 1 && h == 1) ? net.minecraft.block.Blocks.GLASS : net.minecraft.block.Blocks.SPRUCE_PLANKS;
                    hubWorld.setBlockState(base.add(x, h, -6), wall.getDefaultState());
                    // южная стена (z = 6)
                    wall = (Math.abs(x) % 3 == 1 && h == 1) ? net.minecraft.block.Blocks.GLASS : net.minecraft.block.Blocks.SPRUCE_PLANKS;
                    hubWorld.setBlockState(base.add(x, h, 6), wall.getDefaultState());
                }
            }
            for (int z = -6; z <= 6; z++) {
                for (int h = 0; h < 3; h++) {
                    // западная стена (x = -6)
                    net.minecraft.block.Block wall = (Math.abs(z) % 3 == 1 && h == 1) ? net.minecraft.block.Blocks.GLASS : net.minecraft.block.Blocks.SPRUCE_PLANKS;
                    hubWorld.setBlockState(base.add(-6, h, z), wall.getDefaultState());
                    // восточная стена (x = 6)
                    wall = (Math.abs(z) % 3 == 1 && h == 1) ? net.minecraft.block.Blocks.GLASS : net.minecraft.block.Blocks.SPRUCE_PLANKS;
                    hubWorld.setBlockState(base.add(6, h, z), wall.getDefaultState());
                }
            }

            // Проём двери с южной стороны
            hubWorld.setBlockState(base.add(0, 0, 6), net.minecraft.block.Blocks.AIR.getDefaultState());
            hubWorld.setBlockState(base.add(0, 1, 6), net.minecraft.block.Blocks.AIR.getDefaultState());

            // Дверь (дубовая) на южной стороне, ориентирована на юг
            try {
                var doorLower = net.minecraft.block.Blocks.OAK_DOOR.getDefaultState()
                        .with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, net.minecraft.util.math.Direction.SOUTH)
                        .with(net.minecraft.state.property.Properties.DOUBLE_BLOCK_HALF, net.minecraft.block.enums.DoubleBlockHalf.LOWER);
                var doorUpper = net.minecraft.block.Blocks.OAK_DOOR.getDefaultState()
                        .with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, net.minecraft.util.math.Direction.SOUTH)
                        .with(net.minecraft.state.property.Properties.DOUBLE_BLOCK_HALF, net.minecraft.block.enums.DoubleBlockHalf.UPPER);
                hubWorld.setBlockState(base.add(0, 0, 6), doorLower);
                hubWorld.setBlockState(base.add(0, 1, 6), doorUpper);
            } catch (Throwable ignored) {}

            // Крыша из дубовых досок — два слоя (y+3 и y+4) для надёжности
            for (int dx = -6; dx <= 6; dx++) {
                for (int dz = -6; dz <= 6; dz++) {
                    hubWorld.setBlockState(base.add(dx, 3, dz), net.minecraft.block.Blocks.OAK_PLANKS.getDefaultState());
                    hubWorld.setBlockState(base.add(dx, 4, dz), net.minecraft.block.Blocks.OAK_PLANKS.getDefaultState());
                }
            }

            // Интерьер: верстак, печи, стол чар, наковальня, кровати, сундуки
            hubWorld.setBlockState(base.add(0, 0, 0), net.minecraft.block.Blocks.ENCHANTING_TABLE.getDefaultState());
            hubWorld.setBlockState(base.add(2, 0, 0), net.minecraft.block.Blocks.ANVIL.getDefaultState());
            hubWorld.setBlockState(base.add(-2, 0, 0), net.minecraft.block.Blocks.CRAFTING_TABLE.getDefaultState());
            hubWorld.setBlockState(base.add(3, 0, 0), net.minecraft.block.Blocks.FURNACE.getDefaultState());
            hubWorld.setBlockState(base.add(-3, 0, 0), net.minecraft.block.Blocks.FURNACE.getDefaultState());
            hubWorld.setBlockState(base.add(0, 0, 2), net.minecraft.block.Blocks.CHEST.getDefaultState());
            hubWorld.setBlockState(base.add(0, 0, -2), net.minecraft.block.Blocks.CHEST.getDefaultState());
            // Заполняем сундук(и) бумажными «страницами» гайда
            try {
                fillGuideChests(base);
            } catch (Throwable ignored) {}
            // кровати (две) на восточной стороне — корректно размещаем голову/ноги, ориентированы на восток
            try {
                var foot = net.minecraft.block.Blocks.WHITE_BED.getDefaultState()
                        .with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, net.minecraft.util.math.Direction.EAST)
                        .with(net.minecraft.block.BedBlock.PART, net.minecraft.block.enums.BedPart.FOOT);
                var head = net.minecraft.block.Blocks.WHITE_BED.getDefaultState()
                        .with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, net.minecraft.util.math.Direction.EAST)
                        .with(net.minecraft.block.BedBlock.PART, net.minecraft.block.enums.BedPart.HEAD);
                // кровать 1
                hubWorld.setBlockState(base.add(4, 0, -1), foot);
                hubWorld.setBlockState(base.add(5, 0, -1), head);
                // кровать 2
                hubWorld.setBlockState(base.add(4, 0, 1), foot);
                hubWorld.setBlockState(base.add(5, 0, 1), head);
            } catch (Throwable ignored) {}

            // Освещение: факелы на колоннах
            hubWorld.setBlockState(base.add(-6, 2, -6), net.minecraft.block.Blocks.TORCH.getDefaultState());
            hubWorld.setBlockState(base.add(6, 2, -6), net.minecraft.block.Blocks.TORCH.getDefaultState());
            hubWorld.setBlockState(base.add(-6, 2, 6), net.minecraft.block.Blocks.TORCH.getDefaultState());
            hubWorld.setBlockState(base.add(6, 2, 6), net.minecraft.block.Blocks.TORCH.getDefaultState());

            // Фундамент под стенами до твёрдой поверхности (чтобы дом не висел)
            for (int dx = -6; dx <= 6; dx++) {
                for (int dz = -6; dz <= 6; dz++) {
                    if (Math.abs(dx) == 6 || Math.abs(dz) == 6) {
                        for (int dy = -2; dy >= -12; dy--) {
                            BlockPos p = base.add(dx, dy, dz);
                            if (!hubWorld.isAir(p)) break;
                            hubWorld.setBlockState(p, net.minecraft.block.Blocks.STONE_BRICKS.getDefaultState());
                        }
                    }
                }
            }

            // Лестница к земле с южной стороны
            int ground = top;
            for (int d = 0; d < 16; d++) {
                BlockPos p = base.add(0, -d-1, 7 + d);
                if (!hubWorld.isAir(p)) { ground = p.getY(); break; }
            }
            int steps = Math.max(0, (top - ground));
            for (int i = 0; i < steps; i++) {
                BlockPos stairPos = base.add(0, -i, 6 + i);
                hubWorld.setBlockState(stairPos, net.minecraft.block.Blocks.STONE_BRICK_STAIRS.getDefaultState());
            }

            // Запомним внутреннюю безопасную точку и обновим спавн мира
            hubHouseBase = base;
            try { hubWorld.setSpawnPos(base, 0.0F); } catch (Throwable ignored) {}

            hubHouseBuilt = true;
            
            // Сохраняем данные хаба после постройки
            saveHubData();
            LOGGER.info("[Race] Hub house built and data saved at {}", base);
        } catch (Throwable ignored) {}
    }

    // Заполняем сундуки в хабе «гайдом»: бумага с пронумерованными страницами и краткими подсказками
    private static void fillGuideChests(BlockPos base) {
        try {
            net.minecraft.block.entity.BlockEntity be1 = hubWorld.getBlockEntity(base.add(0, 0, 2));
            net.minecraft.block.entity.BlockEntity be2 = hubWorld.getBlockEntity(base.add(0, 0, -2));
            if (be1 instanceof net.minecraft.block.entity.ChestBlockEntity chest1) {
                fillChestWithGuide(chest1);
            }
            if (be2 instanceof net.minecraft.block.entity.ChestBlockEntity chest2) {
                fillChestWithGuide(chest2);
            }
        } catch (Throwable ignored) {}
    }

    private static void fillChestWithGuide(net.minecraft.block.entity.ChestBlockEntity chest) {
        try {
            chest.clear();
        } catch (Throwable ignored) {}
        java.util.List<String> pages = java.util.Arrays.asList(
                "R — меню гонки; G — ачивки; U — HUD",
                "Команды: /race team invite <ник>, затем Accept",
                "Выбор сида: /race seed <num> или seed random",
                "Готовность: /race ready; Старт: лидер делает /race go",
                "Join/Spectate в меню R; /race lobby; /race ghosts",
                "Цели: инструменты+еда → портал → перлы/стержни → stronghold → end",
                "Совет: F3+C копирует координаты; F3+G границы чанков"
        );
        int slot = 0;
        for (int i = 0; i < pages.size() && slot < chest.size(); i++, slot++) {
            String text = (i + 1) + ". " + pages.get(i);
            net.minecraft.item.ItemStack paper = new net.minecraft.item.ItemStack(net.minecraft.item.Items.PAPER);
            paper.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, net.minecraft.text.Text.literal(text).formatted(net.minecraft.util.Formatting.WHITE));
            try { chest.setStack(slot, paper); } catch (Throwable ignored) {}
        }
        try { chest.markDirty(); } catch (Throwable ignored) {}
    }
    
    /**
     * Телепортирует игрока в хаб
     */
    public static void teleportToHub(ServerPlayerEntity player) {
        if (!hubActive || hubWorld == null) {
            player.sendMessage(Text.literal("Хаб недоступен").formatted(Formatting.RED), false);
            return;
        }
        
        try {
            // ИСПРАВЛЕНИЕ: Сохраняем точку возврата перед переходом в хаб
            // Это гарантирует, что RETURN вернет к исходной позиции
            try {
                race.server.world.ReturnPointRegistry.saveCurrent(player);
                LOGGER.info("[Race] Saved return point for player {} before teleporting to hub", player.getName().getString());
            } catch (Throwable ignored) {}
            
            // Безопасная телепортация в хаб
            BlockPos base = hubHouseBase != null ? hubHouseBase : hubWorld.getSpawnPos();
            
            // Если домик не построен, строим его
            if (!hubHouseBuilt || hubHouseBase == null) {
                LOGGER.info("[Race] Hub house not built, building it now");
                setupHubWorld();
                base = hubHouseBase != null ? hubHouseBase : hubWorld.getSpawnPos();
            }
            
            // Сначала стабилизируем состояние игрока
            player.setHealth(player.getMaxHealth());
            player.getHungerManager().setFoodLevel(20);
            player.getHungerManager().setSaturationLevel(20.0f);
            player.setAir(player.getMaxAir());
            player.setVelocity(0, 0, 0);
            player.fallDistance = 0;
            
            // Меняем режим игры
            player.changeGameMode(GameMode.ADVENTURE);
            
            // ИСПРАВЛЕНИЕ: Используем правильный способ телепортации между мирами
            // Если игрок в персональном мире, используем специальную телепортацию
            if (player.getServerWorld().getRegistryKey().getValue().getNamespace().equals("fabric_race")) {
                LOGGER.info("[Race] Player {} is in personal world, teleporting to hub world", player.getName().getString());
                // Используем EnhancedWorldManager для правильной телепортации
                race.server.world.EnhancedWorldManager.teleportToWorld(player, hubWorld);
            } else {
                // Прямая телепортация в хаб для ванильных миров
                player.teleport(hubWorld, base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 0, 0);
            }
            
            // Финальная стабилизация после телепортации
            player.setHealth(player.getMaxHealth());
            player.getHungerManager().setFoodLevel(20);
            player.getHungerManager().setSaturationLevel(20.0f);
            player.setAir(player.getMaxAir());
            player.setVelocity(0, 0, 0);
            player.fallDistance = 0;
            
            // Обновляем способности
            player.sendAbilitiesUpdate();
            
            // Показываем меню выбора сида
            showSeedSelectionMenu(player);
        } catch (Throwable t) {
            player.sendMessage(Text.literal("Ошибка при переходе в хаб: " + t.getMessage()).formatted(Formatting.RED), false);
        }
    }
    
    /**
     * Показывает меню выбора сида
     */
    private static void showSeedSelectionMenu(ServerPlayerEntity player) {
        player.sendMessage(Text.literal("=== ВЫБОР СИДА ДЛЯ ГОНКИ ===").formatted(Formatting.GOLD), false);
        player.sendMessage(Text.literal("Используйте команды:").formatted(Formatting.YELLOW), false);
        player.sendMessage(Text.literal("/race seed <число> - выбрать сид").formatted(Formatting.WHITE), false);
        player.sendMessage(Text.literal("/race seed random - случайный сид").formatted(Formatting.WHITE), false);
        player.sendMessage(Text.literal("/race ready - готов к гонке").formatted(Formatting.GREEN), false);
        player.sendMessage(Text.literal("Текущий выбранный сид: " + getPlayerSeedChoice(player.getUuid())).formatted(Formatting.AQUA), false);
    }
    
    /**
     * Устанавливает выбор сида игрока
     */
    public static void setPlayerSeedChoice(UUID playerId, long seed) {
        // СТРОГАЯ ПРОВЕРКА: Блокируем любые повторные вызовы с тем же сидом
        Long existingSeed = playerSeedChoices.get(playerId);
        if (existingSeed != null && existingSeed.equals(seed)) {
            LOGGER.info("[Race] Player {} already has seed {} - BLOCKING repeated call", playerId, seed);
            return; // Полная блокировка повторных вызовов
        }
        
        // ДОПОЛНИТЕЛЬНАЯ ПРОВЕРКА: Игрок уже в гонке
        if (race.server.RaceServerInit.personalStarted.contains(playerId)) {
            LOGGER.info("[Race] Player {} already in race - BLOCKING seed change", playerId);
            
            ServerPlayerEntity player = getServer().getPlayerManager().getPlayer(playerId);
            if (player != null) {
                player.sendMessage(net.minecraft.text.Text.literal("Вы уже в гонке! Смена сида заблокирована.")
                    .formatted(net.minecraft.util.Formatting.RED), false);
            }
            return;
        }
        
        // ПРОВЕРКА НА ЧАСТЫЕ ВЫЗОВЫ
        Long lastTime = lastProcessedTime.get(playerId);
        long currentTime = System.currentTimeMillis();
        if (lastTime != null && (currentTime - lastTime) < 2000) { // 2 секунды
            LOGGER.warn("[Race] BLOCKING rapid setPlayerSeedChoice for player {} ({}ms ago)", 
                playerId, currentTime - lastTime);
            return;
        }
        
        lastProcessedTime.put(playerId, currentTime);
        playerSeedChoices.put(playerId, seed);
        
        LOGGER.info("[Race] setPlayerSeedChoice: player={}, seed={} [FIRST TIME]", playerId, seed);
        
        // Обновляем счетчик игроков для этого сида
        seedPlayerCount.put(seed, seedPlayerCount.getOrDefault(seed, 0) + 1);
    }

    // Лидер игрока или сам игрок
    public static UUID getLeader(UUID player) {
        return teamLeaderOfPlayer.getOrDefault(player, player);
    }

    public static java.util.List<UUID> getTeamMembers(UUID leader) {
        java.util.ArrayList<UUID> list = new java.util.ArrayList<>();
        list.add(leader);
        for (var e : teamLeaderOfPlayer.entrySet()) {
            if (e.getValue().equals(leader) && !e.getKey().equals(leader)) list.add(e.getKey());
        }
        return list;
    }

    // Устанавливает сид всей команде (по лидеру игрока chooser)
    public static void setTeamSeedChoice(MinecraftServer server, UUID chooser, long seed) {
        UUID leader = getLeader(chooser);
        java.util.List<UUID> members = getTeamMembers(leader);
        if (members.isEmpty()) members = java.util.List.of(leader);
        
        // ИСПРАВЛЕНИЕ: Фильтруем игроков, которые уже в гонке
        java.util.List<UUID> newMembers = new java.util.ArrayList<>();
        for (UUID id : members) {
            if (!race.server.RaceServerInit.personalStarted.contains(id)) {
                newMembers.add(id);
            } else {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
                if (p != null) {
                    LOGGER.info("[Race] Player {} already in race, skipping team seed assignment", 
                        p.getName().getString());
                    p.sendMessage(Text.literal("Вы уже в гонке! Смена сида заблокирована.").formatted(Formatting.RED), false);
                }
            }
        }
        
        // Устанавливаем сид только для новых игроков
        for (UUID id : newMembers) {
            setPlayerSeedChoice(id, seed);
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
            if (p != null) p.sendMessage(Text.literal("Сид команды установлен: " + seed).formatted(Formatting.AQUA), false);
        }
    }
    
    /**
     * Получает выбор сида игрока
     */
    public static long getPlayerSeedChoice(UUID playerId) {
        return playerSeedChoices.getOrDefault(playerId, -1L);
    }
    
    /**
     * Устанавливает последний использованный мир игрока
     */
    public static void setLastWorldSeed(UUID playerId, long seed) {
        lastWorldSeeds.put(playerId, seed);
        LOGGER.info("[Race] setLastWorldSeed: player={}, seed={}", playerId, seed);
    }
    
    /**
     * Получает последний использованный мир игрока
     */
    public static long getLastWorldSeed(UUID playerId) {
        return lastWorldSeeds.getOrDefault(playerId, -1L);
    }

    /** Отметить игрока готовым (для выбора лидера) */
    public static void markReady(UUID playerId) {
        readyTimestamps.put(playerId, System.currentTimeMillis());
    }
    
    /**
     * Проверяет, готов ли игрок к гонке
     */
    public static boolean isPlayerReady(UUID playerId) {
        return playerSeedChoices.containsKey(playerId) && playerSeedChoices.get(playerId) >= 0;
    }
    
    /**
     * Получает количество игроков, выбравших определенный сид
     */
    public static int getSeedPlayerCount(long seed) {
        return seedPlayerCount.getOrDefault(seed, 0);
    }
    
    /**
     * Получает всех игроков, выбравших определенный сид
     */
    public static Map<UUID, Long> getPlayersForSeed(long seed) {
        Map<UUID, Long> players = new HashMap<>();
        for (Map.Entry<UUID, Long> entry : playerSeedChoices.entrySet()) {
            if (entry.getValue().equals(seed)) {
                players.put(entry.getKey(), entry.getValue());
            }
        }
        return players;
    }

    public static void setSeedLeader(long seed, UUID leader) {
        seedLeaders.put(seed, leader);
    }

    public static UUID getSeedLeader(long seed) {
        return seedLeaders.get(seed);
    }

    // === Команды/команды (party) ===
    public static void inviteToTeam(ServerPlayerEntity inviter, ServerPlayerEntity target) {
        if (inviter == null || target == null) return;
        pendingTeamInvites.put(target.getUuid(), inviter.getUuid());
        inviter.sendMessage(Text.literal("Приглашение отправлено " + target.getName().getString()).formatted(Formatting.YELLOW), false);
        target.sendMessage(Text.literal(inviter.getName().getString() + " приглашает в команду (/race team accept)" ).formatted(Formatting.AQUA), false);
    }

    public static void acceptTeamInvite(ServerPlayerEntity player) {
        UUID inviter = pendingTeamInvites.remove(player.getUuid());
        if (inviter == null) {
            player.sendMessage(Text.literal("Нет активных приглашений").formatted(Formatting.RED), false);
            return;
        }
        // Объединяем под лидером-вдохновителем (inviter)
        teamLeaderOfPlayer.put(inviter, inviter);
        teamLeaderOfPlayer.put(player.getUuid(), inviter);
        player.sendMessage(Text.literal("Вы в команде с " + getName(inviter, player.getServer())).formatted(Formatting.GREEN), false);
        ServerPlayerEntity inv = player.getServer().getPlayerManager().getPlayer(inviter);
        if (inv != null) inv.sendMessage(Text.literal(player.getName().getString() + " принял приглашение").formatted(Formatting.GREEN), false);
    }

    private static String getName(UUID id, MinecraftServer server) {
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
        return p != null ? p.getName().getString() : id.toString();
    }

    public static Map<UUID, java.util.List<UUID>> getTeamsForSeed(long seed) {
        Map<UUID, java.util.List<UUID>> out = new java.util.HashMap<>();
        for (var e : teamLeaderOfPlayer.entrySet()) {
            UUID player = e.getKey();
            UUID leader = e.getValue();
            if (getPlayerSeedChoice(player) != seed) continue;
            out.computeIfAbsent(leader, k -> new java.util.ArrayList<>()).add(player);
        }
        return out;
    }
    
    /**
     * Запускает гонку для всех готовых игроков
     */
    public static CompletableFuture<Void> startRaceForReadyPlayers(MinecraftServer server) {
        CompletableFuture<Void> cf = new CompletableFuture<>();
        server.execute(() -> {
            try {
                // Формируем команды по лидерам; если игрок без команды — сам себе лидер
                Map<UUID, java.util.List<UUID>> byLeader = new java.util.HashMap<>();
                for (UUID pid : playerSeedChoices.keySet()) {
                    UUID leader = getLeader(pid);
                    byLeader.computeIfAbsent(leader, k -> new java.util.ArrayList<>()).add(pid);
                }
                for (var e : byLeader.entrySet()) {
                    UUID leader = e.getKey();
                    Long seed = playerSeedChoices.get(leader);
                    if (seed == null || seed < 0) continue; // лидер ещё не выбрал сид
                    java.util.List<UUID> members = e.getValue();
                    
                    // СТРОГАЯ ПРОВЕРКА: Исключаем уже начавших игроков из обработки
                    java.util.List<UUID> newPlayers = new java.util.ArrayList<>();
                    java.util.List<UUID> existingPlayers = new java.util.ArrayList<>();
                    
                    for (UUID memberId : members) {
                        if (race.server.RaceServerInit.personalStarted.contains(memberId)) {
                            existingPlayers.add(memberId);
                            LOGGER.info("[Race] Player {} already started - EXCLUDING from processing", memberId);
                        } else {
                            newPlayers.add(memberId);
                            LOGGER.info("[Race] Player {} is new - will process", memberId);
                        }
                    }
                    
                    // Если новых игроков нет - ПОЛНОСТЬЮ ПРОПУСКАЕМ группу
                    if (newPlayers.isEmpty()) {
                        LOGGER.info("[Race] All players already started for seed {} - COMPLETELY SKIPPING group", seed);
                        continue;
                    }
                    
                    // Создаем миры ТОЛЬКО для действительно НОВЫХ игроков
                    LOGGER.info("[Race] Processing {} new players ({} existing players EXCLUDED)", 
                        newPlayers.size(), existingPlayers.size());
                    
                    for (UUID newPlayerId : newPlayers) {
                        // ДОПОЛНИТЕЛЬНАЯ ПРОВЕРКА: Убеждаемся что игрок действительно новый
                        if (!race.server.RaceServerInit.personalStarted.contains(newPlayerId)) {
                            startRaceForSolo(server, seed, newPlayerId);
                        } else {
                            LOGGER.warn("[Race] Player {} marked as new but already started - SKIPPING", newPlayerId);
                        }
                    }
                    
                    // Показываем варианты присоединения существующим игрокам
                    for (UUID existingId : existingPlayers) {
                        ServerPlayerEntity existing = server.getPlayerManager().getPlayer(existingId);
                        if (existing != null) {
                            existing.sendMessage(net.minecraft.text.Text.literal("К вашей гонке присоединились новые игроки!")
                                .formatted(net.minecraft.util.Formatting.GREEN), false);
                            showJoinOptionsToPlayer(server, existing, seed);
                        }
                    }
                    
                    race.server.RaceServerInit.startRace(seed, System.currentTimeMillis());
                }
                cf.complete(null);
            } catch (Throwable t) {
                cf.completeExceptionally(t);
            }
        });
        return cf;
    }
    
    /**
     * Запускает гонку для группы игроков
     */
    private static void startRaceForGroup(MinecraftServer server, long seed, java.util.List<UUID> playerIds) {
        // Устанавливаем глобальный сид
        ServerRaceConfig.GLOBAL_SEED = seed;
        LOGGER.info("[Race] startRaceForGroup: seed={}, players={}", seed, playerIds);
        
        // Назначаем лидера по правилу:
        // 1) Если есть OP (perm >=2) среди группы — он лидер (берём первого по алфавиту ника)
        // 2) Иначе — игрок, кто раньше всех нажал Ready (по метке времени)
        // 3) Фолбэк — первый по UUID
        if (!playerIds.isEmpty()) {
            UUID best = null;
            // искать op
            java.util.List<ServerPlayerEntity> candidates = new java.util.ArrayList<>();
            for (UUID pid : playerIds) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(pid);
                if (p != null && p.hasPermissionLevel(2)) candidates.add(p);
            }
            if (!candidates.isEmpty()) {
                candidates.sort(java.util.Comparator.comparing(a -> a.getName().getString(), String.CASE_INSENSITIVE_ORDER));
                best = candidates.get(0).getUuid();
            }
            if (best == null) {
                long bestTs = Long.MAX_VALUE;
                for (UUID pid : playerIds) {
                    long ts = readyTimestamps.getOrDefault(pid, Long.MAX_VALUE);
                    if (ts < bestTs) { bestTs = ts; best = pid; }
                }
            }
            if (best == null) best = playerIds.get(0);
            setSeedLeader(seed, best);
        }
        
        // Создаем изолированные миры для каждого игрока
        for (UUID playerId : playerIds) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player != null) {
                // Создаем/получаем персональный мир для игрока (серверный тред)
                var dst = race.server.world.EnhancedWorldManager.getOrCreateWorld(server, playerId, seed, net.minecraft.world.World.OVERWORLD);
                race.server.world.EnhancedWorldManager.teleportToWorld(player, dst);
                LOGGER.info("[Race] Player {} moved to personal world with seed {}", player.getGameProfile().getName(), seed);
                // Регистрируем игрока в системе прогресса/активности
                try { race.hub.ProgressSyncManager.registerPlayer(player, seed); } catch (Throwable ignored) {}
                // Замораживаем до личного старта таймера
                try {
                    race.server.RaceServerInit.class.getDeclaredField("frozenUntilStart");
                    race.server.RaceServerInit.class.getDeclaredField("freezePos");
                } catch (NoSuchFieldException e) {}
                race.server.RaceServerInit.freezePlayerUntilStart(player);
            }
        }
        
        // Уведомляем игроков о возможности старта по команде лидера
        for (UUID playerId : playerIds) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player != null) {
                UUID leader = getSeedLeader(seed);
                String who = (leader != null && server.getPlayerManager().getPlayer(leader) != null)
                        ? server.getPlayerManager().getPlayer(leader).getName().getString() : "лидер";
                player.sendMessage(Text.literal("Ожидание старта от: " + who + " (\u002Frace go)").formatted(Formatting.YELLOW), false);
            }
        }
    }

    
    // Старт для соло гонки: каждый игрок получает свой мир
    private static void startRaceForSolo(MinecraftServer server, long seed, UUID playerId) {
        try {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null) return;
            
            // СТРОГАЯ ПРОВЕРКА: НЕ создаем мир повторно для уже начавших игроков
            if (race.server.RaceServerInit.personalStarted.contains(playerId)) {
                LOGGER.warn("[Race] BLOCKED: Player {} already started race - not creating new world", 
                    player.getName().getString());
                
                // Показываем только варианты присоединения
                showJoinOptionsToPlayer(server, player, seed);
                return;
            }
            
            // ИСПРАВЛЕНИЕ: Проверяем, есть ли уже персональный мир для этого игрока
            boolean hasPersonalWorld = hasExistingPersonalWorld(server, playerId, seed);
            
            if (hasPersonalWorld) {
                // У игрока уже есть персональный мир - проверяем, нужно ли телепортировать
                String currentWorldKey = player.getServerWorld().getRegistryKey().getValue().toString();
                String expectedWorldKey = "fabric_race:player_" + playerId.toString().replace("-", "") + "_s" + seed;
                
                if (currentWorldKey.equals(expectedWorldKey)) {
                    // Игрок уже в правильном мире - НЕ телепортируем
                    LOGGER.info("[Race] Player {} already in correct personal world - no teleportation needed", 
                        player.getName().getString());
                    
                    player.sendMessage(net.minecraft.text.Text.literal("Вы уже в своем изолированном мире!")
                        .formatted(net.minecraft.util.Formatting.GREEN), false);
                } else {
                    // Игрок в неправильном мире - телепортируем в правильный
                    LOGGER.info("[Race] Player {} in wrong world, teleporting to personal world", 
                        player.getName().getString());
                    
                    ServerWorld personalWorld = race.server.world.CustomWorldManager.getOrCreatePersonalWorld(server, playerId, seed);
                    if (personalWorld != null) {
                        net.minecraft.util.math.BlockPos spawn = personalWorld.getSpawnPos();
                        player.teleport(personalWorld, spawn.getX() + 0.5, spawn.getY() + 1, spawn.getZ() + 0.5, 0, 0);
                        player.sendMessage(net.minecraft.text.Text.literal("Возвращены в свой изолированный мир!")
                            .formatted(net.minecraft.util.Formatting.GREEN), false);
                    }
                }
                return;
            }
            
            // КЛЮЧЕВОЕ ИЗМЕНЕНИЕ: Всегда создаем изолированный мир для нового игрока
            LOGGER.info("[Race] Creating isolated world for player {} with seed {}", 
                player.getName().getString(), seed);
            
            // Создаем ПЕРСОНАЛЬНЫЙ изолированный мир (уникальный для каждого игрока)
            ServerWorld personalWorld = race.server.world.CustomWorldManager.getOrCreatePersonalWorld(server, playerId, seed);
            
            System.out.println("✓ Personal isolated world created for " + player.getName().getString());
            System.out.println("✓ World dimension: " + personalWorld.getDimensionEntry().getKey());
            
            net.minecraft.util.math.BlockPos spawn = personalWorld.getSpawnPos();
            player.teleport(personalWorld, spawn.getX() + 0.5, spawn.getY() + 1, spawn.getZ() + 0.5, 0, 0);
            
            player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
            player.sendAbilitiesUpdate();
            
            race.hub.ProgressSyncManager.registerPlayer(player, seed);
            race.server.world.ReturnPointRegistry.saveCurrent(player);
            
            // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Сразу добавляем игрока в personalStarted
            race.server.RaceServerInit.personalStarted.add(playerId);
            LOGGER.info("[Race] Player {} added to personalStarted immediately", player.getName().getString());
            
            // Замораживаем до старта только если гонка еще не началась
            if (!race.server.RaceServerInit.personalStarted.contains(playerId)) {
                race.server.RaceServerInit.freezePlayerUntilStart(player);
            }
            
            // Отмечаем время создания мира
            worldCreationTimestamps.put(playerId, System.currentTimeMillis());
            
            // ИСПРАВЛЕНИЕ: Сохраняем оригинальный мир игрока
            String worldKey = personalWorld.getRegistryKey().getValue().toString();
            race.server.RaceServerInit.savePlayerOriginalWorld(playerId, worldKey);
            
            LOGGER.info("[Race] Solo race started for player {} in isolated world with seed {}", 
                player.getName().getString(), seed);
                
            // Показываем информацию о возможности присоединения к другим
            showJoinOptionsToPlayer(server, player, seed);
            
        } catch (Throwable t) {
            LOGGER.error("[Race] startRaceForSolo failed: {}", t.toString());
        }
    }
    
    // Проверка существования персонального мира
    private static boolean hasExistingPersonalWorld(MinecraftServer server, UUID playerId, long seed) {
        try {
            // Проверяем, существует ли персональный мир для данного игрока
            String worldName = "player_" + playerId.toString().replace("-", "") + "_s" + seed;
            net.minecraft.registry.RegistryKey<net.minecraft.world.World> key = 
                net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, 
                    net.minecraft.util.Identifier.of("fabric_race", worldName));
            
            return server.getWorld(key) != null;
        } catch (Throwable t) {
            return false;
        }
    }
    
    // Показать варианты присоединения к другим игрокам
    private static void showJoinOptionsToPlayer(MinecraftServer server, ServerPlayerEntity player, long seed) {
        try {
            // Найти других игроков с тем же сидом
            java.util.List<String> otherPlayers = new java.util.ArrayList<>();
            for (UUID otherId : playerSeedChoices.keySet()) {
                if (otherId.equals(player.getUuid())) continue;
                if (playerSeedChoices.get(otherId) == seed) {
                    ServerPlayerEntity other = server.getPlayerManager().getPlayer(otherId);
                    if (other != null && race.server.RaceServerInit.personalStarted.contains(otherId)) {
                        otherPlayers.add(other.getName().getString());
                    }
                }
            }
            
            if (!otherPlayers.isEmpty()) {
                player.sendMessage(net.minecraft.text.Text.literal("━━━ ДОСТУПНЫ ДЛЯ ПРИСОЕДИНЕНИЯ ━━━")
                    .formatted(net.minecraft.util.Formatting.GOLD), false);
                player.sendMessage(net.minecraft.text.Text.literal("Игроки с сидом " + seed + ":")
                    .formatted(net.minecraft.util.Formatting.YELLOW), false);
                
                for (String name : otherPlayers) {
                    player.sendMessage(net.minecraft.text.Text.literal("• " + name)
                        .formatted(net.minecraft.util.Formatting.WHITE), false);
                }
                
                player.sendMessage(net.minecraft.text.Text.literal("Используйте: /race join <ник>")
                    .formatted(net.minecraft.util.Formatting.AQUA), false);
            }
        } catch (Throwable t) {
            LOGGER.debug("[Race] showJoinOptionsToPlayer failed: {}", t.getMessage());
        }
    }
    
    // Проверка, находится ли игрок в race-мире
    public static boolean isPlayerInRaceWorld(ServerPlayerEntity player) {
        String worldKey = player.getServerWorld().getRegistryKey().getValue().toString();
        return worldKey.startsWith("fabric_race:");
    }
    
    // Проверка, был ли мир создан недавно
    public static boolean wasWorldRecentlyCreated(UUID playerId, long seed) {
        // Проверяем, был ли мир создан в последние 30 секунд
        Long lastCreation = worldCreationTimestamps.get(playerId);
        if (lastCreation == null) return false;
        
        return (System.currentTimeMillis() - lastCreation) < 30000; // 30 секунд
    }
    
    // Методы для работы с сервером
    public static void setServer(MinecraftServer server) {
        serverInstance = server;
    }
    
    public static MinecraftServer getServer() {
        return serverInstance;
    }
    
    /**
     * Очищает выбор игрока
     */
    public static void clearPlayerChoice(UUID playerId) {
        Long oldSeed = playerSeedChoices.remove(playerId);
        readyTimestamps.remove(playerId);
        lastProcessedTime.remove(playerId); // Очищаем время обработки
        
        // ИСПРАВЛЕНИЕ: Очищаем состояние гонки игрока
        race.server.RaceServerInit.personalStarted.remove(playerId);
        race.server.RaceServerInit.frozenUntilStart.remove(playerId);
        LOGGER.info("[Race] Cleared race state for player: {}", playerId);
        
        if (oldSeed != null) {
            int count = seedPlayerCount.getOrDefault(oldSeed, 0) - 1;
            if (count <= 0) {
                seedPlayerCount.remove(oldSeed);
            } else {
                seedPlayerCount.put(oldSeed, count);
            }
        }
    }
    
    /**
     * Проверяет, активен ли хаб
     */
    public static boolean isHubActive() {
        return hubActive;
    }
    
    /**
     * Получает хаб-мир
     */
    public static ServerWorld getHubWorld() {
        return hubWorld;
    }
    
    /**
     * Возвращает название хаба
     */
    public static String getHubName() {
        if (hubWorld == null) return "Неизвестный хаб";
        return hubWorld.getRegistryKey().getValue().toString();
    }
    
    /**
     * Возвращает позицию домика хаба
     */
    public static BlockPos getHubHousePosition() {
        return hubHouseBase != null ? hubHouseBase : (hubWorld != null ? hubWorld.getSpawnPos() : null);
    }
    
    /**
     * Возвращает информацию о хабе в виде строки
     */
    public static String getHubInfo() {
        if (!hubActive || hubWorld == null) {
            return "Хаб не активен";
        }
        
        BlockPos housePos = getHubHousePosition();
        if (housePos == null) {
            return String.format("Хаб: %s (позиция неизвестна)", getHubName());
        }
        
        return String.format("Хаб: %s, Домик: (%d, %d, %d)", 
            getHubName(), housePos.getX(), housePos.getY(), housePos.getZ());
    }
    
    /**
     * Сохраняет данные хаба в JSON файл
     */
    private static void saveHubData() {
        if (hubDataFile == null) return;
        
        try {
            com.google.gson.JsonObject root = new com.google.gson.JsonObject();
            
            // Сохраняем основную информацию
            root.addProperty("hubActive", hubActive);
            root.addProperty("hubHouseBuilt", hubHouseBuilt);
            
            // Сохраняем позицию домика
            if (hubHouseBase != null) {
                com.google.gson.JsonObject housePos = new com.google.gson.JsonObject();
                housePos.addProperty("x", hubHouseBase.getX());
                housePos.addProperty("y", hubHouseBase.getY());
                housePos.addProperty("z", hubHouseBase.getZ());
                root.add("hubHouseBase", housePos);
            }
            
            // Сохраняем информацию о мире
            if (hubWorld != null) {
                root.addProperty("hubWorldKey", hubWorld.getRegistryKey().getValue().toString());
            }
            
            // Записываем в файл
            java.nio.file.Files.createDirectories(hubDataFile.getParent());
            try (java.io.FileWriter writer = new java.io.FileWriter(hubDataFile.toFile())) {
                com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
                gson.toJson(root, writer);
            }
            
            LOGGER.info("[Race] Saved hub data to {}", hubDataFile);
        } catch (Exception e) {
            LOGGER.error("[Race] Failed to save hub data: {}", e.getMessage());
        }
    }
    
    /**
     * Загружает данные хаба из JSON файла
     */
    private static void loadHubData() {
        if (hubDataFile == null || !java.nio.file.Files.exists(hubDataFile)) {
            LOGGER.info("[Race] No hub data file found, starting fresh");
            return;
        }
        
        try {
            com.google.gson.JsonObject root;
            try (java.io.FileReader reader = new java.io.FileReader(hubDataFile.toFile())) {
                com.google.gson.Gson gson = new com.google.gson.Gson();
                root = gson.fromJson(reader, com.google.gson.JsonObject.class);
            }
            
            // Загружаем основную информацию
            if (root.has("hubActive")) {
                hubActive = root.get("hubActive").getAsBoolean();
            }
            if (root.has("hubHouseBuilt")) {
                hubHouseBuilt = root.get("hubHouseBuilt").getAsBoolean();
            }
            
            // Загружаем позицию домика
            if (root.has("hubHouseBase")) {
                com.google.gson.JsonObject housePos = root.getAsJsonObject("hubHouseBase");
                int x = housePos.get("x").getAsInt();
                int y = housePos.get("y").getAsInt();
                int z = housePos.get("z").getAsInt();
                hubHouseBase = new BlockPos(x, y, z);
                LOGGER.info("[Race] Loaded hub house position: ({}, {}, {})", x, y, z);
            }
            
            LOGGER.info("[Race] Loaded hub data from {}", hubDataFile);
        } catch (Exception e) {
            LOGGER.error("[Race] Failed to load hub data: {}", e.getMessage());
        }
    }
    
    /**
     * Принудительно сохраняет данные хаба
     */
    public static void forceSaveHubData() {
        saveHubData();
    }
    
}
