package race.mixin.server;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkTicketManager.class)
public abstract class MixinChunkTicketManager {

    @Shadow @Final private Long2ObjectMap<ObjectSet<ServerPlayerEntity>> playersByChunkPos;

    // Безопасно обрабатываем уход игрока: если набора нет, просто выходим
    @Inject(method = "handleChunkLeave", at = @At("HEAD"), cancellable = true)
    private void race$safeHandleChunkLeave(ChunkSectionPos pos, ServerPlayerEntity player, CallbackInfo ci) {
        long key = pos.toChunkPos().toLong();
        ObjectSet<ServerPlayerEntity> set = this.playersByChunkPos.get(key);
        if (set == null) {
            ci.cancel();
        }
    }
}


