// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tight capacities need a two-sided repair, not an invented raw-material credit. */
public final class CountDemandResidualTest {
    private static int checks;
    private static final int[][] COEFFICIENTS = {
            {-10, -1, -5, -4}, {-3, -1, -12, -6}, {9, 3, 5, 11}, {10, 3, 9, 4}};
    private static final long[] RHS = {-84, -81, 105, 112};

    public static void main(String[] args) {
        int recovered = 0;
        for (int copies : new int[]{1, 2}) for (long scale : new long[]{1, 2, 16, 1_000_000_000L})
            for (int order = 0; order < 3; order++) {
                var rows = rows(copies, scale, order);
                var budget = budget(16L << 20, () -> false);
                try (var projection = new CountDemandProjection(4 * copies, rows, budget, 1_000_000)) {
                    while (!projection.step()) {}
                    check(valid(rows, projection.result()), "Tight integer witness lost: " + copies + "/" + scale + "/" + order);
                    check(projection.work() <= 1_000_001, "Residual repair replenished the projection account");
                    if (budget.diagnostics().contains("count_demand_residual")) recovered++;
                }
                check(budget.reservedBytes() == 0, "Residual retained workspace");
            }
        check(recovered > 0, "Controls bypassed the residual repair");
        physical();
        limits();
        System.out.println("CountDemandResidualTest PASS: checks=" + checks + "; recovered=" + recovered);
    }

