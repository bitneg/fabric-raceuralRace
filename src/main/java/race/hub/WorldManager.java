package race.hub;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.minecraft.util.math.BlockPos;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import race.server.world.EnhancedWorldManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Менеджер для создания изолированных миров игроков
 */
public class WorldManager {
    private static final Map<UUID, ServerWorld> playerWorlds = new HashMap<>();
    private static final Map<UUID, Long> playerSeeds = new HashMap<>();
    // ДОБАВЛЯЕМ: Кеширование слота игрока
    private static final Map<UUID, Integer> playerSlots = new HashMap<>();
    
    /**
     * Создает персональный мир для игрока
     */
    public static ServerWorld createPlayerWorld(MinecraftServer server, ServerPlayerEntity player, long seed) {
        UUID playerId = player.getUuid();
        
        // Получаем/создаем мир игрока через существующий менеджер
        ServerWorld playerWorld = EnhancedWorldManager.getOrCreateWorld(server, playerId, seed, World.OVERWORLD);
        
        // Сохраняем ссылки
        playerWorlds.put(playerId, playerWorld);
        playerSeeds.put(playerId, seed);
        
        // Телепортируем игрока в его мир
        teleportPlayerToWorld(player, playerWorld);
        
        return playerWorld;
    }
    
    /**
     * Телепортирует игрока в его персональный мир
     */
    public static void teleportPlayerToWorld(ServerPlayerEntity player, ServerWorld world) {
        // Находим безопасное место для спавна
        BlockPos spawnPos = findSafeSpawnPosition(world);
        
        // Телепортируем игрока
        player.teleport(world, spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0, 0);
        
        // Уведомляем игрока
        player.sendMessage(Text.literal("Добро пожаловать в ваш персональный мир!").formatted(Formatting.GREEN), false);
        player.sendMessage(Text.literal("Сид: " + playerSeeds.get(player.getUuid())).formatted(Formatting.AQUA), false);
    }
    
