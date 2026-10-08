// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Exhaustive checks for demand sums, their assumptions and optional scaled witnesses. */
public final class CountAggregationTest {
    public static void main(String[] args) {
        jointBound();
        exhaustiveBounds();
        inheritedScope();
        scaledWitnesses();
        retainedScaling();
        System.out.println("Demand aggregation: 600 exhaustive models, assumption replay, sibling scopes, scaled witnesses and cancellation passed");
    }

    private static PlanningBudget budget() {
        return new PlanningBudget(0, 20_000_000, 256L << 20, () -> false, System::nanoTime);
    }

    private static BigInteger z(long value) { return BigInteger.valueOf(value); }

    private static ExactLinearProgram.Constraint row(long bound, long... coefficients) {
        var terms = new LinkedHashMap<Integer, BigInteger>();
        for (int i = 0; i < coefficients.length; i++) if (coefficients[i] != 0) terms.put(i, z(coefficients[i]));
        return new ExactLinearProgram.Constraint(terms, z(bound));
    }

    private static List<ExactLinearProgram.Constraint> jointRows(long supply) {
        return List.of(row(supply, 1, 1), row(supply, 0, 0, 1, 1),
                row(0, -1, 0, -1, 0, 1), row(0, 0, -1, 0, -1, 1));
    }

    private static void jointBound() {
        for (BigInteger scale : List.of(BigInteger.ONE, BigInteger.ONE.shiftLeft(160).add(z(7)))) {
            var rows = jointRows(1).stream().map(r -> new ExactLinearProgram.Constraint(r.terms(), r.upper().multiply(scale))).toList();
            var budget = budget();
            try (var propagation = new CountBounds(5, rows, budget)) {
                while (!propagation.step()) {}
                check(!propagation.blocked(), "Valid shared supply rejected");
                check(scale.equals(propagation.upperBounds()[4]), "Joint demand did not expose the feedstock bound");
            }
            check(budget.reservedBytes() == 0, "Joint demand reservation leaked");
        }
    }

