// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/** Many upstream ports must not hide a small local count model. */
public final class RegionBoundaryProjectionTest {
    private static GraphRecipe<String> recipe(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        return new GraphRecipe<>(id, id, inputs.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), outputs);
    }

    private static List<GraphRecipe<String>> recipes(int ports) {
        var inputs = new LinkedHashMap<String, Long>();
        inputs.put("A", 1L);
        for (int i = 0; i < ports; i++) inputs.put("feed" + i, 1L);
        return List.of(recipe("grow", inputs, Map.of("B", 2L)), recipe("return", Map.of("B", 1L), Map.of("A", 1L)));
    }

    private static PlanningBudget budget() { return new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime); }

    public static void main(String[] args) {
        for (int ports : new int[] { 255, 256, 600, 1200 }) for (boolean reverse : new boolean[] { false, true }) {
            var recipes = new ArrayList<>(recipes(ports));
            if (reverse) Collections.reverse(recipes);
            Map<String, BigInteger> goals = Map.of("B", BigInteger.valueOf(100));
            Map<String, Long> stock = new LinkedHashMap<>();
            stock.put("A", 1L);
            for (int i = 0; i < ports; i++) stock.put("feed" + i, 100L);
            var budget = budget();
            try (var model = RecipeCountModel.region(recipes, goals, stock, Set.of(), budget)) {
                check(model != null, "Boundary ports blocked a two-row model");
                check(model.keys.size() == ports + 2 && model.constraints.size() == 2, "Boundary restoration metadata was lost");
                for (int i = 0; i < ports; i++) check(model.external.contains("feed" + i), "Upstream input became a local requirement");
                try (var bounds = new CountBounds(2, model.constraints, budget)) {
                    while (!bounds.step()) {}
                    check(!bounds.blocked(), "Funded growth cycle rejected");
                    var counts = bounds.lowerBounds();
                    check(counts[reverse ? 1 : 0].equals(BigInteger.valueOf(99)) && counts[reverse ? 0 : 1].equals(BigInteger.valueOf(98)),
                            "Wrong exact region counts");
                }
            }
            check(budget.reservedBytes() == 0, "Projected model leaked");
            // Solve and summarize ORIGINAL recipes: none of the upstream
            // consumption may disappear merely because the local rows omit it.
            var solveBudget = budget();
            try (var counts = new RegionCounts<>(recipes, goals, stock, Set.of(), "B", 100, false, false, solveBudget)) {
                while (!counts.step()) {}
                var result = counts.result();
                check(result != null, "No restored region witness: " + solveBudget.diagnostics());
                for (int i = 0; i < ports; i++) check(result.summary().delta("feed" + i).equals(BigInteger.valueOf(-99)), "Lost upstream consumption");
                var byId = new LinkedHashMap<String, GraphRecipe<String>>();
                recipes.forEach(recipe -> byId.put(recipe.id(), recipe));
                var plan = new GraphPlan<>("B", 100, false, result.body(), byId, stock, Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
                PlanVerifier.verify(plan);
                PlanVerifier.verifyRuntimeInventory(plan);
            }
            check(solveBudget.reservedBytes() == 0, "Restored schedule leaked");
        }
        constrainedRowsRemainBounded();
        cancellationAndMemory();
        System.out.println("Region boundary projection: eight original-recipe schedules, row admission, memory and cancellation passed");
    }

    private static void constrainedRowsRemainBounded() {
        var outputs = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 257; i++) outputs.put("key" + i, 1L);
        var recipes = List.of(recipe("many", Map.of(), outputs));
        var budget = budget();
        check(RecipeCountModel.region(recipes, Map.of(), Map.of(), Set.of(), budget) == null, "Constrained-row cap grew");
        try (var allowed = RecipeCountModel.region(recipes, Map.of(), Map.of(), Set.of("key0"), budget)) {
            check(allowed != null && allowed.constraints.size() == 256, "External output consumed a constrained-row slot");
        }
        check(budget.reservedBytes() == 0, "Admission leaked");
    }

    private static void cancellationAndMemory() {
        var recipes = recipes(600);
        var goals = Map.of("B", BigInteger.valueOf(100));
        for (int stop = 1; stop <= 300; stop++) {
            int limit = stop;
            var calls = new AtomicInteger();
            var budget = new PlanningBudget(0, 4_000_000, 64L << 20, () -> calls.incrementAndGet() >= limit, System::nanoTime);
            try (var model = RecipeCountModel.region(recipes, goals, Map.of("A", 1L), Set.of(), budget)) {
                if (model != null) check(model.constraints.size() == 2, "Bad admitted model");
            } catch (CancellationException expected) {}
            check(budget.reservedBytes() == 0, "Cancellation leaked at " + stop);
        }
        for (long memory : new long[] { 1, 1024, 512L << 10 }) {
            var budget = new PlanningBudget(0, 4_000_000, memory, () -> false, System::nanoTime);
            try (var model = RecipeCountModel.region(recipes, goals, Map.of("A", 1L), Set.of(), budget)) {
                check(model == null, "Unfunded optional model admitted");
            }
            check(budget.reservedBytes() == 0, "Memory refusal leaked");
        }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
