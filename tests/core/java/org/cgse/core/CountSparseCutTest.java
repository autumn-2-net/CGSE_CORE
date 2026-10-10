// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Sparse consequences retain scope/certification without the dense-bound dimension cap. */
public final class CountSparseCutTest {
    public static void main(String[] args) {
        for (int size : new int[]{32, 1024, 1025, 1699, 4096, 8192}) sharing(size);
        refusalAndCancellation();
        System.out.println("Sparse cut sharing: 1024..8192 variables; certification, foreign scopes, duplicates, proof replay, memory refusal and cancellation passed");
    }

    private static void sharing(int size) {
        var budget = budget(() -> false);
        var low = new BigInteger[size];
        var high = new BigInteger[size];
        Arrays.fill(low, BigInteger.ZERO);
        if (size == 32) Arrays.fill(high, BigInteger.ZERO);
        var rows = rows(size);
        var cut = cut(size);
        try (var models = CountModelViews.create(rows, low, high, budget);
             var foreign = CountModelViews.create(rows, low, high, budget);
             var destination = new CountLcg(rows, low, high, budget, 100000)) {
            check(models != null && foreign != null, "wide models not admitted");
            var view = models.available().get(0);
            var alien = foreign.available().get(0);
            Object source = new Object();
            check(models.sharesCuts(view), "registered wide cut scope rejected");
            if (size > 1024) check(!models.sharesBounds(view), "dense domain cap was removed");
            check(!models.sharesCuts(alien), "identical foreign model acquired this scope");
            models.publishCuts(alien, List.of(cut), 0, source);
            check(models.cutVersion() == 0, "foreign cut escaped scope");
            var trial = new CountModelViews.View("trial", rows, low, high, view.shape(), null,
                    CountModelViews.Semantics.RESTRICTED, null);
            models.publishCuts(trial, List.of(cut), 0, source);
            check(models.cutVersion() == 0, "trial cut escaped scope");
            var invalid = new CountLpLearning.Cut(new ExactLinearProgram.Constraint(cut.row().terms(),
                    BigInteger.ONE.negate()), cut.parents(), cut.divisor());
            models.publishCuts(view, List.of(invalid), 0, source);
            check(models.cutVersion() == 0, "invalid linear combination was trusted");

            // Replay through the same production publication/import route.
            // Proof replay has its own dimension/work admission. A larger
            // sparse search must not bypass that boundary when journaling.
            var journal = new CountProof.Journal(16L << 20);
            if (size == 32 || size == 1025) budget.proofJournal(journal);
            models.publishCuts(view, List.of(cut), 0, source);
            check(models.cutVersion() == 1, "sparse wide cut lost");
            models.publishCuts(view, List.of(cut), 0, source);
            check(models.cutVersion() == 1, "duplicate cut published twice");
            check(models.importCuts(alien, 0, destination, destination) == 0, "foreign destination accepted");
            check(models.importCuts(view, 0, source, destination) == 0, "origin imported its own fact");
            if (size == 1025) {
                check(models.importCuts(view, 0, destination, destination) == 0, "unreplayed transfer escaped proof boundary");
                check(journal.affine().isEmpty(), "unsupported certificate was recorded");
                budget.proofJournal(null);
            }
            check(models.importCuts(view, 0, destination, destination) == 1, "wide cut not imported: " + size);
            check(models.importCuts(view, 0, destination, destination) == 0, "duplicate imported row");
            if (size == 32) {
                check(!journal.affine().isEmpty(), "missing transfer certificate");
                for (var proof : journal.affine())
                    check(CountAffineProof.verify(proof, 2_000_000) == CountProof.Verdict.VERIFIED,
                            "independent wide proof replay failed");
            }
        }
        check(budget.reservedBytes() == 0, "wide cut reservation leak");
    }

    private static void refusalAndCancellation() {
        var cancelled = new AtomicBoolean();
        var budget = budget(cancelled::get);
        var low = new BigInteger[1699];
        Arrays.fill(low, BigInteger.ZERO);
        try (var models = CountModelViews.create(rows(low.length), low, new BigInteger[low.length], budget)) {
            var view = models.available().get(0);
            var cuts = List.of(cut(low.length));
            long held = budget.availableBytes();
            check(budget.tryReserve(held), "refusal guard");
            try {
                models.publishCuts(view, cuts, 0, new Object());
                check(models.cutVersion() == 0, "sharing ignored memory allowance");
            } finally {
                budget.release(held);
            }
            cancelled.set(true);
            try {
                models.publishCuts(view, cuts, 0, new Object());
                throw new AssertionError("sharing ignored cancellation");
            } catch (CancellationException expected) {
                check(models.cutVersion() == 0, "interrupted publication marked complete");
            }
            cancelled.set(false);
            models.publishCuts(view, cuts, 0, new Object());
            check(models.cutVersion() == 1, "optional refusal permanently suppressed later publication");
        }
        check(budget.reservedBytes() == 0, "refused/cancelled cut reservation leak");
    }

    private static List<ExactLinearProgram.Constraint> rows(int n) {
        return List.of(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE, 1, BigInteger.ONE.negate(),
                        2, BigInteger.ONE, n - 2, BigInteger.ONE), BigInteger.ZERO),
                new ExactLinearProgram.Constraint(Map.of(1, BigInteger.ONE, 2, BigInteger.ONE.negate(),
                        n - 1, BigInteger.ONE.negate()), BigInteger.ZERO));
    }

    private static CountLpLearning.Cut cut(int n) {
        return new CountLpLearning.Cut(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE,
                n - 2, BigInteger.ONE, n - 1, BigInteger.ONE.negate()), BigInteger.ZERO),
                Map.of(0, BigInteger.ONE, 1, BigInteger.ONE), BigInteger.ONE);
    }

    private static PlanningBudget budget(java.util.function.BooleanSupplier cancelled) {
        return new PlanningBudget(0, 20_000_000, 64L << 20, cancelled, () -> 0L);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
