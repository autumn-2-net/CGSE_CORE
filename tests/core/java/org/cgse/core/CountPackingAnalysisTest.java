// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Request ownership and exact boundaries of the compiler's packing descriptor. */
public final class CountPackingAnalysisTest {
    public static void main(String[] args) {
        var lower = new BigInteger[64]; var upper = new BigInteger[64];
        Arrays.fill(lower, BigInteger.ZERO); Arrays.fill(upper, BigInteger.ZERO);
        upper[0] = upper[1] = upper[2] = BigInteger.ONE;
        lower[63] = upper[63] = BigInteger.ONE;
        var rows = List.of(row(41, 2, 3, 1, 37), row(-26, -3, -5, -2, -19), row(3, 1, 1, 1, 0));
        var cancelled = new AtomicBoolean();
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, cancelled::get, System::nanoTime);
        try (var models = CountModelViews.create(rows, lower, upper, budget)) {
            var view = models.available().get(0);
            var structure = models.packingStructure(view);
            check(structure != null && structure.columns.length == 3, "Missing declared free domain");
            check(structure.matrix.length == 1 && structure.rhs[0] == 4 && structure.target == 7,
                    "Fixed-one substitution or box-implied row elimination changed semantics");
            long bytes = budget.reservedBytes(), work = budget.threadWork();
            check(models.packingStructure(view) == structure, "Repeated analysis did not reuse its owner");
            check(budget.reservedBytes() == bytes && budget.threadWork() == work, "Repeated analysis rebuilt its workspace");
            var tightened = upper.clone(); tightened[0] = BigInteger.ZERO;
            models.publishBounds(view, lower, tightened); // x0 = 1 cannot meet the exact demand of seven.
            check(models.domains(view).upper()[0].equals(BigInteger.ZERO), "Test did not publish root propagation");
            check(models.packingStructure(view) == structure && structure.upper[0].equals(BigInteger.ONE),
                    "A shared-bound handoff replaced the declared candidate scope");
            bytes = budget.reservedBytes();
            try (var search = CountPackingCore.create(structure, budget, Long.MAX_VALUE)) {
                check(search != null, "Shared descriptor was not usable");
                while (!search.step()) {}
                var candidate = search.counts();
                check(candidate != null && models.restoreAndCheck(view, candidate) != null, "Auxiliary candidate failed original restoration");
                check(candidate[0].signum() == 0 && candidate[1].equals(BigInteger.ONE) && candidate[2].equals(BigInteger.ONE),
                        "Candidate did not satisfy the unique original solution");
            }
            check(budget.reservedBytes() == bytes, "Backend close released or leaked the owner's descriptor");

            var changed = List.of(row(42, 2, 3, 1, 37), rows.get(1), rows.get(2));
            try (var other = CountModelViews.create(changed, lower, upper, budget)) {
                var nextView = other.available().get(0); var next = other.packingStructure(nextView);
                check(next != structure && next.rhs[0] == 5 && next.upper[0].equals(BigInteger.ONE),
                        "A new inventory reused shifted rows or old propagated bounds");
                try { models.packingStructure(nextView); throw new AssertionError("Accepted a foreign request's view"); }
                catch (IllegalArgumentException expected) {}
            }
            var reversed = List.of(row(3, 1, 1, 1, 0), row(-26, -2, -3, -5, -19), row(41, 1, 2, 3, 37));
            try (var other = CountModelViews.create(reversed, lower, upper, budget)) {
                var next = other.packingStructure(other.available().get(0));
                check(structure.ranks[0] == next.ranks[1] && structure.ranks[1] == next.ranks[2] &&
                        structure.ranks[2] == next.ranks[0], "Structural tie order depends on row or coordinate numbering");
            }
        }
        check(budget.reservedBytes() == 0, "Compiler descriptor lifecycle leaked");

        var manyRows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int i = 0; i < 33; i++) manyRows.add(rows.get(0));
        manyRows.add(rows.get(1));
        try (var models = CountModelViews.create(manyRows, lower, upper, budget)) {
            var view = models.available().get(0); long owned = budget.reservedBytes();
            check(models.packingStructure(view) == null, "Exceeded the auxiliary matrix row limit");
            check(budget.reservedBytes() == owned, "Unsupported analysis leaked partial rows");
            long work = budget.threadWork();
            check(models.packingStructure(view) == null && budget.threadWork() == work,
                    "Repeated an unsupported analysis on the same immutable view");
        }
        check(budget.reservedBytes() == 0, "Unsupported compiler owner leaked");

        // Failure during analysis must release the descriptor reservation, while
        // leaving the model owner's existing allocation intact until its close.
        try (var models = CountModelViews.create(rows, lower, upper, budget)) {
            var view = models.available().get(0); long owned = budget.reservedBytes(); cancelled.set(true);
            try { models.packingStructure(view); throw new AssertionError("Ignored cancellation"); }
            catch (CancellationException expected) {}
            check(budget.reservedBytes() == owned, "Cancelled analysis leaked a partial descriptor");
        }
        check(budget.reservedBytes() == 0, "Cancelled compiler owner leaked");
        System.out.println("Packing auxiliary analysis: exact scope, request isolation, shared reuse, permutations and cleanup passed");
    }

    private static ExactLinearProgram.Constraint row(long rhs, long a, long b, long c, long fixed) {
        var terms = new TreeMap<Integer, BigInteger>();
        terms.put(0, BigInteger.valueOf(a)); terms.put(1, BigInteger.valueOf(b)); terms.put(2, BigInteger.valueOf(c));
        if (fixed != 0) terms.put(63, BigInteger.valueOf(fixed));
        return new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(rhs));
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
