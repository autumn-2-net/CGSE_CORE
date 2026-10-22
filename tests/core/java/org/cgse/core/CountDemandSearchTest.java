// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Wide feedback graphs, wrong producer choices and inventory mutations. */
public final class CountDemandSearchTest {
    private static int checks;

    public static void main(String[] args) {
        for (int size : new int[] {3, 32, 256}) for (boolean reverse : List.of(false, true)) {
            var recipes = ring(size, 1);
            recipes.add(recipe("unfunded", Map.of("absent", 1L), Map.of("k0", 4L)));
            if (reverse) Collections.reverse(recipes);
            var compiler = new GraphCompiler<>(recipes);
            for (long fuel : new long[] {8, 0, 8}) {
                var stock = Map.of("k0", 1L, "fuel", fuel);
                var plan = solve(compiler, stock, 8, Map.of("k0", 1L), Set.of(), Set.of());
                check((plan != null) == (fuel == 8), "Finite feed changed or stale count reused");
                if (plan != null) verify(plan, stock, Set.of());
            }
            check(solve(compiler, Map.of("fuel", 8L), 8, Map.of("k0", 1L), Set.of(), Set.of()) == null,
                    "Balanced counts invented a cycle seed");
            check(solve(compiler, Map.of("k0", 1L, "fuel", 8L), 8, Map.of("k0", 1L), Set.of(), Set.of("turn1")) == null,
                    "Removed producer was still used");
            var repaired = solve(compiler, Map.of("k0", 1L, "fuel", 8L), 8, Map.of("k0", 1L), Set.of(), Set.of());
            check(repaired != null, "Repair did not restore the witness");
        }
        var exact = PlanningInventory.exactSnapshot(Map.of("k0", BigInteger.ONE, "fuel", BigInteger.ONE.shiftLeft(90)));
        var huge = solve(new GraphCompiler<>(ring(8, 16)), exact, Long.MAX_VALUE, Map.of("k0", 1L), Set.of(), Set.of());
        check(huge != null && huge.patternTimesExact().values().stream().anyMatch(v -> v.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0),
                "Exact intermediate counts were clamped to long");
        verify(huge, exact, Set.of());

        var tool = new GraphRecipe<String>("tool", "tool", List.of(new GraphRecipe.Slot<>("ore", 1),
                new GraphRecipe.Slot<>("tool", 1, 1, true, false)), Map.of("G", 1L));
        var toolCompiler = new GraphCompiler<>(List.of(tool));
        check(solve(toolCompiler, Map.of("ore", 8L), 8, Map.of(), Set.of(), Set.of()) == null,
                "Configuration-only resource bypassed physical execution");
        var supplied = solve(toolCompiler, Map.of("ore", 8L), 8, Map.of(), Set.of("tool"), Set.of());
        check(supplied != null, "Explicit external supply was ignored");
        verify(supplied, Map.of("ore", 8L), Set.of("tool"));

        var lossy = new GraphCompiler<>(List.of(recipe("loss", Map.of("k0", 2L), Map.of("k1", 1L)),
                recipe("return", Map.of("k1", 1L), Map.of("k0", 1L)), recipe("finish", Map.of("k0", 1L), Map.of("G", 1L))));
        check(solve(lossy, Map.of("k0", 1L), 8, Map.of("k0", 1L), Set.of(), Set.of()) == null,
                "Repeated debt propagation invented a productive loop");
        check(solve(lossy, Map.of("k0", 9L), 8, Map.of("k0", 1L), Set.of(), Set.of()) != null,
                "Failed debt closure poisoned a subsequently funded request");

        var wide = ring(256, 1);
        var preferred = recipe("unfunded-cycle", Map.of("k255", 1L, "absent", 1L), Map.of("k0", 2L));
        wide.add(0, preferred);
        var fullBudget = new PlanningBudget(0, 8_000_000, 64L << 20, () -> false, System::nanoTime);
        var fullStock = Map.of("k0", 1L, "fuel", 8L);
        GraphPlan<String> full;
        var planner = new GraphPlanningWork<>(new GraphCompiler<>(wide), "G", 8, fullStock, Set.of(),
                Map.of("k0", 1L), true, true, fullBudget).catalysts(CatalystPolicy.MINIMAL);
        try {
            while (!planner.step()) {}
            full = planner.result();
        } finally { planner.close(); }
        check(full.feasible() && fullBudget.diagnostics().contains("count_demand"), "Main planner did not admit the wide feedback scout");
        verify(full, fullStock, Set.of());
        check(fullBudget.reservedBytes() == 0, "Main planner retained the completed scout");

        var insufficient = new PlanningBudget(0, 4_000_000, 8192, () -> false, System::nanoTime);
        try (var search = new CountDemandSearch<>(new GraphCompiler<>(ring(256, 1)), "G", 8,
                Map.of("k0", 1L, "fuel", 8L), Set.of(), Map.of("k0", 1L), Set.of(), true, true, insufficient, System.nanoTime())) {
            while (!search.step()) {}
            check(search.result() == null, "Memory refusal fabricated a result");
        }
        check(insufficient.reservedBytes() == 0, "Optional memory refusal leaked reservations");
        var cancelled = new PlanningBudget(0, 4_000_000, 32L << 20, () -> true, System::nanoTime);
        try (var search = new CountDemandSearch<>(toolCompiler, "G", 8, Map.of("ore", 8L),
                Set.of("tool"), Map.of(), Set.of(), true, true, cancelled, System.nanoTime())) {
            try { search.step(); throw new AssertionError("Cancellation ignored"); }
            catch (java.util.concurrent.CancellationException expected) { checks++; }
        }
        check(cancelled.reservedBytes() == 0, "Cancellation leaked reservations");
        System.out.println("CountDemandSearchTest PASS: checks=" + checks);
    }

