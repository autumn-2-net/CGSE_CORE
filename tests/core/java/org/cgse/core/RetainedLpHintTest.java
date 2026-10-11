// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A direction-only LP may outlive its first branch, but never becomes a proof. */
public final class RetainedLpHintTest {
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {}

    public static void main(String[] args) throws Exception {
        long resumptions = 0;
        var workers = new ExecutorService[] {Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor()};
        try {
            for (int seed = 0; seed < 12; seed++) {
                var model = model(seed);
                var referenceBudget = budget(4_000_000, 64L << 20);
                double[] expected;
                try (var result = CountLpHint.solve(model.rows, model.low, model.high, referenceBudget, 2_000_000)) {
                    check(result != null, "No continuous reference for " + seed);
                    expected = result.point.clone();
                }
                var b = budget(4_000_000, 64L << 20);
                var rows = new ArrayList<>(model.rows);
                var low = model.low.clone();
                var high = model.high.clone();
                var attempt = new CountLpHint.Attempt();
                try (var session = new CountLpHint.Session()) {
                    try (var first = session.start(rows, low, high, b, 8192, attempt)) {
                        check(first == null && session.pending(), "Fixture did not retain cold progress");
                        check(attempt.outcome == CountLpHint.Outcome.WORK_LIMIT, "Pause is not completion");
                    }
                    // The caller can branch, backtrack or release its old lists.
                    // This session explicitly proposes a point for its frozen LP.
                    rows.clear();
                    Arrays.fill(low, BigInteger.TEN);
                    Arrays.fill(high, null);
                    double[] actual = null;
                    for (int visit = 0; visit < 512 && actual == null; visit++) {
                        long before = b.nodes();
                        try (var result = workers[visit % 2].submit(() -> session.resume(2048, attempt)).get(30, TimeUnit.SECONDS)) {
                            check(b.nodes() - before < 16384, "Visit exceeded one complete pivot");
                            if (result != null) actual = result.point.clone();
                            else check(session.pending(), "Lost unfinished projection");
                        }
                    }
                    check(Arrays.equals(expected, actual), "Sliced hint changed the frozen LP point");
                    check(!session.pending(), "Completed hint retained its projection");
                    resumptions += session.resumptions();
                }
                check(referenceBudget.reservedBytes() == 0 && b.reservedBytes() == 0, "Hint ownership leaked");
            }
        } finally { for (var worker : workers) worker.shutdownNow(); }
        check(resumptions > 100, "Insufficient actual handoffs");
        owner();
        lifetime();
        limits();
        exactOracles();
        System.out.println("Retained LP hints: 12 frozen trajectories; " + resumptions +
                " resumptions; worker migration, owner progress, bounds, cancellation and memory passed");
    }

    private static void owner() throws Exception {
        var model = model(3, 48);
        var b = budget(4_000_000, 64L << 20);
        var hint = field("finiteHint");
        var pending = field("finiteHintSession");
        var decisions = field("decisions");
        var used = field("finiteHintPreparationWork");
        boolean sawPending = false, branchedWhilePending = false, gotHint = false;
        try (var search = new CountLcg(model.rows, model.low, model.high, b, 16384, true).retainedFiniteHint()) {
            for (int visit = 0; visit < 512 && !gotHint; visit++) {
                if (search.paused()) search.resume(4096);
                boolean done;
                do {
                    done = search.step();
                    var session = (CountLpHint.Session) pending.get(search);
                    if (session != null && session.pending()) {
                        sawPending = true;
                        branchedWhilePending |= decisions.getInt(search) > 0;
                    }
                    gotHint = hint.get(search) != null;
                } while (!done && !gotHint);
                if (done && !search.paused()) break;
            }
            check(sawPending && branchedWhilePending && gotHint, "Retained LCG did not advance its direction-only hint: pending=" +
                    sawPending + "; branched=" + branchedWhilePending + "; hint=" + gotHint + "; spent=" + used.getLong(search));
            check(used.getLong(search) < 262144, "Lifetime preparation allowance exceeded");
            check(search.counts() == null && !search.infeasible(), "A continuous proposal was promoted to an integer conclusion");
        }
        check(b.reservedBytes() == 0, "LCG retained hint memory after close");
    }

