// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Every pair of power-of-two equalities has one independently known assignment. */
public final class CountPackingIndexTest {
    public static void main(String[] args) throws Exception {
        int checked = 0;
        var worker = Executors.newSingleThreadExecutor();
        try {
            for (int radius : new int[] {3, 4})
            for (int n : new int[] {2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 20, 24, 31, 32, 33, 40, 48, 63, 64}) {
                int split = n / 2;
                long all = n == 64 ? -1L : (1L << n) - 1;
                for (int kind = 0; kind < 3; kind++) {
                    var random = new Random(413901L + 17L * n + kind);
                    long seed = random.nextLong() & all, change = 0;
                    for (int start : new int[] {0, split}) {
                        int end = start == 0 ? split : n;
                        for (int j = 0; j < Math.min(radius, end - start); j++)
                            change |= 1L << (kind == 0 ? start + j : end - 1 - j);
                    }
                    // One extra flip lies outside this tool's declared radius
                    // even though the original equalities have a unique witness.
                    boolean expected = kind != 2 || split <= radius;
                    if (!expected) change |= 1L << (split - radius - 1);
                    long wanted = seed ^ change;
                    var rows = new long[4][n]; var bounds = new long[4];
                    long scale = kind == 1 ? 1L << 25 : 1;
                    for (int i = 0; i < n; i++) {
                        int row = i < split ? 0 : 2, position = i < split ? i : i - split;
                        long coefficient = (1L << position) * scale;
                        rows[row][i] = coefficient; rows[row + 1][i] = -coefficient;
                        if ((wanted & (1L << i)) != 0) { bounds[row] += coefficient; bounds[row + 1] -= coefficient; }
                    }
                    var found = solve(rows, bounds, seed, radius, n == 12 ? worker : null);
                    if (expected ? found == null || found != wanted : found != null)
                        throw new AssertionError("Lost exact exchange assignment: width=" + n + ", kind=" + kind + ", radius=" + radius);
                    checked++;
                }
            }
        } finally { worker.shutdownNow(); }
        exhaustiveRadii();
        storageAndCancellation(3, 256L << 10, 1360);
        storageAndCancellation(4, 1L << 20, 6205);
        System.out.println(checked + " unique exact exchange oracles, incomplete leaves, scaled signed rows, worker joins and compact storage passed");
    }

    private static Long solve(long[][] rows, long[] bounds, long seed, int radius, ExecutorService worker) throws Exception {
        var budget = CountPackingExchangeTest.budget(256L << 20, () -> false);
        try (var search = new CountPackingExchange(rows, bounds, seed, budget, radius)) {
            while (!(worker == null ? search.step() : worker.submit(search::step).get())) {}
            return search.feasible() ? search.assignment() : null;
        } finally {
            if (budget.reservedBytes() != 0) throw new AssertionError("Exchange reservation leaked");
        }
    }

    private static void exhaustiveRadii() throws Exception {
        for (int trial = 0; trial < 256; trial++) {
            int n = 2 + trial % 13, m = 1 + trial % 9, radius = 1 + trial % 4;
            var random = new Random(984607L + trial);
            long seed = random.nextInt(1 << n), half = (1L << (n / 2)) - 1;
            var rows = new long[m][n]; var bounds = new long[m];
            for (int r = 0; r < m; r++) {
                bounds[r] = random.nextInt(31) - 15;
                for (int i = 0; i < n; i++) rows[r][i] = random.nextInt(31) - 15;
            }
            boolean expected = false;
            for (long mask = 0; mask < 1L << n; mask++)
                if (Long.bitCount((mask ^ seed) & half) <= radius && Long.bitCount((mask ^ seed) & ~half) <= radius
                        && CountPackingExchangeTest.valid(rows, bounds, mask)) { expected = true; break; }
            var found = solve(rows, bounds, seed, radius, null);
            if ((found != null) != expected || found != null && (!CountPackingExchangeTest.valid(rows, bounds, found)
                    || Long.bitCount((found ^ seed) & half) > radius || Long.bitCount((found ^ seed) & ~half) > radius))
                throw new AssertionError("Independent radius oracle " + trial);
        }
    }

    private static void storageAndCancellation(int radius, long memory, int turns) {
        var stop = new AtomicBoolean();
        var budget = CountPackingExchangeTest.budget(memory, stop::get);
        try (var search = new CountPackingExchange(new long[12][40], new long[12], 0, budget, radius)) {
            while (!search.step()) {}
            if (!search.feasible() || budget.peakBytes() > memory) throw new AssertionError("Compact index admission");
        }
        if (budget.reservedBytes() != 0) throw new AssertionError("Completed index retained memory");
        try (var search = new CountPackingExchange(new long[12][40], new long[12], 0, budget, radius)) {
            // Point enumeration is complete; cancellation occurs during tree
            // construction, whose postorder stack can contain unfinished joins.
            for (int i = 0; i < turns; i++) search.step();
            stop.set(true);
            try { search.step(); throw new AssertionError("Ignored index cancellation"); }
            catch (java.util.concurrent.CancellationException expected) {}
        }
        if (budget.reservedBytes() != 0) throw new AssertionError("Cancelled index retained memory");
        var small = CountPackingExchangeTest.budget(1024, () -> false);
        try (var search = new CountPackingExchange(new long[12][40], new long[12], 0, small, radius)) {
            if (!search.step() || search.feasible() || small.reservedBytes() != 0) throw new AssertionError("Declined index leaked");
        }
    }
}
