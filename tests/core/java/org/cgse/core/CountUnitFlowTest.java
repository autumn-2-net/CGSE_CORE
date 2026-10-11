// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Independent token capacity and guarded counterexamples for forced conversion orders. */
public final class CountUnitFlowTest {
    public static void main(String[] args) {
        int checks = 0;
        for (int order = 0; order < 6; order++) {
            var recipes = new ArrayList<>(List.of(recipe("grind", Map.of("I", 1L), Map.of("T", 1L)),
                    recipe("cast", Map.of("F", 144L, "tool", 1L), Map.of("I", 1L, "tool", 1L)),
                    recipe("melt", Map.of("T", 1L), Map.of("F", 144L))));
            Collections.shuffle(recipes, new Random(31267 + order));
            var compiler = new GraphCompiler<>(recipes);
            // Same compiler, changing stock and amounts: no prior absence may
            // survive new raw material or turn old target stock into production.
            for (long fluid : new long[]{199, 432, 199}) for (long amount : new long[]{1, 2, 3, 1000, 1_000_000_000L, Long.MAX_VALUE}) {
                var stock = Map.of("T", 49_859_202_484_469_322L, "F", fluid, "tool", 1L);
                var budget = budget();
                var plan = new GraphPlanner<>(compiler).plan("T", amount, stock, true, true, budget);
                boolean possible = amount <= fluid / 144;
                check(plan.feasible() == possible, "conversion capacity/order=" + order + "; fluid=" + fluid +
                        "; amount=" + amount + "; result=" + plan.result());
                check(Set.of(GraphPlan.Result.FEASIBLE, GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL,
                        GraphPlan.Result.MISSING_INPUT, GraphPlan.Result.MISSING_SEED, GraphPlan.Result.INFEASIBLE).contains(plan.result()),
                        "unit conversion still exhausts budget: " + plan.result());
                if (possible) {
                    PlanVerifier.verify(plan);
                    var delta = SequenceSummary.of(plan.steps(), plan.recipes()).delta("T");
                    check(delta.compareTo(BigInteger.valueOf(amount)) >= 0, "stock turnover counted as fresh material");
                }
                check(budget.reservedBytes() == 0, "conversion request leaked");
                checks++;
            }
        }
        exclusions();
        startupReadArc();
        multipleGoals();
        System.out.println("Unit flow: " + checks + " exact capacity/quantity/order/warm-stock checks plus startup and scope guards passed");
    }

    private static void exclusions() {
        check(!recognized(List.of(recipe("grow", Map.of("A", 2L, "B", 3L), Map.of("A", 1L, "T", 3L)),
                recipe("start", Map.of("T", 1L), Map.of("A", 1L))), Map.of("A", 1L, "B", 3L, "T", 1L), Map.of()),
                "joint-input startup was treated as a unit conversion");
        check(!recognized(List.of(recipe("grow", Map.of("T", 1L), Map.of("T", 2L))), Map.of("T", 1L), Map.of()),
                "productive multiplier treated as turnover");
        check(!recognized(List.of(recipe("batch", Map.of("A", 1L), Map.of("T", 2L)),
                recipe("start", Map.of("T", 1L), Map.of("A", 1L))), Map.of("T", 1L), Map.of()),
                "indivisible target batch lost its surplus");
        check(!recognized(List.of(recipe("one", Map.of("F", 144L), Map.of("T", 1L)),
                recipe("two", Map.of("T", 1L), Map.of("F", 72L))), Map.of("T", 1L), Map.of()),
                "inconsistent resource quantum accepted");
        var withTool = List.of(recipe("convert", Map.of("F", 1L, "tool", 1L), Map.of("T", 1L, "tool", 1L)),
                recipe("back", Map.of("T", 1L), Map.of("F", 1L)));
        check(!recognized(withTool, Map.of("T", 1L), Map.of()), "unfunded read arc accepted");
        check(recognized(withTool, Map.of("T", 1L, "tool", 1L), Map.of()), "fixed funded tool refused");
    }

    private static void startupReadArc() {
        var recipes = List.of(recipe("start", Map.of("T", 1L), Map.of("A", 1L)),
                recipe("make", Map.of("B", 1L, "A", 1L), Map.of("T", 1L, "A", 1L)));
        var stock = Map.of("T", 1L, "B", 2L);
        check(!recognized(recipes, stock, Map.of()), "mutable catalyst became an unconditional flow edge");
        verifyStartup(recipes, stock, Map.of(), new PlanStep.Sequence(List.of(new PlanStep.Batch("start", 1), new PlanStep.Batch("make", 2))));
    }

    private static void multipleGoals() {
        var recipes = List.of(recipe("reserve", Map.of("T", 1L), Map.of("A", 1L)),
                recipe("make", Map.of("B", 1L), Map.of("T", 1L)));
        var stock = Map.of("T", 1L, "B", 2L);
        check(!recognized(recipes, stock, Map.of("A", 1L)), "second mandatory flow goal was ignored");
        verifyStartup(recipes, stock, Map.of("A", 1L), new PlanStep.Sequence(List.of(new PlanStep.Batch("reserve", 1), new PlanStep.Batch("make", 2))));
    }

    private static void verifyStartup(List<GraphRecipe<String>> recipes, Map<String, Long> stock, Map<String, Long> seeds, PlanStep witness) {
        var budget = budget();
        var byId = new LinkedHashMap<String, GraphRecipe<String>>();
        recipes.forEach(r -> byId.put(r.id(), r));
        try (var candidate = new AllocationSearch.Candidate<>(witness, byId, "T", 2, stock, seeds, Set.of(), false, true, false, budget, System.nanoTime())) {
            while (!candidate.step()) {}
            check(candidate.plan != null && candidate.plan.feasible(), "legitimate target-consuming startup was rejected");
            check(candidate.summary.delta("T").equals(BigInteger.ONE), "startup counterexample no longer has low net gain");
            PlanVerifier.verify(candidate.plan);
        }
        check(budget.reservedBytes() == 0, "startup check leaked");
    }

    private static boolean recognized(List<GraphRecipe<String>> recipes, Map<String, Long> stock, Map<String, Long> seeds) {
        var budget = budget();
        try (var model = RecipeCountModel.create(new GraphCompiler<>(recipes), "T", 2, stock, seeds, Set.of(), Set.of(), false, budget)) {
            return CountUnitFlow.requiresNetProduction(model, "T");
        } finally { check(budget.reservedBytes() == 0, "certificate check leaked"); }
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 4_000_000, 256L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
