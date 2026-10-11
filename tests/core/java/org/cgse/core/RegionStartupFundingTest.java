// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Upstream feed is manufacturable; unavailable internal startup stock is not interchangeable with it. */
public final class RegionStartupFundingTest {
    private static GraphRecipe<String> recipe(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        return new GraphRecipe<>(id, id, inputs.entrySet().stream()
                .map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), outputs);
    }

    public static void main(String[] args) {
        int checked = 0;
        for (int order = 0; order < 12; order++) for (long gas : new long[] {0, 20, 99}) {
            var recipes = new ArrayList<>(List.of(
                    recipe("solid", Map.of("liquid", 1L, "feed", 1L), Map.of("crystal", 1L)),
                    recipe("separate", Map.of("gas", 100L), Map.of("liquid", 1L)),
                    recipe("vaporize", Map.of("crystal", 1L, "carrier", 100L), Map.of("gas", 900L)),
                    recipe("fresh", Map.of("ore", 1L), Map.of("carrier", 1L))));
            Collections.shuffle(recipes, new Random(order));
            var stock = Map.of("crystal", 400L, "gas", gas, "ore", 10000L, "feed", 100L);
            var budget = new PlanningBudget(0, 1_000_000, 64L << 20, () -> false, System::nanoTime);
            var compiler = new GraphCompiler<>(recipes);
            GraphCompiler.Compiled<String> graph;
            try (var compilation = compiler.begin("crystal", Set.of(), Map.of(), Set.of(), budget)) {
                while (!compilation.step()) {}
                graph = compilation.result();
            }
            // Exercise the local choice itself, before global allocation or
            // recursive bootstrap can hide an unfunded cycle ordering.
            try (var solve = new GraphSolve<>(graph, "crystal", 10, stock, Set.of(), Map.of(), true, true,
                    budget, 0, new CatalystPolicy(65, 64), stock)) {
                while (!solve.step()) {}
                var plan = solve.result();
                check(plan.feasible(), "Manufacturable feed lost to missing startup: " + order + "/" + gas + " " + plan.missingExact());
                Map<String, BigInteger> held = new HashMap<>(plan.initialExact());
                held.forEach((key, count) -> check(count.compareTo(BigInteger.valueOf(stock.getOrDefault(key, 0L))) <= 0,
                        "Unfunded initial inventory"));
                BigInteger initialCrystal = held.getOrDefault("crystal", BigInteger.ZERO);
                execute(plan.steps(), plan, held);
                check(held.getOrDefault("crystal", BigInteger.ZERO).subtract(initialCrystal).compareTo(BigInteger.TEN) >= 0,
                        "Forced order did not grow the crystal stock");
                plan.seeds().forEach((key, count) -> check(held.getOrDefault(key, BigInteger.ZERO).compareTo(BigInteger.valueOf(count)) >= 0,
                        "Lost parallel seed contract"));
                check(plan.seeds().getOrDefault("crystal", 0L) > 1, "Funded parallel copies were silently reduced to minimum startup");
                PlanVerifier.verify(plan);
                PlanVerifier.verifyRuntimeInventory(plan);
                checked++;
            }
            check(budget.reservedBytes() == 0, "Regional choice leaked");
        }
        System.out.println("Regional startup funding: " + checked + " ordered/partial-stock witnesses passed");
    }

    private static void execute(PlanStep step, GraphPlan<String> plan, Map<String, BigInteger> held) {
        if (step instanceof PlanStep.Sequence sequence) {
            for (var child : sequence.children()) execute(child, plan, held);
        } else if (step instanceof PlanStep.Repeat repeat) {
            for (long i = 0; i < repeat.times(); i++) execute(repeat.body(), plan, held);
        } else {
            var batch = (PlanStep.Batch) step;
            var recipe = plan.recipes().get(batch.recipe());
            for (long i = 0; i < batch.runs(); i++) {
                recipe.inputs().forEach((key, value) -> {
                    BigInteger next = held.getOrDefault(key, BigInteger.ZERO).subtract(BigInteger.valueOf(value));
                    check(next.signum() >= 0, "Unexecutable prefix: " + recipe.id() + "/" + key);
                    held.put(key, next);
                });
                recipe.outputs().forEach((key, value) -> held.merge(key, BigInteger.valueOf(value), BigInteger::add));
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
