// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Count feasibility must include the non-turnover obligation enforced on completed plans. */
public final class CountForcedGainTest {
    public static void main(String[] args) {
        var ring = List.of(recipe("ab", "A", "B"), recipe("bc", "B", "C"), recipe("ca", "C", "A"));
        int cases = 0;
        for (int order = 0; order < 12; order++) {
            var recipes = new ArrayList<>(ring);
            Collections.shuffle(recipes, new Random(9137 + order));
            var compiler = new GraphCompiler<>(recipes);
            for (boolean preserve : new boolean[]{false, true}) {
                var budget = budget();
                var plan = new GraphPlanner<>(compiler).plan("A", 1, Map.of("A", 1L), preserve, true, budget);
                check(!plan.feasible() && Set.of(GraphPlan.Result.MISSING_INPUT, GraphPlan.Result.MISSING_SEED,
                        GraphPlan.Result.INFEASIBLE).contains(plan.result()), "neutral turnover exhausted search: " + plan.result());
                check(plan.patternTimesExact().getOrDefault("ab", BigInteger.ZERO).signum() == 0,
                        "preview retained an unproductive complete turnover");
                check(budget.reservedBytes() == 0, "neutral request leaked");
                cases++;

                budget = budget();
                var funded = new GraphPlanner<>(compiler).plan("A", 1, Map.of("B", 1L), preserve, true, budget);
                check(funded.feasible(), "linear funded exit was pruned");
                PlanVerifier.verify(funded);
                check(budget.reservedBytes() == 0, "funded request leaked");
                cases++;
            }
            var budget = budget();
            var stocked = new GraphPlanner<>(compiler).plan("A", 1, Map.of("A", 1L), false, false, budget);
            check(stocked.feasible() && stocked.patternTimesExact().isEmpty(), "unforced stock delivery was tightened");
            check(budget.reservedBytes() == 0, "unforced request leaked");
            cases++;
        }
        // Consuming one target seed is valid: net C gain is two, gross output
        // is three and all three requested units are available at the end.
        var startup = List.of(new GraphRecipe<>("grow", "grow", List.of(new GraphRecipe.Slot<>("A", 2),
                        new GraphRecipe.Slot<>("B", 3)), Map.of("A", 1L, "C", 3L)), recipe("seed", "C", "A"));
        var budget = budget();
        var plan = new GraphPlanner<>(new GraphCompiler<>(startup)).plan("C", 3,
                Map.of("A", 1L, "B", 3L, "C", 1L), false, true, budget);
        check(plan.feasible(), "positive startup gain was strengthened to the entire requested amount");
        PlanVerifier.verify(plan);
        check(budget.reservedBytes() == 0, "startup request leaked");
        modelScopes();
        System.out.println("Forced gain: " + cases + " ring/order/preservation cases; productive startup, batch configuration, warm scopes and long bounds passed");
    }

    private static void modelScopes() {
        var config = new GraphRecipe<>("config", "config", List.of(new GraphRecipe.Slot<>("A", 2, 0, true),
                new GraphRecipe.Slot<>("B", 1)), Map.of("A", 1L));
        var compiler = new GraphCompiler<>(List.of(config));
        for (long stock : new long[]{2, Long.MAX_VALUE, 2}) {
            var budget = budget();
            try (var model = RecipeCountModel.create(compiler, "A", 1, Map.of("A", stock, "B", 3L),
                    Map.of(), Set.of(), Set.of(), true, budget)) {
                // One physical push of three runs consumes two configuration
                // A once and returns three A. Per-firing subtraction would
                // incorrectly remove this positive-gain candidate.
                check(model != null && fits(model.constraints, new BigInteger[]{BigInteger.valueOf(3)}),
                        "per-push configuration charged once per firing");
            }
            check(budget.reservedBytes() == 0, "configuration scope leaked");
        }
        var neutral = new GraphRecipe<>("neutral", "neutral", List.of(new GraphRecipe.Slot<>("A", 1)),
                Map.of("A", 1L, "B", 1L));
        compiler = new GraphCompiler<>(List.of(neutral));
        for (boolean force : new boolean[]{true, false, true}) {
            var budget = budget();
            try (var model = RecipeCountModel.create(compiler, "A", 1, Map.of("A", Long.MAX_VALUE),
                    Map.of(), Set.of(), Set.of(), force, budget)) {
                check(fits(model.constraints, new BigInteger[]{BigInteger.ONE}) == !force,
                        "warm catalog lost the request-local positive-gain condition");
            }
            check(budget.reservedBytes() == 0, "warm forced scope leaked");
        }
    }

    private static boolean fits(List<ExactLinearProgram.Constraint> rows, BigInteger[] counts) {
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(counts[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private static GraphRecipe<String> recipe(String id, String input, String output) {
        return new GraphRecipe<>(id, id, List.of(new GraphRecipe.Slot<>(input, 1)), Map.of(output, 1L));
    }

    private static PlanningBudget budget() {
        return new PlanningBudget(0, 1_000_000, 64L << 20, () -> false, System::nanoTime);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
