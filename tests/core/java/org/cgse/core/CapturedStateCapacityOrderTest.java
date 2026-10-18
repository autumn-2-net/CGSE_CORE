// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Earlier wear can exhaust later work without disabling that rule immediately. */
public final class CapturedStateCapacityOrderTest {
    private static int cases, checks, feedback;
    private static long maxWork, maxBytes;
    private record Fixture(List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock, int n, long tools) {}
    private record Wear(boolean heavy) implements StateFamily<String> {
        public String id() { return heavy ? "heavy" : "light"; }
        public boolean accepts(String key) { return key.startsWith("tool:") && durability(key) >= (heavy ? 2 : 1); }
        public long capacity(String key) { return accepts(key) ? heavy ? 1 : durability(key) : 0; }
        public String nominalAfterOne(String key) { return heavy ? "tool:1" : durability(key) == 1 ? null : "tool:" + (durability(key) - 1); }
    }

    public static void main(String[] args) throws Exception {
        for (int n : new int[]{1, 2, 7, 31}) for (int tools : new int[]{1, 2})
            for (boolean mixed : List.of(false, true)) for (int order = 0; order < 4; order++) {
                var fixture = fixture(n, tools, mixed, order, false, false);
                var plan = solve(fixture, order % 2 == 0, Map.of(), false);
                if (n == 7 && tools == 1 && !mixed) runtime(plan);
            }
        solve(fixture(127, 1, false, 0, false, false), true, Map.of("tool:1", 1L), false);
        solve(fixture(3, Long.MAX_VALUE / 4, false, 0, false, false), false, Map.of(), false);
        var forced = fixture(7, 2, true, 0, false, false);
        var stock = new LinkedHashMap<>(forced.stock()); stock.put("product", 1L);
        solve(new Fixture(forced.entries(), stock, forced.n(), forced.tools()), true, Map.of("tool:1", 2L), true);
        boundaries(); handoff(); cancelled();
        check(feedback > 0, "No guaranteed-use order feedback exercised");
        System.out.println("CapturedStateCapacityOrderTest: cases=" + cases + "; assertions=" + checks +
                "; capacity_orders=" + feedback + "; max_work=" + maxWork + "; max_bytes=" + maxBytes);
    }

    private static GraphPlan<String> solve(Fixture fixture, boolean publicEntry, Map<String, Long> seeds, boolean force) {
        var budget = budget(40_000_000); budget.enableMetrics(); GraphPlan<String> plan;
        if (publicEntry) {
            try (var work = new CapturedCatalog<>(fixture.entries()).begin("product", 1, fixture.stock(), Set.of(), seeds, false, force, budget)) {
                while (!work.step()) {}
                plan = work.result().plan();
                check(plan.feasible() && !work.result().coverage().complete(), "Known ordering lost: " + plan.result() + "; " + budget.diagnostics());
            }
        } else {
            try (var work = new CapturedStatePlanning<>(fixture.entries(), "product", 1, fixture.stock(), Set.of(), seeds, false, force, budget)) {
                while (!work.step()) {}
                plan = work.result();
                check(plan != null, "Known ordering lost: " + work.outcome() + "; " + budget.diagnostics());
            }
        }
        check(budget.reservedBytes() == 0, "Order recovery leaked");
        check(budget.searchWork() < 200_000 && budget.peakBytes() < (16L << 20), "Small ordering inflated its work or memory");
        PlanningCostTraceTest.conserved(budget);
        maxWork = Math.max(maxWork, budget.searchWork()); maxBytes = Math.max(maxBytes, budget.peakBytes()); cases++;
        if (budget.diagnostics().contains("retry_same_counts") && budget.diagnostics().contains("feedback=guaranteed_uses")) feedback++;
        verify(plan, fixture, seeds); return plan;
    }

