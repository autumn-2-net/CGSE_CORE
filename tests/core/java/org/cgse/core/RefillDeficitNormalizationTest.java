// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Force-production stock loans remain usable when importing canonical refill alternatives. */
public final class RefillDeficitNormalizationTest {
    private static void check(boolean okay, String why) { if (!okay) throw new AssertionError(why); }
    private static void bill(GraphPlan<String> plan, Map<String, Long> stock, boolean ignoreTarget) {
        var expected = new LinkedHashMap<String, BigInteger>();
        plan.initialExact().forEach((key, amount) -> {
            BigInteger available = ignoreTarget && key.equals(plan.target()) ? BigInteger.ZERO :
                    BigInteger.valueOf(stock.getOrDefault(key, 0L));
            BigInteger gap = amount.subtract(available);
            if (gap.signum() > 0) expected.put(key, gap);
        });
        check(expected.equals(plan.missingExact()), "Incorrect shortage: " + plan.missingExact() + " expected " + expected);
        PlanVerifier.initialExact(plan.steps(), plan.recipes(), plan.target(), plan.amount(), plan.seeds())
                .forEach((key, need) -> check(plan.initialExact().getOrDefault(key, BigInteger.ZERO).compareTo(need) >= 0,
                        "Unfunded physical prefix/final reserve: " + key));
        check(SequenceSummary.of(plan.steps(), plan.recipes()).delta(plan.target()).compareTo(BigInteger.valueOf(plan.amount())) >= 0,
                "Target stock loan replaced manufacturing");
        for (var alternative : plan.alternatives()) bill(alternative, stock, ignoreTarget);
    }
    public static void main(String[] args) {
        var random = new Random(2992026);
        for (int trial = 0; trial <= 296; trial++) {
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
            if (trial != 296) continue;
            var budget = new PlanningBudget(0, 131072, 16L << 20, () -> false, System::nanoTime);
            GraphPlan<String> plan;
            var work = new GraphPlanningWork<>(new GraphCompiler<>(recipes), "k0", amount, stock, true, true, budget);
            try {
                while (!work.step()) {}
                plan = work.result(); bill(plan, stock, false);
                var loan = plan.alternatives().stream().filter(p -> p.patternTimesExact().containsKey("r0")).findFirst().orElseThrow();
                check(loan.missingExact().get("k0").equals(BigInteger.TWO), "Valid target-stock loan was lost");
                check(loan.netProductionVerified(), "Deficit annotation lost production certificate");
                var labelled = loan.withMissingSources(GraphPlan.MissingSource.CYCLE_BOUNDARY, Map.of())
                        .withRefillCycles(List.of(List.of(recipes.get(0))));
                var frontier = labelled.withAlternatives(List.of(plan.withAlternatives(List.of()), labelled.withAlternatives(List.of())));
                for (boolean ignore : List.of(false, true)) {
                    var normalizedBudget = new PlanningBudget(0, 10000, 1L << 20, () -> false, System::nanoTime);
                    try (var normalizer = new RefillDeficitNormalization<>(frontier, stock, Set.of(), ignore, normalizedBudget)) {
                        while (!normalizer.step()) {}
                        var normalized = normalizer.result(); bill(normalized, stock, ignore);
                        check(normalized.alternatives().size() == 2, "Imported alternatives lost");
                        check(normalized.steps() == loan.steps() && normalized.initialExact() == loan.initialExact(), "Physical witness changed");
                        check(normalized.netProductionVerified(), "Production certificate lost");
                        check(normalized.missingBoundary("k0").source() == GraphPlan.MissingSource.CYCLE_BOUNDARY &&
                                normalized.alternatives().get(1).missingBoundary("k0").source() == GraphPlan.MissingSource.CYCLE_BOUNDARY,
                                "Existing alternative source labels lost");
                        check(normalized.refillCycles().equals(labelled.refillCycles()), "Display cycles lost");
                    }
                    check(normalizedBudget.reservedBytes() == 0, "Normalization memory leaked");
                    check(normalizedBudget.nodes() > 0, "Normalization did not charge real work");
                }
                var limitedBudget = new PlanningBudget(0, 10000, 900, () -> false, System::nanoTime);
                try (var normalizer = new RefillDeficitNormalization<>(frontier, stock, Set.of(), false, limitedBudget)) {
                    try { while (!normalizer.step()) {} throw new AssertionError("Memory refusal not exercised"); }
                    catch (PlanningBudget.Exhausted expected) {
                        check(expected.limit() == PlanningBudget.Limit.MEMORY_LIMIT, "Wrong optional-pass limit");
                        check(normalizer.result() == null, "Incomplete frontier was exported");
                        bill(frontier, stock, false);
                    }
                }
                check(limitedBudget.reservedBytes() == 0, "Partial normalization memory leaked");
            } finally { work.close(); }
            check(budget.reservedBytes() == 0, "Canonical import leaked memory");
        }
        System.out.println("RefillDeficitNormalizationTest PASS");
    }
}
