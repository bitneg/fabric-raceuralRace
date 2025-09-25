package race.mixin;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.dimension.DimensionTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

@Mixin(ServerPlayerEntity.class)
public class NetherSpawnMixin {
    private static final Logger LOGGER = LogUtils.getLogger();
    private boolean race$netherGuard = false;

    @Inject(method = "teleport(Lnet/minecraft/server/world/ServerWorld;DDDFF)V", at = @At("HEAD"), cancellable = true)
    private void race$avoidRoof(ServerWorld targetWorld, double x, double y, double z, float yaw, float pitch, CallbackInfo ci) {
        if (race$netherGuard) return; // защита от реентрантного вызова
        if (!targetWorld.getDimensionEntry().matchesKey(DimensionTypes.THE_NETHER)) return; // не ад
        if (!targetWorld.getRegistryKey().getValue().getNamespace().equals("fabric_race")) return; // не личный мир
        if (y < 126.5) return; // уже нормально

        // мягкая коррекция: ищем ближайшую безопасную высоту под крышей
        net.minecraft.util.math.Direction.Axis axis = race.server.world.PortalHelper.pickAxis(targetWorld, new BlockPos((int)x, (int)y, (int)z));
        BlockPos safe = race.server.world.PortalHelper.findOrCreateLinkedPortal(targetWorld, new BlockPos((int)x, 64, (int)z), axis);
        ci.cancel();
        race$netherGuard = true;
        try {
            ((ServerPlayerEntity)(Object)this).teleport(targetWorld, safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5, yaw, pitch);
        } finally {
            race$netherGuard = false;
        }
    }
}
