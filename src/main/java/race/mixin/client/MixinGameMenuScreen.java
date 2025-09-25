package race.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import race.client.ui.RaceMenuScreen;

@Mixin(GameMenuScreen.class)
public abstract class MixinGameMenuScreen extends Screen {
    protected MixinGameMenuScreen(Text title) { super(title); }

    // Отключаем блюр/потемнение для in-game меню, чтобы HUD/фон были чёткими
    @Override
    protected void applyBlur(float delta) {}

    @Override
    protected void renderDarkening(DrawContext context) {}

    @Inject(method = "initWidgets", at = @At("RETURN"))
    private void race$addButton(CallbackInfo ci) {
        int x = this.width / 2 - 102;
        int y = 24; // под первой кнопкой
        ButtonWidget.Builder builder = ButtonWidget.builder(Text.literal("Race"), b -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            // Разрешаем открывать HUD/меню и в одиночной игре
            mc.setScreen(new RaceMenuScreen((Screen)(Object)this));
        }).dimensions(x, y, 204, 20);
        builder.tooltip(Tooltip.of(Text.literal("Открыть меню гонки: выбор сида, список игроков, Race/Join")));
        this.addDrawableChild(builder.build());
    }
}


