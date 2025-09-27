package race.mixin.server;

import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import net.minecraft.server.WorldGenerationProgressListener;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.level.storage.LevelStorage;
import net.minecraft.world.gen.noise.NoiseConfig;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;
import java.util.concurrent.Executor;

/**
 * Логирование и валидация сидов при инициализации менеджера чанков.
 */
@Mixin(ServerChunkLoadingManager.class)
public abstract class MixinServerChunkLoadingManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Shadow @Final private ServerWorld world;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(ServerWorld world,
                        LevelStorage.Session session,
                        DataFixer dataFixer,
                        StructureTemplateManager structureTemplateManager,
                        Executor executor,
                        net.minecraft.util.thread.ThreadExecutor<Runnable> mainThreadExecutor,
                        net.minecraft.world.chunk.ChunkProvider chunkProvider,
                        ChunkGenerator chunkGenerator,
                        WorldGenerationProgressListener worldGenerationProgressListener,
                        net.minecraft.world.chunk.ChunkStatusChangeListener chunkStatusChangeListener,
                        Supplier<PersistentStateManager> persistentStateManagerFactory,
                        int viewDistance,
                        boolean dsync,
                        CallbackInfo ci) {
        // ИСПРАВЛЕНИЕ: Не обращаемся к getChunkManager() в конструкторе
        // Это предотвращает NPE когда ChunkManager еще не готов
        try {
            long worldSeed = this.world.getSeed();
            LOGGER.info("[Race] ChunkLoadingManager init: world={}, seed={}",
                    this.world.getRegistryKey().getValue(),
                    worldSeed);
        } catch (Throwable t) {
            LOGGER.warn("[Race] ChunkLoadingManager log failed: {}", t.toString());
        }
    }
}


