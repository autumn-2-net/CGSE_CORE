// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The recipe-domain selector constrains every primary solver entry path. */
public final class PrimaryGraphModeTest {
    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream()
                .map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static void check(boolean condition, Object message) {
        if (!condition) throw new AssertionError(message);
    }
    private static GraphPlan<String> solve(List<GraphRecipe<String>> recipes, Map<String, Long> stock,
                                            Map<String, Long> seeds, boolean preserve, GraphMode mode) {
        var budget = new PlanningBudget(0, 1_000_000, 32L << 20, () -> false, System::nanoTime);
        var work = new GraphPlanningWork<>(new GraphCompiler<>(recipes), "T", 1, stock, Set.of(), seeds,
                preserve, true, budget, mode);
        try {
            while (!work.step()) {}
            return work.result();
        } finally {
            work.close();
            check(budget.reservedBytes() == 0, "retained primary domain workspace");
        }
    }
    public static void run() {
        var grow = recipe("grow", Map.of("A", 1L, "raw", 1L), Map.of("A", 2L));
        var spend = recipe("spend", Map.of("A", 1L), Map.of("T", 1L));
        var cycle = List.of(grow, spend);
        var full = solve(cycle, Map.of("A", 1L, "raw", 1L), Map.of(), true, GraphMode.FULL);
        check(full.feasible(), "FULL lost executable growth");
        PlanVerifier.verify(full);
        var acyclic = solve(cycle, Map.of("A", 1L, "raw", 1L), Map.of(), true, GraphMode.ACYCLIC);
        check(!acyclic.patternTimes().containsKey("grow"), "ACYCLIC executed self-growth");
        check(acyclic.feasible() && acyclic.seeds().isEmpty(), "ACYCLIC inferred an automatic seed from the original growth cycle");
        check(acyclic.preserveSeeds(), "ACYCLIC changed preservation metadata");
        PlanVerifier.verify(acyclic);
        var explicit = solve(cycle, Map.of("A", 1L, "raw", 1L), Map.of("A", 1L), true, GraphMode.ACYCLIC);
        check(explicit.conditionalPreview() && explicit.seeds().equals(Map.of("A", 1L)) &&
                explicit.missingExact().getOrDefault("A", BigInteger.ZERO).equals(BigInteger.ONE),
                "explicit final inventory reserve was dropped or solved through the excluded cycle");
        var explicitNeeded = PlanVerifier.initialExact(explicit.steps(), explicit.recipes(), "T", 1, explicit.seeds());
        explicitNeeded.forEach((key, value) -> check(explicit.initialExact().getOrDefault(key, BigInteger.ZERO)
                .compareTo(value) >= 0, "explicit final reserve is unfunded"));
        var linear = recipe("linear", Map.of("raw", 1L), Map.of("T", 1L));
        var choice = List.of(recipe("unfunded", Map.of("absent", 1L), Map.of("T", 1L)), linear);
        acyclic = solve(choice, Map.of("raw", 1L), Map.of(), true, GraphMode.ACYCLIC);
        check(acyclic.feasible() && acyclic.patternTimes().containsKey("linear"), "restricted sources did not backtrack");
        check(acyclic.result() == GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, "restricted positive overstated proof");
        PlanVerifier.verify(acyclic);
        var seeded = solve(List.of(linear), Map.of("raw", 1L, "seed", 1L), Map.of("seed", 1L),
                true, GraphMode.ACYCLIC);
        check(seeded.feasible() && seeded.seeds().getOrDefault("seed", 0L) == 1, "explicit final seed lost");
        PlanVerifier.verify(seeded);
        var droppedTarget = solve(List.of(recipe("target-growth", Map.of("T", 1L), Map.of("T", 2L))),
                Map.of(), Map.of(), true, GraphMode.ACYCLIC);
        check(droppedTarget.result() == GraphPlan.Result.INCOMPLETE_MODEL && droppedTarget.missingExact().isEmpty(),
                "supplied target was mislabeled as a forced manufacturing witness");
        var excludedInput = solve(cycle, Map.of(), Map.of(), true, GraphMode.ACYCLIC);
        check(excludedInput.conditionalPreview(), "manufacturing preview with dropped input producer lost");
        check(excludedInput.missingBoundary("A").source() == GraphPlan.MissingSource.EXCLUDED_SOURCES,
                "dropped production source mislabeled as terminal raw supply");
        var rawPreview = solve(List.of(linear), Map.of(), Map.of(), true, GraphMode.ACYCLIC);
        check(rawPreview.conditionalPreview(), "raw-material preview lost");
        check(rawPreview.missingBoundary("raw").source() == GraphPlan.MissingSource.TERMINAL_UNDER_CURRENT_COVERAGE,
                "true raw supply incorrectly labeled as an excluded source");
        var negative = solve(List.of(grow), Map.of("A", 1L, "raw", 1L), Map.of(), true, GraphMode.ACYCLIC);
        check(negative.result() == GraphPlan.Result.INCOMPLETE_MODEL, "restricted empty domain proved infeasible");
    }
    private static void ordinaryCountAndAllocation() {
        var grow = recipe("grow", Map.of("A", 1L, "raw", 1L), Map.of("A", 2L));
        var spend = recipe("spend", Map.of("A", 1L), Map.of("T", 1L));
        var source = new GraphCompiler<>(List.of(grow, spend));
        var budget = new PlanningBudget(0, 1_000_000, 32L << 20, () -> false, System::nanoTime);
        var domain = source.beginDomain("T", Set.of(), GraphMode.ACYCLIC, budget);
        try {
            while (!domain.step()) {}
            var compiler = domain.result();
            var counts = new IntegerCountSearch<>(compiler, "T", 1, Map.of("A", 1L), Map.of(), Set.of(), Set.of(),
                    true, true, budget, System.nanoTime());
            try {
                while (!counts.step()) {}
                var result = counts.result();
                check(result != null && result.feasible() && result.seeds().isEmpty(), "count path inferred cycle startup seeds");
                PlanVerifier.verify(result);
            } finally { counts.close(); }
            var allocation = new AllocationSearch<>(compiler, "T", 1, Map.of("A", 1L), Set.of(), Map.of(),
                    true, true, Set.of(), budget, System.nanoTime());
            try {
                while (!allocation.step()) {}
                var result = allocation.result();
                check(result != null && result.feasible() && result.seeds().isEmpty(), "allocation path inferred cycle startup seeds");
                PlanVerifier.verify(result);
            } finally { allocation.discard(); }
        } finally { domain.close(); }
        check(budget.reservedBytes() == 0, "ordinary mode count/allocation workspace retained");
    }

