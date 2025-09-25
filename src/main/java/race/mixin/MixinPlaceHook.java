package race.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import race.server.RaceServerInit;

@Mixin(ServerPlayerInteractionManager.class)
public abstract class MixinPlaceHook {
    @Inject(method = "interactBlock", at = @At("HEAD"), cancellable = true)
    private void race$placeOverlay(ServerPlayerEntity player,
                                   World world,
                                   ItemStack stack,
                                   Hand hand,
                                   BlockHitResult hit,
                                   CallbackInfoReturnable<ActionResult> cir) {
        if (!RaceServerInit.isActive()) return;

        if (stack.getItem() instanceof BlockItem bi) {
            ItemPlacementContext ctx = new ItemPlacementContext(player, hand, stack, hit);
            BlockPos placePos = ctx.getBlockPos();
            BlockState placeState = bi.getBlock().getPlacementState(ctx);
            if (placeState == null) return;
            // В этой версии мы не прерываем ванильную установку блока
        }
    }
}
