// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;

/** Optional retention and failed donor probes must leave the original elimination allowance intact. */
public final class ConservationKernelQuotaTest {
    private record Result(boolean blocked, long lastStart) {}

    public static void main(String[] args) throws Exception {
        var recipes = recipes();
        var original = solve(new GraphCompiler<>(recipes), "C", 4_000_000);
        check(original.blocked, "Missing exact A+C conservation proof");
        long threshold = 4 * (original.lastStart + 1);
        for (long extra : new long[]{0, 4, 16, 32, 64, 128}) {
            long units = threshold + extra;
            var same = new GraphCompiler<>(recipes);
            check(solve(same, "C", units).blocked, "Cold boundary is not conclusive");
            check(solve(same, "C", units).blocked, "Optional capture consumed the proof's allowance at " + units);
            var donor = new GraphCompiler<>(recipes);
            check(!solve(donor, "B", 4_000_000).blocked && !solve(donor, "B", 4_000_000).blocked, "B is already in stock");
            check(donor.conservationBasis("B", Set.of()) != null, "Donor was not captured");
            check(solve(donor, "C", units).blocked, "Failed donor consumed the proof's allowance at " + units);
            check(donor.cacheMetrics().caches().get("conservation_overlap").hits() > 0, "No donor exercised");
        }
        System.out.println("Conservation quota: six tight exact-proof boundaries preserve cold, repeated and failed-donor conclusions; threshold=" + threshold);
    }

    private static List<GraphRecipe<String>> recipes() {
        var rows = new ArrayList<GraphRecipe<String>>();
        var pair = new LinkedHashMap<String, Long>(); pair.put("B", 1L); pair.put("C", 1L);
        rows.add(new GraphRecipe<>("split", "split", List.of(new GraphRecipe.Slot<>("A0", 1)), pair));
        rows.add(new GraphRecipe<>("join", "join", List.of(new GraphRecipe.Slot<>("B", 1), new GraphRecipe.Slot<>("C", 1)), Map.of("A0", 1L)));
        int n = 32;
        for (int i = 0; i < n; i++) rows.add(new GraphRecipe<>("ring" + i, "ring" + i,
                List.of(new GraphRecipe.Slot<>("A" + i, 1)), Map.of("A" + ((i + 1) % n), 1L)));
        var random = new Random(2026101411L);
        for (int i = 0; i < n; i++) {
            var slots = new ArrayList<GraphRecipe.Slot<String>>(); var outputs = new LinkedHashMap<String, Long>();
            for (int j = 0; j < 4; j++) {
                slots.add(new GraphRecipe.Slot<>("A" + random.nextInt(n), 1));
                outputs.merge("A" + random.nextInt(n), 1L, Long::sum);
            }
            rows.add(new GraphRecipe<>("mix" + i, "mix" + i, slots, outputs));
        }
        return rows;
    }

    private static Result solve(GraphCompiler<String> compiler, String target, long units) throws Exception {
        var budget = new PlanningBudget(0, units, 64L << 20, () -> false, System::nanoTime);
        var work = ConservationAnalysis.class.getDeclaredField("eliminationWork"); work.setAccessible(true);
        try (var analysis = new ConservationAnalysis<>(compiler, target, 1, Map.of("B", 5L), Set.of(), Map.of(), Set.of(), budget)) {
            long before;
            do { before = work.getLong(analysis); } while (!analysis.step());
            return new Result(analysis.blocked(), before);
        } finally { check(budget.reservedBytes() == 0, "Boundary analysis leaked workspace"); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
