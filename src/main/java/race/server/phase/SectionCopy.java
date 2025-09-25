package race.server.phase;

import net.minecraft.block.BlockState;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.ReadableContainer;

public final class SectionCopy {
    private SectionCopy() {}

    public static ChunkSection copySectionCompat(ChunkSection src) {
        PalettedContainer<BlockState> blocksCopy = src.getBlockStateContainer().copy(); // есть в 1.21 [3]
        // Вместо copy(): берём срез, он независим при дальнейшей сериализации пакета
        ReadableContainer<RegistryEntry<Biome>> biomesSlice = src.getBiomeContainer().slice(); // доступный API [1]
        return new ChunkSection(blocksCopy, biomesSlice); // валидный конструктор [2]
    }
}
