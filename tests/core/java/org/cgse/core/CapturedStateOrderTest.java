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
import java.util.concurrent.atomic.AtomicBoolean;

/** State eligibility is temporal: extra marginal capacity is not always the repair. */
public final class CapturedStateOrderTest {
    private static int cases, checks;

    private record Stage(int level) implements StateFamily<String> {
        public String id() { return "stage-" + level; }
        public boolean accepts(String key) { return key.startsWith("tool:") && mode(key) <= level; }
        public long capacity(String key) { return accepts(key) ? Long.MAX_VALUE : 0; }
        public String nominalAfterOne(String key) { return "tool:" + level; }
    }

    public static void main(String[] args) {
        for (int levels : new int[]{2, 3, 5}) for (int permutation = 0; permutation < 6; permutation++) {
            for (long n : new long[]{1, 7, 1_000_000_000L, Long.MAX_VALUE}) {
                var entries = entries(levels, 1, permutation, false, false);
                var stock = stock(levels, n, 1, permutation % 2 != 0);
                var plan = solve(entries, stock, n, Map.of(), false, false, 40_000_000);
                if (n == 7 && permutation == 0) runtime(plan, n);
            }
        }
        for (int tools : new int[]{2, 3}) for (int permutation = 0; permutation < 6; permutation++) {
            var entries = entries(2, tools, permutation, false, false);
            var plan = solve(entries, stock(2, 7, tools, permutation % 2 != 0), 7,
                    Map.of("tool:1", (long) tools), false, false, 400_000);
            if (permutation == 0) runtime(plan, 7);
        }
        boundaries();
        cancelled();
        System.out.println("CapturedStateOrderTest: cases=" + cases + "; assertions=" + checks);
    }

    private static GraphPlan<String> solve(List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock,
            long n, Map<String, Long> seeds, boolean preserve, boolean force, long workLimit) {
        var budget = budget(workLimit); budget.enableMetrics(); GraphPlan<String> plan;
        try (var work = new CapturedStatePlanning<>(entries, "product", n, stock, Set.of(), seeds, preserve, force, budget)) {
            while (!work.step()) {}
            check(work.outcome().status() == CapturedStatePlanning.Status.VERIFIED,
                    "Lost a known physical ordering: " + work.outcome() + "; " + budget.diagnostics());
            plan = work.result();
        }
        check(budget.reservedBytes() == 0, "Order continuation / physical restoration leaked");
        check(budget.searchWork() < 150_000 && budget.peakBytes() < (16L << 20), "Small same-count repair ballooned");
        PlanningCostTraceTest.conserved(budget);
        verify(plan, entries, stock, n);
        cases++; return plan;
    }

    private static void boundaries() {
        var entries = entries(2, 1, 0, false, false);
        var stock = stock(2, 7, 1, false); stock.put("product", 7L);
        solve(entries, stock, 7, Map.of("tool:1", 1L), false, true, 400_000);
        solve(entries, stock, 7, Map.of("tool:1", 1L), true, true, 400_000);
        stock.remove("product");
        // No ordering can restore mode 0 after the required mode-1 operation.
        var budget = budget(40_000_000);
        try (var work = new CapturedStatePlanning<>(entries, "product", 7, stock, Set.of(), Map.of("tool:0", 1L), false, false, budget)) {
            while (!work.step()) {}
            check(work.result() == null, "Order repair silently dropped an exact physical seed");
        }
        check(budget.reservedBytes() == 0, "Rejected seed retained a scheduler");

        // The ordinary gate now forces the destructive rule first. A reordered
        // proposal must still own ordinary inputs; state-only sorting is invalid.
        var gated = entries(2, 1, 0, true, false);
        var initial = stock(2, 1, 1, false);
        budget = budget(40_000_000); budget.enableMetrics();
        try (var work = new CapturedStatePlanning<>(gated, "product", 1, initial, Set.of(), Map.of(), false, false, budget)) {
            while (!work.step()) {}
            check(work.result() == null, "Reordering invented an unavailable ordinary gate / second tool");
            check(budget.diagnostics().contains("retry_same_counts"), "Missing order-repair boundary exercise");
        }
        check(budget.reservedBytes() == 0 && budget.searchWork() < 100_000, "Unrepairable order renewed its scout");
        // Additional real supply changes this conclusion. Exhausted order
        // alternatives must retain the existing entity refinement path.
        initial.put("tool:0", 2L);
        solve(gated, initial, 1, Map.of(), false, false, 40_000_000);
        initial.put("tool:0", 1L); initial.put("metal", 1L);
        var build = entries(2, 1, 0, true, true);
        var plan = solve(build, initial, 1, Map.of(), false, false, 40_000_000);
        check(plan.recipes().values().stream().anyMatch(r -> r.binding().equals("build")), "Replacement source was dropped");
        runtime(plan, 1);

        // Public request coverage must remain incomplete on the unrepairable
        // family domain. A private scheduler's DEAD is never global UNSAT.
        budget = budget(40_000_000);
        try (var work = new CapturedCatalog<>(gated).begin("product", 1, stock(2, 1, 1, false), false, false, budget)) {
            while (!work.step()) {}
            check(!work.result().plan().feasible() && !work.result().coverage().complete() &&
                    work.result().plan().result() != GraphPlan.Result.INFEASIBLE, "Order failure escaped its positive-only scope");
        }
        check(budget.reservedBytes() == 0, "Unrepairable public request leaked");
        budget = budget(400_000);
        var requestStock = stock(2, 7, 1, false);
        try (var work = new CapturedCatalog<>(entries).begin("product", 7, requestStock, false, true, budget)) {
            while (!work.step()) {}
            check(work.result().plan().feasible(), "Repair not connected to public request planning");
            verify(work.result().plan(), entries, requestStock, 7);
        }
        check(budget.reservedBytes() == 0, "Public request leaked");
        for (long cap : new long[]{2_000, 8_000, 24_000}) {
            budget = budget(cap);
            try (var work = new CapturedStatePlanning<>(gated, "product", 1, stock(2, 1, 1, false), Set.of(), Map.of(), false, false, budget)) {
                try { while (!work.step()) {} } catch (PlanningBudget.Exhausted limit) { /* No negative theorem. */ }
                check(work.result() == null, "A limited repair invented a witness");
            }
            check(budget.reservedBytes() == 0 && budget.searchWork() <= cap + 1, "Retry renewed the request cap");
        }
    }

