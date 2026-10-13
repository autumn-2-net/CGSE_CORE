// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CountRelaxationNeighborhoodTest {
    public static void main(String[] args) throws Exception {
        exhaustedRelaxationTail();
        var worker = Executors.newSingleThreadExecutor(); int solved = 0;
        try {
            for (int seed = 0; seed < 8; seed++) {
                var view = model(seed, false);
                var whole = solve(view, 1_000_000, null);
                var sliced = solve(view, 1, worker);
                check(Arrays.equals(whole, sliced), "Changed neighborhood after handoff");
                if (whole != null) { check(valid(view, whole), "Invalid exact witness"); solved++; }
                var impossible = model(seed, true);
                check(solve(impossible, 17, null) == null, "Parity contradiction accepted");
            }
        } finally { worker.shutdownNow(); }
        check(solved == 8, "Small arithmetic repair not reached: " + solved);
        var view = model(0, false);
        var paddedRows = new ArrayList<>(view.rows());
        // Fixed material rows should not hide a small repair at a 256-row gate.
        for (int i = 0; i < 300; i++) paddedRows.add(new ExactLinearProgram.Constraint(
                Map.of(3 + i % 61, BigInteger.valueOf(i + 1)), BigInteger.ZERO));
        var padded = new CountModelViews.View("padded", paddedRows, view.lower(), view.upper(),
                new CountModelViews.Shape(64, 312, 6, 3), null, CountModelViews.Semantics.EQUIVALENT, null);
        var paddedCounts = solve(padded, 17, null);
        check(paddedCounts != null && valid(padded, paddedCounts), "Small repair rejected behind fixed material rows");
        var cancelled = new AtomicBoolean();
        var budget = new PlanningBudget(0, 8_000_000, 64L << 20, cancelled::get, System::nanoTime);
        try (var search = CountRelaxationNeighborhood.create(view, new CountModelViews.Domains(view.lower(), view.upper(), 0), budget, 1)) {
            check(search != null && search.matches(view.rows(), view.lower(), view.upper()), "Missing owned scope");
            var high = view.upper().clone(); high[0] = high[0].add(BigInteger.ONE);
            check(!search.matches(view.rows(), view.lower(), high), "Changed bound reused");
            search.step(); cancelled.set(true);
            try { search.step(); throw new AssertionError("Ignored cancellation"); }
            catch (CancellationException expected) {}
        }
        check(budget.reservedBytes() == 0, "Cancelled workspace retained");
        var tiny = new PlanningBudget(0, 8_000_000, 1, () -> false, System::nanoTime);
        check(CountRelaxationNeighborhood.create(view, new CountModelViews.Domains(view.lower(), view.upper(), 0), tiny, 1) == null,
                "Ignored memory refusal");
        check(tiny.reservedBytes() == 0, "Admission leaked");
        System.out.println("Retained relaxation neighborhoods: 8 constructive oracles, parity controls, worker handoffs and exact row/bound checks passed");
    }

    private static CountModelViews.View tailModel(int n, int dimensions) {
        var low = new BigInteger[n]; var high = new BigInteger[n]; var oracle = new BigInteger[n];
        Arrays.fill(low, BigInteger.ZERO); Arrays.fill(high, BigInteger.valueOf(1_000_003));
        var random = new Random(81121);
        for (int i = 0; i < n; i++) oracle[i] = BigInteger.valueOf(random.nextInt(1_000_000));
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int d = 0; d < dimensions; d++) {
            var terms = new TreeMap<Integer, BigInteger>(); var rhs = BigInteger.ZERO;
            for (int i = 0; i < n; i++) {
                var a = BigInteger.valueOf(1 + random.nextInt(1000));
                terms.put(i, a); rhs = rhs.add(a.multiply(oracle[i]));
            }
            equality(rows, terms, rhs);
        }
        var view = new CountModelViews.View("tail", rows, low, high, new CountModelViews.Shape(n, n * 2L * dimensions, 10, 10),
                null, CountModelViews.Semantics.EQUIVALENT, null);
        check(valid(view, oracle), "Bad constructive tail-budget oracle");
        return view;
    }

    private static void exhaustedRelaxationTail() throws Exception {
        var view = tailModel(256, 9);
        var budget = new PlanningBudget(0, 278785, 64L << 20, () -> false, System::nanoTime);
        try (var search = CountRelaxationNeighborhood.create(view, new CountModelViews.Domains(view.lower(), view.upper(), 0), budget, 1)) {
            check(search != null, "Tail-budget repair was not admitted");
            int visits = 0;
            for (;;) {
                check(++visits < 1000, "Retried a retained LP without enough work to advance it");
                if (!search.step()) continue;
                if (!search.paused()) break;
                search.resume(1);
            }
            check(search.counts() == null || valid(view, search.counts()), "Invalid tail-budget candidate");
        }
        check(budget.reservedBytes() == 0, "Tail-budget exit retained the LP");

        var sharedView = tailModel(512, 32);
        var shared = new PlanningBudget(0, 8_000_000, 64L << 20, () -> false, System::nanoTime);
        var worker = Executors.newSingleThreadExecutor();
        try (var search = CountRelaxationNeighborhood.create(sharedView,
                new CountModelViews.Domains(sharedView.lower(), sharedView.upper(), 0), shared, Long.MAX_VALUE)) {
            check(search != null && !search.step() && search.progress() == 0, "Shared-tail fixture needs a retained LP");
            worker.submit(() -> shared.charge(shared.remainingWork() - 900)).get(30, TimeUnit.SECONDS);
            check(search.step() && !search.paused() && search.counts() == null, "Shared budget drain left a spinning LP");
        } finally { worker.shutdownNow(); }
        check(shared.reservedBytes() == 0, "Shared-tail exit retained the LP");
    }

    private static BigInteger[] solve(CountModelViews.View view, long quantum, ExecutorService worker) throws Exception {
        var budget = new PlanningBudget(0, 8_000_000, 64L << 20, () -> false, System::nanoTime);
        BigInteger[] result;
        try (var search = CountRelaxationNeighborhood.create(view, new CountModelViews.Domains(view.lower(), view.upper(), 0), budget, quantum)) {
            check(search != null, "Expected bounded neighborhood");
            for (;;) {
                boolean done = worker == null ? search.step() : worker.submit(search::step).get(30, TimeUnit.SECONDS);
                if (!done) continue;
                if (!search.paused()) break;
                search.resume(quantum);
            }
            result = search.counts();
        }
        check(budget.reservedBytes() == 0, "Retained repair workspace");
        return result;
    }

    private static CountModelViews.View model(int seed, boolean impossible) {
        int n = 64; var lo = new BigInteger[n]; var hi = new BigInteger[n];
        Arrays.fill(lo, BigInteger.ZERO); Arrays.fill(hi, BigInteger.ZERO);
        BigInteger capacity = BigInteger.valueOf(1_000_003 + 2L * seed);
        for (int i = 0; i < 3; i++) hi[i] = capacity;
        BigInteger x = BigInteger.valueOf(100_001 + seed), y = BigInteger.valueOf(200_003 + seed), z = capacity.subtract(x).subtract(y);
        BigInteger goal = x.multiply(BigInteger.valueOf(23)).add(y.multiply(BigInteger.valueOf(37))).add(z.multiply(BigInteger.valueOf(43)));
        if (impossible) goal = goal.add(BigInteger.ONE); // Odd weights, fixed total: opposite parity is impossible.
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        equality(rows, Map.of(0, BigInteger.ONE, 1, BigInteger.ONE, 2, BigInteger.ONE), capacity);
        equality(rows, Map.of(0, BigInteger.valueOf(23), 1, BigInteger.valueOf(37), 2, BigInteger.valueOf(43)), goal);
        return new CountModelViews.View("test", rows, lo, hi, new CountModelViews.Shape(n, 12, 6, 3), null,
                CountModelViews.Semantics.EQUIVALENT, null);
    }
    private static void equality(List<ExactLinearProgram.Constraint> rows, Map<Integer, BigInteger> terms, BigInteger rhs) {
        rows.add(new ExactLinearProgram.Constraint(terms, rhs));
        var opposite = new TreeMap<Integer, BigInteger>(); terms.forEach((i, a) -> opposite.put(i, a.negate()));
        rows.add(new ExactLinearProgram.Constraint(opposite, rhs.negate()));
    }
    private static boolean valid(CountModelViews.View view, BigInteger[] p) {
        for (int i = 0; i < p.length; i++) if (p[i].compareTo(view.lower()[i]) < 0 || p[i].compareTo(view.upper()[i]) > 0) return false;
        for (var row : view.rows()) {
            var sum = BigInteger.ZERO;
            for (var t : row.terms().entrySet()) sum = sum.add(t.getValue().multiply(p[t.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
