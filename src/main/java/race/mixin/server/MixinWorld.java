package race.mixin.server;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.BlockView;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * В наших кастомных мирах fabric_race:* возвращаем AIR для незагруженных чанков
 * чтобы избежать блокирующих getChunk в getBlockState
 */
@Mixin(World.class)
public abstract class MixinWorld {

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void race$returnAirIfChunkMissing(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        World self = (World) (Object) this;
        if (!(self instanceof ServerWorld serverWorld)) return;
        String key = serverWorld.getRegistryKey().getValue().toString();
        if (!key.startsWith("fabric_race:")) return;
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        if (!serverWorld.getChunkManager().isChunkLoaded(cx, cz)) {
            cir.setReturnValue(Blocks.AIR.getDefaultState());
        }
    }

	@Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void race$returnEmptyFluidIfChunkMissing(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        World self = (World) (Object) this;
        if (!(self instanceof ServerWorld serverWorld)) return;
        String key = serverWorld.getRegistryKey().getValue().toString();
        if (!key.startsWith("fabric_race:")) return;
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        if (!serverWorld.getChunkManager().isChunkLoaded(cx, cz)) {
            cir.setReturnValue(Fluids.EMPTY.getDefaultState());
        }
    }

	@Inject(method = "getChunkAsView", at = @At("HEAD"), cancellable = true)
	private void race$chunkViewGuard(int chunkX, int chunkZ, CallbackInfoReturnable<BlockView> cir) {
		World self = (World) (Object) this;
		if (!(self instanceof ServerWorld serverWorld)) return;
		String key = serverWorld.getRegistryKey().getValue().toString();
		if (!key.startsWith("fabric_race:")) return;
		// Возвращаем null только если чанк НЕ загружен, иначе позволяем ванили вернуть BlockView
		if (!serverWorld.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
			cir.setReturnValue(null);
		}
	}

	// Прерываем setBlockState в незагруженных чанках, до обращения к getWorldChunk
	@Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z", at = @At("HEAD"), cancellable = true)
	private void race$guardSetBlockState3(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<Boolean> cir) {
		World self = (World) (Object) this;
		if (!(self instanceof ServerWorld serverWorld)) return;
		String key = serverWorld.getRegistryKey().getValue().toString();
		if (!key.startsWith("fabric_race:")) return;
		int cx = pos.getX() >> 4;
		int cz = pos.getZ() >> 4;
		if (!serverWorld.getChunkManager().isChunkLoaded(cx, cz)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z", at = @At("HEAD"), cancellable = true)
	private void race$guardSetBlockState4(BlockPos pos, BlockState state, int flags, int maxUpdateDepth, CallbackInfoReturnable<Boolean> cir) {
		World self = (World) (Object) this;
		if (!(self instanceof ServerWorld serverWorld)) return;
		String key = serverWorld.getRegistryKey().getValue().toString();
		if (!key.startsWith("fabric_race:")) return;
		int cx = pos.getX() >> 4;
		int cz = pos.getZ() >> 4;
		if (!serverWorld.getChunkManager().isChunkLoaded(cx, cz)) {
			cir.setReturnValue(false);
		}
	}

	// В персональных мирах отключаем World.markDirty, чтобы не вызывать getWorldChunk (блокирующий join)
	@Inject(method = "markDirty", at = @At("HEAD"), cancellable = true)
	private void race$skipMarkDirty(BlockPos pos, CallbackInfo ci) {
		World self = (World) (Object) this;
		if (!(self instanceof ServerWorld serverWorld)) return;
		String key = serverWorld.getRegistryKey().getValue().toString();
		if (!key.startsWith("fabric_race:")) return;
		ci.cancel();
	}
}
