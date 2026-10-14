// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Executable batch caps against the original arbitrary-precision prefix equations. */
public final class AllocationMaximumTest {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var maximum = AllocationSearch.class.getDeclaredMethod("maximum", int.class);
        var heldField = AllocationSearch.class.getDeclaredField("held");
        var summariesField = AllocationSearch.class.getDeclaredField("summaries");
        var goalsField = AllocationSearch.class.getDeclaredField("goals");
        for (var member : List.of(maximum, heldField, summariesField, goalsField)) member.setAccessible(true);
        var random = new Random(2026101406L);
        var endpoints = new BigInteger[]{BigInteger.ZERO, BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(127),
                ExactAmounts.LONG_MAX.subtract(BigInteger.ONE), ExactAmounts.LONG_MAX,
                BigInteger.ONE.shiftLeft(63), BigInteger.ONE.shiftLeft(65), BigInteger.ONE.shiftLeft(150)};
        for (int sample = 0; sample < 8192; sample++) {
            String target = "output";
            boolean force = sample % 2 == 0;
            long amount = sample % 3 == 0 ? Long.MAX_VALUE : 1 + random.nextInt(1000);
            var stock = Map.of(target, sample % 4 == 0 ? Long.MAX_VALUE : (long) random.nextInt(10000));
            var seeds = Map.of(target, sample % 7 == 0 ? Long.MAX_VALUE : 0L);
            var budget = new PlanningBudget(0, 10000, 16L << 20, () -> false, System::nanoTime);
            var search = new AllocationSearch<>(new GraphCompiler<String>(List.of()), target, amount, stock,
                    Set.of(), seeds, false, force, Set.of(), budget, 0);
            var held = (Map<String, BigInteger>) heldField.get(search);
            var summaries = (List<SequenceSummary<String>>) summariesField.get(search);
            var goals = (Map<String, BigInteger>) goalsField.get(search);
            long in = sample % 9 == 0 ? Long.MAX_VALUE : 1 + random.nextInt(100);
            long out = sample % 11 == 0 ? Long.MAX_VALUE : 1 + random.nextInt(100);
            var slots = List.of(new GraphRecipe.Slot<>("input", in), new GraphRecipe.Slot<>("catalyst", 1));
            var outputs = new LinkedHashMap<String, Long>();
            outputs.put("output", out);
            outputs.put("input", sample % 3 == 0 ? in : sample % 5 == 0 ? Long.MAX_VALUE : 0L);
            outputs.put("catalyst", 1L);
            if (sample % 13 == 0) { outputs.remove("output"); outputs.put("input", in); }
            var recipe = new GraphRecipe<>("r", "r", slots, outputs);
            var summary = SequenceSummary.recipe(recipe);
            summaries.add(summary);
            for (String key : List.of("input", "output", "catalyst")) held.put(key, endpoints[random.nextInt(endpoints.length)]);
            // Include exact funding boundaries, self-returning inputs and stock
            // above long range after earlier hypothetical byproduct production.
            if (sample % 3 == 0) held.put("input", BigInteger.valueOf(in));
            if (sample % 2 == 0) held.put("catalyst", BigInteger.ONE);
            var keys = new ArrayList<>(summary.keys());
            check(keys.equals(new ArrayList<>(summary.delta().keySet())), "Recipe support ordering differs");
            long before = budget.nodes();
            long actual = (long) maximum.invoke(search, 0);
            var expected = reference(summary, held, goals, stock, target, amount, force);
            check(actual == expected[0], "Batch cap differs at sample " + sample + ": " + actual + " != " + expected[0]);
            check(budget.nodes() - before == expected[1], "Resource scan work changed");
            check(budget.reservedBytes() == 0, "Arithmetic retained workspace");
        }
        System.out.println("Allocation maxima: 8192 exact batch/scan comparisons, including wide inventory and forced goals passed");
    }

    private static long[] reference(SequenceSummary<String> summary, Map<String, BigInteger> held, Map<String, BigInteger> goals,
                                    Map<String, Long> stock, String target, long amount, boolean force) {
        var bound = ExactAmounts.LONG_MAX;
        var useful = BigInteger.ZERO;
        int visited = 0;
        for (String key : summary.keys()) {
            visited++;
            var current = held.getOrDefault(key, BigInteger.ZERO);
            if (current.compareTo(summary.required(key)) < 0) return new long[]{0, visited};
            var delta = summary.delta(key);
            if (delta.signum() < 0) bound = bound.min(current.subtract(summary.required(key)).divide(delta.negate()).add(BigInteger.ONE));
            else if (delta.signum() > 0) {
                var goal = goals.getOrDefault(key, BigInteger.ZERO);
                if (force && key.equals(target)) goal = goal.max(BigInteger.valueOf(stock.getOrDefault(target, 0L)).add(BigInteger.valueOf(amount)));
                var difference = goal.subtract(current);
                if (difference.signum() > 0) useful = useful.max(difference.subtract(BigInteger.ONE).divide(delta).add(BigInteger.ONE));
            }
        }
        if (summary.delta().values().stream().allMatch(v -> v.signum() == 0)) return new long[]{0, visited};
        return new long[]{bound.min(useful.max(BigInteger.ONE)).longValueExact(), visited};
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
