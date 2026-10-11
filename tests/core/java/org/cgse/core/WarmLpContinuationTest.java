// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Paired dual-simplex trajectories across RHS changes, worker handoffs and scope invalidation. */
public final class WarmLpContinuationTest {
    private record Model(int n, List<ExactLinearProgram.Constraint> rows, BigInteger[] cost) {}

    public static void main(String[] args) throws Exception {
        long resumes = 0, warmWork = 0, coldWork = 0;
        var workers = new ExecutorService[] {Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor()};
        try {
            for (int seed = 0; seed < 24; seed++) {
                var model = model(seed);
                var referenceBudget = budget();
                var budget = budget();
                try (var reference = new CountNumericRelaxation.Session();
                     var retained = new CountNumericRelaxation.Session(true)) {
                    same(reference.solve(model.n, model.rows, model.cost, referenceBudget, 2_000_000),
                            retained.solve(model.n, model.rows, model.cost, budget, 2_000_000));
                    coldWork += budget.nodes();
                    long before = budget.nodes();
                    for (int branch = 0; branch < 3; branch++) {
                        var rows = changed(model, branch);
                        var expected = reference.solve(model.n, rows, model.cost, referenceBudget, 2_000_000);
                        check(expected != null, "Reference warm solve failed");
                        CountNumericRelaxation.Result result = null;
                        for (int visit = 0; result == null && visit < 1024; visit++) {
                            long start = budget.nodes();
                            result = workers[visit % 2].submit(() -> retained.solve(model.n, rows, model.cost, budget, 512))
                                    .get(30, TimeUnit.SECONDS);
                            check(budget.nodes() - start < 8192, "Unbounded local continuation");
                            if (result == null) check(retained.pending(), "Lost warm progress");
                        }
                        same(expected, result);
                        check(!retained.pending(), "Completed solve remained pending");
                    }
                    check(retained.rebuilt() == 1 && retained.fallbacks() == 0, "Slices unnecessarily rebuilt the LP basis");
                    check(retained.reused() == 3, "Warm completions were not counted once each");
                    resumes += retained.warmResumed();
                    warmWork += budget.nodes() - before;
                }
                check(budget.reservedBytes() == 0 && referenceBudget.reservedBytes() == 0, "LP workspace leaked");
            }
        } finally { for (var worker : workers) worker.shutdownNow(); }
        check(resumes > 50, "Insufficient warm continuation coverage");
        expensiveWarmBasis();
        invalidation();
        cancellation();
        System.out.println("Warm LP: 24 models / 72 RHS changes, resumes=" + resumes + "; warm work=" + warmWork +
                "; initial cold work=" + coldWork + "; paired points and duals, worker handoffs, invalidation and cancellation passed");
    }

