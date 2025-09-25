package race.client.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class RaceGuideScreen extends Screen {
    private static final int PADDING = 12;
    private static final String[] TABS = {"Overworld", "Nether", "Stronghold", "End", "Советы", "Checklist", "Таймеры"};
    private int tab = 0;
    private int scroll = 0;
    private static boolean[] checklistDone;

    public RaceGuideScreen() { super(Text.literal("Speedrun Guide")); }
    public RaceGuideScreen(int initialTab) {
        super(Text.literal("Speedrun Guide"));
        this.tab = Math.max(0, Math.min(initialTab, TABS.length - 1));
    }

    @Override
    protected void init() {
        int w = 100, h = 20;
        int x = this.width - w - PADDING;
        int y = this.height - h - PADDING;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Achievements"), b -> {
            if (this.client != null) this.client.setScreen(new AchievementsScreen(this));
        }).dimensions(x - w - 8, y, w + 20, h).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close()).dimensions(x, y, w, h).build());

        // Кнопки-вкладки
        int tx = PADDING;
        for (int i = 0; i < TABS.length; i++) {
            int idx = i;
            this.addDrawableChild(ButtonWidget.builder(Text.literal(TABS[i]), b -> { tab = idx; scroll = 0; }).dimensions(tx, PADDING, 100, 20).build());
            tx += 104;
        }
    }

    @Override
    public void close() { MinecraftClient.getInstance().setScreen(null); }
    @Override public boolean shouldPause() { return false; }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        double amt = Math.abs(verticalAmount) > 0.0001 ? verticalAmount : -horizontalAmount;
        int maxScroll = Math.max(0, contentHeight() - visibleHeight());
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int)(amt * 24)));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // PgUp/PgDn
        if (keyCode == 266) { scroll = Math.max(0, scroll - visibleHeight()); return true; }
        if (keyCode == 267) { scroll = Math.min(Math.max(0, contentHeight() - visibleHeight()), scroll + visibleHeight()); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private int visibleHeight() { return this.height - PADDING * 3 - 20 - 24; }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);
        var tr = MinecraftClient.getInstance().textRenderer;

        int contentX = PADDING;
        int contentY = PADDING + 24 + 8; // под вкладками
        int y = contentY - scroll;

        // Заголовок вкладки
        ctx.drawText(tr, Text.literal("Гайд: " + TABS[tab]).formatted(Formatting.GOLD, Formatting.BOLD), contentX, y, 0xFFFFFF, false);
        y += 18;

        // Контент
        String[][] sections = switch (tab) {
            case 0 -> sectionsOverworld();
            case 1 -> sectionsNether();
            case 2 -> sectionsStronghold();
            case 3 -> sectionsEnd();
            case 4 -> sectionsTips();
            case 5 -> sectionsChecklist();
            default -> sectionsTimers();
        };

        if (tab == 5) {
            y = renderChecklist(ctx, contentX, y);
        } else if (tab == 6) {
            y = renderTimers(ctx, contentX, y);
        } else {
            for (String[] block : sections) {
                y = section(ctx, contentX, y, block[0], java.util.Arrays.copyOfRange(block, 1, block.length));
            }
        }

        // Рамка видимой области (визуальный намёк на скролл)
        int vh = visibleHeight();
        ctx.drawBorder(contentX - 6, contentY - 6, this.width - contentX - PADDING + 6, vh + 12, 0x66FFFFFF);

        super.render(ctx, mouseX, mouseY, delta);
    }

    private int section(DrawContext ctx, int x, int y, String title, String[] lines) {
        var tr = MinecraftClient.getInstance().textRenderer;
        ctx.drawText(tr, Text.literal(title).formatted(Formatting.AQUA, Formatting.BOLD), x, y, 0xFFFFFF, false);
        y += 14;
        for (String s : lines) {
            ctx.drawText(tr, Text.literal("• " + s).formatted(Formatting.GRAY), x, y, 0xFFFFFF, false);
            y += 12;
        }
        y += 8;
        return y;
    }

    private int contentHeight() {
        int base = 18; // заголовок
        String[][] sec = switch (tab) {
            case 0 -> sectionsOverworld();
            case 1 -> sectionsNether();
            case 2 -> sectionsStronghold();
            case 3 -> sectionsEnd();
            case 4 -> sectionsTips();
            case 5 -> sectionsChecklist();
            default -> sectionsTimers();
        };
        if (tab == 5) {
            return base + checklistSteps().length * 14 + 24;
        } else if (tab == 6) {
            return base + 200; // Примерная высота для таймеров
        } else {
            int h = base;
            for (String[] s : sec) h += 14 + (s.length - 1) * 12 + 8;
            return h;
        }
    }

    private String[][] sectionsOverworld() {
        return new String[][]{
                {
                        "Старт",
                        "Дерево → верстак → топор/кирка",
                        "Камень 8 → кам. инструменты + печь",
                        "Железо 3: ведро, огниво, 1 запас",
                        "Еда 8–16 шт, кровати 2–4"
                },
                {
                        "Портал",
                        "Ищем лужу лавы/разлом/разрушенный портал",
                        "Метод ведра: колонна 3×3, заливка по схеме",
                        "Проверка: щит/еда/блоки готовы перед входом"
                },
                {
                        "Альтернативы",
                        "Корабль: еда, железо, карта/сундук",
                        "Деревня: кровати, еда, голем → железо",
                        "Разрушенный портал: золото, шанс быстрой сборки"
                }
        };
    }

    private String[][] sectionsNether() {
        return new String[][]{
                {
                        "Цели в Nether",
                        "12–16 перлов (бартер/варп)",
                        "6–8 огненных стержней (крепость)",
                        "Лут: обсидиан, струнка, стрелы, огнестойки"
                },
                {
                        "Бастион (бартер)",
                        "Собрать золото (блоки/сундуки)",
                        "Запереть пиглинов в яме, кидать золото",
                        "Цель: эндер-перлы, обсидиан, огнестойки"
                },
                {
                        "Варп-лес (фарм перлов)",
                        "Подняться на платформу 2 блока, согреть эндерменов",
                        "Убивать в безопасности; 12+ перлов"
                },
                {
                        "Крепость",
                        "Находим спавнер, строим коробку от ифритов",
                        "Фармим 6–8 стержней; варим огнестойку при необходимости"
                },
                {
                        "Возврат в OW",
                        "Скрафтить 12 глаз края",
                        "Поставить спавн, проверить еду/кровати",
                        "Построить портал (если нужен)"
                }
        };
    }

    private String[][] sectionsStronghold() {
        return new String[][]{
                {
                        "Поиск",
                        "Бросай глаз каждые ~8–10 чанков",
                        "Запоминай угол; при смене направления — ближе",
                        "Триангуляция: две точки, провести перпендикуляры"
                },
                {
                        "Дигдаун",
                        "Дигни в центре чанка у пересечения",
                        "На y~30 слушай сереброкаменных, ищи кирпич",
                        "Найди порталную, поставь спавн и сундук"
                },
                {
                        "Подготовка к Энду",
                        "Кровати 5–8, еда, блоки 2 стака, щит",
                        "Перлы 2–4, ведро воды/огнестойка",
                        "Активируй портал — вперёд"
                }
        };
    }

    private String[][] sectionsEnd() {
        return new String[][]{
                {
                        "Старт в Энде",
                        "Мост на остров (если спавн далеко)",
                        "Площадка у портала 3×3, позиция под кровати"
                },
                {
                        "Кристаллы",
                        "Сломать доступные (лук/снежки) или игнор и бэдстраты",
                        "Смотреть на высоту дракона — ждать посадку"
                },
                {
                        "Кроватные страты",
                        "На посадке — ставь кровать, приседай, взрывай",
                        "Повторить 5–6 раз; добить мечом/якори/якорь+заряд"
                },
                {
                        "Финиш",
                        "Запрыгни в портал, забери яйцо — GG"
                }
        };
    }

    private String[][] sectionsTips() {
        return new String[][]{
                {
                        "Хоткеи/инфо",
                        "R — меню гонки, G — гайд, U — HUD",
                        "Ставь спавн перед риском; всегда имей кровать",
                        "Золото в ад — минимум 40–60, лучше блоками",
                        "F3+C — копировать координаты",
                        "F3+G — показать границы чанков"
                },
                {
                        "Инвентарь перед адом",
                        "Ведро воды/огниво/еда/блоки 2 стака",
                        "Лук/стрелы или снежки, щит, лодка"
                },
                {
                        "Полезные трюки",
                        "Лодка для падений; MLG водой/лодкой",
                        "Бедстраты требуют тренировки — держи щит!",
                        "Не бойся ресета, цель — стабильность"
                },
                {
                        "Интерактивные функции",
                        "Клик по чеклисту — отмечать прогресс",
                        "ПКМ по чеклисту — сбросить все",
                        "Вкладка 'Таймеры' — отслеживание времени",
                        "Связь с Achievements — детальный прогресс"
                }
        };
    }

        // Checklist
    private String[] checklistSteps() {
        return new String[]{
                "Инструменты + еда",
                "Железо 3: ведро/огниво",
                "Портал в ад",
                "Перлы 12–16",
                "Стержни 6–8",
                "Глаза края 12",
                "Найти стронгхолд",
                "Активировать портал",
                "Кровати/площадка",
                "Убийство дракона"
        };
    }

    private String[][] sectionsChecklist() {
        String[] steps = checklistSteps();
        String[][] res = new String[1 + steps.length][];
        res[0] = new String[]{"Чеклист", "Нажимай на шаги, чтобы отмечать прогресс"};
        for (int i = 0; i < steps.length; i++) res[i + 1] = new String[]{Integer.toString(i)};
        return res;
    }

    private int renderChecklist(DrawContext ctx, int x, int y) {
        var tr = MinecraftClient.getInstance().textRenderer;
        String[] steps = checklistSteps();
        if (checklistDone == null || checklistDone.length != steps.length) checklistDone = new boolean[steps.length];
        for (int i = 0; i < steps.length; i++) {
            String box = checklistDone[i] ? "[x] " : "[ ] ";
            ctx.drawText(tr, Text.literal(box + steps[i]).formatted(checklistDone[i] ? Formatting.GREEN : Formatting.GRAY), x, y, 0xFFFFFF, false);
            y += 14;
        }
        y += 6;
        // Reset hint
        ctx.drawText(tr, Text.literal("ПКМ — сбросить чеклист").formatted(Formatting.DARK_GRAY), x, y, 0xFFFFFF, false);
        return y + 12;
    }

    private String[][] sectionsTimers() {
        return new String[][]{
                {
                        "Целевые времена",
                        "Старт → Nether: 2-4 минуты",
                        "Nether → Stronghold: 8-12 минут", 
                        "Stronghold → End: 12-16 минут",
                        "End → Финиш: 16-20 минут"
                },
                {
                        "Промежуточные цели",
                        "Железо 3: 1-2 минуты",
                        "Портал: 3-5 минут",
                        "Перлы 12: 6-10 минут",
                        "Стержни 6: 8-12 минут"
                },
                {
                        "Секреты экономии времени",
                        "Параллельные задачи: еда + железо",
                        "Быстрый портал: ищи лаву, не копай",
                        "Эффективный бартер: золото блоками",
                        "Быстрый дигдаун: слушай звуки"
                }
        };
    }

    private int renderTimers(DrawContext ctx, int x, int y) {
        var tr = MinecraftClient.getInstance().textRenderer;
        
        // Показываем текущее время
        long currentTime = race.client.RaceClientEvents.getRtaMs();
        String timeStr = race.client.TimeFmt.msToClock(currentTime);
        
        ctx.drawText(tr, Text.literal("Текущее время: " + timeStr).formatted(Formatting.GOLD, Formatting.BOLD), x, y, 0xFFFFFF, false);
        y += 20;
        
        // Прогресс-бар времени
        int barWidth = 200;
        int barHeight = 8;
        int barX = x;
        int barY = y;
        
        // Фон прогресс-бара
        ctx.fill(barX, barY, barX + barWidth, barY + barHeight, 0x66000000);
        
        // Прогресс (20 минут = 100%)
        float progress = Math.min(1.0f, currentTime / (20.0f * 60 * 1000));
        int progressWidth = (int)(barWidth * progress);
        
        // Цвет прогресс-бара
        int color = progress < 0.5f ? 0xFF00FF00 : progress < 0.8f ? 0xFFFFFF00 : 0xFFFF0000;
        ctx.fill(barX, barY, barX + progressWidth, barY + barHeight, color);
        
        // Рамка
        ctx.drawBorder(barX, barY, barWidth, barHeight, 0xFF404040);
        
        y += 20;
        
        // Остальной контент
        String[][] sections = sectionsTimers();
        for (String[] block : sections) {
            y = section(ctx, x, y, block[0], java.util.Arrays.copyOfRange(block, 1, block.length));
        }
        
        return y;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (tab != 5) return super.mouseClicked(mouseX, mouseY, button);
        int contentX = PADDING;
        int contentY = PADDING + 24 + 8; // начало контента
        int y = contentY - scroll + 18; // ниже заголовка
        String[] steps = checklistSteps();
        if (checklistDone == null || checklistDone.length != steps.length) checklistDone = new boolean[steps.length];
        for (int i = 0; i < steps.length; i++) {
            int lineY = y + i * 14;
            if (mouseY >= lineY && mouseY <= lineY + 12 && mouseX >= contentX && mouseX <= this.width - PADDING) {
                if (button == 1) { // ПКМ — сброс всего
                    java.util.Arrays.fill(checklistDone, false);
                } else {
                    checklistDone[i] = !checklistDone[i];
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}