    private static void cancelled() {
        var cancel = new AtomicBoolean();
        var budget = new PlanningBudget(0, 400_000, 64L << 20, cancel::get, System::nanoTime); budget.enableMetrics();
        boolean seen = false;
        try (var work = new CapturedStatePlanning<>(entries(2, 1, 0, false, false), "product", 7,
                stock(2, 7, 1, false), Set.of(), Map.of(), false, false, budget)) {
            while (!work.step()) {
                if (budget.diagnostics().contains("restore_alternative_order")) { cancel.set(true); break; }
            }
            try { work.step(); } catch (java.util.concurrent.CancellationException limit) { seen = true; }
        }
        check(cancel.get() && seen && budget.reservedBytes() == 0, "Cancelled retained order did not release both workspaces");
    }

    private static List<CapturedCatalog.Entry<String>> entries(int levels, int tools, int permutation, boolean gate, boolean build) {
        var entries = new ArrayList<CapturedCatalog.Entry<String>>();
        var finish = new ArrayList<CapturedRecipe.Input<String>>();
        for (int level = levels - 1; level >= 0; level--) {
            var inputs = new ArrayList<>(List.of(new CapturedRecipe.Input<>(tools, List.of(new CapturedRecipe.Candidate<>(
                    new CapturedRecipe.Amount<>("tool:0", 1), "tool:" + level, false, false, new Stage(level))), CapturedRecipe.InputMode.MIXED),
                    ordinary("raw" + level)));
            if (gate && level == 0) inputs.add(ordinary("gate"));
            if (permutation % 2 != 0) Collections.reverse(inputs);
            var outputs = new ArrayList<>(List.of(new CapturedRecipe.Amount<>("part" + level, 1)));
            if (gate && level == 1) outputs.add(new CapturedRecipe.Amount<>("gate", 1));
            entries.add(new CapturedCatalog.Entry<>("stage" + level, "stage" + level, new CapturedRecipe<>(inputs, outputs, false, false)));
            finish.add(ordinary("part" + level));
        }
        entries.add(entry("finish", finish, "product"));
        if (build) entries.add(entry("build", List.of(ordinary("metal")), "tool:0"));
        Collections.rotate(entries, permutation / 2); if (permutation % 2 != 0) Collections.reverse(entries);
        return entries;
    }

    private static LinkedHashMap<String, Long> stock(int levels, long n, long tools, boolean reverse) {
        var stock = new LinkedHashMap<String, Long>(); stock.put("tool:0", tools);
        for (int level = 0; level < levels; level++) stock.put("raw" + level, n);
        if (!reverse) return stock;
        var ordered = new LinkedHashMap<String, Long>(); var keys = new ArrayList<>(stock.keySet()); Collections.reverse(keys);
        for (String key : keys) ordered.put(key, stock.get(key)); return ordered;
    }

