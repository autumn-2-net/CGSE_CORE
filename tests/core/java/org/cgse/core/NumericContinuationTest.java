// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.*;

/** Numeric handoffs must preserve witnesses, scopes, accounting and disposal. */
public final class NumericContinuationTest {
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {}
    private record Result(BigInteger[] counts, long work, int pauses) {}

    public static void main(String[] args) throws Exception {
        int pauses = 0, witnesses = 0;
        for (int seed = 0; seed < 40; seed++) for (boolean affine : new boolean[] {true, false}) {
            Model model = model(seed);
            var sliced = solve(model, affine, 1024, 37);
            var whole = solve(model, affine, 131072, 131072);
            check(Arrays.equals(sliced.counts, whole.counts), "Handoff changed numeric trajectory: " + seed + "/" + affine);
            if (sliced.counts != null) { check(valid(model, sliced.counts), "Invalid original witness"); witnesses++; }
            pauses += sliced.pauses;
        }
        check(pauses > 100 && witnesses > 20, "Insufficient numeric handoff coverage: " + pauses + "/" + witnesses);
        kernel();
        limits();
        parallel();
        System.out.println("Numeric continuations: 80 paired runs; pauses=" + pauses + "; witnesses=" + witnesses +
                "; projected kernels, scope, cancellation, memory and request limits passed");
    }

