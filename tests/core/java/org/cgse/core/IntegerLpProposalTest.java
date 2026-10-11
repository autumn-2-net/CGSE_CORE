// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;

/** A no-cut numerical witness must reach execution feedback, without becoming a proof or duplicate solver. */
public final class IntegerLpProposalTest {
    private static final List<ExactLinearProgram.Constraint> ROWS = List.of(
            new ExactLinearProgram.Constraint(Map.of(0, BigInteger.TWO, 1, BigInteger.valueOf(3)), BigInteger.valueOf(12)),
            new ExactLinearProgram.Constraint(Map.of(0, BigInteger.TWO.negate(), 1, BigInteger.valueOf(-3)), BigInteger.valueOf(-6)));
    private static final Method SCOUT;
    static {
        try { SCOUT = CountViewSearch.class.getDeclaredMethod("addIntegerLp"); SCOUT.setAccessible(true); }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }

    public static void main(String[] args) throws Exception {
        var budget = budget();
        try (var probe = new CountIntegerLpLearning(); var result = probe.solve(ROWS, values(0, 0), values(10, 10), -1, budget, 16384)) {
            check(result != null && result.integerPoint != null && result.cut == null, "Fixture must yield a point without a cut");
        }
        check(budget.reservedBytes() == 0, "Probe leaked");
        for (var outcome : new CountViewSearch.CandidateOutcome[] {CountViewSearch.CandidateOutcome.REJECTED,
                CountViewSearch.CandidateOutcome.SCHEDULE_DEAD, CountViewSearch.CandidateOutcome.UNRESOLVED,
                CountViewSearch.CandidateOutcome.VERIFIED}) {
            budget = budget();
            try (var models = CountModelViews.create(ROWS, values(0, 0), values(10, 10), budget);
                 var search = new CountViewSearch(models, budget)) {
                check(!scout(search), "Scout displaced initial compilation");
                models.compileLight();
                check(scout(search), "A no-cut exact point was discarded");
                check(models.cutVersion() == 0, "Fixture produced an unexpected cut");
                check(!scout(search), "Scout allowance was renewed");
                search.resume(16384);
                while (!search.step()) {}
                check(search.counts() != null && !search.infeasible(), "Checked proposal did not reach count selection");
                var origin = search.candidateOrigin();
                check(origin.engine().equals("integer_lp_hint"), "A duplicate exact solver consumed the proposal: " + origin);
                for (var row : ROWS) check(holds(row, search.counts()), "Proposal violates a full row");
                search.feedback(origin, CountViewSearch.CandidateOutcome.SCHEDULE_WITNESS, 10);
                search.feedback(origin, outcome, 200);
                check(search.retained(), "A candidate-only result closed the original exact engines");
                search.resume(65536);
                while (!search.step()) {}
                check(search.counts() != null && !search.candidateOrigin().engine().equals("integer_lp_hint"),
                        "Rejected/consumed LP proposal was replayed or lost the original solver");
                check(!scout(search), "Downstream feedback restarted a scout");
            }
            check(budget.reservedBytes() == 0, "Candidate lifecycle leaked");
        }

        // The LP support excludes an unbounded column. Completing it with the
        // lower endpoint is merely a proposal: ALL omitted rows must still hold.
        var sparse = new ArrayList<>(ROWS);
        sparse.add(new ExactLinearProgram.Constraint(Map.of(2, BigInteger.ONE.negate()), BigInteger.ONE.negate()));
        for (long lower : new long[] {0, 1}) {
            budget = budget();
            try (var models = CountModelViews.create(sparse, values(0, 0, lower), new BigInteger[] {BigInteger.TEN, BigInteger.TEN, null}, budget);
                 var search = new CountViewSearch(models, budget)) {
                models.compileLight();
                check(scout(search) == (lower == 1), "Omitted row/domain was treated as zero or ignored");
                if (lower == 1) {
                    search.resume(16384);
                    while (!search.step()) {}
                    check(search.counts() != null && search.counts()[2].equals(BigInteger.ONE), "Boundary completion lost its exact value");
                    for (var row : sparse) check(holds(row, search.counts()), "Sparse proposal escaped full validation");
                }
                check(!search.infeasible(), "A local LP outcome became a full-model negative");
            }
            check(budget.reservedBytes() == 0, "Sparse candidate leak");
        }

        budget = budget();
        try (var models = CountModelViews.create(ROWS, values(0, 0), values(10, 10), budget);
             var search = new CountViewSearch(models, budget)) {
            models.compileLight();
            search.mode(CountPortfolioPolicy.Mode.PROOF);
            check(!scout(search), "A candidate-only arm consumed proof-mode allocation");
            check(!search.infeasible(), "An unused numerical scout proved infeasibility");
        }
        check(budget.reservedBytes() == 0, "Proof-mode scout leaked");

        for (int stop = 1; stop <= 240; stop++) {
            int limit = stop;
            var checks = new AtomicInteger();
            var armed = new AtomicBoolean();
            var cancelled = new PlanningBudget(0, 1_000_000, 128L << 20,
                    () -> armed.get() && checks.incrementAndGet() >= limit, System::nanoTime);
            try (var models = CountModelViews.create(ROWS, values(0, 0), values(10, 10), cancelled);
                 var search = new CountViewSearch(models, cancelled)) {
                models.compileLight();
                armed.set(true);
                try { scout(search); } catch (CancellationException ignored) {}
            }
            check(cancelled.reservedBytes() == 0, "Interrupted scout/proposal leaked");
        }
        System.out.println("Integer LP proposals: no-cut witnesses, downstream rejection/resume, omitted rows, proof mode and 240 cancellations passed");
    }

    private static boolean scout(CountViewSearch search) throws Exception {
        try { return (Boolean) SCOUT.invoke(search); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException failure) throw failure;
            if (e.getCause() instanceof Error failure) throw failure;
            throw e;
        }
    }
    private static boolean holds(ExactLinearProgram.Constraint row, BigInteger[] point) {
        var total = BigInteger.ZERO;
        for (var term : row.terms().entrySet()) total = total.add(term.getValue().multiply(point[term.getKey()]));
        return total.compareTo(row.upper()) <= 0;
    }
    private static BigInteger[] values(long... values) { return Arrays.stream(values).mapToObj(BigInteger::valueOf).toArray(BigInteger[]::new); }
    private static PlanningBudget budget() { return new PlanningBudget(0, 1_000_000, 128L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
