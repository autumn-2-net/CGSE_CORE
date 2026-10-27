// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** A failed full-stock proposal must still complete the minimum restart token. */
public final class CountCyclicStartupCompletionTest {
    private static int checks;

    public static void main(String[] args) {
        for (int size : new int[] {1, 3, 32}) for (boolean reverse : List.of(false, true)) {
            var recipes = new ArrayList<GraphRecipe<String>>();
            for (int i = 0; i < size - 1; i++) recipes.add(r("turn" + i,
                    i == 0 ? Map.of("k0", 1L, "ore", 1L) : Map.of("k" + i, 1L), Map.of("k" + (i + 1), 1L)));
            recipes.add(r("grow", size == 1 ? Map.of("k0", 1L, "ore", 1L) : Map.of("k" + (size - 1), 1L), Map.of("k0", 9L)));
            recipes.add(r("finish", Map.of("k0", 144L), Map.of("G", 1L)));
            if (reverse) Collections.reverse(recipes);
            var compiler = new GraphCompiler<>(recipes);
            for (long amount : new long[] {1, 2, 64}) {
                var stock = Map.of("k0", 144 * amount, "ore", 1L);
                var plan = solve(compiler, amount, stock, true, 16_000_000);
                check(plan != null, "A one-turn startup completion fell into deep search");
                verify(plan, stock);
                check(plan.patternTimesExact().get("finish").equals(BigInteger.valueOf(amount)), "Delivery count changed");
                check(plan.patternTimesExact().get("grow").equals(BigInteger.ONE), "Startup completion overproduced");
                check(plan.seeds().getOrDefault("k0", 0L) >= 1, "Startup obligation was lost");
                check(solve(compiler, amount, Map.of("k0", 144 * amount), true, 16_000_000) == null,
                        "A failed completion invented its raw feed");
                var repaired = solve(compiler, amount, stock, true, 16_000_000);
                check(repaired != null, "A failed completion poisoned the next request");
                verify(repaired, stock);
                var ordinary = solve(compiler, amount, Map.of("k0", 144 * amount), false, 16_000_000);
                check(ordinary != null && ordinary.seeds().isEmpty() && !ordinary.patternTimesExact().containsKey("grow"),
                        "Ordinary consumption acquired a preserve obligation");
            }
        }
        var compiler = new GraphCompiler<>(List.of(r("grow", Map.of("A", 1L, "ore", 1L), Map.of("A", 2L)),
                r("finish", Map.of("A", 3L), Map.of("G", 1L))));
        for (long cap : new long[] {1, 512, 32768, 262144, 2_000_000})
            solve(compiler, 1, Map.of("A", 1L, "ore", 3L), true, cap);
        boundaries();
        System.out.println("CountCyclicStartupCompletionTest PASS: checks=" + checks);
    }

    private static void boundaries() {
        var recipes = List.of(r("grow", Map.of("A", 1L, "ore", 1L), Map.of("A", 2L)),
                r("finish", Map.of("A", 3L), Map.of("G", 1L)));
        var stock = Map.of("A", 10L, "ore", 87L);
        var compiler = new GraphCompiler<>(recipes);
        for (long bytes : new long[]{1, 8192, 16384, 32768, 65536, 131072}) {
            var budget = new PlanningBudget(0, 16_000_000, bytes, () -> false, System::nanoTime);
            try (var search = new CountCyclicStockSearch<>(compiler, "G", 32, stock, Set.of(), Map.of(), Set.of(),
                    true, true, budget, System.nanoTime())) {
                while (!search.step()) {}
                if (search.result() != null) verify(search.result(), stock);
            }
            check(budget.reservedBytes() == 0 && budget.peakBytes() <= bytes, "Startup retry escaped its memory account");
        }
        for (int extraSteps : new int[]{0, 1, 10}) {
            var cancel = new java.util.concurrent.atomic.AtomicBoolean();
            var budget = new PlanningBudget(0, 16_000_000, 64L << 20, cancel::get, System::nanoTime);
            try (var search = new CountCyclicStockSearch<>(compiler, "G", 32, stock, Set.of(), Map.of(), Set.of(),
                    true, true, budget, System.nanoTime())) {
                while (!budget.diagnostics().contains("count_cyclic_startup"))
                    check(!search.step(), "Cancellation control missed the startup retry");
                for (int i = 0; i < extraSteps; i++) check(!search.step(), "Cancellation control ended early");
                cancel.set(true);
                try { search.step(); throw new AssertionError("Startup retry swallowed cancellation"); }
                catch (java.util.concurrent.CancellationException expected) { checks++; }
                check(search.result() == null, "Cancelled startup retry published an unfinished plan");
            }
            check(budget.reservedBytes() == 0, "Cancelled startup retry retained workspace");
        }
        for (boolean external : List.of(false, true)) {
            var budget = new PlanningBudget(0, 16_000_000, 64L << 20, () -> false, System::nanoTime);
            try (var search = new CountCyclicStockSearch<>(compiler, "G", 32, stock,
                    external ? Set.of("ore") : Set.of(), Map.of(), Set.of("grow"),
                    true, true, budget, System.nanoTime())) {
                while (!search.step()) {}
                check(search.result() == null, "Startup completion used an excluded producer");
            }
            check(budget.reservedBytes() == 0, "Excluded startup retained workspace");
        }
    }

    private static GraphPlan<String> solve(GraphCompiler<String> compiler, long amount, Map<String, Long> stock,
                                           boolean preserve, long cap) {
        var budget = new PlanningBudget(0, cap, 64L << 20, () -> false, System::nanoTime);
        GraphPlan<String> plan = null;
        try (var search = new CountCyclicStockSearch<>(compiler, "G", amount, stock, Set.of(), Map.of(), Set.of(),
                preserve, true, budget, System.nanoTime())) {
            while (!search.step()) {}
            plan = search.result();
        } catch (PlanningBudget.Exhausted limit) {
            check(cap < 16_000_000, "Normal completion exceeded its budget");
        }
        check(budget.reservedBytes() == 0, "Local startup overlay leaked memory");
        if (plan != null) verify(plan, stock);
        return plan;
    }

    private static void verify(GraphPlan<String> plan, Map<String, Long> stock) {
        check(plan.feasible() && plan.missingExact().isEmpty(), "Unfunded completion escaped");
        var need = PlanVerifier.initialExact(plan.steps(), plan.recipes(), "G", plan.amount(), plan.seeds());
        need.forEach((key, amount) -> check(amount.compareTo(BigInteger.valueOf(stock.getOrDefault(key, 0L))) <= 0,
                "Completed word exceeds actual inventory"));
        var summary = SequenceSummary.of(plan.steps(), plan.recipes());
        check(summary.delta("G").compareTo(BigInteger.valueOf(plan.amount())) >= 0, "Forced production was lost");
    }

    private static GraphRecipe<String> r(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        return new GraphRecipe<>(id, id, inputs.entrySet().stream()
                .map(entry -> new GraphRecipe.Slot<>(entry.getKey(), entry.getValue())).toList(), outputs);
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
