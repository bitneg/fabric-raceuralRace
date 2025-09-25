package race.mixin;

import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import net.minecraft.entity.boss.dragon.EnderDragonFight;

/**
 * Accessor для доступа к приватному полю world в EnderDragonFight
 */
@Mixin(EnderDragonFight.class)
public interface EnderDragonFightAccessor {
    @Accessor("world")
    ServerWorld getWorld();
}
