// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Numerical prices only select rows; independent integer replay establishes validity. */
public final class CountPackingExplanationTest {
    public static void main(String[] args) {
        for (int n : new int[] {64, 128, 512}) sharing(n);
        fixedCoordinates();
        widePoolLimit();
        System.out.println("Packing explanations: exact original rows, wide propagation, proof replay, changed stock, invalid prices and bounded sharing passed");
    }

    private static void sharing(int n) {
        var budget = budget(() -> false);
        var low = new BigInteger[n]; var high = new BigInteger[n];
        Arrays.fill(low, BigInteger.ZERO); Arrays.fill(high, BigInteger.ONE);
        var capacity = new TreeMap<Integer, BigInteger>(); var demand = new TreeMap<Integer, BigInteger>();
        capacity.put(0, BigInteger.valueOf(400)); demand.put(0, BigInteger.valueOf(-400));
        for (int i = 1; i < n; i++) { capacity.put(i, BigInteger.valueOf(3)); demand.put(i, BigInteger.ONE.negate()); }
        var rows = List.of(row(capacity, 400), row(demand, -400));
        var journal = new CountProof.Journal(16L << 20);
        budget.proofJournal(journal);
        try (var models = CountModelViews.create(rows, low, high, budget);
             var solver = new CountLcg(rows, low, high, budget, 1_000_000)) {
            var view = models.available().get(0); var analysis = models.packingStructure(view);
            check(analysis != null, "Missing packing descriptor");
            var cut = analysis.explain(new double[] {1});
            verify(view, cut);
            check(cut.row().terms().size() == n - 1 && cut.row().upper().signum() == 0,
                    "Lost cancellation or exact gcd normalization");
            models.publishCuts(view, List.of(cut), 0, analysis);
            check(models.cutVersion() == 1, "Wide original-row certificate was lost");
            int imported = models.importCuts(view, 0, solver, solver);
            // Independent affine/LCG implication replay has its own budget.
            // A wide derivation must not silently bypass that proof boundary.
            if (imported != 0) check(!journal.affine().isEmpty(), "No independent transfer certificate");
            for (var proof : journal.affine())
                check(CountAffineProof.verify(proof, 2_000_000) == CountProof.Verdict.VERIFIED, "Affine proof failed");
            budget.proofJournal(null);
            try (var ordinary = new CountLcg(rows, low, high, budget, 1_000_000)) {
                check(models.importCuts(view, 0, ordinary, ordinary) == 1, "Certified cut was not propagated: " + n);
                while (!ordinary.step()) {}
                var result = ordinary.counts();
                check(result != null && result[0].equals(BigInteger.ONE), "Lost unique feasible packing");
                for (int i = 1; i < n; i++) check(result[i].signum() == 0, "Nonzero dominated choice");
            }

            // The old stronger RHS is invalid in the new inventory. Recreate
            // from its own rows instead of treating a price as a stock theorem.
            var changed = List.of(row(capacity, 403), rows.get(1));
            try (var other = CountModelViews.create(changed, low, high, budget)) {
                var next = other.available().get(0);
                other.publishCuts(next, List.of(cut), 0, analysis);
                check(other.cutVersion() == 0, "Old stock constraint escaped exact recertification");
                var refreshed = other.packingStructure(next).explain(new double[] {1});
                verify(next, refreshed);
                check(refreshed.row().upper().equals(BigInteger.ONE), "New RHS was not floored exactly");
            }
        }
        check(budget.reservedBytes() == 0, "Wide proof workspace leaked");
    }

