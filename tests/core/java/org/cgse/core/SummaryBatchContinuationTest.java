// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CancellationException;

/** Port folding must preserve literal prefixes across batch/loop boundaries. */
public final class SummaryBatchContinuationTest {
    private static int cases, checks;

    public static void main(String[] args) {
        var random = new Random(0x181018fL);
        for (int sample = 0; sample < 600; sample++) {
            var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
            var steps = new ArrayList<PlanStep>();
            for (int i = 0; i < 4 + random.nextInt(70); i++) {
                var in = new LinkedHashMap<String, Long>(); var out = new LinkedHashMap<String, Long>();
                for (int k = 0; k < 5; k++) {
                    if (random.nextBoolean()) in.put("k" + k, 1L + random.nextInt(4));
                    if (random.nextBoolean()) out.put("k" + k, 1L + random.nextInt(4));
                }
                if (out.isEmpty()) out.put("k5", 1L);
                String id = "r" + i; recipes.put(id, recipe(id, in, out));
                PlanStep step = new PlanStep.Batch(id, random.nextInt(4));
                if (i % 7 == 0) step = new PlanStep.Repeat(step, random.nextInt(4));
                if (i % 11 == 0) step = new PlanStep.Sequence(List.of(step, new PlanStep.Sequence(List.of())));
                steps.add(step);
            }
            verify(new PlanStep.Sequence(steps), recipes);
        }
        for (int count : new int[]{1, 7, 8, 9, 16, 31, 32, 33, 63, 257, 1024}) {
            var recipes = chain(count); var steps = new ArrayList<PlanStep>();
            recipes.keySet().forEach(id -> steps.add(new PlanStep.Batch(id, 3)));
            var word = new PlanStep.Sequence(steps);
            verify(word, recipes);
            verify(new PlanStep.Sequence(List.of(word, new PlanStep.Repeat(word, 2), word)), recipes);
        }
        var large = Map.of("a", recipe("a", Map.of("raw", Long.MAX_VALUE), Map.of("mid", Long.MAX_VALUE)),
                "b", recipe("b", Map.of("mid", Long.MAX_VALUE), Map.of("product", Long.MAX_VALUE)));
        var huge = new PlanStep.Sequence(List.of(new PlanStep.Batch("a", Long.MAX_VALUE), new PlanStep.Batch("b", Long.MAX_VALUE)));
        var exact = compute(huge, large, budget()); var squared = BigInteger.valueOf(Long.MAX_VALUE).pow(2);
        check(exact.required("raw").equals(squared) && exact.delta("product").equals(squared) && exact.peak("mid").equals(squared), "Narrowed wide prefix");
        check(exact.required("mid").signum() == 0 && exact.delta("mid").signum() == 0, "Invented intermediate shortage");
        identityWrappers(); limits();
        System.out.println("SummaryBatchContinuationTest: cases=" + cases + "; assertions=" + checks);
    }

    private static void verify(PlanStep word, Map<String, GraphRecipe<String>> recipes) {
        var state = new LinkedHashMap<String, BigInteger>(); var low = new LinkedHashMap<String, BigInteger>();
        var high = new LinkedHashMap<String, BigInteger>();
        literal(word, recipes, state, low, high);
        var budget = budget(); var summary = compute(word, recipes, budget);
        var keys = new LinkedHashSet<>(state.keySet()); keys.addAll(summary.keys());
        for (String key : keys) {
            check(summary.delta(key).equals(state.getOrDefault(key, BigInteger.ZERO)), "Changed final inventory");
            check(summary.required(key).equals(low.getOrDefault(key, BigInteger.ZERO).negate().max(BigInteger.ZERO)), "Changed prefix need");
            if (low.getOrDefault(key, BigInteger.ZERO).signum() < 0)
                check(summary.delta().containsKey(key), "Prefix input disappeared from verification keys, including zero-net tools");
            check(summary.peak(key).equals(high.getOrDefault(key, BigInteger.ZERO).max(BigInteger.ZERO)), "Changed prefix peak");
        }
        check(budget.reservedBytes() == 0, "Summary workspace leaked"); cases++;
    }

    private static void literal(PlanStep step, Map<String, GraphRecipe<String>> recipes, Map<String, BigInteger> state,
                                Map<String, BigInteger> low, Map<String, BigInteger> high) {
        if (step instanceof PlanStep.Sequence s) { for (var child : s.children()) literal(child, recipes, state, low, high); }
        else if (step instanceof PlanStep.Repeat r) { for (long i = 0; i < r.times(); i++) literal(r.body(), recipes, state, low, high); }
        else {
            var batch = (PlanStep.Batch) step; var recipe = recipes.get(batch.recipe());
            for (long i = 0; i < batch.runs(); i++) {
                recipe.inputs().forEach((key, count) -> low.merge(key, state.merge(key, BigInteger.valueOf(count).negate(), BigInteger::add), BigInteger::min));
                recipe.outputs().forEach((key, count) -> high.merge(key, state.merge(key, BigInteger.valueOf(count), BigInteger::add), BigInteger::max));
            }
        }
    }

