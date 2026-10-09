// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** The count-stage regression uses the same entry and limits as corpus replay. */
public final class CapacityPipelineReview {
    public static void main(String[] args) {
        for (int groups = 4; groups <= 13; groups++) for (int order = 0; order < 3; order++) {
            solve(permute(HallCapacityTest.coloring(groups, groups - 1, false, order), order), false);
            if (groups <= 6) solve(permute(HallCapacityTest.coloring(groups, groups - 1, true, order), order), true);
        }
        var budget = budget();
        try {
            CountModelReplay.solve("main-counts", List.of(), new BigInteger[]{BigInteger.ZERO},
                    new BigInteger[]{null}, budget, 20_000_000);
            throw new AssertionError("Unbounded conversion silently changed the domain");
        } catch (CountModelReplay.Unsupported expected) {}
        check(budget.reservedBytes() == 0, "Unsupported conversion leaked");
        System.out.println("Main count stage: 30 capacity deficits and 9 feasible neighbors; shuffled rows/columns, signed offsets, original count and primitive-prefix checks passed");
    }

    private static void solve(HallCapacityTest.Model model, boolean feasible) {
        var budget = budget();
        var result = CountModelReplay.solve("main-counts", model.rows(), model.low(), model.high(), budget, 20_000_000);
        check(budget.reservedBytes() == 0, "Main count stage leaked");
        check(result.counts() != null || result.infeasible(), "Capacity pipeline unresolved: " + model.low().length + " " + budget.diagnostics());
        check(feasible == (result.counts() != null), "Capacity pipeline disagrees with independent oracle");
        if (result.counts() == null) return;
        var point = result.counts();
        for (int i = 0; i < point.length; i++)
            check(point[i].compareTo(model.low()[i]) >= 0 && point[i].compareTo(model.high()[i]) <= 0, "Original bound violated");
        for (var row : model.rows()) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(point[term.getKey()]));
            check(sum.compareTo(row.upper()) <= 0, "Original row violated");
        }
    }

    private static HallCapacityTest.Model permute(HallCapacityTest.Model source, int order) {
        if (order == 0) return source;
        int n = source.low().length;
        var random = new Random(5001L + n + order);
        var permutation = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) permutation.add(i);
        Collections.shuffle(permutation, random);
        var low = new BigInteger[n];
        var high = new BigInteger[n];
        // Shift domains as well as changing column and term insertion order.
        // The finite recipe embedding must recover the exact original values.
        for (int i = 0; i < n; i++) {
            low[i] = BigInteger.valueOf(order == 2 ? random.nextInt(7) - 3 : 0);
            high[i] = low[i].add(BigInteger.ONE);
        }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (var row : source.rows()) {
            var terms = new LinkedHashMap<Integer, BigInteger>();
            var entries = new ArrayList<>(row.terms().entrySet());
            Collections.shuffle(entries, random);
            BigInteger bound = row.upper();
            for (var term : entries) {
                int id = permutation.get(term.getKey());
                terms.put(id, term.getValue());
                bound = bound.add(term.getValue().multiply(low[id]));
            }
            rows.add(new ExactLinearProgram.Constraint(terms, bound));
        }
        Collections.shuffle(rows, random);
        return new HallCapacityTest.Model(rows, low, high);
    }

    private static PlanningBudget budget() { return new PlanningBudget(0, 20_000_000, 256L << 20, () -> false, System::nanoTime); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
