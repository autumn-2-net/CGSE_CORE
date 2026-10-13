// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exact fractional accounting, large caller counts, and shared-request charging. */
public final class PlanningBudgetBatchTest {
    public static void main(String[] args) throws Exception {
        equivalent();
        boundaries();
        concurrent();
        System.out.println("Batched budget: exact ticks, fractional handoffs, int-sized counts, cancellation and shared caps passed");
    }

    private static void equivalent() {
        var individual = budget(Long.MAX_VALUE);
        var batched = budget(Long.MAX_VALUE);
        individual.threadWork(); batched.threadWork();
        var random = new Random(833729);
        long ticks = 0;
        for (int trial = 0; trial < 2048; trial++) {
            var operation = PlanningBudget.Operation.values()[random.nextInt(3)];
            int bits = random.nextInt(8192) - 64, count = random.nextInt(65);
            long expected = 0;
            for (int i = 0; i < count; i++) expected += individual.operation(operation, bits);
            check(batched.operations(operation, bits, count) == expected, "Different operation weight");
            ticks += expected;
            check(individual.nodes() == PlanningBudget.units(ticks) && batched.nodes() == individual.nodes(), "Different request charge");
            check(batched.threadWork() == individual.threadWork(), "Lost fractional worker charge");
            check(batched.remainingWork() == individual.remainingWork(), "Different remaining allowance");
        }
    }

    private static void boundaries() {
        for (var operation : PlanningBudget.Operation.values()) {
            var budget = budget(Long.MAX_VALUE);
            budget.threadWork();
            long expected = (long) Integer.MAX_VALUE * operation.ticks *
                    (operation == PlanningBudget.Operation.SCAN ? 1 : 32);
            check(budget.operations(operation, Integer.MAX_VALUE, Integer.MAX_VALUE) == expected, "Int count overflow");
            check(budget.nodes() == PlanningBudget.units(expected) && budget.threadWork() == budget.nodes(), "Large count accounting");
            long before = budget.nodes();
            try { budget.operations(operation, 64, -1); throw new AssertionError("Negative count accepted"); }
            catch (IllegalArgumentException expectedFailure) {}
            check(budget.nodes() == before, "Invalid count charged");
        }
        var budget = budget(1);
        budget.operations(PlanningBudget.Operation.SCAN, 0, 4);
        check(budget.remainingWork() == 0, "Fractional cap not consumed");
        try { budget.operations(PlanningBudget.Operation.SCAN, 0, 1); throw new AssertionError("Cap bypassed"); }
        catch (PlanningBudget.Exhausted expected) { check(expected.limit() == PlanningBudget.Limit.SEARCH_LIMIT, "Wrong cap"); }

        var overflow = budget(Long.MAX_VALUE);
        overflow.charge(Long.MAX_VALUE / PlanningBudget.WORK_SCALE);
        for (int i = 0; i < 2; i++) {
            try { overflow.operations(PlanningBudget.Operation.INTEGER, 64, Integer.MAX_VALUE); throw new AssertionError("Saturation reopened"); }
            catch (PlanningBudget.Exhausted expected) {}
            check(overflow.nodes() == PlanningBudget.units(Long.MAX_VALUE) && overflow.remainingWork() == 0, "Not sticky");
        }
        var stop = new AtomicBoolean();
        var cancelled = new PlanningBudget(0, 100, 1L << 20, stop::get, System::nanoTime);
        stop.set(true);
        try { cancelled.operations(PlanningBudget.Operation.SCAN, 0, 0); throw new AssertionError("Empty batch ignored cancellation"); }
        catch (CancellationException expected) {}
        check(cancelled.nodes() == 0, "Cancelled batch charged");
    }

    private static void concurrent() throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        try {
            var budget = budget(10_000_000);
            var jobs = new java.util.ArrayList<Future<Long>>();
            for (int t = 0; t < 4; t++) jobs.add(executor.submit(() -> {
                long before = budget.threadWork();
                for (int i = 0; i < 1000; i++) budget.operations(PlanningBudget.Operation.SCAN, 0, 7);
                return budget.threadWork() - before;
            }));
            long total = 0;
            for (var job : jobs) { long work = job.get(); check(work == 1750, "Foreign worker charge"); total += work; }
            check(budget.nodes() == total, "Shared batch charge lost");
        } finally { executor.shutdownNow(); }
    }

    private static PlanningBudget budget(long work) {
        return new PlanningBudget(0, work, 1L << 20, () -> false, System::nanoTime);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
