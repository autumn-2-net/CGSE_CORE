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

/** Shared physical tools across wear rules, with an independent primitive replay. */
public final class CapturedStateSharingTest {
    private static int checks, cases;

    private record Wear(int cost, String prefix) implements StateFamily<String> {
        public String id() { return prefix + cost; }
        public boolean accepts(String key) { return key.startsWith(prefix + ":"); }
        public long capacity(String key) { return durability(key) / cost; }
        public String nominalAfterOne(String key) {
            long left = durability(key) - cost; return left <= 0 ? null : prefix + ":" + left;
        }
    }

    public static void main(String[] args) {
        for (boolean reverse : List.of(false, true)) {
            for (boolean expensiveFirst : List.of(false, true)) {
                sequential(1, 3, 1, false, reverse, expensiveFirst);
                sequential(100, 8192, 1, false, reverse, expensiveFirst);
                sequential(40, 100, 1, true, reverse, expensiveFirst);
            }
            simultaneous(2, Map.of("t:3", 2L), reverse);
            simultaneous(3, Map.of("t:3", 2L, "t:6", 1L), reverse);
            disjoint(reverse);
            negative(reverse, false);
            negative(reverse, true);
        }
        requestAndSeeds();
        limited();
        System.out.println("CapturedStateSharingTest: cases=" + cases + "; assertions=" + checks);
    }

    private static void sequential(long n, long durability, long tools, boolean build, boolean reverse, boolean expensiveFirst) {
        var entries = new ArrayList<CapturedCatalog.Entry<String>>();
        entries.add(entry("first", List.of(tool(expensiveFirst ? 2 : 1, "t"), ordinary("raw")), "mid"));
        entries.add(entry("second", List.of(tool(expensiveFirst ? 1 : 2, "t"), ordinary("mid")), "product"));
        if (build) entries.add(entry("build", List.of(ordinary("metal")), "t:100"));
        var stock = new LinkedHashMap<String, Long>(); stock.put("t:" + durability, tools); stock.put("raw", n);
        if (build) stock.put("metal", 1L);
        if (reverse) Collections.reverse(entries);
        var plan = solve(entries, stock, n, Map.of(), build);
        if (build) runtime(plan, n);
    }

    private static void simultaneous(long n, Map<String, Long> tools, boolean reverse) {
        var inputs = new ArrayList<>(List.of(tool(1, "t"), tool(2, "t"), ordinary("raw")));
        if (reverse) Collections.reverse(inputs);
        var stock = new LinkedHashMap<String, Long>();
        var keys = new ArrayList<>(tools.keySet()); Collections.sort(keys); if (reverse) Collections.reverse(keys);
        for (var key : keys) stock.put(key, tools.get(key)); stock.put("raw", n);
        var entries = List.of(entry("use", inputs, "product"));
        var plan = solve(entries, stock, n, Map.of(), false); runtime(plan, n);
    }

    private static void disjoint(boolean reverse) {
        var inputs = new ArrayList<>(List.of(tool(1, "a"), tool(2, "b"), ordinary("raw")));
        if (reverse) Collections.reverse(inputs);
        solve(List.of(entry("use", inputs, "product")), Map.of("a:3", 1L, "b:6", 1L, "raw", 3L), 3, Map.of(), false);
    }

    private static GraphPlan<String> solve(List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock,
            long n, Map<String, Long> seeds, boolean repair) {
        var budget = budget(40_000_000); budget.enableMetrics(); GraphPlan<String> plan;
        try (var work = new CapturedStatePlanning<>(entries, "product", n, stock, Set.of(), seeds, false, false, budget)) {
            while (!work.step()) {}
            check(work.outcome().status() == CapturedStatePlanning.Status.VERIFIED, "No shared physical witness: " + work.outcome() + "; " + budget.diagnostics());
            plan = work.result();
        }
        check(budget.reservedBytes() == 0, "Restoration / retry leaked");
        check(budget.searchWork() < 100_000 && budget.peakBytes() < (16L << 20), "Small shared-tool request ballooned");
        if (repair) check(budget.diagnostics().contains("refine_shared_entities"), "Combined wear did not request a replacement entity");
        PlanningCostTraceTest.conserved(budget);
        replay(plan, entries, stock, n); cases++; return plan;
    }

