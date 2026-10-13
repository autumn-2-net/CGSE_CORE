// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent box enumeration, prefix handoffs and original-row matcher controls. */
public final class CountRangeIndexTest {
    public static void main(String[] args) throws Exception {
        boxes();
        correlated();
        matcher();
        refinementCancellation();
        limits();
        System.out.println("Range indexes: 3200 first-match boxes, wide/negative/unbounded coordinates, outliers, owned inputs, worker handoffs, refinement, matcher controls and limits passed");
    }

    private static void boxes() throws Exception {
        var workers = new ExecutorService[]{Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor()};
        var refined = CountRangeIndex.class.getDeclaredField("refined");
        refined.setAccessible(true);
        int refinements = 0;
        try {
            for (int seed = 0; seed < 16; seed++) {
                var random = new Random(131026L + seed);
                int dimensions = 2 + seed % 7;
                var scale = seed % 2 == 0 ? BigInteger.ONE : BigInteger.ONE.shiftLeft(120).add(BigInteger.valueOf(13));
                var source = new BigInteger[257][dimensions];
                for (int i = 0; i < source.length; i++) for (int d = 0; d < dimensions; d++)
                    source[i][d] = BigInteger.valueOf(random.nextInt(81) - 40).multiply(scale);
                // Neither sampling nor equal-key partitions may lose an outlier.
                source[133][0] = scale.multiply(BigInteger.valueOf(1000000));
                source[91] = source[19].clone();
                var original = Arrays.stream(source).map(BigInteger[]::clone).toArray(BigInteger[][]::new);
                var budget = budget();
                try (var index = CountRangeIndex.create(source.length, dimensions, i -> source[i], budget, budget::check)) {
                    check(index != null, "index declined");
                    source[133][0] = BigInteger.ZERO;
                    source[15] = new BigInteger[dimensions];
                    int visits = 0;
                    if (seed == 0) {
                        while (!workers[visits++ % 2].submit(index::buildStep).get(30, TimeUnit.SECONDS)) {}
                    } else while (!index.buildStep()) {}
                    for (int query = 0; query < 200; query++) {
                        var low = new BigInteger[dimensions];
                        var high = new BigInteger[dimensions];
                        for (int d = 0; d < dimensions; d++) {
                            low[d] = random.nextInt(4) == 0 ? null : scale.multiply(BigInteger.valueOf(random.nextInt(81) - 40));
                            high[d] = random.nextInt(4) == 0 ? null : scale.multiply(BigInteger.valueOf(random.nextInt(81) - 40));
                        }
                        if (query < original.length / 2) {
                            low = original[query].clone();
                            high = low.clone();
                        }
                        if (query == 199) { low = original[133].clone(); high = low.clone(); }
                        if (query == 198) { Arrays.fill(low, BigInteger.ONE.shiftLeft(20000).negate()); Arrays.fill(high, null); }
                        var expectedLow = low.clone();
                        var expectedHigh = high.clone();
                        int expected = -1;
                        for (int i = 0; i < original.length; i++) if (inside(original[i], expectedLow, expectedHigh)) {
                            expected = i;
                            break;
                        }
                        index.begin(low, high);
                        Arrays.fill(low, BigInteger.ZERO);
                        Arrays.fill(high, BigInteger.ZERO);
                        if (seed == 0) {
                            while (!workers[visits++ % 2].submit(index::queryStep).get(30, TimeUnit.SECONDS)) {}
                        } else while (!index.queryStep()) {}
                        int id = index.match();
                        check(id == expected, "first-match oracle mismatch: " + seed + "/" + query);
                        if (id >= 0) check(inside(original[id], expectedLow, expectedHigh), "out-of-box match");
                    }
                    if (refined.getBoolean(index)) refinements++;
                }
                check(budget.reservedBytes() == 0, "box workspace leaked");
            }
            check(refinements > 0, "box campaign did not exercise spatial refinement");
        } finally { for (var worker : workers) worker.shutdownNow(); }
    }

