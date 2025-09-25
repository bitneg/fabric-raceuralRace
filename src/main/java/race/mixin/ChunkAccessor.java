package race.mixin;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Chunk.class)
public interface ChunkAccessor {
    @Accessor("sectionArray")
    ChunkSection[] race$getSectionArray();

    @Accessor("sectionArray")
    void race$setSectionArray(ChunkSection[] sections);
}
