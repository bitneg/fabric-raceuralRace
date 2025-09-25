package race.mixin.server;

import com.mojang.logging.LogUtils;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldGenerationProgressListener;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.level.ServerWorldProperties;
import net.minecraft.world.level.storage.LevelStorage;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.random.RandomSequencesState;
import net.minecraft.world.gen.GeneratorOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import race.server.world.WorldSeedRegistry;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.Executor;

@Mixin(ServerWorld.class)
public abstract class MixinServerWorld {
    private static final Logger LOGGER = LogUtils.getLogger();

    // Подменяем seed, который ServerWorld берёт из глобального GeneratorOptions,
    // на наш персональный сид для мира, если он зарегистрирован в WorldSeedRegistry.
    @org.spongepowered.asm.mixin.injection.Redirect(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/gen/GeneratorOptions;getSeed()J")
    )
    private long redirectGeneratorOptionsSeed(GeneratorOptions options,
                                              MinecraftServer server, Executor workerExecutor, LevelStorage.Session session,
                                              ServerWorldProperties properties, RegistryKey<World> worldKey, DimensionOptions dimensionOptions,
                                              WorldGenerationProgressListener worldGenerationProgressListener, boolean debugWorld, long seed,
                                              java.util.List<?> spawners, boolean shouldTickTime, RandomSequencesState randomSequencesState) {
        Long forced = WorldSeedRegistry.get(worldKey);
        long optSeed = options.getSeed();
        if (forced != null) {
            LOGGER.info("[Race] MixinServerWorld: applying forced seed {} for world {} (optionsSeed={})", forced, worldKey.getValue(), optSeed);
            return forced;
        }
        return optSeed;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void afterInit(MinecraftServer server, Executor workerExecutor, LevelStorage.Session session, ServerWorldProperties properties, RegistryKey<World> worldKey, DimensionOptions dimensionOptions, WorldGenerationProgressListener worldGenerationProgressListener, boolean debugWorld, long seed, List<?> spawners, boolean shouldTickTime, RandomSequencesState randomSequencesState, CallbackInfo ci) {
        // Не удаляем запись — используем её в getSeed(), если понадобится
    }

    // Гарантируем, что getSeed() для наших динамических миров возвращает сид из ключа вида *_s<seed>
    @Inject(method = "getSeed", at = @At("HEAD"), cancellable = true)
    private void onGetSeed(CallbackInfoReturnable<Long> cir) {
        ServerWorld self = (ServerWorld)(Object)this;
        String path = self.getRegistryKey().getValue().getPath();
        int idx = path.lastIndexOf("_s");
        if (idx >= 0) {
            try {
                long parsed = Long.parseUnsignedLong(path.substring(idx + 2));
                cir.setReturnValue(parsed);
                return;
            } catch (Throwable ignored) {}
        }
        Long forced = WorldSeedRegistry.get(self.getRegistryKey());
        if (forced != null) {
            cir.setReturnValue(forced);
        }
    }
}


