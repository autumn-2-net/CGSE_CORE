// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;
import java.util.concurrent.CancellationException;

/** Optional seed repair must not turn incidental output into forced-production obligations. */
public final class CandidateProductionContractTest {
    private static int checks;

    public static void main(String[] args) {
        for (long turns : new long[]{1, 3, 7}) {
            for (boolean reverse : new boolean[]{false, true}) {
                for (boolean preserve : new boolean[]{false, true}) {
                    turnoverContract(turns, reverse, preserve);
                    productiveGrowth(turns, reverse, preserve);
                }
            }
        }
        cancellation();
        System.out.println("Candidate production contract: " + checks
                + " checks; enhanced seed obligations, genuine growth and cancellation passed");
    }

    private static void turnoverContract(long turns, boolean reverse, boolean preserve) {
        var make = recipe("make", Map.of("ore", 1L), Map.of("T", 1L));
        var turn = recipe("turn", Map.of("T", 1L), Map.of("T", 1L, "bonus", 1L));
        var recipes = catalog(reverse, make, turn);
        var word = new PlanStep.Sequence(List.of(new PlanStep.Batch("make", 1),
                new PlanStep.Batch("turn", turns)));
        var stock = Map.of("T", turns, "ore", 1L);
        var enhanced = Map.of("bonus", turns);

        // An independent primitive execution gives the same requested terminal
        // T with only one freshly produced T. The turnover word is therefore
        // unnecessary unless bonus was part of the caller's original request.
        var reduced = execute(List.of(make), new long[]{1}, stock);
        check(reduced.stock.getOrDefault("T", 0L) == turns + 1, "reduced terminal target");
        check(reduced.produced.getOrDefault("T", 0L) < turns + 1, "counterexample target production");
        var complete = execute(List.of(make, turn), new long[]{1, turns}, stock);
        check(complete.stock.getOrDefault("bonus", 0L) == turns, "complete enhanced seed goal");
        check(complete.produced.getOrDefault("T", 0L) == turns + 1, "complete physical target output");

        var budget = budget();
        try (var candidate = candidate(word, recipes, turns + 1, stock, enhanced, preserve, budget)) {
            candidate.productionContract(Map.of());
            finish(candidate);
            check(candidate.plan == null, "optional bonus retention proved gratuitous target turnover necessary");
        }
        check(budget.reservedBytes() == 0, "rejected enhanced contract leaked");

        // This is a positive control: bonus really is a caller obligation.
        budget = budget();
        try (var candidate = candidate(word, recipes, turns + 1, stock, enhanced, preserve, budget)) {
            candidate.productionContract(enhanced);
            finish(candidate);
            check(candidate.plan != null && candidate.plan.feasible(), "original bonus contract was weakened");
            PlanVerifier.verify(candidate.plan);
        }
        check(budget.reservedBytes() == 0, "accepted original contract leaked");

        // The caller may reuse its mutable map after candidate construction.
        budget = budget();
        try (var candidate = candidate(word, recipes, turns + 1, stock, enhanced, preserve, budget)) {
            var original = new LinkedHashMap<String, Long>();
            candidate.productionContract(original);
            original.putAll(enhanced);
            finish(candidate);
            check(candidate.plan == null, "caller mutation changed the frozen production contract");
        }
        check(budget.reservedBytes() == 0, "mutable contract case leaked");
    }

    private static void productiveGrowth(long runs, boolean reverse, boolean preserve) {
        var grow = recipe("grow", Map.of("T", 1L, "ore", 1L), Map.of("T", 2L));
        var spare = recipe("spare", Map.of("unused", 1L), Map.of("other", 1L));
        var recipes = catalog(reverse, grow, spare);
        long targetSeed = preserve ? 1L : 0L;
        var stock = Map.of("T", 1L + targetSeed, "ore", runs, "keep", 1L);
        var exact = execute(List.of(grow), new long[]{runs}, stock);
        check(exact.stock.getOrDefault("T", 0L) == runs + 1 + targetSeed, "productive terminal target");
        check(exact.produced.getOrDefault("T", 0L) >= runs + 1, "productive physical target output");
        for (long fewer = 0; fewer < runs; fewer++) {
            var reduced = execute(List.of(grow), new long[]{fewer}, stock);
            check(reduced.stock.getOrDefault("T", 0L) < runs + 1 + targetSeed,
                    "growth counterexample should miss target plus original seed goal");
        }
        var budget = budget();
        try (var candidate = candidate(new PlanStep.Batch("grow", runs), recipes,
                runs + 1, stock, preserve ? Map.of("keep", 1L, "T", 1L) : Map.of("keep", 1L), preserve, budget)) {
            candidate.productionContract(preserve ? Map.of("T", 1L) : Map.of());
            finish(candidate);
            check(candidate.plan != null && candidate.plan.feasible(),
                    "genuine forced growth with net target gain below request was rejected: runs="
                            + runs + ", reverse=" + reverse + ", preserve=" + preserve
                            + ", stage=" + candidate.stage);
            PlanVerifier.verify(candidate.plan);
        }
        check(budget.reservedBytes() == 0, "productive growth leaked");
    }

