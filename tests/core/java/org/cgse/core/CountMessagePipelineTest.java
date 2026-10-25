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

    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                         BigInteger[] upper, BigInteger[] planted) {}

    public static void main(String[] args) throws Exception {
        for (int seed = 0; seed < 2; seed++) {
            String name = "message-sparse-144-" + seed + ".txt";
            run(name, read(name));
        }
        String name = "message-sparse-257-1";
        Model original = read(name + ".txt");
        run(name, original);
        // Explicit permutations preserve the observed coordinate, inequality
        // and term insertion order without depending on a corpus RNG stream.
        for (int order = 1; order <= 2; order++)
            run(name + "/order=" + order, permute(original, name + "-order" + order + ".txt"));
    }

    private static Scanner fixture(String name) throws Exception {
        String input = Files.readAllLines(Path.of("tests/core/data", name)).stream()
                .filter(line -> !line.startsWith("#")).collect(Collectors.joining("\n"));
        return new Scanner(input);
    }

    private static Model read(String name) throws Exception {
        try (var scanner = fixture(name)) {
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
                    check(variable >= 0 && variable < n, "Invalid fixture coordinate");
                    check(coefficients.put(variable, coefficient) == null, "Repeated coordinate");
                    reverse.put(variable, coefficient.negate());
                }
                rows.add(new ExactLinearProgram.Constraint(coefficients, rhs));
                rows.add(new ExactLinearProgram.Constraint(reverse, rhs.negate()));
            }
            check(!scanner.hasNext(), "Trailing fixture data");
            return new Model(rows, lower, upper, planted);
        }
    }

    private static Model permute(Model original, String name) throws Exception {
        try (var scanner = fixture(name)) {
            int n = scanner.nextInt(), count = scanner.nextInt();
            check(n == original.lower.length && count == original.rows.size(), "Wrong permutation dimensions");
            var lower = new BigInteger[n];
            var upper = new BigInteger[n];
            var planted = new BigInteger[n];
            int[] oldToNew = new int[n];
            boolean[] seen = new boolean[n];
            for (int i = 0; i < n; i++) {
                int old = scanner.nextInt();
                check(old >= 0 && old < n && !seen[old], "Invalid coordinate permutation");
                seen[old] = true; oldToNew[old] = i;
                lower[i] = original.lower[old]; upper[i] = original.upper[old]; planted[i] = original.planted[old];
            }
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            seen = new boolean[count];
            for (int r = 0; r < count; r++) {
                int old = scanner.nextInt();
                check(old >= 0 && old < count && !seen[old], "Invalid row permutation");
                seen[old] = true;
                var row = original.rows.get(old);
                var coefficients = new LinkedHashMap<Integer, BigInteger>();
                for (int k = 0; k < row.terms().size(); k++) {
                    int coordinate = scanner.nextInt();
                    check(row.terms().containsKey(coordinate), "Invalid term permutation");
                    check(coefficients.put(oldToNew[coordinate], row.terms().get(coordinate)) == null,
                            "Repeated permuted term");
                }
                rows.add(new ExactLinearProgram.Constraint(coefficients, row.upper()));
            }
            check(!scanner.hasNext(), "Trailing permutation data");
            return new Model(rows, lower, upper, planted);
        }
    }

    private static void run(String name, Model model) {
        // The input's independently planted point establishes SAT; it
        // is never passed to the solver or used to choose its branches.
        verify(model.rows, model.lower, model.upper, model.planted);
        var budget = new PlanningBudget(0, WORK, 256L << 20, () -> false, System::nanoTime);
        // The replay additionally checks primitive recipe prefixes and
        // the produced target before restoring original coordinates.
        var result = CountModelReplay.solve("main-counts", model.rows, model.lower, model.upper, budget, WORK);
        check(!result.infeasible() && result.counts() != null, name + " lost its feasible plan");
        verify(model.rows, model.lower, model.upper, result.counts());
        check(budget.nodes() <= WORK, name + " exceeded the matched work budget");
        check(budget.reservedBytes() == 0, name + " leaked portfolio ownership");
        System.out.println("Message main pipeline " + name + ": original rows and primitive prefixes passed; work=" + budget.nodes());
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
