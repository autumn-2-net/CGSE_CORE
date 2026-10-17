// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Saving a derivation is optional; applying it to a current order is solver work. */
public final class CountRowRetentionBudgetTest {
    private static final List<GraphRecipe<String>> RECIPES = List.of(
            new GraphRecipe<>("a", "a", List.of(new GraphRecipe.Slot<>("ore", 2), new GraphRecipe.Slot<>("dust", 9)), Map.of("out", 2L)),
            new GraphRecipe<>("b", "b", List.of(new GraphRecipe.Slot<>("ore", 6), new GraphRecipe.Slot<>("dust", 3)), Map.of("out", 2L)));

    public static void main(String[] args) throws Exception {
        var owner = budget();
        try (var model = RecipeCountModel.forShell(RECIPES, Map.of("out", BigInteger.ONE),
                Map.of("ore", 11L, "dust", 17L), Set.of(), owner)) {
            independentAccounts(model);
            configuredRetention(model);
            exhaustedAccounts(model);
            interruptions(model);
            concurrentPreparation(model);
            preparationPressure(model);
        }
        check(owner.reservedBytes() == 0, "Fixture leaked");
        System.out.println("Row retention: independent work/memory, shared optional cap, normalized proofs, concurrent admission and interruption passed");
    }

    private static void independentAccounts(RecipeCountModel<?> model) {
        var cache = new CountRowTemplates();
        var b = budget(); b.threadWork(); b.threadSearchWork();
        b.operation(PlanningBudget.Operation.SCAN, 0); b.compilationScan();
        long nodes = b.nodes(), local = b.threadWork(), search = b.threadSearchWork(), remaining = b.remainingWork();
        long peak = b.peakBytes();
        remember(cache, model, b);
        System.out.println("retention: solver_work=" + (b.nodes() - nodes) + "; cache_work=" + b.cachePreparationWork() +
                "; request_peak_delta=" + (b.peakBytes() - peak) + "; entries=" + cache.cacheMetrics().entries());
        check(cache.cacheMetrics().entries() == 1, "Normalized proof not retained");
        check(b.nodes() == nodes && b.threadWork() == local && b.threadSearchWork() == search && b.remainingWork() == remaining,
                "Saving a template consumed current solver or continuation work");
        check(b.reservedBytes() == 0 && b.peakBytes() == peak, "Saving a template borrowed solver memory");
        check(b.cachePreparationWork() > 0, "Optional proof validation was not accounted");
        var duplicate = new PlanningBudget(0, 1, 1, () -> Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(frame ->
                frame.getClassName().equals(CountRowTemplates.class.getName()) && frame.getMethodName().equals("rebuild")), System::nanoTime);
        remember(cache, model, duplicate);
        check(cache.cacheMetrics().entries() == 1 && duplicate.cachePreparationWork() > 0, "Duplicate changed retained derivations");
        b.operations(PlanningBudget.Operation.SCAN, 0, 3);
        check(b.searchWork() == 1 && b.compilationWork() == 1, "Cache cost contaminated fractional clocks");
        long before = b.searchWork(), cacheWork = b.cachePreparationWork();
        var journal = new CountProof.Journal(1L << 20); b.proofJournal(journal);
        var rows = cache.reuse(model, b);
        check(rows.size() == 1 && rows.get(0).row().equals(consequence()), "Retained proof changed its integer floors");
        check(b.searchWork() > before && b.cachePreparationWork() == cacheWork && b.peakBytes() > peak,
                "Current-order derivation became uncharged cache work");
        check(b.reservedBytes() == 0, "Current-order proof workspace leaked");
    }

