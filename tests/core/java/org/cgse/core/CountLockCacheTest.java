// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cached rounding decisions must equal fresh scans at every current domain. */
public final class CountLockCacheTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        randomTrails();
        isolationAndReplacement();
        sparseReuse();
        limits();
        System.out.println("Rounding lock cache: " + checks + " checks; widening, row append/replacement, worker handoff, limits and reuse passed");
    }

    private static PlanningBudget budget(long memory) {
        return new PlanningBudget(0, 100_000_000, memory, () -> false, System::nanoTime);
    }

    private static void randomTrails() throws Exception {
        var workers = new ExecutorService[] {Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor()};
        try {
            for (int seed = 0; seed < 48; seed++) {
                var random = new Random(seed);
                int n = 16 + random.nextInt(49);
                var rows = rows(n, n * 3, random);
                var low = new BigInteger[n];
                var high = new BigInteger[n];
                var activity = new double[n];
                for (int i = 0; i < n; i++) {
                    low[i] = BigInteger.valueOf(random.nextInt(7) - 3);
                    high[i] = i % 9 == 0 ? null : low[i].add(BigInteger.valueOf(7));
                }
                var originalLow = low.clone();
                var originalHigh = high.clone();
                var cached = budget(64L << 20);
                var fresh = budget(64L << 20);
                try (var session = new CountLockBranch.Session(n, cached)) {
                    for (int turn = 0; turn < 128; turn++) {
                        if (turn % 19 == 0) {
                            System.arraycopy(originalLow, 0, low, 0, n);
                            System.arraycopy(originalHigh, 0, high, 0, n);
                        } else {
                            int id = random.nextInt(n);
                            low[id] = originalLow[id].add(BigInteger.valueOf(random.nextInt(5)));
                            high[id] = turn % 11 == 0 ? null : low[id].add(BigInteger.valueOf(random.nextInt(4)));
                            activity[id] += random.nextInt(5) * 0.25;
                        }
                        if (turn % 23 == 22) rows.addAll(rows(n, 1, random));
                        var expected = CountLockBranch.choose(rows, low, high, activity, fresh);
                        var actual = workers[turn % 2].submit(() -> session.choose(rows, low, high, activity)).get(30, TimeUnit.SECONDS);
                        check(Objects.equals(expected, actual), "Cached choice changed at seed=" + seed + "; turn=" + turn + "; " + expected + " != " + actual);
                    }
                }
                check(cached.reservedBytes() == 0 && fresh.reservedBytes() == 0, "Random trail workspace leaked");
            }
        } finally { for (var worker : workers) worker.shutdownNow(); }
    }

    private static ArrayList<ExactLinearProgram.Constraint> rows(int n, int size, Random random) {
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int r = 0; r < size; r++) {
            var terms = new LinkedHashMap<Integer, BigInteger>();
            int count = 1 + random.nextInt(Math.min(n, 12));
            for (int i = 0; i < count; i++) terms.put(random.nextInt(n), BigInteger.valueOf(random.nextInt(11) - 5));
            rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(random.nextInt(31) - 15)));
        }
        return rows;
    }

    private static void isolationAndReplacement() {
        for (int change = 0; change < 3; change++) {
            var rows = rows(12, 30, new Random(137));
            BigInteger huge = BigInteger.ONE.shiftLeft(8192);
            BigInteger[] low = new BigInteger[12], high = new BigInteger[12];
            Arrays.fill(low, huge.negate());
            Arrays.fill(high, huge);
            double[] scores = new double[12];
            var b = budget(16L << 20);
            var reference = budget(16L << 20);
            try (var a = new CountLockBranch.Session(12, b); var sibling = new CountLockBranch.Session(12, b)) {
                check(Objects.equals(a.choose(rows, low, high, scores), sibling.choose(rows, low, high, scores)), "Independent cache mismatch");
                // A source replacement, deletion or reorder must not retain old locks.
                if (change == 0) rows.set(0, new ExactLinearProgram.Constraint(Map.of(11, huge), huge));
                else if (change == 1) rows.remove(0);
                else Collections.swap(rows, 0, rows.size() - 1);
                high[3] = low[3];
                check(Objects.equals(a.choose(rows, low, high, scores), CountLockBranch.choose(rows, low, high, scores, reference)), "Stale source scope");
                low[3] = BigInteger.ZERO;
                high[3] = huge;
                check(Objects.equals(sibling.choose(rows, low, high, scores), CountLockBranch.choose(rows, low, high, scores, reference)), "Sibling scope contaminated");
            }
            check(b.reservedBytes() == 0 && reference.reservedBytes() == 0, "Huge endpoints retained after close");
        }
    }

    private static void sparseReuse() {
        int n = 192;
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int i = 0; i < n; i++) {
            rows.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.valueOf(3), (i + 1) % n, BigInteger.valueOf(-2)), BigInteger.ONE));
            rows.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE, (i + 7) % n, BigInteger.ONE), BigInteger.valueOf(11)));
        }
        var low = new BigInteger[n];
        var high = new BigInteger[n];
        Arrays.fill(low, BigInteger.ZERO);
        Arrays.fill(high, BigInteger.valueOf(7));
        var cached = budget(64L << 20);
        var fresh = budget(64L << 20);
        var activity = new double[n];
        try (var session = new CountLockBranch.Session(n, cached)) {
            for (int turn = 0; turn < 256; turn++) {
                int id = turn % n;
                high[id] = turn % 2 == 0 ? BigInteger.ZERO : BigInteger.valueOf(7);
                check(Objects.equals(session.choose(rows, low, high, activity), CountLockBranch.choose(rows, low, high, activity, fresh)), "Sparse score mismatch");
            }
        }
        check(cached.searchWork() < fresh.searchWork() / 2, "Unchanged sparse rows still dominated repeated choices");
        check(cached.reservedBytes() == 0 && fresh.reservedBytes() == 0, "Sparse cache leaked");
        System.out.println("Same 256 sparse decisions: fresh_work=" + fresh.searchWork() + "; cached_work=" + cached.searchWork());
    }

    private static void limits() {
        var rows = rows(48, 96, new Random(49));
        var low = new BigInteger[48];
        var high = new BigInteger[48];
        Arrays.fill(low, BigInteger.ZERO);
        Arrays.fill(high, BigInteger.ONE);
        var activity = new double[48];
        var fresh = budget(64L << 20);
        var expected = CountLockBranch.choose(rows, low, high, activity, fresh);
        for (long memory : new long[] {8192, 16_384, 32_768}) {
            var b = budget(memory);
            try (var cache = new CountLockBranch.Session(48, b)) {
                check(Objects.equals(cache.choose(rows, low, high, activity), expected), "Optional cache refusal lost the old scan");
            }
            check(b.reservedBytes() == 0, "Cache refusal leaked");
        }
        var stop = new AtomicBoolean();
        var b = new PlanningBudget(0, 100_000, 64L << 20, stop::get, System::nanoTime);
        try (var cache = new CountLockBranch.Session(48, b)) {
            cache.choose(rows, low, high, activity);
            stop.set(true);
            boolean cancelled = false;
            try { cache.choose(rows, low, high, activity); }
            catch (CancellationException expectedCancel) { cancelled = true; }
            check(cancelled, "Retained lock cache ignored cancellation");
        }
        check(b.reservedBytes() == 0, "Cancelled lock cache leaked");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
