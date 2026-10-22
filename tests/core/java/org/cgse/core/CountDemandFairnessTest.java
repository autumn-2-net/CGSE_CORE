// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Both uniform source orders may follow endless feedback while a joint source is executable. */
public final class CountDemandFairnessTest {
    private static int checks;

    public static void main(String[] args) {
        for (boolean reverse : List.of(false, true)) {
            var recipes = feedback(reverse);
            var compiler = new GraphCompiler<>(recipes);
            for (long work : new long[] {1_000_000, 16_000_000}) {
                var stock = Map.of("raw", 8L);
                // Independent positive oracle: one raw unit supplies both ingredients.
                verify(recipes, new PlanStep.Sequence(List.of(new PlanStep.Batch("joint", 8),
                        new PlanStep.Batch("finish", 8))), stock, 8);
                var plan = solve(compiler, stock, Set.of(), work);
                check(plan != null, "Uniform feedback exhausted the portfolio before its joint source: reverse=" +
                        reverse + "; work=" + work);
                verify(recipes, plan.steps(), stock, 8);
            }
            check(solve(compiler, Map.of(), Set.of(), 1_000_000) == null,
                    "Fair scheduling invented raw input or a feedback seed");
            check(solve(compiler, Map.of("raw", 8L), Set.of("joint"), 1_000_000) == null,
                    "Excluded joint source still supplied the request");
            var repaired = solve(compiler, Map.of("raw", 8L), Set.of(), 1_000_000);
            check(repaired != null, "A failed intervening request poisoned the joint source");
            verify(recipes, repaired.steps(), Map.of("raw", 8L), 8);
        }
        System.out.println("CountDemandFairnessTest PASS: checks=" + checks);
    }

    private static List<GraphRecipe<String>> feedback(boolean reverse) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        recipes.add(recipe("leftA", Map.of("leftA-token", 1L), Map.of("A", 1L)));
        recipes.add(recipe("leftZ", Map.of("leftZ-token", 1L), Map.of("Z", 1L)));
        recipes.add(recipe("joint", Map.of("raw", 1L), Map.of("A", 1L, "Z", 1L)));
        recipes.add(recipe("rightA", Map.of("rightA-token", 1L), Map.of("A", 1L)));
        recipes.add(recipe("rightZ", Map.of("rightZ-token", 1L), Map.of("Z", 1L)));
        for (String side : List.of("left", "right")) for (String key : List.of("A", "Z"))
            recipes.add(recipe(side + key + "-return", Map.of(key, 1L), Map.of(side + key + "-token", 1L)));
        recipes.add(recipe("finish", Map.of("A", 1L, "Z", 1L), Map.of("G", 1L)));
        if (reverse) Collections.reverse(recipes);
        return recipes;
    }

    private static GraphPlan<String> solve(GraphCompiler<String> compiler, Map<String, Long> stock,
                                            Set<String> excluded, long work) {
        var budget = new PlanningBudget(0, work, 32L << 20, () -> false, System::nanoTime);
        GraphPlan<String> plan;
        try (var search = new CountDemandSearch<>(compiler, "G", 8, stock, Set.of(), Map.of(), excluded,
                true, true, budget, System.nanoTime())) {
            while (!search.step()) {}
            plan = search.result();
        }
        check(budget.reservedBytes() == 0, "Fairness search retained request workspace");
        if (plan != null) check(plan.feasible() && plan.missingExact().isEmpty(), "Published an unchecked purchase");
        return plan;
    }

    private static void verify(List<GraphRecipe<String>> catalog, PlanStep program, Map<String, Long> stock,
                               long amount) {
        var recipes = new HashMap<String, GraphRecipe<String>>();
        catalog.forEach(r -> recipes.put(r.id(), r));
        var held = new HashMap<String, BigInteger>();
        stock.forEach((k, v) -> held.put(k, BigInteger.valueOf(v)));
        execute(program, recipes, held);
        check(held.getOrDefault("G", BigInteger.ZERO).compareTo(BigInteger.valueOf(amount)) >= 0,
                "Execution did not produce the requested goal");
    }

    private static void execute(PlanStep step, Map<String, GraphRecipe<String>> recipes,
                                Map<String, BigInteger> held) {
        if (step instanceof PlanStep.Sequence sequence) {
            for (var child : sequence.children()) execute(child, recipes, held);
        } else if (step instanceof PlanStep.Repeat repeat) {
            check(repeat.times() <= 100_000, "Unexpectedly large literal witness");
            for (long i = 0; i < repeat.times(); i++) execute(repeat.body(), recipes, held);
        } else if (step instanceof PlanStep.Batch batch) {
            var recipe = recipes.get(batch.recipe());
            check(recipe != null, "Unknown recipe in witness");
            var runs = BigInteger.valueOf(batch.runs());
            for (var input : recipe.inputs().entrySet()) {
                var left = held.getOrDefault(input.getKey(), BigInteger.ZERO)
                        .subtract(BigInteger.valueOf(input.getValue()).multiply(runs));
                check(left.signum() >= 0, "Unfunded physical prefix: " + input.getKey());
                held.put(input.getKey(), left);
            }
            recipe.outputs().forEach((k, v) -> held.merge(k, BigInteger.valueOf(v).multiply(runs), BigInteger::add));
        }
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> input, Map<String, Long> output) {
        return new GraphRecipe<>(id, id, new TreeMap<>(input).entrySet().stream()
                .map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), new TreeMap<>(output));
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
