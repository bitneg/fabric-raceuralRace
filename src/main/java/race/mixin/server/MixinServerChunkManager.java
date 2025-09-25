package race.mixin.server;

import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerChunkManager.class)
public abstract class MixinServerChunkManager {

    @Shadow @Final private ServerWorld world;

    @Inject(method = "tickChunks", at = @At("HEAD"), cancellable = true)
    private void race$skipTickChunks(CallbackInfo ci) {
        String key = this.world.getRegistryKey().getValue().toString();
        if (key.startsWith("fabric_race:")) {
            // Пропускаем спавн мобов и другие тик-операции чанков, чтобы не триггерить синхронные обращения к структурам/чанкам
            ci.cancel();
        }
    }
}


