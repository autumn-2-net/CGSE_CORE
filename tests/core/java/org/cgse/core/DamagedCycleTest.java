// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Mutations of known executable cycles, including repairs on the same compiler. */
public final class DamagedCycleTest {
    private static int checks;
    private static final BigInteger ZERO = BigInteger.ZERO, ONE = BigInteger.ONE;

    public static void main(String[] args) {
        for (int size : new int[] {3, 8, 32, 96, 256}) for (boolean reverse : List.of(false, true)) {
            var recipes = ring(size);
            if (reverse) Collections.reverse(recipes);
            var compiler = new GraphCompiler<>(recipes);
            var seeds = Map.of("k0", 1L);
            for (long fuel : new long[] {8, 7, 0, 8}) {
                var stock = Map.of("k0", 1L, "fuel", fuel);
                var p = solve(compiler, stock, seeds);
                verify(p, stock);
                check(p.missingExact().equals(fuel == 8 ? Map.of() : Map.of("fuel", BigInteger.valueOf(8 - fuel))),
                        "Changed fuel must reach its conservation shortage: n=" + size + "; fuel=" + fuel + "; " + p.missingExact());
                check(p.feasible() == (fuel == 8), "Warm structural reuse ignored changed stock");
            }
            var noSeed = Map.of("fuel", 8L);
            var p = solve(compiler, noSeed, seeds);
            verify(p, noSeed);
            check(!p.feasible() && p.missingExact().keySet().stream().anyMatch(k -> k.startsWith("k")), "Unseeded cycle invented a startup");
            var broken = new ArrayList<>(recipes);
            String removed = "turn" + size / 2;
            broken.removeIf(r -> r.id().equals(removed));
            var missingLink = "k" + (size / 2 + 1);
            p = solve(new GraphCompiler<>(broken), Map.of("k0", 1L, "fuel", 8L), seeds);
            verify(p, Map.of("k0", 1L, "fuel", 8L));
            check(p.missingExact().containsKey(missingLink), "Deleted producer was silently reused");
            check(p.missingExact().keySet().stream().allMatch(k -> broken.stream().noneMatch(r -> r.outputs().containsKey(k))),
                    "Broken acyclic chain stopped at a producible intermediate: " + p.missingExact());
            p = solve(compiler, Map.of("k0", 1L, "fuel", 8L), seeds);
            check(p.feasible(), "Repair did not restore the original executable cycle");
        }
        System.out.println("DamagedCycleTest PASS: checks=" + checks);
    }

    private static ArrayList<GraphRecipe<String>> ring(int size) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < size - 1; i++) recipes.add(r("turn" + i, Map.of("k" + i, 1L), Map.of("k" + (i + 1), 1L)));
        recipes.add(r("grow", Map.of("k" + (size - 1), 1L, "fuel", 1L), Map.of("k0", 2L)));
        recipes.add(r("finish", Map.of("k0", 1L), Map.of("G", 1L)));
        return recipes;
    }
    private static GraphPlan<String> solve(GraphCompiler<String> compiler, Map<String, Long> stock, Map<String, Long> seeds) {
        var budget = new PlanningBudget(0, 2_000_000, 32L << 20, () -> false, System::nanoTime);
        GraphPlan<String> result;
        var work = new GraphPlanningWork<>(compiler, "G", 8, stock, Set.of(), seeds, true, true, budget).catalysts(CatalystPolicy.MINIMAL);
        try {
            while (!work.step()) {}
            result = work.result();
        } finally { work.close(); }
        check(budget.reservedBytes() == 0, "Damaged search leaked memory");
        check(result.feasible() || !result.missingExact().isEmpty(), "Damaged cycle lost its whole preview: " + result.result());
        return result;
    }
    private static void verify(GraphPlan<String> p, Map<String, Long> stock) {
        // Independent exact word fold: every prefix is funded, not just its
        // aggregate balance. Explicit final seeds remain part of the goal.
        var required = PlanVerifier.initialExact(p.steps(), p.recipes(), p.target(), p.amount(), p.seeds());
        for (var e : required.entrySet()) {
            check(p.initialExact().getOrDefault(e.getKey(), ZERO).compareTo(e.getValue()) >= 0, "Unfunded prefix");
            var have = BigInteger.valueOf(stock.getOrDefault(e.getKey(), 0L));
            check(p.initialExact().get(e.getKey()).subtract(have).max(ZERO).equals(p.missingExact().getOrDefault(e.getKey(), ZERO)), "Incorrect exact supply bill");
        }
        check(p.seeds().getOrDefault("k0", 0L) >= 1, "Lost seed restoration");
    }
    private static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