    private static void fixedCoordinates() {
        var cancelled = new AtomicBoolean(); var budget = budget(cancelled::get);
        var low = new BigInteger[64]; var high = new BigInteger[64];
        Arrays.fill(low, BigInteger.ZERO); Arrays.fill(high, BigInteger.ZERO);
        high[0] = high[1] = high[2] = BigInteger.ONE;
        low[63] = high[63] = BigInteger.ONE;
        var rows = List.of(row(Map.of(0, BigInteger.ONE, 1, BigInteger.ONE, 2, BigInteger.ONE), 3),
                row(Map.of(0, BigInteger.valueOf(-3), 1, BigInteger.valueOf(-5), 2, BigInteger.valueOf(-2), 63, BigInteger.valueOf(-19)), -26),
                row(Map.of(0, BigInteger.valueOf(2), 1, BigInteger.valueOf(3), 2, BigInteger.ONE, 63, BigInteger.valueOf(37)), 41));
        try (var models = CountModelViews.create(rows, low, high, budget)) {
            var view = models.available().get(0); var analysis = models.packingStructure(view);
            for (double price : new double[] {0, 0.5, 1.234567, 2, Double.NaN, Double.POSITIVE_INFINITY, -1, Double.MAX_VALUE}) {
                var cut = analysis.explain(new double[] {price}); verify(view, cut);
                check(cut.parents().containsKey(1) && !cut.parents().containsKey(0), "Dropped row changed parent indices");
                // Exhaust all original free assignments, retaining the fixed
                // one in the original row arithmetic, independent of the LP.
                int feasible = 0;
                for (int mask = 0; mask < 8; mask++) {
                    var point = low.clone();
                    for (int i = 0; i < 3; i++) point[i] = (mask & 1 << i) == 0 ? BigInteger.ZERO : BigInteger.ONE;
                    if (rows.stream().allMatch(r -> satisfies(r, point))) {
                        feasible++;
                        check(satisfies(cut.row(), point), "Combination removed an original feasible assignment");
                    }
                }
                check(feasible == 1, "Exhaustive oracle changed");
            }
            long bytes = budget.reservedBytes(); cancelled.set(true);
            try { analysis.explain(new double[] {1}); throw new AssertionError("Ignored cancellation"); }
            catch (CancellationException expected) {}
            check(budget.reservedBytes() == bytes, "Cancelled explanation changed ownership");
        }
        check(budget.reservedBytes() == 0, "Fixed-coordinate workspace leaked");
    }

    private static void widePoolLimit() {
        var budget = budget(() -> false); int n = 128;
        var low = new BigInteger[n]; var high = new BigInteger[n];
        Arrays.fill(low, BigInteger.ZERO); Arrays.fill(high, BigInteger.ONE);
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        var cuts = new ArrayList<CountLpLearning.Cut>();
        for (int r = 0; r < 6; r++) {
            var terms = new TreeMap<Integer, BigInteger>();
            for (int i = 0; i < n; i++) terms.put(i, BigInteger.valueOf(i == r ? 2 : 1));
            rows.add(row(terms, 100));
            cuts.add(new CountLpLearning.Cut(rows.get(r), Map.of(r, BigInteger.ONE), BigInteger.ONE));
        }
        var view = new CountModelViews.View("wide-limit", rows, low, high,
                new CountModelViews.Shape(n, (long) n * rows.size(), 8, 8), null,
                CountModelViews.Semantics.EQUIVALENT, null);
        try (var pool = CountViewCuts.create(budget, view)) {
            pool.publish(view, cuts, 0, new Object());
            check(pool.version() == 4, "Wide facts did not obey their own cap");
            var forged = new CountLpLearning.Cut(row(cuts.get(0).row().terms(), 99), cuts.get(0).parents(), BigInteger.ONE);
            verifyInvalid(view, forged);
        }
        check(budget.reservedBytes() == 0, "Bounded pool leaked");
    }

    private static void verify(CountModelViews.View view, CountLpLearning.Cut cut) {
        check(CountProof.verify(proof(view, cut), 2_000_000) == CountProof.Verdict.VERIFIED, "Independent exact derivation failed");
    }

    private static void verifyInvalid(CountModelViews.View view, CountLpLearning.Cut cut) {
        check(CountProof.verify(proof(view, cut), 2_000_000) == CountProof.Verdict.INVALID, "Forged derivation accepted");
    }

    private static CountProof.Derivation proof(CountModelViews.View view, CountLpLearning.Cut cut) {
        return new CountProof.Derivation("packing-original-rows", view.lower().length, view.rows().stream().map(CountProof::row).toList(),
                List.of(new CountProof.Combination(cut.parents(), cut.divisor(), CountProof.row(cut.row()))));
    }

    private static boolean satisfies(ExactLinearProgram.Constraint row, BigInteger[] point) {
        var value = BigInteger.ZERO;
        for (var term : row.terms().entrySet()) value = value.add(term.getValue().multiply(point[term.getKey()]));
        return value.compareTo(row.upper()) <= 0;
    }

    private static ExactLinearProgram.Constraint row(Map<Integer, BigInteger> terms, long bound) {
        return new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(bound));
    }

    private static PlanningBudget budget(java.util.function.BooleanSupplier cancelled) {
        return new PlanningBudget(0, 40_000_000, 64L << 20, cancelled, () -> 0L);
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
