package race.mixin.vanillafix;

import net.minecraft.block.BlockState;
import net.minecraft.block.FireBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.dimension.NetherPortal;
import net.minecraft.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Даем огню инициировать сборку портала в персональных мирах:
 * после установки огня пробуем создать портал через PortalForcer.
 */
@Mixin(FireBlock.class)
public abstract class MixinFireBlock {

    @Inject(method = "onBlockAdded", at = @At("TAIL"))
    private void race$tryNetherPortal(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify, CallbackInfo ci) {
        if (!(world instanceof ServerWorld sw)) return;
        // Только персональные миры
        try {
            var key = sw.getRegistryKey().getValue();
            if (key == null || !"fabric_race".equals(key.getNamespace())) return;
        } catch (Throwable ignored) {}
        try {
            // Только доверяем валидной рамке вокруг
            race$mTryNearby(sw, pos);
        } catch (Throwable ignored) {}
    }

    @Inject(method = "scheduledTick", at = @At("HEAD"))
    private void race$tryNetherPortalTick(BlockState state, ServerWorld world, BlockPos pos, net.minecraft.util.math.random.Random random, CallbackInfo ci) {
        // Только персональные миры
        try {
            var key = world.getRegistryKey().getValue();
            if (key == null || !"fabric_race".equals(key.getNamespace())) return;
        } catch (Throwable ignored) {}
        try {
            race$mTryNearby(world, pos);
        } catch (Throwable ignored) {}
    }

    private static boolean race$mTryNearby(ServerWorld sw, BlockPos base) {
        int[] dy = new int[]{-1, 0, 1, 2, 3};
        int[] off = new int[]{-2, -1, 0, 1, 2};
        for (int y : dy) {
            for (int ox : off) for (int oz : off) {
                BlockPos p = base.add(ox, y, oz);
                if (race$tryFlexibleFrame(sw, p, Direction.Axis.X)) return true;
                if (race$tryFlexibleFrame(sw, p, Direction.Axis.Z)) return true;
            }
        }
        return false;
    }

    // Проверяем валидную рамку 2x3..4x5 и создаём портал вручную
    private static boolean race$tryFlexibleFrame(ServerWorld w, BlockPos interiorBL, Direction.Axis axis) {
        Direction up = Direction.UP;
        Direction left = (axis == Direction.Axis.X) ? Direction.NORTH : Direction.EAST;
        for (int width = 2; width <= 4; width++) {
            for (int height = 3; height <= 5; height++) {
                boolean ok = true;
                for (int dx = 0; dx <= width - 1; dx++) {
                    BlockPos p = interiorBL.offset(left, dx).offset(Direction.DOWN);
                    if (!w.getBlockState(p).isOf(Blocks.OBSIDIAN)) { ok = false; break; }
                }
                if (!ok) continue;
                for (int y = 0; y <= height - 1 && ok; y++) {
                    BlockPos lp = interiorBL.offset(left, -1).offset(up, y);
                    BlockPos rp = interiorBL.offset(left, width).offset(up, y);
                    if (!w.getBlockState(lp).isOf(Blocks.OBSIDIAN)) { ok = false; break; }
                    if (!w.getBlockState(rp).isOf(Blocks.OBSIDIAN)) { ok = false; break; }
                }
                if (!ok) continue;
                for (int dx = 0; dx <= width - 1; dx++) {
                    BlockPos p = interiorBL.offset(left, dx).offset(up, height);
                    if (!w.getBlockState(p).isOf(Blocks.OBSIDIAN)) { ok = false; break; }
                }
                if (!ok) continue;
                for (int dx = 0; dx <= width - 1 && ok; dx++) {
                    for (int y = 0; y <= height - 1; y++) {
                        BlockPos ip = interiorBL.offset(left, dx).offset(up, y);
                        var bs = w.getBlockState(ip);
                        if (!(bs.isAir() || bs.isOf(Blocks.FIRE))) { ok = false; break; }
                    }
                }
                if (!ok) continue;
                net.minecraft.block.BlockState portal = Blocks.NETHER_PORTAL.getDefaultState().with(net.minecraft.state.property.Properties.HORIZONTAL_AXIS, axis);
                for (int dx = 0; dx <= width - 1; dx++) {
                    for (int y = 0; y <= height - 1; y++) {
                        BlockPos ip = interiorBL.offset(left, dx).offset(up, y);
                        w.setBlockState(ip, portal, net.minecraft.block.Block.NOTIFY_ALL);
                    }
                }
                return true;
            }
        }
        return false;
    }

    // убрали ручной фолбэк — оставляем ванильную логику PortalForcer
}


