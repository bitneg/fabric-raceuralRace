package race.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
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
import race.net.RaceBoardPayload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Улучшенный HUD гонки — актуализирован под 1.21.1
 */
public final class EnhancedRaceHud {
    private static final int HUD_MARGIN = 8;
    private static final int ROW_HEIGHT = 14;
    private static final int PLAYER_INFO_BASE_HEIGHT = 56;
    private static final int PROGRESS_SECTION_HEIGHT = 84;
    private static final int MAX_OTHER_PLAYERS = 5;
    private static final int[] TEAM_BG_COLORS = {
        0x402196F3, 0x40F44336, 0x40FF9800, 0x40673AB7, 0x4026A69A, 0x40FDD835
    };

    private static final String[][] PROGRESS = {
        {"Nether",    "minecraft:story/enter_the_nether"},
        {"Bastion",   "minecraft:nether/find_bastion"},
        {"Fortress",  "minecraft:nether/find_fortress"},
        {"End",       "minecraft:story/enter_the_end"},
        {"Complete",  "minecraft:end/kill_dragon"}
    };

    private static String lastActivity = "";
    private static long lastActivityTime = 0L;
    private static final long ACTIVITY_DISPLAY_TIME = 2000L;

    private static volatile double currentTps = 20.0;
    private static volatile boolean tpsDisplayEnabled = false;

    private EnhancedRaceHud() {}

