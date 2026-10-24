// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Restoring physical bindings must retain the request's producer exclusions. */
public final class AllocationStartupCatalogTest {
    public static void main(String[] args) {
        for (boolean reverse : List.of(false, true)) for (boolean preview : List.of(false, true)) {
            var grow = rule("grow", Map.of("A", 1L, "ore", 1L), Map.of("A", 2L));
            var source = rule("source", Map.of("raw", 1L), Map.of("A", 1L));
            var finish = rule("finish", Map.of("A", 2L), Map.of("G", 1L));
            var catalog = new ArrayList<>(List.of(grow, source, finish));
            if (reverse) Collections.reverse(catalog);
            // The restored word binds only finish. The unselected productive
            // loop still matters, and an excluded raw route cannot reopen it.
            var guarded = assemble(finish, catalog, Set.of("source"), true, preview);
            if (!preview) check(guarded == null, "Published a depleted productive startup");
            else {
                check(guarded != null && guarded.seeds().equals(Map.of("A", 1L)), "Unused growth path was not protected");
                check(guarded.missingExact().equals(Map.of("A", BigInteger.ONE)), "Excluded source hid the missing startup");
                check(guarded.initialExact().get("A").equals(BigInteger.valueOf(3)), "Seed loan was not funded");
            }
            var renewable = assemble(finish, catalog, Set.of(), true, preview);
            check(renewable != null && renewable.feasible() && renewable.seeds().isEmpty(),
                    "Available terminal source was incorrectly treated as a closed loop");
            var raw = assemble(finish, catalog, Set.of("grow", "source"), true, preview);
            check(raw != null && raw.feasible() && raw.seeds().isEmpty(), "Excluded loops made raw stock unspendable");
            var ordinary = assemble(finish, catalog, Set.of("source"), false, preview);
            check(ordinary != null && ordinary.feasible() && ordinary.seeds().isEmpty(), "Ordinary mode inherited automatic reserves");
        }
        System.out.println("AllocationStartupCatalogTest PASS");
    }

    private static GraphPlan<String> assemble(GraphRecipe<String> finish, List<GraphRecipe<String>> catalog,
                                              Set<String> excluded, boolean preserve, boolean preview) {
        var budget = new PlanningBudget(0, 32768, 4L << 20, () -> false, System::nanoTime);
        GraphPlan<String> result;
        try (var candidate = new AllocationSearch.Candidate<>(new PlanStep.Batch("finish", 1),
                Map.of("finish", finish), "G", 1, Map.of("A", 2L), Map.of(), Set.of(), preserve,
                true, preview, budget, System.nanoTime()).startupCatalog(catalog, excluded)) {
            while (!candidate.step()) {}
            result = candidate.plan;
        }
        check(budget.reservedBytes() == 0, "Startup catalog validation leaked workspace");
        return result;
    }

    private static GraphRecipe<String> rule(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream()
                .map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
