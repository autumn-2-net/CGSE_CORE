// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Latches expose completion-before-close without depending on a timing race or GC. */
public final class PlanningCompletionTest {
    public static void main(String[] args) throws Exception {
        for (int mode = 0; mode < 4; mode++) terminal(mode);
        cleanupFailure();
        callbackCanSubmitNextRequest();
        System.out.println("Planning completion: successful, retained-limit, failed and rejected-limit results publish after owned cleanup; cleanup failures settle the future");
    }

    private static void terminal(int mode) throws Exception {
        var budget = new PlanningBudget(0, 1000, () -> false);
        budget.enableMetrics();
        budget.reserve(4096);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closes = new AtomicInteger();
        try (var scheduler = new PlanningScheduler(1, 2, 1, 1_000_000)) {
            var work = new PlanningScheduler.Work<Integer>() {
                public boolean advance(PlanningScheduler.Slice slice) {
                    if (mode == 1 || mode == 3) throw new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT);
                    if (mode == 2) throw new IllegalArgumentException("expected failure");
                    return true;
                }
                public Integer result() { return 42; }
                public Integer limited(PlanningBudget.Exhausted limit) { if (mode == 3) throw limit; return 43; }
                public void close() {
                    closes.incrementAndGet();
                    entered.countDown();
                    try {
                        if (!release.await(30, TimeUnit.SECONDS)) throw new AssertionError("Cleanup release not signalled");
                    } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                    budget.release(4096);
                }
            };
            var future = scheduler.submit(work, budget);
            try {
                check(entered.await(30, TimeUnit.SECONDS), "Cleanup did not start");
                check(!future.isDone(), "Result was published before cleanup finished, mode=" + mode);
            } finally { release.countDown(); }
            if (mode < 2) check(future.get(30, TimeUnit.SECONDS) == 42 + mode, "Result changed during cleanup");
            else {
                try { future.get(30, TimeUnit.SECONDS); throw new AssertionError("Failure was lost"); }
                catch (ExecutionException expected) {
                    check(mode == 2 ? expected.getCause() instanceof IllegalArgumentException :
                            expected.getCause() instanceof PlanningBudget.Exhausted, "Wrong terminal cause");
                }
            }
            check(budget.reservedBytes() == 0 && closes.get() == 1, "Published result retains resources/double closes");
        } finally { release.countDown(); }
    }

    private static void cleanupFailure() throws Exception {
        try (var scheduler = new PlanningScheduler(1, 2, 1, 1_000_000)) {
            var future = scheduler.submit(new PlanningScheduler.Work<Integer>() {
                public boolean advance(PlanningScheduler.Slice slice) { return true; }
                public Integer result() { return 7; }
                public void close() { throw new IllegalStateException("cleanup failed"); }
            }, new PlanningBudget(0, 1000, () -> false));
            try { future.get(30, TimeUnit.SECONDS); throw new AssertionError("Cleanup failure was discarded"); }
            catch (ExecutionException expected) {
                check(expected.getCause() instanceof IllegalStateException, "Cleanup failure stranded/replaced");
            }
        }
    }

    private static void callbackCanSubmitNextRequest() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var scheduler = new PlanningScheduler(1, 1, 1, 1_000_000)) {
            var first = scheduler.submit(new PlanningScheduler.Work<Integer>() {
                public boolean advance(PlanningScheduler.Slice slice) {
                    entered.countDown();
                    try { if (!release.await(30, TimeUnit.SECONDS)) throw new AssertionError("First request not released"); }
                    catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                    return true;
                }
                public Integer result() { return 1; }
            }, new PlanningBudget(0, 1000, () -> false));
            check(entered.await(30, TimeUnit.SECONDS), "First request did not start");
            // CompletableFuture invokes dependents in stack order. Admission
            // release in an earlier whenComplete can run AFTER this callback.
            var chained = first.thenCompose(value -> scheduler.submit(new PlanningScheduler.Work<Integer>() {
                public boolean advance(PlanningScheduler.Slice slice) { return true; }
                public Integer result() { return value + 1; }
            }, new PlanningBudget(0, 1000, () -> false)));
            release.countDown();
            check(chained.get(30, TimeUnit.SECONDS) == 2, "Completion callback saw a stale admission slot");
        } finally { release.countDown(); }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
