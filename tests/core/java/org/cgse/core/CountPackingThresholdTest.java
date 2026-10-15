// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;
import java.util.concurrent.*;

/** Compare the first witness with independent exhaustive Boolean evaluation. */
public final class CountPackingThresholdTest {
    public static void main(String[] args) throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try {
            for (int trial = 0; trial < 4096; trial++) checkCase(trial, trial < 16 ? worker : null);
        } finally { worker.shutdownNow(); }
        ties();
        memoryAndLimits();
        System.out.println("4096 signed, clustered and scaled threshold models preserve the first exhaustive witness; exact ties and worker migration passed");
    }

    private static void checkCase(int trial, ExecutorService worker) throws Exception {
        var random = new Random(73811L + trial);
        int n = 2 + trial % 11, m = 1 + random.nextInt(12);
        long scale = trial % 7 == 0 ? 1L << 42 : 1;
        long seed = random.nextLong() & ((1L << n) - 1), planted = random.nextInt(1 << n);
        var a = new long[m][n]; var b = new long[m];
        for (int r = 0; r < m; r++) {
            for (int i = 0; i < n; i++) {
                long value = (random.nextInt(13) - 6) * scale;
                if (trial % 3 == 0 && value != 0) value += Long.signum(value) * (1L << 49);
                a[r][i] = value;
                if ((planted & 1L << i) != 0) b[r] += value;
            }
            b[r] += (random.nextInt(9) - 4) * scale;
        }
        Long expected = null;
        for (int order = 0; order < 1 << n; order++) {
            long mask = seed ^ (Integer.toUnsignedLong(Integer.reverse(order)) >>> (32 - n));
            if (valid(a,b,mask)) { expected = mask; break; }
        }
        var budget = new PlanningBudget(0,40_000_000,64L<<20,() -> false,System::nanoTime);
        try (var search = new CountPackingSearch(a,b,seed,budget)) {
            for (int turns = 0; !(worker == null ? search.step() : worker.submit(search::step).get()); turns++)
                check(turns < 100000, "Unbounded small search");
            Long actual = search.feasible() ? search.assignment() : null;
            check(Objects.equals(expected,actual), "Changed first witness, trial " + trial + ": " + expected + " / " + actual);
        }
        check(budget.reservedBytes()==0,"Search leaked");
    }

    private static void ties() {
        for (long coefficient : new long[] {1, 127, 128, 129, 255, 256, 257, (1L<<40)-1, 1L<<40, (1L<<40)+1, (1L<<57)-1})
            for (int sign : new int[] {-1,1}) for (int delta : new int[] {-1,0,1}) {
                var a = new long[][] {{sign*coefficient,sign*coefficient}};
                var b = new long[] {sign*coefficient+delta};
                var budget = new PlanningBudget(0,40_000_000,64L<<20,()->false,System::nanoTime);
                try (var search = new CountPackingSearch(a,b,3,budget)) {
                    while(!search.step()) {}
                    boolean expected=false;
                    for(long mask=0;mask<4;mask++) expected |= valid(a,b,mask);
                    check(search.feasible()==expected,"Wrong exact threshold tie");
                    if(search.feasible())check(valid(a,b,search.assignment()),"Rounded a threshold");
                }
            }
    }
    private static boolean valid(long[][] a,long[] b,long mask) {
        for(int r=0;r<a.length;r++) {
            long value=0;
            for(int i=0;i<a[r].length;i++) if((mask&1L<<i)!=0)value+=a[r][i];
            if(value>b[r])return false;
        }
        return true;
    }

    private static void memoryAndLimits() {
        var rows = new long[4][12];
        var bounds = new long[] {200, -180, 175, -160};
        for (int i = 0; i < 12; i++) {
            rows[0][i] = 3 + 9 * i; rows[1][i] = -rows[0][i];
            rows[2][i] = 11 + 7 * i; rows[3][i] = -rows[2][i];
        }
        Long reference = null;
        for (long memory : new long[] {1, 64L + 576L * rows.length - 1, 64L + 576L * rows.length}) {
            var budget = new PlanningBudget(0, 40_000_000, memory, () -> false, System::nanoTime);
            try (var search = new CountPackingSearch(rows, bounds, 0, budget)) {
                while (!search.step()) {}
                check(search.feasible(), "Lost witness on index memory refusal");
                if (reference == null) reference = search.assignment();
                else check(reference == search.assignment(), "Memory admission changed search order");
                check(budget.peakBytes() <= memory, "Unreserved threshold storage");
            }
            check(budget.reservedBytes() == 0, "Threshold storage leaked after close");
        }
        for (long work = 1; work <= 400; work++) {
            var budget = new PlanningBudget(0, work, 64L << 20, () -> false, System::nanoTime);
            try (var search = new CountPackingSearch(rows, bounds, 0, budget)) {
                while (!search.step()) {}
            } catch (PlanningBudget.Exhausted expected) {}
            check(budget.reservedBytes() == 0, "Construction/search budget exhaustion leaked: " + work);
        }
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
