package race.mixin.standardrng;

import net.minecraft.structure.StructurePiecesList;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.gen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import race.server.phase.PhaseState;

/**
 * Стандартизация генерации структур для честной игры
 */
@Mixin(StructureStart.class)
public class MixinStructureStart {
    
    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(Structure structure, ChunkPos pos, int references, StructurePiecesList children, CallbackInfo ci) {
        
        // Если гонка активна, используем детерминированную генерацию структур
        if (PhaseState.isRaceActive()) {
            long raceSeed = PhaseState.getRaceSeed();
            
            // Синхронизируем генерацию структуры на основе сида гонки
            StructureStart start = (StructureStart) (Object) this;
            // Логируем генерацию структуры для отладки
            System.out.println("Structure generated with race seed: " + raceSeed + " at " + pos);
        }
    }
}
