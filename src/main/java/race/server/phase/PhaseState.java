package race.server.phase;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PhaseState extends PersistentState {
    public static final String ID = "fabric_race_phase";
    
    // Глобальные данные гонки
    private static volatile boolean raceActive = false;
    private static volatile long raceStartTime = -1L;
    private static volatile long raceSeed = -1L;

    public static final class Overlay {
        // pos.asLong() -> Block NBT
        public final Long2ObjectOpenHashMap<NbtCompound> blocks = new Long2ObjectOpenHashMap<>();
    }

    public static final class PhaseData {
        // ChunkPos.toLong(x,z) -> Overlay
        public final Long2ObjectOpenHashMap<Overlay> chunkOverlays = new Long2ObjectOpenHashMap<>();
        // BlockPos.asLong() -> Nbt of virtual container
        public final Long2ObjectOpenHashMap<NbtCompound> containers = new Long2ObjectOpenHashMap<>();
        public long lootSeed = 0L;
    }

    private final Map<UUID, PhaseData> players = new HashMap<>();
    
    // Геттеры и сеттеры для глобальных данных гонки
    public static boolean isRaceActive() { return raceActive; }
    public static void setRaceActive(boolean active) { raceActive = active; }
    
    public static long getRaceStartTime() { return raceStartTime; }
    public static void setRaceStartTime(long time) { raceStartTime = time; }
    
    public static long getRaceSeed() { return raceSeed; }
    public static void setRaceSeed(long seed) { raceSeed = seed; }

    public static PhaseState get(MinecraftServer server) {
        PersistentStateManager psm = server.getOverworld().getPersistentStateManager();
        return psm.getOrCreate(new Type<>(
                PhaseState::new,
                (nbt, lookup) -> PhaseState.readNbt(nbt),
                null
        ), ID);
    }

    public PhaseData of(UUID id) {
        return players.computeIfAbsent(id, k -> new PhaseData());
    }

    public Overlay overlayOf(PhaseData pd, BlockPos pos) {
        long key = ChunkPos.toLong(pos);
        return pd.chunkOverlays.computeIfAbsent(key, k -> new Overlay());
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        NbtCompound playersTag = new NbtCompound();
        for (var e : players.entrySet()) {
            UUID uuid = e.getKey();
            PhaseData pd = e.getValue();
            NbtCompound pdTag = new NbtCompound();
            pdTag.putLong("lootSeed", pd.lootSeed);

            // containers
            NbtCompound containers = new NbtCompound();
            pd.containers.long2ObjectEntrySet().forEach(en -> containers.put(Long.toString(en.getLongKey()), en.getValue()));
            pdTag.put("containers", containers);

            // overlays
            NbtCompound overlays = new NbtCompound();
            pd.chunkOverlays.long2ObjectEntrySet().forEach(en -> {
                NbtCompound ovTag = new NbtCompound();
                en.getValue().blocks.long2ObjectEntrySet().forEach(be -> ovTag.put(Long.toString(be.getLongKey()), be.getValue()));
                overlays.put(Long.toString(en.getLongKey()), ovTag);
            });
            pdTag.put("overlays", overlays);

            playersTag.put(uuid.toString(), pdTag);
        }
        nbt.put("players", playersTag);
        return nbt;
    }

    private static PhaseState readNbt(NbtCompound nbt) {
        PhaseState s = new PhaseState();
        NbtCompound playersTag = nbt.getCompound("players");
        for (String uuidStr : playersTag.getKeys()) {
            UUID uuid = UUID.fromString(uuidStr);
            NbtCompound pdTag = playersTag.getCompound(uuidStr);
            PhaseData pd = new PhaseData();
            pd.lootSeed = pdTag.getLong("lootSeed");

            NbtCompound containers = pdTag.getCompound("containers");
            for (String k : containers.getKeys()) {
                pd.containers.put(Long.parseLong(k), containers.getCompound(k));
            }

            NbtCompound overlays = pdTag.getCompound("overlays");
            for (String ck : overlays.getKeys()) {
                long chunkKey = Long.parseLong(ck);
                Overlay ov = new Overlay();
                NbtCompound ovTag = overlays.getCompound(ck);
                for (String pk : ovTag.getKeys()) {
                    ov.blocks.put(Long.parseLong(pk), ovTag.getCompound(pk));
                }
                pd.chunkOverlays.put(chunkKey, ov);
            }
            s.players.put(uuid, pd);
        }
        return s;
    }
}
