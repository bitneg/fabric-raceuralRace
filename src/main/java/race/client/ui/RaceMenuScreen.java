package race.client.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import race.net.SeedLobbyListPayload;
import race.net.SeedLobbyEntry;

import java.util.List;

public class RaceMenuScreen extends Screen {
	private TextFieldWidget seedField;
	private final Screen parent;
    private List<SeedLobbyEntry> lobby = java.util.List.of();
    private final java.util.List<ButtonWidget> lobbyButtons = new java.util.ArrayList<>();
    private int page = 0;
    private Double originalMenuBlur = null;
    private ButtonWidget startButton;
    private ButtonWidget cancelButton;
    private ButtonWidget ghostsButton;
    private static boolean ghostsOn = true;
    private static boolean hasActiveJoinRequest = false;
    private static String joinTargetPlayer = "";

	public RaceMenuScreen(Screen parent) {
		super(Text.literal("Race"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		int y = this.height / 3;
		
		// ИСПРАВЛЕНИЕ: Синхронизируем состояние призраков с сервером при открытии меню
		if (this.client != null && this.client.player != null) {
			this.client.player.networkHandler.sendChatCommand("race ghosts status");
		}
		// Временно отключаем размытие фона меню через accessor, если поле существует
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
		seedField = new TextFieldWidget(this.textRenderer, centerX - 100, y, 200, 20, Text.literal("seed"));
		if (this.client != null && this.client.player != null) {
			seedField.setText(String.valueOf(generateVanillaLikeSeed()));
		}
		this.addSelectableChild(seedField);

		this.addDrawableChild(ButtonWidget.builder(Text.literal("Ready"), b -> onReady()).dimensions(centerX - 150, y + 30, 95, 20).build());
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Random"), b -> onRandom()).dimensions(centerX - 50, y + 30, 95, 20).build());
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> onClose()).dimensions(centerX + 50, y + 30, 95, 20).build());

		// Return / Start (group leader) / Hub
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Return"), b -> onReturn()).dimensions(centerX - 50, y + 60, 100, 20).build());
		startButton = ButtonWidget.builder(Text.literal("Start"), b -> onStart()).dimensions(centerX + 50, y + 60, 95, 20).build();
		if (isLeaderForCurrentSeed()) this.addDrawableChild(startButton);
		cancelButton = ButtonWidget.builder(Text.literal("Cancel Join"), b -> onCancelJoin()).dimensions(centerX + 50, y + 60, 95, 20).build();
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Hub"), b -> onHub()).dimensions(centerX - 150, y + 60, 95, 20).build());
		// Кнопка принятия приглашения в команду
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Accept Invite"), b -> {
			trySend("/race team accept");
		}).dimensions(centerX - 150, y + 80, 145, 20).build());
        // Переключатель призраков параллельных игроков
		ghostsButton = ButtonWidget.builder(Text.literal("Призраки: "+ (ghostsOn ? "ВКЛ" : "ВЫКЛ")), b -> onToggleGhosts())
				.dimensions(centerX + 50, y + 80, 145, 20).build();
        this.addDrawableChild(ghostsButton);

        rebuildLobbyButtons();
        
        // Отправляем запрос списка лобби
        trySend("/race lobbylist");
        page = 0;
        lobby = java.util.List.of();
        rebuildLobbyButtons();
	}

	private void onReady() {
		// ИСПРАВЛЕНИЕ: Проверяем, не находится ли игрок уже в персональном мире
		if (this.client != null && this.client.player != null) {
			String currentWorld = this.client.player.getWorld().getRegistryKey().getValue().toString();
			if (currentWorld.startsWith("fabric_race:")) {
				// Игрок уже в персональном мире - не инициализируем гонку заново
				this.client.player.sendMessage(net.minecraft.text.Text.literal("Вы уже в персональном мире! Используйте /race join для присоединения к другим игрокам.").formatted(net.minecraft.util.Formatting.YELLOW), false);
				this.close();
				return;
			}
		}
		// ИСПРАВЛЕНИЕ: Создаем новый персональный мир с выбранным сидом (становимся лидером группы)
		trySend("/race seed " + seedField.getText());
		trySend("/race ready");
		this.close();
	}

	private void onRandom() {
		seedField.setText(Long.toString(generateVanillaLikeSeed()));
	}
	
	private long generateVanillaLikeSeed() {
		java.util.Random random = new java.util.Random();
		// ИСПРАВЛЕНИЕ: Разрешаем отрицательные сиды как в ванильном Minecraft
		// 50% шанс на отрицательный сид для разнообразия
		if (random.nextBoolean()) {
			// Генерируем отрицательный сид
			return -random.nextLong();
		} else {
			// Генерируем положительный сид
			return random.nextLong();
		}
	}

	private void onClose() {
		if (this.client != null) this.client.setScreen(parent);
	}

	private void onReturn() {
		trySend("/race return");
		this.close();
	}

	private void onStart() {
		// ИСПРАВЛЕНИЕ: Лидер группы запускает гонку через команду go (personalGo на сервере)
		trySend("/race go");
		this.close();
	}

	private void onHub() {
		trySend("/race lobby");
		this.close();
	}

    private void onToggleGhosts() {
        ghostsOn = !ghostsOn;
        trySend(ghostsOn ? "/race ghosts on" : "/race ghosts off");
        updateGhostsButton();
    }

	private void trySend(String cmd) {
		MinecraftClient mc = this.client;
		if (mc != null && mc.player != null) {
			mc.player.networkHandler.sendChatCommand(cmd.substring(1));
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		// прозрачный фон без размытия — как у инвентаря
		int centerX = this.width / 2;
		int topY = this.height / 3;

		context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, topY - 20, 0xFFFFFF);
		seedField.render(context, mouseX, mouseY, delta);

		// список игроков/сидов — ниже кнопок Accept/Призраки
		int startY = this.height / 3 + 140;
		int x = this.width / 2 - 140;
		int buttonsX = this.width / 2 + 100; // такое же, как в rebuildLobbyButtons
		int listWidth = Math.max(120, buttonsX - x - 8);
		// фон под списком, чтобы текст не "терялся" и не уезжал под кнопки
		// Даем запас по высоте, чтобы многострочные записи не выходили за фон
		int perPage = 8;
		int listBottom = Math.min(this.height - 8, startY + 12 + 28 * perPage * 2);
		context.fill(x - 6, startY - 6, x + listWidth + 2, listBottom, 0x66000000);
		context.drawBorder(x - 6, startY - 6, listWidth + 8, (listBottom - (startY - 6)), 0xAA404040);

		context.drawText(this.textRenderer, Text.literal("Players/Seeds:"), x, startY, 0xFFFFFF, false);
		int y = startY + 12;
		if (lobby.isEmpty()) {
			context.drawText(this.textRenderer, Text.literal("Нет игроков в лобби"), x, y + 3, 0xBBBBBB, false);
		}
		int from = Math.min(page * perPage, Math.max(0, lobby.size()));
		int to = Math.min(from + perPage, lobby.size());
		for (int i = from; i < to; i++) {
			SeedLobbyEntry e = lobby.get(i);
			String line1 = (i + 1) + ". " + e.playerName() + " — " + e.seed();
			String line2 = "[" + e.worldKey() + "]";
			context.drawText(this.textRenderer, Text.literal(line1), x, y + 3, 0xFFFFFF, false);
			// Переносим worldKey на следующую строку с автоматическим переносом по ширине
			java.util.List<net.minecraft.text.OrderedText> wrapped = this.textRenderer.wrapLines(Text.literal(line2), listWidth);
			int yy = y + 3 + 12;
			for (var ot : wrapped) {
				context.drawText(this.textRenderer, ot, x, yy, 0xA0A0A0, false);
				yy += 12;
			}
			y = yy + 6; // отступ между записями
		}
		// номера страниц
		if (lobby.size() > perPage) {
			int pages = (lobby.size() + perPage - 1) / perPage;
			String p = (page + 1) + "/" + pages;
			context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(p), this.width / 2, y + 2, 0xCCCCCC);
		}
		super.render(context, mouseX, mouseY, delta);
	}

	@Override
	public boolean shouldPause() {
		return false; // не ставим игру на паузу (как инвентарь)
	}

    @Override
    public void removed() {
        // Восстанавливаем размытие, если меняли
        try {
            if (originalMenuBlur != null && this.client != null && this.client.options != null) {
                race.mixin.client.GameOptionsAccessor acc = (race.mixin.client.GameOptionsAccessor) (Object) this.client.options;
                net.minecraft.client.option.SimpleOption<Integer> opt = acc.getMenuBackgroundBlurriness_FAB();
                if (opt != null) opt.setValue(originalMenuBlur.intValue());
            }
        } catch (Throwable ignored) {}
        
        // ИСПРАВЛЕНИЕ: Сбрасываем статические переменные при закрытии экрана
        resetStaticState();
        
        super.removed();
    }

    public void setLobby(List<SeedLobbyEntry> list) {
        this.lobby = list;
        if (this.client != null) {
            this.rebuildLobbyButtons();
			this.updateStartButton();
			this.updateCancelButton();
        }
    }

    private void rebuildLobbyButtons() {
        // удалить старые кнопки
        for (ButtonWidget b : lobbyButtons) this.remove(b);
        lobbyButtons.clear();

        // ИСПРАВЛЕНИЕ: Точное выравнивание кнопок с элементами списка
        int listStartY = this.height / 3 + 140;
        int startY = listStartY + 18; // после заголовка "Players/Seeds:"
        int buttonsX = this.width / 2 + 100; // кнопки правее от центра
        int perPage = 8;
        int from = Math.min(page * perPage, Math.max(0, lobby.size()));
        int to = Math.min(from + perPage, lobby.size());
        
        // ИСПРАВЛЕНИЕ: Используем тот же алгоритм позиционирования, что и в render()
        for (int idx = from; idx < to; idx++) {
            SeedLobbyEntry e = lobby.get(idx);
            int i = idx - from;
            
            // Точное позиционирование как в render() - каждая запись занимает разную высоту
            int y = startY + 3; // базовая позиция первой записи
            for (int j = 0; j < i; j++) {
                // Вычисляем высоту каждой предыдущей записи
                SeedLobbyEntry prevEntry = lobby.get(from + j);
                String line2 = "[" + prevEntry.worldKey() + "]";
                java.util.List<net.minecraft.text.OrderedText> wrapped = this.textRenderer.wrapLines(Text.literal(line2), 120);
                y += 12 + 12 + 6; // высота первой строки + высота второй строки + отступ
                y += (wrapped.size() - 1) * 12; // дополнительные строки если текст переносится
            }
            // ИСПРАВЛЕНИЕ: Позиционируем кнопки точно по центру текста записи
            int buttonY = y + 6; // центрируем кнопки относительно текста
            
            ButtonWidget raceBtn = ButtonWidget.builder(Text.literal("Race"), b -> {
                // ИСПРАВЛЕНИЕ: Проверяем, не находится ли игрок уже в персональном мире
                if (this.client != null && this.client.player != null) {
                    String currentWorld = this.client.player.getWorld().getRegistryKey().getValue().toString();
                    if (currentWorld.startsWith("fabric_race:")) {
                        // Игрок уже в персональном мире - не инициализируем гонку заново
                        this.client.player.sendMessage(net.minecraft.text.Text.literal("Вы уже в персональном мире! Используйте /race join для присоединения к другим игрокам.").formatted(net.minecraft.util.Formatting.YELLOW), false);
                        this.close();
                        return;
                    }
                }
                // ИСПРАВЛЕНИЕ: При нажатии "Race" создаем параллельный мир с тем же сидом но на свободном слоте
                trySend("/race seed " + e.seed()); // Устанавливаем тот же сид
                trySend("/race parallel " + e.playerName()); // Новая команда для параллельной гонки
                this.close();
            }).dimensions(buttonsX, buttonY, 60, 20).build();
            ButtonWidget joinBtn = ButtonWidget.builder(Text.literal("Join"), b -> {
                // ИСПРАВЛЕНИЕ: Используем правильную команду для присоединения
                trySend("/race join " + e.playerName());
                this.close();
            }).dimensions(buttonsX + 65, buttonY, 60, 20).build();
            ButtonWidget spectBtn = ButtonWidget.builder(Text.literal("Spectate"), b -> {
                trySend("/race spectate " + e.playerName());
                this.close();
            }).dimensions(buttonsX + 130, buttonY, 70, 20).build();
            ButtonWidget inviteBtn = ButtonWidget.builder(Text.literal("Invite Team"), b -> {
                trySend("/race team invite " + e.playerName());
            }).dimensions(buttonsX + 205, buttonY, 90, 20).build();
            // Скрываем Spectate для самого себя
            boolean isSelf = false;
            try {
                isSelf = this.client != null && this.client.player != null && this.client.player.getGameProfile().getName().equals(e.playerName());
            } catch (Throwable ignored) {}
            if (!isSelf) this.addDrawableChild(raceBtn);
            if (!isSelf) this.addDrawableChild(joinBtn);
            if (!isSelf) this.addDrawableChild(spectBtn);
            if (!isSelf) this.addDrawableChild(inviteBtn);
            if (!isSelf) lobbyButtons.add(raceBtn);
            if (!isSelf) lobbyButtons.add(joinBtn);
            if (!isSelf) lobbyButtons.add(spectBtn);
            if (!isSelf) lobbyButtons.add(inviteBtn);
        }

        // Кнопки страниц
        if (lobby.size() > perPage) {
            int y = startY + perPage * 24 + 4;
            ButtonWidget prev = ButtonWidget.builder(Text.literal("<"), b -> { if (page > 0) { page--; rebuildLobbyButtons(); } })
                    .dimensions(buttonsX, y, 24, 20).build();
            ButtonWidget next = ButtonWidget.builder(Text.literal(">"), b -> {
                        int pages = (lobby.size() + perPage - 1) / perPage; if (page+1 < pages) { page++; rebuildLobbyButtons(); }
                    }).dimensions(buttonsX + 31, y, 24, 20).build();
            this.addDrawableChild(prev); this.addDrawableChild(next);
            lobbyButtons.add(prev); lobbyButtons.add(next);
        }
    }

	private boolean isLeaderForCurrentSeed() {
		if (this.client == null || this.client.getSession() == null) return false;
		String me = this.client.getSession().getUsername();
		long mySeed = -1L;
		for (SeedLobbyEntry e : lobby) {
			if (e.playerName().equals(me)) { mySeed = e.seed(); break; }
		}
		if (mySeed < 0) return false;
		for (SeedLobbyEntry e : lobby) {
			if (e.seed() == mySeed) {
				return e.playerName().equals(me);
			}
		}
		return false;
	}

	private void updateStartButton() {
		if (startButton == null) return;
		boolean shouldShow = isLeaderForCurrentSeed();
		try { this.remove(startButton); } catch (Throwable ignored) {}
		if (shouldShow) this.addDrawableChild(startButton);
	}
	
	private void updateCancelButton() {
		if (cancelButton == null) return;
		boolean shouldShow = hasActiveJoinRequest();
		if (shouldShow && !joinTargetPlayer.isEmpty()) {
			cancelButton.setMessage(Text.literal("Cancel Join " + joinTargetPlayer));
		}
		try { this.remove(cancelButton); } catch (Throwable ignored) {}
		if (shouldShow) this.addDrawableChild(cancelButton);
	}

    private void updateGhostsButton() {
        if (ghostsButton != null) ghostsButton.setMessage(Text.literal("Призраки: " + (ghostsOn ? "ВКЛ" : "ВЫКЛ")));
    }
    
    private boolean hasActiveJoinRequest() {
        return hasActiveJoinRequest;
    }
    
    public void setJoinRequestStatus(boolean hasRequest, String targetPlayer) {
        hasActiveJoinRequest = hasRequest;
        joinTargetPlayer = targetPlayer;
        if (this.client != null) {
            this.updateCancelButton();
        }
    }
    
    private void onCancelJoin() {
        trySend("/race cancel");
    }
    
    /**
     * Сбрасывает статическое состояние экрана
     */
    private static void resetStaticState() {
        hasActiveJoinRequest = false;
        joinTargetPlayer = "";
    }
    
    /**
     * Принудительный сброс состояния (для вызова извне)
     */
    public static void forceResetState() {
        resetStaticState();
    }
}
