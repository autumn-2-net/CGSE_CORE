// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

/** Structural analysis remains independent of request RHS and map hash collisions. */
public final class CountStructuralAnalysisTest {
    public static void main(String[] args) throws Exception {
        var random = new Random(2026101401L);
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int sample = 0; sample < 2048; sample++) {
            var input = new LinkedHashMap<Integer, BigInteger>();
            int width = sample % 65;
            BigInteger scale = sample % 3 == 0 ? BigInteger.ONE.shiftLeft(sample % 1024) : BigInteger.valueOf(6);
            for (int i = width - 1; i >= 0; i--) input.put(i * 3, BigInteger.valueOf(random.nextInt(33) - 16).multiply(scale));
            var frozen = new TreeMap<>(input);
            var row = new ExactLinearProgram.Constraint(input, BigInteger.valueOf(sample - 1024));
            input.clear();
            rows.add(row);
            verify(row, frozen);
            verify(new ExactLinearProgram.Constraint(row.terms(), row.upper().negate().subtract(BigInteger.ONE)), frozen);
        }
        var zeroHash = new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ZERO, 1, BigInteger.ONE), BigInteger.ZERO);
        check(zeroHash.terms().hashCode() == 0 && zeroHash.terms().hashCode() == 0, "Zero hash was not preserved");
        var hashTwo = new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE, 1, BigInteger.ZERO), BigInteger.ZERO);
        check(hashTwo.terms().hashCode() == 2, "Unexpected hash oracle");
        var collision = new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ZERO, 1, BigInteger.valueOf(3)), BigInteger.ZERO);
        check(collision.terms().hashCode() == hashTwo.terms().hashCode() && !collision.equals(hashTwo), "Hash replaced equality");
        // Race first publication, not just reads of an already analyzed row.
        var fresh = new ArrayList<ExactLinearProgram.Constraint>();
        for (var row : rows) fresh.add(new ExactLinearProgram.Constraint(new TreeMap<>(row.terms()), row.upper()));
        var workers = Executors.newFixedThreadPool(4);
        try {
            var jobs = new ArrayList<Future<?>>();
            for (int i = 0; i < 4; i++) jobs.add(workers.submit(() -> {
                for (var row : fresh) verify(row, new TreeMap<>(row.terms()));
            }));
            for (var job : jobs) job.get(30, TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); }
        // Compare the exceptional-coefficient transformation with an exhaustive
        // integer oracle, including nonzero lower bounds and negative RHS.
        for (int sample = 0; sample < 512; sample++) {
            Map<Integer, BigInteger> terms = new LinkedHashMap<>();
            var low = new BigInteger[3]; var high = new BigInteger[3];
            for (int i = 0; i < 3; i++) {
                terms.put(i, BigInteger.valueOf(random.nextInt(17) - 8));
                low[i] = BigInteger.valueOf(random.nextInt(7) - 3);
                high[i] = low[i].add(BigInteger.valueOf(3));
            }
            var row = new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(random.nextInt(31) - 15));
            var budget = budget();
            var simplified = CountIntegerCoefficients.simplify(row, low, high, budget);
            for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++) for (int c = 0; c < 4; c++) {
                var point = new BigInteger[]{low[0].add(BigInteger.valueOf(a)), low[1].add(BigInteger.valueOf(b)), low[2].add(BigInteger.valueOf(c))};
                check(satisfied(row, point) == satisfied(simplified, point), "Integer scaling changed a feasible point");
            }
            check(budget.reservedBytes() == 0, "Analysis leaked request workspace");
        }
        System.out.println("Structural analysis: 2048 exact normalizations, concurrent RHS overlays and 32768 integer points passed");
    }

    private static void verify(ExactLinearProgram.Constraint row, Map<Integer, BigInteger> reference) {
        check(row.terms().equals(reference) && row.terms().hashCode() == reference.hashCode(), "Coefficient value changed");
        var gcd = BigInteger.ZERO;
        for (var value : reference.values()) gcd = gcd.gcd(value);
        check(row.coefficientGcd().equals(gcd), "Wrong structural gcd");
        check(row.hasZeroCoefficient() == reference.containsValue(BigInteger.ZERO), "Wrong zero marker");
        var expected = new LinkedHashMap<Integer, BigInteger>();
        var divisor = gcd.max(BigInteger.ONE);
        for (var term : reference.entrySet()) if (term.getValue().signum() != 0)
            expected.put(term.getKey(), term.getValue().divide(divisor));
        var qr = row.upper().divideAndRemainder(divisor);
        var bound = qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
        check(CountReduction.normalize(row).equals(new ExactLinearProgram.Constraint(expected, bound)), "Normalization or signed rounding changed");
    }

    private static boolean satisfied(ExactLinearProgram.Constraint row, BigInteger[] point) {
        var sum = BigInteger.ZERO;
        for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(point[term.getKey()]));
        return sum.compareTo(row.upper()) <= 0;
    }

    private static PlanningBudget budget() { return new PlanningBudget(0, 1000000, 32L << 20, () -> false, System::nanoTime); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
