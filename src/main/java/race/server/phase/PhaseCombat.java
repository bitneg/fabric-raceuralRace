package race.server.phase;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import race.server.RaceServerInit;

public final class PhaseCombat {
    private PhaseCombat() {}

    public static void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!RaceServerInit.isActive()) return true;
            var atk = source.getAttacker();
            if (atk instanceof ServerPlayerEntity a && entity instanceof ServerPlayerEntity b) {
                return a.getUuid().equals(b.getUuid()); // true только для self-damage
            }
            return true;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!RaceServerInit.isActive()) return ActionResult.PASS;
            if (entity instanceof ServerPlayerEntity other && !player.getUuid().equals(other.getUuid())) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
    }
}
