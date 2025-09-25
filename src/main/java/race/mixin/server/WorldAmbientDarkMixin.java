package race.mixin.server;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Миксин для пересчёта ambient darkness по виртуальному времени SLOT_TIME.
 * Обеспечивает корректное определение ночи для спавна монстров.
 */
@Mixin(World.class)
public abstract class WorldAmbientDarkMixin {

    @Inject(method = "calculateAmbientDarkness", at = @At("HEAD"), cancellable = true)
    private void race$ambientBySlotTime(CallbackInfo ci) {
        World self = (World) (Object) this;
        if (!(self instanceof ServerWorld w)) return;
        
        String id = w.getRegistryKey().getValue().toString();
        if (!id.startsWith("fabric_race:")) return;
        
        // Берём градиенты дождя/грозы из мира как есть (они у вас стабильны)
        float rain = self.getRainGradient(1.0F);
        float thunder = self.getThunderGradient(1.0F);
        double d = 1.0 - (rain * 5.0) / 16.0;
        double e = 1.0 - (thunder * 5.0) / 16.0;

        // Угол солнца по виртуальному времени
        long t = race.server.SlotTimeService.getTime(w.getRegistryKey());
        float f = ((float)(t % 24000L) + 1.0F) / 24000.0F; // tickDelta≈1
        float g = f - 0.25F;
        if (g < 0) g += 1.0F;
        if (g > 1) g -= 1.0F;
        float h = 1.0F - ((float)Math.cos(g * (float)Math.PI) + 1.0F) / 2.0F;
        float sky = g + (h - g) / 3.0F;

        // Ванильная формула: ambient = (1 - (0.5 + 2 * clamp(cos(..), -0.25..0.25))) * 11
        double cosClamp = MathHelper.clamp(Math.cos(sky * ((float)Math.PI * 2F)), -0.25D, 0.25D);
        double daylight = 0.5D + 2.0D * cosClamp;
        int ambient = (int)((1.0D - daylight * d * e) * 11.0D);

        // Устанавливаем ambient darkness через рефлексию
        try {
            java.lang.reflect.Field fld = net.minecraft.world.World.class.getDeclaredField("ambientDarkness");
            fld.setAccessible(true);
            fld.setInt(self, ambient);
        } catch (Throwable ignored) {}


        ci.cancel(); // мы полностью заменили расчёт
    }
}