    private static ArrayList<ExactLinearProgram.Constraint> rows(int copies, long scale, int order) {
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        var known = new BigInteger[4 * copies];
        long[] witness = {5, 6, 4, 2};
        var columns = new ArrayList<Integer>(); for (int i = 0; i < known.length; i++) columns.add(i);
        if (order == 1) Collections.reverse(columns);
        if (order == 2) Collections.shuffle(columns, new Random(20261027));
        for (int copy = 0; copy < copies; copy++) {
            for (int i = 0; i < 4; i++) known[columns.get(4 * copy + i)] = BigInteger.valueOf(witness[i]).multiply(BigInteger.valueOf(scale));
            for (int r = 0; r < 4; r++) {
                var terms = new TreeMap<Integer, BigInteger>();
                for (int v = 0; v < 4; v++) terms.put(columns.get(4 * copy + v), BigInteger.valueOf(COEFFICIENTS[r][v]));
                rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(RHS[r]).multiply(BigInteger.valueOf(scale))));
            }
        }
        if (order == 1) Collections.reverse(rows);
        if (order == 2) Collections.shuffle(rows, new Random(20261028));
        check(valid(rows, known), "Independent integer witness is not feasible");
        return rows;
    }

    private static void physical() {
        var recipes = new ArrayList<GraphRecipe<String>>();
        recipes.add(recipe("finish", Map.of("a", 84L, "b", 81L), Map.of("goal", 1L)));
        for (int v = 0; v < 4; v++) recipes.add(recipe("source" + v,
                Map.of("feed1", (long) COEFFICIENTS[2][v], "feed2", (long) COEFFICIENTS[3][v]),
                Map.of("a", (long) -COEFFICIENTS[0][v], "b", (long) -COEFFICIENTS[1][v])));
        var stock = Map.of("feed1", 105L, "feed2", 112L);
        var budget = budget(16L << 20, () -> false);
        GraphPlan<String> plan;
        try (var search = new CountCyclicStockSearch<>(new GraphCompiler<>(recipes), "goal", 1, stock,
                Set.of(), Map.of(), Set.of(), true, true, budget, System.nanoTime())) {
            while (!search.step()) {}
            plan = search.result();
        }
        check(plan != null && plan.feasible() && plan.missingExact().isEmpty(), "Repaired count point did not reach physical validation");
        var original = new HashMap<String, GraphRecipe<String>>(); recipes.forEach(r -> original.put(r.id(), r));
        var inventory = new HashMap<>(plan.initialExact());
        inventory.forEach((key, value) -> check(value.compareTo(BigInteger.valueOf(stock.getOrDefault(key, 0L))) <= 0,
                "Plan invented initial material"));
        execute(plan.steps(), original, inventory);
        check(inventory.getOrDefault("goal", BigInteger.ZERO).compareTo(BigInteger.ONE) >= 0, "Physical target missing");
        plan.seeds().forEach((key, amount) -> check(inventory.getOrDefault(key, BigInteger.ZERO).compareTo(BigInteger.valueOf(amount)) >= 0,
                "Physical seed missing"));
        check(budget.reservedBytes() == 0, "Physical proposal retained workspace");
    }

    private static void execute(PlanStep step, Map<String, GraphRecipe<String>> recipes, Map<String, BigInteger> inventory) {
        if (step instanceof PlanStep.Batch batch) {
            check(batch.runs() <= 1000, "Synthetic execution became unexpectedly large");
            var recipe = recipes.get(batch.recipe()); check(recipe != null, "Unknown recipe in physical word");
            for (long i = 0; i < batch.runs(); i++) {
                recipe.inputs().forEach((key, amount) -> {
                    BigInteger available = inventory.getOrDefault(key, BigInteger.ZERO);
                    check(available.compareTo(BigInteger.valueOf(amount)) >= 0, "Unfunded physical prefix");
                    inventory.put(key, available.subtract(BigInteger.valueOf(amount)));
                });
                recipe.outputs().forEach((key, amount) -> inventory.merge(key, BigInteger.valueOf(amount), BigInteger::add));
            }
        } else if (step instanceof PlanStep.Repeat repeat) {
            check(repeat.times() <= 1000, "Unexpected synthetic repeat");
            for (long i = 0; i < repeat.times(); i++) execute(repeat.body(), recipes, inventory);
        } else for (var child : ((PlanStep.Sequence) step).children()) execute(child, recipes, inventory);
    }

    private static void limits() {
        var rows = rows(1, 1, 0);
        for (long cap : new long[]{1, 128, 1024, 32768, 65536, 131072}) {
            var budget = budget(16L << 20, () -> false);
            try (var p = new CountDemandProjection(4, rows, budget, cap)) {
                while (!p.step()) {}
                check(p.result() == null || valid(rows, p.result()), "Cutoff published invalid counts");
                check(p.work() <= cap + 1, "Residual exceeded the shared projection limit");
            }
            check(budget.reservedBytes() == 0, "Cutoff retained memory");
        }
        for (long bytes : new long[]{1, 4096, 8192, 16384, 32768, 65536}) {
            var budget = budget(bytes, () -> false);
            try (var p = new CountDemandProjection(4, rows, budget, 1_000_000)) {
                while (!p.step()) {}
                check(p.result() == null || valid(rows, p.result()), "Memory refusal published invalid counts");
            }
            check(budget.reservedBytes() == 0 && budget.peakBytes() <= bytes, "Residual escaped memory accounting");
        }
        var stop = new AtomicBoolean(); var budget = budget(16L << 20, stop::get);
        try (var p = new CountDemandProjection(4, rows, budget, 1_000_000)) {
            while (!budget.diagnostics().contains("bounded_neighborhood")) check(!p.step(), "Control skipped residual cancellation");
            stop.set(true);
            try { p.step(); throw new AssertionError("Residual swallowed cancellation"); }
            catch (java.util.concurrent.CancellationException expected) { checks++; }
            check(p.result() == null, "Cancelled repair published unfinished counts");
        }
        check(budget.reservedBytes() == 0, "Cancelled residual retained memory");
    }

    private static boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] values) {
        if (values == null) return false;
        for (var value : values) if (value == null || value.signum() < 0) return false;
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(values[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private static PlanningBudget budget(long bytes, java.util.function.BooleanSupplier cancelled) {
        return new PlanningBudget(0, 8_000_000, bytes, cancelled, System::nanoTime);
    }
    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static void check(boolean value, String detail) { checks++; if (!value) throw new AssertionError(detail); }
}
