// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Original-row controls for price-guided cores; closed local fixings are never global UNSAT. */
public final class CountLpCoreTest {
    public static void main(String[] args) throws Exception {
        expansion(false);
        expansion(true);
        outsideCore();
        exhaustive();
        limits();
        claims();
        System.out.println("LP cores: eight integral changes, widened fixings, 64 exhaustive models, exact wide arithmetic, worker handoffs, limits and request claims passed");
    }

    private static void expansion(boolean huge) throws Exception {
        int n = 24;
        var scale = huge ? BigInteger.ONE.shiftLeft(120).add(BigInteger.valueOf(13)) : BigInteger.ONE;
        var demand = new TreeMap<Integer, BigInteger>();
        // Eight integral coordinates must change together; several are outside
        // the first core. The large fixed coordinate must be subtracted exactly.
        for (int i = 10; i < 18; i++) demand.put(i, scale.negate());
        demand.put(23, scale.multiply(BigInteger.valueOf(99)));
        var rows = List.of(new ExactLinearProgram.Constraint(demand, scale.multiply(BigInteger.valueOf(91))),
                row(Map.of(0, 1, 1, 1), 1), row(Map.of(0, -1, 1, -1), -1));
        var low = domain(n, 0); low[23] = BigInteger.ONE;
        var high = domain(n, 1);
        var point = new double[n]; point[0] = point[1] = 0.5; point[23] = 1;
        var savedLow = low.clone(); var savedPoint = point.clone();
        var budget = budget();
        var workers = new ExecutorService[]{Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor()};
        try (var core = CountLpCoreSearch.create(rows, low, high, point, prices(n), budget)) {
            check(core != null, "core declined");
            // The core owns its point and bounds; later LP/parent updates must
            // not change either the local assumptions or coordinate recovery.
            low[22] = BigInteger.ONE; point[22] = 1;
            int visits = 0;
            while (!workers[visits++ % 2].submit(core::step).get(30, TimeUnit.SECONDS))
                check(visits < 10000, "core stalled");
            var value = core.counts();
            check(value != null && valid(rows, savedLow, high, value), "widened core lost the original witness");
            for (int i = 10; i < 18; i++) check(value[i].equals(BigInteger.ONE), "missing simultaneous change");
            check(value[22].equals(BigInteger.ZERO) && savedPoint[22] == 0, "borrowed parent state escaped");
        } finally { for (var worker : workers) worker.shutdownNow(); }
        check(budget.reservedBytes() == 0, "expanded core leaked memory");
    }

    private static void outsideCore() {
        int n = 24;
        var rows = List.of(row(Map.of(23, -1), -1));
        var low = domain(n, 0); var high = domain(n, 1);
        var point = new double[n]; point[0] = point[1] = 0.5;
        var budget = budget();
        try (var core = CountLpCoreSearch.create(rows, low, high, point, prices(n), budget)) {
            check(core != null, "missing restricted core");
            while (!core.step()) {}
            check(core.counts() == null, "unreleased coordinate changed");
        }
        try (var original = new CountLcg(rows, low, high, budget, 131072)) {
            while (!original.step()) {}
            check(original.counts() != null && valid(rows, low, high, original.counts()), "restricted failure closed the parent");
        }
        check(budget.reservedBytes() == 0, "closed core leaked memory");
    }

