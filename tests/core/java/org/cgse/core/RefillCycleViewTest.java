// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Refill cuts must retain real cycle edges for display without scheduling those recipes. */
public final class RefillCycleViewTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private static PlanningBudget budget() { return new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime); }
    private static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static GraphPlan<String> partial(GraphRecipe<String> finish) {
        return new GraphPlan<>("G", 1, false, new PlanStep.Batch(finish.id(), 1), Map.of(finish.id(), finish),
                finish.inputs(), Map.of(), finish.inputs(), GraphPlan.Result.MISSING_INPUT, 0, 0);
    }
    private static List<GraphRecipe<String>> pair() {
        return new ArrayList<>(List.of(r("finish", Map.of("A", 2L), Map.of("G", 1L)),
                r("a", Map.of("B", 1L), Map.of("A", 1L)), r("b", Map.of("A", 1L), Map.of("B", 1L))));
    }
    private static PlanTopology<String> topology(GraphPlan<String> plan, List<GraphRecipe<String>> catalog) {
        var view = new PlanRingView<>(plan, plan.patternTimesExact().keySet());
        var selected = new ArrayList<GraphRecipe<String>>();
        var ids = new HashSet<String>();
        for (int i = 0; i < view.rows().size(); i++) {
            var row = view.rows().get(i);
            if (row.kind() != PlanRingView.Kind.RECIPE) continue;
            check(i < view.graphRows(), "Reference recipe outside graph prefix");
            check(ids.add(row.id()), "Duplicate reference recipe");
            var original = catalog.stream().filter(v -> v.id().equals(row.id())).findFirst().orElseThrow();
            check(row.count().equals(plan.patternTimesExact().getOrDefault(row.id(), BigInteger.ZERO)), "Reference changed execution count");
            var inputs = new LinkedHashMap<String, Long>(); var outputs = new LinkedHashMap<String, Long>();
            row.inputs().forEach(v -> inputs.put(v.key(), v.amount().longValueExact()));
            row.outputs().forEach(v -> outputs.put(v.key(), v.amount().longValueExact()));
            check(inputs.equals(original.inputs()) && outputs.equals(original.executionOutputs()), "Invented cycle edges");
            selected.add(r(row.id(), inputs, outputs));
        }
        return new PlanTopology<>(selected);
    }
    private static void cyclic(PlanTopology<String> topology, String key) {
        var node = topology.nodes().stream().filter(n -> key.equals(n.resource())).findFirst().orElseThrow();
        check(topology.groups().get(topology.groupOf(node.id())).cyclic(), "Cycle missing from refill view: " + key);
    }
    private static GraphPlan<String> annotate(List<GraphRecipe<String>> recipes, GraphPlan<String> original,
                                               Set<String> excluded, PlanningBudget diagnostics) {
        var compiler = new GraphCompiler<>(recipes); var b = budget();
        try (var order = new RefillProducerOrder<>(compiler, original.missingExact().keySet(), Set.of(), excluded, b)) {
            while (!order.step()) {}
            try (var d = new RefillBoundaryDiagnostics<>(original, compiler, order, diagnostics)) {
                boolean done;
                do { long before = diagnostics.refillDiagnosticWork(); done = d.step();
                    check(diagnostics.refillDiagnosticWork() - before <= 64, "Unbounded diagnostic turn");
                } while (!done);
                var result = d.result();
                check(result.steps() == original.steps() && result.recipes() == original.recipes() &&
                        result.initialExact() == original.initialExact() && result.missingExact() == original.missingExact() &&
                        result.result() == original.result(), "Display changed executable plan or refill bill");
                return result;
            }
        } finally { check(b.reservedBytes() == 0 && diagnostics.reservedBytes() == 0, "Display workspace leaked"); }
    }
    private static void publicPaths() {
        for (boolean fallback : List.of(false, true)) for (long amount : new long[]{1, Long.MAX_VALUE}) {
            var recipes = pair(); var compiler = new GraphCompiler<>(recipes); var b = budget();
            var plan = fallback ? GraphFallback.plan(compiler, "G", amount, Map.of(), Set.of(), Map.of(), false, true, b) :
                    new GraphPlanner<>(compiler).plan("G", amount, Map.of(), false, true, b);
            check(!plan.missingExact().isEmpty(), "Missing bill vanished");
            var topology = topology(plan, recipes);
            for (var key : plan.missingExact().keySet()) {
                check(plan.missingBoundary(key).source() == GraphPlan.MissingSource.CYCLE_BOUNDARY, "Missing boundary annotation");
                cyclic(topology, key);
            }
            check(PlanVerifier.initialExact(plan.steps(), plan.recipes(), "G", amount, plan.seeds()).equals(plan.initialExact()), "Invalid execution witness");
            check(plan.missingExact().equals(plan.initialExact()), "Unfunded refill witness");
            check(b.reservedBytes() == 0, "Public planning workspace leaked");
        }
    }
    private static void scopesAndCopies() {
        for (int seed = 0; seed < 12; seed++) {
            var recipes = pair(); var original = partial(recipes.get(0));
            recipes.add(r("unrelated1", Map.of("X", 1L), Map.of("Y", 1L)));
            recipes.add(r("unrelated2", Map.of("Y", 1L), Map.of("X", 1L)));
            Collections.shuffle(recipes, new Random(seed));
            var b = budget(); var p = annotate(recipes, original, Set.of(), b);
            check(b.nodes() == 0 && b.compilationWork() == 0 && b.cachePreparationWork() == 0, "Display stole solving quotas");
            cyclic(topology(p, recipes), "A");
            check(topology(p, recipes).nodes().stream().noneMatch(n -> "X".equals(n.resource())), "Unrelated catalog cycle included");
            p = p.withSeedOptimality(new GraphPlan.SeedOptimality(0, 0, false, false, true)).withAlternatives(List.of(original));
            cyclic(topology(p, recipes), "A");
            check(topology(original, recipes).groups().stream().noneMatch(PlanTopology.Group::cyclic), "Original plan mutated");
            var excluded = annotate(recipes, original, Set.of("a"), budget());
            check(topology(excluded, recipes).groups().stream().noneMatch(PlanTopology.Group::cyclic), "Excluded producer restored");
            recipes.add(r("escape", Map.of("ore", 1L), Map.of("A", 1L)));
            var escape = annotate(recipes, original, Set.of(), budget());
            check(topology(escape, recipes).groups().stream().noneMatch(PlanTopology.Group::cyclic), "Acyclic source marked as closed boundary");
        }
        var finish = r("finish", Map.of("A", 1L, "B", 1L), Map.of("G", 1L));
        var multi = List.of(finish, r("shared", Map.of("C", 1L), Map.of("A", 1L, "B", 1L)),
                r("return", Map.of("A", 1L, "B", 1L), Map.of("C", 1L)));
        var p = annotate(multi, partial(finish), Set.of(), budget());
        for (String key : List.of("A", "B", "C")) cyclic(topology(p, multi), key);
        var grow = List.of(finish, r("grow", Map.of("A", 1L), Map.of("A", 2L, "B", 1L)));
        cyclic(topology(annotate(grow, partial(finish), Set.of(), budget()), grow), "A");
    }
    private static void limits() {
        var recipes = pair(); var p = partial(recipes.get(0));
        // The view allowance is independent of classification and cannot publish a broken cycle.
        var b = budget(); for (int i = 0; i < 16_382; i++) check(b.tryRefillCycleView(), "Diagnostic precharge");
        var limited = annotate(recipes, p, Set.of(), b);
        check(limited.missingBoundary("A").source() == GraphPlan.MissingSource.CYCLE_BOUNDARY, "Classification displaced by tracing");
        check(topology(limited, recipes).groups().stream().noneMatch(PlanTopology.Group::cyclic), "Incomplete trace published");
        check(!b.tryRefillCycleView() && b.tryRefillDiagnostic(), "View cap displaced source classification");
        b = new PlanningBudget(0, 4_000_000, 192, () -> false, System::nanoTime);
        limited = annotate(recipes, p, Set.of(), b);
        check(limited.missingBoundary("A").source() == GraphPlan.MissingSource.CYCLE_BOUNDARY, "Optional memory admission changed boundary");
        check(topology(limited, recipes).groups().stream().noneMatch(PlanTopology.Group::cyclic), "Memory limit published partial trace");
        // Unsupported optional recipe shapes must not invalidate the otherwise displayable plan.
        var in = new LinkedHashMap<String, Long>(); in.put("B", 1L);
        for (int i = 0; i < PlanRingView.MAX_SLOTS; i++) in.put("raw" + i, 1L);
        recipes.set(1, r("a", in, Map.of("A", 1L)));
        limited = annotate(recipes, p, Set.of(), budget());
        check(topology(limited, recipes).groups().stream().noneMatch(PlanTopology.Group::cyclic), "Oversized optional shape admitted");
    }
    public static void main(String[] args) {
        publicPaths(); scopesAndCopies(); limits();
        System.out.println("RefillCycleViewTest: assertions=" + checks);
    }
}
