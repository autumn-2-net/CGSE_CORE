// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Saved arithmetic conflicts are optional; checking the current order is not. */
public final class CountSessionBudgetTest {
    static final List<GraphRecipe<String>> RECIPES = List.of(
            new GraphRecipe<>("a", "a", List.of(new GraphRecipe.Slot<>("ore", 2)), Map.of("goal", 1L)),
            new GraphRecipe<>("b", "b", List.of(new GraphRecipe.Slot<>("dust", 3)), Map.of("goal", 1L)));

    public static void main(String[] args) throws Exception {
        var owner = budget();
        try (var model = model(owner, 2)) {
            independentAccounts(model);
            exhaustedAccounts(model);
            interruptions(model);
            concurrentAccess(model);
            concurrentQuota(model);
            changedInventory(model);
        }
        check(owner.reservedBytes() == 0, "Fixture leaked");
        System.out.println("Count sessions: independent retention accounts, exact proofs, bounded publication, cancellation and concurrent readers passed");
    }

    private static void independentAccounts(RecipeCountModel<?> model) {
        var cache = new CountSessions(); var b = budget();
        b.threadWork(); b.threadSearchWork();
        b.operation(PlanningBudget.Operation.SCAN, 0); b.compilationScan();
        long work = b.nodes(), local = b.threadWork(), search = b.threadSearchWork(), peak = b.peakBytes();
        cache.remember(model, List.of(conflict(model)), b);
        check(cache.cacheMetrics().entries() == 1, "Arithmetic conflict was not retained");
        check(b.nodes() == work && b.threadWork() == local && b.threadSearchWork() == search,
                "Retention consumed solver/compilation/continuation work");
        check(b.peakBytes() == peak && b.reservedBytes() == 0, "Retention borrowed request memory");
        check(b.cachePreparationWork() > 0, "Retention proof was not charged to optional account");
        b.operations(PlanningBudget.Operation.SCAN, 0, 3);
        check(b.searchWork() == 1 && b.compilationWork() == 1, "Fractional clocks contaminated");
        long cacheWork = b.cachePreparationWork(), before = b.searchWork();
        var result = cache.reuse(model, b);
        check(result.equals(List.of(conflict(model))), "Retained conflict changed");
        check(b.searchWork() > before && b.cachePreparationWork() == cacheWork, "Current-order check escaped solver work");
        check(b.reservedBytes() == 0, "Reuse leaked workspace");
        verified(model, result);
    }

    private static void exhaustedAccounts(RecipeCountModel<?> model) {
        var cache = new CountSessions();
        var full = new PlanningBudget(0, 1, 1, () -> false, System::nanoTime);
        full.reserve(1); full.check();
        cache.remember(model, List.of(conflict(model)), full);
        check(cache.cacheMetrics().entries() == 1, "Full search accounts prevented independent retention");
        check(full.searchWork() == 1 && full.compilationWork() == 0 && full.reservedBytes() == 1 && full.peakBytes() == 1,
                "Retention charged exhausted request accounts");
        full.release(1);
        for (int available = 0; available <= 96; available++) {
            cache = new CountSessions(); var b = budget();
            check(b.tryCacheCharge(PlanningBudget.CACHE_PREPARATION_WORK - available), "Cannot arrange optional budget");
            cache.remember(model, List.of(conflict(model)), b);
            check(b.searchWork() == 0 && b.peakBytes() == 0 && b.failureDetail().isEmpty(), "Partial optional cap affected request");
            check(b.cachePreparationWork() <= PlanningBudget.CACHE_PREPARATION_WORK, "Optional cap exceeded");
            var stored = cache.reuse(model, budget());
            if (!stored.isEmpty()) verified(model, stored);
            if (cache.cacheMetrics().entries() == 0) check(cache.cacheMetrics().estimatedBytes() == 0, "Partial publication leaked");
            cache.remember(model, List.of(conflict(model)), budget());
            check(cache.cacheMetrics().entries() == 1, "Refused preparation poisoned next request");
        }
    }

    private static void interruptions(RecipeCountModel<?> model) {
        for (int stop = 1; stop <= 80; stop++) {
            var cache = new CountSessions(); var calls = new AtomicInteger(); int limit = stop;
            var b = new PlanningBudget(0, 1, 1, () -> calls.incrementAndGet() >= limit, System::nanoTime);
            boolean cancelled = false;
            try { cache.remember(model, List.of(conflict(model)), b); }
            catch (CancellationException expected) { cancelled = true; }
            check(b.searchWork() == 0 && b.peakBytes() == 0, "Cancelled retention used request accounts");
            if (cancelled) check(cache.cacheMetrics().entries() == 0 && cache.cacheMetrics().estimatedBytes() == 0,
                    "Cancelled capture published partial proof or pinned memory");
            cache.remember(model, List.of(conflict(model)), budget());
            check(cache.cacheMetrics().entries() == 1, "Cancellation poisoned next capture");
        }
        var clock = new AtomicLong(); var cache = new CountSessions();
        var b = new PlanningBudget(1, 1, 1, () -> false, clock::get); b.start(); clock.set(1_000_000);
        try { cache.remember(model, List.of(conflict(model)), b); throw new AssertionError("Deadline ignored"); }
        catch (PlanningBudget.Exhausted expected) { check(expected.limit() == PlanningBudget.Limit.TIMEOUT, "Wrong stop reason"); }
        check(cache.cacheMetrics().estimatedBytes() == 0 && b.searchWork() == 0, "Deadline leaked optional state");
    }

