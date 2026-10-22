// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Check generation itself, before the coordinator's incumbent-retention guard. */
public final class RefillBatchCountsTest {
    public static void main(String[] args) {
        generator();
        if (args.length > 0 && args[0].equals("generator")) return;
        finiteOracle();
        sharedInputs();
        wideAmounts();
        declinedDomains();
        interruption();
        System.out.println("RefillBatchCountsTest PASS: whole orders, batch mixtures, shared demand, exact counts and limits");
    }

    private static void generator() {
        for (int cheap : new int[]{1, 2}) for (boolean reverse : List.of(false, true))
            for (int depth : new int[]{1, 8}) for (int count : new int[]{7, 65, 128})
                for (int stock : new int[]{0, 1}) for (int completion : new int[]{-1, 10, 30}) {
                    var recipes = sources(cheap, 1, 100, 64, "raw", "x0");
                    if (reverse) Collections.reverse(recipes);
                    for (int i = 1; i < depth; i++) recipes.add(recipe("link" + i, Map.of("x" + (i - 1), 1L), Map.of("x" + i, 1L)));
                    recipes.add(recipe("finish", Map.of("x" + (depth - 1), 1L), Map.of("G", 1L)));
                    long expected = Math.max(0, oracle(cheap, 1, 100, 64, count) - stock);
                    allocation(recipes, count, Map.of("raw", (long) stock), completion, expected);
                }
    }

    private static void finiteOracle() {
        int cases = 0;
        for (int ao = 1; ao <= 5; ao++) for (int bo = 1; bo <= 5; bo++)
            for (int ai : new int[]{1, 2, 7, 11}) for (int bi : new int[]{1, 2, 7, 11})
                for (int count : new int[]{1, 7, 19, 65}) for (boolean reverse : List.of(false, true)) {
                    var recipes = sources(ai, ao, bi, bo, "raw", "G");
                    if (reverse) Collections.reverse(recipes);
                    Map<String, BigInteger> stock = Map.of("raw", BigInteger.valueOf(count % 4));
                    PlanStep word = exact(recipes, stock, Map.of("G", BigInteger.valueOf(count)));
                    var missing = execute(word, recipes, stock, BigInteger.valueOf(count));
                    BigInteger expected = BigInteger.valueOf(Math.max(0, oracle(ai, ao, bi, bo, count) - count % 4));
                    check(missing.equals(positive("raw", expected)), "finite batch oracle differs: " + recipes + " / " + count + " / " + missing);
                    cases++;
                }
        check(cases == 3200, "oracle coverage changed");
    }

    private static void sharedInputs() {
        var recipes = sources(2, 1, 100, 64, "raw", "P");
        recipes.add(recipe("A", Map.of("P", 3L), Map.of("A", 1L)));
        recipes.add(recipe("B", Map.of("P", 5L), Map.of("B", 1L)));
        recipes.add(recipe("finish", Map.of("A", 1L, "B", 1L), Map.of("G", 1L)));
        Map<String, Long> held = Map.of("P", 3L, "A", 2L, "B", 1L, "raw", 4L, "G", 2L);
        // Nine new goals need 7*3 + 8*5 - 3 = 58 P: one bulk batch, minus 4 raw held.
        for (int order = 0; order < 8; order++) {
            Collections.shuffle(recipes, new Random(order));
            for (int completion : new int[]{-1, 10, 30}) allocation(recipes, 9, held, completion, 96);
            var stock = ExactAmounts.copy(held);
            var word = exact(recipes, stock, Map.of("G", BigInteger.valueOf(11)));
            check(execute(word, recipes, stock, BigInteger.valueOf(11)).equals(Map.of("raw", BigInteger.valueOf(96))), "shared stock credited twice");
        }
        // A second batch choice upstream must receive the aggregate demand too.
        var upstream = sources(2, 1, 100, 64, "ore", "raw");
        recipes.addAll(upstream);
        var stock = ExactAmounts.copy(held);
        var word = exact(recipes, stock, Map.of("G", BigInteger.valueOf(11)));
        check(execute(word, recipes, stock, BigInteger.valueOf(11)).equals(Map.of("ore", BigInteger.valueOf(164))), "upstream batching lost shared demand");
    }

