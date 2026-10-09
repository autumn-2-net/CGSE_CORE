// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Comparable test entries; main-counts is the integer stage, not GraphPlanner. */
final class CountModelReplay {
    record Result(BigInteger[] counts, boolean infeasible) {}
    static final class Unsupported extends RuntimeException {
        Unsupported(String reason) { super(reason); }
    }

    static Result solve(String engine, List<ExactLinearProgram.Constraint> rows, BigInteger[] low,
                        BigInteger[] high, PlanningBudget budget, long maximumWork) {
        if (engine.equals("lcg-first") || engine.equals("lcg-retained")) {
            boolean retained = engine.equals("lcg-retained");
            try (var search = new CountLcg(rows, low, high, budget, retained ? 262144 : maximumWork)) {
                do {
                    long before = budget.nodes();
                    while (!search.step()) {}
                    if (!retained || search.counts() != null || search.infeasible() || !search.paused() ||
                            budget.remainingWork() < 1024 || before == budget.nodes())
                        return new Result(search.counts(), search.infeasible());
                    search.resume(Math.min(262144, budget.remainingWork()));
                } while (true);
            }
        }
        if (engine.equals("views")) {
            try (var models = CountModelViews.create(rows, low, high, budget)) {
                if (models == null) return new Result(null, false);
                models.compileLight();
                try (var search = new CountViewSearch(models, budget)) {
                    do {
                        long before = budget.nodes();
                        search.resume(Math.min(262144, budget.remainingWork()));
                        while (!search.step()) {}
                        if (search.counts() != null || search.infeasible() || !search.retained() ||
                                budget.remainingWork() < 8192 || before == budget.nodes())
                            return new Result(search.counts(), search.infeasible());
                    } while (true);
                }
            }
        }
        if (!engine.equals("main-counts")) throw new IllegalArgumentException("Unknown model engine: " + engine);
        var embedded = new Embedding(rows, low, high);
        try (var search = new IntegerCountSearch<>(new GraphCompiler<>(embedded.recipes), "target", 1,
                embedded.stock, Map.of(), Set.of(), Set.of(), false, true, budget, System.nanoTime())) {
            do {
                long before = budget.nodes();
                while (!search.step()) {}
                var plan = search.result();
                if (plan != null && plan.feasible()) {
                    // Check the execution against the original primitive arcs,
                    // independently of SequenceSummary and the solver verifier.
                    var primitives = new HashMap<String, GraphRecipe<String>>();
                    embedded.recipes.forEach(recipe -> primitives.put(recipe.id(), recipe));
                    var summary = GraphFixtureRegression.summary(plan.steps(), primitives, new IdentityHashMap<>());
                    for (var need : summary.need().entrySet()) {
                        if (BigInteger.valueOf(embedded.stock.getOrDefault(need.getKey(), 0L)).compareTo(need.getValue()) < 0 ||
                                plan.initialExact().getOrDefault(need.getKey(), BigInteger.ZERO).compareTo(need.getValue()) < 0)
                            throw new AssertionError("Unfunded original model prefix: " + need);
                    }
                    if (summary.delta().getOrDefault("target", BigInteger.ZERO).compareTo(BigInteger.ONE) < 0)
                        throw new AssertionError("Main count plan did not produce the target");
                    var witness = new BigInteger[low.length];
                    for (int i = 0; i < low.length; i++)
                        witness[i] = plan.patternTimesExact().getOrDefault("var" + i, BigInteger.ZERO).add(low[i]);
                    return new Result(witness, false);
                }
                if (search.infeasible() || !search.paused() || budget.remainingWork() < 8192 || before == budget.nodes())
                    return new Result(null, search.infeasible());
                search.resume();
            } while (true);
        }
    }

    /** Finite exact model -> recipes. Outside-domain conversions are never called UNSAT. */
    private static final class Embedding {
        final List<GraphRecipe<String>> recipes = new ArrayList<>();
        final Map<String, Long> stock = new LinkedHashMap<>();

        Embedding(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {
            var inputs = new ArrayList<List<GraphRecipe.Slot<String>>>();
            var outputs = new ArrayList<Map<String, Long>>();
            for (int i = 0; i < low.length; i++) {
                if (high[i] == null) throw new Unsupported("main-counts conversion requires finite upper bounds");
                if (high[i].compareTo(low[i]) < 0) throw new IllegalArgumentException("Invalid model domain");
                inputs.add(new ArrayList<>());
                outputs.add(new LinkedHashMap<>());
                long capacity = amount(high[i].subtract(low[i]));
                if (capacity > 0) stock.put("quota" + i, capacity);
                inputs.get(i).add(new GraphRecipe.Slot<>("quota" + i, 1));
                outputs.get(i).put("marker" + i, 1L);
            }
            var finish = new ArrayList<GraphRecipe.Slot<String>>();
            for (int r = 0; r < rows.size(); r++) {
                var row = rows.get(r);
                BigInteger bound = row.upper(), buffer = BigInteger.ZERO;
                for (var term : row.terms().entrySet()) {
                    int i = term.getKey();
                    BigInteger coefficient = term.getValue();
                    bound = bound.subtract(coefficient.multiply(low[i]));
                    if (coefficient.signum() > 0) buffer = buffer.add(coefficient.multiply(high[i].subtract(low[i])));
                }
                // Every variable recipe can run before finish. This buffer funds
                // all positive consumption, irrespective of the chosen order.
                buffer = buffer.max(bound).max(BigInteger.ZERO);
                long initial = amount(buffer), need = amount(buffer.subtract(bound));
                if (initial > 0) stock.put("row" + r, initial);
                if (need > 0) finish.add(new GraphRecipe.Slot<>("row" + r, need));
                for (var term : row.terms().entrySet()) {
                    int i = term.getKey();
                    long quantity = amount(term.getValue().abs());
                    if (term.getValue().signum() > 0) inputs.get(i).add(new GraphRecipe.Slot<>("row" + r, quantity));
                    else if (term.getValue().signum() < 0) outputs.get(i).put("row" + r, quantity);
                }
            }
            for (int i = 0; i < low.length; i++) recipes.add(new GraphRecipe<>("var" + i, "var" + i, inputs.get(i), outputs.get(i)));
            recipes.add(new GraphRecipe<>("finish", "finish", finish, Map.of("target", 1L)));
        }

        private static long amount(BigInteger value) {
            if (value.signum() < 0 || value.bitLength() > 63)
                throw new Unsupported("main-counts conversion requires recipe/stock amounts to fit nonnegative long");
            return value.longValueExact();
        }
    }
}
