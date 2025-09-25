package race.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.server.network.ChunkDataSender;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import race.server.phase.PhaseService;
import race.server.phase.PhaseState;

import java.util.Objects;

@Mixin(ChunkDataSender.class)
abstract class MixinChunkDataSender {

    @Inject(method = "sendChunkData(Lnet/minecraft/server/network/ServerPlayNetworkHandler;Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/world/chunk/WorldChunk;)V",
            at = @At("HEAD"), cancellable = true)
    private static void race$patch(ServerPlayNetworkHandler handler, ServerWorld world, WorldChunk chunk, CallbackInfo ci) {

        ServerPlayerEntity player = handler.player;
        PhaseState st = PhaseState.get(Objects.requireNonNull(player.getServer()));
        PhaseState.PhaseData pd = st.of(player.getUuid());
        ChunkPos cpos = chunk.getPos();
        PhaseState.Overlay overlay = pd.chunkOverlays.get(ChunkPos.toLong(cpos.x, cpos.z));

        // Если нет overlay, отправляем обычный пакет
        if (overlay == null || overlay.blocks.isEmpty()) {
            ChunkDataS2CPacket packet = new ChunkDataS2CPacket(chunk, world.getLightingProvider(), null, null);
            handler.sendPacket(packet);
            ci.cancel();
            return;
        }

        // Получаем секции из чанка
        ChunkAccessor chunkAcc = (ChunkAccessor) (Object) chunk;
        ChunkSection[] originalSections = chunkAcc.race$getSectionArray();

        if (originalSections == null || originalSections.length == 0) {
            ChunkDataS2CPacket packet = new ChunkDataS2CPacket(chunk, world.getLightingProvider(), null, null);
            handler.sendPacket(packet);
            ci.cancel();
            return;
        }

        // Создаём копии секций для модификации
        ChunkSection[] modifiedSections = new ChunkSection[originalSections.length];
        for (int i = 0; i < originalSections.length; i++) {
            if (originalSections[i] == null) {
                modifiedSections[i] = null;
                continue;
            }
            PalettedContainer blocksCopy = originalSections[i].getBlockStateContainer().copy();
            var biomesSlice = originalSections[i].getBiomeContainer().slice();
            modifiedSections[i] = new ChunkSection(blocksCopy, biomesSlice);
        }

        int bottomSection = world.getBottomSectionCoord();

        // Применяем изменения overlay к копиям секций
        overlay.blocks.long2ObjectEntrySet().forEach(e -> {
            BlockPos bp = BlockPos.fromLong(e.getLongKey());
            int secY = bp.getY() >> 4;
            int idx = secY - bottomSection;
            if (idx < 0 || idx >= modifiedSections.length) return;
            ChunkSection sec = modifiedSections[idx];
            if (sec == null) return;
            BlockState state = PhaseService.nbtToStatePublic(e.getValue());
            PalettedContainer container = sec.getBlockStateContainer();
            container.set(bp.getX() & 15, bp.getY() & 15, bp.getZ() & 15, state);
        });

        try {
            // Временно подменяем секции в чанке (может упасть на final-поле в новых версиях)
            chunkAcc.race$setSectionArray(modifiedSections);
            // Создаём пакет с изменёнными секциями
            ChunkDataS2CPacket packet = new ChunkDataS2CPacket(chunk, world.getLightingProvider(), null, null);
            // Отправляем модифицированный пакет
            handler.sendPacket(packet);
            ci.cancel();
        } catch (Throwable t) {
            // Фолбэк: отправляем ванильный пакет, чтобы не крашить сервер
            ChunkDataS2CPacket fallback = new ChunkDataS2CPacket(chunk, world.getLightingProvider(), null, null);
            handler.sendPacket(fallback);
            ci.cancel();
        } finally {
            try { chunkAcc.race$setSectionArray(originalSections); } catch (Throwable ignored) {}
        }
    }
}
