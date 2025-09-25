package race.mixin;

import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkData;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkDataS2CPacket.class)
public interface ChunkDataS2CPacketAccessor {

    @Accessor("chunkX")
    int race$getChunkX();

    @Accessor("chunkZ")
    int race$getChunkZ();

    @Accessor("chunkData")
    ChunkData race$getChunkData();

    @Accessor("chunkData")
    void race$setChunkData(ChunkData chunkData);


}
