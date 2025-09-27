package race.mixin.server;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.SpawnHelper;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.biome.SpawnSettings;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpawnHelper.class)
public class SpawnHelperProtectionMixin {
    
    /**
     * Защита от NPE в getEntitySpawnList при работе с StructureAccessor
     * ИСПРАВЛЕНИЕ: Убираем перехват - метод не вызывает NPE напрямую
     */
    // УДАЛЕНО: getEntitySpawnList не вызывает NPE напрямую
    
    /**
     * Защита от NPE в pickRandomSpawnEntry
     * ИСПРАВЛЕНИЕ: Убираем перехват - метод не вызывает NPE напрямую
     */
    // УДАЛЕНО: pickRandomSpawnEntry не вызывает NPE напрямую
}
