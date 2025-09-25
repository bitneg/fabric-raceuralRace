package race.mixin.vanillafix;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.FlintAndSteelItem;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.dimension.NetherPortal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FlintAndSteelItem.class)
public abstract class MixinFlintAndSteelItem {

    @Inject(method = "useOnBlock", at = @At("TAIL"))
    private void race$afterUse(ItemUsageContext ctx, CallbackInfoReturnable<ActionResult> cir) {
        World world = ctx.getWorld();
        if (world.isClient()) return;
        ServerWorld sw = (ServerWorld) world;
        // Ограничиваем нашу логику только персональными мирами, в остальных оставляем ваниллу
        try {
            var key = sw.getRegistryKey().getValue();
            if (key == null || !"fabric_race".equals(key.getNamespace())) return;
        } catch (Throwable ignored) {}
        PlayerEntity player = ctx.getPlayer();
        BlockPos pos = ctx.getBlockPos().offset(ctx.getSide());
        try {
            // Доверяем ванильному PortalForcer: создаём портал только при валидной рамке
            race$mTryNearby(sw, pos);
        } catch (Throwable ignored) {}
    }

    private static boolean race$mTryNearby(ServerWorld sw, BlockPos base) {
        // Пытаемся найти валидную рамку 2x3..4x5 вокруг точки и зажечь портал вручную
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

    // Проверка рамки 2x3..4x5 без углов и заполнение порталом
    private static boolean race$tryFlexibleFrame(ServerWorld w, BlockPos interiorBL, Direction.Axis axis) {
        Direction up = Direction.UP;
        Direction left = (axis == Direction.Axis.X) ? Direction.NORTH : Direction.EAST;
        // width: 2..4, height: 3..5 (внутренние размеры)
        for (int width = 2; width <= 4; width++) {
            for (int height = 3; height <= 5; height++) {
                // Проверяем рамку
                boolean ok = true;
                // низ рамки (угловые блоки не обязательны)
                for (int dx = 0; dx <= width - 1; dx++) {
                    BlockPos p = interiorBL.offset(left, dx).offset(Direction.DOWN);
                    if (!w.getBlockState(p).isOf(net.minecraft.block.Blocks.OBSIDIAN)) { ok = false; break; }
                }
                if (!ok) continue;
                // вертикальные стороны
                for (int y = 0; y <= height - 1 && ok; y++) {
                    BlockPos lp = interiorBL.offset(left, -1).offset(up, y);
                    BlockPos rp = interiorBL.offset(left, width).offset(up, y);
                    if (!w.getBlockState(lp).isOf(net.minecraft.block.Blocks.OBSIDIAN)) { ok = false; break; }
                    if (!w.getBlockState(rp).isOf(net.minecraft.block.Blocks.OBSIDIAN)) { ok = false; break; }
                }
                if (!ok) continue;
                // верх рамки (углы опциональны)
                for (int dx = 0; dx <= width - 1; dx++) {
                    BlockPos p = interiorBL.offset(left, dx).offset(up, height);
                    if (!w.getBlockState(p).isOf(net.minecraft.block.Blocks.OBSIDIAN)) { ok = false; break; }
                }
                if (!ok) continue;
                // внутренняя полость
                for (int dx = 0; dx <= width - 1 && ok; dx++) {
                    for (int y = 0; y <= height - 1; y++) {
                        BlockPos ip = interiorBL.offset(left, dx).offset(up, y);
                        var bs = w.getBlockState(ip);
                        if (!(bs.isAir() || bs.isOf(net.minecraft.block.Blocks.FIRE))) { ok = false; break; }
                    }
                }
                if (!ok) continue;
                // Заполняем портал с правильной осью полотна (совпадает с осью рамки)
                net.minecraft.block.BlockState portal = net.minecraft.block.Blocks.NETHER_PORTAL.getDefaultState()
                    .with(net.minecraft.block.NetherPortalBlock.AXIS, axis);
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
}



