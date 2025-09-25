package race.client.ui;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import race.net.SeedHandshakeC2SPayload;

public final class SelectSeedScreen extends Screen {
    private TextFieldWidget input;
    private final Runnable onSent;

    public SelectSeedScreen(Runnable onSent) {
        super(Text.literal("Select Seed"));
        this.onSent = onSent;
    }

    @Override
    protected void init() {
        int w = 200, h = 20, x = this.width / 2 - w / 2, y = this.height / 2 - 30;
        input = new TextFieldWidget(this.textRenderer, x, y, w, h, Text.literal(""));
        input.setPlaceholder(Text.literal("Enter seed (long)"));
        addDrawableChild(input);
        addDrawableChild(ButtonWidget.builder(Text.literal("Use seed"), b -> send())
                .dimensions(x, y + 30, w, h).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Random"), b ->
                        input.setText(Long.toString(new java.util.Random().nextLong())))
                .dimensions(x, y + 60, w, h).build());
    }

    private void send() {
        try {
            long seed = Long.parseLong(input.getText().trim());
            ClientPlayNetworking.send(new SeedHandshakeC2SPayload(seed));
            if (onSent != null) onSent.run();
            // ВАЖНО: не закрываем экран здесь — ждём ACK от сервера
        } catch (Exception ignored) {
            input.setText("");
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);
        super.render(ctx, mouseX, mouseY, delta);
    }
}
