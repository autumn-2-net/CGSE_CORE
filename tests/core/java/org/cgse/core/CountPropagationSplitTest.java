// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Large finite feedback domains must not enumerate a billion bound updates. */
public final class CountPropagationSplitTest {
    public static void main(String[] args) {
        for (boolean damaged : List.of(false, true)) for (boolean reverse : List.of(false, true)) {
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            var a = new TreeMap<Integer, BigInteger>(); var b = new TreeMap<Integer, BigInteger>();
            a.put(0, BigInteger.ONE.negate()); a.put(1, BigInteger.ONE);
            b.put(0, BigInteger.valueOf(damaged ? 1_000_000_000 : 999_999_999)); b.put(1, BigInteger.valueOf(-1_000_000_000));
            // Fixed resource ports make this an ordinary recipe row, outside
            // the two-term difference shortcut. The damaged model may still
            // use its exact sparse-pair contradiction.
            for (int k = 2; k < 16; k++) { a.put(k, BigInteger.ONE); b.put(k, BigInteger.ONE); }
            // A fixed zero producer keeps this outside the monotone cycle
            // compiler, so this test still exercises the split continuation.
            a.put(2, BigInteger.ONE.negate()); b.put(2, BigInteger.ONE.negate());
            rows.add(new ExactLinearProgram.Constraint(a, BigInteger.ONE.negate()));
            rows.add(new ExactLinearProgram.Constraint(b, BigInteger.ZERO));
            if (reverse) Collections.reverse(rows);
            var low = new BigInteger[300]; var high = new BigInteger[300]; Arrays.fill(low, BigInteger.ZERO); Arrays.fill(high, BigInteger.ZERO);
            high[0] = high[1] = BigInteger.valueOf(1_000_000_000_000L);
            var budget = new PlanningBudget(0, 2_000_000, 128L << 20, () -> false, System::nanoTime);
            try (var search = new CountLcg(rows, low, high, budget, 1_500_000, true)) {
                while (!search.step()) {}
                if (damaged) {
                    check(search.infeasible(), "Damaged feedback was not resolved: " + budget.diagnostics());
                    check(CountProof.verify(search.certificate(), 3_000_000) == CountProof.Verdict.VERIFIED, "Split produced an invalid proof");
                } else {
                    var counts = search.counts(); check(counts != null, "Intact feedback lost its witness: " + budget.diagnostics());
                    check(budget.diagnostics().contains("count_propagation_split"), "Slow feedback did not exercise an eager split");
                    for (var row : rows) {
                        BigInteger sum = BigInteger.ZERO;
                        for (var e : row.terms().entrySet()) sum = sum.add(e.getValue().multiply(counts[e.getKey()]));
                        check(sum.compareTo(row.upper()) <= 0, "Unfunded integer witness");
                    }
                }
            }
            check(budget.reservedBytes() == 0, "Feedback trail leaked");
        }
        System.out.println("CountPropagationSplitTest PASS");
    }
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
}