    private static void expensiveWarmBasis() {
        int n = 256;
        var root = new ArrayList<ExactLinearProgram.Constraint>();
        var changed = new ArrayList<ExactLinearProgram.Constraint>();
        var cost = new BigInteger[n];
        Arrays.fill(cost, BigInteger.ZERO);
        for (int i = 0; i < n; i++) {
            root.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE.negate()), BigInteger.ZERO));
            changed.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE.negate()), BigInteger.ONE.negate()));
            var upper = new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), BigInteger.valueOf(7));
            root.add(upper);
            changed.add(upper);
        }
        var rows = List.copyOf(changed);
        var budget = budget();
        try (var session = new CountNumericRelaxation.Session(true)) {
            check(session.solve(n, List.copyOf(root), cost, budget, 2_000_000) != null, "No cheap initial basis");
            CountNumericRelaxation.Result result = null;
            for (int visit = 0; result == null && visit < 512; visit++)
                result = session.solve(n, rows, cost, budget, 4096);
            check(result != null && !result.phaseOneInfeasible(), "Lost feasible RHS after warm retreat");
            for (double value : result.point()) check(value >= 1 - 1e-8 && value <= 7 + 1e-8, "Invalid fallback point");
            check(session.fallbacks() > 0 && session.warmResumed() < 32,
                    "A cheap root's expensive dual basis monopolized all visits");
        }
        check(budget.reservedBytes() == 0, "Warm retreat leaked workspace");
    }

    private static void invalidation() {
        for (int mutation = 0; mutation < 5; mutation++) {
            var model = model(7);
            var budget = budget();
            var nextBudget = mutation == 4 ? budget() : budget;
            try (var session = new CountNumericRelaxation.Session(true)) {
                check(session.solve(model.n, model.rows, model.cost, budget, 2_000_000) != null, "Missing initial basis");
                var original = changed(model, 0);
                check(session.solve(model.n, original, model.cost, budget, 128) == null && session.pending(), "Missing warm frontier");
                var rows = new ArrayList<>(original);
                var cost = model.cost.clone();
                if (mutation == 0) rows.set(0, new ExactLinearProgram.Constraint(rows.get(0).terms(), BigInteger.valueOf(1000)));
                if (mutation == 1) rows.set(0, new ExactLinearProgram.Constraint(Map.of(0, BigInteger.TWO), BigInteger.valueOf(14)));
                if (mutation == 2) rows.add(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE), BigInteger.valueOf(3)));
                if (mutation == 3) cost[0] = cost[0].add(BigInteger.ONE);
                long resumes = session.warmResumed();
                var result = session.solve(model.n, List.copyOf(rows), cost, nextBudget, 2_000_000);
                check(result != null && !result.phaseOneInfeasible() && !session.pending(), "Replacement failed");
                check(session.warmResumed() == resumes, "Resumed a different RHS, matrix, objective or request");
                check(mutation == 4 || session.invalidated() > 0, "Changed scope reused pending dual state");
                if (mutation == 4) check(budget.reservedBytes() == 0, "Old request retained reservations");
            }
            check(budget.reservedBytes() == 0 && nextBudget.reservedBytes() == 0, "Invalidated workspace leaked");
        }
    }

    private static void cancellation() {
        var cancelled = new AtomicBoolean();
        var budget = new PlanningBudget(0, 4_000_000, 64L << 20, cancelled::get, System::nanoTime);
        var model = model(3);
        try (var session = new CountNumericRelaxation.Session(true)) {
            check(session.solve(model.n, model.rows, model.cost, budget, 2_000_000) != null, "Missing cancellation basis");
            var rows = changed(model, 0);
            check(session.solve(model.n, rows, model.cost, budget, 128) == null && session.pending(), "Missing cancellation frontier");
            cancelled.set(true);
            try {
                session.solve(model.n, rows, model.cost, budget, 512);
                throw new AssertionError("Cancellation ignored");
            } catch (CancellationException expected) { /* Terminal cancellation discards the whole frontier. */ }
            check(!session.pending() && budget.reservedBytes() == 0, "Cancelled dual workspace retained");
        }
    }

    private static Model model(int seed) {
        int n = 64;
        var random = new Random(9101100L + seed);
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        var cost = new BigInteger[n];
        for (int i = 0; i < n; i++) cost[i] = BigInteger.valueOf(1 + random.nextInt(7));
        for (int r = 0; r < 80; r++) {
            var terms = new LinkedHashMap<Integer, BigInteger>();
            while (terms.size() < 9) terms.put(random.nextInt(n), BigInteger.valueOf(1 + random.nextInt(9)));
            var rhs = terms.values().stream().reduce(BigInteger.ZERO, BigInteger::add).multiply(BigInteger.valueOf(3));
            rows.add(new ExactLinearProgram.Constraint(terms, rhs));
        }
        for (int i = 0; i < n; i++) rows.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), BigInteger.valueOf(7)));
        return new Model(n, List.copyOf(rows), cost);
    }

    private static List<ExactLinearProgram.Constraint> changed(Model model, int branch) {
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int i = 0; i < model.rows.size(); i++) {
            var row = model.rows.get(i);
            var rhs = row.upper();
            if (i < 80) rhs = branch == 2 && i == 0 ? BigInteger.ONE.negate() :
                    branch == 1 ? rhs.add(BigInteger.ONE) : rhs.multiply(BigInteger.valueOf(9)).divide(BigInteger.TEN);
            rows.add(new ExactLinearProgram.Constraint(row.terms(), rhs));
        }
        return List.copyOf(rows);
    }

    private static PlanningBudget budget() { return new PlanningBudget(0, 8_000_000, 64L << 20, () -> false, System::nanoTime); }
    private static void same(CountNumericRelaxation.Result expected, CountNumericRelaxation.Result actual) {
        check(expected != null && actual != null, "Missing paired result");
        check(expected.phaseOneInfeasible() == actual.phaseOneInfeasible(), "Pause changed numeric feasibility");
        check(Arrays.equals(expected.point(), actual.point()), "Pause changed point or pivot order");
        check(Arrays.equals(expected.dual(), actual.dual()), "Pause changed dual coefficients");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
