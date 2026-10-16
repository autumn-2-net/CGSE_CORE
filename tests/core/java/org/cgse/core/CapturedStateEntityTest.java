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

/** Actual captured proposals must repair entity selection, not invent capacity tools. */
public final class CapturedStateEntityTest {
    private static int checks;
    private static final Family FAMILY = new Family();

    public static void main(String[] args) {
        for (boolean reverse : List.of(false, true)) {
            scenario(List.of(tool(1, true), tool(2, false)), 1, Map.of("tool:100", 2L, "tool:10", 1L), false, reverse, false);
            scenario(List.of(tool(3, false), tool(2, false), tool(2, false)), 1,
                    Map.of("tool:100", 4L, "tool:10", 3L), false, reverse, false);
            scenario(List.of(tool(3, false), tool(3, false), tool(2, false), tool(2, false)), 1,
                    Map.of("tool:100", 6L, "tool:10", 4L), false, reverse, false);
            for (long amount : new long[]{2, 3, 8}) {
                scenario(List.of(tool(2, true)), amount, Map.of("tool:1", amount, "tool:100", 1L), false, reverse, true);
                scenario(List.of(tool(1, false), tool(1, false)), amount,
                        Map.of("tool:1", amount, "tool:100", 1L), false, reverse, true);
                scenario(List.of(tool(2, true)), amount,
                        Map.of("tool:1", 1L, "tool:100", 1L, "metal", amount - 1), true, reverse, true);
            }
        }
        boundaries();
        System.out.println("CapturedStateEntityTest: " + checks + " assertions passed");
    }

    private static void scenario(List<CapturedRecipe.Input<String>> toolSlots, long amount, Map<String, Long> initial,
                                 boolean build, boolean reverse, boolean refined) {
        var stock = ordered(initial, reverse); stock.put("raw", amount);
        var slots = new ArrayList<>(toolSlots); slots.add(ordinary("raw"));
        if (reverse) Collections.reverse(slots);
        var entries = new ArrayList<CapturedCatalog.Entry<String>>(); entries.add(entry("use", slots, "product"));
        if (build) entries.add(entry("build", List.of(ordinary("metal")), "tool:1"));
        if (reverse) Collections.reverse(entries);
        var budget = budget(40_000_000); budget.enableMetrics();
        GraphPlan<String> plan;
        try (var work = new CapturedStatePlanning<>(entries, "product", amount, stock, Set.of(), Map.of(), false, false, budget)) {
            while (!work.step()) {}
            check(work.outcome().status() == CapturedStatePlanning.Status.VERIFIED, "Entity restoration declined: " + work.outcome() +
                    "; amount=" + amount + "; build=" + build + "; reverse=" + reverse + "; " + budget.diagnostics());
            plan = work.result();
        }
        check(budget.reservedBytes() == 0, "Repair retained an old workspace");
        check(budget.searchWork() < 100_000 && budget.peakBytes() < (16L << 20), "Small entity repair ballooned");
        if (refined) check(budget.diagnostics().contains("refinements=1"), "Missing one scoped horizon refinement");
        PlanningCostTraceTest.conserved(budget);
        verifyPhysical(plan, entries, stock, amount);
        if (build && amount == 8) runtime(plan, amount);
    }

    private static void boundaries() {
        var use = entry("use", List.of(tool(2, true), ordinary("raw")), "product");
        var stock = Map.of("tool:100", 1L, "tool:1", 1L, "raw", 2L);
        var budget = budget(40_000_000);
        try (var work = new CapturedStatePlanning<>(List.of(use), "product", 2, stock, Set.of(), Map.of(), false, false, budget)) {
            while (!work.step()) {}
            check(work.result() == null && work.outcome().status() == CapturedStatePlanning.Status.DECLINED,
                    "One long-lived entity occupied two simultaneous slots");
        }
        check(budget.reservedBytes() == 0, "Unrepairable candidate leaked");
        // Request-level coverage still treats arbitrary family domains as open.
        budget = budget(40_000_000);
        try (var work = new CapturedCatalog<>(List.of(use)).begin("product", 2, stock, false, false, budget)) {
            while (!work.step()) {}
            check(!work.result().coverage().complete() && work.result().plan().result() != GraphPlan.Result.INFEASIBLE,
                    "A restricted entity failure became global infeasibility");
        }
        check(budget.reservedBytes() == 0, "Catalog decline leaked");
        // Neither retries nor a resumable allocator renew the arm's allowance.
        budget = budget(36_000);
        try (var work = new CapturedStatePlanning<>(List.of(use), "product", 2,
                Map.of("tool:100", 1L, "tool:1", 2L, "raw", 2L), Set.of(), Map.of(), false, false, budget)) {
            while (!work.step()) {}
            // The auxiliary solver may itself decline at this cap, or find a
            // cheaper witness. Neither outcome can renew the parent allowance.
            check(work.outcome().status() != CapturedStatePlanning.Status.RUNNING, "Lost limited candidate outcome");
            if (work.result() != null) verifyPhysical(work.result(), List.of(use),
                    Map.of("tool:100", 1L, "tool:1", 2L, "raw", 2L), 2);
            System.out.println("entity low-budget outcome=" + work.outcome() + "; search=" + budget.searchWork());
        }
        check(budget.reservedBytes() == 0 && budget.searchWork() < 20_000, "Refinement spent the next arm's budget");
    }

