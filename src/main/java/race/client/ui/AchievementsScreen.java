package race.client.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import race.client.RaceProgressTracker;
import race.client.GuideGoalsTracker;
import race.client.RaceClientEvents;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AchievementsScreen extends Screen {
    private final Screen parent;
    private final Map<String, String> stages = new LinkedHashMap<>();
    private boolean showGuideGoals = true;
    private Double originalMenuBlur = null;
    private final java.util.Map<String, int[]> rowBounds = new java.util.HashMap<>();

    public AchievementsScreen(Screen parent) {
        super(Text.literal("Achievements"));
        this.parent = parent;
        // Порядок соответствует гоночно-гаидовым целям
        // По умолчанию — показываем цели гайда; второй режим — ванильные достижения
        rebuildStages();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 6;
        // Временно отключаем размытие фона через accessor, как в RaceMenuScreen
        try {
            if (this.client != null && this.client.options != null) {
                race.mixin.client.GameOptionsAccessor acc = (race.mixin.client.GameOptionsAccessor) (Object) this.client.options;
                net.minecraft.client.option.SimpleOption<Integer> opt = acc.getMenuBackgroundBlurriness_FAB();
                if (opt != null) {
                    originalMenuBlur = opt.getValue().doubleValue();
                    opt.setValue(0);
                }
            }
        } catch (Throwable ignored) {}
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> {
            if (this.client != null) this.client.setScreen(parent);
        }).dimensions(centerX - 40, y + 12 + stages.size() * 22 + 16, 80, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Open Guide"), b -> {
            if (this.client != null) this.client.setScreen(new RaceGuideScreen( mapStageToTab(currentHighlightedTab()) ));
        }).dimensions(centerX - 140, y + 12 + stages.size() * 22 + 16, 90, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Mode: " + (showGuideGoals ? "Guide" : "Adv")), b -> {
            showGuideGoals = !showGuideGoals;
            rebuildStages();
            this.init();
        }).dimensions(centerX + 50, y + 12 + stages.size() * 22 + 16, 90, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int centerX = this.width / 2;
        int y = this.height / 6;

        // Полупрозрачная панель под списком, чтобы текст не "замазывался"
        int panelWidth = 360;
        int panelHeight = 24 + stages.size() * 22 + 12;
        int px = centerX - panelWidth / 2;
        int py = y - 18;
        ctx.fill(px - 6, py - 6, px + panelWidth + 6, py + panelHeight + 6, 0x88000000);
        ctx.drawBorder(px - 6, py - 6, panelWidth + 12, panelHeight + 12, 0xFF404040);
        ctx.drawBorder(px - 5, py - 5, panelWidth + 10, panelHeight + 10, 0xFF606060);

        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, y - 12, 0xFFFFFF);

        int rowY = y + 12;
        rowBounds.clear();
        for (Map.Entry<String, String> e : stages.entrySet()) {
            String id = e.getKey();
            String name = e.getValue();
            boolean done;
            long t;
            if (showGuideGoals) {
                done = GuideGoalsTracker.isDone(id);
                t = GuideGoalsTracker.getDoneTimeMs(id);
            } else {
                done = RaceProgressTracker.isStageCompleted(id);
                t = RaceProgressTracker.getStageTime(id);
            }
            String timeStr = t > 0 ? formatMs(t) : "--:--";
            int color = done ? 0x55FF55 : 0xFFAA00;
            int textX = centerX - 140;
            // чекбокс только для guide-целей
            if (showGuideGoals) {
                String box = done ? "[x] " : "[ ] ";
                ctx.drawText(this.textRenderer, Text.literal(box), textX - 24, rowY, 0xCCCCCC, false);
            }
            ctx.drawText(this.textRenderer, Text.literal(name), textX, rowY, color, false);
            ctx.drawText(this.textRenderer, Text.literal(timeStr), centerX + 100, rowY, 0xCCCCCC, false);
            rowBounds.put(id, new int[]{textX - 28, rowY - 2, 260, 16});
            rowY += 22;
        }
        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void removed() {
        // Восстанавливаем размытие меню
        try {
            if (originalMenuBlur != null && this.client != null && this.client.options != null) {
                race.mixin.client.GameOptionsAccessor acc = (race.mixin.client.GameOptionsAccessor) (Object) this.client.options;
                net.minecraft.client.option.SimpleOption<Integer> opt = acc.getMenuBackgroundBlurriness_FAB();
                if (opt != null) opt.setValue(originalMenuBlur.intValue());
            }
        } catch (Throwable ignored) {}
        super.removed();
    }

    private static String formatMs(long ms) {
        long s = ms / 1000L;
        long m = s / 60L; s %= 60L;
        long h = m / 60L; m %= 60L;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%d:%02d", m, s);
    }

    private int currentHighlightedTab() {
        // Выделяем следующий незавершенный этап; если всё сделано — последний
        int idx = 0;
        int i = 0;
        for (String id : stages.keySet()) {
            boolean done = showGuideGoals ? GuideGoalsTracker.isDone(id) : RaceProgressTracker.isStageCompleted(id);
            if (!done) { idx = i; break; }
            idx = i; i++;
        }
        return idx;
    }

    private static int mapStageToTab(int stageIndex) {
        // TABS: {"Overworld", "Nether", "Stronghold", "End", "Советы", "Checklist"}
        // Наши этапы: 0 Nether, 1 Bastion, 2 Fortress, 3 Blaze Rod, 4 Stronghold, 5 End, 6 Complete
        return switch (stageIndex) {
            case 0, 1, 2, 3 -> 1; // Всё про Nether
            case 4 -> 2; // Stronghold
            case 5, 6 -> 3; // End
            default -> 0;
        };
    }

    private void rebuildStages() {
        stages.clear();
        if (showGuideGoals) {
            for (GuideGoalsTracker.Goal g : GuideGoalsTracker.getGoals()) {
                stages.put(g.id, g.title);
            }
        } else {
            stages.put("minecraft:story/enter_the_nether", "Nether");
            stages.put("minecraft:nether/find_bastion", "Bastion");
            stages.put("minecraft:nether/find_fortress", "Fortress");
            stages.put("minecraft:nether/obtain_blaze_rod", "Blaze Rod");
            stages.put("minecraft:story/follow_ender_eye", "Stronghold");
            stages.put("minecraft:story/enter_the_end", "End");
            stages.put("minecraft:end/kill_dragon", "Complete");
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (showGuideGoals && button == 0) {
            for (var e : rowBounds.entrySet()) {
                int[] r = e.getValue();
                if (mouseX >= r[0] && mouseX <= r[0] + r[2] && mouseY >= r[1] && mouseY <= r[1] + r[3]) {
                    GuideGoalsTracker.toggle(e.getKey(), RaceClientEvents.getRtaMs());
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}


