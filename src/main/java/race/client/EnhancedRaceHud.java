package race.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.UseAction;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeKeys;
import org.lwjgl.glfw.GLFW;
import race.net.RaceBoardPayload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static race.server.phase.RacePhaseManager.getCurrentWorldName;

/**
 * Исправленный и «закалённый» HUD гонки:
 * - Надёжное переключение видимости по клавише R (toggle) без дребезга.
 * - TPS-оверлей подчиняется общей видимости HUD.
 * - Защиты от NPE и «пустых» состояний (нет мира/игрока/скороадов).
 * - Устойчивая активность: не прыгает на пустые значения.
 * - Безопасные расчёты высоты с переносами строк.
 * - Ненавязчивые дефолты: таймеры отображаются как 00:00.000, если источник даёт некорректное значение.
 *
 * Важно: класс регистрирует keybinding и обработчик тиков лениво при первом обращении.
 * Достаточно продолжать вызывать EnhancedRaceHud.render(ctx) в клиентском HUD-ивенте.
 */
public final class EnhancedRaceHud {

    // ---------- Константы макета ----------
    private static final int HUD_MARGIN = 8;
    private static final int ROW_HEIGHT = 14;
    
    // Убран throttling - он вызывает мигание HUD
    private static final int PLAYER_INFO_BASE_HEIGHT = 56;
    private static final int PROGRESS_SECTION_MIN_ROWS = 3;
    private static final int OTHER_PLAYERS_MIN_ROWS = 3;
    private static final int MAX_OTHER_PLAYERS = 5;

    // Полупрозрачные фоны команд
    private static final int[] TEAM_BG_COLORS = {
        0x402196F3, 0x40F44336, 0x40FF9800, 0x40673AB7, 0x4026A69A, 0x40FDD835
    };

    // Этапы прогресса
    private static final String[][] PROGRESS = {
        {"Nether",    "minecraft:story/enter_the_nether"},
        {"Bastion",   "minecraft:nether/find_bastion"},
        {"Fortress",  "minecraft:nether/find_fortress"},
        {"End",       "minecraft:story/enter_the_end"},
        {"Complete",  "minecraft:end/kill_dragon"}
    };

    // ---------- Состояние HUD/ввода ----------
    private static volatile boolean initialized = false;
    private static KeyBinding TOGGLE_HUD_KEY;
    private static KeyBinding TOGGLE_TPS_KEY;

    private static volatile boolean hudVisible = true;

    // ---------- Активность/строки ----------
    private static String currentActivity = "исследует";
    private static int lastDrawnActivityHeight = ROW_HEIGHT;

    // ---------- TPS ----------
    private static volatile double currentTps = 20.0;
    private static volatile boolean tpsDisplayEnabled = true;

    private EnhancedRaceHud() {}

