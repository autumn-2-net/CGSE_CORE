// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent exhaustive oracles for signed rows, not the numerical LP proposal. */
public final class CountPackingPropagationTest {
    public static void main(String[] args) throws Exception {
        int checked = 0;
        var worker = Executors.newSingleThreadExecutor();
        try {
            for (int seed = 0; seed < 512; seed++) {
                var random = new Random(seed + 17539L);
                int n = 2 + seed % 9, m = 2 + random.nextInt(9);
                long scale = seed % 5 == 0 ? 1L << 44 : 1;
                var a = new long[m][n]; var b = new long[m];
                long planted = random.nextInt(1 << n);
                for (int r = 0; r < m; r++) {
                    for (int i = 0; i < n; i++) {
                        a[r][i] = (random.nextInt(19) - 9) * scale;
                        if ((planted & 1L << i) != 0) b[r] += a[r][i];
                    }
                    b[r] += (random.nextInt(9) - 4) * scale;
                }
                boolean expected = false;
                for (long mask = 0; mask < 1L << n && !expected; mask++) expected = valid(a, b, mask);
                long preferred = random.nextLong();
                var result = solve(a, b, preferred, seed < 8 ? worker : null);
                check(result != null == expected, "Exhaustive result changed: " + seed);
                if (result != null) check(valid(a, b, result), "Invalid signed witness");
                // Scatter the same problem across both halves of a 64-bit
                // domain. A separate row forces only the padding to zero.
                int[] ids = new int[n];
                ids[0] = 63; ids[1] = 31;
                for (int i = 2; i < n; i++) ids[i] = i % 2 == 0 ? 30 + i : i - 3;
                var wide = new long[m + 1][64]; var bounds = Arrays.copyOf(b, m + 1);
                Arrays.fill(wide[m], 1);
                long hint = 0;
                for (int i = 0; i < n; i++) {
                    wide[m][ids[i]] = 0;
                    if ((preferred & 1L << i) != 0) hint |= 1L << ids[i];
                    for (int r = 0; r < m; r++) wide[r][ids[i]] = a[r][i];
                }
                var scattered = solve(wide, bounds, hint, null);
                check(scattered != null == expected, "64-bit padding changed feasibility: " + seed);
                if (scattered != null) {
                    check(valid(wide, bounds, scattered), "Invalid scattered witness");
                    long restored = 0;
                    for (int i = 0; i < n; i++) if ((scattered & 1L << ids[i]) != 0) restored |= 1L << i;
                    check(valid(a, b, restored), "Scattered witness does not restore");
                }
                checked += 2;
            }
        } finally { worker.shutdownNow(); }
        wordBoundaries();
        cancellation();
        System.out.println("Packing propagation: " + checked + " exhaustive signed/scaled controls, 64-bit boundaries, backtracking and worker handoffs passed");
    }

    private static void wordBoundaries() throws Exception {
        for (int n : new int[] {1, 31, 32, 33, 63, 64}) {
            long all = n == 64 ? -1L : (1L << n) - 1;
            var a = new long[2][n]; Arrays.fill(a[0], -1); Arrays.fill(a[1], 1);
            var b = new long[] {-n, n};
            check(Objects.equals(solve(a, b, 0, null), all), "Lost forced-one word: " + n);
            b[1]--;
            check(solve(a, b, all, null) == null, "Missed conflicting forced values: " + n);
            Arrays.fill(a[0], 1); b[0] = 0; b[1] = n;
            check(Objects.equals(solve(a, b, all, null), 0L), "Lost forced-zero word: " + n);
        }
        var mixed = new long[1][64]; long bound = 0, expected = 0;
        for (int i = 0; i < 64; i++) {
            mixed[0][i] = (i % 3 == 0 ? -1 : 1) * ((1L << 48) + i);
            if (mixed[0][i] < 0) { bound += mixed[0][i]; expected |= 1L << i; }
        }
        check(Objects.equals(solve(mixed, new long[] {bound}, ~expected, null), expected), "Mixed-sign forced word");
        var rows = new long[2][64]; Arrays.fill(rows[0], 1); rows[1][63] = -1;
        Long found = solve(rows, new long[] {32, -1}, -1, null);
        check(found != null && found < 0 && Long.bitCount(found) == 32, "Sign bit or exact slack tie lost");
        var budget = budget(() -> false);
        for (long value : new long[] {Long.MIN_VALUE, Long.MAX_VALUE, 1L << 60}) {
            try {
                new CountPackingSearch(new long[][] {{value}}, new long[] {0}, 0, budget);
                throw new AssertionError("Unsafe long coefficient accepted");
            } catch (IllegalArgumentException expectedFailure) {}
        }
    }

    private static void cancellation() {
        var stop = new AtomicBoolean(); var budget = budget(stop::get);
        var search = new CountPackingSearch(new long[][] {{1, 1}}, new long[] {1}, 3, budget);
        search.step(); stop.set(true);
        try { search.step(); throw new AssertionError("Cancelled propagation continued"); }
        catch (CancellationException expected) {}
    }

    private static Long solve(long[][] a, long[] b, long seed, ExecutorService worker) throws Exception {
        var budget = budget(() -> false);
        var search = new CountPackingSearch(a, b, seed, budget);
        for (int turns = 0; ; turns++) {
            check(turns < 100000, "Propagation did not terminate");
            if (worker == null ? search.step() : worker.submit(search::step).get()) break;
        }
        check(budget.reservedBytes() == 0, "Private kernel unexpectedly owns a reservation");
        return search.feasible() ? search.assignment() : null;
    }

    private static boolean valid(long[][] rows, long[] bounds, long assignment) {
        for (int r = 0; r < rows.length; r++) {
            var sum = BigInteger.ZERO;
            for (int i = 0; i < rows[r].length; i++) if ((assignment & 1L << i) != 0)
                sum = sum.add(BigInteger.valueOf(rows[r][i]));
            if (sum.compareTo(BigInteger.valueOf(bounds[r])) > 0) return false;
        }
        return true;
    }
    private static PlanningBudget budget(java.util.function.BooleanSupplier stopped) {
        return new PlanningBudget(0, 40_000_000, 64L << 20, stopped, System::nanoTime);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