    private static Fixture fixture(int n, long tools, boolean mixed, int order, boolean gated, boolean manufacture) {
        // Independent constructive witness: light n times leaves tool:2, then
        // heavy once leaves tool:1. Finish uses n light parts and one heavy part.
        // A gate deliberately makes this word unavailable in the negative case.
        long left = n + 2L;
        for (int i = 0; i < n; i++) { check(left > 0, "Invalid light witness"); left--; }
        check(left == 2, "Invalid heavy witness");
        var entries = new ArrayList<CapturedCatalog.Entry<String>>();
        for (boolean heavy : List.of(true, false)) {
            var rule = new Wear(heavy); String key = "tool:" + (n + 2), id = rule.id();
            var inputs = new ArrayList<>(List.of(new CapturedRecipe.Input<>(tools, List.of(new CapturedRecipe.Candidate<>(
                    new CapturedRecipe.Amount<>(key, 1), rule.nominalAfterOne(key), false, false, rule)),
                    mixed ? CapturedRecipe.InputMode.MIXED : CapturedRecipe.InputMode.INDIVISIBLE), ordinary(id + "Raw", 1)));
            var outputs = new ArrayList<>(List.of(new CapturedRecipe.Amount<>(id + "Part", 1)));
            if (gated) { if (heavy) outputs.add(new CapturedRecipe.Amount<>("gate", n)); else inputs.add(ordinary("gate", 1)); }
            if (order % 2 != 0) Collections.reverse(inputs);
            entries.add(new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, outputs, false, false)));
        }
        var finish = new ArrayList<>(List.of(ordinary("heavyPart", 1), ordinary("lightPart", n)));
        if (order >= 2) Collections.reverse(finish);
        entries.add(new CapturedCatalog.Entry<>("finish", "finish", new CapturedRecipe<>(finish, List.of(new CapturedRecipe.Amount<>("product", 1)), false, false)));
        if (manufacture) entries.add(new CapturedCatalog.Entry<>("build", "build", new CapturedRecipe<>(List.of(ordinary("metal", 1)),
                List.of(new CapturedRecipe.Amount<>("tool:" + (n + 2), 1)), false, false)));
        Collections.rotate(entries, order); if (order % 2 != 0) Collections.reverse(entries);
        var stock = new LinkedHashMap<String, Long>();
        if (order % 2 == 0) stock.put("tool:" + (n + 2), tools);
        stock.put("heavyRaw", 1L); stock.put("lightRaw", (long) n);
        if (order % 2 != 0) stock.put("tool:" + (n + 2), tools);
        return new Fixture(List.copyOf(entries), stock, n, tools);
    }

    private static void verify(GraphPlan<String> plan, Fixture f, Map<String, Long> seeds) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        var inventory = new LinkedHashMap<String, BigInteger>();
        plan.initialExact().forEach((key, count) -> {
            check(count.signum() >= 0 && count.compareTo(BigInteger.valueOf(f.stock().getOrDefault(key, 0L))) <= 0, "Invented initial entity/input");
            inventory.put(key, count);
        });
        var counts = new LinkedHashMap<String, BigInteger>();
        plan.patternTimesExact().forEach((id, count) -> counts.merge(plan.recipes().get(id).binding(), count, BigInteger::add));
        check(BigInteger.valueOf(f.n()).equals(counts.get("light")) && BigInteger.ONE.equals(counts.get("heavy")) &&
                BigInteger.ONE.equals(counts.get("finish")), "Order recovery changed material-chain counts");
        for (var recipe : plan.recipes().values()) {
            var source = f.entries().stream().filter(entry -> entry.binding().equals(recipe.binding())).findFirst().orElseThrow();
            var filled = new LinkedHashMap<Integer, BigInteger>(); var whole = new LinkedHashMap<Integer, String>();
            var outputs = new LinkedHashMap<String, BigInteger>();
            for (var slot : recipe.slots()) {
                var input = source.recipe().inputs().get(slot.inputSlot()); var candidate = input.candidates().get(0);
                filled.merge(slot.inputSlot(), BigInteger.valueOf(slot.amount()), BigInteger::add);
                var prior = whole.put(slot.inputSlot(), slot.key());
                check(source.recipe().mixed(input) || prior == null || prior.equals(slot.key()), "Split whole-key cohort");
                if (candidate.family() instanceof Wear wear) {
                    check(slot.key().startsWith("tool:") && durability(slot.key()) >= (wear.heavy() ? 2 : 1), "Ineligible physical tool");
                    long left = wear.heavy() ? 1 : durability(slot.key()) - 1;
                    if (left > 0) add(outputs, "tool:" + left, slot.amount());
                } else check(slot.key().equals(candidate.stack().what()), "Changed source input");
            }
            for (int i = 0; i < source.recipe().inputs().size(); i++)
                check(BigInteger.valueOf(source.recipe().inputs().get(i).multiplier()).equals(filled.get(i)), "Lost simultaneous tool/input demand");
            source.recipe().outputs().forEach(output -> add(outputs, output.what(), output.amount()));
            check(outputs.equals(exact(recipe.executionOutputs())), "Changed literal tool return");
        }
        var cursor = new PlanCursor(plan.steps()); int firings = 0;
        while (cursor.current() != null) {
            var batch = cursor.current(); var recipe = plan.recipes().get(batch.recipe());
            for (long run = 0; run < batch.runs(); run++) {
                for (var slot : recipe.slots()) take(inventory, slot.key(), slot.amount());
                recipe.executionOutputs().forEach((key, count) -> add(inventory, key, count));
                check(++firings <= f.n() + 4, "Unexpected source expansion");
            }
            cursor.dispatched(batch.runs());
        }
        check(inventory.getOrDefault("product", BigInteger.ZERO).signum() > 0, "No delivery");
        seeds.forEach((key, amount) -> check(inventory.getOrDefault(key, BigInteger.ZERO).compareTo(BigInteger.valueOf(amount)) >= 0, "Lost exact final seed"));
    }

    private static void boundaries() {
        var fixture = fixture(7, 1, false, 0, true, true);
        for (int mode = 0; mode < 3; mode++) {
            var stock = new LinkedHashMap<>(fixture.stock());
            if (mode == 1) stock.put("tool:9", 2L);
            if (mode == 2) stock.put("metal", 1L);
            var f = new Fixture(fixture.entries(), stock, 7, 1);
            if (mode > 0) {
                var plan = solve(f, true, Map.of(), false);
                if (mode == 2) check(plan.recipes().values().stream().anyMatch(r -> r.binding().equals("build")), "Replacement route disappeared");
                runtime(plan); continue;
            }
            var budget = budget(40_000_000); budget.enableMetrics();
            try (var work = new CapturedCatalog<>(f.entries()).begin("product", 1, stock, false, false, budget)) {
                boolean sawOrder = false;
                while (!work.step()) sawOrder |= budget.diagnostics().contains("feedback=guaranteed_uses");
                check(!work.result().plan().feasible() && work.result().plan().result() != GraphPlan.Result.INFEASIBLE,
                        "Forced heavy-first order invented a tool or became a global negative proof");
                // Diagnostics retain a bounded tail; later failed refinements
                // may evict the initial admission, so observe it while pending.
                check(sawOrder, "Gated failure did not exercise order repair");
            }
            check(budget.reservedBytes() == 0 && budget.searchWork() < 100_000, "Impossible order renewed allowance or leaked");
        }
        var f = fixture(7, 1, false, 0, false, false);
        var stock = new LinkedHashMap<>(f.stock()); stock.put("lightRaw", 6L);
        var budget = budget(40_000_000);
        try (var work = new CapturedCatalog<>(f.entries()).begin("product", 1, stock, false, false, budget)) {
            while (!work.step()) {}
            check(!work.result().plan().feasible(), "Reordering supplied missing raw material");
        }
        check(budget.reservedBytes() == 0, "Missing ordinary input leaked");
        for (long cap : new long[]{1024, 4096, 20_000}) {
            budget = budget(cap);
            try (var work = new CapturedStatePlanning<>(f.entries(), "product", 1, f.stock(), Set.of(), Map.of(), false, false, budget)) {
                try { while (!work.step()) {} } catch (PlanningBudget.Exhausted expected) { /* Inconclusive. */ }
                if (work.result() != null) verify(work.result(), f, Map.of());
            }
            check(budget.reservedBytes() == 0 && budget.searchWork() <= cap + 1, "Order retry renewed work or leaked on limit");
        }
    }

    private static void runtime(GraphPlan<String> plan) {
        final class Host implements GraphJobRuntime.Adapter<String> {
            GraphJobRuntime<String> job = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            final Map<String, Long> physical = new LinkedHashMap<>(plan.initial());
            final List<Map<String, Long>> pending = new ArrayList<>(); long delivered;
            public long capacity(GraphRecipe<String> recipe, long requested) { return requested; }
            public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
                inputs.forEach((key, amount) -> { check(physical.getOrDefault(key, 0L) >= amount, "Tool still in flight"); physical.merge(key, -amount, Long::sum); });
                var outputs = new LinkedHashMap<String, Long>(); recipe.executionOutputs().forEach((key, amount) -> outputs.put(key, Math.multiplyExact(amount, runs)));
                pending.add(outputs); return GraphJobRuntime.Outcome.ACCEPTED;
            }
            public long deliver(String key, long amount) { delivered += amount; return amount; }
            public long refund(String key, long amount) { return amount; }
        }
        var host = new Host();
        for (int tick = 0; tick < 2000 && host.delivered == 0; tick++) {
            host.job.tick(host, tick * 20L, 8, () -> 0L); host.job = new GraphJobRuntime<>(host.job.snapshot());
            if (tick % 3 != 2) continue;
            Collections.reverse(host.pending);
            for (var outputs : host.pending) outputs.forEach((key, amount) -> {
                host.physical.merge(key, amount, Long::sum); check(host.job.accept(key, amount, false) == amount, "Lost return on reload");
            });
            host.pending.clear();
        }
        check(host.delivered == 1, "Delayed physical returns stalled");
    }

    private static void handoff() throws Exception {
        var f = fixture(7, 1, false, 0, false, false); var budget = budget(40_000_000);
        var left = Executors.newSingleThreadExecutor(); var right = Executors.newSingleThreadExecutor();
        try (var work = new CapturedStatePlanning<>(f.entries(), "product", 1, f.stock(), Set.of(), Map.of(), false, false, budget)) {
            int visits = 0; boolean done = false;
            while (!done) done = ((visits++ & 1) == 0 ? left : right).submit(() -> {
                for (int i = 0; i < 16; i++) if (work.step()) return true; return false;
            }).get();
            check(work.result() != null && visits > 1, "Lost order feedback across workers"); verify(work.result(), f, Map.of());
        } finally { left.shutdownNow(); right.shutdownNow(); }
        check(budget.reservedBytes() == 0, "Worker handoff leaked");
    }

    private static void cancelled() {
        var f = fixture(7, 1, false, 0, false, false); var cancel = new AtomicBoolean();
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, cancel::get, System::nanoTime); budget.enableMetrics();
        try (var work = new CapturedStatePlanning<>(f.entries(), "product", 1, f.stock(), Set.of(), Map.of(), false, false, budget)) {
            while (!work.step()) if (budget.diagnostics().contains("retry_same_counts")) { cancel.set(true); break; }
            check(cancel.get(), "No pending order recovery to cancel");
            try { work.step(); throw new AssertionError("Ignored cancellation"); }
            catch (java.util.concurrent.CancellationException expected) { checks++; }
        }
        check(budget.reservedBytes() == 0, "Cancelled order feedback leaked");
    }

    private static CapturedRecipe.Input<String> ordinary(String key, long count) { return new CapturedRecipe.Input<>(count, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false))); }
    private static long durability(String key) { return Long.parseLong(key.substring(5)); }
    private static LinkedHashMap<String, BigInteger> exact(Map<String, Long> values) { var out = new LinkedHashMap<String, BigInteger>(); values.forEach((key, amount) -> add(out, key, amount)); return out; }
    private static void add(Map<String, BigInteger> values, String key, long amount) { values.merge(key, BigInteger.valueOf(amount), BigInteger::add); }
    private static void take(Map<String, BigInteger> values, String key, long amount) { var left = values.getOrDefault(key, BigInteger.ZERO).subtract(BigInteger.valueOf(amount)); check(left.signum() >= 0, "Unfunded literal input " + key); values.put(key, left); }
    private static PlanningBudget budget(long cap) { return new PlanningBudget(0, cap, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
