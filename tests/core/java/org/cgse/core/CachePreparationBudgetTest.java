// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Retention has an independent, cumulative quota and compiler-owned preparation memory. */
public final class CachePreparationBudgetTest {
    private static final List<String> KEYS = List.of("A", "B");
    private static final List<BigInteger[]> BASIS = Collections.singletonList(new BigInteger[]{BigInteger.ONE, BigInteger.ONE});

    public static void main(String[] args) throws Exception {
        accounts();
        capture();
        concurrent();
        preparationLifetime();
        System.out.println("Cache preparation: independent work/memory, fractional solver clocks, shared cap, cancellation/timeout and concurrent admission passed");
    }

    private static void accounts() {
        var b = budget(); b.threadWork(); b.threadSearchWork();
        b.operation(PlanningBudget.Operation.SCAN, 0); b.compilationScan();
        long nodes = b.nodes(), local = b.threadWork(), search = b.threadSearchWork(), remaining = b.remainingWork();
        for (int i = 0; i < PlanningBudget.CACHE_PREPARATION_WORK * 4; i++) check(b.tryCacheScan(1), "Premature retention cap");
        check(!b.tryCacheScan(1) && !b.tryCacheScan(Integer.MAX_VALUE), "Retention cap overflow or reset");
        check(b.cachePreparationWork() == PlanningBudget.CACHE_PREPARATION_WORK, "Incorrect fractional retention charge");
        check(b.nodes() == nodes && b.threadWork() == local && b.threadSearchWork() == search && b.remainingWork() == remaining,
                "Retention stole search, compilation or continuation work");
        check(b.searchWork() == 1 && b.compilationWork() == 1, "Retention entered a solver account");
        b.operations(PlanningBudget.Operation.SCAN, 0, 3);
        check(b.searchWork() == 1, "Retention contaminated solver fractional ticks");
        try { b.tryCacheScan(-1); throw new AssertionError("Negative scan accepted"); }
        catch (IllegalArgumentException expected) {}
    }

    private static void capture() {
        var compiler = prepared();
        var b = new PlanningBudget(0, 1, 1, () -> false, System::nanoTime);
        b.reserve(1); b.check();
        compiler.rememberConservationBasis("A", Set.of(), KEYS, BASIS, b);
        check(compiler.conservationBasis("A", Set.of()) != null, "Full solver accounts blocked independent retention");
        check(b.searchWork() == 1 && b.remainingWork() == 0 && b.compilationWork() == 0 && b.cachePreparationWork() > 0,
                "Capture borrowed solver work");
        check(b.reservedBytes() == 1 && b.peakBytes() == 1, "Capture borrowed solver memory"); b.release(1);
        compiler = prepared(); b = budget();
        check(b.tryCacheScan((int) PlanningBudget.CACHE_PREPARATION_WORK * 4), "Cannot consume retention quota");
        compiler.rememberConservationBasis("A", Set.of(), KEYS, BASIS, b);
        check(compiler.conservationBasis("A", Set.of()) == null, "Exhausted retention published incomplete data");
        b.check(); check(b.searchWork() == 1 && b.failureDetail().isEmpty(), "Retention exhaustion aborted solver");
        compiler.rememberConservationBasis("A", Set.of(), KEYS, BASIS, budget());
        check(compiler.conservationBasis("A", Set.of()) != null, "Retention exhaustion poisoned later requests");
    }

    private static void concurrent() throws Exception {
        var b = budget(); var pool = Executors.newFixedThreadPool(4); var start = new CountDownLatch(1);
        try {
            var jobs = new ArrayList<Future<Integer>>();
            for (int t = 0; t < 4; t++) jobs.add(pool.submit(() -> {
                start.await(); long before = b.threadWork(); int n = 0;
                while (b.tryCacheScan(1)) n++;
                check(b.threadWork() == before, "Retention changed worker continuation"); return n;
            }));
            start.countDown(); int total = 0; for (var job : jobs) total += job.get(30, TimeUnit.SECONDS);
            check(total == PlanningBudget.CACHE_PREPARATION_WORK * 4 && b.nodes() == 0, "Workers multiplied retention allowance");
        } finally { pool.shutdownNow(); }
    }

    private static void preparationLifetime() throws Exception {
        var compiler = prepared(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        var b = new PlanningBudget(0, 1, 1, () -> {
            entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Preparation did not resume"); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            return false;
        }, System::nanoTime);
        try {
            var job = pool.submit(() -> compiler.rememberConservationBasis("A", Set.of(), KEYS, BASIS, b));
            check(entered.await(10, TimeUnit.SECONDS), "No preparation checkpoint");
            var stats = compiler.cacheMetrics().caches();
            check(stats.get("conservation_preparation").estimatedBytes() == ConservationKernel.MAX_BYTES,
                    "In-flight clone not charged to cache");
            check(stats.get("conservation_preparation").estimatedBytes() + stats.get("conservation_basis").estimatedBytes() <= (1L << 20),
                    "Resident and preparation memory exceeded cap");
            var other = budget(); compiler.rememberConservationBasis("A", Set.of(), KEYS, BASIS, other);
            check(other.cachePreparationWork() == 0, "Parallel borrower duplicated an in-flight preparation");
            release.countDown(); job.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); }
        check(compiler.cacheMetrics().caches().get("conservation_preparation").entries() == 0 && b.peakBytes() == 0,
                "Completed capture retained preparation reservation");

        for (boolean timeout : new boolean[]{false, true}) {
            var interruptedCompiler = prepared(); var checks = new AtomicInteger(); var clock = new AtomicLong();
            var interrupted = new PlanningBudget(timeout ? 1 : 0, 1, 1,
                    () -> !timeout && checks.incrementAndGet() == 2, clock::get);
            interrupted.start(); if (timeout) clock.set(1_000_000);
            try { interruptedCompiler.rememberConservationBasis("A", Set.of(), KEYS, BASIS, interrupted); throw new AssertionError("Interruption ignored"); }
            catch (CancellationException expected) { check(!timeout, "Wrong interruption"); }
            catch (PlanningBudget.Exhausted expected) { check(timeout && expected.limit() == PlanningBudget.Limit.TIMEOUT, "Wrong timeout"); }
            check(interruptedCompiler.cacheMetrics().caches().get("conservation_preparation").entries() == 0 &&
                    interruptedCompiler.conservationBasis("A", Set.of()) == null && interrupted.searchWork() == 0, "Interrupted preparation leaked or published");
            interruptedCompiler.rememberConservationBasis("A", Set.of(), KEYS, BASIS, budget());
            check(interruptedCompiler.conservationBasis("A", Set.of()) != null, "Interruption poisoned future preparation");
        }
    }

    private static GraphCompiler<String> prepared() {
        var compiler = new GraphCompiler<String>(List.of());
        compiler.conservationBasis("A", Set.of()); compiler.conservationBasis("A", Set.of()); return compiler;
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 1_000_000, 1L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
