package race.mixin.server;

import net.minecraft.entity.SpawnGroup;
import net.minecraft.server.network.ServerPlayerEntity;
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
        
        String worldName = world.getRegistryKey().getValue().toString();
        if (!worldName.startsWith("fabric_race:")) {
            return; // Обычная логика для не-гоночных миров
        }
        
        // В гоночных мирах используем виртуальное время для враждебных мобов
        if (group == SpawnGroup.MONSTER) {
            // Используем виртуальное время из SlotTimeService
            long slotTime = race.server.SlotTimeService.getTime(world.getRegistryKey());
            long timeOfDay = slotTime % 24000L;
            boolean isNight = timeOfDay >= 13000L && timeOfDay <= 23000L;
            
            if (!isNight) {
                // День в мире - не спавним враждебных мобов
                cir.setReturnValue(false);
                return;
            }
            
            // DEBUG: Логируем успешный спавн
            if (Math.random() < 0.01) { // 1% логов
                System.out.println("[Race] Allowing monster spawn in world " + worldName + 
                                  " (night time: " + timeOfDay + ")");
            }
        }
        // Для других типов мобов (животные, водные) - обычная логика (не отменяем)
    }
}
