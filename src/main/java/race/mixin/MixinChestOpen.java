package race.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import race.server.RaceServerInit;
import race.server.phase.PhaseState;
import race.server.phase.VirtualChestHandler;

@Mixin(ChestBlock.class)
abstract class MixinChestOpen {

    // Сигнатура строго совпадает с protected ActionResult onUse(BlockState, World, BlockPos, PlayerEntity, BlockHitResult)
    @Inject(method = "onUse(Lnet/minecraft/block/BlockState;Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/util/hit/BlockHitResult;)Lnet/minecraft/util/ActionResult;",
            at = @At("HEAD"), cancellable = true)
    private void race$openVirtual(BlockState state,
                                  World world,
                                  BlockPos pos,
                                  PlayerEntity player,
                                  BlockHitResult hit,
                                  CallbackInfoReturnable<ActionResult> cir) {
        if (!RaceServerInit.isActive()) return;
        if (!(player instanceof ServerPlayerEntity sp)) return;
        // Не перехватываем сундуки в персональных мирах fabric_race — там нужен ванильный лут
        try {
            var key = world.getRegistryKey().getValue();
            if (key != null && "fabric_race".equals(key.getNamespace())) return;
        } catch (Throwable ignored) {}

        var st = PhaseState.get(sp.getServer());
        var pd = st.of(sp.getUuid());
        var data = pd.containers.get(pos.asLong());
        if (data == null) data = new net.minecraft.nbt.NbtCompound();

        VirtualChestHandler.open(sp, pos, data);
        // Возвращаем серверный успех/consume, чтобы не запустить ванильное меню
        cir.setReturnValue(ActionResult.CONSUME);
    }
}
