// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Ordinary fallback must finish its bill before spending on optional search. */
public final class FallbackCycleBoundaryTest {
    public static void main(String[] args) {
        for (int depth : new int[] {40, 100, 200, 300}) {
            var recipes = new ArrayList<GraphRecipe<String>>();
            for (int i = 0; i < depth; i++) {
                recipes.add(r("join" + i, Map.of("a" + i, 1L, "b" + i, 1L), Map.of("k" + i, 1L)));
                recipes.add(r("left" + i, Map.of("k" + (i + 1), 1L), Map.of("a" + i, 1L)));
                recipes.add(r("right" + i, Map.of("k" + (i + 1), 1L), Map.of("b" + i, 1L)));
            }
            recipes.add(r("base", Map.of("k" + depth, 1L, "raw", 1L), Map.of("k" + depth, 2L)));
            var budget = new PlanningBudget(0, 131_072, 16L << 20, () -> false, System::nanoTime);
            GraphPlan<String> p;
            try { p = GraphFallback.plan(new GraphCompiler<>(recipes), "k0", 1, Map.of("k" + depth, 1L, "raw", 1L),
                    Set.of(), Map.of(), true, true, budget); }
            catch (PlanningBudget.Exhausted e) { throw new AssertionError("depth=" + depth + "; " + budget.diagnostics(), e); }
            check(p.missingExact().equals(Map.of("raw", BigInteger.ONE.shiftLeft(depth).subtract(BigInteger.TWO))),
                    "Shared demand did not finish at its base deficit: depth=" + depth + "; " + p.missingExact());
            var required = PlanVerifier.initialExact(p.steps(), p.recipes(), "k0", 1, p.seeds());
            required.forEach((k, n) -> check(p.initialExact().getOrDefault(k, BigInteger.ZERO).compareTo(n) >= 0, "Unfunded prefix"));
            check(!budget.diagnostics().contains("source_search@"), "Ordinary fallback started a native cycle solve");
            check(budget.reservedBytes() == 0, "Fallback workspace leaked");
        }
        // This loop needs alternating amplification. Ordinary fallback may
        // expose its cycle boundary; native main planning owns that search.
        var recipes = List.of(r("ab", Map.of("A", 1L, "ore", 1L), Map.of("B", 1L)),
                r("ba", Map.of("B", 1L), Map.of("A", 2L)), r("finish", Map.of("A", 10L), Map.of("g", 1L)));
        var budget = new PlanningBudget(0, 1_000_000, 16L << 20, () -> false, System::nanoTime);
        var p = GraphFallback.plan(new GraphCompiler<>(recipes), "g", 1, Map.of("A", 1L), Set.of(), Map.of(), false, true, budget);
        check(!p.feasible() && !p.missingExact().isEmpty(), "An unfunded loop became feasible");
        check(!budget.diagnostics().contains("source_search@"), "Fallback recursively invoked primary search");
        check(budget.nodes() < 131_072 && budget.reservedBytes() == 0, "Ordinary scope grew into a cyclic portfolio");
        System.out.println("FallbackCycleBoundaryTest PASS");
    }
    private static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