    private static void negative(boolean reverse, boolean simultaneous) {
        var entries = new ArrayList<CapturedCatalog.Entry<String>>();
        if (simultaneous) entries.add(entry("use", List.of(tool(1, "t"), tool(2, "t"), ordinary("raw")), "product"));
        else {
            entries.add(entry("first", List.of(tool(1, "t"), ordinary("raw")), "mid"));
            entries.add(entry("second", List.of(tool(2, "t"), ordinary("mid")), "product"));
        }
        if (reverse) Collections.reverse(entries);
        long n = simultaneous ? 1 : 40;
        var stock = Map.of("t:100", 1L, "raw", n);
        var budget = budget(40_000_000);
        try (var work = new CapturedStatePlanning<>(entries, "product", n, stock, Set.of(), Map.of(), false, false, budget)) {
            while (!work.step()) {}
            check(work.result() == null && work.outcome().status() == CapturedStatePlanning.Status.DECLINED, "Invented tool / shared wear");
        }
        check(budget.reservedBytes() == 0, "Rejected proposal leaked");
        budget = budget(40_000_000);
        try (var work = new CapturedCatalog<>(entries).begin("product", n, stock, false, false, budget)) {
            while (!work.step()) {}
            var result = work.result();
            check(!result.plan().feasible(), "Unexecutable family witness escaped");
            check(!result.coverage().complete() && result.plan().result() != GraphPlan.Result.INFEASIBLE,
                    "Positive-only allocation failure became global UNSAT");
        }
        check(budget.reservedBytes() == 0, "Request-level failure leaked"); cases++;
    }

    private static void requestAndSeeds() {
        var entries = List.of(entry("first", List.of(tool(2, "t"), ordinary("raw")), "mid"),
                entry("second", List.of(tool(1, "t"), ordinary("mid")), "product"));
        var stock = Map.of("t:4", 1L, "raw", 1L);
        solve(entries, stock, 1, Map.of("t:1", 1L), false);
        var budget = budget(40_000_000);
        try (var work = new CapturedCatalog<>(entries).begin("product", 1, stock, false, true, budget)) {
            while (!work.step()) {}
            check(work.result().plan().feasible(), "Shared arm was not connected to request planning");
            replay(work.result().plan(), entries, stock, 1);
        }
        check(budget.reservedBytes() == 0, "Request-level witness leaked");
        budget = budget(40_000_000);
        try (var work = new CapturedStatePlanning<>(entries, "product", 1, stock, Set.of(), Map.of("t:4", 1L), false, false, budget)) {
            while (!work.step()) {}
            check(work.result() == null, "Capacity reuse silently dropped an exact physical seed");
        }
        check(budget.reservedBytes() == 0, "Unfunded seed leaked");
    }

    private static void limited() {
        var entries = List.of(entry("use", List.of(tool(1, "t"), tool(2, "t"), ordinary("raw")), "product"));
        var budget = budget(4000);
        try (var work = new CapturedStatePlanning<>(entries, "product", 100, Map.of("t:3", 100L, "raw", 100L),
                Set.of(), Map.of(), false, false, budget)) {
            try { while (!work.step()) {} } catch (PlanningBudget.Exhausted exhausted) { /* Still no mathematical proof. */ }
        }
        check(budget.reservedBytes() == 0 && budget.searchWork() <= 4001, "Continuation renewed its search cap");
    }

