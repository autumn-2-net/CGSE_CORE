// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Completed refill witnesses survive owner limits without promoting restricted proofs. */
public final class RequestPreviewHandoffTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        for (var limit : List.of(PlanningBudget.Limit.SEARCH_LIMIT, PlanningBudget.Limit.MEMORY_LIMIT, PlanningBudget.Limit.TIMEOUT)) {
            for (boolean bounded : List.of(false, true)) {
                var b = budget();
                var request = request(5, false, bounded, b);
                try {
                    var child = child(request, 5);
                    while (!child.step()) {}
                    check(child.result().missing().equals(Map.of("raw", 10L)), "No verified child refill");
                    set(request, "current", child);
                    var exhausted = new PlanningBudget.Exhausted(limit);
                    try {
                        var retained = request.limited(exhausted);
                        check(!bounded, "Restricted ordinary proof escaped its coverage guard");
                        check(retained.amount() == 5 && retained.missing().equals(Map.of("raw", 10L)), "Completed refill was lost");
                        verify(retained);
                        check(!request.fallbackMode() && request.reuseCandidate() == null, "Preview gained fallback or feasibility provenance");
                    } catch (PlanningBudget.Exhausted failure) {
                        check(bounded && failure == exhausted, "Complete refill was replaced by a resource failure");
                    }
                } finally { request.close(); }
                check(b.reservedBytes() == 0, "Handoff leaked workspace");
            }
            // Only a feasible reduced quantity should replace a useful full-order
            // preview. An interrupted smaller conditional probe is inconclusive.
            var b = budget();
            var request = request(5, true, true, b);
            try {
                GraphPlan<String> full, reduced;
                var fullChild = child(request, 5);
                try {
                    while (!fullChild.step()) {}
                    full = conditional(fullChild.result());
                } finally { fullChild.close(); }
                var reducedChild = child(request, 1);
                try {
                    while (!reducedChild.step()) {}
                    reduced = conditional(reducedChild.result());
                } finally { reducedChild.close(); }
                set(request, "selected", full);
                set(request, "partialSearch", true);
                set(request, "current", new PlanningScheduler.Work<GraphPlan<String>>() {
                    public boolean advance(PlanningScheduler.Slice slice) { return true; }
                    public GraphPlan<String> result() { return reduced; }
                    public GraphPlan<String> limited(PlanningBudget.Exhausted ignored) { return reduced; }
                });
                var retained = request.limited(new PlanningBudget.Exhausted(limit));
                check(retained.amount() == 5 && retained.missing().equals(Map.of("raw", 10L)), "Smaller unfunded probe replaced full-order preview");
                check(retained.conditionalPreview() && !retained.feasible(), "Retained refill lost its scope");
                verify(retained);
            } finally { request.close(); }
            check(b.reservedBytes() == 0, "Partial handoff leaked workspace");
        }
        System.out.println("RequestPreviewHandoffTest: assertions=" + checks);
    }

    private static GraphPlan<String> conditional(GraphPlan<String> p) {
        return new GraphPlan<>(p.target(), p.amount(), p.preserveSeeds(), p.steps(), p.recipes(), p.initialExact(), p.seeds(),
                p.missingExact(), GraphPlan.Result.INCOMPLETE_MODEL, p.searchNodes(), p.planningNanos());
    }

    private static void verify(GraphPlan<String> p) {
        check(!p.feasible(), "Unfunded preview became executable");
        PlanVerifier.verify(new GraphPlan<>(p.target(), p.amount(), p.preserveSeeds(), p.steps(), p.recipes(), p.initialExact(),
                p.seeds(), Map.of(), GraphPlan.Result.FEASIBLE, p.searchNodes(), p.planningNanos()));
    }

    @SuppressWarnings("unchecked")
    private static CatalystPlanningWork<String> child(RequestPlanningWork<String> request, long amount) throws Exception {
        var method = RequestPlanningWork.class.getDeclaredMethod("calculation", long.class);
        method.setAccessible(true);
        return (CatalystPlanningWork<String>) method.invoke(request, amount);
    }

    private static void set(RequestPlanningWork<String> request, String name, Object value) throws Exception {
        var field = RequestPlanningWork.class.getDeclaredField(name); field.setAccessible(true); field.set(request, value);
    }

    private static RequestPlanningWork<String> request(long amount, boolean craftLess, boolean bounded, PlanningBudget b) {
        var recipe = new GraphRecipe<>("make", "make", List.of(new GraphRecipe.Slot<>("raw", 2)), Map.of("book", 1L));
        return new RequestPlanningWork<>(new GraphCompiler<>(List.of(recipe)), "book", amount, Map.of(), Set.of(), null,
                false, craftLess, "REPORT_MISSING_ITEMS", CatalystPolicy.MINIMAL, false, bounded, b, () -> false,
                report -> { throw new AssertionError("Unexpected fallback"); });
    }

    private static PlanningBudget budget() { return new PlanningBudget(0, 1_000_000, 32L << 20, () -> false, System::nanoTime); }
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
