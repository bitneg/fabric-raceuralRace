package race.server.phase;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

public final class PhaseService {
    private PhaseService() {}

    public static void setOverlayBlock(MinecraftServer server, UUID player, BlockPos pos, BlockState state) {
        PhaseState st = PhaseState.get(server);
        PhaseState.PhaseData pd = st.of(player);
        PhaseState.Overlay ov = st.overlayOf(pd, pos);
        ov.blocks.put(pos.asLong(), stateToNbt(state));
        st.markDirty();
    }

    public static BlockState getOverlayBlockOrNull(MinecraftServer server, UUID player, BlockPos pos) {
        PhaseState st = PhaseState.get(server);
        PhaseState.PhaseData pd = st.of(player);
        PhaseState.Overlay ov = st.overlayOf(pd, pos);
        NbtCompound tag = ov.blocks.get(pos.asLong());
        return tag == null ? null : nbtToState(tag);
    }

    public static void removeOverlayBlock(MinecraftServer server, UUID player, BlockPos pos) {
        PhaseState st = PhaseState.get(server);
        PhaseState.PhaseData pd = st.of(player);
        PhaseState.Overlay ov = st.overlayOf(pd, pos);
        ov.blocks.remove(pos.asLong());
        st.markDirty();
    }

    private static NbtCompound stateToNbt(BlockState state) {
        NbtCompound nbt = new NbtCompound();
        nbt.putInt("raw", Block.getRawIdFromState(state));
        return nbt;
    }

    private static BlockState nbtToState(NbtCompound nbt) {
        return Block.getStateFromRawId(nbt.getInt("raw"));
    }

    // Публичный helper для миксинов
    public static BlockState nbtToStatePublic(NbtCompound nbt) {
        return nbtToState(nbt);
    }
}
