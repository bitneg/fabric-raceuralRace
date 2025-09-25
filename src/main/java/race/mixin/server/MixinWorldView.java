package race.mixin.server;

import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.WorldView;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldView.class)
public interface MixinWorldView {

    @Inject(method = "getBiomeForNoiseGen", at = @At("HEAD"), cancellable = true)
    private void race$fastBiome(int biomeX, int biomeY, int biomeZ, CallbackInfoReturnable<RegistryEntry<Biome>> cir) {
        WorldView self = (WorldView) this;
        if (!(self instanceof ServerWorld serverWorld)) return;
        String key = serverWorld.getRegistryKey().getValue().toString();
        if (!key.startsWith("fabric_race:")) return;
        RegistryEntry<Biome> plains = serverWorld.getRegistryManager().get(RegistryKeys.BIOME).entryOf(BiomeKeys.PLAINS);
        cir.setReturnValue(plains);
    }


    @Inject(method = "getChunk(IILnet/minecraft/world/chunk/ChunkStatus;)Lnet/minecraft/world/chunk/Chunk;", at = @At("HEAD"), cancellable = true)
    private void race$nullChunk3(int chunkX, int chunkZ, net.minecraft.world.chunk.ChunkStatus status, CallbackInfoReturnable<net.minecraft.world.chunk.Chunk> cir) {
        WorldView self = (WorldView) this;
        if (!(self instanceof ServerWorld serverWorld)) return;
        String key = serverWorld.getRegistryKey().getValue().toString();
        if (!key.startsWith("fabric_race:")) return;
        // Не вмешиваемся в рабочие потоки генерации — пусть генератор получит настоящий чанк
        if (!"Server thread".equals(Thread.currentThread().getName())) return;
        // Для главного треда: если чанк не загружен, возвращаем null (не вызывать sync load)
        if (!self.isChunkLoaded(chunkX, chunkZ)) {
            cir.setReturnValue(null);
        }
    }
}