    private static GraphPlan<String> solve(GraphCompiler<String> compiler, Map<String, Long> stock, long amount,
            Map<String, Long> seeds, Set<String> external, Set<String> excluded) {
        var budget = new PlanningBudget(0, 16_000_000, 64L << 20, () -> false, System::nanoTime);
        GraphPlan<String> plan;
        try (var search = new CountDemandSearch<>(compiler, "G", amount, stock, external, seeds, excluded,
                true, true, budget, System.nanoTime())) {
            while (!search.step()) {}
            plan = search.result();
        }
        check(budget.reservedBytes() == 0, "Count proposal or schedule leaked memory");
        if (plan != null) check(plan.feasible() && plan.missingExact().isEmpty(), "Published an unchecked purchase");
        return plan;
    }

    private static void verify(GraphPlan<String> plan, Map<String, Long> stock, Set<String> external) {
        var required = PlanVerifier.initialExact(plan.steps(), plan.recipes(), "G", plan.amount(), plan.seeds());
        for (var e : required.entrySet()) if (!external.contains(e.getKey()))
            check(e.getValue().compareTo(PlanningInventory.exactAmount(stock, e.getKey())) <= 0, "Unfunded physical prefix");
        check(SequenceSummary.of(plan.steps(), plan.recipes()).delta("G").compareTo(BigInteger.valueOf(plan.amount())) >= 0,
                "Forced target was borrowed instead of produced");
    }

    private static ArrayList<GraphRecipe<String>> ring(int size, long consumed) {
        var out = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < size - 1; i++) out.add(recipe("turn" + i, Map.of("k" + i, 1L), Map.of("k" + (i + 1), 1L)));
        out.add(recipe("grow", Map.of("k" + (size - 1), 1L, "fuel", 1L), Map.of("k0", 2L)));
        out.add(recipe("finish", Map.of("k0", consumed), Map.of("G", 1L)));
        return out;
    }
    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
