// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Resource capacities, complete producer boundaries, changed stock/seeds and optional-aid lifetime. */
public final class QuantityPotentialTest {
    public static void main(String[] args) {
        for (int order = 0; order < 6; order++) {
            var recipes = network(260);
            Collections.shuffle(recipes, new Random(72801 + order));
            var compiler = new GraphCompiler<>(recipes);
            var stock = stock(1, 0, 260);
            var first = budget();
            check(analyze(compiler, 3, stock, Map.of(), Set.of(), Set.of(), first), "missed shared capacity");
            check(!compiler.quantityCertificates(Set.of()).isEmpty(), "exact weights were not retained");
            var warm = budget();
            check(analyze(compiler, 3, stock, Map.of(), Set.of(), Set.of(), warm), "lost cached capacity");
            check(warm.searchWork() < first.searchWork(), "cached proof redid the search");
            check(!analyze(compiler, 2, stock, Map.of(), Set.of(), Set.of(), budget()), "smaller feasible order rejected");
            check(!analyze(compiler, 3, stock(2, 0, 260), Map.of(), Set.of(), Set.of(), budget()), "new stock rejected");
            check(analyze(compiler, 3, stock(2, 0, 260), Map.of("A", 1L), Set.of(), Set.of(), budget()), "mandatory seed was omitted");
            check(!analyze(compiler, 3, stock(2, 0, 260), Map.of(), Set.of(), Set.of(), budget()), "old seed condition survived relaxation");
            check(!analyze(compiler, 3, stock, Map.of(), Set.of("B"), Set.of(), budget()), "external supply inherited a finite capacity");
            var plan = new GraphPlanner<>(compiler).plan("T", 2, stock, true, true, budget());
            check(plan.feasible(), "cached proof damaged executable boundary");
            PlanVerifier.verify(plan);
        }
        lateSourceAdmission();
        boundaries();
        longBoundary();
        lifetime();
        System.out.println("Quantity potential: permutations, stock/seed/external reuse, late source admission, full producer boundary, long precision and lifetime passed");
    }

    private static void lateSourceAdmission() {
        var recipes = new ArrayList<>(List.of(recipe("cycle-t", Map.of("X", 1L), Map.of("T", 1L)),
                recipe("cycle-x", Map.of("T", 1L), Map.of("X", 1L)), recipe("good-t", Map.of("c0", 1L), Map.of("T", 1L))));
        for (int i = 0; i < 2000; i++) recipes.add(recipe("chain" + i, Map.of("c" + (i + 1), 1L), Map.of("c" + i, 1L)));
        var b = new PlanningBudget(0, 900_000, 128L << 20, () -> false, System::nanoTime);
        var work = new GraphPlanningWork<>(new GraphCompiler<>(recipes), "T", 1, Map.of("c2000", 1L), true, true, b);
        try {
            while (!work.step()) {}
            var plan = work.result();
            check(plan.feasible(), "optional proof starved the funded later source: " + plan.result());
            PlanVerifier.verify(plan);
            PlanVerifier.verifyRuntimeInventory(plan);
        } finally { work.close(); }
        check(b.reservedBytes() == 0, "deferred proof admission leaked");
    }

    private static void boundaries() {
        var base = network(0);
        var extended = new ArrayList<>(base);
        extended.add(recipe("late", Map.of("R", 1L), Map.of("T", 1L)));
        var inventory = Map.of("A", 1L, "R", 3L);
        // Even if a caller hands the aid a restricted count model, the full
        // producer boundary prevents publishing its apparent resource proof.
        var budget = budget();
        try (var model = RecipeCountModel.forBounds(new GraphCompiler<>(base), "T", 3, inventory, Map.of(), Set.of(), Set.of(), budget);
             var aid = new QuantityPotential<>(model, "T", extended, budget)) {
            while (!aid.step()) {}
            check(aid.certificate().isEmpty(), "omitted producer became a global theorem");
        }
        check(budget.reservedBytes() == 0, "boundary check leaked");

        var compiler = new GraphCompiler<>(extended);
        var b = budget();
        try (var model = RecipeCountModel.forBounds(compiler, "T", 3, inventory, Map.of(), Set.of(), Set.of("late"), b);
             var aid = new QuantityPotential<>(model, "T", base, b)) {
            while (!aid.step()) {}
            check(!aid.certificate().isEmpty(), "excluded-source capacity was missed");
            compiler.rememberQuantityCertificate(Set.of("late"), aid.certificate());
        }
        check(b.reservedBytes() == 0, "excluded proof leaked");
        check(!analyze(compiler, 3, inventory, Map.of(), Set.of(), Set.of(), budget()), "excluded-source certificate escaped its scope");
        check(!analyze(new GraphCompiler<>(extended), 3, inventory, Map.of(), Set.of(), Set.of(), budget()), "new producer was ignored");

        var config = new GraphRecipe<>("configuration", "configuration", List.of(new GraphRecipe.Slot<>("C", 1, 0, true)), Map.of("T", 1L));
        var configBudget = budget();
        try (var model = RecipeCountModel.forBounds(new GraphCompiler<>(List.of(config)), "T", 3, Map.of("C", 1L), Map.of(), Set.of(), Set.of(), configBudget);
             var aid = new QuantityPotential<>(model, "T", List.of(config), configBudget)) {
            while (!aid.step()) {}
            check(aid.certificate().isEmpty(), "per-push configuration became per-item consumption");
        }
        check(configBudget.reservedBytes() == 0, "configuration check leaked");
    }

