package race.server.phase;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

public final class VirtualChestHandler {
    private VirtualChestHandler() {}

    public static void open(ServerPlayerEntity player, BlockPos pos, NbtCompound data) {
        SimpleInventory inv = new SimpleInventory(27);
        RegistryWrapper.WrapperLookup lookup = player.getServerWorld().getRegistryManager();

        if (data != null) {
            NbtList list = data.getList("Items", 10);
            inv.readNbtList(list, lookup);
        }

        player.openHandledScreen(new NamedScreenHandlerFactory() {
            @Override
            public net.minecraft.text.Text getDisplayName() {
                return net.minecraft.text.Text.translatable("container.chest");
            }

            @Override
            public ScreenHandler createMenu(int syncId, PlayerInventory plInv, PlayerEntity pl) {
                return new GenericContainerScreenHandler(ScreenHandlerType.GENERIC_9X3, syncId, plInv, inv, 3) {
                    @Override
                    public void onClosed(PlayerEntity closing) {
                        super.onClosed(closing);
                        if (!(closing instanceof ServerPlayerEntity sp)) return;
                        NbtList outList = inv.toNbtList(lookup);
                        NbtCompound out = new NbtCompound();
                        out.put("Items", outList);
                        var st = PhaseState.get(sp.getServer());
                        var pd = st.of(sp.getUuid());
                        pd.containers.put(pos.asLong(), out);
                        st.markDirty();
                    }
                };
            }
        });
    }
}
