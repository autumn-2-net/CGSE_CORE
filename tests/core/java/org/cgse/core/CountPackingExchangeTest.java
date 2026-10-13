// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util. * ;
import java.util.concurrent. * ;
import java.util.concurrent.atomic.AtomicBoolean;
public final class CountPackingExchangeTest {
    public static void main(String[] args) throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try {
            for (int trial = 0; trial < 512; trial++) {
                var random = new Random(2299423L + trial);
                int n = 2 + trial%13, m = 1 + trial%12;
                long seed = random.nextInt(1 << n);
                var a = new long[m][n];
                var b = new long[m];
                int planted = random.nextInt(1 << n);
                for (int r = 0; r < m; r++) {
                    for (int i = 0; i < n; i++) {
                        a[r][i] = random.nextInt(29) - 14;
                        if ((planted & (1 << i)) != 0)b[r] += a[r][i];
                    }

                    b[r] += random.nextInt(7) - 3;
                }

                long left = (1L << (n/2)) - 1;
                boolean expected = false;
                for (int x = 0; x < 1 << n; x++)if (Long.bitCount((x ^ seed) & left) <= 3 && Long.bitCount((x ^ seed) & ~left) <= 3 && valid(a, b, x)) {
                    expected = true;
                    break;
                }

                var found = solve(a, b, seed, trial < 8 ? worker : null);
                check((found != null) == expected, "oracle " + trial);
                if (found != null)check(valid(a, b, found) && Long.bitCount((found ^ seed) & left) <= 3 && Long.bitCount((found ^ seed) & ~left) <= 3, "witness " + trial);
            }

        }

        finally {
            worker.shutdownNow();
        }

        var a = new long[35][64];
        var b = new long[35];
        Arrays.fill(a[0], 1);
        b[0] = 1;
        a[34][63] =  - 1;
        b[34] =  - 1;
        check(Objects.equals(solve(a, b, 0, null), Long.MIN_VALUE), "word/row boundary");
        a = new long[2][16];
        b = new long[] {
 - 4, 0
        };
        for (int i = 0; i < 8; i++)a[0][i] =  - 1;
        for (int i = 8; i < 16; i++)a[1][i] = 1;
        check(solve(a, b, 0, null) == null && valid(a, b, 15), "restricted empty face differs from original SAT");
        var tiny = budget(1, () -> false);
        try (var s = new CountPackingExchange(new long[][] {
           {
                1, 1
            }

        }

        , new long[] {
            1
        }

        , 0, tiny)) {
            check(s.step() && !s.feasible(), "decline memory");
        }

        check(tiny.reservedBytes() == 0, "refusal cleanup");
        var stop = new AtomicBoolean(true);
        var cancel = budget(64L << 20, stop::get);
        try {
            new CountPackingExchange(new long[][] {
               {
                    1, 1
                }

            }

            , new long[] {
                1
            }

            , 0, cancel);
            throw new AssertionError("cancel");
        }
 catch (CancellationException expected) {
        }

        check(cancel.reservedBytes() == 0, "constructor cancellation cleanup");
        var invalid = budget(64L << 20, () -> false);
        try {
            new CountPackingExchange(new long[][] {
               {
                    1L << 60, 1
                }

            }

            , new long[] {
                0
            }

            , 0, invalid);
            throw new AssertionError("overflow admitted");
        }
 catch (IllegalArgumentException expected) {
        }

        check(invalid.reservedBytes() == 0, "invalid range cleanup");
        System.out.println("512 independent exhaustive exchange oracles; bit 64 / row 35, worker handoff, scoped failure, memory, cancellation and range guards passed");
    }

    static Long solve(long[][] a, long[] b, long seed, ExecutorService worker) throws Exception {
        var budget = budget(256L << 20, () -> false);
        try (var s = new CountPackingExchange(a, b, seed, budget)) {
            int turns = 0;
            while (!(worker == null ? s.step() : worker.submit(s::step).get()))check(++turns < 1000000, "spin");
            return s.feasible() ? s.assignment() : null;
        }

        finally {
            check(budget.reservedBytes() == 0, "release");
        }

    }
    static boolean valid(long[][]a, long[]b, long mask) {
        for (int r = 0; r < a.length; r++) {
            var sum = BigInteger.ZERO;
            for (int i = 0; i < a[r].length; i++)if ((mask & (1L << i)) != 0)sum = sum.add(BigInteger.valueOf(a[r][i]));
            if (sum.compareTo(BigInteger.valueOf(b[r])) > 0) return false;
        }

        return true;
    }

    static PlanningBudget budget(long bytes, java.util.function.BooleanSupplier stop) {
        return new PlanningBudget(0, 40_000_000, bytes, stop, System::nanoTime);
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
