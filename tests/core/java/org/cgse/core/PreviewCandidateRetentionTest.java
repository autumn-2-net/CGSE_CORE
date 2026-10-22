// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** A missing-material proof must not discard an already cheaper funded word. */
public final class PreviewCandidateRetentionTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        for (int length : new int[] {1, 8, 25}) {
            var recipes = new ArrayList<GraphRecipe<String>>();
            recipes.add(recipe("cheap", Map.of("raw", 1L), Map.of("x0", 1L)));
            recipes.add(recipe("bulk", Map.of("raw", 100L), Map.of("x0", 64L)));
            for (int i = 1; i < length; i++) recipes.add(recipe("link" + i,
                    Map.of("x" + (i - 1), 1L), Map.of("x" + i, 1L)));
            recipes.add(recipe("finish", Map.of("x" + (length - 1), 1L), Map.of("G", 1L)));
            long[] caps = length == 1 ? new long[] {2000, 8000, 80000} : new long[] {32000, 80000};
            for (long cap : caps) for (int workers : new int[] {1, 4}) {
                var compiler = new GraphCompiler<>(recipes);
                try (var scheduler = new PlanningScheduler(workers, 8, 64, 1_000_000)) {
                    for (int repeat = 0; repeat < 3; repeat++) {
                        var budget = new PlanningBudget(0, cap, 64L << 20, () -> false, System::nanoTime);
                        var work = new RequestPlanningWork<>(compiler, "G", 7, Map.of(), Set.of(), null,
                                false, false, "REPORT_MISSING_ITEMS", CatalystPolicy.MINIMAL, false, false,
                                budget, () -> false, report -> { throw new AssertionError("Unexpected fallback"); });
                        var result = scheduler.submit(work, budget).get(20, TimeUnit.SECONDS);
                        check(result.missingExact().equals(Map.of("raw", BigInteger.valueOf(7))),
                                "cheaper source word lost: length=" + length + ", cap=" + cap + ", repeat=" + repeat + ": " + result.missingExact());
                        var stock = new HashMap<>(Map.of("raw", 7L));
                        execute(result.steps(), result.recipes(), stock);
                        check(stock.getOrDefault("G", 0L) >= 7, "refill failed delivery");
                        check(budget.reservedBytes() == 0 && compiler.cacheMetrics().activeSearches() == 0, "retention leaked state");
                    }
                }
            }
        }
        System.out.println("PreviewCandidateRetentionTest PASS: " + checks + " checks");
    }

    private static void execute(PlanStep step, Map<String, GraphRecipe<String>> recipes, Map<String, Long> stock) {
        if (step instanceof PlanStep.Sequence sequence) {
            for (var child : sequence.children()) execute(child, recipes, stock);
        } else if (step instanceof PlanStep.Repeat repeat) {
            check(repeat.times() < 1000, "unbounded primitive test replay");
            for (long i = 0; i < repeat.times(); i++) execute(repeat.body(), recipes, stock);
        } else {
            var batch = (PlanStep.Batch) step; var recipe = recipes.get(batch.recipe());
            check(batch.runs() < 1000, "unbounded primitive test batch");
            for (long i = 0; i < batch.runs(); i++) {
                for (var input : recipe.inputs().entrySet()) {
                    long left = stock.getOrDefault(input.getKey(), 0L) - input.getValue();
                    check(left >= 0, "unfunded input " + input.getKey()); stock.put(input.getKey(), left);
                }
                recipe.outputs().forEach((key, amount) -> stock.merge(key, amount, Math::addExact));
            }
        }
    }
    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
}
