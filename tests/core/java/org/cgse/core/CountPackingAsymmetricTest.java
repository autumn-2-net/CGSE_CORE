// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent exact neighborhoods with different paid radii on each side. */
public final class CountPackingAsymmetricTest {
    public static void main(String[] args) throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try {
            for (int trial = 0; trial < 512; trial++) {
                var random = new Random(20102026L + trial);
                int n = 2 + trial % 13, m = 1 + trial % 35;
                int left = 1 + trial % 4, right = 1 + trial / 4 % 4;
                long seed = random.nextInt(1 << n), flexible = trial % 7 == 0 ? 0 : random.nextInt(1 << n);
                long planted = random.nextInt(1 << n);
                long[][] a = new long[m][n]; long[] b = new long[m];
                for (int r = 0; r < m; r++) {
                    for (int i = 0; i < n; i++) {
                        a[r][i] = random.nextInt(31) - 15;
                        if ((planted & (1L << i)) != 0) b[r] += a[r][i];
                    }
                    b[r] += random.nextInt(9) - 4;
                }
                boolean expected = false;
                for (long x = 0; x < 1L << n; x++)
                    if (allowed(x ^ seed, n, flexible, left, right) && valid(a, b, x)) { expected = true; break; }
                Long found = solve(a, b, seed, flexible, left, right, trial < 16 ? worker : null);
                check((found != null) == expected, "Disagreed with exhaustive neighborhood " + trial);
                if (found != null) check(valid(a, b, found) && allowed(found ^ seed, n, flexible, left, right), "Invalid witness");
            }
            wider(worker);
            orientation();
            lifecycle();
        } finally { worker.shutdownNow(); }
        System.out.println("512 exact asymmetric neighborhoods, five paid flips, orientation, wide rows, handoff and ownership passed");
    }

    private static void wider(ExecutorService worker) throws Exception {
        int n = 40;
        long flexible = 31 | (15L << 20);
        long witness = flexible | (1L << 19) | (15L << 36);
        long[][] a = new long[14][n]; long[] b = new long[14];
        int row = 0;
        for (int i = 0; i < n; i++) if ((witness & (1L << i)) != 0) { a[row][i] = -1; b[row++] = -1; }
        check(solve(a, b, 0, flexible, 2, 2, null) == null, "Smaller neighborhood unexpectedly covers four right flips");
        for (long scale : new long[] {1, 1L << 53}) {
            long[][] scaled = new long[a.length][n]; long[] bounds = new long[b.length];
            for (int r = 0; r < a.length; r++) {
                bounds[r] = b[r] * scale;
                for (int i = 0; i < n; i++) scaled[r][i] = a[r][i] * scale;
            }
            Long value = solve(scaled, bounds, 0, flexible, 1, 4, null);
            Long moved = solve(scaled, bounds, 0, flexible, 1, 4, worker);
            check(value != null && (value & witness) == witness && Objects.equals(value, moved), "Asymmetric witness lost across handoff");
        }
        // The larger right radius must also size the ordinary subset buffer
        // and prefix stack when there are no flexible coordinates at all.
        long[][] ordinary = new long[5][16]; long[] rhs = {-1, -1, -1, -1, -1};
        ordinary[0][7] = -1;
        for (int i = 1; i < 5; i++) ordinary[i][7 + i] = -1;
        check(solve(ordinary, rhs, 0, 0, 1, 4, null) != null, "Ordinary right buffer inherited the left radius");
    }

    private static void orientation() {
        for (int n = 2; n <= 40; n++) for (int f = 1; f <= Math.min(10, n); f++) {
            int split = n / 2; long mask = (1L << n) - 1;
            // Exercise odd widths and non-prefix fractional masks too.
            long flexible = 0;
            for (int j = 0; j < f; j++) flexible |= 1L << (n - 1 - j);
            var budget = budget(40_000_000, 64L << 20, () -> false);
            var advice = CountPackingSchedule.expansion(n, flexible, budget);
            long rotated = ((flexible >>> split) | (flexible << (n - split))) & mask;
            long[] first = points(n, flexible), second = points(n, rotated);
            boolean admitFirst = first[0] <= 65536 && first[1] <= 65536;
            boolean admitSecond = second[0] <= 65536 && second[1] <= 65536;
            check((advice != null) == (admitFirst || admitSecond), "Incorrect expansion admission");
            if (advice != null) {
                long[] chosen = advice.rotate() ? second : first;
                check(chosen[0] == advice.leftPoints() && chosen[1] == advice.rightPoints(), "Wrong table estimate");
                if (admitFirst && admitSecond) check(chosen[0] + chosen[1] == Math.min(first[0] + first[1], second[0] + second[1]), "Wrong orientation");
            }
        }
        var budget = budget(1_000_000, 64L << 20, () -> false);
        check(CountPackingSchedule.expansion(41, 3, budget) == null &&
                CountPackingSchedule.expansion(40, 2047, budget) == null, "Oversized companion admitted");
    }

    private static long[] points(int n, long mask) {
        int split = n / 2;
        int lf = Long.bitCount(mask & ((1L << split) - 1)), rf = Long.bitCount(mask >>> split);
        return new long[] {(1L << lf) * (1 + split - lf), (1L << rf) * combinations(n - split - rf, 4)};
    }
    private static long combinations(int n, int radius) {
        long sum = 1, binomial = 1;
        for (int k = 1; k <= Math.min(n, radius); k++) { binomial = binomial * (n - k + 1) / k; sum += binomial; }
        return sum;
    }

    private static void lifecycle() {
        long[][] a = {{1, 2, 3, -1, -2, -3}}; long[] b = {0};
        var budget = budget(1_000_000, 1, () -> false);
        try (var search = new CountPackingExchange(a, b, 0, budget, 1, 9, 4)) {
            check(search.step() && !search.feasible(), "Memory refusal became a witness");
        }
        check(budget.reservedBytes() == 0, "Memory refusal leaked");
        var cancelled = new AtomicBoolean(); budget = budget(1_000_000, 64L << 20, cancelled::get);
        try (var search = new CountPackingExchange(a, b, 0, budget, 1, 9, 4)) {
            search.step(); cancelled.set(true);
            try { search.step(); throw new AssertionError("Ignored cancellation"); } catch (CancellationException expected) {}
        }
        check(budget.reservedBytes() == 0, "Cancellation leaked");
        budget = budget(1_000_000, 64L << 20, () -> false);
        try {
            new CountPackingExchange(new long[1][40], new long[1], 0, budget, 1, (1L << 20) - 1, 4);
            throw new AssertionError("Oversized exact domain was silently truncated");
        } catch (IllegalArgumentException expected) {}
        check(budget.reservedBytes() == 0, "Oversized domain leaked");
    }

    private static Long solve(long[][] a, long[] b, long seed, long flexible, int left, int right, ExecutorService worker) throws Exception {
        var budget = budget(40_000_000, 64L << 20, () -> false);
        Long found;
        try (var search = new CountPackingExchange(a, b, seed, budget, left, flexible, right)) {
            boolean done = false;
            while (!done) {
                if (worker == null) done = search.step();
                else done = worker.submit(() -> { for (int i = 0; i < 97; i++) if (search.step()) return true; return false; }).get(30, TimeUnit.SECONDS);
            }
            found = search.feasible() ? search.assignment() : null;
        }
        check(budget.reservedBytes() == 0, "Workspace leaked");
        return found;
    }
    private static boolean allowed(long mask, int n, long flexible, int left, int right) {
        mask &= ~flexible;
        return Long.bitCount(mask & ((1L << (n / 2)) - 1)) <= left && Long.bitCount(mask >>> (n / 2)) <= right;
    }
    private static boolean valid(long[][] a, long[] b, long mask) {
        for (int r = 0; r < b.length; r++) {
            long sum = 0; for (int i = 0; i < a[r].length; i++) if ((mask & (1L << i)) != 0) sum += a[r][i];
            if (sum > b[r]) return false;
        }
        return true;
    }
    private static PlanningBudget budget(long work, long memory, java.util.function.BooleanSupplier cancelled) {
        return new PlanningBudget(0, work, memory, cancelled, System::nanoTime);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
