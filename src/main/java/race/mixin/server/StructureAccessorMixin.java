package race.mixin.server;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.StructureHolder;
import net.minecraft.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

@Mixin(StructureAccessor.class)
public class StructureAccessorMixin {
    
    /**
     * Защита от NullPointerException в getStructureStart
     * когда holder равен null в кастомных мирах
     */
    @Inject(method = "getStructureStart", at = @At("HEAD"), cancellable = true)
    private void race$protectStructureStart(ChunkSectionPos pos, Structure structure, StructureHolder holder, CallbackInfoReturnable<StructureStart> cir) {
        // Защита от null параметров
        if (structure == null || holder == null) {
            cir.setReturnValue(null);
            return;
        }
    }
    
    /**
     * Защита от NullPointerException в acceptStructureStarts
     */
    @Inject(method = "acceptStructureStarts", at = @At("HEAD"), cancellable = true)
    private void race$protectAcceptStructureStarts(Structure structure, LongSet structureStartPositions, Consumer<StructureStart> consumer, CallbackInfo ci) {
        // Защита от null параметров
        if (structure == null || structureStartPositions == null || consumer == null) {
            return; // Просто возвращаемся, не отменяем выполнение
        }
    }
}
