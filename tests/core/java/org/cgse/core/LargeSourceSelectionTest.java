// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;

/** A small executable support must remain reachable beyond full-count admission. */
public final class LargeSourceSelectionTest {
    public static void main(String[] args) {
        for (int order = 0; order < 8; order++) largeCatalog(order);
        selectionOrderAndCutoff();
        System.out.println("Large source selection: eight 9,002-recipe orders, demand reopening, stable ties and interrupted scans passed");
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }

    private static PlanningBudget budget() { return new PlanningBudget(0, 350_000, 64L << 20, () -> false, System::nanoTime); }

    private static void largeCatalog(int order) {
        List<GraphRecipe<String>> recipes = new ArrayList<>();
        recipes.add(recipe("consume", Map.of("X", 10L), Map.of("C", 1L)));
        for (int i = 0; i < 9000; i++) recipes.add(recipe("unfunded" + i, Map.of("raw" + i, 1L), Map.of("X", 10L)));
        recipes.add(recipe("funded", Map.of("ore", 1L), Map.of("X", 10L)));
        if (order > 0) Collections.shuffle(recipes, new Random(order));
        var compiler = new GraphCompiler<>(recipes);
        var stock = Map.of("ore", 1L, "X", 1L);
        var budget = budget();
        check(compiler.countClosure("C", Set.of(), Set.of(), 8192, 8192, budget) == null, "Test did not exceed full-closure admission");
        try (var ranking = GraphSourceRanking.createLocal(compiler, stock, Set.of(), "C", true, budget, 8192);
             var view = new GraphStockViewWork<>(compiler, "C", 1, stock, Set.of(), Map.of(), Set.of(), false, true,
                     CatalystPolicy.STOCK, budget, System.nanoTime(), 2, () -> ranking)) {
            while (!view.step()) {}
            var result = view.result();
            check(result != null && result.feasible(), "Useful late source lost at order " + order + ": " + budget.diagnostics());
            check(result.patternTimes().keySet().equals(Set.of("consume", "funded")), "Unexpected support");
            try (var verification = new PlanVerification<>(result, budget)) {
                while (!verification.step()) {}
                try (var proof = new ForceCraftProof<>(result, verification, Map.of(), budget)) {
                    while (!proof.step()) {}
                    check(proof.proved(), "Late source failed final production validation");
                }
            }
            // A separate arithmetic check on the primitive route, independent
            // of the graph solver's aggregate feasibility flags.
            long runs = result.patternTimes().get("funded"), consumes = result.patternTimes().get("consume");
            check(runs == 1 && consumes == 1 && stock.get("ore") - runs >= 0 && stock.get("X") + 10 * runs - 10 * consumes == 1,
                    "Primitive inventory prefix does not fund the order");
            System.out.println("order=" + order + " search=" + budget.searchWork() + " compile=" + budget.compilationWork());
        }
        check(budget.reservedBytes() == 0, "Large view leaked");
    }

    private static void selectionOrderAndCutoff() {
        List<GraphRecipe<String>> recipes = new ArrayList<>();
        recipes.add(recipe("missing", Map.of("absent", 1L), Map.of("T", 1L)));
        recipes.add(recipe("good", Map.of("ore", 1L), Map.of("T", 2L)));
        recipes.add(recipe("tie", Map.of("ore", 1L), Map.of("T", 2L)));
        for (int i = 0; i < 100; i++) recipes.add(recipe("later" + i, Map.of("absent", 1L), Map.of("T", 1L)));
        var compiler = new GraphCompiler<>(recipes);
        var budget = budget();
        try (var ranking = GraphSourceRanking.createLocal(compiler, Map.of("ore", 1L), Set.of(), "T", true, budget, 8192)) {
            var partial = ranking.choose("T", true, 2, false, Set.of(), Set.of(), 8);
            check(partial != null && partial.recipe().id().equals("good"), "Scan cutoff discarded the funded prefix");
            for (boolean byCost : new boolean[]{false, true}) for (boolean batch : new boolean[]{false, true}) {
                var order = batch ? ranking.sources("T", 2, 8192) : ranking.sources("T", byCost, 8192);
                for (Set<String> excluded : List.of(Set.<String>of(), Set.of("missing"))) {
                    var choice = ranking.choose("T", byCost, 2, batch, excluded, Set.of("good"), 8192);
                    var expected = order.stream().filter(r -> !excluded.contains(r.id()) && !r.id().equals("good")).findFirst().orElseThrow();
                    check(choice.recipe() == expected, "Selection changed stable source priority");
                    var selectable = recipes.stream().filter(r -> !excluded.contains(r.id())).toList();
                    check(selectable.get(choice.ordinal()) == expected, "Filtered producer ordinal changed");
                }
            }
        }
        check(budget.reservedBytes() == 0, "Selection leaked");
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
