package race.mixin;

import com.mojang.logging.LogUtils;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionTypes;
import net.minecraft.util.math.Direction;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NetherPortalBlock.class)
public class PortalMixin {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Inject(method = "createTeleportTarget", at = @At("HEAD"), cancellable = true)
    private void race$teleport(ServerWorld srcWorld, Entity entity, BlockPos srcPortalPos, CallbackInfoReturnable<TeleportTarget> cir) {
        if (!(entity instanceof ServerPlayerEntity player)) return;

        long seed = race.hub.HubManager.getPlayerSeedChoice(player.getUuid());
        // ИСПРАВЛЕНИЕ: Разрешаем отрицательные сиды, но блокируем только -1 (не выбран)
        if (seed == -1) return;

        boolean fromOW = srcWorld.getRegistryKey() == World.OVERWORLD
                || (srcWorld.getRegistryKey().getValue().getNamespace().equals("fabric_race")
                && srcWorld.getRegistryKey().getValue().getPath().contains("overworld"));

        boolean fromN = srcWorld.getDimensionEntry().matchesKey(DimensionTypes.THE_NETHER)
                && srcWorld.getRegistryKey().getValue().getNamespace().equals("fabric_race");

        if (!fromOW && !fromN) return;

        // целевой персональный мир по слоту/сиду
        ServerWorld dstWorld;
        if (fromOW) {
            int slot = race.server.world.EnhancedWorldManager.getOrAssignSlotForPlayer(player.getUuid());
            dstWorld = race.server.world.EnhancedWorldManager.getOrCreateWorldForGroup(player.getServer(), slot, seed, World.NETHER);
        } else {
            int slot = race.server.world.EnhancedWorldManager.slotFromWorld(srcWorld);
            dstWorld = race.server.world.EnhancedWorldManager.getOrCreateWorldForGroup(player.getServer(), slot, seed, World.OVERWORLD);
        }
        if (dstWorld == null) return;

        // стабилизированный якорь и ось портала
        Direction.Axis portalAxis = readPortalAxisFromSource(srcWorld, srcPortalPos);
        if (portalAxis == null) portalAxis = race.server.world.PortalHelper.pickAxis(dstWorld, srcPortalPos);
        BlockPos anchor = race.server.world.PortalHelper.stabilizedAnchor(fromOW, srcPortalPos);

        // поиск/создание рамки с правильной осью и полотном
        BlockPos frameCenter = race.server.world.PortalHelper.findOrCreateLinkedPortal(dstWorld, anchor, portalAxis);

        // безопасная высота (анти-крыша)
        frameCenter = race.server.world.PortalHelper.adjustSafeY(dstWorld, frameCenter);

        // TeleportTarget с валидным postTransition (исправляет NPE)
        Vec3d pos = Vec3d.ofCenter(frameCenter, 0.0);
        TeleportTarget.PostDimensionTransition post = e -> { /* no-op; vanilla cooldown сохранится */ };
        TeleportTarget target = new TeleportTarget(dstWorld, pos, entity.getVelocity(), entity.getYaw(), entity.getPitch(), post);

        cir.setReturnValue(target);
    }

    private static Direction.Axis readPortalAxisFromSource(ServerWorld w, BlockPos pos) {
        var bs = w.getBlockState(pos);
        if (bs.isOf(net.minecraft.block.Blocks.NETHER_PORTAL)) {
            // читаем AXIS исходного портала для сохранения ориентации
            return bs.getOrEmpty(NetherPortalBlock.AXIS).orElse(null);
        }
        return null;
    }
}