    private static SequenceSummary<String> compute(PlanStep word, Map<String, GraphRecipe<String>> recipes, PlanningBudget budget) {
        try (var computation = new SummaryComputation<>(word, recipes, budget)) { while (!computation.step()) {} return computation.result(); }
    }

    private static void identityWrappers() {
        var recipes = chain(96);
        PlanStep body = new PlanStep.Sequence(recipes.keySet().stream().map(id -> (PlanStep) new PlanStep.Batch(id, 1)).toList());
        var baseBudget = budget(); compute(body, recipes, baseBudget);
        for (int depth : new int[]{1, 2, 17, 257, 8192}) {
            PlanStep wrapped = body;
            for (int i = 0; i < depth; i++) wrapped = i % 2 == 0 ? new PlanStep.Repeat(wrapped, 1) : new PlanStep.Sequence(List.of(wrapped));
            var b = budget(); var summary = compute(wrapped, recipes, b);
            check(b.nodes() <= baseBudget.nodes() + depth, "Recomputed material vectors at identity wrappers");
            check(b.peakBytes() == baseBudget.peakBytes() && b.reservedBytes() == 0, "Identity-only fold retained duplicate vectors");
            check(summary.required("k0").equals(BigInteger.ONE) && summary.delta("k96").equals(BigInteger.ONE), "Changed wrapped chain");
            // Memoization still refers to the original immutable subtree after
            // peeling, including reuse below a nontrivial repeat.
            var repeated = new PlanStep.Sequence(List.of(wrapped, wrapped, new PlanStep.Repeat(wrapped, 3)));
            b = budget(); summary = compute(repeated, recipes, b);
            check(summary.required("k0").equals(BigInteger.valueOf(5)) && summary.delta("k96").equals(BigInteger.valueOf(5)), "Changed reused identity word");
            for (int i = 1; i < 96; i++) check(summary.required("k" + i).signum() == 0 && summary.delta("k" + i).signum() == 0 && summary.peak("k" + i).equals(BigInteger.ONE), "Lost transient prefix key");
            check(b.nodes() < depth + 5000 && b.reservedBytes() == 0, "Refolded deep shared subtree/leaked");
        }
        var returned = Map.of("tool", recipe("tool", Map.of("tool", 1L, "raw", 2L), Map.of("tool", 1L, "product", 1L)));
        for (int runs = 0; runs < 4; runs++) for (int shape = 0; shape < 3; shape++) {
            PlanStep word = new PlanStep.Batch("tool", runs);
            for (int i = 0; i < 8; i++) word = i % 2 == 0 ? new PlanStep.Repeat(word, 1) : new PlanStep.Sequence(List.of(word));
            if (shape > 0) word = new PlanStep.Repeat(word, shape == 1 ? 0 : 3);
            verify(word, returned); // independent literal execution, including zero and returned inputs
        }
        PlanStep deep = body;
        for (int i = 0; i < 10000; i++) deep = new PlanStep.Repeat(deep, 1);
        var b = budget();
        try (var work = new SummaryComputation<>(deep, recipes, b)) {
            for (int i = 0; i < 8; i++) { long before = b.nodes(); check(!work.step() && b.nodes() - before == 1, "Uncharged/unbounded wrapper traversal"); }
            b.cancel();
            try { work.step(); throw new AssertionError("Ignored wrapper cancellation"); } catch (CancellationException expected) { checks++; }
        }
        check(b.reservedBytes() == 0, "Cancelled wrapper fold leaked");
    }

    private static void limits() {
        var recipes = chain(2048); var word = new PlanStep.Sequence(recipes.keySet().stream().map(id -> (PlanStep) new PlanStep.Batch(id, 1)).toList());
        var budget = budget();
        try (var computation = new SummaryComputation<>(word, recipes, budget)) {
            for (int i = 0; i < 6; i++) {
                long start = budget.searchWork(); check(!computation.step(), "Unbounded continuation");
                check(budget.searchWork() - start <= 33, "One continuation exceeded 32 ports");
            }
            budget.cancel();
            try { computation.step(); throw new AssertionError("Ignored cancellation"); } catch (CancellationException expected) { checks++; }
        }
        check(budget.reservedBytes() == 0, "Cancelled fold leaked");
        var limited = new PlanningBudget(0, 16, 4L << 20, () -> false, System::nanoTime);
        try { compute(word, recipes, limited); throw new AssertionError("Uncharged arithmetic"); }
        catch (PlanningBudget.Exhausted expected) { check(expected.limit() == PlanningBudget.Limit.SEARCH_LIMIT, "Wrong limit"); }
        check(limited.reservedBytes() == 0, "Exhausted fold leaked");
    }

    private static Map<String, GraphRecipe<String>> chain(int count) {
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
        for (int i = 0; i < count; i++) recipes.put("r" + i, recipe("r" + i, Map.of("k" + i, 1L), Map.of("k" + (i + 1), 1L)));
        return recipes;
    }
    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 1_000_000, 8L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
