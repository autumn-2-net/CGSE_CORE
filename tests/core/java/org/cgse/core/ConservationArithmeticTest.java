// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.*;

/** Elimination and nullspace vectors checked against unreduced exact arithmetic. */
public final class ConservationArithmeticTest {
    public static void main(String[] args) throws Exception {
        var random = new Random(2026101408L);
        int clipped = 0, nonzero = 0;
        for (int sample = 0; sample < 4096; sample++) {
            int n = 2 + random.nextInt(12);
            var pivot = vector(random, n, sample);
            var row = vector(random, n, sample + 1);
            if (pivot[0].signum() == 0) pivot[0] = BigInteger.ONE;
            if (sample % 9 == 0) row = pivot.clone();
            var expected = row.clone();
            var gcd = BigInteger.ZERO;
            boolean exceeded = false;
            int visits = 0;
            if (row[0].signum() != 0) {
                for (int j = 0; j < n; j++) {
                    visits++;
                    expected[j] = row[j].multiply(pivot[0]).subtract(pivot[j].multiply(row[0]));
                    if (expected[j].bitLength() > 4096) { exceeded = true; break; }
                    gcd = gcd.gcd(expected[j]);
                }
                if (!exceeded && gcd.signum() > 0) for (int j = 0; j < n; j++) expected[j] = expected[j].divide(gcd);
            }
            var budget = budget();
            try (var analysis = analysis(budget)) {
                set(analysis, "keys", Collections.nCopies(n, "k"));
                set(analysis, "matrix", new BigInteger[][]{pivot, row});
                set(analysis, "phase", 3); set(analysis, "row", 1);
                check(analysis.step() == exceeded, "Precision boundary changed at " + sample);
                check(Arrays.equals(row, expected), "Eliminated row changed at " + sample);
                check(budget.nodes() == 1 + visits, "Elimination scan work changed");
            }
            check(budget.reservedBytes() == 0, "Elimination workspace leaked");
            if (exceeded) clipped++;
            int rank = random.nextInt(n);
            var matrix = new BigInteger[rank][n];
            var pivots = new int[rank];
            for (int i = 0; i < rank; i++) {
                matrix[i] = vector(random, n, sample + i);
                Arrays.fill(matrix[i], 0, i, BigInteger.ZERO);
                if (matrix[i][i].signum() == 0) matrix[i][i] = BigInteger.ONE;
                pivots[i] = i;
            }
            var expectedVector = new BigInteger[n]; Arrays.fill(expectedVector, BigInteger.ZERO);
            expectedVector[rank] = BigInteger.ONE;
            exceeded = false; visits = 0;
            outer: for (int i = rank - 1; i >= 0; i--) {
                var sum = BigInteger.ZERO;
                for (int j = i + 1; j < n; j++) { visits++; sum = sum.add(matrix[i][j].multiply(expectedVector[j])); }
                var scale = matrix[i][i].abs().divide(matrix[i][i].gcd(sum));
                for (int j = 0; j < n; j++) {
                    expectedVector[j] = expectedVector[j].multiply(scale);
                    if (expectedVector[j].bitLength() > 4096) { exceeded = true; break outer; }
                }
                expectedVector[i] = sum.multiply(scale).negate().divide(matrix[i][i]);
            }
            budget = budget();
            try (var analysis = analysis(budget)) {
                set(analysis, "keys", Collections.nCopies(n, "k"));
                set(analysis, "matrix", matrix); set(analysis, "pivots", pivots);
                set(analysis, "phase", 4); set(analysis, "rank", rank); set(analysis, "free", rank);
                check(analysis.step() == exceeded, "Nullspace precision changed");
                @SuppressWarnings("unchecked") var basis = (List<BigInteger[]>) get(analysis, "basis");
                check(basis.size() == (exceeded ? 0 : 1), "Partial vector escaped");
                check(budget.nodes() == 1 + visits, "Nullspace scan work changed");
                if (!exceeded) {
                    var actual = basis.get(0);
                    check(Arrays.equals(actual, expectedVector), "Nullspace scaling changed at " + sample);
                    check(actual[rank].signum() != 0, "Zero vector");
                    for (var equation : matrix) {
                        var sum = BigInteger.ZERO;
                        for (int j = 0; j < n; j++) sum = sum.add(equation[j].multiply(actual[j]));
                        check(sum.signum() == 0, "Vector fails original equation");
                    }
                    nonzero++;
                } else clipped++;
            }
            check(budget.reservedBytes() == 0, "Nullspace workspace leaked");
        }
        check(clipped > 20 && nonzero > 2000, "Insufficient exact/precision coverage");
        supplyChanges();
        System.out.println("Conservation arithmetic: 8192 exact row/vector comparisons, original nullspace equations, precision/work and changed supply passed");
    }

    private static BigInteger[] vector(Random random, int n, int sample) {
        var values = new BigInteger[n];
        for (int j = 0; j < n; j++) {
            int bits = sample % 17 == 0 ? 2048 : sample % 29 == 0 ? 4096 : sample % 5 == 0 ? 160 : 12;
            values[j] = new BigInteger(bits, random);
            if (random.nextBoolean()) values[j] = values[j].negate();
            if (sample % 7 == 0) values[j] = values[j].shiftLeft(12);
            if (random.nextInt(4) == 0) values[j] = BigInteger.ZERO;
        }
        return values;
    }

    private static void supplyChanges() {
        var recipes = new ArrayList<GraphRecipe<String>>();
        recipes.add(new GraphRecipe<>("ab", "ab", List.of(new GraphRecipe.Slot<>("A", 1)), Map.of("B", 1L)));
        recipes.add(new GraphRecipe<>("ba", "ba", List.of(new GraphRecipe.Slot<>("B", 1)), Map.of("A", 1L)));
        for (int variant = 0; variant < 5; variant++) {
            if (variant == 3) recipes.add(new GraphRecipe<>("supply", "supply", List.of(), Map.of("A", 1L)));
            var budget = budget();
            try (var analysis = new ConservationAnalysis<>(new GraphCompiler<>(recipes), "B", variant == 1 ? 4 : 6,
                    Map.of("A", 5L), variant == 2 ? Set.of("A") : Set.of(), Map.of(),
                    variant == 4 ? Set.of("supply") : Set.of(), budget)) {
                while (!analysis.step()) {}
                check(analysis.blocked() == (variant == 0 || variant == 4), "Old resource bound escaped supply scope " + variant);
            }
            check(budget.reservedBytes() == 0, "Full analysis workspace leaked");
        }
    }

    private static PlanningBudget budget() { return new PlanningBudget(0, 1_000_000, 16L << 20, () -> false, System::nanoTime); }
    private static ConservationAnalysis<String> analysis(PlanningBudget budget) {
        return new ConservationAnalysis<>(new GraphCompiler<>(List.of()), "k", 1, Map.of(), Set.of(), Map.of(), Set.of(), budget);
    }
    private static Field field(String name) throws ReflectiveOperationException {
        var field = ConservationAnalysis.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void set(Object target, String name, Object value) throws ReflectiveOperationException { field(name).set(target, value); }
    private static Object get(Object target, String name) throws ReflectiveOperationException { return field(name).get(target); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
