package local.linearaudit;

import appeng.api.config.*;
import appeng.api.crafting.*;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.storage.*;
import appeng.api.stacks.*;
import appeng.crafting.CraftingCalculation;
import appeng.me.service.CraftingService;
import com.google.gson.*;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.level.Level;
import org.gtlcore.gtlcore.config.*;
import org.gtlcore.gtlcore.integration.ae2.graph.*;
import org.cgse.core.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/** LOCAL ONLY. Replay a complete archive through native MaxFast and CGSE using identical immutable inputs. */
public final class RandomOrderAudit {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final ExecutorService DRIVER = Executors.newSingleThreadExecutor(r -> daemon(r, "random-order-audit"));
    private static final ExecutorService LEGACY = Executors.newSingleThreadExecutor(r -> daemon(r, "random-order-maxfast"));
    private static final PlanningScheduler WORKER = new PlanningScheduler(1, 4, 4096, 2_000_000L);
    private static final Path DIR = Path.of("local/linear-search-20261002");
    private static final Map<String, AEKey> keys = new LinkedHashMap<>();
    private static final Map<String, IPatternDetails> patterns = new LinkedHashMap<>();
    private static final Map<IPatternDetails, String> ids = new IdentityHashMap<>();
    private static final Map<IPatternDetails, Integer> priorities = new IdentityHashMap<>();
    private static final Map<AEKey, List<IPatternDetails>> original = new LinkedHashMap<>();
    private static final List<IPatternDetails> registered = new ArrayList<>();
    private static final Set<AEKey> emitable = new LinkedHashSet<>();
    private static final Map<AEKey, Long> allStock = new LinkedHashMap<>();
    private static KeyCounter inventory;
    private static Level level;
    private static IActionSource source;
    private static IGrid frozen;
    private static FrozenService service;
    private static GtlPatternCatalog catalog = new GtlPatternCatalog();
    private static int parallel = 4096;
    private static long generation;
    private static String order;
    private static volatile CraftingCalculation tickingLegacy;
    private static volatile Future<?> tickingFuture;
    public static void tickLegacy() { var f=tickingFuture; var c=tickingLegacy; if(c!=null && f!=null && !f.isDone()) c.simulateFor(10000); }
    private static Thread daemon(Runnable r, String name) { var t = new Thread(r, name); t.setDaemon(true); return t; }
    private static AEKey key(String s) throws Exception { return AEKey.fromTagGeneric(TagParser.m_129359_(s)); }
    private static String text(ZipFile zip, String entry) throws Exception {
        return new String(zip.getInputStream(zip.getEntry(entry)).readAllBytes(), StandardCharsets.UTF_8);
    }
    private static void write(String name, JsonObject value) throws Exception {
        Files.createDirectories(DIR); Files.writeString(DIR.resolve(name), JSON.toJson(value));
    }
    private static void append(String name, JsonObject value) throws Exception {
        Files.createDirectories(DIR); Files.writeString(DIR.resolve(name), JSON.toJson(value) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    private static final class FrozenService extends CraftingService implements GraphRequestTracker {
        Map<AEKey, List<IPatternDetails>> producers = original;
        List<IPatternDetails> registry = registered;
        KeyCounter craftables = new KeyCounter();
        FrozenService(IGrid grid, IStorageService storage) {
            super(grid, storage, grid.getEnergyService());
            original.keySet().forEach(k -> craftables.add(k, 1));
        }
        @Override public Collection<IPatternDetails> getCraftingFor(AEKey k) { return producers.getOrDefault(k, List.of()); }
        @Override public Set<AEKey> getCraftables(AEKeyFilter filter) {
            Set<AEKey> out = new LinkedHashSet<>(); for (var k : producers.keySet()) if (filter.matches(k)) out.add(k); return out;
        }
        @Override public AEKey getFuzzyCraftable(AEKey k, AEKeyFilter filter) {
            for (var e : craftables.findFuzzy(k, FuzzyMode.IGNORE_ALL)) if (filter.matches(e.getKey())) return e.getKey(); return null;
        }
        @Override public boolean canEmitFor(AEKey k) { return emitable.contains(k); }
        @Override public Iterable<ICraftingProvider> getProviders(IPatternDetails p) {
            return List.of(NetworkAudit.proxy(ICraftingProvider.class, null, (m, a) -> switch (m.getName()) {
                case "getPatternPriority" -> priorities.get(p);
                case "getAvailablePatterns" -> List.of(p);
                case "getEmitableItems" -> Set.of();
                case "isBusy" -> false;
                default -> throw new AssertionError("Unexpected provider operation " + m.getName());
            }));
        }
        @Override public void gtlcore$expectGraphOutput(AEKey k) { throw new AssertionError("Planning only"); }
        @Override public long gtlcore$graphProviderGeneration() { return generation; }
        @Override public Iterable<IPatternDetails> gtlcore$registeredGraphPatterns() { return registry; }
    }
    public static CompletableFuture<String> load(Level world, IGrid grid, IActionSource action, String file) throws Exception {
        if (service != null) throw new IllegalStateException("Already loaded");
        level = world; source = action;
        try (var zip = new ZipFile(file, StandardCharsets.UTF_8)) {
            for (var line : text(zip, "resources.jsonl").lines().toList()) {
                var r = JsonParser.parseString(line).getAsJsonObject(); var k = key(r.get("snbt").getAsString());
                if (k == null) throw new AssertionError("Cannot decode key " + r.get("id")); keys.put(r.get("id").getAsString(), k);
            }
            for (var line : text(zip, "patterns.jsonl").lines().toList()) {
                var p = JsonParser.parseString(line).getAsJsonObject(); String id = p.get("id").getAsString();
                var details = PatternDetailsHelper.decodePattern((AEItemKey) keys.get(p.get("definition").getAsString()), world);
                if (details == null || !equal(details.getOutputs(), p.getAsJsonArray("outputs"))) throw new AssertionError("Pattern output differs " + id);
                var inputs = details.getInputs(); var expected = p.getAsJsonArray("inputs");
                if (inputs.length != expected.size()) throw new AssertionError("Pattern input count " + id);
                for (int i = 0; i < inputs.length; i++) {
                    var v = expected.get(i).getAsJsonObject();
                    if (inputs[i].getMultiplier() != v.get("multiplier").getAsLong() || !equal(inputs[i].getPossibleInputs(), v.getAsJsonArray("possible"))) throw new AssertionError("Pattern inputs differ " + id);
                    for (var check : v.getAsJsonArray("candidate_checks")) {
                        var c = check.getAsJsonObject(); var k = keys.get(c.get("key").getAsString());
                        if (inputs[i].isValid(k, world) != c.get("valid").getAsBoolean()) throw new AssertionError("Pattern validity differs " + id);
                        AEKey remaining = c.get("remaining").isJsonNull() ? null : keys.get(c.get("remaining").getAsString());
                        if (!Objects.equals(inputs[i].getRemainingKey(k), remaining)) throw new AssertionError("Pattern remainder differs " + id);
                    }
                }
                patterns.put(id, details); ids.put(details, id);
                int priority = Integer.MIN_VALUE;
                for (var v : p.getAsJsonArray("provider_priorities_in_dispatch_order")) priority = Math.max(priority, v.getAsInt());
                priorities.put(details, priority);
            }
            var n = JsonParser.parseString(text(zip, "network.json")).getAsJsonObject();
            if (!n.get("complete").getAsBoolean()) throw new AssertionError("Incomplete dump");
            for (var e : n.getAsJsonObject("extractable_inventory").entrySet()) if (e.getValue().getAsLong() > 0) allStock.put(keys.get(e.getKey()), e.getValue().getAsLong());
            for (var e : n.getAsJsonObject("producer_order").entrySet()) {
                var list = new ArrayList<IPatternDetails>(); e.getValue().getAsJsonArray().forEach(v -> list.add(Objects.requireNonNull(patterns.get(v.getAsString()))));
                original.put(keys.get(e.getKey()), List.copyOf(list));
            }
            n.getAsJsonArray("registered_pattern_order").forEach(v -> registered.add(patterns.get(v.getAsString())));
            n.getAsJsonArray("emitable").forEach(v -> emitable.add(keys.get(v.getAsString())));
        }
        inventory = new KeyCounter(); allStock.forEach(inventory::add);
        MEStorage storage = NetworkAudit.proxy(MEStorage.class, null, (m, a) -> switch (m.getName()) {
            case "extract" -> { if (a[2] != Actionable.SIMULATE) throw new AssertionError("Cannot extract"); yield Math.min(inventory.get((AEKey) a[0]), (long) a[1]); }
            case "getAvailableStacks" -> { var out = (KeyCounter) a[0]; for (var e : inventory) out.add(e.getKey(), e.getLongValue()); yield null; }
            case "isPreferredStorageFor" -> false;
            default -> throw new AssertionError("Unexpected storage call " + m.getName());
        });
        IStorageService ss = NetworkAudit.proxy(IStorageService.class, grid.getStorageService(), (m, a) -> switch (m.getName()) {
            case "getCachedInventory" -> inventory;
            case "getInventory" -> storage;
            case "addGlobalStorageProvider" -> null;
            default -> throw new AssertionError("Unexpected storage service call " + m.getName());
        });
        frozen = NetworkAudit.proxy(IGrid.class, grid, (m, a) -> switch (m.getName()) {
            case "getStorageService" -> ss;
            case "getCraftingService" -> service;
            default -> NetworkAudit.PASS;
        });
        service = new FrozenService(frozen, ss);
        var info = new JsonObject(); info.addProperty("ok", true); info.addProperty("patterns", patterns.size()); info.addProperty("resources", keys.size());
        info.addProperty("producer_keys", original.size()); info.addProperty("stock_keys", allStock.size()); info.addProperty("parallelism", parallel);
        info.addProperty("shufflable_lists", original.values().stream().filter(v -> v.size() > 1).count());
        write("loaded.json", info); return CompletableFuture.completedFuture(JSON.toJson(info));
    }
    private static boolean equal(GenericStack[] values, JsonArray array) {
        if (values.length != array.size()) return false;
        for (int i = 0; i < values.length; i++) { var s = array.get(i).getAsJsonObject(); if (!values[i].what().equals(keys.get(s.get("key").getAsString())) || values[i].amount() != s.get("amount").getAsLong()) return false; }
        return true;
    }
    private static List<IPatternDetails> permute(List<IPatternDetails> base, long seed, Random random) {
        var out = new ArrayList<>(base); Map<Integer, List<Integer>> positions = new LinkedHashMap<>();
        for (int i = 0; i < base.size(); i++) positions.computeIfAbsent(priorities.get(base.get(i)), unused -> new ArrayList<>()).add(i);
        for (var indexes : positions.values()) {
            var group = new ArrayList<IPatternDetails>(); for (int i : indexes) group.add(base.get(i));
            if (seed == -1) Collections.reverse(group); else if (seed != 0) Collections.shuffle(group, random);
            for (int i = 0; i < indexes.size(); i++) out.set(indexes.get(i), group.get(i));
        }
        for (int i = 0; i < base.size(); i++) if (!priorities.get(base.get(i)).equals(priorities.get(out.get(i)))) throw new AssertionError("Changed priority");
        return List.copyOf(out);
    }
    private static void reorder(long seed, String label, boolean byproducts) throws Exception {
        var random = new Random(seed); var map = new LinkedHashMap<AEKey, List<IPatternDetails>>(); int changed = 0;
        for (var e : original.entrySet()) { var out = permute(e.getValue(), seed, random); map.put(e.getKey(), out); if (!out.equals(e.getValue())) changed++; }
        service.producers = map; service.registry = permute(registered, seed, random);
        generation++; order = label;
        if (ConfigHolder.INSTANCE.ae2GraphDiscoverByproducts != byproducts) { ConfigHolder.INSTANCE.ae2GraphDiscoverByproducts = byproducts; catalog = new GtlPatternCatalog(); }
        var info = new JsonObject(); info.addProperty("order", label); info.addProperty("seed", seed); info.addProperty("generation", generation);
        info.addProperty("changed_producer_lists", changed); info.addProperty("changed_registry", !service.registry.equals(registered)); info.addProperty("byproducts", byproducts);
        var lists = new JsonObject(); map.forEach((k, v) -> { if (v.size() > 1) { var a = new JsonArray(); v.forEach(p -> a.add(ids.get(p))); lists.add(NetworkAudit.key(k), a); } }); info.add("producer_order", lists);
        append("orders.jsonl", info);
    }
    private static CompletableFuture<GtlPatternCatalog.Snapshot> capture(AEKey target, GtlPatternCatalog cat, PlanningBudget budget) {
        var f = new CompletableFuture<GtlPatternCatalog.Snapshot>();
        NetworkAudit.onServer(() -> { GraphSnapshots.enqueue(cat.begin(frozen, service, level, source, target, budget), budget, f, t -> {}); return null; }).exceptionally(e -> { f.completeExceptionally(e); return null; });
        return f;
    }
    private static JsonObject cgse(AEKey target, long amount, GtlPatternCatalog cat, long limit) throws Exception {
        var b = NetworkAudit.budget(limit, 0); long start = System.nanoTime(); var out = new JsonObject();
        try {
            var snap = capture(target, cat, b).get(45, TimeUnit.SECONDS);
            var prepared = WORKER.submit(snap.structure().catalog().build(b), b).get(45, TimeUnit.SECONDS);
            var work = new CatalystPlanningWork<AEKey>(new CatalystPolicy(parallel, ConfigHolder.INSTANCE.ae2GraphMaxExtraCatalystCopies), b,
                    policy -> new GraphPlanningWork<>(prepared.compiler(), target, amount, snap.stock(), snap.emitable(), Map.of(), true, true, b).catalysts(policy));
            GraphPlan<AEKey> plan;
            try { try { while (!work.step()) {} plan = work.result(); } catch (PlanningBudget.Exhausted e) { plan = work.limited(e); } }
            finally { work.close(); }
            if (plan.feasible()) {
                PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
                for (var e : plan.initialExact().entrySet()) if (e.getValue().compareTo(BigInteger.valueOf(inventory.get(e.getKey()))) > 0) throw new AssertionError("Initial stock exceeded " + NetworkAudit.key(e.getKey()));
            }
            out.addProperty("result", plan.result().toString()); out.add("missing", NetworkAudit.amounts(plan.missingExact()));
            out.add("initial", NetworkAudit.amounts(plan.initialExact())); out.add("seeds", NetworkAudit.amounts(plan.seeds()));
            out.addProperty("recipe_count", plan.patternTimesExact().size()); out.addProperty("plan_hash", plan.patternTimesExact().hashCode());
            out.addProperty("cache_hit", snap.cacheHit()); out.addProperty("bounded", snap.structure().boundedAlternatives());
        } catch (Exception | AssertionError e) { out.addProperty("result", "ERROR"); out.addProperty("error", e.toString()); e.printStackTrace(); }
        out.addProperty("diagnostics",b.diagnostics()); out.addProperty("work", b.nodes()); out.addProperty("peak_bytes", b.peakBytes()); out.addProperty("ms", (System.nanoTime() - start) / 1e6);
        return out;
    }
    private static boolean same(JsonObject a, JsonObject b) {
        for (String field : List.of("result", "missing", "initial", "seeds", "recipe_count", "plan_hash")) if (!Objects.equals(a.get(field), b.get(field))) return false; return true;
    }
    public static CompletableFuture<String> batch(String input, String output) {
        if (service == null) throw new IllegalStateException("Load first");
        var spec = JsonParser.parseString(input).getAsJsonObject();
        return CompletableFuture.supplyAsync(() -> {
            int done = 0;
            try {
                for (var o : spec.getAsJsonArray("orders")) {
                    var config = o.getAsJsonObject(); long seed = config.get("seed").getAsLong(); String label = config.get("name").getAsString();
                    NetworkAudit.onServer(() -> { reorder(seed, label, config.has("byproducts") && config.get("byproducts").getAsBoolean()); return null; }).get();
                    var cases = new ArrayList<JsonElement>(); spec.getAsJsonArray("cases").forEach(cases::add);
                    if (seed != 0) Collections.shuffle(cases, new Random(seed ^ 0x15ba7cf7L));
                    for (var entry : cases) {
                        var c = entry.getAsJsonObject(); var row = new JsonObject(); row.addProperty("order", order); row.addProperty("id", c.get("id").getAsString());
                        AEKey target = key(c.get("target").getAsString()); long amount = c.get("amount").getAsLong();
                        row.addProperty("target", NetworkAudit.key(target)); row.addProperty("amount", Long.toString(amount));
                        inventory = new KeyCounter(); if (c.has("stock")) { for (var e : c.getAsJsonObject("stock").entrySet()) if (e.getValue().getAsLong() > 0) inventory.add(key(e.getKey()), e.getValue().getAsLong()); } else allStock.forEach(inventory::add);
                        if (ConfigHolder.INSTANCE.ae2CalculationMode != AE2CalculationMode.MAX_FAST) throw new AssertionError("Not MaxFast");
                        long start = System.nanoTime(); var lo = new JsonObject();
                        var legacy = NetworkAudit.onServer(() -> new CraftingCalculation(level, frozen, () -> source, new GenericStack(target, amount), CalculationStrategy.REPORT_MISSING_ITEMS)).get();
                        tickingLegacy=legacy; var lf = LEGACY.submit(legacy::run); tickingFuture=lf;
                        try { var plan = lf.get(15, TimeUnit.SECONDS); lo.addProperty("result", plan.simulation() ? "MISSING_INPUT" : "FEASIBLE"); lo.add("missing", NetworkAudit.counter(plan.missingItems())); lo.add("initial", NetworkAudit.counter(plan.usedItems())); }
                        catch (TimeoutException e) { lf.cancel(true); lo.addProperty("result", "TIMEOUT"); }
                        catch (Exception e) { lo.addProperty("result", "ERROR"); lo.addProperty("error", e.toString()); }
                        tickingFuture=null; tickingLegacy=null; lo.addProperty("ms", (System.nanoTime() - start) / 1e6); row.add("maxfast", lo);
                        var go = cgse(target, amount, catalog, 20_000_000L);
                        
                        row.add("cgse", go);
                        if (c.has("verify_cache") && c.get("verify_cache").getAsBoolean()) {
                            var warm = cgse(target, amount, catalog, 20_000_000L); var cold = cgse(target, amount, new GtlPatternCatalog(), 20_000_000L);
                            row.add("warm", warm); row.add("cold", cold); row.addProperty("cache_equal", same(go, warm) && same(warm, cold));
                        }
                        append(output, row); done++;
                        if (done % 25 == 0) System.out.println("[Random Order] completed " + done + " order=" + order + " last=" + c.get("id"));
                    }
                }
                var result = new JsonObject(); result.addProperty("ok", true); result.addProperty("completed", done); result.addProperty("file", output); return result.toString();
            } catch (Throwable e) { e.printStackTrace(); var result = new JsonObject(); result.addProperty("ok", false); result.addProperty("completed", done); result.addProperty("error", e.toString()); return result.toString(); }
        }, DRIVER);
    }
}
