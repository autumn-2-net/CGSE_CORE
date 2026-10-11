// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Cold simplex slices publish only complete pivots and never become exact proofs. */
public final class ColdLpContinuationTest {
    private record Model(int n, List<ExactLinearProgram.Constraint> rows, BigInteger[] cost) {}

    public static void main(String[] args) throws Exception {
        long resumes = 0;
        var workers = new ExecutorService[] {Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor()};
        try {
            for (int seed = 0; seed < 24; seed++) {
                var model = model(seed);
                var referenceBudget = budget(4_000_000, 64L << 20);
                CountNumericRelaxation.Result reference;
                try (var whole = new CountNumericRelaxation.Session()) {
                    reference = whole.solve(model.n, model.rows, model.cost, referenceBudget, 2_000_000);
                    check(reference != null, "Reference did not complete: " + seed);
                }
                var budget = budget(4_000_000, 64L << 20);
                try (var session = new CountNumericRelaxation.Session(true)) {
                    CountNumericRelaxation.Result result = null;
                    int visits = 0;
                    while (result == null && visits++ < 512) {
                        long before = budget.nodes();
                        result = workers[visits % workers.length].submit(() -> session.solve(model.n, model.rows, model.cost, budget, 2048))
                                .get(30, TimeUnit.SECONDS);
                        check(budget.nodes() - before < 8192, "A local visit exceeded one complete pivot");
                        if (result == null) check(session.pending(), "Cold tableau lost its continuation: " + seed);
                    }
                    check(result != null && !session.pending(), "Cold continuation did not finish");
                    same(reference, result);
                    check(session.rebuilt() == 1, "Unchanged cold model rebuilt");
                    resumes += session.resumed();
                    // A completed basis may be re-used with a different RHS.
                    var relaxed = new ArrayList<ExactLinearProgram.Constraint>();
                    for (var row : model.rows) relaxed.add(new ExactLinearProgram.Constraint(row.terms(), row.upper().add(BigInteger.ONE)));
                    var warm = session.solve(model.n, relaxed, model.cost, budget, 2_000_000);
                    check(warm != null, "Completed cold basis was unusable by warm solve");
                    check(session.reused() == 1, "Completed cold basis did not reach normal warm path");
                }
                check(budget.reservedBytes() == 0 && referenceBudget.reservedBytes() == 0, "Numeric workspace leaked");
            }
        } finally { for (var worker : workers) worker.shutdownNow(); }
        check(resumes > 100, "No significant cold continuation coverage");
        invalidation();
        projectedContinuation();
        maximumSupport();
        limits();
        System.out.println("Cold LP continuation: 24 paired trajectories; resumes=" + resumes +
                "; complete pivots, worker handoffs, changed scopes, cancellation and accounting passed");
    }

