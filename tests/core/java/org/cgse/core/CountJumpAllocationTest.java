// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** A candidate scout must not replace successful ordinary allocation walks. */
public final class CountJumpAllocationTest {
    private static final Set<Integer> BASELINE_SOLVED = Set.of(0, 1, 4, 5, 8, 13, 14, 16, 18, 24, 26, 33,
            36, 39, 40, 42, 48, 52, 53, 54, 56, 65, 67, 68, 69, 72, 76, 78, 80, 81, 90, 91, 92, 93);

    public static void main(String[] args) {
        int solved = 0;
        for (int seed = 0; seed < 96; seed++) {
            var random = new Random(74129 + seed);
            int groups = 4 + seed % 13, size = groups * 2, capacity = 2 + seed % 4;
            var low = new BigInteger[size]; var high = new BigInteger[size];
            var planted = new BigInteger[size]; var initial = new BigInteger[size];
            Arrays.fill(low, BigInteger.ZERO); Arrays.fill(high, BigInteger.valueOf(capacity));
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int group = 0; group < groups; group++) {
                int a = random.nextInt(capacity + 1);
                planted[2 * group] = BigInteger.valueOf(a); planted[2 * group + 1] = BigInteger.valueOf(capacity - a);
                initial[2 * group] = BigInteger.valueOf(capacity); initial[2 * group + 1] = BigInteger.ZERO;
                equality(rows, Map.of(2 * group, BigInteger.ONE, 2 * group + 1, BigInteger.ONE), BigInteger.valueOf(capacity));
            }
            for (int d = 0; d < 2 + seed % 2; d++) {
                var terms = new LinkedHashMap<Integer, BigInteger>(); var rhs = BigInteger.ZERO;
                for (int group = 0; group < groups; group++) {
                    BigInteger weight = BigInteger.valueOf(1 + random.nextInt(16 + seed % 3 * 16));
                    terms.put(2 * group, weight); rhs = rhs.add(weight.multiply(planted[2 * group]));
                }
                equality(rows, terms, rhs);
            }
            Collections.shuffle(rows, random);
            verify(rows, low, high, planted);
            var budget = new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, () -> 0L);
            try (var jump = new CountJump(rows, low, high, budget, 4096, initial).retained()) {
                while (true) {
                    while (!jump.step()) {}
                    if (!jump.paused() || budget.nodes() >= 262144) break;
                    jump.resume(4096);
                }
                var counts = jump.counts();
                if (counts != null) { verify(rows, low, high, counts); solved++; }
                if (BASELINE_SOLVED.contains(seed) && counts == null) throw new AssertionError("Lost ordinary witness: " + seed);
                // An early 16K scout preserved this result but displaced a cheap
                // 36K walk by almost 50%. Keep the original short path cheap.
                if (seed == 5 && budget.nodes() > 50000) throw new AssertionError("Scout displaced the short ordinary path");
            }
            if (budget.reservedBytes() != 0) throw new AssertionError("Allocation workspace leaked");
        }
        System.out.println("Allocation walks: 96 planted models, all 34 baseline successes retained; solved=" + solved);
    }

    private static void verify(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high, BigInteger[] point) {
        for (int i = 0; i < point.length; i++)
            if (point[i].compareTo(low[i]) < 0 || point[i].compareTo(high[i]) > 0) throw new AssertionError("Domain violated");
        for (var row : rows) {
            var sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(point[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) throw new AssertionError("Original row violated");
        }
    }

    private static void equality(List<ExactLinearProgram.Constraint> rows, Map<Integer, BigInteger> terms, BigInteger rhs) {
        rows.add(new ExactLinearProgram.Constraint(terms, rhs));
        var negative = new LinkedHashMap<Integer, BigInteger>(); terms.forEach((id, value) -> negative.put(id, value.negate()));
        rows.add(new ExactLinearProgram.Constraint(negative, rhs.negate()));
    }
}