    private static void replay(GraphPlan<String> plan, List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock, long n) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        var inventory = new LinkedHashMap<String, Long>();
        plan.initialExact().forEach((key, count) -> {
            check(count.compareTo(BigInteger.valueOf(stock.getOrDefault(key, 0L))) <= 0, "Invented physical initial inventory");
            inventory.put(key, count.longValueExact());
        });
        var cursor = new PlanCursor(plan.steps()); int fired = 0;
        while (cursor.current() != null) {
            var batch = cursor.current(); var recipe = plan.recipes().get(batch.recipe());
            var source = entries.stream().filter(e -> e.binding().equals(recipe.binding())).findFirst().orElseThrow();
            for (int iteration = 0; iteration < batch.runs(); iteration++) {
                var outputs = new LinkedHashMap<String, Long>(); long[] filled = new long[source.recipe().inputs().size()];
                for (var slot : recipe.slots()) {
                    check(inventory.getOrDefault(slot.key(), 0L) >= slot.amount(), "Unowned / double-assigned entity");
                    inventory.merge(slot.key(), -slot.amount(), Long::sum); filled[slot.inputSlot()] += slot.amount();
                    var input = source.recipe().inputs().get(slot.inputSlot()); var candidate = input.candidates().get(0);
                    if (candidate.family() instanceof Wear wear) {
                        check(slot.key().startsWith(wear.prefix() + ":") && durability(slot.key()) >= wear.cost(), "Ineligible tool");
                        long left = durability(slot.key()) - wear.cost();
                        if (left > 0) outputs.merge(wear.prefix() + ":" + left, slot.amount(), Long::sum);
                    } else check(candidate.stack().what().equals(slot.key()), "Wrong ordinary input");
                }
                for (int i = 0; i < filled.length; i++) check(filled[i] == source.recipe().inputs().get(i).multiplier(), "Missing input slot");
                source.recipe().outputs().forEach(out -> outputs.merge(out.what(), out.amount(), Long::sum));
                check(outputs.equals(recipe.executionOutputs()), "Changed source rule or physical return");
                outputs.forEach((key, count) -> inventory.merge(key, count, Long::sum));
                check(++fired < 1000, "Unexpected state expansion");
            }
            cursor.dispatched(batch.runs());
        }
        check(inventory.getOrDefault("product", 0L) >= n, "Did not fulfill the physical request");
    }

    private static void runtime(GraphPlan<String> plan, long n) {
        final class Host implements GraphJobRuntime.Adapter<String> {
            GraphJobRuntime<String> job = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            final Map<String, Long> physical = new LinkedHashMap<>(plan.initial());
            final List<Map<String, Long>> pending = new ArrayList<>(); long delivered;
            public long capacity(GraphRecipe<String> recipe, long requested) { return requested; }
            public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
                inputs.forEach((key, count) -> {
                    check(physical.getOrDefault(key, 0L) >= count, "Dispatched a tool still in flight");
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
            host.job.tick(host, tick * 20L, 8, () -> 0L);
            host.job = new GraphJobRuntime<>(host.job.snapshot());
            if (tick % 3 != 2) continue; // Keep physical tools in flight over several ticks.
            Collections.reverse(host.pending);
            for (var outputs : host.pending) outputs.forEach((key, count) -> {
                host.physical.merge(key, count, Long::sum);
                check(host.job.accept(key, count, false) == count, "Lost actual return after save/load");
            });
            host.pending.clear();
        }
        check(host.delivered == n, "Cross-rule execution stalled");
    }

    private static long durability(String key) { return Long.parseLong(key.substring(key.indexOf(':') + 1)); }
    private static CapturedRecipe.Input<String> tool(int cost, String prefix) {
        return new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(prefix + ":100", 1),
                prefix + ":" + (100 - cost), false, false, new Wear(cost, prefix))), CapturedRecipe.InputMode.INDIVISIBLE);
    }
    private static CapturedRecipe.Input<String> ordinary(String key) {
        return new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false)), CapturedRecipe.InputMode.INDIVISIBLE);
    }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, String output) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>(output, 1)), false, false));
    }
    private static PlanningBudget budget(long work) { return new PlanningBudget(0, work, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
