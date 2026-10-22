// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;

public final class GraphSupplyViewTest {
    public static void main(String[] args) {
        for (boolean reverse : List.of(false, true)) {
            var source = new GraphRecipe<String>("source", "physical-binding", List.of(
                    new GraphRecipe.Slot<>("a", 1), new GraphRecipe.Slot<>("ore", 1),
                    new GraphRecipe.Slot<>("circuit", 1, 2, true, true)), Map.of("b", 1L, "circuit", 1L));
            var recipes = new ArrayList<>(List.of(source, r("back", "b", 1, "a", 2),
                    r("finish", "a", 3, "g", 1), r("raw", "stone", 1, "ore", 1)));
            if (reverse) Collections.reverse(recipes);
            var compiler = new GraphCompiler<>(recipes);
            var before = List.copyOf(compiler.catalog());
            var producers = List.copyOf(compiler.producers("a"));
            var supply = Set.of("ore", "circuit");
            var stock = Map.of("a", 1L);
            var budget = budget(false, 16L << 20);
            GraphPlan<String> restored;
            try (var view = new GraphSupplyView<>(compiler, supply, budget)) {
                check(view.compiler().producers("ore").isEmpty(), "Supplied output still has a production edge");
                var projected = view.compiler().catalog().stream().filter(r -> r.id().equals("source")).findFirst().orElseThrow();
                check(projected.inputs().equals(Map.of("a", 1L)) && projected.outputs().equals(Map.of("b", 1L)),
                        "Supply projection retained a physical supply dependency");
                check(view.originals().get("source") == source && projected.binding().equals(source.binding()), "Binding lost");
                var goal = new GraphPlan<String>("g", 1, false, new PlanStep.Sequence(List.of()), Map.of(), Map.of(),
                        Map.of(), Map.of(), GraphPlan.Result.UNKNOWN, 0, 0);
                var search = GraphPlanningWork.refillView(view.compiler(), goal, stock, supply, Set.of(), true, budget);
                GraphPlan<String> proposal;
                try { while (!search.step()) {} proposal = search.result(); }
                finally { search.close(); }
                check(proposal.feasible(), "Projected native cycle did not solve");
                try (var candidate = new AllocationSearch.Candidate<>(proposal.steps(), view.originals(), "g", 1,
                        stock, Map.of(), supply, false, true, true, budget, System.nanoTime())) {
                    while (!candidate.step()) {} restored = candidate.plan;
                }
                check(restored != null && restored.feasible(), "Physical restoration failed");
                check(restored.initialExact().get("ore").equals(BigInteger.TWO), "Lost finite ore consumption");
                check(restored.initialExact().get("circuit").equals(BigInteger.ONE), "Returned circuit charged per execution");
                check(restored.recipes().get("source") == source, "Projected binding escaped publication");
            }
            PlanVerifier.verify(restored);
            check(List.copyOf(compiler.catalog()).equals(before) && compiler.producers("a").equals(producers),
                    "Private view changed the source compiler/order");
            check(budget.reservedBytes() == 0, "Supply view leaked memory");
            // A finite-port solve after an unlimited supply proposal must still fail.
            var finite = new GraphPlanningWork<>(compiler, "g", 1, stock, false, true, budget).missingPreview(false);
            try { while (!finite.step()) {} check(!finite.result().feasible(), "Supply witness contaminated finite-stock search"); }
            finally { finite.close(); }
            check(budget.reservedBytes() == 0, "Finite check leaked");
        }
        var compiler = new GraphCompiler<>(List.of(r("r", "ore", 1, "g", 1)));
        var cancelled = budget(true, 4096);
        try { new GraphSupplyView<>(compiler, Set.of("ore"), cancelled); throw new AssertionError("Ignored cancellation"); }
        catch (CancellationException expected) { }
        check(cancelled.reservedBytes() == 0, "Cancelled constructor leaked");
        var small = budget(false, 128);
        try { new GraphSupplyView<>(compiler, Set.of("ore"), small); throw new AssertionError("Ignored memory cap"); }
        catch (PlanningBudget.Exhausted expected) { }
        check(small.reservedBytes() == 0, "Failed constructor leaked");
        System.out.println("GraphSupplyViewTest PASS: private supply edges, physical restoration, finite reuse and cleanup");
    }
    private static PlanningBudget budget(boolean cancel, long memory) {
        return new PlanningBudget(0, 2_000_000, memory, () -> cancel, System::nanoTime);
    }
    private static GraphRecipe<String> r(String id, String in, long count, String out, long made) {
        return new GraphRecipe<>(id, id, List.of(new GraphRecipe.Slot<>(in, count)), Map.of(out, made));
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
