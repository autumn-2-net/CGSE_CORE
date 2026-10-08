// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;

/** First visits must reach the other root algorithms without losing retained searches. */
public final class RootPortfolioTest {
    public static void main(String[] args) throws Exception {
        lpFirstVisit();
        for (int choices : new int[] { 3, 4 }) for (int permutation = 0; permutation < 3; permutation++) {
            var recipes = choices(20, choices, 719);
            Collections.shuffle(recipes, new Random(permutation));
            var budget = budget(20_000_000);
            var compiler = new GraphCompiler<>(recipes);
            var plan = new GraphPlanner<>(compiler).plan("GOAL", 1, stock(20, 1), false, true, budget);
            verify(plan, recipes, stock(20, 1), 1);
            check(budget.reservedBytes() == 0 && compiler.cacheMetrics().activeSearches() == 0, "Root search leaked");
        }
        parallelRequests(false);
        parallelRequests(true);
        System.out.println("Root portfolio: bounded LP first visit, retained cancellation, six source orders and eight scheduled quantity requests passed");
    }

    private static PlanningBudget budget(long work) {
        return new PlanningBudget(0, work, 256L << 20, () -> false, System::nanoTime);
    }

    private static void lpFirstVisit() {
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        var random = new Random(17);
        var lower = new BigInteger[32];
        var upper = new BigInteger[32];
        Arrays.fill(lower, BigInteger.ZERO);
        Arrays.fill(upper, BigInteger.ONE);
        for (int d = 0; d < 2; d++) {
            var terms = new LinkedHashMap<Integer, BigInteger>();
            BigInteger target = BigInteger.ZERO;
            for (int i = 0; i < lower.length; i++) {
                var coefficient = BigInteger.valueOf(1 + random.nextInt(1000));
                terms.put(i, coefficient);
                if (i % 3 == 0) target = target.add(coefficient);
            }
            rows.add(new ExactLinearProgram.Constraint(terms, target));
            var negative = new LinkedHashMap<Integer, BigInteger>();
            terms.forEach((id, coefficient) -> negative.put(id, coefficient.negate()));
            rows.add(new ExactLinearProgram.Constraint(negative, target.negate()));
        }
        var budget = budget(20_000_000);
        try (var reduction = new CountReduction(rows, lower, upper, budget)) {
            while (!reduction.step()) {}
            try (var search = CountLpSearch.create(reduction, lower.length, budget)) {
                check(search != null, "Weighted LP view was not admitted");
                long before = budget.searchWork();
                while (!search.step()) {}
                check(budget.searchWork() - before < 1_000_000, "First LP visit monopolized the root budget");
                check(search.retained() && search.counts() == null && !search.infeasible(), "Expected a retained, undecided LP frontier");
                before = budget.searchWork();
                search.resume(32768);
                while (!search.step()) {}
                check(budget.searchWork() > before, "Retained LP made no further progress");
                if (search.counts() != null) verifyRows(rows, lower, upper, search.counts());
                if (search.retained()) {
                    budget.cancel();
                    boolean cancelled = false;
                    try {
                        search.resume(4096);
                        while (!search.step()) {}
                    } catch (CancellationException expected) { cancelled = true; }
                    check(cancelled, "Retained LP ignored cancellation");
                }
            }
        }
        check(budget.reservedBytes() == 0, "Retained LP leaked after cancellation/close");
    }

    private static void verifyRows(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high, BigInteger[] values) {
        for (int i = 0; i < values.length; i++)
            check(values[i].compareTo(low[i]) >= 0 && values[i].compareTo(high[i]) <= 0, "LP domain violation");
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(values[term.getKey()]));
            check(sum.compareTo(row.upper()) <= 0, "LP row violation");
        }
    }

    private static void parallelRequests(boolean expanded) throws Exception {
        var recipes = choices(12, 3, 913);
        var compiler = new GraphCompiler<>(recipes);
        try (var scheduler = new PlanningScheduler(4, 8, 1024, 2_000_000)) {
            for (long amount : new long[] { 1, 2, 16, 1000 }) {
                long cap = PlanningBudget.parallelWorkLimit(20_000_000, 4, expanded);
                var budget = budget(cap);
                var stock = stock(12, amount);
                var work = new GraphPlanningWork<>(compiler, "GOAL", amount, stock, false, true, budget);
                var plan = scheduler.submit(work, budget).get(60, TimeUnit.SECONDS);
                check(plan.feasible(), "Scheduled amount=" + amount + "; expanded=" + expanded + "; result=" + plan.result() + "; " + budget.diagnostics());
                verify(plan, recipes, stock, amount);
                check(budget.searchWork() <= cap && budget.compilationWork() <= cap, "Scheduled request exceeded shared budget");
                check(budget.reservedBytes() == 0 && compiler.cacheMetrics().activeSearches() == 0, "Scheduled request leaked");
            }
        }
    }

    private static List<GraphRecipe<String>> choices(int size, int categories, long seed) {
        var random = new Random(seed);
        var recipes = new ArrayList<GraphRecipe<String>>();
        var goal = new LinkedHashMap<String, Long>();
        for (int i = 0; i < size; i++) {
            long first = 1 + random.nextInt(1000), second = 1 + random.nextInt(1000);
            int chosen = random.nextInt(categories);
            for (int category = 0; category < categories; category++) {
                String id = "r" + i + "c" + category;
                var outputs = new LinkedHashMap<String, Long>();
                outputs.put("C" + category + "_0", first);
                outputs.put("C" + category + "_1", second);
                recipes.add(new GraphRecipe<>(id, id, List.of(new GraphRecipe.Slot<>("U" + i, 1)), outputs));
                if (category == chosen) outputs.forEach((key, amount) -> goal.merge(key, amount, Long::sum));
            }
        }
        recipes.add(new GraphRecipe<>("finish", "finish", goal.entrySet().stream()
                .map(entry -> new GraphRecipe.Slot<>(entry.getKey(), entry.getValue())).toList(), Map.of("GOAL", 1L)));
        return recipes;
    }

    private static Map<String, Long> stock(int size, long amount) {
        var stock = new LinkedHashMap<String, Long>();
        for (int i = 0; i < size; i++) stock.put("U" + i, amount);
        return stock;
    }

    private static void verify(GraphPlan<String> plan, List<GraphRecipe<String>> recipes, Map<String, Long> stock, long amount) {
        check(plan.feasible(), "Known SAT choice graph returned " + plan.result());
        var primitives = new LinkedHashMap<String, GraphRecipe<String>>();
        recipes.forEach(recipe -> primitives.put(recipe.id(), recipe));
        // Independent primitive-prefix arithmetic, not the production scheduler.
        var summary = PrimitivePlanOracle.summary(plan.steps(), primitives, new IdentityHashMap<>());
        summary.need().forEach((key, need) -> check(BigInteger.valueOf(stock.getOrDefault(key, 0L)).compareTo(need) >= 0,
                "Unfunded execution prefix: " + key));
        check(summary.delta().getOrDefault("GOAL", BigInteger.ZERO).compareTo(BigInteger.valueOf(amount)) >= 0,
                "Missing fresh goal production");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
