// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** A replacement word need not reproduce the previous word's batch surplus. */
public final class RefillProductionCreditTest {
    private static void check(boolean okay, String reason) { if (!okay) throw new AssertionError(reason); }
    private static GraphRecipe<String> r(String id, long input, long output) {
        return new GraphRecipe<>(id, id, List.of(new GraphRecipe.Slot<>("raw", input)), Map.of("T", output));
    }
    private static void complementaryRoutes() {
        var random = new Random(2992026);
        for (int trial = 0; trial <= 411; trial++) {
            var recipes = new ArrayList<GraphRecipe<String>>(); var stock = new HashMap<String, Long>();
            for (int i = 0; i < 6; i++) stock.put("k" + i, (long) random.nextInt(8));
            for (int i = 0; i < 8; i++) {
                var in = new LinkedHashMap<String, Long>(); var out = new LinkedHashMap<String, Long>();
                for (int j = 0; j < 1 + random.nextInt(2); j++) in.merge("k" + random.nextInt(6), 1L + random.nextInt(3), Long::sum);
                for (int j = 0; j < 1 + random.nextInt(2); j++) out.merge("k" + random.nextInt(6), 1L + random.nextInt(3), Long::sum);
                recipes.add(new GraphRecipe<>("r" + i, "r" + i, in.entrySet().stream()
                        .map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out));
            }
            int amount = 1 + random.nextInt(6);
            if (trial != 232 && trial != 411) continue;
            for (var mode : GraphMode.values()) {
                var budget = new PlanningBudget(0, 131072, 16L << 20, () -> false, System::nanoTime);
                var work = new GraphPlanningWork<>(new GraphCompiler<>(recipes), "k0", amount, stock,
                        Set.of(), Map.of(), true, true, budget, mode);
                try {
                    while (!work.step()) {}
                    var plan = work.result();
                    var expected = trial == 232 ? Map.of("k1", BigInteger.valueOf(4)) :
                            Map.of("k3", BigInteger.valueOf(mode == GraphMode.FULL ? 2 : 8));
                    check(plan.missingExact().equals(expected), "Lost complementary stock/whole-source route: " + trial + " " + mode + " " + plan.missingExact());
                    plan.initialExact().forEach((key, count) -> check(plan.missingExact().getOrDefault(key, BigInteger.ZERO)
                            .equals(count.subtract(BigInteger.valueOf(stock.getOrDefault(key, 0L))).max(BigInteger.ZERO)), "Incorrect exact deficit"));
                    check(SequenceSummary.of(plan.steps(), plan.recipes()).delta("k0").compareTo(BigInteger.valueOf(amount)) >= 0,
                            "Stock loan replaced manufacturing");
                    PlanVerifier.verify(new GraphPlan<>(plan.target(), plan.amount(), plan.preserveSeeds(), plan.steps(),
                            plan.recipes(), plan.initialExact(), plan.seeds(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0));
                } finally { work.close(); }
                check(budget.reservedBytes() == 0, "Complementary route workspace leaked");
            }
        }
    }
    public static void main(String[] args) {
        var expensive = r("old", 3, 2); var cheap = r("replacement", 1, 1);
        var recipes = List.of(expensive, cheap); var full = new GraphCompiler<>(recipes);
        for (var mode : GraphMode.values()) {
            var compiler = new GraphCompiler<>(recipes, mode, full.verificationCatalog());
            var initial = Map.of("raw", BigInteger.valueOf(3));
            var original = new GraphPlan<>("T", 1, false, new PlanStep.Batch("old", 1),
                    Map.of("old", expensive), initial, Map.of(), initial, GraphPlan.Result.MISSING_INPUT, 0, 0);
            var budget = new PlanningBudget(0, 131072, 16L << 20, () -> false, System::nanoTime);
            try (var search = new RefillSourceSearch<>(compiler, original, Map.of(), Set.of(), Set.of(), true, false, budget)) {
                while (!search.step()) {}
                var plan = search.result();
                check(plan.missingExact().equals(Map.of("raw", BigInteger.ONE)), mode + " preserved unnecessary old production: " + plan.missingExact());
                check(SequenceSummary.of(plan.steps(), plan.recipes()).delta("T").equals(BigInteger.ONE), "Replacement exceeds requested production");
                PlanVerifier.verify(new GraphPlan<>(plan.target(), plan.amount(), plan.preserveSeeds(), plan.steps(),
                        plan.recipes(), plan.initialExact(), plan.seeds(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0));
            }
            check(budget.reservedBytes() == 0, "Source refinement memory leaked");
        }
        complementaryRoutes();
        System.out.println("RefillProductionCreditTest PASS");
    }
}