    private static void verify(GraphPlan<String> plan, List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock, long n) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        var inventory = new LinkedHashMap<String, BigInteger>();
        plan.initialExact().forEach((key, count) -> {
            check(count.compareTo(BigInteger.valueOf(stock.getOrDefault(key, 0L))) <= 0, "Invented initial entity / raw material");
            inventory.put(key, count);
        });
        var bindingCounts = new LinkedHashMap<String, BigInteger>();
        plan.patternTimesExact().forEach((id, count) -> bindingCounts.merge(plan.recipes().get(id).binding(), count, BigInteger::add));
        for (var source : entries) if (!source.binding().equals("build"))
            check(BigInteger.valueOf(n).equals(bindingCounts.get(source.binding())), "Changed the known per-source counts");
        for (var recipe : plan.recipes().values()) {
            var source = entries.stream().filter(e -> e.binding().equals(recipe.binding())).findFirst().orElseThrow();
            var outputs = new LinkedHashMap<String, Long>(); long[] filled = new long[source.recipe().inputs().size()];
            for (var slot : recipe.slots()) {
                var input = source.recipe().inputs().get(slot.inputSlot()); var candidate = input.candidates().get(0);
                filled[slot.inputSlot()] += slot.amount();
                if (candidate.family() instanceof Stage stage) {
                    check(slot.key().startsWith("tool:") && mode(slot.key()) <= stage.level(), "Ineligible exact state");
                    outputs.merge("tool:" + stage.level(), slot.amount(), Long::sum);
                } else check(candidate.stack().what().equals(slot.key()), "Changed ordinary source input");
            }
            for (int i = 0; i < filled.length; i++) check(filled[i] == source.recipe().inputs().get(i).multiplier(), "Lost simultaneous entity demand");
            source.recipe().outputs().forEach(out -> outputs.merge(out.what(), out.amount(), Long::sum));
            check(outputs.equals(recipe.executionOutputs()), "Changed the immutable source transition");
        }
        if (n > 32) return; // Huge words are checked symbolically above; small words get a separate primitive replay.
        var cursor = new PlanCursor(plan.steps()); int fired = 0;
        while (cursor.current() != null) {
            var batch = cursor.current(); var recipe = plan.recipes().get(batch.recipe());
            for (long i = 0; i < batch.runs(); i++) {
                for (var slot : recipe.slots()) {
                    var have = inventory.getOrDefault(slot.key(), BigInteger.ZERO); var need = BigInteger.valueOf(slot.amount());
                    check(have.compareTo(need) >= 0, "Primitive replay spent an unavailable entity / gate");
                    inventory.put(slot.key(), have.subtract(need));
                }
                recipe.executionOutputs().forEach((key, count) -> inventory.merge(key, BigInteger.valueOf(count), BigInteger::add));
                check(++fired < 1000, "Unexpected physical expansion");
            }
            cursor.dispatched(batch.runs());
        }
        check(inventory.getOrDefault("product", BigInteger.ZERO).compareTo(BigInteger.valueOf(n)) >= 0, "Physical request unfulfilled");
    }

    private static void runtime(GraphPlan<String> plan, long n) {
        final class Host implements GraphJobRuntime.Adapter<String> {
            GraphJobRuntime<String> job = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            final Map<String, Long> physical = new LinkedHashMap<>(plan.initial());
            final List<Map<String, Long>> pending = new ArrayList<>(); long delivered;
            public long capacity(GraphRecipe<String> recipe, long requested) { return requested; }
            public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
                inputs.forEach((key, count) -> {
                    check(physical.getOrDefault(key, 0L) >= count, "Used a tool still in flight");
                    physical.merge(key, -count, Long::sum);
                });
                var outputs = new LinkedHashMap<String, Long>();
                recipe.executionOutputs().forEach((key, count) -> outputs.put(key, Math.multiplyExact(count, runs)));
                pending.add(outputs); return GraphJobRuntime.Outcome.ACCEPTED;
            }
            public long deliver(String key, long count) { delivered += count; return count; }
            public long refund(String key, long count) { return count; }
        }
        var host = new Host();
        for (int tick = 0; tick < 1000 && host.delivered < n; tick++) {
            host.job.tick(host, tick * 20L, 8, () -> 0L); host.job = new GraphJobRuntime<>(host.job.snapshot());
            if (tick % 3 != 2) continue;
            Collections.reverse(host.pending);
            for (var output : host.pending) output.forEach((key, count) -> {
                host.physical.merge(key, count, Long::sum);
                check(host.job.accept(key, count, false) == count, "Lost actual return after save/load");
            });
            host.pending.clear();
        }
        check(host.delivered == n, "Reordered program stalled at runtime");
    }

    private static int mode(String key) { return Integer.parseInt(key.substring(5)); }
    private static CapturedRecipe.Input<String> ordinary(String key) {
        return new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false)), CapturedRecipe.InputMode.INDIVISIBLE);
    }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, String output) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>(output, 1)), false, false));
    }
    private static PlanningBudget budget(long work) { return new PlanningBudget(0, work, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