    private static void exhaustive() {
        for (int seed = 0; seed < 64; seed++) {
            var random = new Random(101300L + seed);
            int n = 7 + seed % 5;
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int r = 0; r < 5; r++) {
                var terms = new TreeMap<Integer, BigInteger>();
                for (int i = 0; i < n; i++) {
                    int coefficient = random.nextInt(11) - 5;
                    if (coefficient != 0) terms.put(i, BigInteger.valueOf(coefficient));
                }
                rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(random.nextInt(15) - 5)));
            }
            var low = domain(n, 0); var high = domain(n, 1);
            boolean possible = false;
            for (int mask = 0; mask < 1 << n; mask++) {
                var value = domain(n, 0);
                for (int i = 0; i < n; i++) if ((mask & 1 << i) != 0) value[i] = BigInteger.ONE;
                possible |= valid(rows, low, high, value);
            }
            var point = new double[n]; point[0] = 0.5;
            for (int i = 1; i < n; i++) point[i] = random.nextBoolean() ? 1 : 0;
            var budget = budget();
            try (var core = CountLpCoreSearch.create(rows, low, high, point, prices(n), budget)) {
                check(core != null, "small core declined");
                while (!core.step()) {}
                var value = core.counts();
                check((value != null) == possible, "exhaustive oracle mismatch at " + seed);
                if (value != null) check(valid(rows, low, high, value), "invalid primitive row witness");
            }
            check(budget.reservedBytes() == 0, "exhaustive core leaked memory");
        }
    }

    private static void limits() {
        var rows = List.of(row(Map.of(0, 1, 1, 1), 1));
        var low = domain(24, 0); var high = domain(24, 1);
        var point = new double[24]; point[0] = 0.5;
        var cancelled = new AtomicBoolean();
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, cancelled::get, System::nanoTime);
        try (var core = CountLpCoreSearch.create(rows, low, high, point, prices(24), budget)) {
            check(core != null, "missing cancellation core");
            core.step();
            cancelled.set(true);
            try { core.step(); throw new AssertionError("cancel ignored"); }
            catch (CancellationException expected) { /* no candidate escapes */ }
            check(core.counts() == null, "cancelled candidate escaped");
        }
        check(budget.reservedBytes() == 0, "cancel leaked memory");
        for (long memory : new long[]{1024, 65536}) {
            var small = new PlanningBudget(0, 40_000_000, memory, () -> false, System::nanoTime);
            try (var core = CountLpCoreSearch.create(rows, low, high, point, prices(24), small)) {
                if (core != null) { while (!core.step()) {} }
            }
            check(small.reservedBytes() == 0, "allocation refusal leaked memory");
        }
        var declined = budget(); point[0] = Double.NaN;
        check(CountLpCoreSearch.create(rows, low, high, point, prices(24), declined) == null, "nonfinite hint accepted");
        point[0] = 0.5; high[1] = BigInteger.TWO;
        check(CountLpCoreSearch.create(rows, low, high, point, prices(24), declined) == null, "nonboolean domain accepted");
        check(declined.reservedBytes() == 0, "admission leaked memory");
    }

    private static void claims() throws Exception {
        var budget = budget();
        var workers = Executors.newFixedThreadPool(4);
        try {
            var attempts = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 32; i++) attempts.add(budget::claimLpCore);
            int wins = 0;
            for (var result : workers.invokeAll(attempts)) if (result.get()) wins++;
            check(wins == 1 && budget().claimLpCore(), "claim leaked across requests or workers");
        } finally { workers.shutdownNow(); }
    }

    private static boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high, BigInteger[] value) {
        for (int i = 0; i < value.length; i++) if (value[i].compareTo(low[i]) < 0 || value[i].compareTo(high[i]) > 0) return false;
        for (var row : rows) {
            var sum = BigInteger.ZERO;
            for (var e : row.terms().entrySet()) sum = sum.add(e.getValue().multiply(value[e.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }
    private static ExactLinearProgram.Constraint prices(int n) {
        var terms = new TreeMap<Integer, BigInteger>();
        for (int i = 0; i < n; i++) terms.put(i, BigInteger.valueOf(i + 1));
        return new ExactLinearProgram.Constraint(terms, BigInteger.ZERO); // Deliberately not a valid global constraint.
    }
    private static ExactLinearProgram.Constraint row(Map<Integer, Integer> terms, int rhs) {
        var copy = new TreeMap<Integer, BigInteger>(); terms.forEach((i, a) -> copy.put(i, BigInteger.valueOf(a)));
        return new ExactLinearProgram.Constraint(copy, BigInteger.valueOf(rhs));
    }
    private static BigInteger[] domain(int n, int value) { var a = new BigInteger[n]; Arrays.fill(a, BigInteger.valueOf(value)); return a; }
    private static PlanningBudget budget() { return new PlanningBudget(0, 40_000_000, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