    private static void reusableReadNeedsOnlyOrdinaryInput() {
        var recipe = new GraphRecipe<>("read", "read", List.of(new GraphRecipe.Slot<>("tool", 1, 0, true, true),
                new GraphRecipe.Slot<>("raw", 1)), Map.of("tool", 1L, "T", 1L));
        var budget = new PlanningBudget(0, 1_000_000, 32L << 20, () -> false, System::nanoTime);
        var work = new GraphPlanningWork<>(new GraphCompiler<>(List.of(recipe)), "T", 100,
                Map.of("tool", 100L, "raw", 100L), Set.of(), Map.of(), true, true, budget, GraphMode.ACYCLIC);
        try {
            while (!work.step()) {}
            var plan = work.result();
            check(plan.feasible() && plan.seeds().isEmpty() && plan.initialExact().get("tool").equals(BigInteger.ONE),
                    "ordinary reusable read inflated working stock or inferred a cycle seed");
            PlanVerifier.verify(plan);
        } finally { work.close(); }
        check(budget.reservedBytes() == 0, "ordinary reusable input workspace retained");
    }

    public static void main(String[] args) { run(); ordinaryCountAndAllocation(); reusableReadNeedsOnlyOrdinaryInput(); System.out.println("PrimaryGraphModeTest passed"); }
}
