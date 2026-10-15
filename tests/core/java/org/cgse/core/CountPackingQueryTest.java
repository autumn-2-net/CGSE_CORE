// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.concurrent.*;

/** A remembered rejection must be checked anew when the query changes. */
public final class CountPackingQueryTest {
    public static void main(String[] args) throws Exception {
        var rows = new long[35][64];
        for (int r = 0; r < rows.length; r++)
            for (int i = 52; i < 64; i++) rows[r][i] = (r + 1L) * (i - 51);
        var budget = new PlanningBudget(0, 100_000_000, 8L << 20, () -> false, System::nanoTime);
        var worker = Executors.newSingleThreadExecutor();
        long mask = -1L << 52;
        try (var table = CountPackingTail.create(rows, 52, budget)) {
            check(table != null, "Table admission");
            while (!table.prepare()) {}
            long storage = budget.reservedBytes();
            var slacks = new long[35]; slacks[0] = -1;
            table.begin(mask, 0, slacks);
            long before = budget.nodes();
            while (!table.step()) {}
            long cold = budget.nodes() - before;
            check(!table.feasible(), "Negative capacity over nonnegative coordinates");
            table.begin(mask, 0, slacks);
            before = budget.nodes();
            while (!worker.submit(table::step).get()) {}
            long hot = budget.nodes() - before;
            check(!table.feasible() && cold > 3 * hot + 4, "Repeated query rescanned every harmless row");
            for (int r = 0; r < slacks.length; r++) {
                java.util.Arrays.fill(slacks, 100_000);
                slacks[r] = -1;
                table.begin(mask, 0, slacks);
                while (!worker.submit(table::step).get()) {}
                check(!table.feasible(), "Missed a changed separating row");
                slacks[r] = 100_000;
                table.begin(mask, 0, slacks);
                while (!table.step()) {}
                check(table.feasible(), "Reused a stale failure after relaxing the RHS");
            }
            for (long wanted : new long[] {0, 1, 7, 511, 2048, 4095, 0}) {
                java.util.Arrays.fill(slacks, 0);
                table.begin(0, wanted << 52, slacks);
                while (!worker.submit(table::step).get()) {}
                check(table.feasible() && table.assignment() == wanted << 52,
                        "Remembered row discarded a changed fixed assignment");
            }
            check(budget.reservedBytes() == storage, "Queries allocated another index");
        } finally { worker.shutdownNow(); }
        check(budget.reservedBytes() == 0, "Query index ownership leaked");
        compactBoundary();
        accounting();
        sharedAccounting();
        inheritedBounds();
        inheritedAccounting();
        System.out.println("Exact queries, inherited bounds, changed RHS, bit 64, fixed domains, worker moves, bounded storage and shared scan accounting passed");
    }

    /** Every accepted leaf is checked independently against every query row.
     * Changed bounds restart at the root; a previous parent pass is not reusable. */
    private static void inheritedBounds() {
        var random = new java.util.Random(161016);
        var budget = new PlanningBudget(0, 100_000_000, 1L << 20, () -> false, System::nanoTime);
        for (int dimensions : new int[] {1, 2, 7, 32, 35}) for (int trial = 0; trial < 64; trial++) {
            var values = new long[32 * dimensions];
            var rows = new long[32]; rows[1] = (1L << dimensions) - 1;
            for (int leaf = 16; leaf < 32; leaf++) for (int r = 0; r < dimensions; r++) {
                int kind = random.nextInt(16);
                values[leaf * dimensions + r] = kind == 0 ? Long.MIN_VALUE : kind == 1 ? Long.MAX_VALUE :
                        kind == 2 ? (1L << 53) + leaf : kind == 3 ? -(1L << 53) - leaf : random.nextInt(9) - 4;
            }
            for (int node = 15; node > 0; node--)
                CountPackingQuery.join(values, rows, dimensions, node, 2 * node, 2 * node + 1, budget);
            var hints = new int[32]; var query = new long[dimensions]; var stack = new int[32];
            for (int turn = 0; turn < 32; turn++) {
                for (int r = 0; r < dimensions; r++) query[r] = turn % 4 == 0 ? Long.MAX_VALUE :
                        turn % 4 == 1 ? Long.MIN_VALUE : values[(16 + turn % 16) * dimensions + r];
                int expected = 0;
                for (int leaf = 16; leaf < 32; leaf++) {
                    boolean fits = true;
                    for (int r = 0; r < dimensions; r++) fits &= values[leaf * dimensions + r] <= query[r];
                    if (fits) expected |= 1 << (leaf - 16);
                }
                int found = 0, pending = 1; stack[0] = 1;
                while (pending > 0) {
                    int node = stack[--pending];
                    int rejection = CountPackingQuery.reject(values, node * dimensions, query, hints[node], rows[node], budget);
                    if (rejection >= 0) { hints[node] = rejection; continue; }
                    if (node >= 16) found |= 1 << (node - 16);
                    else { stack[pending++] = 2 * node + 1; stack[pending++] = 2 * node; }
                }
                check(found == expected, "Inherited row comparison lost a leaf or accepted an invalid one");
            }
        }
    }