    private static void projectedContinuation() throws Exception {
        var model = model(2, 144, 72);
        var low = new BigInteger[model.n];
        var high = new BigInteger[model.n];
        Arrays.fill(low, BigInteger.ZERO);
        Arrays.fill(high, BigInteger.valueOf(7));
        var budget = budget(4_000_000, 64L << 20);
        var continuationBudget = budget;
        var workers = new ExecutorService[] {Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor()};
        try (var session = new CountIntegerLpLearning()) {
            int proposals = 0, visits = 0;
            for (; visits < 256 && proposals == 0; visits++) {
                long allowance = visits == 0 ? 65536 : 4096;
                try (var result = workers[visits % 2].submit(() -> session.solve(model.rows, low, high, -1, continuationBudget, allowance))
                        .get(30, TimeUnit.SECONDS)) {
                    if (visits == 0) check(result == null && session.pending(), "Large projected fixture did not pause");
                    if (result != null) proposals++;
                }
            }
            check(proposals == 1 && !session.pending(), "Small projected visits never reached a proposal");
            check(visits > 2, "Projected continuation not exercised");
        } finally { for (var worker : workers) worker.shutdownNow(); }
        check(budget.reservedBytes() == 0, "Cached projection leaked after proposal");

        var pointField = CountLcg.class.getDeclaredField("relaxationPoint");
        pointField.setAccessible(true);
        var integerField = CountLcg.class.getDeclaredField("integerLpSession");
        integerField.setAccessible(true);
        var numericalField = CountIntegerLpLearning.class.getDeclaredField("numerical");
        numericalField.setAccessible(true);
        var requiredField = CountLpLearning.Session.class.getDeclaredField("continuationWork");
        requiredField.setAccessible(true);
        var ownerBudget = budget(2_000_000, 64L << 20);
        var ownerWorkers = new ExecutorService[] {Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor()};
        try (var owner = new CountLcg(model.rows, low, high, ownerBudget, 131072).integerRelaxation()) {
            boolean proposal = false;
            int handoffs = 0;
            for (; handoffs < 256 && !proposal; handoffs++) {
                proposal = ownerWorkers[handoffs % 2].submit(() -> {
                    if (owner.paused()) owner.resume(4096);
                    do {
                        boolean done = owner.step();
                        if (pointField.get(owner) != null) return true;
                        if (done) return false;
                    } while (true);
                }).get(30, TimeUnit.SECONDS);
                check(proposal || owner.paused(), "Owner terminated before numerical proposal");
                if (handoffs == 0 && owner.paused()) {
                    // Exercise a valid continuation request larger than the
                    // scheduler's quantum. It must accrue previous unused work,
                    // not reset the local credit every time the worker resumes.
                    var integer = (CountIntegerLpLearning) integerField.get(owner);
                    check(integer.pending(), "No retained owner state to hand off");
                    requiredField.setLong(numericalField.get(integer), 8192);
                }
            }
            check(proposal && handoffs > 1, "Actual 4096-work LCG owner did not advance its cold proposal");
        } finally { for (var worker : ownerWorkers) worker.shutdownNow(); }
        check(ownerBudget.reservedBytes() == 0, "LCG owner retained projected memory after close");

        var limitedBudget = budget(2_000_000, 64L << 20);
        try (var owner = new CountLcg(model.rows, low, high, limitedBudget, 131072).integerRelaxation()) {
            while (!owner.step()) {}
            var integer = (CountIntegerLpLearning) integerField.get(owner);
            check(owner.paused() && integer.pending(), "No cold owner for the shrinking-cap test");
            requiredField.setLong(numericalField.get(integer), 8192);
            limitedBudget.charge(limitedBudget.remainingWork() - 32768);
            owner.resume(4096);
            for (int i = 0; i < 2048 && integer.pending(); i++) owner.step();
            check(!integer.pending(), "Optional LP monopolized a request below its required work share");
        }
        check(limitedBudget.reservedBytes() == 0, "Abandoned pending proposal leaked");

        // Caller-owned arrays may mutate while the numerical phase is paused.
        // An unsupported replacement must also discard the old pending scope.
        for (boolean changeRow : new boolean[] {false, true}) {
            budget = budget(4_000_000, 64L << 20);
            try (var session = new CountLpLearning.Session(true, true)) {
                var small = model(3);
                var lower = new BigInteger[small.n]; Arrays.fill(lower, BigInteger.ZERO);
                var upper = new BigInteger[small.n]; Arrays.fill(upper, BigInteger.valueOf(7));
                try (var result = session.solve(small.rows, lower, upper, budget, 4096)) {
                    check(result == null && session.pending(), "No projected pending scope to invalidate");
                }
                var rows = new ArrayList<>(small.rows);
                if (changeRow) rows.set(0, new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE.shiftLeft(2048)), BigInteger.ONE));
                else upper[0] = null;
                try (var result = session.solve(rows, lower, upper, budget, 65536)) {
                    check(result == null && !session.pending(), "Unsupported scope retained old cold progress");
                }
            }
            check(budget.reservedBytes() == 0, "Invalidated projected scope leaked");
        }
    }

    private static void maximumSupport() {
        var model = model(2, 128, 192); // 512 input rows; up to 640 projected rows with domains.
        var lower = new BigInteger[128]; Arrays.fill(lower, BigInteger.ZERO);
        var upper = new BigInteger[128]; Arrays.fill(upper, BigInteger.valueOf(7));
        for (boolean impossible : new boolean[] {false, true}) {
            var rows = new ArrayList<>(model.rows);
            if (impossible) {
                var first = rows.get(0);
                // Its exact opposite remains, independently proving contradiction.
                rows.set(0, new ExactLinearProgram.Constraint(first.terms(), first.upper().subtract(BigInteger.ONE)));
            }
            var budget = budget(8_000_000, 64L << 20);
            boolean finished = false;
            try (var session = new CountLpLearning.Session(true, true)) {
                for (int visit = 0; visit < 2048 && !finished; visit++) {
                    long grant = visit == 0 ? 65536 : Math.max(4096, session.continuationWork());
                    try (var result = session.solve(rows, lower, upper, budget, grant)) {
                        if (result != null) {
                            check(result.numericalInfeasible == impossible, "Maximum support changed numerical feasibility");
                            if (result.cut != null) verifyCut(rows, result.cut);
                            // A numerical negative without an exact explanation
                            // remains a proposal status, never a learned row.
                            finished = true;
                        } else if (!session.pending()) finished = true; // Safe optional refusal is allowed.
                    }
                }
                check(finished && !session.pending(), "Maximum support stayed pending indefinitely");
            }
            check(budget.reservedBytes() == 0, "Maximum support retained memory");
        }
    }

    private static void verifyCut(List<ExactLinearProgram.Constraint> rows, CountLpLearning.Cut cut) {
        var terms = new TreeMap<Integer, BigInteger>();
        var rhs = BigInteger.ZERO;
        for (var parent : cut.parents().entrySet()) {
            check(parent.getValue().signum() >= 0, "Negative proof multiplier");
            var row = rows.get(parent.getKey());
            rhs = rhs.add(parent.getValue().multiply(row.upper()));
            for (var term : row.terms().entrySet())
                terms.merge(term.getKey(), parent.getValue().multiply(term.getValue()), BigInteger::add);
        }
        check(cut.divisor().signum() > 0, "Invalid proof divisor");
        terms.replaceAll((id, a) -> {
            var qr = a.divideAndRemainder(cut.divisor());
            check(qr[1].signum() == 0, "Inexact proof coefficient");
            return qr[0];
        });
        terms.values().removeIf(a -> a.signum() == 0);
        var qr = rhs.divideAndRemainder(cut.divisor());
        var bound = qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
        check(terms.equals(cut.row().terms()) && bound.equals(cut.row().upper()), "Invalid exact row proof");
    }

    private static void invalidation() {
        var model = model(3);
        for (int mutation = 0; mutation < 4; mutation++) {
            var budget = budget(4_000_000, 64L << 20);
            try (var session = new CountNumericRelaxation.Session(true)) {
                check(session.solve(model.n, model.rows, model.cost, budget, 2048) == null && session.pending(), "No pending model");
                var rows = new ArrayList<>(model.rows);
                var costs = model.cost.clone();
                if (mutation == 0) {
                    var row = rows.get(0);
                    rows.set(0, new ExactLinearProgram.Constraint(row.terms(), row.upper().add(BigInteger.ONE)));
                } else if (mutation == 1) {
                    costs[0] = costs[0].add(BigInteger.ONE);
                } else if (mutation == 2) {
                    Collections.swap(rows, 0, 2);
                } else {
                    var row = rows.get(0);
                    var terms = new TreeMap<>(row.terms());
                    terms.merge(0, BigInteger.ONE, BigInteger::add);
                    rows.set(0, new ExactLinearProgram.Constraint(terms, row.upper()));
                }
                var actual = session.solve(model.n, rows, costs, budget, 2_000_000);
                CountNumericRelaxation.Result expected;
                var freshBudget = budget(4_000_000, 64L << 20);
                try (var fresh = new CountNumericRelaxation.Session()) {
                    expected = fresh.solve(model.n, rows, costs, freshBudget, 2_000_000);
                }
                check(actual != null && expected != null, "Changed model failed numerical completion");
                same(expected, actual);
                check(session.rebuilt() == 2 && session.invalidated() == 1, "Partial basis escaped model scope");
                check(freshBudget.reservedBytes() == 0, "Fresh model leaked");
            }
            check(budget.reservedBytes() == 0, "Changed model leaked");
        }
    }

    private static void limits() {
        var model = model(11);
        for (int stop : new int[] {1, 20, 100, 400, 1000, 3000}) {
            var checks = new AtomicInteger();
            var budget = new PlanningBudget(0, 4_000_000, 64L << 20,
                    () -> checks.incrementAndGet() >= stop, System::nanoTime);
            try (var session = new CountNumericRelaxation.Session(true)) {
                try {
                    for (int i = 0; i < 512; i++) if (session.solve(model.n, model.rows, model.cost, budget, 2048) != null) break;
                } catch (CancellationException expected) {
                    check(!session.pending() && budget.reservedBytes() == 0, "Cancelled partial pivot retained");
                }
            }
            check(budget.reservedBytes() == 0, "Cancellation leaked");
        }
        for (long limit : new long[] {1, 500, 2500, 10000}) {
            var budget = budget(limit, 64L << 20);
            try (var session = new CountNumericRelaxation.Session(true)) {
                try {
                    for (int i = 0; i < 512; i++) if (session.solve(model.n, model.rows, model.cost, budget, 2048) != null) break;
                } catch (PlanningBudget.Exhausted expected) {
                    check(!session.pending() && budget.reservedBytes() == 0, "Exhausted partial pivot retained");
                }
            }
            check(budget.reservedBytes() == 0, "Request cutoff leaked");
            check(budget.nodes() <= limit + 16, "Local continuation bypassed global work cap");
        }
        for (long memory : new long[] {1, 1024, 65536}) {
            var budget = budget(4_000_000, memory);
            try (var session = new CountNumericRelaxation.Session(true)) {
                session.solve(model.n, model.rows, model.cost, budget, 2048);
            }
            check(budget.reservedBytes() == 0, "Memory refusal leaked");
        }
    }

    private static Model model(int seed) {
        return model(seed, 48, 24);
    }

    private static Model model(int seed, int n, int equations) {
        var random = new Random(318913L + seed);
        var planted = new int[n];
        var costs = new BigInteger[n];
        for (int i = 0; i < n; i++) {
            planted[i] = random.nextInt(8);
            costs[i] = BigInteger.valueOf(seed % 2 == 0 ? 0 : random.nextInt(7) - 3);
        }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int r = 0; r < equations; r++) {
            var terms = new LinkedHashMap<Integer, BigInteger>();
            while (terms.size() < 9) terms.put(random.nextInt(n), BigInteger.valueOf(random.nextInt(9) + 1));
            var rhs = BigInteger.ZERO;
            for (var term : terms.entrySet()) rhs = rhs.add(term.getValue().multiply(BigInteger.valueOf(planted[term.getKey()])));
            rows.add(new ExactLinearProgram.Constraint(terms, rhs));
            var opposite = new LinkedHashMap<Integer, BigInteger>();
            terms.forEach((id, value) -> opposite.put(id, value.negate()));
            rows.add(new ExactLinearProgram.Constraint(opposite, rhs.negate()));
        }
        for (int i = 0; i < n; i++) rows.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), BigInteger.valueOf(7)));
        return new Model(n, List.copyOf(rows), costs);
    }

    private static PlanningBudget budget(long work, long memory) {
        return new PlanningBudget(0, work, memory, () -> false, System::nanoTime);
    }

    private static void same(CountNumericRelaxation.Result expected, CountNumericRelaxation.Result actual) {
        check(expected.phaseOneInfeasible() == actual.phaseOneInfeasible(), "Pause changed numeric feasibility");
        check(Arrays.equals(expected.point(), actual.point()), "Pause changed numerical point or pivot order");
        check(Arrays.equals(expected.dual(), actual.dual()), "Pause changed numerical row combination");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
