package org.gtlcore.test;

import org.gtlcore.gtlcore.integration.ae2.graph.*;
import org.cgse.core.*;

import net.minecraft.world.level.Level;

import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.me.service.CraftingService;
import com.google.gson.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Local snapshot fixture exporter. It is never included in the production mod. */
public final class GraphFixtureDump {

    private static CompletableFuture<CapturedPatternCatalog.Prepared> pending;
    private static PlanningScheduler scheduler;
    private static GtlPatternCatalog.Snapshot snapshot;
    private static final Map<AEKey, String> ids = new LinkedHashMap<>();
    private static JsonObject root;
    private static long started;
    private static JsonArray keys;

    private static String key(AEKey key) {
        return ids.computeIfAbsent(key, k -> {
            String id = "k" + ids.size();
            JsonObject row = new JsonObject();
            row.addProperty("key", id);
            row.addProperty("label", k.toString());
            row.addProperty("snbt", k.toTagGeneric().toString());
            keys.add(row);
            return id;
        });
    }

    private static JsonObject amounts(Map<AEKey, Long> amounts) {
        JsonObject result = new JsonObject();
        amounts.forEach((k, n) -> result.addProperty(key(k), Long.toString(n)));
        return result;
    }

    public static void start(IGrid grid, Level level, IActionSource source) throws Exception {
        if (pending != null) throw new IllegalStateException("Dump already running");
        started = System.nanoTime();
        ids.clear();
        root = new JsonObject();
        keys = new JsonArray();
        root.addProperty("schema", 1);
        root.addProperty("quantityEncoding", "decimal strings");
        root.addProperty("source", "original GTL-Sky (2).zip, isolated server copy");
        var service = (CraftingService) grid.getCraftingService();
        var targets = new LinkedHashSet<>(service.getCraftables(k -> true));
        if (targets.isEmpty()) throw new IllegalStateException("No craftable targets");
        var budget = new PlanningBudget(0, 32_000_000, 1L << 30, () -> false, System::nanoTime);
        var capture = new GtlPatternCatalog().begin(grid, service, level, source, targets.iterator().next(), targets, budget);
        while (!capture.step()) {}
        snapshot = capture.result();
        root.addProperty("boundedAlternatives", snapshot.structure().boundedAlternatives());
        root.addProperty("providerEpoch", Long.toString(snapshot.epoch()));
        JsonObject allStock = new JsonObject();
        for (var entry : grid.getStorageService().getCachedInventory())
            allStock.addProperty(key(entry.getKey()), Long.toString(entry.getLongValue()));
        root.add("networkStock", allStock);
        root.add("planningStock", amounts(snapshot.stock()));
        JsonArray emit = new JsonArray();
        snapshot.emitable().forEach(k -> emit.add(key(k)));
        root.add("external", emit);
        JsonArray ts = new JsonArray();
        targets.forEach(k -> ts.add(key(k)));
        root.add("targets", ts);
        JsonArray raw = new JsonArray();
        var fingerprints = new PatternFingerprint.Context();
        JsonObject discovery = new JsonObject();
        snapshot.structure().dependencies().forEach((k, sources) -> {
            JsonArray bindings = new JsonArray();
            sources.forEach(s -> bindings.add(fingerprints.of(s.values())));
            discovery.add(key(k), bindings);
        });
        root.add("discovery", discovery);
        var field = CapturedPatternCatalog.class.getDeclaredField("entries");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var entries = (List<CapturedPatternCatalog.Entry>) field.get(snapshot.structure().catalog());
        for (var entry : entries) {
            JsonObject p = new JsonObject();
            p.addProperty("priority", entry.priority());
            p.addProperty("binding", fingerprints.of(entry.values()));
            LinkedHashSet<AEKey> dependencies = new LinkedHashSet<>();
            entry.values().inputs().forEach(i -> i.choices().forEach(c -> dependencies.add(c.stack().what())));
            entry.pattern().dependencies().forEachRemaining(dependencies::add);
            JsonArray deps = new JsonArray();
            dependencies.forEach(k -> deps.add(key(k)));
            p.add("dependencies", deps);
            p.addProperty("definition", entry.handle().getDefinition().toTagGeneric().toString());
            p.addProperty("boundedAlternatives", entry.pattern().bounded());
            JsonArray providers = new JsonArray();
            for (var provider : service.getProviders(entry.handle()))
                providers.add(provider.getClass().getName() + (provider instanceof com.gregtechceu.gtceu.api.machine.MetaMachine m ? "@" + m.getPos() : ""));
            p.add("providers", providers);
            raw.add(p);
        }
        root.add("patterns", raw);
        scheduler = new PlanningScheduler(4, 4, 4096, 2_000_000L);
        pending = scheduler.submit(snapshot.structure().catalog().build(budget), budget);
        System.out.println("[AE Dump] captured targets=" + targets.size() + " patterns=" + raw.size() + " stock=" + snapshot.stock().size() + " bounded=" + snapshot.structure().boundedAlternatives());
    }

    public static void poll() throws Exception {
        if (pending == null || !pending.isDone()) return;
        try {
            var compiled = pending.get().compiler();
            JsonArray recipes = new JsonArray();
            for (var recipe : compiled.catalog()) {
                JsonObject r = new JsonObject();
                r.addProperty("id", recipe.id());
                r.addProperty("binding", recipe.binding());
                JsonArray slots = new JsonArray();
                for (var slot : recipe.slots()) {
                    JsonObject s = new JsonObject();
                    s.addProperty("key", key(slot.key()));
                    s.addProperty("amount", Long.toString(slot.amount()));
                    s.addProperty("slot", slot.inputSlot());
                    s.addProperty("configuration", slot.configuration());
                    s.addProperty("reusable", slot.reusable());
                    slots.add(s);
                }
                r.add("slots", slots);
                r.add("outputs", amounts(recipe.outputs()));
                r.add("executionOutputs", amounts(recipe.executionOutputs()));
                recipes.add(r);
            }
            root.add("recipes", recipes);
            root.add("keys", keys);
            root.addProperty("wallMs", (System.nanoTime() - started) / 1_000_000.0);
            Path path = Path.of("../ae-dumps/sky2-all.json").toAbsolutePath().normalize();
            Files.createDirectories(path.getParent());
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root));
            System.out.println("[AE Dump] saved=" + path + " recipes=" + recipes.size() + " keys=" + keys.size());
        } finally {
            scheduler.close();
            pending = null;
            scheduler = null;
            snapshot = null;
            root = null;
            ids.clear();
        }
    }
}
