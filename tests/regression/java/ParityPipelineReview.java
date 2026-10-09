// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Odd-cycle packing gaps, plus feasible one-edge deletions, through the main count stage. */
public final class ParityPipelineReview {
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {}
    public static void main(String[] args) {
        boolean measure = args.length > 0 && args[0].equals("measure");
        int solved = 0, unknown = 0;
        for (int length : new int[]{5, 9, 17, 25, 33, 41}) for (int groups : new int[]{3, 5})
            for (int order = 0; order < 3; order++) for (boolean missing : new boolean[]{false, true}) {
                var model = model(length, groups, order, missing);
                var budget = new PlanningBudget(0, 20_000_000, 256L << 20, () -> false, System::nanoTime);
                var result = CountModelReplay.solve("main-counts", model.rows, model.low, model.high, budget, 20_000_000);
                check(budget.reservedBytes() == 0, "Main pipeline leaked");
                String id = length + "/" + groups + "/" + order + "/" + missing;
                if (result.counts() == null && !result.infeasible()) {
                    unknown++;
                    System.out.println(id + "\tUNKNOWN\t" + budget.nodes());
                    if (!measure) throw new AssertionError("Unresolved parity boundary: " + id + " " + budget.diagnostics());
                    continue;
                }
                check(missing == (result.counts() != null), "Contradicted independent cycle oracle: " + id);
                if (result.counts() != null) verify(model, result.counts());
                solved++;
                System.out.println(id + "\t" + (result.infeasible() ? "UNSAT" : "SAT") + "\t" + budget.nodes());
            }
        System.out.println("Parity main-count stage: solved=" + solved + " unknown=" + unknown + "; shuffled rows/columns and signed offsets; original constraints and primitive prefixes checked");
    }

    private static Model model(int length, int groups, int order, boolean missing) {
        int n = length * groups;
        var random = new Random(5001L + n + order);
        var permutation = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) permutation.add(i);
        if (order > 0) Collections.shuffle(permutation, random);
        var low = new BigInteger[n];
        var high = new BigInteger[n];
        for (int i = 0; i < n; i++) {
            low[i] = BigInteger.valueOf(order == 2 ? random.nextInt(7) - 3 : 0);
            high[i] = low[i].add(BigInteger.ONE);
        }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int g = 0; g < groups; g++) for (int i = 0; i < length; i++) {
            if (missing && g == 0 && i == length - 1) continue;
            int a = permutation.get(g * length + i), b = permutation.get(g * length + (i + 1) % length);
            rows.add(new ExactLinearProgram.Constraint(Map.of(a, BigInteger.ONE, b, BigInteger.ONE),
                    BigInteger.ONE.add(low[a]).add(low[b])));
        }
        // Every closed odd cycle admits floor(length/2) independent vertices.
        // Removing one edge from the first cycle adds exactly one available slot.
        var demand = new LinkedHashMap<Integer, BigInteger>();
        BigInteger rhs = BigInteger.valueOf(-(groups * (length / 2) + 1));
        for (int i : permutation) { demand.put(i, BigInteger.ONE.negate()); rhs = rhs.subtract(low[i]); }
        rows.add(new ExactLinearProgram.Constraint(demand, rhs));
        Collections.shuffle(rows, new Random(191 + order));
        return new Model(rows, low, high);
    }

    private static void verify(Model model, BigInteger[] point) {
        for (int i = 0; i < point.length; i++) check(point[i].compareTo(model.low[i]) >= 0 && point[i].compareTo(model.high[i]) <= 0, "Original bound violated");
        for (var row : model.rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(point[term.getKey()]));
            check(sum.compareTo(row.upper()) <= 0, "Original row violated");
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
