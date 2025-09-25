package race.client.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import race.client.hub.RaceHubState;

public final class RaceHubScreen extends Screen {
    private TextFieldWidget ipField;
    private TextFieldWidget seedField;

    public RaceHubScreen() {
        super(Text.literal("Race Hub"));
    }

    @Override
    protected void init() {
        int w = 220, h = 20;
        int x = this.width / 2 - w / 2;
        int y = this.height / 2 - 50;

        ipField = new TextFieldWidget(this.textRenderer, x, y, w, h, Text.literal(""));
        ipField.setPlaceholder(Text.literal("server:port (например, localhost:25565)"));
        addDrawableChild(ipField);

        seedField = new TextFieldWidget(this.textRenderer, x, y + 30, w, h, Text.literal(""));
        seedField.setPlaceholder(Text.literal("Введите сид (long)"));
        addDrawableChild(seedField);

        addDrawableChild(ButtonWidget.builder(Text.literal("Случайный сид"), b ->
                seedField.setText(Long.toString(new java.util.Random().nextLong())))
            .dimensions(x, y + 60, w, h).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Подключиться"), b -> connect())
            .dimensions(x, y + 90, w, h).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Назад"), b -> close())
            .dimensions(x, y + 120, w, h).build());
    }

    private void connect() {
        try {
            long seed = Long.parseLong(seedField.getText().trim());
            RaceHubState.setPendingSeed(seed);
        } catch (Exception ignored) {
            RaceHubState.setPendingSeed(-1L);
        }

        String ip = ipField.getText().trim();
        if (ip.isEmpty()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        ServerInfo info = new ServerInfo("Race Server", ip, ServerInfo.ServerType.OTHER);
        ServerAddress addr = ServerAddress.parse(ip);
        ConnectScreen.connect(this, mc, addr, info, false, null);
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(null);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 80, 0xFFFFFF);
    }
}


