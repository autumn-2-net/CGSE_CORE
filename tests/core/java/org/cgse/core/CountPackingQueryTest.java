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
        System.out.println("Repeated exact queries, changed RHS, bit 64, fixed domains, worker moves and bounded storage passed");
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
