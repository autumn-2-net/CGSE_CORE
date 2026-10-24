// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Completed allocation proposals release their startup loans on both exits. */
public final class AllocationCandidateLifecycleTest {
    public static void main(String[] args) {
        for (boolean reverse : List.of(false, true)) for (boolean preserve : List.of(false, true))
            for (long ore : new long[]{2, 3}) completed(reverse, preserve, ore);
        interruptions();
        System.out.println("AllocationCandidateLifecycleTest PASS");
    }

    private static List<GraphRecipe<String>> recipes(boolean reverse) {
        var recipes = new ArrayList<>(List.of(recipe("grow", Map.of("A", 1L, "ore", 1L), Map.of("A", 2L)),
                recipe("finish", Map.of("A", 3L), Map.of("G", 1L))));
        if (reverse) Collections.reverse(recipes);
        return recipes;
    }

    private static AllocationSearch<String> search(List<GraphRecipe<String>> recipes, Map<String, Long> stock,
                                                   boolean preserve, PlanningBudget budget) {
        return new AllocationSearch<>(new GraphCompiler<>(recipes), "G", 1, stock, Set.of(), Map.of(),
                preserve, true, Set.of(), budget, System.nanoTime());
    }

    private static void completed(boolean reverse, boolean preserve, long ore) {
        var stock = Map.of("A", 1L, "ore", ore);
        var budget = new PlanningBudget(0, 131_072, 16L << 20, () -> false, System::nanoTime);
        var search = search(recipes(reverse), stock, preserve, budget);
        GraphPlan<String> plan;
        try {
            while (!search.step()) {}
            plan = search.result();
        } finally { search.discard(); }
        check(budget.reservedBytes() == 0, "Completed allocation candidate retained " + budget.reservedBytes() + " bytes");
        if (preserve && ore == 2) {
            // The quick witness spends the last A token. Its guard records a
            // finite startup loan before rejection; disposing that proposal
            // must release the loan even when no executable plan is found.
            check(plan == null, "Depleted startup proposal became executable");
            return;
        }
        check(plan != null && plan.feasible(), "Executable allocation proposal was lost");
        // The published plan remains usable after its builder was closed.
        PlanVerifier.verify(plan);
        var held = new HashMap<String, BigInteger>();
        stock.forEach((key, count) -> held.put(key, BigInteger.valueOf(count)));
        execute(plan.steps(), plan.recipes(), held);
        check(held.getOrDefault("G", BigInteger.ZERO).compareTo(BigInteger.ONE) >= 0, "Lost target delivery");
        check(held.getOrDefault("A", BigInteger.ZERO).compareTo(preserve ? BigInteger.ONE : BigInteger.ZERO) >= 0,
                "Published preserving plan lost startup");
    }

    private static void interruptions() {
        for (int steps : new int[]{1, 8, 32, 64, 128}) {
            var budget = new PlanningBudget(0, 131_072, 16L << 20, () -> false, System::nanoTime);
            var search = search(recipes(false), Map.of("A", 1L, "ore", 2L), true, budget);
            try {
                for (int i = 0; i < steps && !search.step(); i++) {}
                budget.cancel();
                try { search.step(); throw new AssertionError("Allocation ignored cancellation"); }
                catch (CancellationException expected) { /* Completed and partial proposals must both be disposable. */ }
            } finally { search.discard(); }
            check(budget.reservedBytes() == 0, "Cancelled allocation candidate retained workspace");
        }
    }

    private static void execute(PlanStep word, Map<String, GraphRecipe<String>> recipes, Map<String, BigInteger> held) {
        if (word instanceof PlanStep.Sequence sequence) {
            for (var child : sequence.children()) execute(child, recipes, held);
        } else if (word instanceof PlanStep.Repeat repeat) {
            check(repeat.times() < 100, "Unexpected test repeat");
            for (long i = 0; i < repeat.times(); i++) execute(repeat.body(), recipes, held);
        } else {
            var batch = (PlanStep.Batch) word; var recipe = recipes.get(batch.recipe());
            check(batch.runs() < 100, "Unexpected test batch");
            for (long i = 0; i < batch.runs(); i++) {
                for (var input : recipe.inputs().entrySet()) {
                    BigInteger remaining = held.getOrDefault(input.getKey(), BigInteger.ZERO).subtract(BigInteger.valueOf(input.getValue()));
                    check(remaining.signum() >= 0, "Unfunded physical input"); held.put(input.getKey(), remaining);
                }
                recipe.outputs().forEach((key, count) -> held.merge(key, BigInteger.valueOf(count), BigInteger::add));
            }
        }
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        return new GraphRecipe<>(id, id, inputs.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), outputs);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
