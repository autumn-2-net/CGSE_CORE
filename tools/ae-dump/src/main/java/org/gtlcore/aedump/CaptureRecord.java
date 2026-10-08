// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.gtlcore.aedump;

import appeng.api.networking.crafting.*;
import appeng.api.stacks.AEKey;
import com.google.gson.*;
import org.gtlcore.gtlcore.integration.ae2.graph.*;
import org.cgse.core.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CaptureRecord {
    public final long id, created = System.currentTimeMillis();
    public final UUID owner;
    public final Data.Keys keys = new Data.Keys();
    public final JsonObject request;
    public final CompletableFuture<NetworkSnapshot> network = new CompletableFuture<>();
    public final AtomicBoolean exporting = new AtomicBoolean();
    public volatile Future<ICraftingPlan> future;
    public volatile Runnable deferredNetwork;
    public volatile Trace trace;
    public volatile JsonObject input = new JsonObject(), graphPlan = null, aePlan = null, coordinator = new JsonObject();
    public volatile JsonObject budget;
    public volatile String result = "RUNNING", error, saved;
    public volatile boolean fallback, done;
    public void ensureNetwork() {
        Runnable capture;
        synchronized (this) { capture = deferredNetwork; deferredNetwork = null; }
        if (capture != null) capture.run();
    }
    private final Deque<JsonObject> states = new ArrayDeque<>();

    public CaptureRecord(long id, UUID owner, String engine, AEKey target, long amount, CalculationStrategy strategy, boolean replan) {
        this.id = id; this.owner = owner;
        request = Data.object("schema", "ae-request-v1", "id", Long.toString(id), "created_at", Instant.ofEpochMilli(created).toString(),
                "owner_uuid", owner == null ? null : owner.toString(), "engine", engine, "target", keys.id(target),
                "amount", Long.toString(amount), "strategy", strategy.name(), "replan", replan);
    }
    public void attach(Object work) {
        PlanningBudget b = Reflect.get(work, "budget");
        if ((Object) b instanceof Trace.Access access) { trace = new Trace(this); access.aedump$set(trace); }
    }
    public synchronized void workState(Object work) {
        Object solver = RequestState.solver(work);
        coordinator = Data.JSON.toJsonTree(RequestState.coordinator(work)).getAsJsonObject();
        fallback = Boolean.TRUE.equals(Reflect.get(solver, "fallbackAttempted"));
        PlanningBudget b = Reflect.get(work, "budget"); if (b != null) budget = Data.JSON.toJsonTree(Trace.budget(b)).getAsJsonObject();
        GraphCompiler<AEKey> compiler = Reflect.get(work, "compiler");
        Map<AEKey, Long> available = Reflect.get(solver, "available");
        GtlPatternCatalog.Snapshot snapshot = Reflect.get(work, "snapshot");
        var in = Data.object("schema", "cgse-solver-input-v2", "complete", compiler != null && available != null && snapshot != null,
                "target", request.get("target"), "amount", request.get("amount"), "strategy", request.get("strategy"),
                "preserve_seeds", Reflect.get(work, "preserve"), "force_craft", Reflect.get(work, "checkpoint") == null && !Boolean.TRUE.equals(Reflect.get(solver, "directEmission")),
                "catalysts", Reflect.get(work, "catalysts"));
        in.add("available", keys.amounts(available == null ? Map.of() : available));
        if (snapshot != null) {
            in.add("stock", keys.amounts(snapshot.stock())); in.add("external", keys.keys(snapshot.emitable()));
            in.addProperty("cache_hit", snapshot.cacheHit()); in.addProperty("epoch", Long.toString(snapshot.epoch()));
            in.addProperty("bounded_alternatives", snapshot.structure().boundedAlternatives());
        }
        GraphJobRuntime.ReplanCheckpoint<AEKey> checkpoint = Reflect.get(work, "checkpoint");
        if (checkpoint != null) {
            in.add("required_seeds", keys.amounts(checkpoint.recoverySeeds())); in.add("forecast", keys.amounts(checkpoint.forecast()));
        }
        var recipes = new JsonArray(); in.add("recipes", recipes);
        var order = new JsonObject(); in.add("producer_order", order);
        if (compiler != null) {
            var outputs = new LinkedHashSet<AEKey>();
            for (var r : compiler.catalog()) { recipes.add(recipe(r)); outputs.addAll(r.outputs().keySet()); }
            for (var k : outputs) { var ids = new JsonArray(); for (var r : compiler.producers(k)) ids.add(r.id()); order.add(keys.id(k), ids); }
        }
        input = in;
        GraphPlan<AEKey> plan = Reflect.get(solver, "selected"); if (plan != null) graphPlan = plan(plan);
    }
    public synchronized void state(Object work) {
        Integer nesting = Reflect.get(work, "nesting"); if (nesting == null || nesting != 0) return;
        var s = Data.JSON.toJsonTree(Reflect.scalars(work, "amount", "phase", "nesting", "pending", "seen", "sourceCore", "excluded",
                "allocationAttempted", "countAttempted", "frontierTruncated", "bootstrapTooLarge", "quantityBlocked", "stockBlocked",
                "proofAttempted", "seedAttempted", "quickSearchStarted", "quickSearchAllowance", "previewAllowance")).getAsJsonObject();
        Map<AEKey, Integer> choices = Reflect.get(work, "choices"); if (choices != null) s.add("choices", keys.amounts(choices));
        for (String field : List.of("candidate", "best", "verified", "result")) {
            GraphPlan<AEKey> p = Reflect.get(work, field);
            if (p != null) s.add(field, Data.object("result", p.result().name(), "amount", Long.toString(p.amount()), "missing", keys.amounts(p.missingExact())));
        }
        if (states.size() == 16) states.removeFirst(); states.addLast(s);
    }
    public synchronized JsonElement states() { return Data.JSON.toJsonTree(List.copyOf(states)); }
    public void finish(ICraftingPlan p, Throwable e) {
        error = Trace.stack(e);
        if (e != null) result = e instanceof CancellationException ? "CANCELLED" : "ERROR";
        else if (p != null) {
            result = p instanceof AeGraphPlan g ? g.graph().result().name() : p.simulation() ? "MISSING_INPUT" : "FEASIBLE";
            if (p instanceof AeGraphPlan g) { graphPlan = plan(g.graph()); fallback |= g.fallback(); }
            aePlan = Data.object("simulation", p.simulation(), "multiple_paths", p.multiplePaths(), "bytes", Long.toString(p.bytes()),
                    "output", keys.stack(p.finalOutput()), "used", keys.amounts(p.usedItems()), "missing", keys.amounts(p.missingItems()),
                    "emitted", keys.amounts(p.emittedItems()));
            var times = new JsonArray(); p.patternTimes().forEach((pattern, count) -> times.add(Data.object("definition", keys.id(pattern.getDefinition()), "runs", count.toString())));
            aePlan.add("pattern_times", times);
        } else result = "NO_PLAN";
        future = null; done = true;
    }
    public boolean autoExport() {
        if (result.equals("CANCELLED") || result.equals("NETWORK_ONLY")) return false;
        boolean missing = result.equals("MISSING_INPUT") || result.equals("MISSING_SEED");
        return fallback || trace != null && trace.limited ||
                !result.startsWith("FEASIBLE") && (!missing || DumpConfig.autoMissing.get());
    }
    public JsonObject recipe(GraphRecipe<AEKey> r) {
        var o = Data.object("id", r.id(), "binding", r.binding(), "outputs", keys.amounts(r.outputs()));
        var slots = new JsonArray(); o.add("slots", slots);
        for (var s : r.slots()) slots.add(Data.object("key", keys.id(s.key()), "amount", Long.toString(s.amount()), "input_slot", s.inputSlot(), "configuration", s.configuration(), "reusable", s.reusable()));
        return o;
    }
    public JsonObject plan(GraphPlan<AEKey> p) {
        var o = Data.object("result", p.result().name(), "target", keys.id(p.target()), "amount", Long.toString(p.amount()),
                "preserve_seeds", p.preserveSeeds(), "initial", keys.amounts(p.initialExact()), "seeds", keys.amounts(p.seeds()),
                "missing", keys.amounts(p.missingExact()), "seed_optimality", p.seedOptimality());
        var recipes = new JsonArray(); p.recipes().values().forEach(r -> recipes.add(recipe(r))); o.add("recipes", recipes);
        var ids = new IdentityHashMap<PlanStep, Integer>(); var list = new ArrayList<PlanStep>();
        if (p.steps() != null) { ids.put(p.steps(), 0); list.add(p.steps()); }
        o.addProperty("program_root", list.isEmpty() ? -1 : 0); var program = new JsonArray(); o.add("program", program);
        for (int i = 0; i < list.size(); i++) {
            var step = list.get(i); var n = Data.object("id", i); program.add(n);
            if (step instanceof PlanStep.Batch b) { n.addProperty("kind", "batch"); n.addProperty("recipe", b.recipe()); n.addProperty("runs", Long.toString(b.runs())); }
            else if (step instanceof PlanStep.Repeat r) { n.addProperty("kind", "repeat"); n.addProperty("times", Long.toString(r.times())); n.addProperty("body", child(r.body(), ids, list)); }
            else if (step instanceof PlanStep.Sequence s) { n.addProperty("kind", "sequence"); var a = new JsonArray(); for (var c : s.children()) a.add(child(c, ids, list)); n.add("children", a); }
        }
        return o;
    }
    private int child(PlanStep step, IdentityHashMap<PlanStep, Integer> ids, List<PlanStep> list) {
        Integer id = ids.get(step); if (id == null) { id = list.size(); ids.put(step, id); list.add(step); } return id;
    }
}
