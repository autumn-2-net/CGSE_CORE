// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RefillBoundarySearchTest {
    public static void main(String[] args) {
        canonicalContinuesBoundarySearch();
        coordinatePrefixes();
        var recipes = List.of(r("ab", Map.of("a", 1L, "ore", 1L), Map.of("a", 1L, "b", 1L)),
                r("bc", Map.of("b", 1L), Map.of("c", 1L)), r("finish", Map.of("c", 1L), Map.of("a", 1L, "g", 1L)));
        var stock = Map.of("a", 1L);
        var original = suffix(recipes, Map.of("c", BigInteger.ONE));
        var result = improve(recipes, original, stock);
        check(result.missingExact().equals(Map.of("ore", BigInteger.ONE)), "existing seed enables base-only purchase");
        verify(result, stock);
        var fallback = GraphFallback.plan(new GraphCompiler<>(recipes), "g", 1, stock, Set.of(), Map.of(), false, true,
                new PlanningBudget(0, 8_000_000, 128L << 20, () -> false, System::nanoTime));
        check(fallback.missingExact().equals(Map.of("ore", BigInteger.ONE)), "fallback expands a seeded cycle to its base input");
        verify(fallback, stock);
        var oldMissing = original.missingExact();
        check(result.alternatives().stream().anyMatch(p -> p.missingExact().equals(oldMissing)), "retain old verified tradeoff");
        result = improve(recipes, original, Map.of("a", 1L, "ore", 1L));
        check(result.feasible() && result.missingExact().isEmpty(), "a cycle alone cannot establish a shortage"); verify(result, Map.of("a", 1L, "ore", 1L));

        var tools = List.of(r("ab", Map.of("a", 1L, "ore", 1L, "tool", 1L), Map.of("a", 1L, "b", 1L, "tool", 1L)),
                recipes.get(1), recipes.get(2));
        result = improve(tools, suffix(tools, Map.of("c", BigInteger.ONE)), stock);
        check(result.missingExact().equals(Map.of("ore", BigInteger.ONE, "tool", BigInteger.ONE)),
                "a returned tool without a manufacturing route is a purchasable leaf"); verify(result, stock);

        var loop = List.of(r("ab", Map.of("a", 1L, "fuel", 1L), Map.of("b", 1L)),
                r("ba", Map.of("b", 1L), Map.of("a", 2L)), r("finish", Map.of("a", 1L, "b", 1L), Map.of("g", 1L)));
        original = suffix(loop, Map.of("a", BigInteger.ONE, "b", BigInteger.ONE));
        result = improve(loop, original, Map.of("fuel", 10L));
        verify(result, Map.of("fuel", 10L));
        check(!result.feasible(), "unseeded cycle cannot manufacture free input");
        check(result.missingExact().size() <= 2 &&
                (result.missingExact().containsKey("a") ^ result.missingExact().containsKey("b")), "one cycle anchor, not every cyclic port");

        var cancel = new AtomicBoolean(); var budget = new PlanningBudget(0, 4_000_000, 64L << 20, cancel::get, System::nanoTime);
        var compiler = new GraphCompiler<>(recipes);
        try (var order = new RefillProducerOrder<>(compiler, original.missingExact().keySet(), Set.of(), Set.of(), budget)) {
            while (!order.step()) {}
            long held = budget.reservedBytes();
            try (var search = new RefillPlanningSearch<>(compiler, original, order, Map.of(), Set.of(), Set.of(), true, false, budget)) {
                cancel.set(true);
                try { search.step(); throw new AssertionError("Cancellation ignored"); }
                catch (CancellationException expected) { }
            }
            check(budget.reservedBytes() == held, "cancelled search leaked"); cancel.set(false);
        }
        check(budget.reservedBytes() == 0, "support charge leaked");
        System.out.println("RefillBoundarySearchTest PASS: base-only, funded cycle, concentrated cycle and cancellation");
    }

    private static void coordinatePrefixes() {
        for (boolean reverse : List.of(false, true)) for (boolean damaged : List.of(false, true, false)) {
            var recipes = new ArrayList<>(List.of(r("a", Map.of("a", 1L, "ore", 1L), Map.of("x", 1L)),
                    r("return-a", Map.of("x", 1L), Map.of("a", 2L)),
                    r("b", Map.of("b", 1L, "ore", 1L), Map.of("y", 1L)),
                    r("return-b", Map.of("y", 1L), Map.of("b", 2L))));
            if (damaged) recipes.removeIf(r -> r.id().equals("return-b"));
            for (int i = 0; i < 200; i++) recipes.add(r("other-" + i, Map.of("ore-" + i, 1L), Map.of("other-" + i, 1L)));
            var finish = r("finish", Map.of("a", 10L, "b", 10L), Map.of("g", 1L));
            recipes.add(finish);
            if (reverse) Collections.reverse(recipes);
            var original = new GraphPlan<String>("g", 1, false, new PlanStep.Batch("finish", 1), Map.of("finish", finish),
                    Map.of("a", BigInteger.TEN, "b", BigInteger.TEN), Map.of(),
                    Map.of("a", BigInteger.valueOf(9), "b", BigInteger.valueOf(9)), GraphPlan.Result.MISSING_INPUT, 0, 0);
            var stock = Map.of("a", 1L, "b", 1L);
            var result = improve(recipes, original, stock);
            var expected = damaged ? Map.of("b", BigInteger.valueOf(9), "ore", BigInteger.valueOf(9)) :
                    Map.of("ore", BigInteger.valueOf(18));
            check(result.missingExact().equals(expected), "Coordinate prefix / damaged producer: " + result.missingExact());
            verify(result, stock);
        }
    }

    private static void canonicalContinuesBoundarySearch() {
        // The goal constructor can complete a cyclic seed bill while a
        // terminal-funded execution needs only ore. Completing that candidate
        // must not skip the established terminal-boundary solver afterwards.
        var values = List.of(r("make", Map.of("A", 1L, "ore", 1L), Map.of("B", 1L)),
                r("return", Map.of("B", 1L), Map.of("A", 2L)),
                r("finish", Map.of("A", 10L), Map.of("g", 1L)));
        for (boolean reverse : List.of(false, true)) {
            var recipes = new ArrayList<>(values); if (reverse) Collections.reverse(recipes);
            var initial = Map.of("A", BigInteger.TEN);
            var original = new GraphPlan<>("g", 1, false, new PlanStep.Batch("finish", 1),
                    Map.of("finish", values.get(2)), initial, Map.of(), Map.of("A", BigInteger.valueOf(9)),
                    GraphPlan.Result.MISSING_INPUT, 0, 0);
            var compiler = new GraphCompiler<>(recipes);
            var b = new PlanningBudget(0, 1_000_000, 8L << 20, () -> false, System::nanoTime);
            try (var order = new RefillProducerOrder<>(compiler, initial.keySet(), Set.of(), Set.of(), b)) {
                while (!order.step()) {}
                try (var search = new RefillPlanningSearch<>(compiler, original, order, Map.of("A", 1L),
                        Set.of(), Set.of(), true, false, b).retainTrades(true)) {
                    while (!search.step()) {}
                    check(search.result().missingExact().equals(Map.of("ore", BigInteger.valueOf(9))),
                            "Canonical candidate skipped the terminal-funded solve: " + search.result().missingExact());
                    check(search.result().alternatives().stream().anyMatch(p -> p.missingExact().equals(original.missingExact())),
                            "Canonical reconstruction discarded the incomparable original supply option");
                    RefillWordPruningTest.literalVerify(search.result(), Map.of("A", 1L), Set.of());
                }
            }
            check(b.reservedBytes() == 0, "Canonical continuation leaked");
        }
    }

    private static GraphPlan<String> improve(List<GraphRecipe<String>> recipes, GraphPlan<String> original, Map<String, Long> stock) {
        var compiler = new GraphCompiler<>(recipes);
        var budget = new PlanningBudget(0, 8_000_000, 128L << 20, () -> false, System::nanoTime);
        GraphPlan<String> result;
        try (var order = new RefillProducerOrder<>(compiler, original.missingExact().keySet(), Set.of(), Set.of(), budget)) {
            while (!order.step()) {}
            try (var search = new RefillPlanningSearch<>(compiler, original, order, stock, Set.of(), Set.of(), true, false, budget)) {
                while (!search.step()) {} result = search.result();
                check(search.step() && search.result() == result, "completed search restarted");
            }
        }
        check(budget.reservedBytes() == 0, "refill workspace leaked");
        return result;
    }
    private static GraphPlan<String> suffix(List<GraphRecipe<String>> recipes, Map<String, BigInteger> initial) {
        var finish = recipes.get(recipes.size() - 1);
        return new GraphPlan<>("g", 1, false, new PlanStep.Batch(finish.id(), 1), Map.of(finish.id(), finish),
                initial, Map.of(), initial, GraphPlan.Result.INCOMPLETE_MODEL, 0, 0);
    }
    private static void verify(GraphPlan<String> plan, Map<String, Long> stock) {
        var funded = new GraphPlan<>(plan.target(), plan.amount(), plan.preserveSeeds(), plan.steps(), plan.recipes(),
                plan.initialExact(), plan.seeds(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        PlanVerifier.verify(funded);
        for (var need : plan.initialExact().entrySet()) check(need.getValue().compareTo(BigInteger.valueOf(stock.getOrDefault(need.getKey(), 0L))
                .add(plan.missingExact().getOrDefault(need.getKey(), BigInteger.ZERO))) <= 0, "unfunded prefix " + need);
    }
    private static GraphRecipe<String> r(String id, Map<String, Long> input, Map<String, Long> output) {
        return new GraphRecipe<>(id, id, input.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), output);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