    private static void inheritedAccounting() {
        var actual = new PlanningBudget(0, 1_000_000, 1L << 20, () -> false, System::nanoTime);
        var reference = new PlanningBudget(0, 1_000_000, 1L << 20, () -> false, System::nanoTime);
        actual.threadWork(); reference.threadWork();
        var query = new long[35]; var values = new long[42];
        for (long rows : new long[] {0, 1, 1L << 34, 0x555555555L, (1L << 35) - 1})
            for (int first = 0; first < 35; first++) {
                reference.operations(PlanningBudget.Operation.SCAN, 0, Long.bitCount(rows));
                check(CountPackingQuery.reject(values, 7, query, first, rows, actual) == -1, "Equal inherited bounds rejected");
                check(actual.nodes() == reference.nodes() && actual.threadSearchWork() == reference.threadSearchWork(),
                        "Inherited comparison charged skipped coordinates or lost fractional work");
            }
        check(actual.cachePreparationWork() == 0, "Current-query deductions used the retention account");
        var limited = new PlanningBudget(0, 1, 1L << 20, () -> false, System::nanoTime);
        try {
            CountPackingQuery.reject(values, 7, query, 0, (1L << 35) - 1, limited);
            throw new AssertionError("Inherited query published after exhaustion");
        } catch (PlanningBudget.Exhausted expected) {}
        for (long rows : new long[] {0, (1L << 35) - 1}) {
            try {
                CountPackingQuery.reject(values, 7, query, 0, rows, limited);
                throw new AssertionError("Empty comparison reopened an exhausted account");
            } catch (PlanningBudget.Exhausted expected) {}
            var cancelled = new PlanningBudget(0, 1_000_000, 1L << 20, () -> true, System::nanoTime);
            try {
                CountPackingQuery.reject(values, 7, query, 0, rows, cancelled);
                throw new AssertionError("Inherited query ignored cancellation");
            } catch (CancellationException expected) {}
        }
    }

