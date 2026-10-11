// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Exact promotion, immutable prefix snapshots and inventory-independent local scheduling. */
public final class ExecutionArithmeticTest {
    public static void main(String[] args) throws Exception {
        summaries();
        ordinaryPrefixes();
        dotProducts();
        localInventory();
        System.out.println("Execution arithmetic: wide prefixes, promotion, exact row boundaries and scoped stock passed");
    }

    private static PlanningBudget budget() {
        return new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime);
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        return new GraphRecipe<>(id, id, inputs.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), outputs);
    }

    private static void summaries() {
        var recipes = Map.of("consume", recipe("consume", Map.of("a", Long.MAX_VALUE), Map.of("b", 2L)),
                "return", recipe("return", Map.of("b", 1L), Map.of("a", Long.MAX_VALUE)),
                "seed", recipe("seed", Map.of("c", 3L), Map.of("c", 3L, "b", 1L)));
        var steps = new ArrayList<PlanStep>();
        for (long times : new long[] {0, 1, 2, 3, Long.MAX_VALUE}) {
            var body = new PlanStep.Sequence(List.of(new PlanStep.Batch("consume", times),
                    new PlanStep.Batch("seed", times), new PlanStep.Batch("return", times)));
            steps.add(body);
            steps.add(new PlanStep.Repeat(body, times));
            steps.add(new PlanStep.Sequence(List.of(body, body, new PlanStep.Repeat(body, 0))));
        }
        for (var step : steps) {
            var expected = SequenceSummary.of(step, recipes);
            var budget = budget();
            try (var fold = new SummaryComputation<>(step, recipes, budget)) {
                while (!fold.step()) {}
                var actual = fold.result();
                for (String key : List.of("a", "b", "c"))
                    check(expected.required(key).equals(actual.required(key)) && expected.delta(key).equals(actual.delta(key)) &&
                            expected.peak(key).equals(actual.peak(key)), "wide summary mismatch: " + step + "/" + key);
            }
            check(budget.reservedBytes() == 0, "summary memory leaked");
        }
        var input = new LinkedHashMap<>(Map.of("k", BigInteger.valueOf(Long.MAX_VALUE)));
        var snapshot = new SequenceSummary<>(input, input, input);
        input.put("k", BigInteger.ZERO);
        check(snapshot.required("k").equals(BigInteger.valueOf(Long.MAX_VALUE)) &&
                snapshot.delta("k").equals(BigInteger.valueOf(Long.MAX_VALUE)) &&
                snapshot.peak("k").equals(BigInteger.valueOf(Long.MAX_VALUE)), "Public constructor retained a mutable input");
        try { snapshot.delta().put("k", BigInteger.ZERO); throw new AssertionError("mutable snapshot"); }
        catch (UnsupportedOperationException expected) {}
        var owned = SequenceSummary.recipe(recipes.get("seed"));
        try { owned.delta().entrySet().iterator().next().setValue(BigInteger.ZERO); throw new AssertionError("mutable entry"); }
        catch (UnsupportedOperationException expected) {}
    }

    private static void dotProducts() throws Exception {
        var satisfies = CountLcg.class.getDeclaredMethod("satisfies", BigInteger[].class);
        satisfies.setAccessible(true);
        BigInteger[] endpoints = {BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE.negate(), BigInteger.TWO,
                BigInteger.valueOf(Long.MIN_VALUE), BigInteger.valueOf(Long.MAX_VALUE),
                BigInteger.ONE.shiftLeft(63), BigInteger.ONE.shiftLeft(129).negate()};
        for (BigInteger a : endpoints) for (BigInteger b : endpoints) {
            // The later negative term can cancel a prefix larger than long.
            var terms = new LinkedHashMap<Integer, BigInteger>();
            terms.put(2, a.negate());
            terms.put(0, a);
            terms.put(1, b);
            BigInteger[] point = {b, a, b};
            BigInteger exact = a.multiply(b);
            for (int offset : new int[] {-1, 0, 1}) {
                var row = new ExactLinearProgram.Constraint(terms, exact.add(BigInteger.valueOf(offset)));
                check(new ArrayList<>(row.terms().keySet()).equals(List.of(0, 1, 2)), "row order is unstable");
                var budget = budget();
                try (var search = new CountLcg(List.of(row), point, point, budget, 10000)) {
                    check((boolean) satisfies.invoke(search, (Object) point) == (offset >= 0), "dot product overflow changed truth");
                }
                check(budget.reservedBytes() == 0, "dot product memory leaked");
            }
        }
    }

    private static void ordinaryPrefixes() {
        long[] amounts = {1, 2, 1L << 31, Long.MAX_VALUE};
        for (long input : amounts) for (long output : amounts) for (long count : amounts) {
            var recipes = Map.of("in", recipe("in", Map.of("a", input), Map.of("b", output)),
                    "out", recipe("out", Map.of("b", output), Map.of("a", input)));
            // The prefix can cross MIN_VALUE/MAX_VALUE and later cancel back
            // into long. Promotion must preserve the earlier need and peak.
            var step = new PlanStep.Sequence(List.of(new PlanStep.Batch("in", count),
                    new PlanStep.Batch("in", 1), new PlanStep.Batch("out", count), new PlanStep.Batch("out", 1)));
            var expected = SequenceSummary.of(step, recipes);
            var budget = budget();
            try (var fold = new SummaryComputation<>(step, recipes, budget)) {
                while (!fold.step()) {}
                for (String key : List.of("a", "b"))
                    check(expected.required(key).equals(fold.result().required(key)) &&
                            expected.delta(key).equals(fold.result().delta(key)) &&
                            expected.peak(key).equals(fold.result().peak(key)), "ordinary prefix overflow: " + input + "/" + output + "/" + count);
            }
            check(budget.reservedBytes() == 0, "ordinary fold leaked");
        }
    }

    private static void localInventory() {
        var contents = new HashMap<String, Long>();
        for (int i = 0; i < 10000; i++) contents.put("unrelated-" + i, Long.MAX_VALUE);
        contents.put("a", 1L);
        contents.put("c", 19L);
        Map<String, Long> lookupOnly = new AbstractMap<>() {
            @Override public Long get(Object key) { return contents.get(key); }
            @Override public boolean containsKey(Object key) { return contents.containsKey(key); }
            @Override public Set<Entry<String, Long>> entrySet() {
                throw new AssertionError("Local scheduling enumerated the whole network stock");
            }
        };
        var recipes = List.of(recipe("ab", Map.of("a", 1L), Map.of("b", 1L, "c", 2L)),
                recipe("ba", Map.of("b", 1L), Map.of("a", 1L)));
        var budget = budget();
        try (var model = RecipeCountModel.forShell(recipes, Map.of("c", BigInteger.valueOf(23)), lookupOnly, Set.of(), budget);
             var schedule = new CountSchedule<>(model, new BigInteger[] {BigInteger.TWO, BigInteger.TWO}, budget);
             var bounded = new CountBoundedSchedule<>(model, new BigInteger[] {BigInteger.TWO, BigInteger.TWO}, budget)) {
            while (!schedule.step()) {}
            check(schedule.result() == CountSchedule.Result.WITNESS, "Local stock omitted a startup input");
            var summary = SequenceSummary.of(schedule.witness(), Map.of("ab", recipes.get(0), "ba", recipes.get(1)));
            check(summary.required("a").equals(BigInteger.ONE) && summary.delta("c").equals(BigInteger.valueOf(4)),
                    "Scoped inventory changed the executable multiset");
            while (!bounded.step()) {}
            check(bounded.result() == CountSchedule.Result.WITNESS, "Bounded stock view lost a valid schedule");
        }
        check(budget.reservedBytes() == 0 && contents.get("a") == 1L && contents.get("c") == 19L, "stock or ownership changed");
    }

    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
