package org.gtlcore.aedump;

import appeng.api.config.*;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.*;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.parts.IPart;
import appeng.api.stacks.*;
import appeng.me.service.CraftingService;
import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphRequestTracker;
import java.util.*;

/** Entire connected grid, before either solver starts. All world callbacks run on the server thread. */
public final class NetworkSnapshot {
    public final Data.Keys keys;
    public final JsonObject network = new JsonObject();
    public final JsonArray patterns = new JsonArray(), nodes = new JsonArray(), errors = new JsonArray();
    public boolean complete = true;
    public long estimatedBytes;
    private final long cap = DumpConfig.captureMiB.get() * (1L << 20);
    private final IdentityHashMap<IPatternDetails, String> patternIds = new IdentityHashMap<>();
    private final List<IPatternDetails> pending = new ArrayList<>();
    private final KeyCounter universe = new KeyCounter();
    private boolean exhausted;

    private NetworkSnapshot(Data.Keys keys) { this.keys = keys; }
    private static final class Limit extends RuntimeException { Limit() { super("DIAGNOSTIC_CAPTURE_MEMORY_LIMIT"); } }
    private void charge(long n) { estimatedBytes += n; if (estimatedBytes + keys.estimatedBytes > cap) { exhausted = true; throw new Limit(); } }
    private void addKey(AEKey key) { if (key != null && universe.get(key) == 0) { charge(256); universe.set(key, 1); keys.id(key); } }
    private String pattern(IPatternDetails p) {
        String id = patternIds.get(p);
        if (id == null) { charge(256); id = "p" + pending.size(); patternIds.put(p, id); pending.add(p); }
        return id;
    }
    private void section(String name, Runnable action) {
        if (exhausted) { complete = false; return; }
        try { action.run(); }
        catch (RuntimeException | LinkageError e) { complete = false; errors.add(Data.object("section", name, "error", Trace.stack(e))); }
    }
    public static NetworkSnapshot capture(IGrid grid, Level level, IActionSource source, Data.Keys keys) {
        if (level.getServer() == null || !level.getServer().isSameThread()) throw new IllegalStateException("Dump requires server thread");
        var s = new NetworkSnapshot(keys); s.collect(grid, level, source); return s;
    }
    private void collect(IGrid grid, Level level, IActionSource source) {
        long start = System.nanoTime();
        var service = (CraftingService) grid.getCraftingService();
        var providers = Reflect.get(service, "craftingProviders");
        network.addProperty("schema", "ae-full-network-v1");
        network.addProperty("dimension", level.dimension().location().toString());
        network.addProperty("server_tick", level.getServer().getTickCount());
        network.addProperty("game_time", Long.toString(level.getGameTime()));
        network.addProperty("grid_size", grid.size());
        network.addProperty("provider_generation", Long.toString(((GraphRequestTracker) service).gtlcore$graphProviderGeneration()));
        network.add("provider_state", Data.JSON.toJsonTree(Reflect.scalars(providers, "lastModifiedOnTick")));
        network.addProperty("scope", "ALL registered patterns, all providers/nodes, all advertised inventory; not target reachability");
        var actual = new KeyCounter();
        section("storage_cached", () -> {
            var cached = new KeyCounter();
            for (var e : grid.getStorageService().getCachedInventory()) { charge(128); cached.set(e.getKey(), e.getLongValue()); addKey(e.getKey()); }
            network.add("cached_inventory", keys.amounts(cached));
        });
        section("storage_advertised", () -> {
            grid.getStorageService().getInventory().getAvailableStacks(actual);
            for (var e : actual) { charge(128); addKey(e.getKey()); }
            network.add("advertised_inventory", keys.amounts(actual));
        });
        section("registered_patterns", () -> {
            var registered = new JsonArray();
            for (var p : ((GraphRequestTracker) service).gtlcore$registeredGraphPatterns()) registered.add(pattern(p));
            network.add("registered_pattern_order", registered);
            for (var key : service.getCraftables(k -> true)) { addKey(key); for (var p : service.getCraftingFor(key)) pattern(p); }
        });
        // Include provider slots as well as the service's deduplicated table, including inactive providers.
        section("nodes", () -> {
            var list = new ArrayList<IGridNode>();
            var ids = new IdentityHashMap<IGridNode, Integer>();
            for (var n : grid.getNodes()) { charge(512); ids.put(n, list.size()); list.add(n); }
            for (var n : list) section("node:" + ids.get(n), () -> {
                Object owner = n.getOwner();
                var o = Data.object("id", ids.get(n), "owner_class", owner.getClass().getName(), "active", n.isActive(),
                        "powered", n.isPowered(), "booted", n.hasGridBooted(), "used_channels", n.getUsedChannels(),
                        "max_channels", n.getMaxChannels(), "dimension", n.getLevel().dimension().location().toString());
                nodes.add(o);
                BlockEntity be = owner instanceof BlockEntity b ? b : null;
                Object holder = Reflect.call(owner, "getHolder");
                if (holder instanceof BlockEntity b) be = b;
                BlockPos pos = be != null ? be.getBlockPos() : (Reflect.call(owner, "getPos") instanceof BlockPos p ? p : null);
                if (pos != null) o.add("position", Data.JSON.toJsonTree(new int[]{pos.getX(), pos.getY(), pos.getZ()}));
                var links = new JsonArray();
                for (var link : n.getConnections()) links.add(Data.object("node", ids.get(link.getOtherSide(n)), "in_world", link.isInWorld(), "channels", link.getUsedChannels()));
                o.add("connections", links);
                if (DumpConfig.nodeNbt.get()) {
                    CompoundTag tag = null;
                    if (be != null) tag = be.saveWithFullMetadata();
                    else if (owner instanceof IPart part) { tag = new CompoundTag(); part.writeToNBT(tag); }
                    if (tag != null) { String snbt = tag.toString(); charge(snbt.length() * 2L); o.addProperty("nbt", snbt); }
                }
                var provider = n.getService(ICraftingProvider.class);
                if (provider != null) {
                    o.addProperty("provider_class", provider.getClass().getName());
                    o.addProperty("priority", provider.getPatternPriority()); o.addProperty("busy", provider.isBusy());
                    var pids = new JsonArray(); for (var p : provider.getAvailablePatterns()) pids.add(pattern(p));
                    o.add("patterns_in_provider_order", pids);
                    var emit = provider.getEmitableItems(); emit.forEach(this::addKey); o.add("emitable", keys.keys(emit));
                }
            });
        });
        // Gather all possible candidates before sampling isValid/remaining-key callbacks. No CGSE variant cap.
        for (int i = 0; i < pending.size() && !exhausted; i++) {
            IPatternDetails p = pending.get(i);
            section("pattern_keys:" + i, () -> {
                for (var out : p.getOutputs()) addKey(out.what());
                for (var in : p.getInputs()) for (var candidate : in.getPossibleInputs()) addKey(candidate.what());
            });
        }
        section("emitable", () -> {
            Object raw = Reflect.call(providers, "getEmittableKeys");
            if (raw instanceof Set<?> set) for (Object k : set) addKey((AEKey) k);
            var emitted = new JsonArray(); for (var e : universe) if (service.canEmitFor(e.getKey())) emitted.add(keys.id(e.getKey()));
            network.add("emitable", emitted);
        });
        section("producer_order", () -> {
            var order = new JsonObject();
            for (var e : universe) {
                var ps = new JsonArray(); for (var p : service.getCraftingFor(e.getKey())) ps.add(pattern(p));
                if (!ps.isEmpty()) order.add(keys.id(e.getKey()), ps);
            }
            network.add("producer_order", order);
        });
        for (int i = 0; i < pending.size() && !exhausted; i++) {
            IPatternDetails p = pending.get(i);
            var o = Data.object("id", patternIds.get(p), "class", p.getClass().getName()); patterns.add(o);
            section("pattern:" + i, () -> {
                o.addProperty("definition", keys.id(p.getDefinition()));
                o.addProperty("external_push", p.supportsPushInputsToExternalInventory());
                o.add("outputs", keys.stacks(p.getOutputs()));
                var priorities = new JsonArray(); for (var provider : service.getProviders(p)) priorities.add(provider.getPatternPriority());
                o.add("provider_priorities_in_dispatch_order", priorities);
                var inputs = new JsonArray(); o.add("inputs", inputs);
                for (var in : p.getInputs()) {
                    var input = Data.object("multiplier", Long.toString(in.getMultiplier())); inputs.add(input);
                    input.add("possible", keys.stacks(in.getPossibleInputs()));
                    var candidates = new LinkedHashSet<AEKey>();
                    for (var possible : in.getPossibleInputs()) {
                        candidates.add(possible.what());
                        for (var match : universe.findFuzzy(possible.what(), FuzzyMode.IGNORE_ALL)) candidates.add(match.getKey());
                    }
                    var checked = new JsonArray(); input.add("candidate_checks", checked);
                    for (var candidate : candidates) {
                        charge(192);
                        boolean valid = in.isValid(candidate, level);
                        checked.add(Data.object("key", keys.id(candidate), "valid", valid,
                                "remaining", valid ? keys.id(in.getRemainingKey(candidate)) : null));
                    }
                }
                charge(o.toString().length() * 2L);
            });
        }
        section("extractable", () -> {
            var permitted = new JsonObject(); network.add("extractable_inventory", permitted);
            if (source == null) throw new IllegalStateException("No action source for permission-aware inventory snapshot");
            for (var e : universe) {
                charge(64);
                long value = grid.getStorageService().getInventory().extract(e.getKey(), Long.MAX_VALUE, Actionable.SIMULATE, source);
                if (value != 0) permitted.addProperty(keys.id(e.getKey()), Long.toString(value));
            }
        });
        section("cpus", () -> {
            var cpus = new JsonArray(); network.add("cpus", cpus);
            for (var cpu : service.getCpus()) {
                var name = cpu.getName();
                var c = Data.object("class", cpu.getClass().getName(), "name", name == null ? null : name.getString(), "busy", cpu.isBusy(),
                        "storage", Long.toString(cpu.getAvailableStorage()), "coprocessors", cpu.getCoProcessors(), "selection", cpu.getSelectionMode().name());
                var job = cpu.getJobStatus();
                if (job != null) c.add("job", Data.object("crafting", keys.stack(job.crafting()), "total", Long.toString(job.totalItems()),
                        "progress", Long.toString(job.progress()), "elapsed_ns", Long.toString(job.elapsedTimeNanos())));
                cpus.add(c);
            }
        });
        if (keys.failed > 0) { complete = false; errors.add(Data.object("section", "resources", "failed_keys", keys.failed)); }
        estimatedBytes += keys.estimatedBytes;
        network.add("capture_errors", errors); network.addProperty("complete", complete);
        network.addProperty("pattern_count", pending.size()); network.addProperty("capture_ns", Long.toString(System.nanoTime() - start));
        network.addProperty("end_server_tick", level.getServer().getTickCount());
        network.addProperty("estimated_retained_bytes", Long.toString(estimatedBytes));
        // Do not retain live pattern/provider handles or worlds after the server-thread capture.
        pending.clear(); patternIds.clear(); universe.clear();
    }
}