    private static Result solve(Model m, boolean affine, long first, long quantum) {
        var budget = new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime);
        BigInteger[] counts = null;
        int pauses = 0;
        try (var lattice = affine ? new CountAffineLattice(m.rows, m.low, m.high, budget, first).retained() : null;
             var diophantine = affine ? null : new CountDiophantine(m.rows, m.low, m.high, budget, first).retained()) {
            while (true) {
                while (!(affine ? lattice.step() : diophantine.step())) {}
                boolean paused = affine ? lattice.paused() : diophantine.paused();
                counts = affine ? lattice.counts() : diophantine.counts();
                if (!paused) break;
                check(counts == null, "Paused incomplete witness escaped");
                long work = budget.nodes(), bytes = budget.reservedBytes();
                for (int i = 0; i < 3; i++) check(affine ? lattice.step() : diophantine.step(), "Paused solver ran");
                check(work == budget.nodes() && bytes == budget.reservedBytes(), "Paused poll changed work or memory");
                if (affine) lattice.resume(quantum); else diophantine.resume(quantum);
                check(++pauses < 50000, "No arithmetic progress");
            }
        }
        check(budget.reservedBytes() == 0, "Numeric workspace leaked");
        return new Result(counts, budget.nodes(), pauses);
    }

    private static Model model(int seed) {
        Random r = new Random(971 + seed);
        int n = 6 + seed % 5;
        BigInteger[] low = new BigInteger[n], high = new BigInteger[n], planted = new BigInteger[n];
        for (int i = 0; i < n; i++) {
            low[i] = BigInteger.valueOf(r.nextInt(5) - 2);
            if (seed % 7 == 0) low[i] = low[i].add(BigInteger.ONE.shiftLeft(80));
            high[i] = low[i].add(BigInteger.valueOf(4));
            planted[i] = low[i].add(BigInteger.valueOf(r.nextInt(5)));
        }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int j = 0; j < n - 2; j++) {
            var terms = new LinkedHashMap<Integer, BigInteger>();
            BigInteger sum = BigInteger.ZERO;
            for (int i = 0; i < n; i++) {
                BigInteger a = BigInteger.valueOf(r.nextInt(9) - 4);
                if (a.signum() != 0) terms.put(i, a);
                sum = sum.add(a.multiply(planted[i]));
            }
            rows.add(new ExactLinearProgram.Constraint(terms, sum));
            var reverse = new LinkedHashMap<Integer, BigInteger>();
            terms.forEach((id, a) -> reverse.put(id, a.negate()));
            rows.add(new ExactLinearProgram.Constraint(reverse, sum.negate()));
        }
        Collections.shuffle(rows, r);
        return new Model(rows, low, high);
    }

    private static void kernel() {
        var low = new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO};
        var high = new BigInteger[] {BigInteger.TEN, BigInteger.valueOf(20), BigInteger.TEN, BigInteger.ZERO};
        var basis = List.of(new BigInteger[] {BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO},
                new BigInteger[] {BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO});
        var mu = new ExactRational[][] {{ExactRational.ZERO, ExactRational.ZERO},
                {new ExactRational(BigInteger.ONE, BigInteger.TWO), ExactRational.ZERO}};
        var norms = new ExactRational[] {ExactRational.of(BigInteger.TWO), new ExactRational(BigInteger.valueOf(3), BigInteger.TWO)};
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int i = 0; i < 160; i++) rows.add(new ExactLinearProgram.Constraint(Map.of(i % 2 * 2,
                BigInteger.ONE.negate()), BigInteger.valueOf(-5)));
        var budget = new PlanningBudget(0, 1_000_000, 64L << 20, () -> false, System::nanoTime);
        int pauses = 0;
        try (var search = new CountKernelSearch(rows, low, high, low, basis, mu, norms, budget, 2048).retained()) {
            while (true) {
                while (!search.step()) {}
                if (!search.paused()) break;
                search.resume(17);
                check(++pauses < 10000, "Kernel stalled");
            }
            check(search.counts() != null && valid(new Model(rows, low, high), search.counts()), "Projected witness invalid");
        }
        check(pauses > 2 && budget.reservedBytes() == 0, "Projection was not interrupted or leaked");
    }

    private static void limits() {
        Model m = model(19);
        var cancelled = new AtomicBoolean();
        var budget = new PlanningBudget(0, 1_000_000, 64L << 20, cancelled::get, System::nanoTime);
        var search = new CountDiophantine(m.rows, m.low, m.high, budget, 1024).retained();
        while (!search.step()) {}
        check(search.paused(), "Cancellation case did not pause");
        check(search.matches(m.rows, m.low, m.high), "Same scope rejected");
        var changed = m.high.clone(); changed[0] = changed[0].add(BigInteger.ONE);
        check(!search.matches(m.rows, m.low, changed), "Different scope accepted");
        boolean rejected = false;
        try { search.resume(0); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Zero quantum accepted");
        cancelled.set(true);
        rejected = false;
        try { search.resume(Long.MAX_VALUE); } catch (java.util.concurrent.CancellationException expected) { rejected = true; }
        check(rejected, "Cancelled continuation resumed");
        search.close(); search.close();
        check(budget.reservedBytes() == 0, "Cancelled close leaked");
        for (long memory : new long[] {1, 1024, 65536}) {
            budget = new PlanningBudget(0, 500000, memory, () -> false, System::nanoTime);
            try (var attempt = new CountDiophantine(m.rows, m.low, m.high, budget, 1024).retained()) {
                while (!attempt.step()) {}
            }
            check(budget.reservedBytes() == 0, "Memory refusal leaked");
        }
        budget = new PlanningBudget(0, 4096, 64L << 20, () -> false, System::nanoTime);
        try (var attempt = new CountAffineLattice(m.rows, m.low, m.high, budget, 2048).retained()) {
            // A tiny request may refuse admission; it must never bypass its cap.
            while (!attempt.step()) {}
        } catch (PlanningBudget.Exhausted expected) {}
        check(budget.reservedBytes() == 0, "Request cutoff leaked");
        budget = new PlanningBudget(0, 1_000_000, 64L << 20, () -> false, System::nanoTime);
        try (var attempt = new CountDiophantine(m.rows, m.low, m.high, budget, 1024).retained()) {
            while (!attempt.step()) {}
            check(attempt.paused(), "No frontier to test exhausted resume");
            budget.charge(budget.remainingWork());
            rejected = false;
            try { attempt.resume(Long.MAX_VALUE); } catch (PlanningBudget.Exhausted expected) { rejected = true; }
            check(rejected, "Exhausted request resumed");
        }
        check(budget.reservedBytes() == 0, "Exhausted resume leaked");
        for (int stop : new int[] {1, 20, 200, 2000, 4000}) {
            var checks = new java.util.concurrent.atomic.AtomicInteger();
            budget = new PlanningBudget(0, 4_000_000, 64L << 20, () -> checks.incrementAndGet() >= stop, System::nanoTime);
            try (var attempt = new CountDiophantine(m.rows, m.low, m.high, budget, 1024).retained()) {
                while (true) {
                    while (!attempt.step()) {}
                    if (!attempt.paused()) break;
                    attempt.resume(Long.MAX_VALUE);
                }
            } catch (CancellationException expected) {}
            check(budget.reservedBytes() == 0, "In-flight cancellation leaked at " + stop);
        }
    }

    private static void parallel() throws Exception {
        for (boolean expanded : new boolean[] {false, true}) {
            long cap = PlanningBudget.parallelWorkLimit(4_000_000, 4, expanded);
            var budget = new PlanningBudget(0, cap, 64L << 20, () -> false, System::nanoTime);
            var pool = Executors.newFixedThreadPool(4);
            var jobs = new ArrayList<Future<?>>();
            try {
                for (int seed = 0; seed < 16; seed++) {
                    Model m = model(seed);
                    jobs.add(pool.submit(() -> {
                        try (var search = new CountDiophantine(m.rows, m.low, m.high, budget, 1024).retained()) {
                            do {
                                if (search.paused()) search.resume(2048);
                                while (!search.step()) {}
                            } while (search.paused());
                            check(search.counts() != null && valid(m, search.counts()), "Parallel numeric witness invalid");
                        }
                    }));
                }
                for (var job : jobs) job.get(60, TimeUnit.SECONDS);
            } finally { pool.shutdownNow(); }
            check(budget.searchWork() <= cap && budget.reservedBytes() == 0, "Parallel numeric budget or release error");
        }
    }

    private static boolean valid(Model m, BigInteger[] values) {
        for (int i = 0; i < values.length; i++) if (values[i].compareTo(m.low[i]) < 0 || values[i].compareTo(m.high[i]) > 0) return false;
        for (var row : m.rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(values[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
