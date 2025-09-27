package race.server.death;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionTypes;
import net.minecraft.particle.DustParticleEffect;
import org.joml.Vector3f;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.SignBlock;
import net.minecraft.block.Block;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.text.Text;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.entity.EquipmentSlot;

/**
 * Менеджер «призраков смерти»: сохраняет события и спавнит маркеры в персональных мирах.
 */
public final class DeathEchoManager {
    private DeathEchoManager() {}
    // Троттлинг спавна эффектов: ключ (world|pos|title|player) -> последний показ (ms)
    private static final java.util.Map<String, Long> LAST_SHOW = new java.util.concurrent.ConcurrentHashMap<>();
    // Уже созданные надгробия: ключ (world|signPos) -> timestamp
    private static final java.util.Set<String> PLACED_GRAVES = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    // Дедупликация по факту смерти: ключ (world|deathPos|player) — чтобы не ставить много табличек рядом
    private static final java.util.Set<String> PLACED_ECHOS = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    // Буфер последних позиций игроков для «призрачного пробега» (около 30 сек)
    private static final Map<UUID, Deque<TrailPoint>> TRAILS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int TRAIL_MAX_POINTS = 180; // ~30с при записи каждые ~3-4 тика
    private static final int TRAIL_SAMPLE_TICKS = 3; // писать раз в 3 тика
    private static int tickCounter = 0;
    private static final Map<UUID, Integer> GHOST_TTL = new java.util.concurrent.ConcurrentHashMap<>();
    // Периодическое появление фигур после смерти (8с)
    private static final java.util.Map<UUID, Long> ACTIVE_GHOSTS = new java.util.concurrent.ConcurrentHashMap<>();

    private record TrailPoint(double x, double y, double z, long ms) {}
    
    
    
    /**
     * Получает ключ измерения из ключа мира
     */
    private static String getDimensionKey(net.minecraft.registry.RegistryKey<net.minecraft.world.World> worldKey) {
        try {
            String path = worldKey.getValue().getPath();
            
            // Формат: "player_<uuid>_<seed>_<dimension>"
            // Извлекаем dimension из конца пути
            String[] parts = path.split("_");
            String dimension = parts.length > 0 ? parts[parts.length - 1] : "overworld";
            return dimension;
        } catch (Throwable e) {
            return "overworld";
        }
    }

    public static void onPlayerDeath(ServerPlayerEntity player, net.minecraft.entity.damage.DamageSource source) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        // Не полагаемся на HubManager: определяем сид по ключу мира игрока
        long seed = tryParseSeed(player.getServerWorld().getRegistryKey());
        if (seed < 0) return; // не персональный мир fabric_race
        
        // Сохраняем позицию возврата при смерти в персональном мире
        String worldNamespace = player.getServerWorld().getRegistryKey().getValue().getNamespace();
        if ("fabric_race".equals(worldNamespace)) {
            race.server.world.ReturnPointRegistry.saveCurrent(player);
        }
        
