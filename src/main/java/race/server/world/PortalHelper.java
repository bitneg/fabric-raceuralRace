package race.server.world;

import com.mojang.logging.LogUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.dimension.DimensionTypes;
import org.slf4j.Logger;

public final class PortalHelper {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static Direction.Axis pickAxis(ServerWorld w, BlockPos center) {
        boolean okX = hasSpaceForFrame(w, center, Direction.Axis.X);
        boolean okZ = hasSpaceForFrame(w, center, Direction.Axis.Z);
        if (okX && !okZ) return Direction.Axis.X;
        if (!okX && okZ) return Direction.Axis.Z;
        return Direction.Axis.X;
    }

    // стабилизация центра (chunk-anchor + scale 8)
    public static BlockPos stabilizedAnchor(boolean fromOW, BlockPos src) {
        int cx = (src.getX() & ~15) | 8;
        int cz = (src.getZ() & ~15) | 8;
        int ax = fromOW ? Math.floorDiv(cx, 8) : Math.multiplyExact(cx, 8);
        int az = fromOW ? Math.floorDiv(cz, 8) : Math.multiplyExact(cz, 8);
        return new BlockPos(ax, src.getY(), az);
    }

    public static BlockPos adjustSafeY(ServerWorld w, BlockPos p) {
        int min = w.getBottomY() + 32, max = w.getTopY() - 16;
        int y = Math.max(min, Math.min(max, p.getY()));
        return new BlockPos(p.getX(), y, p.getZ());
    }

    public static BlockPos findOrCreateLinkedPortal(ServerWorld world, BlockPos anchor, Direction.Axis axis) {
        BlockPos found = findExistingPortal(world, anchor, 128, 64); // широкий радиус
        if (found != null) return found;

        BlockPos base = findSafeGround(world, anchor);
        buildFrameWithAxis(world, base, axis);
        fillPortalWithAxis(world, base, axis);
        LOGGER.info("[Race] Created portal at {} axis={}", base, axis);
        return base.add(axis == Direction.Axis.X ? 1 : 0, 1, axis == Direction.Axis.Z ? 1 : 0); // центр проёма
    }

    private static BlockPos findExistingPortal(ServerWorld w, BlockPos a, int rxz, int ry) {
        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;
        for (int x = a.getX() - rxz; x <= a.getX() + rxz; x++) {
            for (int z = a.getZ() - rxz; z <= a.getZ() + rxz; z++) {
                // ИСПРАВЛЕНИЕ: Прогреваем чанк перед проверкой блоков
                try {
                    int cx = x >> 4, cz = z >> 4;
                    w.getChunk(cx, cz, net.minecraft.world.chunk.ChunkStatus.FULL, true);
                } catch (Throwable t) {
                    // Игнорируем ошибки загрузки чанков
                }
                
                for (int y = a.getY() - ry; y <= a.getY() + ry; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    try {
                        if (w.getBlockState(p).isOf(Blocks.NETHER_PORTAL)) {
                            double d2 = p.getSquaredDistance(a);
                            if (d2 < bestD2) { bestD2 = d2; best = p; }
                        }
                    } catch (Throwable t) {
                        // Игнорируем ошибки чтения блоков
                    }
                }
            }
        }
        return best;
    }

    private static BlockPos findSafeGround(ServerWorld w, BlockPos around) {
        // ИСПРАВЛЕНИЕ: Прогреваем чанк перед поиском
        try {
            int cx = around.getX() >> 4, cz = around.getZ() >> 4;
            w.getChunk(cx, cz, net.minecraft.world.chunk.ChunkStatus.FULL, true);
        } catch (Throwable t) {
            // Игнорируем ошибки загрузки чанков
        }
        
        for (int dy = 0; dy < 48; dy++) {
            BlockPos p = around.down(dy);
            try {
                if (w.getBlockState(p).isSolidBlock(w, p) && w.isAir(p.up()) && w.isAir(p.up(2))) return p.up();
            } catch (Throwable t) {
                // Игнорируем ошибки чтения блоков
            }
        }
        return around;
    }

    private static boolean hasSpaceForFrame(ServerWorld w, BlockPos c, Direction.Axis axis) {
        Direction right = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        
        // ИСПРАВЛЕНИЕ: Прогреваем чанк перед проверкой блоков
        try {
            int cx = c.getX() >> 4, cz = c.getZ() >> 4;
            w.getChunk(cx, cz, net.minecraft.world.chunk.ChunkStatus.FULL, true);
        } catch (Throwable t) {
            // Игнорируем ошибки загрузки чанков
        }
        
        // окно 4×5 с проёмом 2×3
        for (int i = 0; i < 4; i++) {
            for (int y = 1; y <= 4; y++) {
                BlockPos p = c.down().offset(right, i).up(y);
                try {
                    if (!w.getBlockState(p).isReplaceable()) return false;
                } catch (Throwable t) {
                    // Игнорируем ошибки чтения блоков
                    return false;
                }
            }
        }
        return true;
    }

    private static void buildFrameWithAxis(ServerWorld w, BlockPos center, Direction.Axis axis) {
        Direction right = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        BlockPos bottomLeft = center.down();

        // ИСПРАВЛЕНИЕ: Создаем нижние блоки обсидиана под порталом
        for (int i = 0; i < 4; i++) {
            // Нижний ряд (под порталом)
            w.setBlockState(bottomLeft.offset(right, i).down(), Blocks.OBSIDIAN.getDefaultState());
            // Основной ряд (рамка портала)
            w.setBlockState(bottomLeft.offset(right, i), Blocks.OBSIDIAN.getDefaultState());
        }
        
        // Боковые стойки
        for (int y = 1; y <= 4; y++) w.setBlockState(bottomLeft.up(y), Blocks.OBSIDIAN.getDefaultState());
        for (int y = 1; y <= 4; y++) w.setBlockState(bottomLeft.offset(right, 3).up(y), Blocks.OBSIDIAN.getDefaultState());
        
        // Верхний ряд
        for (int i = 0; i < 4; i++) w.setBlockState(bottomLeft.up(5).offset(right, i), Blocks.OBSIDIAN.getDefaultState());
    }

    public static void fillPortalWithAxis(ServerWorld w, BlockPos center, Direction.Axis portalAxis) {
        Direction right = portalAxis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        BlockState portal = Blocks.NETHER_PORTAL.getDefaultState()
            .with(NetherPortalBlock.AXIS, portalAxis);
        BlockPos bl = center.down().offset(right, 1);
        for (int col = 0; col < 2; col++) {
            BlockPos c = bl.offset(right, col);
            w.setBlockState(c, portal);
            w.setBlockState(c.up(), portal);
            w.setBlockState(c.up(2), portal);
        }
        // «ванильная» площадка 3×3 под порталом
        makeObsidianPad(w, bl, right);
    }

    private static void makeObsidianPad(ServerWorld w, BlockPos portalBottomLeft, Direction right) {
        // строим подушку под проём по осям right/forward (forward = поворот right на 90° в плоскости портала)
        Direction forward = right.rotateYClockwise();
        
        // подушка 3×3 под проёмом 2×3
        for (int dr = -1; dr <= 2; dr++) {
            for (int df = -1; df <= 1; df++) {
                BlockPos p = portalBottomLeft.offset(right, dr).offset(forward, df).down();
                if (!w.getBlockState(p).isSolidBlock(w, p)) {
                    w.setBlockState(p, Blocks.OBSIDIAN.getDefaultState());
                }
            }
        }
    }

    private PortalHelper() {}
}