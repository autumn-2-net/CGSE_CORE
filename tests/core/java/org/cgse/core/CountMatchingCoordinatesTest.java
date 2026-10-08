// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Exhaustive original-coordinate oracle, independent of the matching representation. */
public final class CountMatchingCoordinatesTest {

    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {}

    public static void main(String[] args) {
        Random random = new Random(8194770);
        int compact = 0, wide = 0, sat = 0, unsat = 0;
        long assignments = 0;
        for (int trial = 0; trial < 280; trial++) {
            int n = 6, bits = new int[] { 0, 30, 52, 58, 61, 65, 1100 }[trial % 7];
            BigInteger[] low = new BigInteger[n], high = new BigInteger[n], anchor = new BigInteger[n];
            for (int i = 0; i < n; i++) {
                low[i] = BigInteger.ONE.shiftLeft(100).multiply(BigInteger.valueOf(random.nextInt(3) - 1));
                high[i] = low[i].add(BigInteger.valueOf(1 + random.nextInt(3)));
                anchor[i] = low[i].add(BigInteger.valueOf(random.nextInt(high[i].subtract(low[i]).intValueExact() + 1)));
            }
            List<ExactLinearProgram.Constraint> rows = new ArrayList<>();
            int dimensions = 1 + trial % 4;
            for (int d = 0; d < dimensions; d++) {
                Map<Integer, BigInteger> terms = new LinkedHashMap<>(), opposite = new LinkedHashMap<>();
                BigInteger target = BigInteger.ZERO;
                for (int i = 0; i < n; i++) {
                    BigInteger a = BigInteger.valueOf(random.nextInt(17) - 8).shiftLeft(bits)
                            .add(BigInteger.valueOf(random.nextInt(5) - 2));
                    terms.put(i, a);
                    opposite.put(i, a.negate());
                    target = target.add(a.multiply(anchor[i]));
                }
                if (trial % 5 == 0 && d == 0) target = target.add(BigInteger.valueOf(17));
                // Exact equalities, small point boxes, and the ordinary ranged path.
                BigInteger slack = BigInteger.valueOf(switch (trial % 3) { case 0 -> 0; case 1 -> 1; default -> 81; });
                rows.add(new ExactLinearProgram.Constraint(terms, target.add(slack)));
                rows.add(new ExactLinearProgram.Constraint(opposite, target.negate()));
            }
            Collections.shuffle(rows, random);
            Model model = new Model(rows, low, high);
            int states = 1;
            for (int i = 0; i < n; i++) states *= high[i].subtract(low[i]).intValueExact() + 1;
            boolean possible = false;
            for (int state = 0; state < states; state++) {
                BigInteger[] point = low.clone();
                int code = state;
                for (int i = 0; i < n; i++) {
                    int radix = high[i].subtract(low[i]).intValueExact() + 1;
                    point[i] = point[i].add(BigInteger.valueOf(code % radix));
                    code /= radix;
                }
                possible |= valid(model, point);
            }
            assignments += states;
            if (possible) sat++; else unsat++;
            String diagnostics = solve(model, possible, trial);
            if (diagnostics.contains("certified_long")) compact++;
            else if (diagnostics.contains("count_match_admission")) wide++;
        }
        check(compact >= 20 && wide >= 20 && sat >= 20 && unsat >= 20, "Insufficient representation/result coverage");
        boundaries();
        System.out.println("Matching coordinates: 280 models, " + assignments + " assignments, compact=" + compact +
                ", wide/ranged=" + wide + ", SAT=" + sat + ", UNSAT=" + unsat + "; signed boundary checks passed");
    }

    private static void boundaries() {
        // Coefficients and goals near the admission limit, beyond signed long,
        // and with partial sums that overflow even though the final sum is small.
        for (int bit : new int[] { 54, 59, 61, 63, 64, 1100 }) for (int sign : new int[] { -1, 1 }) {
            BigInteger a = BigInteger.ONE.shiftLeft(bit).multiply(BigInteger.valueOf(sign));
            Map<Integer, BigInteger> terms = Map.of(0, a, 1, a.negate().add(BigInteger.ONE), 2, BigInteger.valueOf(3));
            Map<Integer, BigInteger> opposite = new HashMap<>();
            terms.forEach((key, value) -> opposite.put(key, value.negate()));
            BigInteger[] low = { BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO };
            BigInteger[] high = { BigInteger.ONE, BigInteger.ONE, BigInteger.ONE };
            for (int target = 1; target <= 2; target++) {
                Model model = new Model(List.of(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(target)),
                        new ExactLinearProgram.Constraint(opposite, BigInteger.valueOf(-target))), low, high);
                String diagnostics = solve(model, target == 1, bit * sign);
                if (bit <= 59) check(diagnostics.contains("certified_long"), "Safe compact boundary declined");
                else check(!diagnostics.contains("certified_long"), "Unsafe prefix admitted to primitive arithmetic");
            }
        }
    }

    private static String solve(Model model, boolean possible, int trial) {
        var budget = new PlanningBudget(0, 20_000_000, 64L << 20, () -> false, System::nanoTime);
        try (var match = new CountMeetInMiddle(model.rows, model.low, model.high, budget)) {
            while (!match.step()) {}
            check((match.counts() != null) == possible, "Wrong matching witness at " + trial + ": " + budget.diagnostics());
            check(match.infeasible() != possible, "Wrong matching proof at " + trial);
            if (possible) check(valid(model, match.counts()), "Invalid original-coordinate witness at " + trial);
        }
        check(budget.reservedBytes() == 0, "Matching workspace leaked");
        return budget.diagnostics();
    }

    private static boolean valid(Model model, BigInteger[] point) {
        for (int i = 0; i < point.length; i++)
            if (point[i].compareTo(model.low[i]) < 0 || point[i].compareTo(model.high[i]) > 0) return false;
        for (var row : model.rows) {
            BigInteger total = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) total = total.add(term.getValue().multiply(point[term.getKey()]));
            if (total.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