        String dim = getDimKey(player.getServerWorld());
        net.minecraft.util.math.BlockPos bp = player.getBlockPos();
        String msg = source.getDeathMessage(player).getString();
        DeathEchoState state = DeathEchoState.get(server);
        state.addEcho(seed, dim, bp, msg, player.getGameProfile().getName());
        state.markDirty();
        // ИСПРАВЛЕНИЕ: Спавним маркер только в ДРУГИХ мирах с тем же сидом и типом измерения
        ServerWorld deathWorld = player.getServerWorld();
        for (ServerWorld w : server.getWorlds()) {
            if (!isPersonalWorld(w)) continue;
            if (w == deathWorld) continue; // НЕ спавним в том же мире, где произошла смерть
            long s = tryParseSeed(w.getRegistryKey());
            if (s != seed) continue;
            if (!getDimKey(w).equals(dim)) continue;
            if (!race.server.RaceServerInit.isDisplayParallelPlayers()) continue;
            spawnGhost(w, bp, msg, player.getGameProfile().getName());
            spawnTrail(w, player.getUuid(), normalizeCause(msg, player.getGameProfile().getName()));
            // spawnGhostFigures(w, player.getUuid(), player.getGameProfile().getName()); // ОТКЛЮЧЕНО: убираем арморные стойки
        }
        ACTIVE_GHOSTS.put(player.getUuid(), System.currentTimeMillis() + 8000L);
    }

    // Вызывается каждый серверный тик для записи последних позиций
    public static void recordTick(ServerPlayerEntity p) {
        String ns = p.getServerWorld().getRegistryKey().getValue().getNamespace();
        if (!"fabric_race".equals(ns)) return;
        if ((++tickCounter % TRAIL_SAMPLE_TICKS) != 0) return;
        Deque<TrailPoint> dq = TRAILS.computeIfAbsent(p.getUuid(), id -> new ArrayDeque<>(TRAIL_MAX_POINTS + 8));
        if (dq.size() >= TRAIL_MAX_POINTS) dq.removeFirst();
        dq.addLast(new TrailPoint(p.getX(), p.getY(), p.getZ(), System.currentTimeMillis()));
    }

    // Периодический тик: обновляем призрачные фигуры
    public static void tickGhosts(MinecraftServer server) {
        long now = System.currentTimeMillis();
        ACTIVE_GHOSTS.entrySet().removeIf(e -> now > e.getValue());
        if (ACTIVE_GHOSTS.isEmpty()) return;
        for (var entry : ACTIVE_GHOSTS.entrySet()) {
            UUID id = entry.getKey();
            for (ServerWorld w : server.getWorlds()) {
                if (!isPersonalWorld(w)) continue;
                // spawnGhostFigures(w, id, ""); // ОТКЛЮЧЕНО: убираем арморные стойки
            }
        }
    }

    public static void populateWorld(ServerWorld world) {
        if (!isPersonalWorld(world)) return;
        long seed = tryParseSeed(world.getRegistryKey());
        if (seed < 0) return;
        String dim = getDimKey(world);
        DeathEchoState st = DeathEchoState.get(world.getServer());
        long now = System.currentTimeMillis();
        for (DeathEchoState.Echo e : st.getEchoes(seed, dim)) {
            String key = world.getRegistryKey().getValue() + "|" + e.pos.getX()+","+e.pos.getY()+","+e.pos.getZ() + "|" + e.title + "|" + e.playerName;
            long last = LAST_SHOW.getOrDefault(key, 0L);
            if (now - last >= 7000L) { // не чаще раза в 7 сек
                spawnGhost(world, e.pos, e.title, e.playerName);
                LAST_SHOW.put(key, now);
            }
        }
    }

    // Спавнит несколько полупризрачных фигур вдоль пути
    private static void spawnGhostFigures(ServerWorld world, UUID playerId, String playerName) {
        Deque<TrailPoint> dq = TRAILS.get(playerId);
        if (dq == null || dq.isEmpty()) return;
        int size = dq.size();
        int count = Math.min(8, Math.max(3, size / 20));
        int step = Math.max(1, size / count);
        int i = 0, placed = 0;
        for (TrailPoint tp : dq) {
            if ((i++ % step) != 0) continue;
            if (placed++ >= count) break;
            ArmorStandEntity as = new ArmorStandEntity(world, tp.x, tp.y, tp.z);
            try { as.setNoGravity(true); } catch (Throwable ignored) {}
            try { as.setInvulnerable(true); } catch (Throwable ignored) {}
            try { as.setGlowing(true); } catch (Throwable ignored) {}
            // Голова-призрак (без скина, чтобы не трогать NBT/профили)
            try { as.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.SKELETON_SKULL)); } catch (Throwable ignored) {}
            // Белая кожаная броня для “силуэта”
            try { as.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.CHAINMAIL_CHESTPLATE)); } catch (Throwable ignored) {}
            try { as.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.CHAINMAIL_LEGGINGS)); } catch (Throwable ignored) {}
            try { as.equipStack(EquipmentSlot.FEET, new ItemStack(Items.CHAINMAIL_BOOTS)); } catch (Throwable ignored) {}
            // Имя бледным текстом (не показываем постоянно)
            try { as.setCustomName(Text.literal(playerName)); as.setCustomNameVisible(false); } catch (Throwable ignored) {}
            as.addCommandTag("race_ghost_fig");
            world.spawnEntity(as);
            GHOST_TTL.put(as.getUuid(), 80); // 4 секунды при 20 т/с
        }
    }

    private static void spawnGhost(ServerWorld world, BlockPos pos, String title, String playerName) {
        // Адаптивное качество призраков на основе производительности
        int particleCount = race.server.RaceServerInit.getGhostParticleCount(180);
        if (particleCount <= 0) return; // Отключено при низкой производительности
        
        // Уменьшаем количество частиц для локального акцента (12-24 вместо 180)
        int reducedCount = Math.min(24, Math.max(12, particleCount / 8));
        
        // Лёгкий эффект «пятна крови» без сущностей: заметная вспышка частиц
        DustParticleEffect red = new DustParticleEffect(new Vector3f(0.85f, 0.05f, 0.05f), 1.6f);
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.15; // чуть выше, чтобы не утонуло в блоке
        double z = pos.getZ() + 0.5;
        world.spawnParticles(red, x, y, z, reducedCount, 0.6, 0.05, 0.6, 0.015);
        
        // Отправляем дальнобойный маркер всем видящим игрокам

        
        // Рядом создаём простое «надгробие»: камень + стоящая табличка с именем
        try {
            String echoKey = world.getRegistryKey().getValue() + "|" + pos.getX()+","+pos.getY()+","+pos.getZ() + "|" + playerName;
            if (!PLACED_ECHOS.add(echoKey)) return; // уже ставили табличку к этой смерти в этом мире
            placeGravestone(world, pos, playerName, title);
        } catch (Throwable ignored) {}
    }

    // Спавнит «призрачный пробег» — линию частиц по последнему пути игрока
    private static void spawnTrail(ServerWorld world, UUID playerId, String cause) {
        Deque<TrailPoint> dq = TRAILS.get(playerId);
        if (dq == null || dq.isEmpty()) return;
        
        // Адаптивное качество призрачных следов
        int quality = race.server.RaceServerInit.getAdaptiveGhostQuality();
        if (quality == 0) return; // Отключено при низкой производительности
        
        // Ограничим число точек в зависимости от качества
        int size = dq.size();
        int maxPoints = switch (quality) {
            case 1 -> 30;  // Низкое качество: 30 точек
            case 2 -> 60;  // Среднее качество: 60 точек
            case 3 -> 120; // Высокое качество: 120 точек
            default -> 60;
        };
        
        int step = Math.max(1, size / maxPoints);
        int i = 0;
        java.util.ArrayList<race.net.GhostTrailPayload.Point> pts = new java.util.ArrayList<>();
        for (TrailPoint tp : dq) { 
            if ((i++ % step) == 0) pts.add(new race.net.GhostTrailPayload.Point(tp.x, tp.y, tp.z)); 
        }
        
        // Рассылаем пейлоад всем игрокам в том же измерении в персональных мирах
        try {
            var payload = new race.net.GhostTrailPayload(playerId.toString(), cause, pts);
            var server = world.getServer();
            var worldDim = getDimensionKey(world.getRegistryKey());
            
            for (var p : server.getPlayerManager().getPlayerList()) {
                var playerWorld = p.getServerWorld();
                if (!isPersonalWorld(playerWorld)) continue;
                
                var playerDim = getDimensionKey(playerWorld.getRegistryKey());
                
                // Отправляем всем игрокам в том же измерении (независимо от seed/инстанса мира)
                if (worldDim.equals(playerDim)) {
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, payload);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void placeGravestone(ServerWorld world, BlockPos deathPos, String playerName, String title) {
        // Ищем ближайшую безопасную точку с твёрдым блоком снизу и 2 блоками воздуха
        BlockPos base = findSafeBase(world, deathPos);
        // Если не нашли — используем саму точку смерти (принудительно подготовим место)
        if (base == null) base = deathPos;
        BlockPos signPos = base.up();
        String key = world.getRegistryKey().getValue() + "|" + signPos.getX()+","+signPos.getY()+","+signPos.getZ();
        if (PLACED_GRAVES.contains(key)) return; // уже ставили
        // Если уже стоит табличка с нашим заголовком — считаем установленной
        if (world.getBlockEntity(signPos) instanceof SignBlockEntity existing) {
            try {
                if (existing.getFrontText().getMessage(0, false).getString().startsWith("✝ ")) {
                    PLACED_GRAVES.add(key);
                    return;
                }
            } catch (Throwable ignored) {}
        }
        // Принудительно ставим камень-основание
        world.setBlockState(base, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        // Ставим стоячую табличку
        BlockState signState = Blocks.OAK_SIGN.getDefaultState();
        // Повернём примерно на северо-восток (не критично)
        try {
            signState = signState.with(SignBlock.ROTATION, Integer.valueOf(0));
        } catch (Throwable ignored) {}
        // Принудительно ставим табличку (перекрываем блок)
        world.setBlockState(signPos, signState, Block.NOTIFY_ALL);
        // Пропишем текст
        if (world.getBlockEntity(signPos) instanceof SignBlockEntity sign) {
            String line0 = "✝ " + playerName;
            String cause = normalizeCause(title, playerName);
            if (cause.length() > 22) cause = cause.substring(0, 22) + "…";
            SignText newFront = sign.getFrontText()
                    .withMessage(0, Text.literal(line0))
                    .withMessage(1, Text.literal(cause));
            try { sign.setText(newFront, true); } catch (Throwable ignored) {}
            sign.markDirty();
            BlockState st = world.getBlockState(signPos);
            world.updateListeners(signPos, st, st, Block.NOTIFY_ALL);
            PLACED_GRAVES.add(key);
        }
    }

    // Немного чистим и локализуем сообщение о смерти, убираем дублирование имени
    private static String normalizeCause(String raw, String playerName) {
        if (raw == null) return "";
        String s = raw.replace(playerName, "").trim();
        // Английские ванильные шаблоны → краткие русские
        s = s.replace("was slain by ", "убит ")
             .replace("was shot by ", "застрелен ")
             .replace("was blown up by ", "взорван ")
             .replace("was blown up", "взорван")
             .replace("was fireballed by ", "сожжен шаром ")
             .replace("fell from a high place", "упал с высоты")
             .replace("fell out of the world", "выпал из мира")
             .replace("tried to swim in lava", "утонул в лаве")
             .replace("drowned", "утонул")
             .replace("was struck by lightning", "поражён молнией")
             .replace("was pricked to death", "умер от уколов")
             .replace("was impaled by ", "пронзён ")
             .replace("hit the ground too hard", "сильно ударился")
             .replace("was burnt to a crisp", "сгорел дотла")
             .replace("went up in flames", "сгорел")
             .replace("was slain", "убит")
             .replace("was killed", "убит");
        // Убираем двойные пробелы/артикли
        s = s.replaceAll("\\s+", " ").replace(" by ", " ").trim();
        if (s.isEmpty()) s = "погиб";
        return s;
    }

    private static BlockPos findSafeBase(ServerWorld world, BlockPos around) {
        // Сместим чуть вбок, чтобы не затирать кровь
        BlockPos[] offsets = new BlockPos[]{
                around.north(), around.south(), around.east(), around.west(),
                around, around.up(), around.down()
        };
        for (BlockPos p0 : offsets) {
            BlockPos p = p0;
            // Поднимаемся до 6 блоков вверх, чтобы найти 2 блока воздуха над твёрдой поверхностью
            for (int i = 0; i < 6; i++) {
                BlockPos below = p.down();
                BlockState belowSt = world.getBlockState(below);
                if (world.isAir(p) && world.isAir(p.up()) && belowSt.isSolidBlock(world, below) && belowSt.getFluidState().isEmpty()) {
                    return below; // базовый камень внизу, табличка сверху
                }
                p = p.up();
            }
        }
        return null;
    }

    private static boolean isPersonalWorld(ServerWorld w) {
        return "fabric_race".equals(w.getRegistryKey().getValue().getNamespace());
    }

    private static String getDimKey(ServerWorld w) {
        if (w.getDimensionEntry().matchesKey(DimensionTypes.OVERWORLD)) return "overworld";
        if (w.getDimensionEntry().matchesKey(DimensionTypes.THE_NETHER)) return "nether";
        if (w.getDimensionEntry().matchesKey(DimensionTypes.THE_END)) return "end";
        return w.getRegistryKey().getValue().getPath();
    }

    public static long tryParseSeed(net.minecraft.registry.RegistryKey<World> key) {
        String path = key.getValue().getPath();
        // формат slotX_overworld_s<seed>
        int i = path.lastIndexOf('_');
        if (i < 0) return -1;
        try {
            return Long.parseLong(path.substring(i + 2));
        } catch (Exception ex) {
            try {
                return Long.parseLong(path.substring(i + 1).replace("s", ""));
            } catch (Exception ignored) {}
        }
        return -1;
    }
}


