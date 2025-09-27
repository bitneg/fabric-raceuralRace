package race.server.world;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.MutableRegistry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.dimension.DimensionTypes;
import net.minecraft.util.math.intprovider.ConstantIntProvider;
import java.util.OptionalLong;

public class DimensionTypeRegistry {
    public static final Identifier CUSTOM_OVERWORLD_ID = Identifier.of("fabric_race", "custom_overworld");
    public static final RegistryKey<DimensionType> CUSTOM_OVERWORLD_KEY = RegistryKey.of(RegistryKeys.DIMENSION_TYPE, CUSTOM_OVERWORLD_ID);
    
    private static boolean registered = false;
    private static RegistryEntry<DimensionType> customDimensionTypeEntry = null;
    
    public static void register() {
        // ИСПРАВЛЕНИЕ: Не регистрируем dimension_type, используем существующий vanilla overworld
        // Это предотвращает ошибку "Registry is already frozen"
        System.out.println("Dimension type registry hooks installed (using vanilla overworld)");
    }
    
    private static void onServerStarting(MinecraftServer server) {
        try {
            var registryManager = server.getRegistryManager();
            Registry<DimensionType> dimensionTypeRegistry = registryManager.get(RegistryKeys.DIMENSION_TYPE);
            
            System.out.println("=== DIMENSION TYPE REGISTRATION ===");
            System.out.println("Registry: " + dimensionTypeRegistry);
            System.out.println("Registry is mutable: " + (dimensionTypeRegistry instanceof MutableRegistry));
            
            // ИСПРАВЛЕНИЕ: Проверяем, не заморожен ли реестр
            if (!(dimensionTypeRegistry instanceof MutableRegistry)) {
                System.out.println("⚠ Registry is frozen, skipping custom dimension type registration");
                return;
            }
            
            if (dimensionTypeRegistry.containsId(CUSTOM_OVERWORLD_ID)) {
                System.out.println("✓ Custom dimension type already exists: " + CUSTOM_OVERWORLD_ID);
                customDimensionTypeEntry = dimensionTypeRegistry.getEntry(CUSTOM_OVERWORLD_KEY).orElse(null);
                registered = true;
                return;
            }
            
            // Создаем dimension type ИДЕНТИЧНЫЙ vanilla overworld
            DimensionType customType = createVanillaOverworldCopy(registryManager);
            
            if (dimensionTypeRegistry instanceof MutableRegistry<DimensionType> mutableRegistry) {
                try {
                    // ИСПРАВЛЕНИЕ: Добавляем и сохраняем entry для использования
                    customDimensionTypeEntry = mutableRegistry.add(
                        CUSTOM_OVERWORLD_KEY, 
                        customType, 
                        RegistryEntryInfo.DEFAULT
                    );
                    
                    registered = true;
                    System.out.println("✓ Custom dimension type registered: " + CUSTOM_OVERWORLD_ID);
                    System.out.println("✓ Custom dimension type entry: " + customDimensionTypeEntry);
                    System.out.println("✓ Entry key: " + customDimensionTypeEntry.getKey());
                    
                    // Проверяем регистрацию
                    if (dimensionTypeRegistry.containsId(CUSTOM_OVERWORLD_ID)) {
                        System.out.println("✓ Verification successful");
                    } else {
                        System.out.println("✗ Verification failed");
                    }
                } catch (IllegalStateException e) {
                    if (e.getMessage().contains("Registry is already frozen")) {
                        System.err.println("Registry is already frozen, using vanilla overworld as fallback");
                        // Fallback: используем vanilla overworld dimension type
                        customDimensionTypeEntry = dimensionTypeRegistry.getEntry(DimensionTypes.OVERWORLD).orElse(null);
                        registered = true;
                        System.out.println("✓ Using vanilla overworld dimension type as fallback");
                    } else {
                        throw e;
                    }
                }
                
            } else {
                System.out.println("✗ Registry is not mutable: " + dimensionTypeRegistry.getClass());
            }
            
        } catch (Exception e) {
            System.err.println("Failed to register dimension type: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    
    // ИСПРАВЛЕНИЕ: Создаем dimension type идентичный vanilla overworld
    private static DimensionType createVanillaOverworldCopy(net.minecraft.registry.DynamicRegistryManager registryManager) {
        // Получаем оригинальный overworld dimension type
        Registry<DimensionType> registry = registryManager.get(RegistryKeys.DIMENSION_TYPE);
        DimensionType overworldType = registry.get(DimensionTypes.OVERWORLD);
        
        if (overworldType == null) {
            // Создаем fallback если не найден оригинал
            return new DimensionType(
                OptionalLong.empty(), // fixedTime
                true,  // hasSkyLight
                false, // hasCeiling
                false, // ultrawarm
                true,  // natural
                1.0,   // coordinateScale
                true,  // bedWorks
                false, // respawnAnchorWorks
                -64,   // minY
                384,   // height
                384,   // logicalHeight
                net.minecraft.registry.tag.BlockTags.INFINIBURN_OVERWORLD, // infiniburn
                net.minecraft.util.Identifier.of("minecraft", "overworld"), // effects
                0.0f,  // ambientLight
                new DimensionType.MonsterSettings(
                    false, // piglinSafe
                    true,  // hasRaids
                    ConstantIntProvider.create(0), // monsterSpawnLightTest
                    0      // monsterSpawnBlockLightLimit
                )
            );
        }
        
        // КЛЮЧЕВОЕ ИСПРАВЛЕНИЕ: Возвращаем точную копию vanilla overworld
        return overworldType;
    }
    
    // Метод для получения dimension type entry (для использования в ServerWorld)
    public static RegistryEntry<DimensionType> getCustomDimensionTypeEntry() {
        return customDimensionTypeEntry;
    }
    
    // ИСПРАВЛЕНИЕ: Всегда используем vanilla overworld dimension type
    public static RegistryEntry<DimensionType> getSafeDimensionTypeEntry(MinecraftServer server) {
        // Всегда используем vanilla overworld - это предотвращает проблемы с замороженным реестром
        var registry = server.getRegistryManager().get(RegistryKeys.DIMENSION_TYPE);
        return registry.entryOf(DimensionTypes.OVERWORLD);
    }
    
    public static boolean isRegistered() {
        return registered && customDimensionTypeEntry != null;
    }
}