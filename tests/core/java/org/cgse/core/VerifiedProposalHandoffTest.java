// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.*;

/** A completed internal witness survives transfer to repeated final verification. */
public final class VerifiedProposalHandoffTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        for (boolean preserve : List.of(false, true)) {
            handoff("integer", preserve, false);
            for (String producer : List.of("integer", "allocation", "allocation_scout", "demand", "cyclic"))
                handoff(producer, preserve, true);
        }
        ordinaryProposalsStayUnverified();
        missingCountsStayMissing();
        System.out.println("VerifiedProposalHandoffTest PASS: checks=" + checks);
    }

    private static void handoff(String producer, boolean preserve, boolean transfer) throws Exception {
        var recipe = recipe();
        var compiler = new GraphCompiler<>(List.of(recipe));
        var stock = Map.of("raw", 1L, "seed", 1L);
        var seeds = preserve ? Map.of("seed", 1L) : Map.<String, Long>of();
        var budget = budget();
        var owner = new GraphPlanningWork<>(compiler, "goal", 1, stock, Set.of(), seeds, preserve, true, budget);
        GraphPlan<String> found;
        try {
            switch (producer) {
                case "integer" -> {
                    var search = new IntegerCountSearch<>(compiler, "goal", 1, stock, seeds, Set.of(), Set.of(),
                            preserve, true, budget, System.nanoTime());
                    set(owner, "countSearch", search); set(owner, "phase", 14);
                    while (!search.step()) {}
                    found = search.result();
                }
                case "allocation", "allocation_scout" -> {
                    var search = new AllocationSearch<>(compiler, "goal", 1, stock, Set.of(), seeds,
                            preserve, true, Set.of(), budget, System.nanoTime());
                    set(owner, "allocating", search);
                    set(owner, "phase", producer.equals("allocation") ? 6 : 18);
                    set(owner, "allocationTurnAllowance", Long.MAX_VALUE);
                    set(owner, "allocationScoutAllowance", Long.MAX_VALUE);
                    while (!search.step()) {}
                    found = search.result();
                }
                case "demand" -> {
                    var search = new CountDemandSearch<>(compiler, "goal", 1, stock, Set.of(), seeds,
                            Set.of(), preserve, true, budget, System.nanoTime());
                    set(owner, "countDemand", search); set(owner, "phase", 23);
                    while (!search.step()) {}
                    found = search.result();
                }
                case "cyclic" -> {
                    var search = new CountCyclicStockSearch<>(compiler, "goal", 1, stock, Set.of(), seeds,
                            Set.of(), preserve, true, budget, System.nanoTime());
                    set(owner, "cyclicStockCounts", search); set(owner, "phase", 24);
                    while (!search.step()) {}
                    found = search.result();
                }
                default -> throw new AssertionError(producer);
            }
            check(found != null && found.feasible(), "Control producer did not finish: " + producer);
            execute(found, stock);
            if (transfer) {
                check(!owner.step() && get(owner, "phase").equals(4), "Control skipped the handoff: " + producer);
                check(get(owner, "candidate") == found, "Different candidate transferred");
            }
            long work = budget.nodes();
            for (var limit : List.of(PlanningBudget.Limit.SEARCH_LIMIT, PlanningBudget.Limit.TIMEOUT,
                    PlanningBudget.Limit.MEMORY_LIMIT)) {
                var retained = owner.limited(new PlanningBudget.Exhausted(limit));
                check(retained.feasible() && retained.missingExact().isEmpty(),
                        "Verified result lost at " + producer + "/" + transfer + "/" + limit);
                check(retained.steps() == found.steps() && retained.seeds().equals(found.seeds()), "Retained witness changed");
                execute(retained, stock);
                check(budget.nodes() == work, "Deadline harvesting started additional search or verification");
            }
            if (transfer) {
                budget.cancel();
                try { owner.step(); throw new AssertionError("Verified incumbent bypassed cancellation"); }
                catch (java.util.concurrent.CancellationException expected) { checks++; }
            }
        } finally { owner.close(); }
        check(budget.reservedBytes() == 0, "Handoff retained workspace: " + producer);
    }

    private static void ordinaryProposalsStayUnverified() throws Exception {
        for (var status : List.of(GraphPlan.Result.FEASIBLE, GraphPlan.Result.MISSING_INPUT, GraphPlan.Result.UNKNOWN))
            for (var origin : List.of(PlanningBudget.CandidateOrigin.SOURCE_GRAPH, PlanningBudget.CandidateOrigin.INTEGER_COUNTS,
                    PlanningBudget.CandidateOrigin.ALLOCATION, PlanningBudget.CandidateOrigin.STOCK_VIEW)) {
            var budget = budget();
            var owner = new GraphPlanningWork<>(new GraphCompiler<>(List.of(recipe())), "goal", 1,
                    Map.of("raw", 1L, "seed", 1L), false, true, budget);
            try {
                // Even a candidate carrying a FEASIBLE label is not a trusted
                // internal handoff. This empty word has no forced production.
                var ordinary = new GraphPlan<String>("goal", 1, false, new PlanStep.Sequence(List.of()), Map.of(),
                        Map.of("goal", BigInteger.ONE), Map.of(),
                        status == GraphPlan.Result.MISSING_INPUT ? Map.of("goal", BigInteger.ONE) : Map.of(),
                        status, 0, 0);
                set(owner, "candidate", ordinary); set(owner, "phase", 4);
                set(owner, "candidateOrigin", origin);
                var result = owner.limited(new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT));
                check(!result.feasible(), "Unverified ordinary candidate became an incumbent: " + status);
            } finally { owner.close(); }
            check(budget.reservedBytes() == 0, "Unverified control leaked workspace");
        }
    }

    private static void missingCountsStayMissing() throws Exception {
        var compiler = new GraphCompiler<>(List.of(recipe()));
        var budget = budget();
        var owner = new GraphPlanningWork<>(compiler, "goal", 1, Map.of("seed", 1L), false, true, budget);
        try {
            var search = new IntegerCountSearch<>(compiler, "goal", 1, Map.of("seed", 1L), Map.of(),
                    Set.of(), Set.of(), false, true, budget, System.nanoTime());
            set(owner, "countSearch", search); set(owner, "phase", 14);
            while (!search.step()) {}
            check(search.result() == null, "Unfunded control produced a witness");
            var result = owner.limited(new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT));
            check(!result.feasible(), "A negative count result became an incumbent");
        } finally { owner.close(); }
        check(budget.reservedBytes() == 0, "Negative count control leaked workspace");
    }

    private static GraphRecipe<String> recipe() {
        return new GraphRecipe<>("craft", "craft", List.of(new GraphRecipe.Slot<>("raw", 1),
                new GraphRecipe.Slot<>("seed", 1)), Map.of("goal", 1L, "seed", 1L));
    }

    private static PlanningBudget budget() { return new PlanningBudget(0, 8_000_000, 64L << 20, () -> false, System::nanoTime); }

    /** Independent explicit interpreter; this control has only one recipe run. */
    private static void execute(GraphPlan<String> plan, Map<String, Long> stock) {
        var held = new HashMap<>(plan.initialExact());
        held.forEach((key, amount) -> check(amount.compareTo(BigInteger.valueOf(stock.getOrDefault(key, 0L))) <= 0,
                "Plan borrowed nonexistent inventory"));
        execute(plan.steps(), plan.recipes(), held);
        check(held.getOrDefault("goal", BigInteger.ZERO).compareTo(BigInteger.valueOf(plan.amount())) >= 0, "Missing delivery");
        plan.seeds().forEach((key, amount) -> check(held.getOrDefault(key, BigInteger.ZERO).compareTo(BigInteger.valueOf(amount)) >= 0,
                "Missing terminal seed"));
    }

    private static void execute(PlanStep step, Map<String, GraphRecipe<String>> recipes, Map<String, BigInteger> held) {
        if (step instanceof PlanStep.Batch batch) {
            check(batch.runs() <= 4, "Control unexpectedly requires a long execution");
            var recipe = recipes.get(batch.recipe());
            for (long run = 0; run < batch.runs(); run++) {
                for (var input : recipe.inputs().entrySet()) {
                    BigInteger amount = BigInteger.valueOf(input.getValue());
                    check(held.getOrDefault(input.getKey(), BigInteger.ZERO).compareTo(amount) >= 0, "Unfunded prefix");
                    held.merge(input.getKey(), amount.negate(), BigInteger::add);
                }
                recipe.outputs().forEach((key, amount) -> held.merge(key, BigInteger.valueOf(amount), BigInteger::add));
            }
        } else if (step instanceof PlanStep.Sequence sequence) {
            sequence.children().forEach(child -> execute(child, recipes, held));
        } else if (step instanceof PlanStep.Repeat repeat) {
            check(repeat.times() <= 4, "Control unexpectedly repeats a long execution");
            for (long n = 0; n < repeat.times(); n++) execute(repeat.body(), recipes, held);
        }
    }

    private static Object get(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    private static void check(boolean condition, String detail) { checks++; if (!condition) throw new AssertionError(detail); }
}
