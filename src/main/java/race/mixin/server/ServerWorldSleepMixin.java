package race.mixin.server;

import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerWorld.class)
public class ServerWorldSleepMixin {

    /**
     * ПОЛНОСТЬЮ блокируем ванильную систему сна в гоночных мирах
     */
    @Inject(method = "updateSleepingPlayers", at = @At("HEAD"), cancellable = true)
    private void raceBlockVanillaSleep(CallbackInfo ci) {
        ServerWorld world = (ServerWorld)(Object)this;
        String worldName = world.getRegistryKey().getValue().toString();
        
        if (worldName.startsWith("fabric_race:")) {
            // ПОЛНОСТЬЮ блокируем ванильную логику сна
            System.out.println("[Race] Blocking vanilla sleep system in " + worldName);
            ci.cancel();
        }
    }
}
