package race.mixin;

import net.minecraft.network.packet.s2c.play.ChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkData.class)
public interface ChunkDataAccessor {
    @Accessor("sectionsData")
    byte[] race$getSectionsData();

    @Accessor("sectionsData")
    void race$setSectionsData(byte[] sectionsData);
}
