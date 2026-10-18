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

/** Several wear rules need a compatible order, not repeated move-to-front retries. */
public final class CapturedStateOrderFeedbackTest {
    private static int checks, cases, feedback;
    private static long maxWork, maxBytes;
    private record Fixture(List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock, int levels, int n, long tools) {}
    private record Wear(int level) implements StateFamily<String> {
        public String id() { return level == 0 ? "light" : "heavy" + level; }
        public boolean accepts(String key) { return key.startsWith("tool:") && durability(key) >= level + 1; }
        public long capacity(String key) { return accepts(key) ? level == 0 ? durability(key) : 1 : 0; }
        public String nominalAfterOne(String key) { return level > 0 ? "tool:" + level : durability(key) == 1 ? null : "tool:" + (durability(key) - 1); }
    }

    public static void main(String[] args) throws Exception {
        for (int levels : new int[]{2, 3, 5, 8}) for (int n : new int[]{2, 7, 31}) for (int order = 0; order < 8; order++) {
            var fixture = fixture(levels, n, 1, false, order, false, false);
            var plan = solve(fixture, (order & 1) == 0, Map.of(), false);
            if (levels == 3 && n == 7 && order == 0) runtime(plan);
        }
        for (int levels : new int[]{3, 5}) for (boolean mixed : List.of(false, true)) for (int order : new int[]{0, 3})
            solve(fixture(levels, 7, 2, mixed, order, false, false), true, Map.of(), false);
        solve(fixture(3, 2, Long.MAX_VALUE / 4, false, 0, false, false), false, Map.of(), false);
        var f = fixture(5, 7, 2, true, 0, false, false);
        solve(f, true, Map.of("tool:1", 2L), false);
        var stock = new LinkedHashMap<>(f.stock()); stock.put("product", 1L);
        solve(new Fixture(f.entries(), stock, f.levels(), f.n(), f.tools()), true, Map.of("tool:1", 2L), true);
        boundaries(); handoff(); cancelled();
        check(feedback > 0, "No structural order feedback exercised");
        System.out.println("CapturedStateOrderFeedbackTest: cases=" + cases + "; assertions=" + checks +
                "; structural_orders=" + feedback + "; max_work=" + maxWork + "; max_bytes=" + maxBytes);
    }

    private static GraphPlan<String> solve(Fixture f, boolean publicEntry, Map<String, Long> seeds, boolean force) {
        var budget = budget(40_000_000); budget.enableMetrics(); GraphPlan<String> plan;
        if (publicEntry) {
            try (var work = new CapturedCatalog<>(f.entries()).begin("product", 1, f.stock(), Set.of(), seeds, false, force, budget)) {
                while (!work.step()) {}
                plan = work.result().plan();
                check(plan.feasible() && !work.result().coverage().complete(), "Lost known order: " + plan.result() + "; " + budget.diagnostics());
            }
        } else {
            try (var work = new CapturedStatePlanning<>(f.entries(), "product", 1, f.stock(), Set.of(), seeds, false, force, budget)) {
                while (!work.step()) {} plan = work.result();
                check(plan != null, "Lost known order: " + work.outcome() + "; " + budget.diagnostics());
            }
        }
        check(budget.reservedBytes() == 0, "Structural order retained memory");
        check(budget.searchWork() < 200_000 && budget.peakBytes() < (32L << 20), "Small state structure inflated its costs");
        PlanningCostTraceTest.conserved(budget);
        if (budget.diagnostics().contains("order_interference")) feedback++;
        maxWork = Math.max(maxWork, budget.searchWork()); maxBytes = Math.max(maxBytes, budget.peakBytes()); cases++;
        verify(plan, f, seeds); return plan;
    }