    private static void limits() {
        var model = model(2);
        for (long bytes : new long[] {1, 100_000, 1_000_000}) {
            var b = budget(4_000_000, bytes);
            try (var session = new CountLpHint.Session();
                 var result = session.start(model.rows, model.low, model.high, b, 8192, new CountLpHint.Attempt())) {
                check(result == null, "Memory-limited cold fixture unexpectedly finished");
            }
            check(b.reservedBytes() == 0, "Optional memory refusal leaked");
        }
        var cancelled = new AtomicBoolean();
        var b = new PlanningBudget(0, 4_000_000, 64L << 20, cancelled::get, System::nanoTime);
        var session = new CountLpHint.Session();
        try {
            var attempt = new CountLpHint.Attempt();
            try (var first = session.start(model.rows, model.low, model.high, b, 8192, attempt)) {
                check(first == null && session.pending(), "No cancellation fixture");
            }
            // No credit does not delete an already prepared projection.
            try (var empty = session.resume(0, attempt)) {
                check(empty == null && session.pending(), "Zero grant discarded progress");
            }
            cancelled.set(true);
            boolean stopped = false;
            try (var ignored = session.resume(4096, attempt)) { }
            catch (RuntimeException expected) { stopped = true; }
            check(stopped && !session.pending() && b.reservedBytes() == 0, "Cancellation retained a tableau");
        } finally { session.close(); }
        check(b.reservedBytes() == 0, "Repeated close leaked");

        // A continuous contradiction is absence of a hint, not a negative fact.
        var impossible = new ArrayList<>(model.rows);
        impossible.add(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE), BigInteger.valueOf(-1)));
        b = budget(4_000_000, 64L << 20);
        try (var negative = new CountLpHint.Session()) {
            var attempt = new CountLpHint.Attempt();
            try (var result = negative.start(impossible, model.low, model.high, b, 2_000_000, attempt)) {
                check(result == null && !negative.pending(), "Contradictory LP generated a hint");
                check(attempt.outcome == CountLpHint.Outcome.ATTEMPTED, "Numeric contradiction escaped as proof");
            }
        }
        check(b.reservedBytes() == 0, "Negative proposal leaked");

        var shortBudget = budget(12_000, 64L << 20);
        var shortSession = new CountLpHint.Session();
        try {
            var attempt = new CountLpHint.Attempt();
            try (var first = shortSession.start(model.rows, model.low, model.high, shortBudget, 8192, attempt)) {
                check(first == null && shortSession.pending(), "No global-cap fixture");
            }
            boolean stopped = false;
            try (var ignored = shortSession.resume(8192, attempt)) { }
            catch (RuntimeException expected) { stopped = true; }
            check(stopped && !shortSession.pending(), "Global cap left a partially written tableau");
        } finally { shortSession.close(); }
        check(shortBudget.reservedBytes() == 0, "Global cap leaked");
    }

    private static void lifetime() throws Exception {
        var model = model(3);
        var b = budget(4_000_000, 64L << 20);
        var used = field("finiteHintPreparationWork");
        var pending = field("finiteHintSession");
        var tried = field("finiteHintTried");
        long visits = 0;
        try (var search = new CountLcg(model.rows, model.low, model.high, b, 16384).retainedFiniteHint()) {
            do {
                if (search.paused()) search.resume(4096);
                while (!search.step()) { }
                var session = (CountLpHint.Session) pending.get(search);
                if (session != null && tried.getBoolean(search)) {
                    check(!session.pending(), "Lifetime exhaustion retained optional memory");
                    break;
                }
            } while (search.paused() && ++visits < 512);
            check(used.getLong(search) <= 262144 + 16384, "Hint exceeded lifetime cap by more than a completed pivot");
            check(!search.infeasible(), "Known planted witness was contradicted");
        }
        check(b.reservedBytes() == 0, "Lifetime cap leaked");
    }

    private static void exactOracles() {
        var random = new Random(2026101117L);
        int sat = 0, unsat = 0;
        for (int seed = 0; seed < 80; seed++) {
            var low = new BigInteger[144]; Arrays.fill(low, BigInteger.ONE);
            var high = low.clone();
            for (int i = 0; i < 4; i++) { low[i] = BigInteger.ZERO; high[i] = BigInteger.valueOf(3); }
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int r = 0; r < 12; r++) {
                var terms = new LinkedHashMap<Integer, BigInteger>();
                for (int i = 0; i < 4; i++) terms.put(i, BigInteger.valueOf(random.nextInt(13) - 6));
                terms.put(10 + r, BigInteger.valueOf(3)); // Fixed offsets must be substituted exactly.
                rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(seed % 3 == 0 ? random.nextInt(25) - 9 : 8)));
            }
            boolean possible = false;
            for (int packed = 0; packed < 256; packed++) {
                var point = low.clone();
                for (int i = 0; i < 4; i++) point[i] = BigInteger.valueOf(packed >>> (2 * i) & 3);
                if (valid(rows, point)) { possible = true; break; }
            }
            var b = budget(2_000_000, 64L << 20);
            try (var search = new CountLcg(rows, low, high, b, 4096, true).retainedFiniteHint()) {
                for (;;) {
                    while (!search.step()) { }
                    if (!search.paused()) break;
                    search.resume(4096);
                }
                check((search.counts() != null) == possible && search.infeasible() != possible, "Finite oracle contradiction");
                check(search.counts() == null || valid(rows, search.counts()), "Unverified rounded hint");
                check(CountProof.verify(search.certificate(), 2_000_000) == CountProof.Verdict.VERIFIED, "Invalid hint-arm proof");
                if (possible) sat++; else unsat++;
            }
            check(b.reservedBytes() == 0, "Finite oracle memory leaked");
        }
        check(sat > 20 && unsat > 10, "Missing positive/negative oracle coverage");
        System.out.println("Retained hint exact oracles: SAT=" + sat + "; UNSAT=" + unsat);
    }

    private static boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] point) {
        for (var row : rows) {
            var sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(point[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private static Field field(String name) throws Exception {
        var f = CountLcg.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static Model model(int seed) {
        return model(seed, 72);
    }

    private static Model model(int seed, int equations) {
        int n = 144;
        var random = new Random(318913L + seed);
        var low = new BigInteger[n];
        var high = new BigInteger[n];
        var planted = new BigInteger[n];
        for (int i = 0; i < n; i++) {
            low[i] = BigInteger.valueOf(seed % 3 == 0 ? 0 : i % 4);
            high[i] = low[i].add(BigInteger.valueOf(seed % 3 == 0 || i % 13 != 0 ? 7 : 0));
            planted[i] = low[i].add(BigInteger.valueOf(low[i].equals(high[i]) ? 0 : random.nextInt(8)));
        }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int r = 0; r < equations; r++) {
            var terms = new LinkedHashMap<Integer, BigInteger>();
            while (terms.size() < 9) terms.put(random.nextInt(n), BigInteger.valueOf(random.nextInt(9) + 1));
            var rhs = BigInteger.ZERO;
            for (var term : terms.entrySet()) rhs = rhs.add(term.getValue().multiply(planted[term.getKey()]));
            rows.add(new ExactLinearProgram.Constraint(terms, rhs));
            var opposite = new LinkedHashMap<Integer, BigInteger>();
            terms.forEach((id, value) -> opposite.put(id, value.negate()));
            rows.add(new ExactLinearProgram.Constraint(opposite, rhs.negate()));
        }
        for (int i = 0; i < n; i++) rows.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), high[i]));
        return new Model(List.copyOf(rows), low, high);
    }

    private static PlanningBudget budget(long work, long memory) {
        return new PlanningBudget(0, work, memory, () -> false, System::nanoTime);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