    /** Comparison values deliberately cross floating-point and signed bounds.
     * Batching must neither skip charged work nor publish a result on exhaustion. */
    private static void accounting() {
        var actual = new PlanningBudget(0, 1_000_000, 1L << 20, () -> false, System::nanoTime);
        var reference = new PlanningBudget(0, 1_000_000, 1L << 20, () -> false, System::nanoTime);
        for (int rows : new int[] {0, 36}) {
            try {
                CountPackingTail.create(new long[rows][12], 0, actual);
                throw new AssertionError("Unbounded packing query admitted");
            } catch (IllegalArgumentException expected) {}
        }
        check(actual.nodes() == 0 && actual.reservedBytes() == 0, "Rejected shape consumed a budget");
        actual.threadWork(); reference.threadWork();
        for (boolean compact : new boolean[] {true, false}) {
            for (int size : new int[] {1, 2, 7, 32, 35}) {
                var query = new long[size];
                var values = new long[size + 11];
                var points = new CountPackingValues(values.length, compact);
                for (int r = 0; r < size; r++) query[r] = compact ? Integer.MIN_VALUE + r : Long.MIN_VALUE + r;
                for (int separator = -1; separator < size; separator++) {
                    for (int r = 0; r < size; r++) {
                        values[r + 7] = separator == r ? (compact ? Integer.MAX_VALUE : Long.MAX_VALUE) : query[r];
                        points.set(r + 7, values[r + 7]);
                    }
                    for (int first = 0; first < size; first++) {
                        // All rows except the designated separator satisfy equality.
                        int visits = separator < 0 ? size : separator == first ? 1 :
                                1 + size - separator - (first > separator ? 1 : 0);
                        for (int i = 0; i < 2 * visits; i++) reference.operation(PlanningBudget.Operation.SCAN, 0);
                        check(CountPackingQuery.reject(values, 7, query, first, actual) == separator, "Wrong box separator");
                        check(CountPackingQuery.reject(points, 7, query, first, actual) == separator, "Wrong point separator");
                        check(actual.nodes() == reference.nodes() && actual.threadWork() == reference.threadWork() &&
                                actual.threadSearchWork() == reference.threadSearchWork(), "Lost exact or fractional scan work");
                        check(actual.cachePreparationWork() == 0, "Query work charged to retention");
                    }
                }
            }
        }
        var query = new long[35];
        var values = new CountPackingValues(35, true);
        for (boolean point : new boolean[] {false, true}) {
            var limited = new PlanningBudget(0, 1, 1L << 20, () -> false, System::nanoTime);
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    if (point) CountPackingQuery.reject(values, 0, query, 0, limited);
                    else CountPackingQuery.reject(query, 0, query, 0, limited);
                    throw new AssertionError("Published a result after budget exhaustion");
                } catch (PlanningBudget.Exhausted expected) {
                    check(expected.limit() == PlanningBudget.Limit.SEARCH_LIMIT, "Wrong exhaustion reason");
                }
            }
            var cancelled = new PlanningBudget(0, 1_000_000, 1L << 20, () -> true, System::nanoTime);
            try {
                if (point) CountPackingQuery.reject(values, 0, query, 0, cancelled);
                else CountPackingQuery.reject(query, 0, query, 0, cancelled);
                throw new AssertionError("Published a result after cancellation");
            } catch (CancellationException expected) {}
        }
    }

    private static void sharedAccounting() throws Exception {
        var budget = new PlanningBudget(0, 1_000_000, 1L << 20, () -> false, System::nanoTime);
        var workers = Executors.newFixedThreadPool(4);
        var tasks = new java.util.ArrayList<Future<Long>>();
        try {
            for (int i = 0; i < 4; i++) tasks.add(workers.submit(() -> {
                var query = new long[35];
                long before = budget.threadSearchWork();
                for (int n = 0; n < 1000; n++) check(CountPackingQuery.reject(query, 0, query, n % 35, budget) == -1,
                        "Shared budget changed query result");
                return budget.threadSearchWork() - before;
            }));
            for (var task : tasks) check(task.get() == 8750, "Another worker charged this continuation");
            check(budget.nodes() == 35000, "Shared query accounting lost work");
        } finally { workers.shutdownNow(); }
    }

    private static void compactBoundary() {
        long threshold = Integer.MAX_VALUE / 4095L;
        long narrow = 0, wide = 0;
        for (long scale : new long[] {1, threshold, threshold + 1, 1L << 43}) {
            var rows = new long[2][64];
            for (int i = 0; i < 12; i++) {
                rows[0][52 + i] = (1L << i) * scale;
                rows[1][52 + i] = -rows[0][52 + i];
            }
            var budget = new PlanningBudget(0, 10_000_000, 8L << 20, () -> false, System::nanoTime);
            try (var table = CountPackingTail.create(rows, 52, budget)) {
                check(table != null, "Boundary table admission");
                if (scale == threshold) narrow = budget.reservedBytes();
                if (scale == threshold + 1) wide = budget.reservedBytes();
                while (!table.prepare()) {}
                for (long wanted : new long[] {0, 1, 7, 8, 127, 1023, 2047, 2048, 4094, 4095}) {
                    table.begin(-1L << 52, 0, new long[] {wanted * scale, (4095 - wanted) * scale});
                    while (!table.step()) {}
                    check(table.feasible() && table.assignment() == wanted << 52,
                            "Truncated a signed exact coordinate at the compact boundary");
                }
            }
            check(budget.reservedBytes() == 0, "Boundary table leaked");
        }
        check(narrow < wide, "Compact coordinates did not reduce their reservation");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
