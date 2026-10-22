// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Corrupt one inequality of a balanced weighted ring, then remove or repair it. */
public final class CountCycleEliminationTest {
    private static int checks;
    public static void main(String[] args) {
        for (int n : new int[] {3, 4, 6, 8}) for (int variant = 0; variant < 12; variant++) {
            var scale = variant % 3 == 0 ? BigInteger.ONE.shiftLeft(96) : BigInteger.valueOf(1 + variant);
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            int[] primes = {2, 3, 5, 7, 11, 13, 17, 19};
            for (int i = 0; i < n; i++) rows.add(new ExactLinearProgram.Constraint(Map.of(i, scale.multiply(BigInteger.valueOf(primes[i])),
                    (i + 1) % n, scale.multiply(BigInteger.valueOf(-primes[(i + 1) % n]))),
                    i == n - 1 ? BigInteger.ONE.negate() : BigInteger.ZERO));
            Collections.shuffle(rows, new Random(variant));
            var low = new BigInteger[n]; var high = new BigInteger[n]; Arrays.fill(low, BigInteger.ZERO);
            var recent = java.util.stream.IntStream.range(0, n).boxed().toList();
            long[] visits = {0};
            var cut = CountCycleElimination.find(rows, recent, low, high, () -> visits[0]++, () -> visits[0] >= 65536);
            check(cut != null, "Failed to eliminate a damaged " + n + "-row ring");
            var axioms = rows.stream().map(CountProof::row).toList();
            var step = new CountProof.Combination(cut.parents(), cut.divisor(), CountProof.row(cut.row()));
            check(CountProof.verify(new CountProof.Derivation("ring", n, axioms, List.of(step)), 1_000_000)
                    == CountProof.Verdict.VERIFIED, "Invalid exact cycle combination");
            // Summing all original rows gives 0 <= -1. Removing any link or
            // restoring its upper bound to zero removes that contradiction.
            var fixed = new ArrayList<ExactLinearProgram.Constraint>();
            rows.forEach(r -> fixed.add(new ExactLinearProgram.Constraint(r.terms(), BigInteger.ZERO)));
            check(CountCycleElimination.find(fixed, recent, low, high, () -> {}, () -> false) == null,
                    "Balanced cycle acquired a false conflict");
            if (n <= 6) for (int removed = 0; removed < n; removed++) {
                var broken = new ArrayList<>(rows); broken.remove(removed);
                check(CountCycleElimination.find(broken, java.util.stream.IntStream.range(0, broken.size()).boxed().toList(),
                        low, high, () -> {}, () -> false) == null, "Open chain acquired a false conflict");
            }
            var budget = new PlanningBudget(0, 200_000, 32L << 20, () -> false, System::nanoTime);
            try (var search = new CountLcg(rows, low, high, budget, 150_000, true)) {
                while (!search.step()) {}
                check(search.infeasible(), "Lazy bounds still walk around the inconsistent ring: " + budget.diagnostics());
                check(CountProof.verify(search.certificate(), 1_000_000) == CountProof.Verdict.VERIFIED, "Unverified LCG conclusion");
            }
            check(budget.reservedBytes() == 0, "Cycle proof workspace leaked");
        }
        var row = new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE, 1, BigInteger.ONE.negate()), BigInteger.ZERO);
        try {
            CountCycleElimination.find(List.of(row, row, row), List.of(0, 1, 2), new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO},
                    new BigInteger[2], () -> { throw new java.util.concurrent.CancellationException(); }, () -> false);
            throw new AssertionError("Cancellation was swallowed");
        } catch (java.util.concurrent.CancellationException expected) { checks++; }
        System.out.println("CountCycleEliminationTest PASS: checks=" + checks);
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
