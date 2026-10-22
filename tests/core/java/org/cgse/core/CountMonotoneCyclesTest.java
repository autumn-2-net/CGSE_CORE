// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;

public final class CountMonotoneCyclesTest {
    public static void main(String[] args) {
        for (int size : new int[] {3, 8, 24}) for (boolean damaged : List.of(false, true)) {
            var rows = ring(size, damaged);
            for (int order = 0; order < 4; order++) {
                Collections.shuffle(rows, new Random(order));
                long[] work = {0};
                var cuts = CountMonotoneCycles.compile(rows, 300, () -> work[0]++, () -> work[0] >= 65536);
                check(!cuts.isEmpty(), "Feedback structure not compiled");
                var axioms = rows.stream().map(CountProof::row).toList();
                var proof = cuts.stream().map(c -> new CountProof.Combination(c.parents(), c.divisor(), CountProof.row(c.row()))).toList();
                check(CountProof.verify(new CountProof.Derivation("feedback", 300, axioms, proof), 2_000_000)
                        == CountProof.Verdict.VERIFIED, "Invalid positive row composition");
                if (damaged) check(cuts.stream().anyMatch(c -> c.row().terms().isEmpty() && c.row().upper().signum() < 0),
                        "Damaged ring did not expose its contradiction");
                else check(cuts.stream().anyMatch(c -> c.row().terms().size() == 1 &&
                        c.row().terms().getOrDefault(0, BigInteger.ZERO).equals(BigInteger.ONE.negate()) &&
                        c.row().upper().compareTo(BigInteger.valueOf(-1_000_000_000)) <= 0), "Slow feedback bound not accelerated");
                var open = new ArrayList<>(rows); open.remove(0);
                check(CountMonotoneCycles.compile(open, 300, () -> {}, () -> false).isEmpty(), "Deleted producer created a cycle");
            }
        }
        for (boolean damaged : List.of(false, true)) {
            var rows = ring(8, damaged);
            BigInteger[] low = new BigInteger[300], high = new BigInteger[300];
            Arrays.fill(low, BigInteger.ZERO); Arrays.fill(high, BigInteger.TEN);
            for (int i = 0; i < 8; i++) high[i] = BigInteger.valueOf(1_000_000_000_000L);
            var budget = new PlanningBudget(0, 1_000_000, 64L << 20, () -> false, System::nanoTime);
            try (var search = new CountLcg(rows, low, high, budget, 800000, true)) {
                while (!search.step()) {}
                if (damaged) {
                    check(search.infeasible(), "Damaged large model still enumerates feedback");
                    check(CountProof.verify(search.certificate(), 2_000_000) == CountProof.Verdict.VERIFIED, "Invalid damage proof");
                } else {
                    var values = search.counts(); check(values != null, "Intact large model lost its witness: " + budget.diagnostics());
                    for (var row : rows) {
                        BigInteger value = BigInteger.ZERO;
                        for (var term : row.terms().entrySet()) value = value.add(term.getValue().multiply(values[term.getKey()]));
                        check(value.compareTo(row.upper()) <= 0, "Invalid accelerated count witness");
                    }
                    check(budget.diagnostics().contains("count_monotone_cycles"), "Large feedback did not exercise structural compilation");
                }
            }
            check(budget.reservedBytes() == 0, "Cycle compilation leaked");
        }
        try {
            CountMonotoneCycles.compile(ring(3, true), 300, () -> { throw new CancellationException(); }, () -> false);
            throw new AssertionError("Ignored cancellation");
        } catch (CancellationException expected) { }
        System.out.println("CountMonotoneCyclesTest PASS: slow/damaged/open feedback, reordered models and exact proof replay");
    }
    private static ArrayList<ExactLinearProgram.Constraint> ring(int n, boolean damaged) {
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int i = 0; i < n; i++) {
            BigInteger left = BigInteger.ONE.negate(), right = BigInteger.ONE;
            if (i == n - 1) { left = BigInteger.valueOf(-1_000_000_000); right = BigInteger.valueOf(damaged ? 1_000_000_000 : 999_999_999); }
            rows.add(new ExactLinearProgram.Constraint(Map.of(i, left, (i + 1) % n, right), i == 0 ? BigInteger.ONE.negate() : BigInteger.ZERO));
        }
        return rows;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
