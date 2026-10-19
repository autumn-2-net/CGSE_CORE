// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Repeated dependencies must not consume the entire fallback on a doomed tree. */
public final class FallbackCompactRefillTest {
    private static int checks;
    private static final BigInteger ZERO = BigInteger.ZERO, ONE = BigInteger.ONE;

    public static void main(String[] args) {
        sharedDependencies(); finishingBound(); forceAndSeeds(); lifecycle();
        System.out.println("FallbackCompactRefillTest: assertions=" + checks);
    }

    private static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return MissingMaterialExpansionTest.recipe(id, in, out);
    }
    private static List<GraphRecipe<String>> diamond(int depth, boolean returns, boolean reverse) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < depth; i++) {
            var join = new LinkedHashMap<String, Long>();
            join.put("a" + i, 1L); join.put("b" + i, 1L);
            if (reverse) { join.clear(); join.put("b" + i, 1L); join.put("a" + i, 1L); }
            recipes.add(r("join" + i, join, Map.of("k" + i, 1L)));
            var in = new LinkedHashMap<String, Long>(); in.put("k" + (i + 1), 1L);
            var out = new LinkedHashMap<String, Long>(); out.put("a" + i, 1L);
            if (returns) { in.put("tool", 1L); out.put("tool", 1L); }
            recipes.add(r("a" + i, in, out));
            recipes.add(r("b" + i, Map.of("k" + (i + 1), 1L), Map.of("b" + i, 1L)));
        }
        recipes.add(r("base", Map.of("raw", 1L), Map.of("k" + depth, 1L)));
        if (reverse) Collections.reverse(recipes);
        return recipes;
    }

    private static void sharedDependencies() {
        int redirected = 0;
        for (int depth : new int[] {6, 12, 20}) for (boolean returns : List.of(false, true))
        for (boolean reverse : List.of(false, true)) for (long amount : new long[] {1, 7}) {
            var compiler = new GraphCompiler<>(diamond(depth, returns, reverse));
            var stock = Map.of("tool", 1L, "k0", 999L);
            var seeds = returns ? Map.of("tool", 1L) : Map.<String, Long>of();
            var b = budget(131072, 16L << 20);
            GraphPlan<String> p;
            try { p = GraphFallback.plan(compiler, "k0", amount, stock, Set.of(), seeds, true, true, b); }
            catch (PlanningBudget.Exhausted e) { throw new AssertionError("depth=" + depth + "; returns=" + returns + "; reverse=" + reverse + "; amount=" + amount + "; " + b.diagnostics(), e); }
            check(p.missingExact().equals(Map.of("raw", BigInteger.valueOf(amount).shiftLeft(depth))), "Shared tree did not reach its raw conservation bound: " + p.missingExact());
            check(p.initialExact().getOrDefault("k0", ZERO).signum() == 0, "Used preexisting forced target stock");
            check(p.patternTimesExact().get("join0").equals(BigInteger.valueOf(amount)), "Wrong target production");
            check(p.seeds().equals(seeds), "Lost restoration goals");
            verify(p, stock, Set.of(), depth <= 12);
            check(b.reservedBytes() == 0 && b.nodes() <= 131072, "Original request quota or memory ownership changed");
            if (b.diagnostics().contains("compact_goals")) redirected++;
        }
        check(redirected >= 8, "Shared fixtures did not exercise the replacement path");
        // Exact counts remain compact even when the repeated leaf demand is
        // larger than long; primitive firings above validate the same family.
        var b = budget(131072, 16L << 20);
        var p = GraphFallback.plan(new GraphCompiler<>(diamond(12, true, false)), "k0", Long.MAX_VALUE,
                Map.of("tool", 1L), Set.of(), Map.of("tool", 1L), true, true, b);
        check(p.missingExact().get("raw").equals(BigInteger.valueOf(Long.MAX_VALUE).shiftLeft(12)), "Truncated exact goal demand");
        verify(p, Map.of("tool", 1L), Set.of(), false);
        check(b.reservedBytes() == 0, "Large count workspace leaked");
    }

    /** Compare the admission bound with actual folds/count work, including
     * fused ordinary batches and nested counts. No timing estimate is a proof. */
    private static void finishingBound() {
        var random = new Random(202610192L);
        for (int trial = 0; trial < 180; trial++) {
            var recipes = new LinkedHashMap<String, GraphRecipe<String>>(); var steps = new ArrayList<PlanStep>(); long lower = 0;
            for (int i = 0; i < 1 + random.nextInt(12); i++) {
                var in = new LinkedHashMap<String, Long>(); var out = new LinkedHashMap<String, Long>();
                for (int j = 0; j < 1 + random.nextInt(32); j++) {
                    in.put("k" + j, 1L + random.nextInt(4));
                    out.put((trial % 2 == 0 ? "k" : "out") + j, 1L + random.nextInt(4));
                }
                var recipe = r("r" + i, in, out); recipes.put(recipe.id(), recipe);
                var runs = trial % 7 == 0 ? BigInteger.valueOf(Long.MAX_VALUE).pow(2).add(ONE) : BigInteger.valueOf(1 + random.nextInt(10));
                steps.add(PlanStep.batch(recipe.id(), runs)); lower += GraphFallback.unsharedFinishingWorkLowerBound(recipe);
            }
            var word = new PlanStep.Sequence(steps); var b = budget(1_000_000, 32L << 20);
            try (var fold = new SummaryComputation<>(word, recipes, b)) {
                // Expansion charges its own step before each child advance.
                while (true) { b.check(); if (fold.step()) break; }
                var initial = new LinkedHashMap<>(fold.result().required()); initial.put("G", ONE);
                var plan = new GraphPlan<>("G", 1, false, word, recipes, initial, Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
                try (var verification = new PlanVerification<>(plan, b)) { while (true) { b.check(); if (verification.step()) break; } }
            }
            check(b.nodes() >= lower, "Finishing bound overestimated actual mandatory work: " + lower + "/" + b.nodes());
            check(b.reservedBytes() == 0, "Bound oracle workspace leaked");
        }
    }

    private static void forceAndSeeds() {
        for (boolean force : List.of(false, true)) for (long finalTarget : new long[] {0, 1, 10}) {
            var grow = r("grow", Map.of("A", 1L, "raw", 1L, "tool", 1L), Map.of("A", 2L, "tool", 1L));
            var seeds = new LinkedHashMap<String, Long>(); seeds.put("tool", 1L); if (finalTarget > 0) seeds.put("A", finalTarget);
            var initial = Map.of("A", BigInteger.valueOf(10 + finalTarget), "tool", ONE);
            var base = new GraphPlan<>("A", 10, false, new PlanStep.Sequence(List.of()), Map.of(), initial, seeds, initial, GraphPlan.Result.MISSING_INPUT, 0, 0);
            var b = budget(100_000, 8L << 20);
            try (var expansion = MissingMaterialExpansion.fromGoals(new GraphCompiler<>(List.of(grow)), base, Map.of(), Set.of(), force, b)) {
                while (!expansion.step()) {}
                var p = expansion.result(); verify(p, Map.of(), Set.of(), true);
                if (force) check(p.patternTimesExact().get("grow").compareTo(BigInteger.TEN) >= 0, "Counted the supplied target seed as forced production");
                check(p.seeds().equals(seeds) && !p.preserveSeeds(), "Changed explicit seeds/preserve flag");
            }
            check(b.reservedBytes() == 0, "Goal credit workspace leaked");
        }
        // A recipe may use target stock to supply a seed; it must restore that
        // target use, as well as the new order, before a funded result is valid.
        var recipes = List.of(r("turn", Map.of("G", 1L), Map.of("seed", 1L)), r("make", Map.of("raw", 1L), Map.of("G", 1L)));
        var base = new GraphPlan<>("G", 2, false, new PlanStep.Sequence(List.of()), Map.of(), Map.of("G", BigInteger.TWO, "seed", ONE),
                Map.of("seed", 1L), Map.of("G", BigInteger.TWO, "seed", ONE), GraphPlan.Result.MISSING_INPUT, 0, 0);
        var b = budget(100_000, 8L << 20);
        try (var expansion = MissingMaterialExpansion.fromGoals(new GraphCompiler<>(recipes), base, Map.of("G", 100L, "raw", 3L), Set.of(), true, b)) {
            while (!expansion.step()) {} var p = expansion.result();
            check(p.feasible() && p.patternTimesExact().get("make").equals(BigInteger.valueOf(3)), "Lost goal credit across seed preparation");
            verify(p, Map.of("raw", 3L), Set.of(), true);
        }
        check(b.reservedBytes() == 0, "Seed preparation leaked");
    }

    private static void lifecycle() {
        var compiler = new GraphCompiler<>(diamond(12, true, false));
        for (long work : new long[] {1, 128, 1024, 8192, 16384, 32768, 65536, 131072, 262144, 524288}) {
            var b = budget(work, 16L << 20);
            try { var p = GraphFallback.plan(compiler, "k0", 1, Map.of("tool", 1L), Set.of(), Map.of(), false, true, b); verify(p, Map.of("tool", 1L), Set.of(), true); }
            catch (PlanningBudget.Exhausted expected) { check(expected.limit() == PlanningBudget.Limit.SEARCH_LIMIT, "Unexpected quota failure"); }
            check(b.reservedBytes() == 0, "Cutoff workspace leaked");
        }
        for (long memory : new long[] {128, 1024, 8192, 65536, 262144}) {
            var b = budget(131072, memory);
            try { var p = GraphFallback.plan(compiler, "k0", 1, Map.of(), Set.of(), Map.of(), false, true, b); verify(p, Map.of(), Set.of(), true); }
            catch (PlanningBudget.Exhausted expected) { check(expected.limit() == PlanningBudget.Limit.MEMORY_LIMIT || expected.limit() == PlanningBudget.Limit.SEARCH_LIMIT, "Wrong memory exit"); }
            check(b.reservedBytes() == 0, "Memory cutoff leaked");
        }
        for (long stop : new long[] {0, 5000, 50000, 90000, 110000}) {
            PlanningBudget[] ref = new PlanningBudget[1]; var cancel = new AtomicBoolean();
            var b = new PlanningBudget(0, 131072, 16L << 20, () -> cancel.get() || ref[0].nodes() >= stop, System::nanoTime); ref[0] = b;
            try { GraphFallback.plan(compiler, "k0", 1, Map.of(), Set.of(), Map.of(), false, true, b); }
            catch (CancellationException expected) { checks++; }
            check(b.reservedBytes() == 0, "Cancelled goal refill leaked");
        }
    }

    private static void verify(GraphPlan<String> p, Map<String, Long> stock, Set<String> external, boolean literal) {
        var inventory = new HashMap<>(p.initialExact());
        for (var e : inventory.entrySet()) check(external.contains(e.getKey()) || e.getValue().compareTo(BigInteger.valueOf(stock.getOrDefault(e.getKey(), 0L)).add(p.missingExact().getOrDefault(e.getKey(), ZERO))) <= 0, "Unfunded initial supply");
        if (literal) {
            var todo = new ArrayDeque<PlanStep>(); todo.push(p.steps()); int firings = 0;
            while (!todo.isEmpty()) {
                var step = todo.pop();
                if (step instanceof PlanStep.Sequence s) for (int i = s.children().size() - 1; i >= 0; i--) todo.push(s.children().get(i));
                else if (step instanceof PlanStep.Repeat r) { check(r.times() < 100000, "Literal repeat too large"); for (int i = 0; i < r.times(); i++) todo.push(r.body()); }
                else {
                    var batch = (PlanStep.Batch) step; var recipe = p.recipes().get(batch.recipe());
                    for (long i = 0; i < batch.runs(); i++) {
                        check(++firings < 1_000_000, "Literal oracle size");
                        for (var in : recipe.inputs().entrySet()) {
                            var left = inventory.getOrDefault(in.getKey(), ZERO).subtract(BigInteger.valueOf(in.getValue()));
                            check(left.signum() >= 0, "Unfunded primitive firing " + batch.recipe()); inventory.put(in.getKey(), left);
                        }
                        recipe.outputs().forEach((k, n) -> inventory.merge(k, BigInteger.valueOf(n), BigInteger::add));
                    }
                }
            }
            check(inventory.getOrDefault(p.target(), ZERO).compareTo(BigInteger.valueOf(p.amount())) >= 0, "Unfulfilled target");
            inventory.merge(p.target(), BigInteger.valueOf(p.amount()).negate(), BigInteger::add);
            p.seeds().forEach((k, n) -> check(inventory.getOrDefault(k, ZERO).compareTo(BigInteger.valueOf(n)) >= 0, "Unfulfilled seed"));
        } else {
            var required = PlanVerifier.initialExact(p.steps(), p.recipes(), p.target(), p.amount(), p.seeds());
            required.forEach((k, n) -> check(p.initialExact().getOrDefault(k, ZERO).compareTo(n) >= 0, "Invalid compact exact witness"));
        }
    }
    private static PlanningBudget budget(long work, long bytes) { return new PlanningBudget(0, work, bytes, () -> false, System::nanoTime); }
    private static void check(boolean condition, String why) { checks++; if (!condition) throw new AssertionError(why); }
}
