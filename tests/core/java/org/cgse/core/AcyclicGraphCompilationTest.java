// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Restricted physical DAGs use the same generic compiler and preserve shared material accounting. */
public final class AcyclicGraphCompilationTest {
    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
    private static PlanningBudget budget() { return new PlanningBudget(0, 4_000_000, 128L << 20, () -> false, System::nanoTime); }
    private static GraphRecipe<String> r(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        return new GraphRecipe<>(id, id, inputs.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), outputs);
    }
    private static GraphCompiler<String> restricted(List<GraphRecipe<String>> recipes) {
        var original = new GraphCompiler<>(recipes);
        return new GraphCompiler<>(recipes, GraphMode.ACYCLIC, original.verificationCatalog());
    }
    private static GraphCompiler.Compiled<String> compile(GraphCompiler<String> compiler, String target, Set<String> additional,
                                                           Map<String, Integer> choices, PlanningBudget budget) {
        try (var work = compiler.begin(target, additional, choices, Set.of(), budget)) {
            while (!work.step()) {}
            return work.result();
        } finally { check(budget.reservedBytes() == 0, "Compilation workspace leaked"); }
    }
    private static void ordered(GraphCompiler.Compiled<String> graph) {
        var positions = new HashMap<String, Integer>();
        for (int i = 0; i < graph.regions().size(); i++) {
            var region = graph.regions().get(i);
            check(!region.cyclic(), "Restricted physical DAG was classified as cyclic");
            for (var recipe : region.recipes()) positions.put(recipe.id(), i);
        }
        check(positions.size() == graph.recipes().size(), "Region publication omitted selected recipe");
        for (var producer : graph.recipes().values()) for (var consumer : graph.recipes().values())
            if (!Collections.disjoint(producer.executionOutputs().keySet(), consumer.inputs().keySet()))
                check(positions.get(consumer.id()) < positions.get(producer.id()), "Regions are not consumers-first");
    }
    private static List<GraphRecipe<String>> shared() {
        return List.of(r("a", Map.of("raw", 1L), Map.of("A", 1L, "shared", 1L)),
                r("b", Map.of("raw", 1L), Map.of("B", 1L, "shared", 1L)),
                r("finish", Map.of("A", 1L, "B", 1L, "shared", 2L), Map.of("T", 1L)),
                r("alternative", Map.of("other", 1L), Map.of("A", 1L)));
    }
    private static void sharingAndAlternatives() {
        for (int seed = 0; seed < 12; seed++) {
            var recipes = new ArrayList<>(shared()); Collections.shuffle(recipes, new Random(seed));
            var compiler = restricted(recipes); var budget = budget();
            var ordinal = new HashMap<String, Integer>();
            ordinal.put("A", compiler.producers("A").indexOf(recipes.stream().filter(x -> x.id().equals("a")).findFirst().orElseThrow()));
            var graph = compile(compiler, "T", Set.of(), ordinal, budget);
            ordered(graph);
            check(graph.recipes().keySet().equals(Set.of("a", "b", "finish")), "Shared producer selection changed");
            ordinal.put("A", 1 - ordinal.get("A"));
            var alternative = compile(compiler, "T", Set.of(), ordinal, budget());
            ordered(alternative);
            check(alternative.recipes().containsKey("alternative"), "Ordinary producer alternative was dropped");
        }
        var recipes = shared(); var compiler = new GraphCompiler<>(recipes); var budget = budget();
        var work = new GraphPlanningWork<>(compiler, "T", 1, Map.<String, Long>of(), Set.<String>of(), Map.<String, Long>of(),
                false, true, budget, GraphMode.ACYCLIC);
        try {
            while (!work.step()) {}
            var plan = work.result();
            check(plan.missingExact().equals(Map.of("raw", BigInteger.TWO)), "Shared raw inventory or coproduct supply was counted twice: " + plan.patternTimesExact() + "; missing=" + plan.missingExact() + "; " + budget.diagnostics());
            check(plan.patternTimesExact().equals(Map.of("a", BigInteger.ONE, "b", BigInteger.ONE, "finish", BigInteger.ONE)), "Shared dependency word changed");
            PlanVerifier.verify(new GraphPlan<>("T", 1, false, plan.steps(), plan.recipes(), plan.initialExact(), plan.seeds(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0));
        } finally { work.close(); }
        check(budget.reservedBytes() == 0, "Primary DAG workspace leaked");
    }
    private static void linearAndLifetime() {
        int n = 10_000;
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < n; i++) recipes.add(r("r" + i, Map.of("k" + i, 1L), Map.of("k" + (i + 1), 1L)));
        var budget = budget(); var compiler = restricted(recipes);
        var graph = compile(compiler, "k" + n, Set.of(), Map.of(), budget);
        check(graph.regions().size() == n && graph.regions().stream().noneMatch(GraphCompiler.Region::cyclic), "Long chain regions changed");
        check(graph.regions().get(0).recipes().get(0).id().equals("r" + (n - 1)), "Long chain consumer order changed");
        check(budget.compilationWork() < 40L * n, "Chain compilation is not linear");
        System.out.println("Generic restricted graph chain: recipes=" + n + "; work=" + budget.compilationWork() + "; peak_bytes=" + budget.peakBytes());
        var cancelled = new AtomicBoolean();
        var cancelBudget = new PlanningBudget(0, 4_000_000, 128L << 20, cancelled::get, System::nanoTime);
        try (var work = compiler.begin("k" + n, Map.of(), Set.of(), cancelBudget)) {
            for (int i = 0; i < 6 * n; i++) work.step();
            cancelled.set(true);
            try { work.step(); throw new AssertionError("Compilation ignored cancellation"); }
            catch (CancellationException expected) {}
        }
        check(cancelBudget.reservedBytes() == 0, "Cancelled graph compilation leaked");
        var limitedBudget = new PlanningBudget(0, 4_000_000, 4096, () -> false, System::nanoTime);
        try (var work = compiler.begin("k" + n, Map.of(), Set.of(), limitedBudget)) {
            try { while (!work.step()) {} throw new AssertionError("Graph memory cap ignored"); }
            catch (PlanningBudget.Exhausted expected) {}
        }
        check(limitedBudget.reservedBytes() == 0, "Limited graph compilation leaked");
        int fan = 1000;
        var broad = new ArrayList<GraphRecipe<String>>(); var inputs = new LinkedHashMap<String, Long>();
        inputs.put("shared", (long) fan);
        for (int i = 0; i < fan; i++) {
            broad.add(r("s" + i, Map.of("raw" + i, 1L), Map.of("u" + i, 1L, "shared", 1L)));
            inputs.put("u" + i, 1L);
        }
        broad.add(r("finish", inputs, Map.of("T", 1L)));
        var broadCompiler = restricted(broad);
        var broadBudget = budget(); var broadGraph = compile(broadCompiler, "T", Set.of(), Map.of(), broadBudget);
        check(broadGraph.regions().size() == fan + 1 && broadGraph.regions().stream().noneMatch(GraphCompiler.Region::cyclic), "Shared outputs were classified as a production cycle");
        check(broadBudget.compilationWork() < 60L * fan, "Shared material compilation expanded quadratic edges");
    }
    public static void main(String[] args) {
        sharingAndAlternatives(); linearAndLifetime();
        System.out.println("AcyclicGraphCompilationTest: generic physical DAG regions, alternatives, coproduct sharing, linear bounds and cleanup PASS");
    }
}
