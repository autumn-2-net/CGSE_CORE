// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Direct primitive sums check every suffix query, including forced holes. */
public final class CountPackingTailTest {
    public static void main(String[] args) throws Exception {
        int checked = 0;
        var worker = Executors.newSingleThreadExecutor();
        try {
            for (int trial = 0; trial < 96; trial++) {
                var random = new Random(714403L + trial);
                int width = 4 + trial % 7, n = trial % 3 == 0 ? 64 : width + 8;
                int offset = n - width, m = 1 + trial % 13;
                long scale = trial % 4 == 0 ? 1L << 43 : 1;
                var a = new long[m][n];
                for (int r = 0; r < m; r++) for (int i = 0; i < n; i++)
                    a[r][i] = (random.nextInt(17) - 8) * scale;
                var budget = budget(64L << 20, () -> false);
                try (var tail = CountPackingTail.create(a, offset, budget)) {
                    check(tail != null, "Table admission");
                    int turns = 0;
                    while (!(trial < 4 ? worker.submit(tail::prepare).get() : tail.prepare()))
                        check(++turns < 20000, "Build did not resume");
                    long tableMemory = budget.reservedBytes();
                    for (int query = 0; query < 32; query++) {
                        long low = (1L << offset) - 1, all = n == 64 ? -1L : (1L << n) - 1;
                        long undecided = (random.nextLong() & all) & ~low;
                        long selected = random.nextLong() & all & ~undecided;
                        long planted = (random.nextLong() & undecided) | selected;
                        var bounds = new long[m]; var slacks = new long[m];
                        for (int r = 0; r < m; r++) {
                            bounds[r] = (query % 3 == 0 ? -1 : random.nextInt(9) - 4) * scale;
                            for (int i = 0; i < n; i++) if ((planted & 1L << i) != 0) bounds[r] += a[r][i];
                            slacks[r] = bounds[r];
                            for (int i = 0; i < n; i++) {
                                if ((selected & 1L << i) != 0) slacks[r] -= a[r][i];
                                else if ((undecided & 1L << i) != 0) slacks[r] -= Math.min(0, a[r][i]);
                            }
                        }
                        boolean expected = false;
                        for (long x = 0; x < 1L << width; x++) {
                            long candidate = (x << offset) | (selected & low);
                            if (((candidate ^ selected) & ~undecided) == 0 && valid(a, bounds, candidate)) {
                                expected = true; break;
                            }
                        }
                        tail.begin(undecided, selected, slacks);
                        turns = 0;
                        while (!(trial < 4 ? worker.submit(tail::step).get() : tail.step()))
                            check(++turns < 20000, "Query did not resume");
                        check(tail.feasible() == expected, "Oracle mismatch " + trial + "/" + query);
                        if (expected) {
                            long result = tail.assignment() | (selected & low);
                            check(((result ^ selected) & ~undecided) == 0, "Lost fixed suffix premise");
                            check(valid(a, bounds, result), "Invalid primitive sum");
                        }
                        check(budget.reservedBytes() == tableMemory, "Query retained another table");
                        checked++;
                    }
                }
                check(budget.reservedBytes() == 0, "Suffix table leaked");
            }
        } finally { worker.shutdownNow(); }
        lifecycle();
        restorationLimit();
        exactJoin();
        retainedIntegration();
        denseSuffixAdmission();
        System.out.println(checked + " exhaustive signed/scaled suffix queries, bit 64, forced holes, changed prefixes, handoffs and cleanup passed");
    }

    private static void exactJoin() {
        // A sampled axis may miss a rare extreme. Exact bottom-up boxes must
        // retain every uniquely determined assignment, including bit 64.
        for (int width = 1; width <= 12; width++) {
            int offset = 64 - width;
            long mask = (1L << width) - 1;
            var rows = new long[2][64];
            for (int i = 0; i < width; i++) { rows[0][offset + i] = 1L << i; rows[1][offset + i] = -(1L << i); }
            var budget = budget(64L << 20, () -> false);
            try (var tail = CountPackingTail.create(rows, offset, budget)) {
                try { tail.begin(mask << offset, 0, new long[2]); throw new AssertionError("Queried an unfinished index"); }
                catch (IllegalStateException expected) {}
                while (!tail.prepare()) {}
                for (long value : new long[] {0, 1, 7, 8, 63, 64, 511, 512, 1023, 1024, 1387, 2048, 4095}) {
                    long wanted = value & mask;
                    for (long fixed : new long[] {0, mask, 0x555 & mask, 0xaaa & mask}) {
                        long selected = (wanted & fixed) << offset, undecided = (mask ^ fixed) << offset;
                        var slacks = new long[] {wanted, -wanted};
                        for (int r = 0; r < rows.length; r++) for (int i = offset; i < 64; i++) {
                            if ((selected & 1L << i) != 0) slacks[r] -= rows[r][i];
                            else if ((undecided & 1L << i) != 0) slacks[r] -= Math.min(0, rows[r][i]);
                        }
                        tail.begin(undecided, selected, slacks);
                        while (!tail.step()) {}
                        check(tail.feasible() && tail.assignment() == wanted << offset,
                                "Fixed endpoint correction pruned an exact suffix value");
                    }
                }
            }
            check(budget.reservedBytes() == 0, "Joined index leaked");
        }
    }

