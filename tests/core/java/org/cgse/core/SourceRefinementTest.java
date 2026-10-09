// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/** Failed stock views must repair the relevant source without losing funded consumers. */
public final class SourceRefinementTest {
    private static int verified;

    public static void main(String[] args) {
        for (int order = 0; order < 12; order++) {
            for (boolean preserve : new boolean[] { false, true }) {
                for (long need : new long[] { 2, 17, 1000 }) seedProducer(order, preserve, need);
                unresolvedRegion(order, preserve);
            }
        }
        cancellationAndAdmission();
        System.out.println("Source refinement: " + verified + " verified plans, seed/UNKNOWN repairs, changed stock, exclusions and cancellation passed");
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        return new GraphRecipe<>(id, id, inputs.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), outputs);
    }

    private static GraphCompiler<String> seedCompiler(int order, long need) {
        var recipes = new ArrayList<>(List.of(recipe("target", Map.of("A", need), Map.of("T", 1L)),
                recipe("back", Map.of("B", 1L), Map.of("A", 1L)),
                recipe("forward", Map.of("A", 1L), Map.of("B", 1L)),
                recipe("funded", Map.of("ore", 1L), Map.of("B", 2L))));
        Collections.shuffle(recipes, new Random(order));
        // Keep the troublesome equal-priority source first while shuffling the
        // catalog and other iteration order. This is the complete four-recipe model.
        var sources = new LinkedHashMap<String, List<GraphRecipe<String>>>();
        for (var r : recipes) for (String key : r.outputs().keySet()) sources.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        sources.get("B").sort(Comparator.comparingInt(r -> r.id().equals("forward") ? 0 : 1));
        return new GraphCompiler<>(recipes, sources);
    }

    private static PlanningBudget budget() { return new PlanningBudget(0, 2_000_000, 64L << 20, () -> false, System::nanoTime); }

    private static GraphStockViewWork<String> view(GraphCompiler<String> compiler, Map<String, Long> stock, Set<String> excluded,
                                                   boolean preserve, PlanningBudget budget) {
        return new GraphStockViewWork<>(compiler, "T", 1, stock, Set.of(), Map.of(), excluded, preserve, true,
                CatalystPolicy.MINIMAL, budget, System.nanoTime(), 0, () -> null);
    }

    private static void seedProducer(int order, boolean preserve, long need) {
        var compiler = seedCompiler(order, need);
        var stock = Map.of("ore", need, "A", 1L);
        var budget = budget();
        try (var view = view(compiler, stock, Set.of(), preserve, budget)) {
            while (!view.step()) {}
            check(budget.diagnostics().contains("result=MISSING_SEED"), "Fixture did not reach seed repair");
            verify(view.result(), stock, budget);
            check(view.result().patternTimesExact().containsKey("back") && view.result().patternTimesExact().containsKey("funded"),
                    "Necessary consumer or alternative seed source lost");
        }
        check(budget.reservedBytes() == 0, "Seed repair leaked");
        // Request-local failures/preferences must not escape into the compiler.
        for (boolean excluded : new boolean[] { false, true }) {
            var no = budget();
            var available = excluded ? stock : Map.of("A", 1L);
            try (var view = view(compiler, available, excluded ? Set.of("funded") : Set.of(), preserve, no)) {
                while (!view.step()) {}
                check(view.result() == null, "Stale inventory/excluded source supplied a witness");
            }
            check(no.reservedBytes() == 0, "Declined source repair leaked");
        }
        var fresh = budget();
        try (var view = view(compiler, stock, Set.of(), preserve, fresh)) {
            while (!view.step()) {}
            verify(view.result(), stock, fresh);
        }
        check(fresh.reservedBytes() == 0, "Restored inventory leaked");
    }

    private static void unresolvedRegion(int order, boolean preserve) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        var inputs = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 8; i++) {
            inputs.put("A" + i, 2L);
            recipes.add(recipe("cycle" + i, Map.of("A" + ((i + 1) % 8), 1L), Map.of("A" + i, 1L)));
        }
        inputs.put("U", 1L);
        recipes.add(recipe("independent", Map.of("ore", 1L), Map.of("U", 1L)));
        recipes.add(recipe("target", inputs, Map.of("T", 1L)));
        Collections.shuffle(recipes, new Random(order));
        recipes.add(recipe("funded", Map.of("ore", 1L), Map.of("A0", 2L)));
        var compiler = new GraphCompiler<>(recipes);
        var stock = Map.of("ore", 1000L);
        var budget = budget();
        try (var view = view(compiler, stock, Set.of(), preserve, budget)) {
            while (!view.step()) {}
            check(budget.diagnostics().contains("repair_unresolved_region"), "Fixture did not retain the unresolved region");
            verify(view.result(), stock, budget);
            check(view.result().patternTimesExact().containsKey("independent"), "Unrelated branch lost during local repair");
        }
        check(budget.reservedBytes() == 0, "UNKNOWN repair leaked");
    }

    private static void cancellationAndAdmission() {
        var compiler = seedCompiler(0, 17);
        var stock = Map.of("ore", 1000L, "A", 1L);
        for (int stop = 1; stop <= 300; stop++) {
            int limit = stop;
            var calls = new AtomicInteger();
            var budget = new PlanningBudget(0, 2_000_000, 64L << 20, () -> calls.incrementAndGet() >= limit, System::nanoTime);
            try (var view = view(compiler, stock, Set.of(), true, budget)) {
                while (!view.step()) {}
            } catch (CancellationException expected) {}
            check(budget.reservedBytes() == 0, "Cancellation leaked at " + stop);
        }
        for (long memory : new long[] { 1, 1024, 16_384, 64L << 20 }) {
            var budget = new PlanningBudget(0, 1000, memory, () -> false, System::nanoTime);
            try (var view = view(compiler, stock, Set.of(), true, budget)) {
                while (!view.step()) {}
                if (view.result() != null) PlanVerifier.verify(view.result());
            } catch (PlanningBudget.Exhausted expected) {}
            check(budget.reservedBytes() == 0, "Admission/work cutoff leaked");
        }
    }

    private static void verify(GraphPlan<String> plan, Map<String, Long> stock, PlanningBudget budget) {
        check(plan != null && plan.feasible(), "Missing funded witness: " + budget.diagnostics());
        PlanVerifier.verify(plan);
        PlanVerifier.verifyRuntimeInventory(plan);
        for (var entry : plan.initialExact().entrySet())
            check(entry.getValue().compareTo(BigInteger.valueOf(stock.getOrDefault(entry.getKey(), 0L))) <= 0, "Stock overdraw");
        try (var check = new PlanVerification<>(plan, budget)) {
            while (!check.step()) {}
            try (var proof = new ForceCraftProof<>(plan, check, Map.of(), budget)) {
                while (!proof.step()) {}
                check(proof.proved(), "Unproductive target turnover");
            }
        }
        verified++;
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