    // Ленивая инициализация: регистрируем hotkeys и tick-хук
    public static void initOnce() {
        if (initialized) return;

        // Регистрируем хоткеи
        TOGGLE_HUD_KEY = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("key.race.toggle_hud", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_U, "key.categories.race")
        );
        TOGGLE_TPS_KEY = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("key.race.toggle_tps", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F7, "key.categories.race")
        );

        // Тик-обработчик: обрабатываем нажатия без дребезга
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (TOGGLE_HUD_KEY != null) {
                while (TOGGLE_HUD_KEY.wasPressed()) {
                    hudVisible = !hudVisible;
                }
            }
            if (TOGGLE_TPS_KEY != null) {
                while (TOGGLE_TPS_KEY.wasPressed()) {
                    tpsDisplayEnabled = !tpsDisplayEnabled;
                }
            }
        });

        initialized = true;
    }

    // Вызывается из клиентского HUD-ивента
    public static void render(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getWindow() == null || mc.textRenderer == null) return;
        if (!hudVisible) {
            // Даже TPS уважаем общую видимость
            return;
        }
        if (mc.player == null || mc.world == null) {
            // Мир ещё не готов — можно показать только TPS-оверлей если включён
            drawTpsInfo(ctx, ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight());
            return;
        }

        int screenW = ctx.getScaledWindowWidth();
        int screenH = ctx.getScaledWindowHeight();

        int dynWidth = clamp((int) (screenW * 0.22), 180, 360);
        int dynMaxH = clamp((int) (screenH * 0.60), 160, screenH - HUD_MARGIN * 2);
        int x = Math.max(HUD_MARGIN, screenW - dynWidth - HUD_MARGIN - 40);
        int y = HUD_MARGIN;

        // TPS-оверлей
        drawTpsInfo(ctx, screenW, screenH);
        
        // Рендерим дымку параллельных игроков
        race.client.GhostOverlay.render(ctx, mc.getRenderTickCounter());

        // Обновление прогресса делаем безопасно
        safeUpdateProgress();

        // Пересчёт высоты «Действие»
        lastDrawnActivityHeight = drawWrappedHeight("Действие: " + getStableActivity(mc), dynWidth - 10);

        // Подсчёт итоговой высоты
        int contentH = calculateHudHeight(dynWidth);
        int hudH = Math.min(dynMaxH, contentH);
        if (hudH <= 0) return;

        drawBackground(ctx, x, y, dynWidth, hudH);

        drawHeader(ctx, x, y, dynWidth);
        y += 18;

        y += drawPlayerInfo(ctx, x, y, dynWidth);

        int remaining = HUD_MARGIN + hudH - y;
        if (remaining > 0) {
            // Прогресс: до 45% остатка, но не меньше минимума
            int minProgress = PROGRESS_SECTION_MIN_ROWS * ROW_HEIGHT + 16;
            int budgetProgress = Math.max(minProgress, (int) (remaining * 0.45));
            y += drawProgressStagesLimited(ctx, x, y, dynWidth, budgetProgress);
        }

        remaining = HUD_MARGIN + hudH - y;
        if (remaining > 0) {
            int minOthers = OTHER_PLAYERS_MIN_ROWS * ROW_HEIGHT + 16;
            int budgetOthers = Math.max(minOthers, remaining);
            y += drawOtherPlayersLimited(ctx, x, y, dynWidth, budgetOthers);
        }
    }

    // ---------- Рендер-помощники ----------

    private static void drawBackground(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x - 4, y - 4, x + w, y + h, 0x88000000);
        ctx.drawBorder(x - 4, y - 4, w + 8, h + 8, 0xFF404040);
        ctx.drawBorder(x - 3, y - 3, w + 6, h + 6, 0xFF606060);
    }

    private static void drawHeader(DrawContext ctx, int x, int y, int width) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.textRenderer == null) return;

        ctx.drawText(mc.textRenderer, Text.literal("Спидран Гонка").formatted(Formatting.GOLD, Formatting.BOLD), x, y, 0xFFFFFF, false);

        long seed = safeSeed();
        String seedStr = seed >= 0 ? Long.toString(seed) : "—";
        String timeStr = TimeFmt.msToClock(safeRtaMs());
        ctx.drawText(mc.textRenderer, Text.literal("Seed: " + seedStr + " | RTA: " + timeStr).formatted(Formatting.GRAY), x, y + 12, 0xFFFFFF, false);
    }

    private static int drawPlayerInfo(DrawContext ctx, int x, int y, int width) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.textRenderer == null) return 0;

        String currentStage = safeCurrentStage();
        long currentTime = safeRtaMs();
        String worldName = getCurrentWorldName(mc);

        ctx.drawText(mc.textRenderer, Text.literal("Этап: " + currentStage).formatted(Formatting.YELLOW), x, y, 0xFFFFFF, false);
        ctx.drawText(mc.textRenderer, Text.literal("Время: " + TimeFmt.msToClock(currentTime)).formatted(Formatting.WHITE), x, y + 14, 0xFFFFFF, false);
        ctx.drawText(mc.textRenderer, Text.literal("Мир: " + worldName).formatted(Formatting.AQUA), x, y + 28, 0xFFFFFF, false);

        String activity = getStableActivity(mc);
        int used = PLAYER_INFO_BASE_HEIGHT;
        used += drawWrapped(ctx, "Действие: " + activity, x, y + 42, width - 10, Formatting.LIGHT_PURPLE);
        return used;
    }

    private static int drawProgressStagesLimited(DrawContext ctx, int x, int y, int width, int maxHeight) {
        int used = 0;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.textRenderer == null) return 0;

        ctx.drawText(mc.textRenderer, Text.literal("Прогресс этапов:").formatted(Formatting.BOLD), x, y, 0xFFFFFF, false);
        used += 16;

        // Текущий этап = первый незавершённый
        int currentIdx = PROGRESS.length - 1;
        for (int i = 0; i < PROGRESS.length; i++) {
            if (!safeStageCompleted(PROGRESS[i][1])) {
                currentIdx = i;
                break;
            }
        }

        long rta = safeRtaMs();

        for (int i = 0; i < PROGRESS.length; i++) {
            if (used + ROW_HEIGHT > maxHeight) break;

            String name = PROGRESS[i][0];
            String id   = PROGRESS[i][1];
            boolean done = safeStageCompleted(id);
            String symbol;
            String timeStr;

            if (done) {
                symbol = "✓";
                timeStr = TimeFmt.msToClock(safeStageTime(id));
            } else if (i == currentIdx) {
                symbol = "▶";
                timeStr = TimeFmt.msToClock(rta);
            } else {
                symbol = "○";
                timeStr = "—";
            }

            Formatting color = done ? Formatting.GREEN : (i == currentIdx ? Formatting.YELLOW : Formatting.GRAY);
            String line = symbol + " " + name + ": " + timeStr;
            ctx.drawText(mc.textRenderer, Text.literal(line).formatted(color), x, y + used, 0xFFFFFF, false);
            used += ROW_HEIGHT;
        }

        // Диагностика — одна строка при наличии места
        String debugInfo = "Seed=" + safeSeed() + " Active=" + safeRaceActive() + " rtaMs=" + safeRtaMs();
        if (used + ROW_HEIGHT <= maxHeight) {
            ctx.drawText(mc.textRenderer, Text.literal(debugInfo).formatted(Formatting.GRAY), x, y + used, 0xFFFFFF, false);
            used += ROW_HEIGHT;
        }
        return used;
    }

    private static int drawOtherPlayersLimited(DrawContext ctx, int x, int y, int width, int maxHeight) {
        int used = 0;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.textRenderer == null) return 0;

        ctx.drawText(mc.textRenderer, Text.literal("Другие игроки:").formatted(Formatting.BOLD), x, y, 0xFFFFFF, false);
        used += 16;

        List<RaceBoardPayload.Row> rows = HudBoardState.getRows();
        if (rows == null) rows = List.of();

        String me = mc.getSession() != null ? mc.getSession().getUsername() : "";
        String myWorld = (mc.world != null) ? mc.world.getRegistryKey().getValue().toString() : null;

        List<RaceBoardPayload.Row> others = new ArrayList<>(rows.size());
        for (RaceBoardPayload.Row r : rows) {
            if (r == null || r.name() == null || r.name().isEmpty() || r.name().equals(me)) continue;
            others.add(r);
        }
        others.sort(Comparator.comparingLong(RaceBoardPayload.Row::rtaMs));

        Map<String, Integer> teamIndex = new java.util.HashMap<>();
        int teamCounter = 1;
        for (RaceBoardPayload.Row r : others) {
            if (r.worldKey() == null) continue;
            if (myWorld != null && r.worldKey().equals(myWorld)) continue;
            if (!teamIndex.containsKey(r.worldKey())) teamIndex.put(r.worldKey(), teamCounter++);
        }

        int drawnCount = 0;
        for (int i = 0; i < others.size() && drawnCount < MAX_OTHER_PLAYERS; i++) {
            RaceBoardPayload.Row r = others.get(i);
            boolean ally = (myWorld != null && r.worldKey() != null && r.worldKey().equals(myWorld));
            int tIdx = (!ally && r.worldKey() != null) ? teamIndex.getOrDefault(r.worldKey(), 0) : 0;

            String activity = (r.activity() == null || r.activity().isEmpty()) ? "" : (" — " + r.activity());
            String prefix = ally ? "[ALLY] " : (tIdx > 0 ? ("[T" + tIdx + "] ") : "[ENEMY] ");
            String line = prefix + r.name() + " [" + r.stage() + "] " + TimeFmt.msToClock(Math.max(0L, r.rtaMs())) + activity;

            int hEst = drawWrappedHeight(line, width - 10);
            if (!ally && tIdx > 0) {
                int col = TEAM_BG_COLORS[(tIdx - 1) % TEAM_BG_COLORS.length];
                ctx.fill(x - 2, y + used - 2, x + width - 6, y + used + hEst - 2, col);
            }

            int h = drawWrapped(ctx, line, x, y + used, width - 10, ally ? Formatting.GREEN : Formatting.WHITE);
            if (used + h > maxHeight) break;

            used += h;
            drawnCount++;
        }
        return used;
    }

    private static int drawWrapped(DrawContext ctx, String str, int x, int y, int width, Formatting color) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.textRenderer == null) return ROW_HEIGHT;
        List<OrderedText> lines = mc.textRenderer.wrapLines(Text.literal(str).formatted(color), width);
        int used = 0;
        for (OrderedText ot : lines) {
            ctx.drawText(mc.textRenderer, ot, x, y + used, 0xFFFFFF, false);
            used += ROW_HEIGHT;
        }
        return used == 0 ? ROW_HEIGHT : used;
    }

    private static int drawWrappedHeight(String str, int width) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.textRenderer == null) return ROW_HEIGHT;
        List<OrderedText> lines = mc.textRenderer.wrapLines(Text.literal(str), width);
        return Math.max(ROW_HEIGHT, lines.size() * ROW_HEIGHT);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    // ---------- Логика активности ----------

    // Внешний inference как был, с защитами
    public static String inferActivityPublic(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) return "";

        var p = client.player;

        if (client.currentScreen != null) {
            String sn = client.currentScreen.getClass().getSimpleName().toLowerCase();
            if (sn.contains("craft")) return "крафтит";
            if (sn.contains("furnace") || sn.contains("smoker") || sn.contains("blast")) return "плавит";
            if (sn.contains("anvil") || sn.contains("smith")) return "куёт";
            if (sn.contains("enchant")) return "чарует";
            if (sn.contains("brew")) return "варит зелья";
            if (sn.contains("grindstone")) return "снимает чары";
            if (sn.contains("shulker") || sn.contains("chest") || sn.contains("barrel")) return "в сундуке";
            if (sn.contains("villager") || sn.contains("merchant")) return "торгуется";
        }

        if (p.isUsingItem()) {
            ItemStack use = p.getActiveItem();
            UseAction act = use.getUseAction();
            if (act == UseAction.EAT) return "ест";
            if (act == UseAction.DRINK) return "пьёт";
            if (use.isOf(Items.ENDER_EYE)) return "кидает око края";
            if (use.isOf(Items.BOW) || use.isOf(Items.CROSSBOW)) return "стреляет";
            if (use.isOf(Items.FISHING_ROD)) return "рыбачит";
            if (use.isOf(Items.SHIELD)) return "блокирует щитом";
        }

        try {
            if (MinecraftClient.getInstance().options.attackKey.isPressed() && client.crosshairTarget != null) {
                HitResult hr = client.crosshairTarget;
                if (hr.getType() == HitResult.Type.BLOCK) {
                    BlockHitResult bhr = (BlockHitResult) hr;
                    var state = client.world.getBlockState(bhr.getBlockPos());
                    return "добывает " + getBlockDisplayName(state.getBlock());
                } else if (hr.getType() == HitResult.Type.ENTITY) {
                    if (hr instanceof net.minecraft.util.hit.EntityHitResult ehr && ehr.getEntity() != null) {
                        return "сражается с " + getEntityDisplayName(ehr.getEntity());
                    }
                    return "сражается";
                }
            }
        } catch (Throwable ignored) {}

        ItemStack main = p.getMainHandStack();
        try {
            if (main.isOf(Items.FLINT_AND_STEEL)) return "поджигает";
            if (MinecraftClient.getInstance().options.useKey.isPressed()) {
                if (main.getItem() instanceof BlockItem) {
                    return "ставит " + getBlockDisplayName(((BlockItem) main.getItem()).getBlock());
                }
                if (main.isOf(Items.WATER_BUCKET)) return "разливает воду";
                if (main.isOf(Items.LAVA_BUCKET)) return "разливает лаву";
            }
        } catch (Throwable ignored) {}

        if (p.isSleeping()) return "спит";
        if (p.isSwimming() || p.isSubmergedInWater()) return "плывёт";
        if (p.isSprinting()) return "бежит";
        if (p.isFallFlying()) return "летит";
        if (p.isSneaking()) return "крадётся";

        if (p.hasVehicle()) {
            var v = p.getVehicle();
            try {
                if (v instanceof BoatEntity) return "едет на лодке";
                if (v instanceof AbstractMinecartEntity) return "едет в вагонетке";
                String id = v.getType().toString().toLowerCase();
                if (id.contains("strider")) return "едет на страйдере";
                if (id.contains("horse")) return "едет на лошади";
            } catch (Throwable ignored) {}
            return "на средстве передвижения";
        }

        if (client.world.getRegistryKey() == World.END) {
            boolean dragonSeen = !client.world.getEntitiesByClass(
                EnderDragonEntity.class, p.getBoundingBox().expand(256), e -> true
            ).isEmpty();
            return dragonSeen ? "сражается с драконом" : "в Краю";
        }
        if (client.world.getRegistryKey() == World.NETHER) {
            return "в Нижнем мире";
        }

        try {
            var biome = client.world.getBiome(p.getBlockPos());
            int y = (int) p.getY();
            boolean miningDepth = y < 40;
            if (client.world.getRegistryKey() == World.NETHER) {
                if (biome.matchesKey(BiomeKeys.WARPED_FOREST)) return "фармит эндерменов";
            } else if (miningDepth && (MinecraftClient.getInstance().options.attackKey.isPressed()
                    || main.isOf(Items.IRON_PICKAXE) || main.isOf(Items.STONE_PICKAXE)
                    || main.isOf(Items.DIAMOND_PICKAXE) || main.isOf(Items.NETHERITE_PICKAXE))) {
                return "копает шахту";
            }
        } catch (Throwable ignored) {}

        return "исследует";
    }

    // Стабилизация: обновлять только на непустое значение
    private static String getStableActivity(MinecraftClient client) {
        String detected = "";
        try {
            detected = inferActivityPublic(client);
        } catch (Throwable ignored) {}
        if (detected != null && !detected.isEmpty()) {
            currentActivity = detected;
        }
        return currentActivity;
    }

    // ---------- Высоты/размеры ----------

    private static int calculateHudHeight(int width) {
        int h = 18 + 4; // заголовок
        int activity = Math.max(ROW_HEIGHT, lastDrawnActivityHeight);
        h += PLAYER_INFO_BASE_HEIGHT + activity;
        h += 16 + PROGRESS_SECTION_MIN_ROWS * ROW_HEIGHT;
        h += 16 + OTHER_PLAYERS_MIN_ROWS * ROW_HEIGHT;
        return h;
    }

    // ---------- Безопасные геттеры внешних систем ----------

    private static void safeUpdateProgress() {
        try {
            RaceProgressTracker.updateProgress();
        } catch (Throwable ignored) {}
    }

    private static String safeCurrentStage() {
        try {
            String s = RaceProgressTracker.getCurrentStage();
            return (s == null || s.isBlank()) ? "—" : s;
        } catch (Throwable ignored) {
            return "—";
        }
    }

    private static boolean safeStageCompleted(String id) {
        try {
            return RaceProgressTracker.isStageCompleted(id);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static long safeStageTime(String id) {
        try {
            long v = RaceProgressTracker.getStageTime(id);
            return Math.max(0L, v);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static long safeRtaMs() {
        try {
            long v = RaceClientEvents.getRtaMs();
            return Math.max(0L, v);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static boolean safeRaceActive() {
        try {
            return RaceClientEvents.isRaceActive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static long safeSeed() {
        try {
            return RaceClientEvents.getWorldSeed();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    // ---------- Имена блоков/сущностей (простая локализация) ----------

    private static String getBlockDisplayName(net.minecraft.block.Block block) {
        try {
            String key = block.getTranslationKey();
            if (key.contains("stone")) return "камень";
            if (key.contains("dirt")) return "землю";
            if (key.contains("grass")) return "траву";
            if (key.contains("sand")) return "песок";
            if (key.contains("gravel")) return "гравий";
            if (key.contains("coal_ore")) return "угольную руду";
            if (key.contains("iron_ore")) return "железную руду";
            if (key.contains("gold_ore")) return "золотую руду";
            if (key.contains("diamond_ore")) return "алмазную руду";
            if (key.contains("emerald_ore")) return "изумрудную руду";
            if (key.contains("redstone_ore")) return "редстоун руду";
            if (key.contains("lapis_ore")) return "лазуритовую руду";
            if (key.contains("nether_quartz_ore")) return "кварцевую руду";
            if (key.contains("nether_gold_ore")) return "золотую руду";
            if (key.contains("ancient_debris")) return "древние обломки";
            if (key.contains("obsidian")) return "обсидиан";
            if (key.contains("cobblestone")) return "булыжник";
            if (key.contains("wood") || key.contains("log")) return "дерево";
            if (key.contains("leaves")) return "листья";
            if (key.contains("planks")) return "доски";
            if (key.contains("glass")) return "стекло";
            if (key.contains("wool")) return "шерсть";
            if (key.contains("clay")) return "глину";
            if (key.contains("snow")) return "снег";
            if (key.contains("ice")) return "лёд";
            if (key.contains("water")) return "воду";
            if (key.contains("lava")) return "лаву";
            if (key.contains("bedrock")) return "бедрок";
            if (key.contains("end_stone")) return "камень Края";
            if (key.contains("netherrack")) return "незерак";
            if (key.contains("soul_sand")) return "песок душ";
            if (key.contains("soul_soil")) return "почву душ";
            if (key.contains("blackstone")) return "чёрный камень";
            if (key.contains("basalt")) return "базальт";
            if (key.contains("crimson") || key.contains("warped")) return "грибной блок";
            return "блок";
        } catch (Throwable ignored) {
            return "блок";
        }
    }

    private static String getEntityDisplayName(net.minecraft.entity.Entity e) {
        try {
            String name = e.getType().toString().toLowerCase();
            if (name.contains("zombie")) return "зомби";
            if (name.contains("skeleton")) return "скелета";
            if (name.contains("creeper")) return "крипера";
            if (name.contains("spider")) return "паука";
            if (name.contains("enderman")) return "эндермена";
            if (name.contains("endermite")) return "эндермита";
            if (name.contains("shulker")) return "шалкера";
            if (name.contains("blaze")) return "блейза";
            if (name.contains("ghast")) return "гаста";
            if (name.contains("wither_skeleton")) return "визер-скелета";
            if (name.contains("piglin")) return "пиглина";
            if (name.contains("hoglin")) return "хоглина";
            if (name.contains("strider")) return "страйдера";
            if (name.contains("villager")) return "жителя";
            if (name.contains("iron_golem")) return "железного голема";
            if (name.contains("snow_golem")) return "снежного голема";
            if (name.contains("cow")) return "корову";
            if (name.contains("pig")) return "свинью";
            if (name.contains("sheep")) return "овцу";
            if (name.contains("chicken")) return "курицу";
            if (name.contains("horse")) return "лошадь";
            if (name.contains("wolf")) return "волка";
            if (name.contains("cat")) return "кота";
            if (name.contains("player")) return "игрока";
            if (name.contains("boat")) return "лодку";
            if (name.contains("minecart")) return "вагонетку";
            return "моба";
        } catch (Throwable ignored) {
            return "моба";
        }
    }

    private static String getCurrentWorldName(MinecraftClient mc) {
        if (mc == null || mc.world == null) return "—";
        var w = mc.world;
        boolean inEnd = w.getDimensionEntry().matchesKey(net.minecraft.world.dimension.DimensionTypes.THE_END);
        boolean inNether = w.getDimensionEntry().matchesKey(net.minecraft.world.dimension.DimensionTypes.THE_NETHER);
        if (inEnd) return "End";
        if (inNether) return "Nether";
        return "Overworld";
    }

    // ---------- TPS ----------

    public static void setTpsInfo(double tps, boolean enabled) {
        currentTps = tps;
        tpsDisplayEnabled = enabled;
    }

    private static void drawTpsInfo(DrawContext ctx, int screenW, int screenH) {
        if (!tpsDisplayEnabled) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.textRenderer == null) return;

        String tpsText = String.format("TPS: %.1f", currentTps);
        int textW = mc.textRenderer.getWidth(tpsText);
        int x = screenW - textW - 10;
        int y = 10;

        int color = currentTps >= 19.5 ? 0x00FF00
                   : currentTps >= 18.0 ? 0xFFFF00
                   : currentTps >= 15.0 ? 0xFF8000
                   : 0xFF0000;
        ctx.fill(x - 2, y - 2, x + textW + 2, y + 10, 0x80000000);
        ctx.drawText(mc.textRenderer, tpsText, x, y, color, false);
    }
}
