package race.server.phase;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import race.server.RaceServerInit;

public final class PhaseEvents {
    private PhaseEvents() {}

    public static void register() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> {
            if (!RaceServerInit.isActive()) return true;
            // Разрешаем ломать блоки в личных мирах гонки (namespace fabric_race)
            var key = world.getRegistryKey().getValue();
            if (key != null && "fabric_race".equals(key.getNamespace())) return true;
            // В остальных мирах (хаб/общие) — запрещаем в рамках гонки
            return false;
        });

        PlayerBlockBreakEvents.CANCELED.register((world, player, pos, state, be) -> {
            if (!RaceServerInit.isActive()) return;
            // Оверлей обновляем только вне личных миров гонки
            var key = world.getRegistryKey().getValue();
            if (key != null && "fabric_race".equals(key.getNamespace())) return;
            onBreakOverlay((ServerPlayerEntity) player, pos, state);
        });
    }

    private static void onBreakOverlay(ServerPlayerEntity player, BlockPos pos, BlockState prev) {
        PhaseService.setOverlayBlock(player.getServer(), player.getUuid(), pos, Blocks.AIR.getDefaultState());
        player.getServerWorld().updateListeners(pos, prev, Blocks.AIR.getDefaultState(), 3);
    }
}
