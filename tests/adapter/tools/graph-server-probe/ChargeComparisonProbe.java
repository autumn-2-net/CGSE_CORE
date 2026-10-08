package org.gtlcore.test;

import org.gtlcore.gtlcore.integration.ae2.AEUtils;
import org.gtlcore.gtlcore.integration.ae2.graph.*;

import net.minecraft.world.level.Level;

import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.*;
import com.google.gson.*;

import java.nio.file.*;
import java.util.concurrent.CompletableFuture;

/** Local read-only planner comparison; never submits either plan. */
public final class ChargeComparisonProbe {

    private static IGrid grid;
    private static Level level;
    private static IActionSource source;
    private static AEKey key;
    private static CompletableFuture<GraphStressProbe.Sample> pending;
    private static final JsonArray results = new JsonArray();
    private static int stage;

    public static void start(IGrid g, Level l, IActionSource s, AEKey k) {
        if (pending != null) throw new IllegalStateException("Already running");
        grid = g;
        level = l;
        source = s;
        key = k;
        stage = 0;
        if (grid.getCraftingService().getCraftingFor(key).isEmpty()) throw new IllegalStateException("Target providers not loaded");
        System.out.println("[Charge Compare] START target=" + key + " legacy_mode=" + AEUtils.getCalculationMode() + " amount=" + Long.MAX_VALUE);
        pending = GraphStressProbe.baselineMaximum(grid, level, source, key);
    }

    public static void tick() {
        if (pending == null || !pending.isDone()) return;
        var sample = pending.join();
        var row = new JsonObject();
        row.addProperty("engine", stage == 0 ? "LEGACY" : "GRAPH");
        row.addProperty("failure", sample.failure());
        row.addProperty("wall_ms", sample.wall() / 1e6);
        row.addProperty("work_ms", sample.work() / 1e6);
        var plan = sample.plan();
        if (plan != null) {
            row.addProperty("amount", Long.toString(plan.finalOutput().amount()));
            row.addProperty("simulation", plan.simulation());
            row.add("used", amounts(plan.usedItems()));
            row.add("missing", amounts(plan.missingItems()));
            row.add("external", amounts(plan.emittedItems()));
            var patterns = new JsonArray();
            plan.patternTimes().forEach((p, n) -> {
                var r = new JsonObject();
                r.addProperty("binding", PatternFingerprint.of(p));
                r.addProperty("runs", Long.toString(n));
                var inputs = new JsonArray();
                for (var slot : p.getInputs()) {
                    var alternatives = new JsonObject();
                    for (var a : slot.getPossibleInputs()) alternatives.addProperty(a.what().toString(), java.math.BigInteger.valueOf(a.amount()).multiply(java.math.BigInteger.valueOf(slot.getMultiplier())).toString());
                    inputs.add(alternatives);
                }
                r.add("inputs", inputs);
                var outputs = new JsonObject();
                for (var o : p.getOutputs()) outputs.addProperty(o.what().toString(), Long.toString(o.amount()));
                r.add("outputs", outputs);
                patterns.add(r);
            });
            row.add("patterns", patterns);
            if (plan instanceof AeGraphPlan p) {
                row.addProperty("result", p.graph().result().name());
                row.addProperty("exact_counts", p.graph().patternTimesExact().toString());
                row.addProperty("exact_missing", p.graph().missingExact().toString());
            }
        }
        results.add(row);
        System.out.println("[Charge Compare] engine=" + (stage == 0 ? "LEGACY" : "GRAPH") + " plan=" + (plan != null) + " simulation=" + (plan != null && plan.simulation()) + " recipes=" + (plan == null ? 0 : plan.patternTimes().size()) + " wall_ms=" + sample.wall() / 1e6 + " failure=" + sample.failure());
        if (stage++ == 0) {
            pending = GraphStressProbe.graphMaximum(grid, level, source, key);
            return;
        }
        pending = null;
        try {
            Files.writeString(Path.of("charge-comparison.json"), new GsonBuilder().setPrettyPrinting().create().toJson(results));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        System.out.println("[Charge Compare] DONE");
    }

    private static JsonObject amounts(KeyCounter counter) {
        var result = new JsonObject();
        for (var e : counter) result.addProperty(e.getKey().toString(), Long.toString(e.getLongValue()));
        return result;
    }
}