    public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.textRenderer == null) return; // безопасный выход

        int screenW = ctx.getScaledWindowWidth();
        int screenH = ctx.getScaledWindowHeight();

        int dynWidth = clamp((int) (screenW * 0.22), 180, 360);
        int dynMaxH = clamp((int) (screenH * 0.60), 140, screenH - HUD_MARGIN * 2);
        int x = Math.max(HUD_MARGIN, screenW - dynWidth - HUD_MARGIN - 40);
        int y = HUD_MARGIN;

        RaceProgressTracker.updateProgress(); // обновить данные перед расчётом компоновки

        int contentH = calculateHudHeight(dynWidth);
        int hudH = Math.min(dynMaxH, contentH);

        drawTpsInfo(ctx, screenW, screenH);

        if (hudH <= 0) return;
        drawBackground(ctx, x, y, dynWidth, hudH);

        // Заголовок
        drawHeader(ctx, x, y, dynWidth);
        y += 18;

        // Блок игрока
        y += drawPlayerInfo(ctx, x, y, dynWidth);
        int remaining = HUD_MARGIN + hudH - y;
        if (remaining <= 0) return;

        // Прогресс этапов (ограниченный по высоте)
        int budgetProgress = Math.max(0, (int) (remaining * 0.45));
        y += drawProgressStagesLimited(ctx, x, y, dynWidth, budgetProgress);
        remaining = HUD_MARGIN + hudH - y;
        if (remaining <= 0) return;

        // Другие игроки (в остаток)
        y += drawOtherPlayersLimited(ctx, x, y, dynWidth, remaining);
    }

    private static void drawBackground(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x - 4, y - 4, x + w, y + h, 0x88000000);
        ctx.drawBorder(x - 4, y - 4, w + 8, h + 8, 0xFF404040);
        ctx.drawBorder(x - 3, y - 3, w + 6, h + 6, 0xFF606060);
    }

    private static void drawHeader(DrawContext ctx, int x, int y, int width) {
        MinecraftClient mc = MinecraftClient.getInstance();
        String title = "Спидран Гонка";
        ctx.drawText(mc.textRenderer, Text.literal(title).formatted(Formatting.GOLD, Formatting.BOLD), x, y, 0xFFFFFF, false);

        String seedStr = RaceClientEvents.getWorldSeed() >= 0 ? Long.toString(RaceClientEvents.getWorldSeed()) : "—";
        String timeStr = TimeFmt.msToClock(RaceClientEvents.getRtaMs());
        ctx.drawText(mc.textRenderer, Text.literal("Seed: " + seedStr + " | RTA: " + timeStr).formatted(Formatting.GRAY), x, y + 12, 0xFFFFFF, false);
    }

    private static int drawPlayerInfo(DrawContext ctx, int x, int y, int width) {
        MinecraftClient mc = MinecraftClient.getInstance();

        String currentStage = RaceProgressTracker.getCurrentStage();
        long currentTime = RaceClientEvents.getRtaMs();
        String worldName = getCurrentWorldName();

        ctx.drawText(mc.textRenderer, Text.literal("Этап: " + currentStage).formatted(Formatting.YELLOW), x, y, 0xFFFFFF, false);
        ctx.drawText(mc.textRenderer, Text.literal("Время: " + TimeFmt.msToClock(currentTime)).formatted(Formatting.WHITE), x, y + 14, 0xFFFFFF, false);
        ctx.drawText(mc.textRenderer, Text.literal("Мир: " + worldName).formatted(Formatting.AQUA), x, y + 28, 0xFFFFFF, false);

        String activity = getCachedActivity(mc);
        int used = PLAYER_INFO_BASE_HEIGHT;
        if (!activity.isEmpty()) {
            used += drawWrapped(ctx, "Действие: " + activity, x, y + 42, width - 10, Formatting.LIGHT_PURPLE);
        }
        return used;
    }

    private static int drawProgressStagesLimited(DrawContext ctx, int x, int y, int width, int maxHeight) {
        int used = 0;
        if (maxHeight <= 0) return 0;

        MinecraftClient mc = MinecraftClient.getInstance();
        ctx.drawText(mc.textRenderer, Text.literal("Прогресс этапов:").formatted(Formatting.BOLD), x, y, 0xFFFFFF, false);
        used += 16;
        if (used >= maxHeight) return used;

        // Определяем «текущий» — первый незавершенный
        int currentIdx = PROGRESS.length - 1;
        for (int i = 0; i < PROGRESS.length; i++) {
            if (!RaceProgressTracker.isStageCompleted(PROGRESS[i][1])) { 
                currentIdx = i; 
                break; 
            }
        }

        long rta = RaceClientEvents.getRtaMs();

        for (int i = 0; i < PROGRESS.length; i++) {
            if (used + ROW_HEIGHT > maxHeight) break;
            String name = PROGRESS[i][0];
            String id   = PROGRESS[i][1];
            boolean done = RaceProgressTracker.isStageCompleted(id);
            String symbol;
            String timeStr;

            if (done) {
                symbol = "✓";
                timeStr = TimeFmt.msToClock(RaceProgressTracker.getStageTime(id));
            } else if (i == currentIdx) {
                symbol = "▶";
                timeStr = TimeFmt.msToClock(rta); // живой таймер для активного этапа
            } else {
                symbol = "○";
                timeStr = "—";
            }

            Formatting color = done ? Formatting.GREEN : (i == currentIdx ? Formatting.YELLOW : Formatting.GRAY);
            String line = symbol + " " + name + ": " + timeStr;
            ctx.drawText(mc.textRenderer, Text.literal(line).formatted(color), x, y + used, 0xFFFFFF, false);
            used += ROW_HEIGHT;
        }
        return used;
    }

    private static int drawOtherPlayersLimited(DrawContext ctx, int x, int y, int width, int maxHeight) {
        int used = 0;
        if (maxHeight <= 0) return 0;

        MinecraftClient mc = MinecraftClient.getInstance();
        ctx.drawText(mc.textRenderer, Text.literal("Другие игроки:").formatted(Formatting.BOLD), x, y, 0xFFFFFF, false);
        used += 16;
        if (used >= maxHeight) return used;

        List<RaceBoardPayload.Row> rows = HudBoardState.getRows();
        String me = mc.getSession() != null ? mc.getSession().getUsername() : "";
        String myWorld = (mc.world != null) ? mc.world.getRegistryKey().getValue().toString() : "";

        List<RaceBoardPayload.Row> others = new ArrayList<>(rows.size());
        for (RaceBoardPayload.Row r : rows) {
            if (r == null || r.name() == null || r.name().isEmpty() || r.name().equals(me)) continue;
            others.add(r);
        }
        others.sort(Comparator.comparingLong(RaceBoardPayload.Row::rtaMs));

        java.util.Map<String, Integer> teamIndex = new java.util.HashMap<>();
        int teamCounter = 1;
        for (RaceBoardPayload.Row r : others) {
            if (r.worldKey() == null) continue;
            if (r.worldKey().equals(myWorld)) continue;
            if (!teamIndex.containsKey(r.worldKey())) teamIndex.put(r.worldKey(), teamCounter++);
        }

        int drawnCount = 0;
        for (int i = 0; i < others.size() && drawnCount < MAX_OTHER_PLAYERS; i++) {
            RaceBoardPayload.Row r = others.get(i);
            boolean ally = r.worldKey() != null && r.worldKey().equals(myWorld);
            int tIdx = (!ally && r.worldKey() != null) ? teamIndex.getOrDefault(r.worldKey(), 0) : 0;

            String activity = (r.activity() == null || r.activity().isEmpty()) ? "" : (" — " + r.activity());
            String prefix = ally ? "[ALLY] " : (tIdx > 0 ? ("[T" + tIdx + "] ") : "[ENEMY] ");
            String line = prefix + r.name() + " [" + r.stage() + "] " + TimeFmt.msToClock(r.rtaMs()) + activity;

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
        List<OrderedText> lines = mc.textRenderer.wrapLines(Text.literal(str), width);
        return Math.max(ROW_HEIGHT, lines.size() * ROW_HEIGHT);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    static String inferActivityPublic(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) return ""; // безопасность

        var p = client.player;

        // Открытые экраны
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

        if (client.options.attackKey.isPressed() && client.crosshairTarget != null) {
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

        ItemStack main = p.getMainHandStack();
        if (main.isOf(Items.FLINT_AND_STEEL)) return "поджигает";
        if (main.getItem() instanceof BlockItem && client.options.useKey.isPressed()) {
            return "ставит " + getBlockDisplayName(((BlockItem) main.getItem()).getBlock());
        }
        if (client.options.useKey.isPressed() && main.isOf(Items.WATER_BUCKET)) return "разливает воду";
        if (client.options.useKey.isPressed() && main.isOf(Items.LAVA_BUCKET)) return "разливает лаву";

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
                String id = v.getType().toString();
                if (id.contains("strider")) return "едет на страйдере";
                if (id.contains("horse")) return "едет на лошади";
            } catch (Throwable ignored) {}
            return "на средстве передвижения";
        }

        // Измерения
        if (client.world.getRegistryKey() == World.END) {
            boolean dragonSeen = !client.world.getEntitiesByClass(
                EnderDragonEntity.class, p.getBoundingBox().expand(256), e -> true
            ).isEmpty();
            return dragonSeen ? "сражается с драконом" : "в Краю";
        }
        if (client.world.getRegistryKey() == World.NETHER) {
            return "в Нижнем мире";
        }

        // Макро-эвристики
        try {
            var biome = client.world.getBiome(p.getBlockPos());
            int y = (int) p.getY();
            boolean miningDepth = y < 40;
            if (client.world.getRegistryKey() == World.NETHER) {
                if (biome.matchesKey(BiomeKeys.WARPED_FOREST)) return "фармит эндерменов";
            } else if (miningDepth && (client.options.attackKey.isPressed()
                    || main.isOf(Items.IRON_PICKAXE) || main.isOf(Items.STONE_PICKAXE)
                    || main.isOf(Items.DIAMOND_PICKAXE) || main.isOf(Items.NETHERITE_PICKAXE))) {
                return "копает шахту";
            }
        } catch (Throwable ignored) {}

        return "исследует";
    }

    private static String getCachedActivity(MinecraftClient client) {
        long now = System.currentTimeMillis();
        String current = inferActivityPublic(client);
        if (!current.isEmpty() && !current.equals("исследует")) {
            lastActivity = current;
            lastActivityTime = now;
            return current;
        }
        if (!lastActivity.isEmpty() && (now - lastActivityTime) < ACTIVITY_DISPLAY_TIME) {
            return lastActivity;
        }
        return current;
    }

    private static int calculateHudHeight(int width) {
        int h = 22;
        MinecraftClient mc = MinecraftClient.getInstance();
        String activity = getCachedActivity(mc);
        int pi = PLAYER_INFO_BASE_HEIGHT;
        if (!activity.isEmpty()) {
            pi += drawWrappedHeight("Действие: " + activity, width - 10);
        }
        h += pi;
        h += PROGRESS_SECTION_HEIGHT;
        h += 20;

        List<RaceBoardPayload.Row> rows = HudBoardState.getRows();
        String me = mc.getSession() != null ? mc.getSession().getUsername() : "";
        String myWorld = (mc.world != null) ? mc.world.getRegistryKey().getValue().toString() : "";

        int shown = 0;
        for (RaceBoardPayload.Row r : rows) {
            if (r == null || r.name() == null || r.name().isEmpty() || r.name().equals(me)) continue;
            boolean ally = r.worldKey() != null && r.worldKey().equals(myWorld);
            String activityLine = (r.activity() == null || r.activity().isEmpty()) ? "" : (" — " + r.activity());
            String prefix = ally ? "[ALLY] " : "";
            String line = prefix + r.name() + " [" + r.stage() + "] " + TimeFmt.msToClock(r.rtaMs()) + activityLine;
            h += drawWrappedHeight(line, width - 10);
            if (++shown >= MAX_OTHER_PLAYERS) break;
        }
        h += 8;
        return h;
    }

    private static String getCurrentWorldName() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return "—";
        
        var w = mc.world;
        boolean inEnd = w.getDimensionEntry().matchesKey(net.minecraft.world.dimension.DimensionTypes.THE_END);
        boolean inNether = w.getDimensionEntry().matchesKey(net.minecraft.world.dimension.DimensionTypes.THE_NETHER);
        
        if (inEnd) return "End";
        if (inNether) return "Nether";
        return "Overworld";
    }

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

    public static void setTpsInfo(double tps, boolean enabled) {
        currentTps = tps;
        tpsDisplayEnabled = enabled;
    }

    private static void drawTpsInfo(DrawContext ctx, int screenW, int screenH) {
        if (!tpsDisplayEnabled) return;
        var tr = MinecraftClient.getInstance().textRenderer;
        String tpsText = String.format("TPS: %.1f", currentTps);
        int textW = tr.getWidth(tpsText);
        int x = screenW - textW - 10;
        int y = 10;

        int color = currentTps >= 19.5 ? 0x00FF00 : currentTps >= 18.0 ? 0xFFFF00 : currentTps >= 15.0 ? 0xFF8000 : 0xFF0000;
        ctx.fill(x - 2, y - 2, x + textW + 2, y + 10, 0x80000000);
        ctx.drawText(tr, tpsText, x, y, color, false);
    }
}