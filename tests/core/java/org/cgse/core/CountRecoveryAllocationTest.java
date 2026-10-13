// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Repair competing fuel credits with joint integer decisions, then replay real recipes. */
public final class CountRecoveryAllocationTest {
    public static void main(String[] args) {
        for (int order = 0; order < 8; order++) for (long fuel : new long[] {9, 0, 9}) run(order, fuel);
        System.out.println("Recovery allocation: joint source/burn repair, exact prefix verification, permutations and stock changes passed");
    }

    private static void run(int order, long fuel) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < 4; i++) {
            String raw = i < 2 ? "U" : "V", product = i < 2 ? "X" : "Y", f = "F" + (i % 2);
            long cost = i == 0 || i == 3 ? 2 : 1;
            recipes.add(CountChainScheduleTest.recipe("r" + i, Map.of("C", 1L, raw, 1L, f, cost), Map.of("C", 1L, product, 1L)));
            recipes.add(CountChainScheduleTest.recipe("b" + i, Map.of("C", 1L, raw, 1L), Map.of(product, 1L)));
        }
        recipes.add(CountChainScheduleTest.recipe("deliver", Map.of("X", 10L, "Y", 10L), Map.of("G", 1L)));
        Collections.shuffle(recipes, new Random(5913 + order));
        var stock = Map.of("C", 2L, "U", 10L, "V", 10L, "F0", fuel, "F1", fuel);
        var budget = CountChainScheduleTest.budget(() -> false);
        var retained = new AtomicLong();
        try (var model = RecipeCountModel.forShell(recipes, Map.of("G", BigInteger.ONE), stock, Set.of(), budget)) {
            var allocation = CountRecoveryFuel.compile(model, recipes, budget);
            CountChainScheduleTest.check(allocation != null, "isolated account view missed");
            Map<String, GraphRecipe<String>> free = new LinkedHashMap<>();
            allocation.recipes().forEach(r -> free.put(r.id(), r));
            var steps = free.values().stream().map(r -> (PlanStep) new PlanStep.Batch(r.id(), r.id().equals("deliver") ? 1 : 5)).toList();
            // This deliberately optimistic count proposal overspends both fuel
            // accounts. The repair must change source choices, not invent fuel.
            var candidate = new GraphPlan<>("G", 1, false, new PlanStep.Sequence(steps), free, stock, Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
            CountChainScheduleTest.check(allocation.lift(candidate, retained::addAndGet) == null, "fixture stopped testing failed credits");
            var repaired = allocation.repair(candidate, retained::addAndGet);
            if (fuel == 0) CountChainScheduleTest.check(repaired == null, "unfunded repair accepted");
            else {
                CountChainScheduleTest.check(repaired != null, "joint repair missed cheap alternatives");
                Map<String, GraphRecipe<String>> real = new LinkedHashMap<>(); recipes.forEach(r -> real.put(r.id(), r));
                var proof = GraphFixtureRegression.summary(repaired, real, new IdentityHashMap<>());
                for (var term : proof.need().entrySet()) CountChainScheduleTest.check(term.getValue().compareTo(BigInteger.valueOf(stock.getOrDefault(term.getKey(), 0L))) <= 0, "unfunded repaired prefix");
                CountChainScheduleTest.check(proof.delta().get("G").equals(BigInteger.ONE), "repair lost target");
                var plan = new GraphPlan<>("G", 1, false, repaired, real, stock, Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
                PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
            }
        } finally { budget.release(retained.get()); }
        CountChainScheduleTest.check(budget.reservedBytes() == 0, "repair leaked memory");
    }
}
