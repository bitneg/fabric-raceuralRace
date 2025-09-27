package race.mixin.server;

import net.minecraft.entity.SpawnGroup;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.SpawnHelper;
import net.minecraft.world.biome.SpawnSettings;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SpawnHelper.class)
public class SpawnHelperMixin {
    
    /**
     * Контролируем спавн мобов на основе времени мира-слота
     * Перехватываем метод canSpawn с правильной сигнатурой
     */
    @Inject(method = "canSpawn(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/entity/SpawnGroup;Lnet/minecraft/world/gen/StructureAccessor;Lnet/minecraft/world/gen/chunk/ChunkGenerator;Lnet/minecraft/world/biome/SpawnSettings$SpawnEntry;Lnet/minecraft/util/math/BlockPos$Mutable;D)Z", 
            at = @At("HEAD"), cancellable = true)
    private static void raceWorldTimeSpawn(
        ServerWorld world, SpawnGroup group, StructureAccessor structureAccessor,
        ChunkGenerator chunkGenerator, SpawnSettings.SpawnEntry spawnEntry, 
        BlockPos.Mutable pos, double squaredDistance,
        CallbackInfoReturnable<Boolean> cir) {
        
        // Работать только в наших гоночных мирах
        String name = world.getRegistryKey().getValue().toString();
        if (!name.startsWith("fabric_race:")) {
            return; // Обычная логика для не-гоночных миров
        }
        
        // 1) В аду и энде — чистая ванила (ничего не отменяем)
        var dim = world.getDimensionEntry();
        if (dim.matchesKey(net.minecraft.world.dimension.DimensionTypes.THE_NETHER)
         || dim.matchesKey(net.minecraft.world.dimension.DimensionTypes.THE_END)) {
            return; // ванильная логика целиком
        }
        
        // 2) Только оверворлд-слоты: ночь по виртуальному времени
        if (group == SpawnGroup.MONSTER) {
            long slotTime = race.server.SlotTimeService.getTime(world.getRegistryKey());
            long tod = slotTime % 24000L;
            boolean isNight = tod >= 13000L && tod <= 23000L;
            if (!isNight) {
                cir.setReturnValue(false); // день — блок
                return;
            }
        }
    }
}
