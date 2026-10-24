// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;

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
            check(p.missingExact().equals(Map.of("raw", BigInteger.ONE.shiftLeft(depth).subtract(BigInteger.ONE))),
                    "Shared demand did not finish at its base deficit: depth=" + depth + "; " + p.missingExact());
            check(p.seeds().getOrDefault("k" + depth, 0L) == 1, "Self-amplification did not retain startup");
            var summary = SequenceSummary.of(p.steps(), p.recipes());
            check(p.initialExact().get("k" + depth).add(summary.delta("k" + depth)).equals(BigInteger.ONE),
                    "A fully funded fallback still consumes its last cycle seed");
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
        selfAmplification();
        startupLifecycle();
        System.out.println("FallbackCycleBoundaryTest PASS");
    }
    private static void selfAmplification() {
        for (boolean reverse : List.of(false, true)) for (boolean preserve : List.of(false, true)) {
            var recipes = new ArrayList<>(List.of(r("grow", Map.of("A", 2L, "ore", 3L), Map.of("A", 5L)),
                    r("finish", Map.of("A", 8L), Map.of("G", 1L))));
            if (reverse) Collections.reverse(recipes);
            var budget = new PlanningBudget(0, 131_072, 16L << 20, () -> false, System::nanoTime);
            var p = GraphFallback.plan(new GraphCompiler<>(recipes), "G", 1, Map.of("A", 2L, "ore", 6L),
                    Set.of(), Map.of(), preserve, true, budget);
            var summary = SequenceSummary.of(p.steps(), p.recipes());
            check(p.missingExact().equals(preserve ? Map.of("ore", BigInteger.valueOf(3)) : Map.of()),
                    "Self-amplification refill did not reach raw supply: " + p.missingExact());
            check(p.initialExact().get("A").add(summary.delta("A")).equals(preserve ? BigInteger.valueOf(3) : BigInteger.ZERO),
                    "Incorrect retained startup after uneven growth");
            check(!budget.diagnostics().contains("source_search@") && budget.reservedBytes() == 0,
                    "Seed repair entered a cyclic portfolio or retained workspace");
        }
        // A finite per-push configuration has different batching semantics.
        // The ordinary seed repair must leave that boundary untouched.
        var grow = new GraphRecipe<>("grow", "grow", List.of(new GraphRecipe.Slot<>("A", 1),
                new GraphRecipe.Slot<>("ore", 1), new GraphRecipe.Slot<>("config", 1, 0, true, false)), Map.of("A", 2L));
        var budget = new PlanningBudget(0, 131_072, 16L << 20, () -> false, System::nanoTime);
        var p = GraphFallback.plan(new GraphCompiler<>(List.of(grow, r("finish", Map.of("A", 3L), Map.of("G", 1L)))),
                "G", 1, Map.of("A", 1L, "ore", 2L, "config", 2L), Set.of(), Map.of(), true, true, budget);
        check(p.missingExact().getOrDefault("A", BigInteger.ZERO).signum() > 0,
                "Finite configuration was treated as an ordinary reusable source");
        check(budget.reservedBytes() == 0, "Declined configured seed repair leaked");
    }
    private static void startupLifecycle() {
        var grow = r("grow", Map.of("A", 1L, "ore", 1L), Map.of("A", 2L));
        var finish = r("finish", Map.of("A", 3L), Map.of("G", 1L));
        var compiler = new GraphCompiler<>(List.of(grow, finish));
        var stock = Map.of("A", 1L);
        var word = new PlanStep.Sequence(List.of(new PlanStep.Batch("grow", 2), new PlanStep.Batch("finish", 1)));
        // A terminal-only bill can still contain a seed-depleting word. It
        // must be checked even when dependency expansion has nothing to add.
        for (boolean preserve : List.of(false, true)) {
            var original = new GraphPlan<>("G", 1, preserve, word, Map.of("grow", grow, "finish", finish),
                    Map.of("A", 1L, "ore", 2L), Map.of(), Map.of("ore", 2L), GraphPlan.Result.MISSING_INPUT, 0, 0);
            var budget = new PlanningBudget(0, 131_072, 16L << 20, () -> false, System::nanoTime);
            try (var work = new MissingMaterialExpansion<>(compiler, original, stock, Set.of(), Set.of(), true, true, budget).ordinaryFallback()) {
                while (!work.step()) {}
                var p = work.result();
                check(p.missingExact().equals(Map.of("ore", BigInteger.valueOf(preserve ? 3 : 2))), "Terminal fast path skipped seed repair");
                var summary = SequenceSummary.of(p.steps(), p.recipes());
                check(p.initialExact().get("A").add(summary.delta("A")).equals(preserve ? BigInteger.ONE : BigInteger.ZERO),
                        "Terminal fast path retained the wrong final seed");
            }
            check(budget.reservedBytes() == 0, "Terminal startup verification leaked");
        }
        for (long limit : new long[]{16, 64, 256, 1024, 2048, 4096, 8192}) {
            var budget = new PlanningBudget(0, limit, 16L << 20, () -> false, System::nanoTime);
            try (var work = MissingMaterialExpansion.fromRequest(compiler, "G", 1, stock, Set.of(), Map.of(), Set.of(), true, true, budget).ordinaryFallback()) {
                try { while (!work.step()) {} }
                catch (PlanningBudget.Exhausted expected) { /* A partial unsafe word must not be published. */ }
                var p = work.result();
                if (p != null) {
                    var summary = SequenceSummary.of(p.steps(), p.recipes());
                    check(p.initialExact().get("A").add(summary.delta("A")).compareTo(BigInteger.ONE) >= 0,
                            "Work limit published an unprotected startup");
                }
            }
            check(budget.reservedBytes() == 0, "Limited startup repair leaked");
        }
        var budget = new PlanningBudget(0, 131_072, 16L << 20, () -> false, System::nanoTime);
        boolean cancelled = false, sawBoundary = false;
        try (var work = MissingMaterialExpansion.fromRequest(compiler, "G", 1, stock, Set.of(), Map.of(), Set.of(), true, true, budget).ordinaryFallback()) {
            try {
                while (!work.step()) if (work.result() != null) { sawBoundary = true; budget.cancel(); }
            } catch (CancellationException expected) { cancelled = true; }
        }
        check(sawBoundary && cancelled && budget.reservedBytes() == 0, "Repair cancellation was swallowed or leaked");
    }
    private static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
