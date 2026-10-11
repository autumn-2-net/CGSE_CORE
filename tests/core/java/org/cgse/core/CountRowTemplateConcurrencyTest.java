// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Exact proof work must not serialize unrelated requests sharing a catalog. */
public final class CountRowTemplateConcurrencyTest {
    private static final List<GraphRecipe<String>> RECIPES = List.of(
            new GraphRecipe<>("a", "a", List.of(new GraphRecipe.Slot<>("ore", 2)), Map.of("out", 2L)),
            new GraphRecipe<>("b", "b", List.of(new GraphRecipe.Slot<>("ore", 4)), Map.of("out", 2L)));
    private static final AtomicInteger assertions = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--benchmark-only")) { benchmark(); return; }
        boolean baseline = Arrays.asList(args).contains("--baseline");
        for (boolean remember : new boolean[] {false, true}) isolatedExactWork(remember, baseline);
        if (!baseline) {
            cancellationAndMemory();
            concurrentPublication();
        }
        benchmark();
        System.out.println("Row template concurrency: " + assertions + " assertions; " + (baseline ? "baseline measured" : "PASS"));
    }

    private static void isolatedExactWork(boolean remember, boolean baseline) throws Exception {
        var cache = new CountRowTemplates();
        var initial = budget(() -> false);
        try (var model = model(initial, 11)) { cache.remember(model, model.constraints, cut(model, 1), initial); }
        var gate = new Gate();
        var first = budget(gate);
        var second = budget(() -> false);
        var pool = Executors.newFixedThreadPool(2);
        try (var left = model(first, 13); var right = model(second, 17)) {
            long modelBytes = first.reservedBytes(), templateBytes = cache.cacheMetrics().estimatedBytes();
            gate.armed = true;
            Future<?> running = pool.submit(() -> {
                if (remember) cache.remember(left, left.constraints, cut(left, 2), first);
                else check(cache.reuse(left, first).get(0).row().equals(cut(left, 1).row()), "Snapshot changed during eviction");
            });
            check(gate.entered.await(5, TimeUnit.SECONDS), "Did not enter exact template work");
            if (!baseline && !remember)
                check(gate.reserved >= modelBytes + 262144 + templateBytes, "Evictable snapshot was not charged to request memory");
            long begin = System.nanoTime();
            Future<?> independent = pool.submit(() -> {
                long entries = cache.cacheMetrics().entries();
                check(entries >= 1 && entries <= 2, "Initial cache corrupted");
                check(cache.reuse(right, second).stream().anyMatch(row -> row.row().equals(cut(right, 1).row())), "Concurrent RHS crossed requests");
                // Evict the paused request's snapshot. It must remain usable and
                // must not republish an evicted entry or corrupt cache counters.
                for (int weight = 3; weight <= 37; weight++) cache.remember(right, right.constraints, cut(right, weight), second);
            });
            boolean blocked = false;
            try { independent.get(1, TimeUnit.SECONDS); }
            catch (TimeoutException expected) { blocked = true; }
            finally { gate.release.countDown(); }
            independent.get(5, TimeUnit.SECONDS);
            running.get(5, TimeUnit.SECONDS);
            System.out.println((remember ? "remember" : "reuse") + " exact gate: follower_blocked=" + blocked +
                    "; follower_ms=" + (System.nanoTime() - begin) / 1_000_000.0 + "; pinned_bytes=" + gate.reserved);
            check(blocked == baseline, baseline ? "Original long critical section not reproduced" : "Exact work still holds catalog monitor");
            check(cache.cacheMetrics().entries() == 32, "Concurrent eviction exceeded capacity");
            check(cache.cacheMetrics().estimatedBytes() > 0 && cache.cacheMetrics().estimatedBytes() <= (2L << 20), "Resident accounting invalid");
        } finally { gate.release.countDown(); pool.shutdownNow(); }
        check(first.reservedBytes() == 0 && second.reservedBytes() == 0 && initial.reservedBytes() == 0, "Concurrent scratch leak");
    }

    private static void cancellationAndMemory() {
        var cache = new CountRowTemplates();
        var budget = budget(() -> false);
        try (var model = model(budget, 11)) {
            cache.remember(model, model.constraints, cut(model, 1), budget);
            for (long memory : new long[] {1, 1023, 1024, 262144, 2L << 20, 8L << 20}) {
                var limited = new PlanningBudget(0, 4_000_000, memory, () -> false, System::nanoTime);
                var rows = cache.reuse(model, limited);
                if (memory >= (8L << 20)) check(rows.size() == 1, "Ordinary memory lost template opportunity");
                check(limited.reservedBytes() == 0, "Snapshot admission leaked memory");
            }
            AtomicBoolean cancel = new AtomicBoolean();
            var cancelled = budget(cancel::get);
            cancel.set(true);
            try { cache.reuse(model, cancelled); throw new AssertionError("Cancellation ignored"); }
            catch (CancellationException expected) { check(cancelled.reservedBytes() == 0, "Cancelled snapshot leak"); }
            var duringProof = budget(() -> Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(frame ->
                    frame.getClassName().equals(CountRowTemplates.class.getName()) && frame.getMethodName().equals("rebuild")));
            long hits = cache.cacheMetrics().hits();
            try { cache.reuse(model, duringProof); throw new AssertionError("Proof cancellation ignored"); }
            catch (CancellationException expected) { check(duringProof.reservedBytes() == 0, "Cancelled proof pinned snapshot"); }
            check(cache.cacheMetrics().hits() == hits, "Unfinished proof was reported as a hit");
            check(cache.cacheMetrics().entries() == 1, "Refused snapshots changed cache");
        }
        check(budget.reservedBytes() == 0, "Fixture leak");
    }

    private static void concurrentPublication() throws Exception {
        var cache = new CountRowTemplates();
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                int stock = worker * 2 + 1;
                futures.add(pool.submit(() -> {
                    var budget = budget(() -> false);
                    try (var model = model(budget, stock)) {
                        for (int repeat = 0; repeat < 32; repeat++) {
                            cache.remember(model, model.constraints, cut(model, 1), budget);
                            var rows = cache.reuse(model, budget);
                            check(rows.size() == 1 && rows.get(0).row().equals(cut(model, 1).row()), "Duplicate publication or stale RHS");
                        }
                    }
                    check(budget.reservedBytes() == 0, "Concurrent publication leak");
                }));
            }
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
            check(cache.cacheMetrics().entries() == 1 && cache.cacheMetrics().evictions() == 0, "Duplicate entries consumed capacity");
            check(cache.cacheMetrics().hits() == 256 && cache.cacheMetrics().misses() == 0, "Concurrent metrics lost updates");
            futures.clear();
            for (int worker = 0; worker < 8; worker++) futures.add(pool.submit(() -> {
                var budget = budget(() -> false);
                var changed = new ArrayList<>(RECIPES);
                changed.add(new GraphRecipe<>("new-producer", "new-producer", List.of(new GraphRecipe.Slot<>("fuel", 1)), Map.of("ore", 2L)));
                try (var model = RecipeCountModel.forShell(changed, Map.of("out", BigInteger.ONE), Map.of("ore", 11L), Set.of(), budget)) {
                    check(cache.reuse(model, budget).isEmpty(), "Concurrent replay ignored complete supply boundary");
                }
                check(budget.reservedBytes() == 0, "Rejected proof workspace leaked");
            }));
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
            check(cache.cacheMetrics().hits() == 256 && cache.cacheMetrics().misses() == 8, "Concurrent misses lost updates");
        } finally { pool.shutdownNow(); }
    }

    private static void benchmark() throws Exception {
        var cache = new CountRowTemplates();
        var owner = budget(() -> false);
        try (var model = model(owner, 11)) {
            for (int weight = 1; weight <= 32; weight++) cache.remember(model, model.constraints, cut(model, weight), owner);
        }
        var pool = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(1);
        List<Future<long[]>> futures = new ArrayList<>();
        try {
            for (int worker = 0; worker < 4; worker++) {
                int stock = worker * 2 + 11;
                futures.add(pool.submit(() -> {
                    var elapsed = new long[100];
                    var budget = budget(() -> false);
                    try (var model = model(budget, stock)) {
                        start.await();
                        for (int i = 0; i < elapsed.length; i++) {
                            long began = System.nanoTime();
                            check(cache.reuse(model, budget).size() == 16, "Concurrent replay failed");
                            elapsed[i] = System.nanoTime() - began;
                        }
                    }
                    check(budget.reservedBytes() == 0, "Benchmark leak");
                    return elapsed;
                }));
            }
            long began = System.nanoTime();
            start.countDown();
            long[] times = new long[400];
            int offset = 0;
            for (var future : futures) { var result = future.get(30, TimeUnit.SECONDS); System.arraycopy(result, 0, times, offset, result.length); offset += result.length; }
            Arrays.sort(times);
            System.out.println("4 workers x 100 replays: elapsed_ms=" + (System.nanoTime() - began) / 1_000_000.0 +
                    "; p50_ms=" + times[199] / 1_000_000.0 + "; p95_ms=" + times[379] / 1_000_000.0 +
                    "; p99_ms=" + times[395] / 1_000_000.0 + "; exact_hits=" + cache.cacheMetrics().hits());
        } finally { pool.shutdownNow(); }
    }

    private static final class Gate implements BooleanSupplier {
        volatile boolean armed;
        volatile long reserved;
        PlanningBudget budget;
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        public boolean getAsBoolean() {
            if (armed) for (var frame : Thread.currentThread().getStackTrace()) {
                if (frame.getClassName().equals(CountRowTemplates.class.getName()) && frame.getMethodName().equals("signature")) {
                    armed = false;
                    reserved = budget.reservedBytes();
                    entered.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Test gate timed out"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                    break;
                }
            }
            return false;
        }
    }

    private static PlanningBudget budget(BooleanSupplier cancelled) {
        var budget = new PlanningBudget(0, 4_000_000, 128L << 20, cancelled, System::nanoTime);
        if (cancelled instanceof Gate gate) gate.budget = budget;
        return budget;
    }
    private static RecipeCountModel<String> model(PlanningBudget budget, long stock) {
        return RecipeCountModel.forShell(RECIPES, Map.of("out", BigInteger.ONE), Map.of("ore", stock), Set.of(), budget);
    }
    private static CountLpLearning.Cut cut(RecipeCountModel<?> model, long weight) {
        int id = model.rowKeys.indexOf("ore");
        var row = model.constraints.get(id);
        BigInteger multiplier = BigInteger.valueOf(weight);
        Map<Integer, BigInteger> terms = new TreeMap<>();
        row.terms().forEach((key, value) -> terms.put(key, value.multiply(multiplier).divide(BigInteger.TWO)));
        BigInteger upper = row.upper().multiply(multiplier).divide(BigInteger.TWO);
        return new CountLpLearning.Cut(new ExactLinearProgram.Constraint(terms, upper), Map.of(id, multiplier), BigInteger.TWO);
    }
    private static void check(boolean condition, String message) { assertions.incrementAndGet(); if (!condition) throw new AssertionError(message); }
}