    private static void correlated() {
        int n = 4096;
        var table = new BigInteger[n][3];
        for (int i = 0; i < n; i++) table[i] = new BigInteger[]{BigInteger.valueOf(i), BigInteger.valueOf(n - i), BigInteger.valueOf(i % 13)};
        var budget = budget();
        try (var index = CountRangeIndex.create(n, 3, i -> table[i], budget, budget::check)) {
            check(index != null, "correlated table declined");
            while (!index.buildStep()) {}
            for (int i = 0; i < 1000; i++) {
                index.begin(new BigInteger[3], new BigInteger[]{BigInteger.valueOf(1023), BigInteger.valueOf(1023), null});
                while (!index.queryStep()) {}
                check(index.match() == -1, "impossible correlated box matched");
            }
            check(budget.searchWork() < n * 1000L / 4, "index did not amortize construction");
        }
        check(budget.reservedBytes() == 0, "correlated workspace leaked");
    }

    private static void matcher() throws Exception {
        long indexedWork = 0, scanWork = 0;
        int activated = 0, pauses = 0;
        var disabled = CountMeetInMiddle.class.getDeclaredField("rangeIndexAttempted");
        var active = CountMeetInMiddle.class.getDeclaredField("rangeIndex");
        disabled.setAccessible(true);
        active.setAccessible(true);
        for (int seed = 0; seed < 16; seed++) {
            int n = 20;
            var random = new Random(6124L + seed);
            var a = new TreeMap<Integer, BigInteger>();
            var b = new TreeMap<Integer, BigInteger>();
            var reverse = new TreeMap<Integer, BigInteger>();
            int sumA = 0, sumB = 0;
            for (int i = 0; i < n; i++) {
                int x = 1 + random.nextInt(100), y = 1 + random.nextInt(100);
                sumA += x; sumB += y;
                a.put(i, BigInteger.valueOf(x)); b.put(i, BigInteger.valueOf(y));
                reverse.put(i, BigInteger.valueOf(-2 * x - 3 * y));
            }
            int capA = sumA / 2, capB = sumB / 2;
            boolean possible = seed >= 8;
            if (possible) {
                capA = capB = 0;
                // A known original-coordinate subset defines a tight but
                // satisfiable box; no auxiliary matcher supplies the oracle.
                for (int i = 0; i < n; i++) if (random.nextBoolean()) {
                    capA += a.get(i).intValueExact();
                    capB += b.get(i).intValueExact();
                }
            }
            var rows = List.of(new ExactLinearProgram.Constraint(a, BigInteger.valueOf(capA)),
                    new ExactLinearProgram.Constraint(b, BigInteger.valueOf(capB)),
                    new ExactLinearProgram.Constraint(reverse, BigInteger.valueOf(-2 * capA - 3 * capB - (possible ? 0 : 1))));
            // Combining the original rows with weights 2, 3, 1 gives 0 <= -1, an independent
            // infeasibility certificate; neither query ordering nor indexing is its oracle.
            var low = new BigInteger[n]; var high = new BigInteger[n];
            Arrays.fill(low, BigInteger.ZERO); Arrays.fill(high, BigInteger.ONE);
            BigInteger[] first = null;
            for (boolean useIndex : new boolean[]{false, true}) {
                var budget = budget();
                try (var search = new CountMeetInMiddle(rows, low, high, budget, 2048).retained()) {
                    if (!useIndex) disabled.setBoolean(search, true);
                    for (;;) {
                        while (!search.step()) {}
                        if (!search.paused()) break;
                        check(search.counts() == null && !search.infeasible(), "pause became a conclusion");
                        search.resume(2048);
                        pauses++;
                    }
                    check(search.infeasible() == !possible && (search.counts() != null) == possible,
                            "matcher disagrees with original-row oracle");
                    if (!useIndex) first = search.counts();
                    else check(Arrays.equals(first, search.counts()), "index changed the first count witness");
                    if (possible) for (var row : rows) {
                        var value = BigInteger.ZERO;
                        for (var term : row.terms().entrySet())
                            value = value.add(term.getValue().multiply(search.counts()[term.getKey()]));
                        check(value.compareTo(row.upper()) <= 0, "matcher witness failed original rows");
                    }
                    if (useIndex) { indexedWork += budget.searchWork(); if (active.get(search) != null) activated++; }
                    else scanWork += budget.searchWork();
                }
                check(budget.reservedBytes() == 0, "matcher workspace leaked");
            }
        }
        System.out.println("Matcher range control: indexed=" + indexedWork + "; scan=" + scanWork +
                "; pauses=" + pauses + "; activated=" + activated);
        check(activated >= 12 && pauses > 100, "campaign did not exercise retained indexes");
        check(indexedWork < scanWork, "indexed matcher lost the scan cost control");
    }