    private static void wideAmounts() {
        var recipes = sources(2, 1, 100, 64, "raw", "G");
        BigInteger count = BigInteger.valueOf(Long.MAX_VALUE).pow(2).multiply(BigInteger.valueOf(64)).add(BigInteger.ONE);
        BigInteger expected = count.subtract(BigInteger.ONE).divide(BigInteger.valueOf(64)).multiply(BigInteger.valueOf(100)).add(BigInteger.TWO);
        var word = exact(recipes, Map.of(), Map.of("G", count));
        check(execute(word, recipes, Map.of(), count).equals(Map.of("raw", expected)), "wide count was capped or rounded");
    }

    private static void declinedDomains() {
        for (var recipes : List.of(sources(1, 259, 2, 257, "raw", "G"),
                List.of(recipe("a", Map.of("raw", 1L), Map.of("G", 1L)), recipe("b", Map.of("other", 2L), Map.of("G", 2L))))) {
            var b = budget();
            try (var work = RefillPairCounts.sameInput(new RefillPairCounts.Choice<>("G", recipes.get(0), recipes.get(1)), BigInteger.valueOf(1_000_000), b)) {
                while (!work.step()) {}
                check(work.summary() == null, "unsupported count shortcut must decline");
            }
            check(b.reservedBytes() == 0, "declined pair leaked");
        }
    }

    private static void interruption() {
        var recipes = sources(2, 1, 100, 64, "raw", "G");
        var cancel = new AtomicBoolean();
        var b = new PlanningBudget(0, 100_000, 1L << 20, cancel::get, System::nanoTime);
        try (var work = MissingMaterialExpansion.fromRequest(new GraphCompiler<>(recipes), "G", 65, Map.of(), Set.of(), Map.of(), Set.of(), false, true, b)) {
            check(!work.step(), "generation should yield"); cancel.set(true);
            try { work.step(); throw new AssertionError("cancellation ignored"); }
            catch (CancellationException expected) { }
        }
        check(b.reservedBytes() == 0, "cancelled workspace leaked");
        b = new PlanningBudget(0, 5, 1L << 20, () -> false, System::nanoTime);
        try (var work = MissingMaterialExpansion.fromRequest(new GraphCompiler<>(recipes), "G", 65, Map.of(), Set.of(), Map.of(), Set.of(), false, true, b)) {
            try { while (!work.step()) {} throw new AssertionError("search cap ignored"); }
            catch (PlanningBudget.Exhausted expected) { check(expected.limit() == PlanningBudget.Limit.SEARCH_LIMIT, "wrong search cap"); check(work.result() == null, "limit published an unfinished bill"); }
        }
        check(b.reservedBytes() == 0, "limited workspace leaked");
        b = new PlanningBudget(0, 100_000, 300, () -> false, System::nanoTime);
        try { MissingMaterialExpansion.fromRequest(new GraphCompiler<>(recipes), "G", 1, Map.of(), Set.of(), Map.of(), Set.of(), false, true, b); throw new AssertionError("memory cap ignored"); }
        catch (PlanningBudget.Exhausted expected) { check(expected.limit() == PlanningBudget.Limit.MEMORY_LIMIT, "wrong memory cap"); }
        check(b.reservedBytes() == 0, "failed constructor leaked");
    }

