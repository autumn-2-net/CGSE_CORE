// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Exhaustive tiny-domain oracle for reversible sparse capacity propagation. */
public final class CountDemandAllocationTest {
    private static int checks;
    public static void main(String[] args) {
        var random = new Random(0xCA9AC17L);
        for (int sample = 0; sample < 1200; sample++) {
            int variables = 2 + sample % 2;
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int i = 0; i < variables; i++) rows.add(row(Map.of(i, 1), 4));
            for (int r = 0; r < 4; r++) {
                var terms = new LinkedHashMap<Integer, Integer>();
                for (int i = 0; i < variables; i++) { int a = random.nextInt(7) - 3; if (a != 0) terms.put(i, a); }
                rows.add(row(terms, random.nextInt(17) - 8));
            }
            boolean expected = enumerate(rows, new BigInteger[variables], 0);
            var answer = solve(variables, rows, 16L << 20);
            check((answer != null) == expected, "Tiny finite domain disagrees with exhaustive oracle, sample=" + sample);
            if (answer != null) check(valid(rows, answer), "Allocation escaped an original constraint");
        }
        var a = BigInteger.ONE.shiftLeft(90).add(BigInteger.valueOf(7));
        var b = BigInteger.ONE.shiftLeft(88).add(BigInteger.valueOf(11));
        var exact = List.of(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE), a),
                new ExactLinearProgram.Constraint(Map.of(1, BigInteger.ONE), b),
                new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE.negate(), 1, BigInteger.ONE.negate()), a.add(b).negate()));
        var counts = solve(2, exact, 16L << 20);
        check(counts != null && counts[0].equals(a) && counts[1].equals(b), "Capacity split lost exact quantities above long");
        check(solve(2, exact, 16) == null, "Optional memory refusal invented counts");
        System.out.println("CountDemandAllocationTest PASS: checks=" + checks);
    }

    private static BigInteger[] solve(int variables, List<ExactLinearProgram.Constraint> rows, long memory) {
        var producers = new ArrayList<int[]>();
        for (var row : rows) producers.add(row.terms().entrySet().stream().filter(e -> e.getValue().signum() < 0)
                .mapToInt(Map.Entry::getKey).toArray());
        var budget = new PlanningBudget(0, 2_000_000, memory, () -> false, System::nanoTime);
        BigInteger[] result;
        try (var search = new CountDemandAllocation(variables, rows, producers, budget)) {
            while (!search.step()) {}
            result = search.result();
        }
        check(budget.reservedBytes() == 0, "Capacity branch or rollback leaked reservations");
        return result;
    }

    private static boolean enumerate(List<ExactLinearProgram.Constraint> rows, BigInteger[] values, int at) {
        if (at == values.length) return valid(rows, values);
        for (int value = 0; value <= 4; value++) {
            values[at] = BigInteger.valueOf(value);
            if (enumerate(rows, values, at + 1)) return true;
        }
        return false;
    }
    private static boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] values) {
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(values[term.getKey()].multiply(term.getValue()));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }
    private static ExactLinearProgram.Constraint row(Map<Integer, Integer> terms, long upper) {
        var exact = new LinkedHashMap<Integer, BigInteger>(); terms.forEach((k, v) -> exact.put(k, BigInteger.valueOf(v)));
        return new ExactLinearProgram.Constraint(exact, BigInteger.valueOf(upper));
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