    private static void longBoundary() {
        var recipes = network(0);
        for (long extra : new long[]{0, 1, 2}) {
            var b = budget();
            try (var model = RecipeCountModel.forBounds(new GraphCompiler<>(recipes), "T", Long.MAX_VALUE,
                    Map.of("A", (Long.MAX_VALUE - 1) / 2, "B", extra), Map.of(), Set.of(), Set.of(), b);
                 var aid = new QuantityPotential<>(model, "T", recipes, b)) {
                while (!aid.step()) {}
                check(aid.certificate().isEmpty() == (extra != 0), "long capacity rounded across a one-unit boundary");
            }
            check(b.reservedBytes() == 0, "long check leaked");
        }
    }

    private static void lifetime() {
        var recipes = network(260);
        for (int stop = 0; stop < 48; stop++) {
            var cancelled = new AtomicBoolean();
            var b = new PlanningBudget(0, 4_000_000, 128L << 20, cancelled::get, System::nanoTime);
            try (var model = RecipeCountModel.forBounds(new GraphCompiler<>(recipes), "T", 3, stock(1, 0, 260), Map.of(), Set.of(), Set.of(), b);
                 var aid = new QuantityPotential<>(model, "T", recipes, b)) {
                boolean done = false;
                for (int i = 0; i < stop && !done; i++) done = aid.step();
                if (!done) {
                    cancelled.set(true);
                    try { aid.step(); throw new AssertionError("cancellation ignored"); }
                    catch (CancellationException expected) {}
                }
                aid.close();
            }
            check(b.reservedBytes() == 0, "interrupted aid leaked at " + stop);
        }
        var b = budget();
        try (var model = RecipeCountModel.forBounds(new GraphCompiler<>(recipes), "T", 3, stock(1, 0, 260), Map.of(), Set.of(), Set.of(), b)) {
            long held = b.availableBytes() - 512;
            b.reserve(held);
            try (var aid = new QuantityPotential<>(model, "T", recipes, b)) {
                while (!aid.step()) {}
                check(aid.certificate().isEmpty(), "memory-declined aid invented a proof");
            } finally { b.release(held); }
        }
        check(b.reservedBytes() == 0, "optional memory decline leaked");
    }

    private static boolean analyze(GraphCompiler<String> compiler, long amount, Map<String, Long> stock,
                                   Map<String, Long> seeds, Set<String> external, Set<String> excluded, PlanningBudget b) {
        var q = new QuantityAnalysis<>(compiler, "T", amount, stock, external, seeds, excluded, b, true);
        try { while (!q.step()) {} return q.blocked(); }
        finally { q.discard(); check(b.reservedBytes() == 0, "quantity analysis leaked"); }
    }

    private static ArrayList<GraphRecipe<String>> network(int size) {
        var result = new ArrayList<>(List.of(recipe("split", Map.of("A", 1L), Map.of("B", 2L)),
                recipe("join", Map.of("B", 2L), Map.of("A", 1L)), recipe("make", Map.of("B", 1L), Map.of("T", 1L))));
        for (int i = 0; i < size; i++) result.add(recipe("choice" + i, Map.of("B", 1L, "C" + i, 1L), Map.of("T", 1L, "C" + i, 1L)));
        return result;
    }

    private static Map<String, Long> stock(long a, long b, int size) {
        var result = new LinkedHashMap<String, Long>(); result.put("A", a); result.put("B", b);
        for (int i = 0; i < size; i++) result.put("C" + i, 1L);
        return result;
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 4_000_000, 128L << 20, () -> false, System::nanoTime); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