    private static void cancellation() {
        var recipes = catalog(false, recipe("make", Map.of("ore", 1L), Map.of("T", 1L)),
                recipe("turn", Map.of("T", 1L), Map.of("T", 1L, "bonus", 1L)));
        var budget = budget();
        var candidate = candidate(new PlanStep.Sequence(List.of(new PlanStep.Batch("make", 1),
                        new PlanStep.Batch("turn", 3))), recipes, 4,
                Map.of("T", 3L, "ore", 1L), Map.of("bonus", 3L), false, budget)
                .productionContract(Map.of());
        try {
            int steps = 0;
            while (candidate.stage != 5 && !candidate.step()) {
                check(++steps < 10000, "candidate did not reach forced-production verification");
            }
            check(candidate.stage == 5, "candidate skipped forced-production verification");
            budget.cancel();
            boolean cancelled = false;
            try { candidate.step(); } catch (CancellationException expected) { cancelled = true; }
            check(cancelled, "production verification ignored cancellation");
        } finally {
            candidate.close();
            candidate.close();
        }
        check(budget.reservedBytes() == 0, "cancelled candidate leaked");
    }

    private static AllocationSearch.Candidate<String> candidate(PlanStep word,
            Map<String, GraphRecipe<String>> recipes, long amount, Map<String, Long> stock,
            Map<String, Long> seeds, boolean preserve, PlanningBudget budget) {
        return new AllocationSearch.Candidate<>(word, recipes, "T", amount, stock,
                seeds, Set.of(), preserve, true, false, budget, System.nanoTime());
    }

    private static void finish(AllocationSearch.Candidate<String> candidate) {
        int steps = 0;
        while (!candidate.step()) {
            if (++steps > 10000) throw new AssertionError("candidate did not finish");
        }
    }

    @SafeVarargs
    private static Map<String, GraphRecipe<String>> catalog(boolean reverse, GraphRecipe<String>... recipes) {
        var result = new LinkedHashMap<String, GraphRecipe<String>>();
        for (int index = 0; index < recipes.length; index++) {
            var recipe = recipes[reverse ? recipes.length - index - 1 : index];
            result.put(recipe.id(), recipe);
        }
        return result;
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        var slots = new ArrayList<GraphRecipe.Slot<String>>();
        for (var entry : inputs.entrySet()) slots.add(new GraphRecipe.Slot<>(entry.getKey(), entry.getValue()));
        return new GraphRecipe<>(id, id, slots, outputs);
    }

    private record Execution(Map<String, Long> stock, Map<String, Long> produced) {}

    /** Deliberately independent from plan summaries and verification. */
    private static Execution execute(List<GraphRecipe<String>> recipes, long[] runs, Map<String, Long> initial) {
        var stock = new HashMap<>(initial);
        var produced = new HashMap<String, Long>();
        for (int index = 0; index < recipes.size(); index++) {
            var recipe = recipes.get(index);
            for (long run = 0; run < runs[index]; run++) {
                for (var input : recipe.inputs().entrySet()) {
                    long remaining = stock.getOrDefault(input.getKey(), 0L) - input.getValue();
                    if (remaining < 0) throw new AssertionError("primitive witness cannot start " + recipe.id());
                    stock.put(input.getKey(), remaining);
                }
                for (var output : recipe.outputs().entrySet()) {
                    stock.merge(output.getKey(), output.getValue(), Long::sum);
                    produced.merge(output.getKey(), output.getValue(), Long::sum);
                }
            }
        }
        return new Execution(stock, produced);
    }

    private static PlanningBudget budget() {
        return new PlanningBudget(0, 1_000_000, 64L << 20, () -> false, System::nanoTime);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