    private static void refinementCancellation() throws Exception {
        var source = new BigInteger[512][2];
        for (int i = 0; i < source.length; i++)
            source[i] = new BigInteger[]{BigInteger.valueOf(2L * (i % 32)), BigInteger.valueOf(2L * ((i * 13) % 64))};
        var refined = CountRangeIndex.class.getDeclaredField("refined");
        refined.setAccessible(true);
        var cancelled = new AtomicBoolean();
        var budget = new PlanningBudget(0, 40_000_000, 256L << 20, cancelled::get, System::nanoTime);
        try (var index = CountRangeIndex.create(source.length, 2, i -> source[i], budget, budget::check)) {
            check(index != null, "refinement control declined");
            while (!index.buildStep()) {}
            boolean rebuilding = false;
            for (int query = 0; query < 1000; query++) {
                index.begin(new BigInteger[]{BigInteger.ONE, BigInteger.ONE}, new BigInteger[]{BigInteger.ONE, BigInteger.ONE});
                if (refined.getBoolean(index)) {
                    check(!index.queryStep(), "unfinished refinement returned a conclusion");
                    cancelled.set(true);
                    try { index.queryStep(); throw new AssertionError("refinement cancellation ignored"); }
                    catch (CancellationException expected) { /* no witness or proof */ }
                    rebuilding = true;
                    break;
                }
                while (!index.queryStep()) {}
                check(index.match() == -1, "odd coordinates matched an even table");
            }
            check(rebuilding, "cancellation control did not refine");
        }
        check(budget.reservedBytes() == 0, "cancelled refinement workspace leaked");
    }

    private static void limits() {
        var source = new BigInteger[128][2];
        for (var row : source) Arrays.fill(row, BigInteger.ZERO);
        var cancelled = new AtomicBoolean();
        var budget = new PlanningBudget(0, 40_000_000, 256L << 20, cancelled::get, System::nanoTime);
        try (var index = CountRangeIndex.create(128, 2, i -> source[i], budget, budget::check)) {
            check(index != null, "cancellation index declined");
            index.buildStep();
            cancelled.set(true);
            try { index.buildStep(); throw new AssertionError("cancellation ignored"); }
            catch (CancellationException expected) { /* no conclusion */ }
        }
        check(budget.reservedBytes() == 0, "cancelled workspace leaked");
        var small = new PlanningBudget(0, 40_000_000, 1024, () -> false, System::nanoTime);
        check(CountRangeIndex.create(128, 2, i -> source[i], small, small::check) == null, "memory refusal ignored");
        check(small.reservedBytes() == 0, "refused workspace leaked");
        var owned = budget();
        source[37][0] = BigInteger.ONE.shiftLeft(1025);
        check(CountRangeIndex.create(128, 2, i -> source[i], owned, owned::check) == null, "oversized index accepted");
        check(owned.reservedBytes() == 0, "invalid input leaked");
        source[37][0] = BigInteger.ZERO;
        var checks = new int[1];
        try {
            CountRangeIndex.create(128, 2, i -> source[i], owned, () -> {
                owned.check();
                if (++checks[0] == 500) throw new CancellationException();
            });
            throw new AssertionError("construction cancellation ignored");
        } catch (CancellationException expected) { /* partial ownership released */ }
        check(owned.reservedBytes() == 0, "partial construction leaked");
    }

    private static boolean inside(BigInteger[] point, BigInteger[] low, BigInteger[] high) {
        for (int d = 0; d < point.length; d++)
            if (low[d] != null && point[d].compareTo(low[d]) < 0 || high[d] != null && point[d].compareTo(high[d]) > 0) return false;
        return true;
    }

    private static PlanningBudget budget() { return new PlanningBudget(0, 40_000_000, 256L << 20, () -> false, System::nanoTime); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
