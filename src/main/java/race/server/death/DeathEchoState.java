package race.server.death;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Персистентное хранилище "эхо смерти" по (seed, dimensionType).
 */
public final class DeathEchoState {
    public static final class Echo {
        public final long seed; public final String dim; public final net.minecraft.util.math.BlockPos pos;
        public final String title; public final String playerName;
        Echo(long seed, String dim, net.minecraft.util.math.BlockPos pos, String title, String playerName) {
            this.seed = seed; this.dim = dim; this.pos = pos; this.title = title; this.playerName = playerName; }
    }

    private final Map<Long, Map<String, List<Echo>>> map = new HashMap<>();
    private final MinecraftServer server;
    private boolean dirty = false;

    private DeathEchoState(MinecraftServer server) { this.server = server; load(); }

    private static final java.util.WeakHashMap<MinecraftServer, DeathEchoState> INSTANCES = new java.util.WeakHashMap<>();

    public static synchronized DeathEchoState get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, s -> new DeathEchoState(s));
    }

    public void addEcho(long seed, String dim, net.minecraft.util.math.BlockPos pos, String title, String playerName) {
        map.computeIfAbsent(seed, k -> new HashMap<>())
                .computeIfAbsent(dim, k -> new ArrayList<>())
                .add(new Echo(seed, dim, pos, title, playerName));
        dirty = true;
    }

    public List<Echo> getEchoes(long seed, String dim) {
        return map.getOrDefault(seed, Collections.emptyMap()).getOrDefault(dim, Collections.emptyList());
    }

    public void markDirty() { dirty = true; save(); }

    private void save() {
        if (!dirty) return;
        try {
            Path dir = server.getSavePath(WorldSavePath.ROOT).resolve("race");
            Files.createDirectories(dir);
            Path file = dir.resolve("death_echoes.dat");
            NbtCompound root = new NbtCompound();
            NbtList seeds = new NbtList();
            for (var eSeed : map.entrySet()) {
                NbtCompound s = new NbtCompound();
                s.putLong("seed", eSeed.getKey());
                NbtList dims = new NbtList();
                for (var eDim : eSeed.getValue().entrySet()) {
                    NbtCompound d = new NbtCompound();
                    d.putString("dim", eDim.getKey());
                    NbtList list = new NbtList();
                    for (Echo e : eDim.getValue()) {
                        NbtCompound n = new NbtCompound();
                        n.putInt("x", e.pos.getX()); n.putInt("y", e.pos.getY()); n.putInt("z", e.pos.getZ());
                        n.putString("title", e.title); n.putString("player", e.playerName);
                        list.add(n);
                    }
                    d.put("list", list);
                    dims.add(d);
                }
                s.put("dims", dims);
                seeds.add(s);
            }
            root.put("seeds", seeds);
            try (var out = Files.newOutputStream(file)) {
                net.minecraft.nbt.NbtIo.writeCompressed(root, out);
            }
            dirty = false;
        } catch (IOException ignored) {}
    }

    private void load() {
        try {
            Path file = server.getSavePath(WorldSavePath.ROOT).resolve("race").resolve("death_echoes.dat");
            if (!Files.exists(file)) return;
            NbtCompound root;
            try (var in = Files.newInputStream(file)) { root = net.minecraft.nbt.NbtIo.readCompressed(in, NbtSizeTracker.ofUnlimitedBytes()); }
            if (root == null) return;
            NbtList seeds = root.getList("seeds", 10);
            for (int i = 0; i < seeds.size(); i++) {
                NbtCompound s = seeds.getCompound(i);
                long seed = s.getLong("seed");
                NbtList dims = s.getList("dims", 10);
                for (int j = 0; j < dims.size(); j++) {
                    NbtCompound d = dims.getCompound(j);
                    String dim = d.getString("dim");
                    NbtList list = d.getList("list", 10);
                    List<Echo> es = new ArrayList<>();
                    for (int k = 0; k < list.size(); k++) {
                        NbtCompound n = list.getCompound(k);
                        es.add(new Echo(seed, dim, new net.minecraft.util.math.BlockPos(n.getInt("x"), n.getInt("y"), n.getInt("z")), n.getString("title"), n.getString("player")));
                    }
                    map.computeIfAbsent(seed, kk -> new HashMap<>()).put(dim, es);
                }
            }
        } catch (IOException ignored) {}
    }
}


