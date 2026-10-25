// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Scanner;
import java.util.stream.Collectors;

/** Generated neighboring equalities must retain a checked main-pipeline witness. */
public final class CountMessagePipelineTest {
    private static final long WORK = 60_000_000;

    public static void main(String[] args) throws Exception {
        for (int seed = 0; seed < 2; seed++) {
            String name = "message-sparse-144-" + seed + ".txt";
            String input = Files.readAllLines(Path.of("tests/core/data", name)).stream()
                    .filter(line -> !line.startsWith("#")).collect(Collectors.joining("\n"));
            try (var scanner = new Scanner(input)) {
                int n = scanner.nextInt(), equations = scanner.nextInt();
                var lower = new BigInteger[n];
                var upper = new BigInteger[n];
                var planted = new BigInteger[n];
                for (int i = 0; i < n; i++) {
                    lower[i] = scanner.nextBigInteger();
                    upper[i] = scanner.nextBigInteger();
                    planted[i] = scanner.nextBigInteger();
                }
                var rows = new ArrayList<ExactLinearProgram.Constraint>();
                for (int row = 0; row < equations; row++) {
                    BigInteger rhs = scanner.nextBigInteger();
                    int terms = scanner.nextInt();
                    var coefficients = new LinkedHashMap<Integer, BigInteger>();
                    var reverse = new LinkedHashMap<Integer, BigInteger>();
                    for (int term = 0; term < terms; term++) {
                        int variable = scanner.nextInt();
                        BigInteger coefficient = scanner.nextBigInteger();
                        check(coefficients.put(variable, coefficient) == null, "Repeated coordinate");
                        reverse.put(variable, coefficient.negate());
                    }
                    rows.add(new ExactLinearProgram.Constraint(coefficients, rhs));
                    rows.add(new ExactLinearProgram.Constraint(reverse, rhs.negate()));
                }
                check(!scanner.hasNext(), "Trailing fixture data");
                // The input's independently planted point establishes SAT; it
                // is never passed to the solver or used to choose its branches.
                verify(rows, lower, upper, planted);
                var budget = new PlanningBudget(0, WORK, 256L << 20, () -> false, System::nanoTime);
                // The replay additionally checks primitive recipe prefixes and
                // the produced target before restoring original coordinates.
                var result = CountModelReplay.solve("main-counts", rows, lower, upper, budget, WORK);
                check(!result.infeasible() && result.counts() != null, name + " lost its feasible plan");
                verify(rows, lower, upper, result.counts());
                check(budget.nodes() <= WORK, name + " exceeded the matched work budget");
                check(budget.reservedBytes() == 0, name + " leaked portfolio ownership");
                System.out.println("Message main pipeline " + name + ": original rows and primitive prefixes passed; work=" + budget.nodes());
            }
        }
    }

    private static void verify(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                               BigInteger[] upper, BigInteger[] point) {
        check(point.length == lower.length, "Witness dimension mismatch");
        for (int i = 0; i < lower.length; i++)
            check(point[i].compareTo(lower[i]) >= 0 && point[i].compareTo(upper[i]) <= 0, "Invalid original bound");
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet())
                sum = sum.add(term.getValue().multiply(point[term.getKey()]));
            check(sum.compareTo(row.upper()) <= 0, "Invalid original equation");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
