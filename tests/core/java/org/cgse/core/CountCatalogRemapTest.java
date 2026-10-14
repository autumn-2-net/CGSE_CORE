// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;

/** Compare every reused row with a freshly built complete material equation. */
public final class CountCatalogRemapTest {
    public static void main(String[] args) {
        var original = recipes();
        var random = new Random(2026101402L);
        var donorBudget = budget();
        CountCatalog<String> donor;
        try (var model = model(original, Set.of(), Set.of(), "target0", 1, donorBudget)) {
            long incidences = model.recipes.stream().mapToLong(r -> r.inputs().size() + r.outputs().size()).sum();
            donor = new CountCatalog<>(model, incidences, donorBudget);
        }
        check(donorBudget.reservedBytes() == 0, "Donor retained request memory");
        long reused = 0;
        for (int sample = 0; sample < 240; sample++) {
            var current = new ArrayList<>(original);
            Collections.shuffle(current, random);
            if (sample % 3 == 0) {
                String id = "source" + sample % 48;
                current.removeIf(r -> r.id().equals(id));
                // Same ID, changed coefficients and a new byproduct producer.
                current.add(new GraphRecipe<>(id, id, List.of(new GraphRecipe.Slot<>("ore0", 17)), Map.of("m", 2L, "ore1", 3L)));
            }
            if (sample % 5 == 0) current.add(new GraphRecipe<>("new", "new", List.of(new GraphRecipe.Slot<>("ore2", 5)), Map.of("ore3", 9L)));
            if (sample % 7 == 0) current.removeIf(r -> r.id().equals("source47"));
            Set<String> excluded = sample % 4 == 0 ? Set.of("source46") : Set.of();
            Set<String> external = sample % 6 == 0 ? Set.of("ore4") : Set.of();
            var budget = budget();
            try (var cold = model(current, external, excluded, "target" + sample % 8, sample + 1, budget)) {
                var shared = donor.unchangedRows(cold.recipes, cold.keys, external, budget);
                check(shared.size() >= 20, "Dense remapped closure was not reused");
                for (var row : shared.entrySet()) {
                    int id = cold.rowKeys.indexOf(row.getKey());
                    check(id >= 0 && cold.constraints.get(id).terms().equals(row.getValue()), "Stale supply or wrong coordinate: " + sample + "/" + row.getKey());
                    check(new ArrayList<>(row.getValue().keySet()).equals(new ArrayList<>(cold.constraints.get(id).terms().keySet())), "Coefficient order changed");
                    reused++;
                }
            }
            check(budget.reservedBytes() == 0, "Remapping leaked request memory");
        }
        // Integration: alternate closures of different depths so every common
        // recipe moves. The compiler must retain the same order as a cold walk.
        var compiler = new GraphCompiler<>(original);
        for (int i = 0; i < 64; i++) {
            var a = budget(); var b = budget();
            String target = "target" + i % 8;
            Map<String, Long> stock = Map.of("ore0", 13L * i, "m", 11L);
            Set<String> excluded = i % 5 == 0 ? Set.of("source1") : Set.of();
            Set<String> external = i % 7 == 0 ? Set.of("ore7") : Set.of();
            try (var cold = RecipeCountModel.create(new GraphCompiler<>(original), target, i + 1, stock, Map.of(), external, excluded, true, a);
                 var warm = RecipeCountModel.create(compiler, target, i + 1, stock, Map.of(), external, excluded, true, b)) {
                check(cold.constraints.equals(warm.constraints) && cold.rowKeys.equals(warm.rowKeys) && cold.recipes.equals(warm.recipes), "Compiler changed the full count model");
            }
            check(a.reservedBytes() == 0 && b.reservedBytes() == 0, "Model leaked request memory");
        }
        check(compiler.cacheMetrics().caches().get("count_overlap").hits() > 0, "Compiler bypassed remap reuse");
        var stopped = budget(); stopped.cancel();
        boolean cancelled = false;
        try { donor.unchangedRows(donor.recipes, donor.keys, Set.of(), stopped); }
        catch (java.util.concurrent.CancellationException expected) { cancelled = true; }
        check(cancelled && stopped.reservedBytes() == 0, "Remapping ignored cancellation");
        System.out.println("Count catalog remapping: " + reused + " independently rebuilt rows; 240 source changes and 64 complete request overlays passed");
    }

    private static RecipeCountModel<String> model(List<GraphRecipe<String>> recipes, Set<String> external, Set<String> excluded,
                                                    String target, int amount, PlanningBudget budget) {
        return RecipeCountModel.create(new GraphCompiler<>(recipes), target, amount, Map.of("ore0", 19L, "m", 2L), Map.of(), external, excluded, true, budget);
    }

    private static List<GraphRecipe<String>> recipes() {
        var result = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < 48; i++) {
            var slots = new ArrayList<GraphRecipe.Slot<String>>();
            for (int j = 0; j < 24; j++) slots.add(new GraphRecipe.Slot<>("ore" + j, 1 + (i + j * 3) % 7));
            // Include both consumed and reusable per-push configurations.
            slots.add(new GraphRecipe.Slot<>("circuit", 1, 25, true, i % 2 == 0));
            var outputs = new LinkedHashMap<String, Long>(); outputs.put("m", 1L); outputs.put("circuit", 1L);
            result.add(new GraphRecipe<>("source" + i, "source" + i, slots, outputs));
        }
        for (int i = 0; i < 8; i++) {
            String input = "m";
            if (i % 2 != 0) {
                input = "bridge" + i;
                result.add(new GraphRecipe<>(input, input, List.of(new GraphRecipe.Slot<>("m", 2)), Map.of(input, 1L)));
            }
            result.add(new GraphRecipe<>("finish" + i, "finish" + i, List.of(new GraphRecipe.Slot<>(input, 3)), Map.of("target" + i, 1L)));
        }
        return result;
    }

    private static PlanningBudget budget() { return new PlanningBudget(0, 2000000, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
