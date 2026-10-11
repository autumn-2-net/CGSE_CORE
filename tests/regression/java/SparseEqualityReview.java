// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Production count-stage admission and execution, using the portable corpus embedding. */
public final class SparseEqualityReview {
    public static void main(String[] args) {
        for (int order = 0; order < 3; order++) {
            int n = 32;
            var random = new Random(731103 + order);
            var ids = new ArrayList<Integer>();
            for (int i = 0; i < n; i++) ids.add(i);
            Collections.shuffle(ids, random);
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int sign : new int[] {1, -1}) {
                var terms = new TreeMap<Integer, BigInteger>();
                for (int i = 0; i < n; i++) terms.put(i, BigInteger.valueOf(sign));
                rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(sign * n / 2)));
            }
            for (int i = 0; i < n; i++) {
                int scale = i % 2 == 0 ? 5 : -3;
                var terms = new TreeMap<Integer, BigInteger>();
                for (int j = 0; j < n; j++) terms.put(ids.get(j), BigInteger.valueOf(scale + (i == j ? 1 : 0)));
                rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(scale * (n / 2) + i % 2)));
            }
            Collections.shuffle(rows, random);
            var lo = new BigInteger[n]; var hi = new BigInteger[n];
            Arrays.fill(lo, BigInteger.ZERO); Arrays.fill(hi, BigInteger.valueOf(100));
            var budget = new PlanningBudget(0, 40_000_000, 128L << 20, () -> false, System::nanoTime);
            var result = CountModelReplay.solve("main-counts", rows, lo, hi, budget, 40_000_000);
            check(result.counts() != null && !result.infeasible(), "Portfolio failed shared equality order " + order);
            for (var value : result.counts()) check(value.signum() >= 0 && value.compareTo(BigInteger.valueOf(100)) <= 0, "Invalid recovered domain");
            for (var row : rows) {
                BigInteger sum = BigInteger.ZERO;
                for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(result.counts()[term.getKey()]));
                check(sum.compareTo(row.upper()) <= 0, "Invalid recovered row");
            }
            check(budget.diagnostics().contains("count_row_sparsify"), "Portfolio did not use sparsification");
            check(budget.reservedBytes() == 0, "Portfolio sparsification memory leak");
        }
        System.out.println("Sparse equality main-counts: three recipe permutations and primitive execution checks passed");
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