    private static void preparationPressure(RecipeCountModel<?> model) throws Exception {
        var cache = new CountRowTemplates(); remember(cache, model, budget());
        long resident = cache.cacheMetrics().estimatedBytes();
        var arrived = new CountDownLatch(16); var release = new CountDownLatch(1); var count = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(16); var jobs = new ArrayList<Future<?>>();
        try {
            for (int t = 0; t < 16; t++) jobs.add(pool.submit(() -> {
                var entered = new AtomicBoolean();
                var b = new PlanningBudget(0, 1, 1, () -> {
                    if (entered.compareAndSet(false, true)) {
                        count.incrementAndGet(); arrived.countDown();
                        try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Pressure gate timed out"); }
                        catch (InterruptedException e) { throw new AssertionError(e); }
                    }
                    return false;
                }, System::nanoTime);
                try { remember(cache, model, b); }
                finally { if (!entered.get()) arrived.countDown(); }
                check(b.searchWork() == 0 && b.peakBytes() == 0, "Pressure borrowed request accounts");
            }));
            check(arrived.await(5, TimeUnit.SECONDS), "Cache admission waited for a competing request");
            check(count.get() > 0 && count.get() < 16, "Concurrent preparation was not bounded");
            check(cache.cacheMetrics().estimatedBytes() > resident && cache.cacheMetrics().estimatedBytes() <= resident + (3L << 20),
                    "Concurrent workspace exceeded independent cap");
            check(cache.reuse(model, budget()).get(0).row().equals(consequence()), "Preparation pressure blocked ordinary reuse");
            release.countDown(); for (var job : jobs) job.get(10, TimeUnit.SECONDS);
            check(cache.cacheMetrics().estimatedBytes() == resident && cache.cacheMetrics().entries() == 1,
                    "Pressure left a reservation or duplicate entry");
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private static void exhaustedAccounts(RecipeCountModel<?> model) {
        var full = new PlanningBudget(0, 1, 1, () -> false, System::nanoTime);
        full.reserve(1); full.check();
        var cache = new CountRowTemplates(); remember(cache, model, full);
        check(cache.cacheMetrics().entries() == 1, "Full solver accounts blocked independent template saving");
        check(full.searchWork() == 1 && full.remainingWork() == 0 && full.compilationWork() == 0 &&
                full.reservedBytes() == 1 && full.peakBytes() == 1, "Optional capture charged full solver accounts");
        full.release(1);
        cache = new CountRowTemplates(); var b = budget();
        check(b.tryCacheScan((int) PlanningBudget.CACHE_PREPARATION_WORK * 4), "Cannot arrange optional cap");
        remember(cache, model, b);
        check(cache.cacheMetrics().entries() == 0 && cache.cacheMetrics().estimatedBytes() == 0,
                "Exhausted cache preparation published partial data or leaked");
        b.check(); check(b.searchWork() == 1 && b.failureDetail().isEmpty(), "Cache cap aborted an order");
        remember(cache, model, budget());
        check(cache.cacheMetrics().entries() == 1, "Refusal poisoned a later request");
    }

    private static void configuredRetention(RecipeCountModel<?> model) {
        for (boolean disabled : new boolean[]{false, true}) {
            var b = new PlanningBudget(0, 1, 1, disabled ? 0 : 32768, 1, () -> false, System::nanoTime);
            if (!disabled) check(b.tryCacheCharge(20000), "Could not arrange an enlarged cache account");
            var cache = new CountRowTemplates(); remember(cache, model, b);
            check(cache.cacheMetrics().entries() == (disabled ? 0 : 1),
                    "Row derivation ignored the configured retention allowance");
            check(b.searchWork() == 0 && b.compilationWork() == 0 && b.reservedBytes() == 0,
                    "Configured retention borrowed request work or memory");
            if (!disabled) check(cache.reuse(model, budget()).get(0).row().equals(consequence()), "Retained row changed");
        }
    }

    private static void interruptions(RecipeCountModel<?> model) {
        for (int stop = 1; stop <= 100; stop++) {
            var cache = new CountRowTemplates(); var calls = new AtomicInteger(); int limit = stop;
            var b = new PlanningBudget(0, 1, 1, () -> calls.incrementAndGet() >= limit, System::nanoTime);
            boolean cancelled = false;
            try { remember(cache, model, b); }
            catch (CancellationException expected) { cancelled = true; }
            check(b.searchWork() == 0 && b.peakBytes() == 0, "Interrupted capture used solver budget");
            if (cancelled) check(cache.cacheMetrics().entries() == 0 && cache.cacheMetrics().estimatedBytes() == 0,
                    "Interrupted derivation was published or pinned");
            remember(cache, model, budget());
            check(cache.cacheMetrics().entries() == 1, "Interrupted capture blocked retry");
        }
        var clock = new AtomicLong(); var cache = new CountRowTemplates();
        var b = new PlanningBudget(1, 1, 1, () -> false, clock::get);
        b.start(); clock.set(1_000_000);
        try { remember(cache, model, b); throw new AssertionError("Timeout ignored"); }
        catch (PlanningBudget.Exhausted e) { check(e.limit() == PlanningBudget.Limit.TIMEOUT, "Wrong timeout"); }
        check(cache.cacheMetrics().estimatedBytes() == 0 && b.searchWork() == 0, "Timeout leaked preparation");
        remember(cache, model, budget()); check(cache.cacheMetrics().entries() == 1, "Timeout poisoned cache");
    }

    private static void concurrentPreparation(RecipeCountModel<?> model) throws Exception {
        var cache = new CountRowTemplates(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var b = new PlanningBudget(0, 1, 1, () -> {
            entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Gate timed out"); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            return false;
        }, System::nanoTime);
        var pool = Executors.newFixedThreadPool(4);
        try {
            var blocked = pool.submit(() -> remember(cache, model, b));
            check(entered.await(5, TimeUnit.SECONDS), "No cache preparation checkpoint");
            long memory = cache.cacheMetrics().estimatedBytes();
            check(memory > 0 && memory <= (5L << 20) && b.reservedBytes() == 0,
                    "In-flight preparation is not accounted independently");
            var other = pool.submit(() -> remember(cache, model, budget()));
            other.get(5, TimeUnit.SECONDS);
            release.countDown(); blocked.get(5, TimeUnit.SECONDS);
            check(cache.cacheMetrics().entries() == 1 && cache.cacheMetrics().estimatedBytes() < memory,
                    "Duplicate publication or preparation reservation leak");

            var shared = budget(); var start = new CountDownLatch(1); var jobs = new ArrayList<Future<?>>();
            for (int t = 0; t < 4; t++) jobs.add(pool.submit(() -> {
                start.await();
                for (int repeat = 0; repeat < 160; repeat++) remember(cache, model, shared);
                return null;
            }));
            start.countDown(); for (var job : jobs) job.get(30, TimeUnit.SECONDS);
            check(shared.cachePreparationWork() <= PlanningBudget.CACHE_PREPARATION_WORK && shared.cachePreparationWork() > 0 &&
                    shared.searchWork() == 0 && shared.peakBytes() == 0, "Concurrent capture multiplied or mixed quotas");
            check(cache.cacheMetrics().entries() == 1 && cache.cacheMetrics().estimatedBytes() < memory,
                    "Concurrent refusal corrupted a complete template");
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private static void remember(CountRowTemplates cache, RecipeCountModel<?> model, PlanningBudget b) {
        var source = List.of(normalize(model, "ore", 2), normalize(model, "dust", 3));
        cache.remember(model, source, new CountLpLearning.Cut(consequence(), Map.of(0, BigInteger.ONE, 1, BigInteger.ONE), BigInteger.TWO), b);
    }
    private static ExactLinearProgram.Constraint normalize(RecipeCountModel<?> model, String key, long divisor) {
        var row = model.constraints.get(model.rowKeys.indexOf(key)); var terms = new TreeMap<Integer, BigInteger>();
        row.terms().forEach((id, value) -> terms.put(id, value.divide(BigInteger.valueOf(divisor))));
        return new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(Math.floorDiv(row.upper().longValueExact(), divisor)));
    }
    private static ExactLinearProgram.Constraint consequence() {
        return new ExactLinearProgram.Constraint(Map.of(0, BigInteger.TWO, 1, BigInteger.TWO), BigInteger.valueOf(5));
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 4_000_000, 128L << 20, () -> false, System::nanoTime); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
