// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Bound feedback must accelerate exact closure without changing its integer consequences. */
public final class PropagationFeedbackTest {
    private static BigInteger bi(long value) { return BigInteger.valueOf(value); }
    private static ExactLinearProgram.Constraint row(long limit, long... coefficients) {
        var terms = new LinkedHashMap<Integer, BigInteger>();
        for (int i = 0; i < coefficients.length; i++) if (coefficients[i] != 0) terms.put(i, bi(coefficients[i]));
        return new ExactLinearProgram.Constraint(terms, bi(limit));
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 20_000, 64L << 20, () -> false, System::nanoTime); }

    public static void main(String[] args) {
        for (int order = 0; order < 12; order++) {
            var impossible = new ArrayList<>(List.of(row(-1, 1, -1, 0), row(0, 0, 1, -1), row(0, -1, 0, 1)));
            Collections.shuffle(impossible, new Random(order));
            var budget = budget();
            try (var bounds = new CountBounds(3, impossible, budget)) {
                while (!bounds.step()) {}
                check(bounds.blocked(), "Circulating contradiction not closed within the small work cap");
            }
            check(budget.reservedBytes() == 0, "Contradiction leaked");
            for (long scale : new long[] { 100, 1_000_000, 1_000_000_000_000L }) {
                var rows = new ArrayList<>(List.of(row(-1, 1, -1), row(-scale, -scale - 1, scale)));
                Collections.shuffle(rows, new Random(order));
                var feasible = budget();
                try (var bounds = new CountBounds(2, rows, feasible)) {
                    while (!bounds.step()) {}
                    check(!bounds.blocked(), "Feasible near-balanced cycle rejected");
                    var point = bounds.lowerBounds();
                    check(point[0].equals(bi(2 * scale)) && point[1].equals(bi(2 * scale + 1)), "Missing exact least fixed point");
                    for (var row : rows) check(satisfies(row, point), "Propagated counts violate an original row");
                }
                check(feasible.reservedBytes() == 0, "Feasible closure leaked");
                // The added branch bound is essential. Its reason must survive
                // early elimination; the feasible global model must stay open.
                rows.add(row(2 * scale - 1, 1, 0));
                var branch = budget();
                try (var bounds = new CountBounds(2, rows, branch, 2)) {
                    while (!bounds.step()) {}
                    check(bounds.blocked() && bounds.conflictingAssumptions() != null && bounds.conflictingAssumptions().get(0),
                            "Lost the branch premise while eliminating feedback");
                }
                check(branch.reservedBytes() == 0, "Branch explanation leaked");
            }
        }
        enumeratedNeighbors();
        System.out.println("Propagation feedback: 84 ordered exact closures and 400 independent finite enumerations passed");
    }

    private static void enumeratedNeighbors() {
        var random = new Random(2026101001L);
        for (int sample = 0; sample < 400; sample++) {
            int n = 2 + random.nextInt(3);
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int i = 0; i < n; i++) { var terms = new long[n]; terms[i] = 1; rows.add(row(3, terms)); }
            for (int r = 0; r < 3 + random.nextInt(8); r++) {
                var terms = new long[n];
                for (int i = 0; i < n; i++) terms[i] = random.nextInt(11) - 5;
                rows.add(row(random.nextInt(19) - 9, terms));
            }
            Collections.shuffle(rows, random);
            var witnesses = new ArrayList<BigInteger[]>();
            for (int encoded = 0; encoded < (1 << (2 * n)); encoded++) {
                var point = new BigInteger[n];
                for (int i = 0; i < n; i++) point[i] = bi((encoded >> (2 * i)) & 3);
                if (rows.stream().allMatch(r -> satisfies(r, point))) witnesses.add(point);
            }
            var budget = budget();
            try (var bounds = new CountBounds(n, rows, budget)) {
                while (!bounds.step()) {}
                check(!bounds.blocked() || witnesses.isEmpty(), "False negative in enumerated model");
                var low = bounds.lowerBounds(); var high = bounds.upperBounds();
                for (var point : witnesses) for (int i = 0; i < n; i++)
                    check(point[i].compareTo(low[i]) >= 0 && (high[i] == null || point[i].compareTo(high[i]) <= 0), "Invalid bound cut");
            }
            check(budget.reservedBytes() == 0, "Enumeration leaked");
        }
    }

    private static boolean satisfies(ExactLinearProgram.Constraint row, BigInteger[] point) {
        BigInteger sum = BigInteger.ZERO;
        for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(point[term.getKey()]));
        return sum.compareTo(row.upper()) <= 0;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
