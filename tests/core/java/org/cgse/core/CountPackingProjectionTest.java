// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent point enumeration checks every two-row implication. */
public final class CountPackingProjectionTest {
    public static void main(String[] args) throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try {
            oracles(worker);
            integration(worker);
            lifecycle();
        } finally { worker.shutdownNow(); }
        System.out.println("4,608 exact projection queries, changed bounds/fixings, deferred admission, handoffs and cleanup passed");
    }

    private static void oracles(ExecutorService worker) throws Exception {
        for (int trial = 0; trial < 144; trial++) {
            var random = new Random(910743L + trial);
            int count = new int[] {1, 3, 17, 65, 511, 1024}[trial % 6];
            int dimensions = 1 + trial % 35, axis = trial % dimensions;
            boolean compact = trial % 3 != 0;
            long scale = compact ? 1 : 1L << 43;
            var raw = new long[count][dimensions];
            var values = new CountPackingValues(count * dimensions, compact);
            for (int i = 0; i < count; i++) for (int r = 0; r < dimensions; r++) {
                raw[i][r] = (random.nextInt(33) - 16) * scale;
                values.set(i * dimensions + r, raw[i][r]);
            }
            var budget = budget(64L << 20, () -> false);
            try (var table = CountPackingProjection.create(values, count, dimensions, axis, compact, budget)) {
                check(table != null, "Projection admission");
                try { table.rejects(new long[dimensions]); throw new AssertionError("Unprepared projection"); }
                catch (IllegalStateException expected) {}
                while (!(trial < 6 ? worker.submit(table::prepare).get() : table.prepare())) {}
                long memory = budget.reservedBytes();
                for (int q = 0; q < 32; q++) {
                    var query = new long[dimensions];
                    for (int r = 0; r < dimensions; r++)
                        query[r] = q == 0 ? Long.MIN_VALUE : q == 1 ? Long.MAX_VALUE : (random.nextInt(41) - 20) * scale;
                    boolean rejected = false;
                    for (int r = 0; r < dimensions; r++) {
                        boolean pair = false;
                        for (var point : raw) if (point[axis] <= query[axis] && point[r] <= query[r]) { pair = true; break; }
                        if (!pair) { rejected = true; break; }
                    }
                    boolean actual = trial < 6 ? worker.submit(() -> table.rejects(query)).get() : table.rejects(query);
                    check(actual == rejected, "Projection mismatch " + trial + "/" + q);
                    check(budget.reservedBytes() == memory, "Query allocated another projection");
                }
            }
            check(budget.reservedBytes() == 0 && budget.cachePreparationWork() == 0, "Projection ownership/accounting");
        }
        // Each pair can pass using a DIFFERENT point. That is not a witness for
        // all rows, and fixed-coordinate restrictions cannot be dropped by callers.
        var points = new CountPackingValues(6, true);
        points.put(new long[] {0, 0, 10, 0, 10, 0}, 0, 0, 6);
        var budget = budget(1L << 20, () -> false);
        try (var table = CountPackingProjection.create(points, 2, 3, 0, true, budget)) {
            while (!table.prepare()) {}
            check(!table.rejects(new long[3]), "Confused joint infeasibility with a failed pair");
        }
    }

    private static void integration(ExecutorService worker) throws Exception {
        int offset = 52;
        var a = new long[2][64];
        for (int i = offset; i < 64; i++) { a[0][i] = 2 * (i - offset + 1L); a[1][i] = -a[0][i]; }
        var budget = budget(64L << 20, () -> false);
        try (var tail = CountPackingTail.create(a, offset, budget)) {
            while (!tail.prepare()) {}
            long initial = budget.reservedBytes();
            // An odd equality over even coefficients has no assignment. Force
            // enough repeated traversals to exercise deferred index construction.
            for (int q = 0; q < 10000 && budget.reservedBytes() == initial; q++) {
                tail.begin(-1L << offset, 0, slacks(a, new long[] {77, -77}, -1L << offset, 0));
                while (!tail.step()) {}
                check(!tail.feasible(), "Odd sum of even coefficients");
            }
            check(budget.reservedBytes() > initial, "Repeated work did not admit the optional projection");
            long retained = budget.reservedBytes();
            var random = new Random(17413);
            for (int q = 0; q < 128; q++) {
                long undecided = random.nextLong() & (-1L << offset);
                long selected = random.nextLong() & (-1L << offset) & ~undecided;
                long goal = q % 2 == 0 ? random.nextInt(157) : 78;
                var bounds = new long[] {goal + (q % 3), -goal};
                boolean expected = false;
                for (long id = 0; id < 4096; id++) {
                    long candidate = id << offset;
                    if (((candidate ^ selected) & ~undecided) == 0 && valid(a, bounds, candidate)) { expected = true; break; }
                }
                tail.begin(undecided, selected, slacks(a, bounds, undecided, selected));
                while (!(q < 8 ? worker.submit(tail::step).get() : tail.step())) {}
                check(tail.feasible() == expected, "Projection carried an old query's bounds/fixings");
                if (expected) check(valid(a, bounds, tail.assignment()) && ((tail.assignment() ^ selected) & ~undecided) == 0,
                        "A relaxed pair was used as a complete witness");
                check(budget.reservedBytes() == retained, "Projection did not retain its index");
            }
        }
        check(budget.reservedBytes() == 0 && budget.cachePreparationWork() == 0, "Tail projection leak/accounting");
    }

    private static void lifecycle() {
        var storage = budget(160L << 10, () -> false);
        try (var table = CountPackingProjection.create(new CountPackingValues(4096 * 35, true),
                4096, 35, 0, true, storage)) {
            check(table != null, "Projection retained one dense summary per point");
        }
        check(storage.reservedBytes() == 0, "Block summaries leaked");
        var values = new CountPackingValues(4096 * 3, true);
        for (int i = 0; i < 4096; i++) { values.set(3 * i, 4095 - i); values.set(3 * i + 1, i); }
        var tiny = budget(1, () -> false);
        check(CountPackingProjection.create(values, 4096, 3, 0, true, tiny) == null && tiny.reservedBytes() == 0, "Memory refusal");
        for (int stage : new int[] {0, 1, 64, 768, 900, 1024, 2048}) {
            var stop = new AtomicBoolean();
            var budget = budget(1L << 20, stop::get);
            try (var table = CountPackingProjection.create(values, 4096, 3, 0, true, budget)) {
                for (int i = 0; i < stage; i++) table.prepare();
                stop.set(true);
                try { table.prepare(); throw new AssertionError("Ignored preparation cancellation"); }
                catch (CancellationException expected) {}
            }
            check(budget.reservedBytes() == 0, "Cancelled construction leaked");
        }
        var budget = budget(1L << 20, () -> false);
        try (var table = CountPackingProjection.create(values, 4096, 3, 0, true, budget)) {
            while (!table.prepare()) {}
            budget.charge(budget.remainingWork() - 1);
            try { table.rejects(new long[] {2048, 2048, 0}); throw new AssertionError("Unpaid projection result"); }
            catch (PlanningBudget.Exhausted expected) {}
        }
        check(budget.reservedBytes() == 0, "Exhausted query leaked");
    }

    private static long[] slacks(long[][] a, long[] bounds, long undecided, long selected) {
        var result = bounds.clone();
        for (int r = 0; r < a.length; r++) for (int i = 0; i < a[r].length; i++) {
            if ((selected & 1L << i) != 0) result[r] -= a[r][i];
            else if ((undecided & 1L << i) != 0) result[r] -= Math.min(0, a[r][i]);
        }
        return result;
    }
    private static boolean valid(long[][] a, long[] bounds, long assignment) {
        for (int r = 0; r < a.length; r++) {
            long sum = 0;
            for (int i = 0; i < a[r].length; i++) if ((assignment & 1L << i) != 0) sum += a[r][i];
            if (sum > bounds[r]) return false;
        }
        return true;
    }
    private static PlanningBudget budget(long bytes, java.util.function.BooleanSupplier stop) {
        return new PlanningBudget(0, 100_000_000, bytes, stop, System::nanoTime);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