    private static void exhaustiveBounds() {
        var random = new Random(820713);
        for (int trial = 0; trial < 600; trial++) {
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int i = 0; i < 5; i++) {
                long[] coefficients = new long[5];
                coefficients[i] = 1;
                rows.add(row(3, coefficients));
            }
            int a = 1 + random.nextInt(9), b = 1 + random.nextInt(9);
            rows.add(row(random.nextInt(5), 1, 1));
            rows.add(row(random.nextInt(5), 0, 0, 1, 1));
            rows.add(row(random.nextInt(5) - 2, -a, 0, -b, 0, 1 + random.nextInt(9)));
            rows.add(row(random.nextInt(5) - 2, 0, -a, 0, -b, 1 + random.nextInt(9)));
            rows.add(row(-random.nextInt(4), 0, 0, 0, 0, -1));
            Collections.shuffle(rows, random);
            int assumptionStart = random.nextInt(rows.size() + 1);
            var budget = budget();
            try (var bounds = new CountBounds(5, rows, budget, assumptionStart)) {
                while (!bounds.step()) {}
                var low = bounds.lowerBounds();
                var high = bounds.upperBounds();
                var conflict = bounds.conflictingAssumptions();
                var premises = new ArrayList<>(rows.subList(0, assumptionStart));
                if (bounds.blocked()) {
                    check(conflict != null && conflict.length() <= rows.size() - assumptionStart, "Invalid proof scope");
                    for (int bit = conflict.nextSetBit(0); bit >= 0; bit = conflict.nextSetBit(bit + 1))
                        premises.add(rows.get(assumptionStart + bit));
                }
                for (int code = 0; code < 1024; code++) {
                    long[] point = new long[5];
                    for (int i = 0, rest = code; i < point.length; i++, rest /= 4) point[i] = rest % 4;
                    if (bounds.blocked()) check(!satisfies(premises, point), "Conflict omitted a needed assumption");
                    else if (satisfies(rows, point)) for (int i = 0; i < point.length; i++)
                        check(z(point[i]).compareTo(low[i]) >= 0 && (high[i] == null || z(point[i]).compareTo(high[i]) <= 0),
                                "Aggregation removed a feasible integer point");
                }
            }
            check(budget.reservedBytes() == 0, "Exhaustive propagation leaked");
        }
    }

    private static boolean satisfies(List<ExactLinearProgram.Constraint> rows, long[] point) {
        for (var row : rows) {
            long sum = 0;
            for (var term : row.terms().entrySet()) sum += term.getValue().longValueExact() * point[term.getKey()];
            if (sum > row.upper().longValueExact()) return false;
        }
        return true;
    }

    private static void inheritedScope() {
        var rows = new ArrayList<>(jointRows(1));
        var budget = budget();
        try (var parent = new CountBounds(5, rows, budget, rows.size())) {
            while (!parent.step()) {}
            try (var seed = parent.snapshot()) {
                check(seed != null, "Missing reusable demand proof");
                for (int request : new int[] { 2, 1 }) {
                    var child = new ArrayList<>(rows);
                    child.add(row(-request, 0, 0, 0, 0, -1));
                    try (var bounds = new CountBounds(5, child, budget, rows.size(), seed)) {
                        while (!bounds.step()) {}
                        check(bounds.blocked() == (request == 2), "Sibling demand assumption leaked");
                        if (bounds.blocked()) check(bounds.conflictingAssumptions().get(0), "Demand premise lost through reuse");
                    }
                }
                // Changing the stock invalidates all old capacity consequences.
                var relaxed = new ArrayList<>(jointRows(2));
                relaxed.add(row(-2, 0, 0, 0, 0, -1));
                try (var bounds = new CountBounds(5, relaxed, budget, 4, seed)) {
                    while (!bounds.step()) {}
                    check(!bounds.blocked(), "Old stock proof leaked into a relaxed inventory");
                    check(z(2).equals(bounds.upperBounds()[4]), "Relaxed capacity was not recompiled");
                }
            }
        }
        check(budget.reservedBytes() == 0, "Demand seed leaked");
    }

    private static void scaledWitnesses() {
        for (BigInteger amount : List.of(z(2), z(3), z(16), z(1000), BigInteger.ONE.shiftLeft(100).add(z(39)))) {
            var terms = Map.of(0, z(3), 1, z(5), 2, z(7));
            var rows = List.of(new ExactLinearProgram.Constraint(terms, amount.multiply(z(3))),
                    new ExactLinearProgram.Constraint(Map.of(0, z(-1), 1, z(-1), 2, z(-1)), amount.negate()));
            BigInteger[] low = { z(0), z(0), z(0) }, high = { amount, amount, amount };
            var budget = budget();
            try (var scale = new CountScale(rows, low, high, budget)) {
                while (!scale.step()) {}
                check(Arrays.equals(scale.counts(), new BigInteger[] { amount, z(0), z(0) }), "Small-domain scaled witness lost");
            }
            check(budget.reservedBytes() == 0, "Scaled witness leaked");
        }
        var rows = List.of(row(17, 3, 5), row(-17, -3, -5));
        BigInteger[] low = { z(0), z(0) }, high = { z(17), z(17) };
        var budget = budget();
        try (var scale = new CountScale(rows, low, high, budget)) {
            while (!scale.step()) {}
            check(scale.counts() == null, "Invalid scaled lattice witness");
        }
        try (var original = new CountQuickSolve(rows, low, high, budget)) {
            while (!original.step()) {}
            var value = original.counts();
            check(!original.infeasible() && value != null && satisfies(rows, Arrays.stream(value).mapToLong(BigInteger::longValueExact).toArray()),
                    "Failed restricted lattice prevented the original integer solution");
        }
        try (var scale = new CountScale(rows, low, high, budget)) {
            budget.cancel();
            boolean cancelled = false;
            try { while (!scale.step()) {} } catch (CancellationException expected) { cancelled = true; }
            check(cancelled, "Scale ignored cancellation");
        }
        check(budget.reservedBytes() == 0, "Scale cancellation leaked");
    }

    private static void retainedScaling() {
        var random = new Random(60219);
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        BigInteger[] low = new BigInteger[40], high = new BigInteger[40];
        Arrays.fill(low, z(0));
        Arrays.fill(high, z(16));
        for (int d = 0; d < 4; d++) {
            var terms = new LinkedHashMap<Integer, BigInteger>();
            var inverse = new LinkedHashMap<Integer, BigInteger>();
            BigInteger target = BigInteger.ZERO;
            for (int i = 0; i < low.length; i++) {
                BigInteger a = z(1 + random.nextInt(1000));
                terms.put(i, a);
                inverse.put(i, a.negate());
                if (i % 3 == 0) target = target.add(a.multiply(z(16)));
            }
            rows.add(new ExactLinearProgram.Constraint(terms, target));
            rows.add(new ExactLinearProgram.Constraint(inverse, target.negate()));
        }
        var budget = budget();
        try (var scale = new CountScale(rows, low, high, budget, true, true)) {
            while (!scale.step()) {}
            check(scale.paused() && scale.counts() == null, "Wide finite search should retain an undecided table");
            long used = budget.searchWork();
            scale.resume(32768);
            while (!scale.step()) {}
            check(scale.paused() && budget.searchWork() > used && budget.searchWork() - used < 100000,
                    "Scaled continuation restarted or ignored its turn");
            budget.cancel();
            boolean cancelled = false;
            try { scale.resume(32768); } catch (CancellationException expected) { cancelled = true; }
            check(cancelled, "Retained scale ignored cancellation");
        }
        check(budget.reservedBytes() == 0, "Retained matching table leaked");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
