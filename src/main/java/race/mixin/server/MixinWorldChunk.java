package race.mixin.server;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldChunk.class)
public class MixinWorldChunk {

    @Inject(method = "runPostProcessing", at = @At("HEAD"), cancellable = true)
    private void race$skipChunkPostProcessing(CallbackInfo ci) {
        WorldChunk self = (WorldChunk) (Object) this;
        if (!(self.getWorld() instanceof ServerWorld serverWorld)) return;
        String key = serverWorld.getRegistryKey().getValue().toString();
        if (key.startsWith("fabric_race:")) {
            // Восстанавливаем пост-обработку для корректного освещения и высотных карт
            // Больше не отменяем - это нужно для спавна монстров
        }
    }
}


