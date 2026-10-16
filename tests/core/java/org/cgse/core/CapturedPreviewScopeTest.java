// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;

/** Only a complete view can expose an infeasible order and its refill preview. */
public final class CapturedPreviewScopeTest {
    private static int checks;
    public static void main(String[] args) {
        var input = new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("raw", 1), null, false)));
        var source = new CapturedCatalog.Entry<>("make", "make", new CapturedRecipe<>(List.of(input), List.of(new CapturedRecipe.Amount<>("product", 1)), false, false));
        long completeWork = 0, restrictedWork = 0;
        for (boolean complete : List.of(true, false)) for (boolean available : List.of(false, true)) {
            var b = budget(); var catalog = new CapturedCatalog<>(List.of(source), complete);
            var result = catalog.plan("product", 7, available ? Map.of("raw", 7L) : Map.of(), false, false, b);
            var plan = result.plan();
            check(b.reservedBytes() == 0, "Discarded preview leaked workspace");
            if (available) { check(plan.feasible(), "Preview policy changed positive solve"); PlanVerifier.verify(plan); }
            else if (complete) {
                check(plan.result() == GraphPlan.Result.MISSING_INPUT && plan.missing().equals(Map.of("raw", 7L)), "Complete capture lost its refill plan: " + plan.result() + "/" + plan.missing() + "/" + b.diagnostics());
                check(!b.diagnostics().contains("omitted_by_view_owner"), "Complete capture suppressed refill preview"); completeWork = b.searchWork();
            } else {
                check(plan.result() == GraphPlan.Result.UNKNOWN && plan.missing().isEmpty(), "Restricted failure escaped coverage scope");
                check(b.diagnostics().contains("omitted_by_view_owner") && !b.failureDetail().startsWith("PROVEN_INFEASIBLE"), "Local failure became global detail");
                restrictedWork = b.searchWork();
            }
        }
        check(restrictedWork < completeWork, "Still assembled a discarded restricted refill preview");
        var recipe = new GraphRecipe<>("make", "make", List.of(new GraphRecipe.Slot<>("raw", 1)), Map.of("product", 1L));
        for (boolean enabled : List.of(false, true)) {
            var b = budget();
            var work = new GraphPlanningWork<>(new GraphCompiler<>(List.of(recipe)), "product", 7, Map.of(), false, false, b).missingPreview(enabled);
            try {
                work.step();
                try { work.missingPreview(!enabled); throw new AssertionError("Changed policy during planning"); }
                catch (IllegalStateException expected) { checks++; }
                while (!work.step()) {}
                check(work.result().result() == (enabled ? GraphPlan.Result.MISSING_INPUT : GraphPlan.Result.INFEASIBLE), "Preview policy changed the local proof");
                check(work.result().missing().isEmpty() != enabled, "Preview ownership ignored");
            } finally { work.close(); }
            check(b.reservedBytes() == 0, "Preview owner leaked memory");
        }
        System.out.println("CapturedPreviewScopeTest: assertions=" + checks);
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