    private static void concurrentAccess(RecipeCountModel<?> model) throws Exception {
        var cache = new CountSessions(); cache.remember(model, List.of(conflict(model)), budget());
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var b = new PlanningBudget(0, 1, 1, () -> {
            entered.countDown(); await(release); return false;
        }, System::nanoTime);
        var pool = Executors.newFixedThreadPool(3);
        try {
            var writing = pool.submit(() -> cache.remember(model, List.of(conflict(model)), b));
            check(entered.await(5, TimeUnit.SECONDS), "Writer did not enter optional work");
            var reading = pool.submit(() -> cache.reuse(model, budget()));
            check(!reading.get(5, TimeUnit.SECONDS).isEmpty(), "Proof saving blocked independent reader");
            long bytes = cache.cacheMetrics().estimatedBytes();
            check(bytes > 0 && bytes <= (8L << 20), "Unbounded cache workspace");
            var other = pool.submit(() -> cache.remember(model, List.of(conflict(model)), budget()));
            other.get(5, TimeUnit.SECONDS);
            release.countDown(); writing.get(5, TimeUnit.SECONDS);
            check(cache.cacheMetrics().entries() == 1 && cache.cacheMetrics().estimatedBytes() < bytes,
                    "Publication duplicated entry or retained scratch");

            var readEntered = new CountDownLatch(1); var readRelease = new CountDownLatch(1);
            var readerBudget = new PlanningBudget(0, 4_000_000, 128L << 20, () -> {
                readEntered.countDown(); await(readRelease); return false;
            }, System::nanoTime);
            var reader = pool.submit(() -> cache.reuse(model, readerBudget));
            try {
                check(readEntered.await(5, TimeUnit.SECONDS), "Reader did not check current model");
                pool.submit(() -> cache.remember(model, List.of(conflict(model)), budget())).get(5, TimeUnit.SECONDS);
            } finally { readRelease.countDown(); }
            verified(model, reader.get(5, TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private static void concurrentQuota(RecipeCountModel<?> model) throws Exception {
        var cache = new CountSessions(); var b = budget(); var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(4); var jobs = new ArrayList<Future<?>>();
        try {
            for (int i = 0; i < 4; i++) jobs.add(pool.submit(() -> {
                await(start);
                for (int round = 0; round < 100; round++) cache.remember(model, List.of(conflict(model)), b);
            }));
            start.countDown(); for (var job : jobs) job.get(20, TimeUnit.SECONDS);
            check(cache.cacheMetrics().entries() == 1, "Shared preparation corrupted publication");
            check(b.searchWork() == 0 && b.peakBytes() == 0 && b.cachePreparationWork() <= PlanningBudget.CACHE_PREPARATION_WORK,
                    "Workers multiplied or mixed optional accounts");
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    private static void changedInventory(RecipeCountModel<?> original) {
        var cache = new CountSessions(); cache.remember(original, List.of(conflict(original)), budget());
        var b = budget();
        try (var funded = model(b, 4)) {
            check(cache.reuse(funded, b).isEmpty(), "Old shortage survived replenished inventory");
            // Two runs of a now fit the material row and satisfy the goal.
            var counts = new BigInteger[]{BigInteger.TWO, BigInteger.ZERO};
            for (var row : funded.constraints) {
                BigInteger sum = BigInteger.ZERO;
                for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(counts[term.getKey()]));
                check(sum.compareTo(row.upper()) <= 0, "Funded oracle is not feasible");
            }
        }
        check(!cache.reuse(original, budget()).isEmpty(), "Changed inventory destroyed valid saved proof");
        check(b.reservedBytes() == 0, "Current revalidation leaked");
    }

    static RecipeCountModel<String> model(PlanningBudget b, long ore) {
        return RecipeCountModel.forShell(RECIPES, Map.of("goal", BigInteger.ONE), Map.of("ore", ore, "dust", 3L), Set.of(), b);
    }
    static CountConflict conflict(RecipeCountModel<?> model) {
        int id = 0; while (!model.recipes.get(id).id().equals("a")) id++;
        return new CountConflict(List.of(new ExactLinearProgram.Constraint(Map.of(id, BigInteger.ONE.negate()), BigInteger.valueOf(-2))));
    }
    static void verified(RecipeCountModel<?> model, List<CountConflict> conflicts) {
        check(!conflicts.isEmpty(), "Expected retained proof");
        var proof = new CountProof.Certificate("current_session_oracle", model.recipes.size(),
                model.constraints.stream().map(CountProof::row).toList(),
                conflicts.stream().map(c -> c.assumptions().stream().map(CountProof::row).toList()).toList(), List.of(), false);
        check(CountProof.verify(proof, 100_000) == CountProof.Verdict.VERIFIED, "Saved conflict failed independent arithmetic replay");
    }
    static PlanningBudget budget() { return new PlanningBudget(0, 4_000_000, 128L << 20, () -> false, System::nanoTime); }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static void await(CountDownLatch gate) {
        try { if (!gate.await(10, TimeUnit.SECONDS)) throw new AssertionError("Concurrent gate timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