    private static Fixture fixture(int levels, int n, long tools, boolean mixed, int order, boolean gated, boolean manufacture) {
        // Constructive witness independent of scheduling: all light uses, then
        // heavy levels..1, then finish. Each cohort still contains 'tools' entities.
        long left = n + levels + 1L;
        for (int i = 0; i < n; i++) { check(left >= 1, "Invalid light witness"); left--; }
        for (int level = levels; level > 0; level--) { check(left >= level + 1, "Invalid heavy witness"); left = level; }
        check(left == 1, "Invalid final tool state");
        var entries = new ArrayList<CapturedCatalog.Entry<String>>(); var finish = new ArrayList<CapturedRecipe.Input<String>>();
        for (int k = 1; k <= levels + 1; k++) {
            int level = k % (levels + 1); var rule = new Wear(level); String key = "tool:" + (n + levels + 1), id = rule.id();
            var inputs = new ArrayList<>(List.of(new CapturedRecipe.Input<>(tools, List.of(new CapturedRecipe.Candidate<>(
                    new CapturedRecipe.Amount<>(key, 1), rule.nominalAfterOne(key), false, false, rule)),
                    mixed ? CapturedRecipe.InputMode.MIXED : CapturedRecipe.InputMode.INDIVISIBLE), ordinary(id + "Raw", 1)));
            var outputs = new ArrayList<>(List.of(new CapturedRecipe.Amount<>(id + "Part", 1)));
            if (gated && level == 1) outputs.add(new CapturedRecipe.Amount<>("gate", n));
            if (gated && level == 0) inputs.add(ordinary("gate", 1));
            if ((order & 1) != 0) Collections.reverse(inputs);
            entries.add(new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, outputs, false, false)));
            finish.add(ordinary(id + "Part", level == 0 ? n : 1));
        }
        finish.add(1, finish.remove(finish.size() - 1)); if ((order & 2) != 0) Collections.reverse(finish);
        entries.add(new CapturedCatalog.Entry<>("finish", "finish", new CapturedRecipe<>(finish, List.of(new CapturedRecipe.Amount<>("product", 1)), false, false)));
        if (manufacture) entries.add(new CapturedCatalog.Entry<>("build", "build", new CapturedRecipe<>(List.of(ordinary("metal", 1)),
                List.of(new CapturedRecipe.Amount<>("tool:" + (n + levels + 1), 1)), false, false)));
        Collections.rotate(entries, order / 4); if ((order & 1) != 0) Collections.reverse(entries);
        var stock = new LinkedHashMap<String, Long>();
        if ((order & 1) == 0) stock.put("tool:" + (n + levels + 1), tools);
        stock.put("lightRaw", (long) n); for (int k = 1; k <= levels; k++) stock.put("heavy" + k + "Raw", 1L);
        if ((order & 1) != 0) stock.put("tool:" + (n + levels + 1), tools);
        return new Fixture(List.copyOf(entries), stock, levels, n, tools);
    }

    private static void verify(GraphPlan<String> plan, Fixture f, Map<String, Long> seeds) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        var inventory = new LinkedHashMap<String, BigInteger>();
        plan.initialExact().forEach((key, amount) -> {
            check(amount.signum() >= 0 && amount.compareTo(BigInteger.valueOf(f.stock().getOrDefault(key, 0L))) <= 0, "Invented initial entity/input"); inventory.put(key, amount);
        });
        var counts = new LinkedHashMap<String, BigInteger>();
        plan.patternTimesExact().forEach((id, count) -> counts.merge(plan.recipes().get(id).binding(), count, BigInteger::add));
        for (var source : f.entries()) if (!source.binding().equals("build"))
            check(BigInteger.valueOf(source.binding().equals("light") ? f.n() : 1).equals(counts.get(source.binding())), "Changed same-count source semantics");
        for (var recipe : plan.recipes().values()) {
            var source = f.entries().stream().filter(e -> e.binding().equals(recipe.binding())).findFirst().orElseThrow();
            var filled = new LinkedHashMap<Integer, BigInteger>(); var whole = new LinkedHashMap<Integer, String>();
            var outputs = new LinkedHashMap<String, BigInteger>();
            for (var slot : recipe.slots()) {
                var input = source.recipe().inputs().get(slot.inputSlot()); var candidate = input.candidates().get(0);
                filled.merge(slot.inputSlot(), BigInteger.valueOf(slot.amount()), BigInteger::add); var prior = whole.put(slot.inputSlot(), slot.key());
                check(source.recipe().mixed(input) || prior == null || prior.equals(slot.key()), "Split whole-key demand");
                if (candidate.family() instanceof Wear wear) {
                    check(slot.key().startsWith("tool:") && durability(slot.key()) >= wear.level() + 1, "Ineligible exact tool");
                    long left = wear.level() == 0 ? durability(slot.key()) - 1 : wear.level();
                    if (left > 0) add(outputs, "tool:" + left, slot.amount());
                } else check(slot.key().equals(candidate.stack().what()), "Changed ordinary source input");
            }
            for (int i = 0; i < source.recipe().inputs().size(); i++)
                check(BigInteger.valueOf(source.recipe().inputs().get(i).multiplier()).equals(filled.get(i)), "Lost simultaneous input/cohort");
            source.recipe().outputs().forEach(output -> add(outputs, output.what(), output.amount()));
            check(outputs.equals(exact(recipe.executionOutputs())), "Changed literal state/part return");
        }
        var cursor = new PlanCursor(plan.steps()); int fired = 0;
        while (cursor.current() != null) {
            var batch = cursor.current(); var recipe = plan.recipes().get(batch.recipe());
            for (long run = 0; run < batch.runs(); run++) {
                for (var slot : recipe.slots()) take(inventory, slot.key(), slot.amount());
                recipe.executionOutputs().forEach((key, count) -> add(inventory, key, count));
                check(++fired <= f.n() + f.levels() + 3, "Unexpected firing expansion");
            }
            cursor.dispatched(batch.runs());
        }
        check(inventory.getOrDefault("product", BigInteger.ZERO).signum() > 0, "No physical delivery");
        seeds.forEach((key, amount) -> check(inventory.getOrDefault(key, BigInteger.ZERO).compareTo(BigInteger.valueOf(amount)) >= 0, "Lost exact final seed"));
    }

    private static void boundaries() {
        var gated = fixture(3, 2, 1, false, 0, true, true);
        for (int mode = 0; mode < 3; mode++) {
            var stock = new LinkedHashMap<>(gated.stock());
            if (mode == 1) stock.put("tool:6", 2L);
            if (mode == 2) stock.put("metal", 1L);
            var f = new Fixture(gated.entries(), stock, gated.levels(), gated.n(), gated.tools());
            if (mode > 0) {
                var plan = solve(f, true, Map.of(), false);
                if (mode == 2) check(plan.recipes().values().stream().anyMatch(r -> r.binding().equals("build")), "Replacement route lost");
                runtime(plan); continue;
            }
            var budget = budget(40_000_000);
            try (var work = new CapturedCatalog<>(f.entries()).begin("product", 1, stock, false, false, budget)) {
                while (!work.step()) {}
                check(!work.result().plan().feasible() && work.result().plan().result() != GraphPlan.Result.INFEASIBLE, "Hint erased a gate, invented a tool or proved global UNSAT");
            }
            check(budget.reservedBytes() == 0 && budget.searchWork() < 100_000, "Impossible order renewed budget or leaked");
        }
        var f = fixture(3, 2, 1, false, 0, false, false);
        var stock = new LinkedHashMap<>(f.stock()); stock.put("lightRaw", 1L);
        var budget = budget(40_000_000);
        try (var work = new CapturedCatalog<>(f.entries()).begin("product", 1, stock, false, false, budget)) {
            while (!work.step()) {} check(!work.result().plan().feasible(), "Hint supplied missing raw material");
        }
        check(budget.reservedBytes() == 0, "Missing input leaked");
        for (long cap : new long[]{1024, 4000, 16_000, 40_000}) {
            budget = budget(cap);
            try (var work = new CapturedStatePlanning<>(f.entries(), "product", 1, f.stock(), Set.of(), Map.of(), false, false, budget)) {
                try { while (!work.step()) {} } catch (PlanningBudget.Exhausted expected) { /* Inconclusive. */ }
                if (work.result() != null) verify(work.result(), f, Map.of());
            }
            check(budget.reservedBytes() == 0 && budget.searchWork() <= cap + 1, "Structural retry renewed work or leaked");
        }
    }

    private static void runtime(GraphPlan<String> plan) {
        final class Host implements GraphJobRuntime.Adapter<String> {
            GraphJobRuntime<String> job = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            final Map<String, Long> physical = new LinkedHashMap<>(plan.initial()); final List<Map<String, Long>> pending = new ArrayList<>(); long delivered;
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
            for (var outputs : host.pending) outputs.forEach((key, amount) -> { host.physical.merge(key, amount, Long::sum); check(host.job.accept(key, amount, false) == amount, "Lost return on reload"); });
            host.pending.clear();
        }
        check(host.delivered == 1, "Delayed return stalled");
    }
    private static void handoff() throws Exception {
        var f = fixture(3, 2, 1, false, 0, false, false); var budget = budget(40_000_000);
        var left = Executors.newSingleThreadExecutor(); var right = Executors.newSingleThreadExecutor();
        try (var work = new CapturedStatePlanning<>(f.entries(), "product", 1, f.stock(), Set.of(), Map.of(), false, false, budget)) {
            boolean done = false; int visits = 0;
            while (!done) done = ((visits++ & 1) == 0 ? left : right).submit(() -> { for (int i = 0; i < 16; i++) if (work.step()) return true; return false; }).get();
            check(work.result() != null && visits > 1, "Lost structural continuation across workers"); verify(work.result(), f, Map.of());
        } finally { left.shutdownNow(); right.shutdownNow(); }
        check(budget.reservedBytes() == 0, "Worker handoff leaked");
    }
    private static void cancelled() {
        var f = fixture(3, 2, 1, false, 0, false, false); var cancel = new AtomicBoolean();
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, cancel::get, System::nanoTime); budget.enableMetrics();
        try (var work = new CapturedStatePlanning<>(f.entries(), "product", 1, f.stock(), Set.of(), Map.of(), false, false, budget)) {
            while (!work.step()) if (budget.diagnostics().contains("order_interference")) { cancel.set(true); break; }
            check(cancel.get(), "No pending structural continuation to cancel");
            try { work.step(); throw new AssertionError("Ignored cancellation"); } catch (java.util.concurrent.CancellationException expected) { checks++; }
        }
        check(budget.reservedBytes() == 0, "Cancelled structural order leaked");
    }
    private static CapturedRecipe.Input<String> ordinary(String key, long count) { return new CapturedRecipe.Input<>(count, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false))); }
    private static long durability(String key) { return Long.parseLong(key.substring(5)); }
    private static LinkedHashMap<String, BigInteger> exact(Map<String, Long> source) { var out = new LinkedHashMap<String, BigInteger>(); source.forEach((key, amount) -> add(out, key, amount)); return out; }
    private static void add(Map<String, BigInteger> values, String key, long amount) { values.merge(key, BigInteger.valueOf(amount), BigInteger::add); }
    private static void take(Map<String, BigInteger> values, String key, long amount) { var left = values.getOrDefault(key, BigInteger.ZERO).subtract(BigInteger.valueOf(amount)); check(left.signum() >= 0, "Unfunded literal input " + key); values.put(key, left); }
    private static PlanningBudget budget(long cap) { return new PlanningBudget(0, cap, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