    private static void verifyPhysical(GraphPlan<String> plan, List<CapturedCatalog.Entry<String>> sources,
                                       Map<String, Long> stock, long amount) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        var inventory = new LinkedHashMap<String, Long>();
        plan.initialExact().forEach((key, n) -> {
            check(n.compareTo(BigInteger.valueOf(stock.getOrDefault(key, 0L))) <= 0, "Invented physical initial stock");
            inventory.put(key, n.longValueExact());
        });
        var cursor = new PlanCursor(plan.steps()); long fired = 0;
        while (cursor.current() != null) {
            var batch = cursor.current(); var recipe = plan.recipes().get(batch.recipe());
            var source = sources.stream().filter(s -> s.binding().equals(recipe.binding())).findFirst().orElseThrow();
            for (int iteration = 0; iteration < batch.runs(); iteration++) {
                var owed = new LinkedHashMap<String, Long>();
                long[] filled = new long[source.recipe().inputs().size()];
                var keys = new LinkedHashMap<Integer, String>();
                for (var slot : recipe.slots()) {
                    check(inventory.getOrDefault(slot.key(), 0L) >= slot.amount(), "Unowned execution input");
                    inventory.merge(slot.key(), -slot.amount(), Long::sum);
                    filled[slot.inputSlot()] += slot.amount();
                    var input = source.recipe().inputs().get(slot.inputSlot());
                    if (input.candidates().get(0).family() != null) {
                        check(FAMILY.accepts(slot.key()), "Wrong physical tool family");
                        String previous = keys.put(slot.inputSlot(), slot.key());
                        check(source.recipe().mixed(input) || previous == null || previous.equals(slot.key()), "Indivisible slot changed keys");
                        String after = FAMILY.nominalAfterOne(slot.key());
                        if (after != null) owed.merge(after, slot.amount(), Long::sum);
                    } else check(input.candidates().get(0).stack().what().equals(slot.key()), "Wrong ordinary input");
                }
                for (int i = 0; i < filled.length; i++) check(filled[i] == source.recipe().inputs().get(i).multiplier(), "Wrong simultaneous entity demand");
                source.recipe().outputs().forEach(output -> owed.merge(output.what(), output.amount(), Long::sum));
                check(owed.equals(recipe.executionOutputs()), "Restoration changed the captured transition");
                owed.forEach((key, n) -> inventory.merge(key, n, Long::sum));
                check(++fired < 1000, "Unexpected program expansion");
            }
            cursor.dispatched(batch.runs());
        }
        check(inventory.getOrDefault("product", 0L) >= amount, "Physical witness lost the target");
    }

    private static void runtime(GraphPlan<String> plan, long amount) {
        final class Host implements GraphJobRuntime.Adapter<String> {
            GraphJobRuntime<String> runtime = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            final List<Map<String, Long>> pending = new ArrayList<>(); long delivered;
            public long capacity(GraphRecipe<String> recipe, long requested) { return requested; }
            public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
                var outputs = new LinkedHashMap<String, Long>();
                recipe.executionOutputs().forEach((key, n) -> outputs.put(key, Math.multiplyExact(n, runs)));
                pending.add(outputs); return GraphJobRuntime.Outcome.ACCEPTED;
            }
            public long deliver(String key, long count) { delivered += count; return count; }
            public long refund(String key, long count) { return count; }
        }
        var host = new Host();
        for (int tick = 0; tick < 100 && host.delivered < amount; tick++) {
            host.runtime.tick(host, tick * 20L, 64, () -> 0L);
            host.runtime = new GraphJobRuntime<>(host.runtime.snapshot());
            Collections.reverse(host.pending);
            for (var outputs : host.pending) outputs.forEach((key, n) -> check(host.runtime.accept(key, n, false) == n, "Lost real tool return"));
            host.pending.clear();
        }
        check(host.delivered == amount, "Repaired tool plan stalled after save/load");
    }

    private static LinkedHashMap<String, Long> ordered(Map<String, Long> stock, boolean reverse) {
        var result = new LinkedHashMap<String, Long>(); var keys = new ArrayList<>(stock.keySet());
        Collections.sort(keys); if (reverse) Collections.reverse(keys);
        for (var key : keys) result.put(key, stock.get(key)); return result;
    }
    private static final class Family implements StateFamily<String> {
        public String id() { return "wear"; }
        public boolean accepts(String key) { return key.startsWith("tool:"); }
        public long capacity(String key) { return accepts(key) ? Long.parseLong(key.substring(5)) : 0; }
        public String nominalAfterOne(String key) { long n = capacity(key); return n <= 1 ? null : "tool:" + (n - 1); }
    }
    private static CapturedRecipe.Input<String> tool(long amount, boolean mixed) {
        return new CapturedRecipe.Input<>(amount, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("tool:100", 1),
                "tool:99", false, false, FAMILY)), mixed ? CapturedRecipe.InputMode.MIXED : CapturedRecipe.InputMode.INDIVISIBLE);
    }
    private static CapturedRecipe.Input<String> ordinary(String key) {
        return new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false)), CapturedRecipe.InputMode.INDIVISIBLE);
    }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, String output) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>(output, 1)), false, false));
    }
    private static PlanningBudget budget(long work) { return new PlanningBudget(0, work, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
