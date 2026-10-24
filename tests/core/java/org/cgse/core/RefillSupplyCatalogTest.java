// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** A local growth prefix may consume a seed belonging to another component. */
public final class RefillSupplyCatalogTest {
    private static int checks;

    public static void main(String[] args) {
        for (int seed = 0; seed < 4; seed++) {
            verify(seed, true, Set.of("source"), true);
            verify(seed, false, Set.of("source"), false);
            verify(seed, true, Set.of(), false);
            verify(seed, true, Set.of("source", "growA"), false);
        }
        System.out.println("RefillSupplyCatalogTest PASS: " + checks + " checks");
    }

    private static void verify(int orderSeed, boolean preserve, Set<String> excluded, boolean protectA) {
        var make = recipe("make", Map.of("X", 1L, "A", 1L), Map.of("Y", 1L));
        var back = recipe("back", Map.of("Y", 1L), Map.of("X", 2L));
        var finish = recipe("finish", Map.of("X", 10L), Map.of("T", 1L));
        var grow = recipe("growA", Map.of("A", 1L, "ore", 1L), Map.of("A", 2L));
        var source = recipe("source", Map.of("raw", 1L), Map.of("A", 1L));
        var catalog = new ArrayList<>(List.of(make, back, finish, grow, source));
        Collections.shuffle(catalog, new Random(orderSeed));
        var compiler = new GraphCompiler<>(catalog);
        var stock = Map.of("X", 1L, "A", 10L, "ore", 30L);
        // The suffix alone leaves A untouched. Its bindings intentionally have
        // neither the prefix SCC nor the disjoint A growth recipe.
        var original = new GraphPlan<>("T", 1, preserve, new PlanStep.Batch("finish", 1), Map.of("finish", finish),
                Map.of("X", 11L), Map.of("X", 1L), Map.of("X", 10L), GraphPlan.Result.MISSING_INPUT, 0, 0);
        var budget = new PlanningBudget(0, 100_000, 16L << 20, () -> false, System::nanoTime);
        GraphPlan<String> result;
        try (var ordering = new RefillProducerOrder<>(compiler, original.missingExact().keySet(), Set.of(), excluded, budget)) {
            while (!ordering.step()) { }
            try (var search = new RefillSupplySearch<>(original, ordering, stock, Set.of(), true, false, budget)
                    .tradeoffs(compiler, excluded)) {
                while (!search.step()) { }
                result = search.result();
            }
        }
        check(budget.reservedBytes() == 0, "Disjoint producer guard leaked");
        var alternatives = new ArrayList<GraphPlan<String>>(); alternatives.add(result); alternatives.addAll(result.alternatives());
        for (var plan : alternatives) {
            var held = new LinkedHashMap<String, BigInteger>();
            stock.forEach((key, amount) -> held.put(key, BigInteger.valueOf(amount)));
            plan.missingExact().forEach((key, amount) -> held.merge(key, amount, BigInteger::add));
            execute(plan.steps(), plan.recipes(), held);
            check(held.getOrDefault("T", BigInteger.ZERO).equals(BigInteger.ONE), "Changed target delivery");
            check(held.getOrDefault("X", BigInteger.ZERO).compareTo(BigInteger.ONE) >= 0, "Lost the explicit seed");
            if (protectA) check(held.getOrDefault("A", BigInteger.ZERO).signum() > 0, "Local prefix exhausted another component's growth seed");
        }
        if (!protectA) check(result.feasible(), "Lost an allowed funded prefix");
    }

    private static void execute(PlanStep step, Map<String, GraphRecipe<String>> recipes, Map<String, BigInteger> held) {
        if (step instanceof PlanStep.Batch batch) {
            var recipe = recipes.get(batch.recipe());
            for (long i = 0; i < batch.runs(); i++) {
                recipe.inputs().forEach((key, amount) -> {
                    var left = held.getOrDefault(key, BigInteger.ZERO).subtract(BigInteger.valueOf(amount));
                    check(left.signum() >= 0, "Prefix borrowed future inventory"); held.put(key, left);
                });
                recipe.outputs().forEach((key, amount) -> held.merge(key, BigInteger.valueOf(amount), BigInteger::add));
            }
        } else if (step instanceof PlanStep.Repeat repeat) {
            for (long i = 0; i < repeat.times(); i++) execute(repeat.body(), recipes, held);
        } else for (var child : ((PlanStep.Sequence) step).children()) execute(child, recipes, held);
    }
    private static GraphRecipe<String> recipe(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        return new GraphRecipe<>(id, id, inputs.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), outputs);
    }
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