    /**
     * Находит безопасное место для спавна
     */
    private static BlockPos findSafeSpawnPosition(ServerWorld world) {
        // Используем стандартную логику спавна Minecraft
        BlockPos spawnPos = world.getSpawnPos();
        
        // Ищем безопасное место в радиусе 10 блоков от спавна
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                BlockPos pos = spawnPos.add(x, 0, z);
                if (isSafeSpawnPosition(world, pos)) {
                    if (world.getDimensionEntry().matchesKey(net.minecraft.world.dimension.DimensionTypes.THE_NETHER)) {
                        int y = Math.max(32, Math.min(120, pos.getY()));
                        pos = new BlockPos(pos.getX(), y, pos.getZ());
                    }
                    return pos;
                }
            }
        }
        
        // Если не нашли безопасное место, возвращаем спавн
        return spawnPos;
    }
    
    /**
     * Проверяет, безопасно ли место для спавна
     */
    private static boolean isSafeSpawnPosition(ServerWorld world, BlockPos pos) {
        // Проверяем, что под ногами есть твердый блок
        if (world.getBlockState(pos.down()).isAir()) {
            return false;
        }
        
        // Проверяем, что над головой есть место
        if (!world.getBlockState(pos).isAir() || !world.getBlockState(pos.up()).isAir()) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Получает мир игрока
     */
    public static ServerWorld getPlayerWorld(UUID playerId) {
        return playerWorlds.get(playerId);
    }
    
    /**
     * Получает сид игрока
     */
    public static long getPlayerSeed(UUID playerId) {
        return playerSeeds.getOrDefault(playerId, -1L);
    }
    
    /**
     * Удаляет мир игрока
     */
    public static void removePlayerWorld(UUID playerId) {
        playerWorlds.remove(playerId);
        playerSeeds.remove(playerId);
        playerSlots.remove(playerId); // ДОБАВЛЯЕМ: Очистка слота
    }
    
    /**
     * Получает кешированный слот игрока
     */
    public static int getPlayerSlot(UUID playerId) {
        return playerSlots.getOrDefault(playerId, 0);
    }
    
    /**
     * Принудительно устанавливает слот для игрока (для отладки)
     */
    public static void setPlayerSlot(UUID playerId, int slot) {
        playerSlots.put(playerId, slot);
        System.out.println("✓ Manually set slot " + slot + " for player: " + playerId);
    }
    
    /**
     * Получает информацию о всех слотах игроков (для отладки)
     */
    public static void debugPlayerSlots() {
        System.out.println("=== PLAYER SLOTS DEBUG ===");
        for (Map.Entry<UUID, Integer> entry : playerSlots.entrySet()) {
            System.out.println("Player " + entry.getKey() + " -> Slot " + entry.getValue());
        }
        System.out.println("========================");
    }
    
    /**
     * Создает мир Нижнего мира для игрока
     */
    public static ServerWorld createPlayerNetherWorld(MinecraftServer server, ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        long seed = getPlayerSeed(playerId);
        
        if (seed < 0) {
            return null;
        }
        
        // ИСПРАВЛЕНИЕ: Сначала пытаемся получить кешированный слот
        int playerSlot = getPlayerSlot(playerId);
        System.out.println("🔍 Cached slot for player " + player.getName().getString() + ": " + playerSlot);
        
        // Если кешированного слота нет, пытаемся извлечь из текущего мира
        if (playerSlot == 0) {
            String currentWorldKey = player.getServerWorld().getRegistryKey().getValue().toString();
            playerSlot = extractSlotFromWorldKey(currentWorldKey);
            System.out.println("🔍 Extracted slot from current world: " + playerSlot);
            
            // Кешируем найденный слот
            if (playerSlot > 0) {
                setPlayerSlot(playerId, playerSlot);
                System.out.println("✓ Cached extracted slot " + playerSlot + " for player " + player.getName().getString());
            }
        }
        
        ServerWorld netherWorld;
        if (playerSlot > 0) {
            netherWorld = EnhancedWorldManager.getOrCreateWorldForGroup(server, playerSlot, seed, World.NETHER);
            System.out.println("✓ Using slot " + playerSlot + " for Nether world");
        } else {
            netherWorld = EnhancedWorldManager.getOrCreateWorld(server, playerId, seed, World.NETHER);
            System.out.println("✓ Using personal world for Nether world");
        }
        
        return netherWorld;
    }
    
    /**
     * Создает мир Энда для игрока
     */
    public static ServerWorld createPlayerEndWorld(MinecraftServer server, ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        long seed = getPlayerSeed(playerId);
        
        if (seed < 0) {
            return null;
        }
        
        // ИСПРАВЛЕНИЕ: Сначала пытаемся получить кешированный слот
        int playerSlot = getPlayerSlot(playerId);
        System.out.println("🔍 Cached slot for player " + player.getName().getString() + ": " + playerSlot);
        
        // Если кешированного слота нет, пытаемся извлечь из текущего мира
        if (playerSlot == 0) {
            String currentWorldKey = player.getServerWorld().getRegistryKey().getValue().toString();
            playerSlot = extractSlotFromWorldKey(currentWorldKey);
            System.out.println("🔍 Extracted slot from current world: " + playerSlot);
            
            // Кешируем найденный слот
            if (playerSlot > 0) {
                setPlayerSlot(playerId, playerSlot);
                System.out.println("✓ Cached extracted slot " + playerSlot + " for player " + player.getName().getString());
            }
        }
        
        ServerWorld endWorld;
        if (playerSlot > 0) {
            // Игрок в слоте - используем кешированный слот для End
            endWorld = EnhancedWorldManager.getOrCreateWorldForGroup(server, playerSlot, seed, World.END);
            System.out.println("✓ Using slot " + playerSlot + " for End world");
        } else {
            // Игрок в персональном мире - создаем персональный End
            endWorld = EnhancedWorldManager.getOrCreateWorld(server, playerId, seed, World.END);
            System.out.println("✓ Using personal world for End world");
        }
        
        return endWorld;
    }
    
    /**
     * Извлекает номер слота из ключа мира
     */
    private static int extractSlotFromWorldKey(String worldKey) {
        try {
            // Ищем паттерн "slot" + число
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("slot(\\d+)");
            java.util.regex.Matcher matcher = pattern.matcher(worldKey);
            if (matcher.find()) {
                int slot = Integer.parseInt(matcher.group(1));
                
                // ИСПРАВЛЕНИЕ: Валидация разумных значений слота
                if (slot >= 1 && slot <= 100) { // Предполагаем максимум 100 слотов
                    System.out.println("✓ Extracted valid slot " + slot + " from world key: " + worldKey);
                    return slot;
                } else {
                    System.err.println("⚠ Invalid slot number " + slot + " in world key: " + worldKey);
                    return 0;
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to extract slot from world key: " + worldKey + " - " + e.getMessage());
        }
        System.out.println("⚠ No valid slot found in world key: " + worldKey + " (using personal world)");
        return 0; // 0 означает персональный мир
    }
    
    /**
     * Телепортирует игрока в Нижний мир
     */
    public static void teleportToNether(ServerPlayerEntity player) {
        ServerWorld netherWorld = createPlayerNetherWorld(player.getServer(), player);
        if (netherWorld != null) {
            // Находим портал в Нижнем мире
            BlockPos portalPos = findNetherPortal(netherWorld);
            player.teleport(netherWorld, portalPos.getX() + 0.5, portalPos.getY(), portalPos.getZ() + 0.5, 0, 0);
        }
    }
    
    /**
     * Телепортирует игрока в Энд
     */
    public static void teleportToEnd(ServerPlayerEntity player) {
        ServerWorld endWorld = createPlayerEndWorld(player.getServer(), player);
        if (endWorld != null) {
            // ИСПРАВЛЕНИЕ: Очищаем неправильных мобов ПЕРЕД спавном дракона
            clearInvalidEndMobs(endWorld);
            
            // Телепортируем на остров End (вне главного острова с драконом)
            BlockPos endPos = new BlockPos(100, 50, 0);
            
            // Создаем платформу для игрока на острове End
            createEndIslandPlatform(endWorld, endPos);
            
            // Спавним дракона End
            spawnEndDragon(endWorld);
            
            player.teleport(endWorld, endPos.getX() + 0.5, endPos.getY(), endPos.getZ() + 0.5, 0, 0);
            
            player.sendMessage(
                net.minecraft.text.Text.literal("End мир очищен от Overworld мобов! Эндермены и другие End существа разрешены.")
                    .formatted(net.minecraft.util.Formatting.GREEN), false);
        }
    }
    
    /**
     * Создает платформу для игрока на острове End (вне главного острова)
     */
    private static void createEndIslandPlatform(ServerWorld world, BlockPos playerPos) {
        try {
            // Создаем платформу 5x5 из энд-камня для игрока
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos pos = playerPos.add(x, -1, z);
                    world.setBlockState(pos, net.minecraft.block.Blocks.END_STONE.getDefaultState());
                }
            }
            
            // Создаем безопасную зону для игрока (3x3)
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos pos = playerPos.add(x, 0, z);
                    if (world.getBlockState(pos).getBlock() != net.minecraft.block.Blocks.AIR) {
                        world.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState());
                    }
                }
            }
            
            System.out.println("✓ Created End island platform for player at " + playerPos);
        } catch (Throwable t) {
            System.err.println("Failed to create End island platform: " + t.getMessage());
        }
    }
    
    /**
     * Создает чистую область вокруг End портала и платформу для игрока
     */
    private static void createEndPortalArea(ServerWorld world, BlockPos portalPos) {
        try {
            // Очищаем большую область вокруг портала (15x15x15)
            for (int x = -7; x <= 7; x++) {
                for (int y = -7; y <= 7; y++) {
                    for (int z = -7; z <= 7; z++) {
                        BlockPos pos = portalPos.add(x, y, z);
                        if (world.getBlockState(pos).getBlock() != net.minecraft.block.Blocks.AIR) {
                            world.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState());
                        }
                    }
                }
            }
            
            // Создаем платформу для игрока на уровне 62 (как в ванилле)
            BlockPos playerPlatform = new BlockPos(0, 62, 0);
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos pos = playerPlatform.add(x, -1, z);
                    world.setBlockState(pos, net.minecraft.block.Blocks.END_STONE.getDefaultState());
                }
            }
            
            // Создаем безопасную зону для игрока (3x3)
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos pos = playerPlatform.add(x, 0, z);
                    if (world.getBlockState(pos).getBlock() != net.minecraft.block.Blocks.AIR) {
                        world.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState());
                    }
                }
            }
            
            System.out.println("✓ Created clean End portal area at " + portalPos + " with player platform");
        } catch (Throwable t) {
            System.err.println("Failed to create End portal area: " + t.getMessage());
        }
    }
    
    // Создание платформы из энд-камня в End мире
    private static void createEndSpawnPlatform(ServerWorld world, BlockPos center) {
        try {
            // Создаем большую платформу 15x15 из энд-камня для безопасности
            for (int x = -7; x <= 7; x++) {
                for (int z = -7; z <= 7; z++) {
                    BlockPos pos = center.add(x, -1, z);
                    world.setBlockState(pos, net.minecraft.block.Blocks.END_STONE.getDefaultState());
                }
            }
            
            // Создаем центральную платформу для игрока (3x3)
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos pos = center.add(x, 0, z);
                    world.setBlockState(pos, net.minecraft.block.Blocks.END_STONE.getDefaultState());
                }
            }
            
            // Создаем безопасную зону вокруг центра (5x5)
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos pos = center.add(x, 0, z);
                    if (world.getBlockState(pos).isAir()) {
                        world.setBlockState(pos, net.minecraft.block.Blocks.END_STONE.getDefaultState());
                    }
                }
            }
            
            System.out.println("✓ End spawn platform created at " + center + " (15x15 base, 5x5 safe zone)");
        } catch (Throwable t) {
            System.err.println("Failed to create End platform: " + t.getMessage());
        }
    }
    
    // Спавн дракона End - ИСПРАВЛЕННАЯ ВЕРСИЯ ДЛЯ КАСТОМНЫХ МИРОВ
    private static void spawnEndDragon(ServerWorld world) {
        // Проверяем, что это End мир
        if (!isEndDimension(world)) {
            System.err.println("Trying to spawn dragon in non-End world: " + 
                              world.getRegistryKey().getValue());
            return;
        }
        
        try {
            // Проверяем, есть ли уже дракон
            var existingDragons = world.getEntitiesByType(
                net.minecraft.entity.EntityType.ENDER_DRAGON, 
                net.minecraft.util.math.Box.of(new net.minecraft.util.math.Vec3d(0, 64, 0), 400, 400, 400),
                entity -> true
            );
            
            if (existingDragons.isEmpty()) {
                BlockPos fightOrigin = new BlockPos(0, 64, 0);
                
                // КРИТИЧЕСКОЕ: EnderDragonFight должен существовать
                var dragonFight = world.getEnderDragonFight();
                if (dragonFight == null) {
                    System.err.println("CRITICAL: No EnderDragonFight in custom End world!");
                    return;
                }
                
                var dragon = (net.minecraft.entity.boss.dragon.EnderDragonEntity) net.minecraft.entity.EntityType.ENDER_DRAGON.create(world);
                if (dragon != null) {
                    dragon.setFight(dragonFight);
                    dragon.setFightOrigin(fightOrigin);
                    dragon.getPhaseManager().setPhase(net.minecraft.entity.boss.dragon.phase.PhaseType.HOLDING_PATTERN);
                    // ИСПРАВЛЕНИЕ: Спавним дракона на правильной высоте (30 блоков) вместо 64
                    dragon.refreshPositionAndAngles(0.0, 30.0, 0.0, 0.0F, 0.0F);
                    world.spawnEntity(dragon);
                    
                    registerDragonInFight(dragonFight, dragon);
                    System.out.println("✓ Dragon spawned in custom End world: " + 
                                     world.getRegistryKey().getValue());
                }
            } else {
                System.out.println("✓ End dragon already exists, skipping spawn");
            }
        } catch (Throwable t) {
            System.err.println("Failed to spawn End dragon: " + t.getMessage());
            t.printStackTrace();
        }
    }
    
    // УДАЛЕНО: createEndPortal и createEndPortalPlatform - портал создается автоматически ванильной игрой
    
    // УДАЛЕНО: createEndCrystals - кристаллы создаются автоматически ванильной игрой
    
    /**
     * Находит портал в Нижнем мире
     */
    private static BlockPos findNetherPortal(ServerWorld world) {
        // Ищем портал в радиусе 100 блоков от спавна
        BlockPos spawnPos = world.getSpawnPos();
        
        for (int x = -100; x <= 100; x += 10) {
            for (int z = -100; z <= 100; z += 10) {
                BlockPos pos = spawnPos.add(x, 0, z);
                if (world.getBlockState(pos).getBlock() == net.minecraft.block.Blocks.NETHER_PORTAL) {
                    return pos;
                }
            }
        }
        
        // Если не нашли портал, возвращаем спавн
        return spawnPos;
    }
    
    /**
     * Получает всех игроков с их мирами
     */
    public static Map<UUID, ServerWorld> getAllPlayerWorlds() {
        return new HashMap<>(playerWorlds);
    }
    
    /**
     * Проверяет, принадлежит ли End мир конкретному игроку
     */
    public static boolean isPlayerEndWorld(ServerWorld world, UUID playerId) {
        if (!isEndDimension(world)) {
            return false;
        }
        
        String worldName = world.getRegistryKey().getValue().getPath();
        String playerIdStr = playerId.toString().replace("-", "");
        
        return worldName.contains("end") && worldName.contains(playerIdStr.substring(0, 8));
    }
    
    /**
     * Очищает неправильных мобов из End мира
     */
    public static void clearInvalidEndMobs(ServerWorld endWorld) {
        if (!isEndDimension(endWorld)) {
            return;
        }
        
        try {
            // Список мобов, которых НЕ должно быть в End (только Overworld мобы)
            var invalidMobTypes = java.util.List.of(
                net.minecraft.entity.EntityType.COW, net.minecraft.entity.EntityType.PIG, 
                net.minecraft.entity.EntityType.SHEEP, net.minecraft.entity.EntityType.CHICKEN,
                net.minecraft.entity.EntityType.HORSE, net.minecraft.entity.EntityType.DONKEY, 
                net.minecraft.entity.EntityType.MULE, net.minecraft.entity.EntityType.LLAMA,
                net.minecraft.entity.EntityType.VILLAGER, net.minecraft.entity.EntityType.ZOMBIE, 
                net.minecraft.entity.EntityType.SKELETON, net.minecraft.entity.EntityType.CREEPER, 
                net.minecraft.entity.EntityType.SPIDER, net.minecraft.entity.EntityType.WITCH,
                net.minecraft.entity.EntityType.SLIME, net.minecraft.entity.EntityType.MAGMA_CUBE,
                net.minecraft.entity.EntityType.BLAZE, net.minecraft.entity.EntityType.GHAST,
                net.minecraft.entity.EntityType.PIGLIN, net.minecraft.entity.EntityType.HOGLIN
            );
            
            int totalRemoved = 0;
            
            for (var mobType : invalidMobTypes) {
                var entities = endWorld.getEntitiesByType(
                    mobType,
                    net.minecraft.util.math.Box.of(
                        new net.minecraft.util.math.Vec3d(0, 64, 0), 
                        500, 200, 500
                    ),
                    entity -> true
                );
                
                for (var entity : entities) {
                    entity.discard();
                    totalRemoved++;
                }
            }
            
            System.out.println("✓ Removed " + totalRemoved + " Overworld mobs from End world (Endermen and End creatures preserved): " + 
                              endWorld.getRegistryKey().getValue());
                              
        } catch (Exception e) {
            System.err.println("Failed to clear invalid End mobs: " + e.getMessage());
        }
    }
    
    /**
     * Получает персональный Overworld игрока по его End миру
     */
    public static ServerWorld getPlayerOverworldFromEndWorld(ServerWorld endWorld) {
        String endWorldName = endWorld.getRegistryKey().getValue().getPath();
        
        // Извлекаем ID игрока из имени End мира
        // Предполагаем формат: "race_end_<uuid>" или "fabric_race:player_<uuid>_end"
        if (endWorldName.contains("end")) {
            // Ищем UUID в названии мира
            for (Map.Entry<UUID, ServerWorld> entry : playerWorlds.entrySet()) {
                String playerIdStr = entry.getKey().toString().replace("-", "");
                if (endWorldName.contains(playerIdStr.substring(0, 8))) {
                    return entry.getValue(); // Возвращаем Overworld игрока
                }
            }
        }
        
        return null;
    }
    
    /**
     * Устанавливает EnderDragonFight в мир через рефлексию
     */
    private static void setEnderDragonFightInWorld(ServerWorld world, net.minecraft.entity.boss.dragon.EnderDragonFight fight) {
        try {
            var field = ServerWorld.class.getDeclaredField("enderDragonFight");
            field.setAccessible(true);
            field.set(world, fight);
            System.out.println("✓ Set EnderDragonFight in custom world: " + 
                             world.getRegistryKey().getValue());
        } catch (Exception e) {
            System.err.println("Failed to set EnderDragonFight: " + e.getMessage());
        }
    }
    
    /**
     * Регистрирует UUID дракона в EnderDragonFight через рефлексию
     */
    private static void registerDragonInFight(net.minecraft.entity.boss.dragon.EnderDragonFight fight, net.minecraft.entity.boss.dragon.EnderDragonEntity dragon) {
        try {
            var field = net.minecraft.entity.boss.dragon.EnderDragonFight.class.getDeclaredField("dragonUuid");
            field.setAccessible(true);
            field.set(fight, dragon.getUuid());
            System.out.println("✓ Registered dragon UUID for Boss Bar");
        } catch (Exception e) {
            System.err.println("Failed to register dragon UUID: " + e.getMessage());
        }
    }
    
    /**
     * Проверяет, является ли мир End измерением (включая кастомные миры)
     */
    public static boolean isEndDimension(ServerWorld world) {
        try {
            // ИСПРАВЛЕНИЕ: Используем matchesKey() - самый надежный способ
            return world.getDimensionEntry().matchesKey(net.minecraft.world.dimension.DimensionTypes.THE_END);
        } catch (Exception e) {
            try {
                // Fallback: через сравнение типов
                var endDimensionType = world.getRegistryManager()
                    .get(net.minecraft.registry.RegistryKeys.DIMENSION_TYPE)
                    .get(net.minecraft.world.dimension.DimensionTypes.THE_END);
                return world.getDimensionEntry().value().equals(endDimensionType);
            } catch (Exception e2) {
                // Fallback для кастомных миров - по имени мира
                String worldName = world.getRegistryKey().getValue().getPath();
                return worldName.contains("end");
            }
        }
    }
    
    /**
     * Проверяет, является ли мир Nether измерением (включая кастомные миры)
     */
    public static boolean isNetherDimension(ServerWorld world) {
        try {
            // ИСПРАВЛЕНИЕ: Используем matchesKey() - самый надежный способ
            return world.getDimensionEntry().matchesKey(net.minecraft.world.dimension.DimensionTypes.THE_NETHER);
        } catch (Exception e) {
            try {
                // Fallback: через сравнение типов
                var netherDimensionType = world.getRegistryManager()
                    .get(net.minecraft.registry.RegistryKeys.DIMENSION_TYPE)
                    .get(net.minecraft.world.dimension.DimensionTypes.THE_NETHER);
                return world.getDimensionEntry().value().equals(netherDimensionType);
            } catch (Exception e2) {
                // Fallback для кастомных миров - по имени мира
                String worldName = world.getRegistryKey().getValue().getPath();
                return worldName.contains("nether");
            }
        }
    }
    

}