    private static void allocation(List<GraphRecipe<String>> recipes, int count, Map<String, Long> stock, int completion, long expected) {
        var b = budget();
        try (var work = MissingMaterialExpansion.fromRequest(new GraphCompiler<>(recipes), "G", count, stock, Set.of(), Map.of(), Set.of(), false, true, b)) {
            int step = 0;
            while (!work.step()) if (++step == completion) b.operation(PlanningBudget.Operation.SCAN, 256);
            var plan = Objects.requireNonNull(work.result(), "generator lost the candidate");
            check(plan.missingExact().equals(positive("raw", BigInteger.valueOf(expected))), "generator chose a larger refill: " + plan.missingExact() + " expected " + expected);
            PlanVerifier.verify(new GraphPlan<>("G", count, false, plan.steps(), plan.recipes(), plan.initialExact(), plan.seeds(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0));
        }
        check(b.reservedBytes() == 0, "generator workspace leaked");
    }

    private static PlanStep exact(List<GraphRecipe<String>> recipes, Map<String, BigInteger> stock, Map<String, BigInteger> goals) {
        var b = budget(); PlanStep result;
        var boundary = new GraphPlan<>("G", goals.get("G").min(ExactAmounts.LONG_MAX).longValueExact(), false, new PlanStep.Sequence(List.of()), Map.of(), goals, Map.of(), goals, GraphPlan.Result.MISSING_INPUT, 0, 0);
        try (var work = MissingMaterialExpansion.fromGoals(new GraphCompiler<>(recipes), boundary, PlanningInventory.exactSnapshot(stock), Set.of(), false, b)) {
            while (!work.step()) {}
            result = Objects.requireNonNull(work.result()).steps();
        }
        check(b.reservedBytes() == 0, "batch workspace leaked"); return result;
    }

    /** Independent finite oracle: enumerate every bounded pair, without a ratio/residue reduction. */
    private static long oracle(int ai, int ao, int bi, int bo, int count) {
        long best = Long.MAX_VALUE;
        for (int x = 0; x <= (count + ao - 1) / ao; x++)
            for (int y = 0; y <= (count + bo - 1) / bo; y++)
                if (x * ao + y * bo >= count) best = Math.min(best, (long) x * ai + (long) y * bi);
        return best;
    }

    private static Map<String, BigInteger> execute(PlanStep word, List<GraphRecipe<String>> recipes, Map<String, BigInteger> stock, BigInteger goal) {
        var indexed = new HashMap<String, GraphRecipe<String>>(); recipes.forEach(r -> indexed.put(r.id(), r));
        var held = new HashMap<>(stock); var missing = new HashMap<String, BigInteger>();
        fire(word, BigInteger.ONE, indexed, held, missing);
        check(held.getOrDefault("G", BigInteger.ZERO).compareTo(goal) >= 0, "word missed its goal"); return missing;
    }

    private static void fire(PlanStep step, BigInteger scale, Map<String, GraphRecipe<String>> recipes, Map<String, BigInteger> held, Map<String, BigInteger> missing) {
        if (step instanceof PlanStep.Sequence sequence) {
            for (var child : sequence.children()) fire(child, scale, recipes, held, missing);
        } else if (step instanceof PlanStep.Repeat repeat) {
            fire(repeat.body(), scale.multiply(BigInteger.valueOf(repeat.times())), recipes, held, missing);
        } else if (step instanceof PlanStep.Batch batch) {
            var recipe = recipes.get(batch.recipe()); BigInteger count = scale.multiply(BigInteger.valueOf(batch.runs()));
            recipe.inputs().forEach((key, quantity) -> {
                BigInteger consumed = count.multiply(BigInteger.valueOf(quantity)), available = held.getOrDefault(key, BigInteger.ZERO);
                if (available.compareTo(consumed) < 0) missing.merge(key, consumed.subtract(available), BigInteger::add);
                held.put(key, available.subtract(consumed).max(BigInteger.ZERO));
            });
            recipe.outputs().forEach((key, quantity) -> held.merge(key, count.multiply(BigInteger.valueOf(quantity)), BigInteger::add));
        }
    }

    private static ArrayList<GraphRecipe<String>> sources(int ai, int ao, int bi, int bo, String input, String output) {
        return new ArrayList<>(List.of(recipe(output + "-small", Map.of(input, (long) ai), Map.of(output, (long) ao)), recipe(output + "-bulk", Map.of(input, (long) bi), Map.of(output, (long) bo))));
    }
    private static GraphRecipe<String> recipe(String id, Map<String, Long> input, Map<String, Long> output) {
        return new GraphRecipe<>(id, id, input.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), output);
    }
    private static Map<String, BigInteger> positive(String key, BigInteger value) { return value.signum() == 0 ? Map.of() : Map.of(key, value); }
    private static PlanningBudget budget() { return new PlanningBudget(0, 2_000_000, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
}