    private static void retainedIntegration() {
        // Every coefficient is even, so the odd equality is impossible. This
        // independent parity oracle makes the search exhaust many prefixes.
        var a = new long[2][24]; var random = new Random(4331);
        long sum = 0;
        for (int i = 0; i < 24; i++) { a[0][i] = 2L * (1 + random.nextInt(50)); a[1][i] = -a[0][i]; sum += a[0][i]; }
        long goal = (sum / 2) | 1;
        for (long bytes : new long[] {1, 64L << 20}) {
            var budget = budget(bytes, () -> false);
            try (var search = new CountPackingSearch(a, new long[] {goal, -goal}, 0, budget)) {
                int turns = 0;
                while (!search.step()) check(++turns < 10000000, "Retained tail did not finish");
                check(!search.feasible(), "Odd sum of even coefficients");
                if (bytes > 1) check(budget.peakBytes() > 0, "Did not exercise lazy suffix admission");
            }
            check(budget.reservedBytes() == 0, "Neighborhood owner failed to close suffix");
        }
    }

    private static void lifecycle() {
        var a = new long[35][64]; a[34][63] = -1;
        var tiny = budget(1, () -> false);
        check(CountPackingTail.create(a, 52, tiny) == null && tiny.reservedBytes() == 0, "Memory refusal");
        var stop = new AtomicBoolean();
        var budget = budget(64L << 20, stop::get);
        try (var tail = CountPackingTail.create(a, 52, budget)) {
            tail.prepare(); stop.set(true);
            try { tail.prepare(); throw new AssertionError("Ignored construction cancellation"); }
            catch (CancellationException expected) {}
        }
        check(budget.reservedBytes() == 0, "Cancelled build leaked");
        stop.set(false);
        try (var tail = CountPackingTail.create(a, 52, budget)) {
            while (!tail.prepare()) {}
            var slacks = new long[35];
            tail.begin(-1L << 52, 0, slacks);
            stop.set(true);
            try { tail.step(); throw new AssertionError("Ignored query cancellation"); }
            catch (CancellationException expected) {}
        }
        check(budget.reservedBytes() == 0, "Cancelled query leaked");
    }

    private static void denseSuffixAdmission() throws Exception {
        // Equivalent scaled rows make repeated propagation costly without
        // changing the independent parity oracle: even terms cannot sum to
        // an odd target. This must close inside the existing local allowance,
        // including all index construction, across retained worker handoffs.
        var a = new long[12][26]; var b = new long[12];
        var random = new Random(4331); var base = new long[26]; long sum = 0;
        for (int i = 0; i < base.length; i++) sum += base[i] = 2L * (1 + random.nextInt(50));
        long goal = (sum / 2) | 1;
        for (int r = 0; r < a.length; r++) {
            long scale = (r % 2 == 0 ? 1 : -1) * (r + 1L);
            b[r] = goal * scale;
            for (int i = 0; i < base.length; i++) a[r][i] = base[i] * scale;
        }
        var budget = new PlanningBudget(0, 2_000_000, 64L << 20, () -> false, System::nanoTime);
        var worker = Executors.newSingleThreadExecutor();
        try (var search = new CountPackingSearch(a, b, 0, budget)) {
            int turns = 0;
            while (!(++turns % 64 == 0 ? worker.submit(search::step).get() : search.step())) {}
            check(!search.feasible(), "Scaled odd equality gained a witness");
        } finally { worker.shutdownNow(); }
        check(budget.reservedBytes() == 0, "Dense retained suffix leaked");
        check(budget.cachePreparationWork() == 0, "Suffix preparation spent the cache allowance");
    }

    private static boolean valid(long[][] a, long[] b, long assignment) {
        for (int r = 0; r < a.length; r++) {
            var sum = BigInteger.ZERO;
            for (int i = 0; i < a[r].length; i++) if ((assignment & 1L << i) != 0)
                sum = sum.add(BigInteger.valueOf(a[r][i]));
            if (sum.compareTo(BigInteger.valueOf(b[r])) > 0) return false;
        }
        return true;
    }

    private static void restorationLimit() {
        var rows = new long[35][4];
        for (int r = 0; r < rows.length; r++) for (int i = 0; i < 4; i++)
            rows[r][i] = (i % 2 == 0 ? 1 : -1) * (r + 1L);
        for (long undecided : new long[] {15, 0}) {
            var budget = budget(64L << 20, () -> false);
            try (var tail = CountPackingTail.create(rows, 0, budget)) {
                while (!tail.prepare()) {}
                budget.charge(budget.remainingWork() - 1);
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        tail.begin(undecided, 5 & ~undecided, new long[35]);
                        throw new AssertionError("Restoration returned after budget exhaustion");
                    } catch (PlanningBudget.Exhausted expected) {
                        check(expected.limit() == PlanningBudget.Limit.SEARCH_LIMIT, "Wrong restoration limit");
                    }
                }
                check(budget.cachePreparationWork() == 0, "Restoration used the retention allowance");
            }
            check(budget.reservedBytes() == 0, "Exhausted restoration leaked the suffix table");
        }
    }
    private static PlanningBudget budget(long bytes, java.util.function.BooleanSupplier stopped) {
        return new PlanningBudget(0, 40_000_000, bytes, stopped, System::nanoTime);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
