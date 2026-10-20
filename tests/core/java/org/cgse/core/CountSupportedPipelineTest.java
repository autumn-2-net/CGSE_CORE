// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/** Original-coordinate and primitive-execution guard for the supported portfolio arm. */
public final class CountSupportedPipelineTest {
    public static void main(String[] args) throws Exception {
        String input = Files.readAllLines(Path.of("tests/core/data/supported-sparse-257.txt")).stream()
                .filter(line -> !line.startsWith("#")).collect(Collectors.joining("\n"));
        try (var scanner = new Scanner(input)) {
            int n = scanner.nextInt(), m = scanner.nextInt();
            var low = new BigInteger[n]; var high = new BigInteger[n]; var planted = new BigInteger[n];
            for (int i = 0; i < n; i++) {
                low[i] = scanner.nextBigInteger(); high[i] = scanner.nextBigInteger(); planted[i] = scanner.nextBigInteger();
            }
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int r = 0; r < m; r++) {
                BigInteger rhs = scanner.nextBigInteger(); int terms = scanner.nextInt();
                var coefficients = new LinkedHashMap<Integer, BigInteger>();
                for (int k = 0; k < terms; k++) coefficients.put(scanner.nextInt(), scanner.nextBigInteger());
                rows.addAll(CountEqualitySupportTest.pair(coefficients, rhs));
            }
            check(!scanner.hasNext(), "Trailing case data");
            verify(rows, low, high, planted);
            var budget = new PlanningBudget(0, 40_000_000, 256L << 20, () -> false, System::nanoTime);
            // The shared replay independently verifies every original recipe
            // prefix and target, as well as restoring the count coordinates.
            var result = CountModelReplay.solve("main-counts", rows, low, high, budget, 40_000_000);
            check(!result.infeasible() && result.counts() != null, "Constructive model was not solved");
            verify(rows, low, high, result.counts());
            check(budget.nodes() < 11_000_000, "Supported completion lost its cost guard: " + budget.nodes());
            check(budget.reservedBytes() == 0, "Portfolio leaked support ownership");
            System.out.println("Supported main pipeline: exact original rows and primitive prefixes passed; work=" + budget.nodes());
        }
    }

    private static void verify(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high, BigInteger[] point) {
        for (int i = 0; i < low.length; i++) check(point[i].compareTo(low[i]) >= 0 && point[i].compareTo(high[i]) <= 0, "Invalid domain");
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(point[term.getKey()]));
            check(sum.compareTo(row.upper()) <= 0, "Invalid original equation");
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
