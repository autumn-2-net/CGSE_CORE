// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/** Returned feed can hide an upstream starter; every proposal must execute with real outputs. */
public final class GraphStartupViewTest {
    private static GraphRecipe<String> recipe(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        return new GraphRecipe<>(id, id, inputs.entrySet().stream()
                .map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), outputs);
    }

    private static List<GraphRecipe<String>> recipes() {
        return List.of(recipe("solid", Map.of("liquid", 1L, "feed", 1L), Map.of("crystal", 1L)),
                recipe("separate", Map.of("gas", 100L), Map.of("liquid", 1L, "crystal", 1L, "carrier", 90L)),
                recipe("vaporize", Map.of("crystal", 1L, "carrier", 100L), Map.of("gas", 90L)),
                recipe("fresh", Map.of("ore", 1L), Map.of("carrier", 1L)));
    }

    private static Map<String, Long> stock() {
        return Map.of("crystal", 400L, "gas", 20L, "ore", 1_000_000L, "feed", 10000L);
    }

    private static PlanningBudget budget(long bytes) {
        return new PlanningBudget(0, 4_000_000, bytes, () -> false, System::nanoTime);
    }

    private static GraphCompiler.Compiled<String> graph(GraphCompiler<String> compiler) {
        var budget = budget(64L << 20);
        try (var work = compiler.begin("crystal", Set.of(), Map.of(), Set.of(), budget)) {
            while (!work.step()) {}
            return work.result();
        } finally { check(budget.reservedBytes() == 0, "Graph compilation leaked"); }
    }

    public static void main(String[] args) {
        int checked = 0;
        for (int order = 0; order < 8; order++) {
            var recipes = new ArrayList<>(recipes());
            Collections.shuffle(recipes, new Random(order));
            var compiler = new GraphCompiler<>(recipes);
            var graph = graph(compiler);
            for (long amount : new long[] {1, 10, 100, 1000}) {
                var budget = budget(64L << 20);
                try (var view = new GraphOutputView<>(compiler, graph, "crystal", amount, stock(), Map.of(),
                        Set.of(), Set.of(), true, true, CatalystPolicy.MINIMAL, budget, 0)) {
                    while (!view.step()) {}
                    var plan = view.result();
                    check(plan != null && plan.feasible(), "No startup witness for " + order + "/" + amount + ": " + budget.diagnostics());
                    replay(plan, stock());
                    check(plan.recipes().get("separate").outputs().equals(recipes().get(1).outputs()), "Returned feed was omitted from the final plan");
                    checked++;
                }
                check(budget.reservedBytes() == 0, "Startup view leaked");
            }
            // A stock change must neither reuse the old marking nor poison the
            // canonical compiler when a restricted proposal cannot run.
            var empty = Map.of("crystal", 400L, "gas", 20L);
            var budget = budget(64L << 20);
            try (var view = new GraphOutputView<>(compiler, graph, "crystal", 10, empty, Map.of(),
                    Set.of(), Set.of(), true, true, CatalystPolicy.MINIMAL, budget, 0)) {
                while (!view.step()) {}
                check(view.result() == null, "Invented unavailable feed");
            }
            check(budget.reservedBytes() == 0, "Failed optional view leaked");
            budget = budget(64L << 20);
            var work = new GraphPlanningWork<>(compiler, "crystal", 10, stock(), true, true, budget);
            try {
                while (!work.step()) {}
                check(work.result().feasible(), "Restricted failure escaped into a global proof");
                replay(work.result(), stock());
            } finally { work.close(); }
            check(budget.reservedBytes() == 0, "Primary planner leaked");
        }
        lifecycle();
        System.out.println("Startup views: " + checked + " restored witnesses, stock changes, primary planning and lifecycle passed");
    }

    private static void lifecycle() {
        var compiler = new GraphCompiler<>(recipes());
        var graph = graph(compiler);
        for (long memory : new long[] {1, 4096, 16384, 65536, 524288, 64L << 20}) {
            var budget = budget(memory);
            try (var view = new GraphOutputView<>(compiler, graph, "crystal", 10, stock(), Map.of(),
                    Set.of(), Set.of(), true, true, CatalystPolicy.MINIMAL, budget, 0)) {
                while (!view.step()) {}
                if (view.result() != null) replay(view.result(), stock());
            }
            check(budget.reservedBytes() == 0, "Memory refusal unbalanced at " + memory);
        }
        for (int stop = 1; stop <= 400; stop++) {
            int limit = stop;
            var calls = new AtomicInteger();
            var budget = new PlanningBudget(0, 4_000_000, 64L << 20,
                    () -> calls.incrementAndGet() >= limit, System::nanoTime);
            try (var view = new GraphOutputView<>(compiler, graph, "crystal", 10, stock(), Map.of(),
                    Set.of(), Set.of(), true, true, CatalystPolicy.MINIMAL, budget, 0)) {
                while (!view.step()) {}
            } catch (CancellationException expected) {}
            check(budget.reservedBytes() == 0, "Cancelled view leaked at " + stop);
        }
    }

    /** Literal inventory replay, independent of summary/flow/force-craft code. */
    private static void replay(GraphPlan<String> plan, Map<String, Long> stock) {
        Map<String, BigInteger> held = new HashMap<>(plan.initialExact());
        held.forEach((key, value) -> check(value.signum() >= 0 && value.compareTo(BigInteger.valueOf(stock.getOrDefault(key, 0L))) <= 0,
                "Unfunded initial inventory"));
        BigInteger before = held.getOrDefault(plan.target(), BigInteger.ZERO);
        BigInteger[] produced = {BigInteger.ZERO};
        execute(plan.steps(), plan, held, produced);
        check(produced[0].compareTo(BigInteger.valueOf(plan.amount())) >= 0, "Insufficient physical output");
        check(held.getOrDefault(plan.target(), BigInteger.ZERO).compareTo(before) > 0, "Forced order has no net gain");
        var goals = new HashMap<>(plan.seeds());
        goals.merge(plan.target(), plan.amount(), Math::addExact);
        goals.forEach((key, value) -> check(held.getOrDefault(key, BigInteger.ZERO).compareTo(BigInteger.valueOf(value)) >= 0, "Lost goal or seed"));
        PlanVerifier.verify(plan);
        PlanVerifier.verifyRuntimeInventory(plan);
    }

    private static void execute(PlanStep step, GraphPlan<String> plan, Map<String, BigInteger> held, BigInteger[] produced) {
        if (step instanceof PlanStep.Sequence sequence) {
            for (var child : sequence.children()) execute(child, plan, held, produced);
        } else if (step instanceof PlanStep.Repeat repeat) {
            for (long i = 0; i < repeat.times(); i++) execute(repeat.body(), plan, held, produced);
        } else {
            var batch = (PlanStep.Batch) step;
            var recipe = plan.recipes().get(batch.recipe());
            for (long i = 0; i < batch.runs(); i++) {
                recipe.inputs().forEach((key, value) -> {
                    BigInteger next = held.getOrDefault(key, BigInteger.ZERO).subtract(BigInteger.valueOf(value));
                    check(next.signum() >= 0, "Unexecutable prefix: " + recipe.id() + "/" + key);
                    held.put(key, next);
                });
                recipe.outputs().forEach((key, value) -> held.merge(key, BigInteger.valueOf(value), BigInteger::add));
                produced[0] = produced[0].add(BigInteger.valueOf(recipe.outputs().getOrDefault(plan.target(), 0L)));
            }
        }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
