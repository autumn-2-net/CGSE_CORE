// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Completed macro copies use cache quotas; source matching never blocks the catalog monitor. */
public final class RecoveryCacheBudgetTest {
    private static final List<GraphRecipe<String>> RECIPES = List.of(recipe("a", "ore", "mid"), recipe("b", "mid", "out"));
    private static final List<GraphRecipe<String>> MACROS = List.of(recipe("macro", "ore", "out"));
    private static final Map<String, PlanStep> BODIES = Map.of("macro", new PlanStep.Sequence(List.of(new PlanStep.Batch("a", 1), new PlanStep.Batch("b", 1))));

    public static void main(String[] args) throws Exception {
        accounts();
        scopes();
        boundaries();
        for (boolean remembering : new boolean[]{true, false}) concurrency(remembering);
        evictedSnapshot();
        System.out.println("Recovery cache: independent admission, full-source identities, stock/quantity boundaries, concurrent preparation and matching passed");
    }

    private static void accounts() {
        for (int mode = 0; mode < 3; mode++) {
            var cache = new CountRecoveryTemplates<String>(); var b = budget();
            try (var model = model(RECIPES, 1, 1, Set.of(), b)) {
                cache.reuse(model); cache.reuse(model); b.threadWork(); b.threadSearchWork();
                long modelBytes = b.reservedBytes();
                if (mode == 1) { b.charge(b.remainingWork()); b.reserve(b.availableBytes()); }
                if (mode == 2) b.tryCacheScan((int) PlanningBudget.CACHE_PREPARATION_WORK * 4);
                long nodes = b.nodes(), local = b.threadWork(), search = b.threadSearchWork(), memory = b.reservedBytes(), peak = b.peakBytes();
                var template = cache.remember(model, MACROS, MACROS, BODIES);
                System.out.println("recovery preparation mode=" + mode + "; solver_delta=" + (b.nodes() - nodes) +
                        "; cache=" + b.cachePreparationWork() + "; peak_delta=" + (b.peakBytes() - peak));
                check((template != null) == (mode != 2), "Wrong independent recovery admission");
                check(b.nodes() == nodes && b.threadWork() == local && b.threadSearchWork() == search &&
                        b.reservedBytes() == memory && b.peakBytes() == peak, "Saving recovery template charged solver accounts");
                if (mode == 1) b.release(memory - modelBytes);
                if (template != null) check(template.completeCompiler == template.retainedCompiler, "Equal macro views duplicated compiler indexes");
            }
            check(b.reservedBytes() == 0, "Preparation fixture leaked");
            try (var fresh = model(RECIPES, 7, 7, Set.of(), budget())) {
                if (mode == 2) check(cache.remember(fresh, MACROS, MACROS, BODIES) != null, "Quota refusal prevented retry");
                long before = fresh.budget.searchWork();
                check(cache.reuse(fresh) != null && fresh.budget.searchWork() > before, "Current source matching was skipped or uncharged");
            }
        }
    }

    private static void scopes() {
        var cache = new CountRecoveryTemplates<String>();
        try (var first = model(RECIPES, 1, 1, Set.of(), budget())) {
            cache.reuse(first); cache.reuse(first); check(cache.remember(first, MACROS, MACROS, BODIES) != null, "No scope fixture");
        }
        for (int variant = 0; variant < 5; variant++) {
            var recipes = new ArrayList<>(RECIPES); Set<String> external = Set.of();
            if (variant == 1) Collections.reverse(recipes);
            if (variant == 2) recipes.set(0, recipe("a", "different", "mid"));
            if (variant == 3) recipes.add(recipe("new-producer", "fuel", "mid"));
            if (variant == 4) external = Set.of("ore");
            try (var model = model(recipes, 11, 9, external, budget())) {
                check((cache.reuse(model) != null) == (variant == 0), "Source/external boundary ignored: " + variant);
            }
        }
        try (var model = model(RECIPES, 1, 1, Set.of(), budget())) {
            var distinct = List.of(recipe("other", "ore", "out"));
            var template = cache.remember(model, MACROS, distinct, BODIES);
            check(template != null && template.completeCompiler != template.retainedCompiler,
                    "Equal-sized but distinct macro views shared an incorrect index");
        }
    }

    private static void boundaries() {
        for (int cut = 1; cut <= 8; cut++) {
            var cache = new CountRecoveryTemplates<String>(); var checks = new AtomicInteger(); var armed = new AtomicBoolean(); int limit = cut;
            var b = new PlanningBudget(0, 4_000_000, 128L << 20, () -> armed.get() && checks.incrementAndGet() >= limit, System::nanoTime);
            try (var model = model(RECIPES, 1, 1, Set.of(), b)) {
                cache.reuse(model); cache.reuse(model); long before = b.nodes(), peak = b.peakBytes(); armed.set(true);
                try { cache.remember(model, MACROS, MACROS, BODIES); } catch (CancellationException expected) {}
                check(b.nodes() == before && b.peakBytes() == peak && cache.cacheMetrics().preparation().estimatedBytes() == 0,
                        "Cancelled retention leaked or charged the solver");
                if (cut <= 3) check(cache.cacheMetrics().entry().entries() == 0, "Cancelled template was published");
            }
            check(b.reservedBytes() == 0, "Cancelled fixture leaked");
            try (var retry = model(RECIPES, 1, 1, Set.of(), budget())) {
                check(cache.remember(retry, MACROS, MACROS, BODIES) != null, "Cancellation poisoned retry");
            }
        }
        for (int scans = 0; scans <= 15; scans++) {
            var cache = new CountRecoveryTemplates<String>(); var b = budget();
            try (var model = model(RECIPES, 1, 1, Set.of(), b)) {
                cache.reuse(model); cache.reuse(model); long before = b.nodes(), peak = b.peakBytes();
                check(b.tryCacheScan((int) PlanningBudget.CACHE_PREPARATION_WORK * 4 - scans), "Cannot set partial quota");
                check((cache.remember(model, MACROS, MACROS, BODIES) != null) == (scans >= 14), "Wrong partial retention boundary: " + scans);
                check(b.nodes() == before && b.peakBytes() == peak && b.failureDetail().isEmpty() &&
                        cache.cacheMetrics().preparation().estimatedBytes() == 0, "Partial retention leaked");
            }
        }
        var cache = new CountRecoveryTemplates<String>(); var clock = new AtomicLong();
        var b = new PlanningBudget(1, 4_000_000, 128L << 20, () -> false, clock::get);
        try (var model = model(RECIPES, 1, 1, Set.of(), b)) {
            cache.reuse(model); cache.reuse(model); clock.set(1_000_000);
            try { cache.remember(model, MACROS, MACROS, BODIES); throw new AssertionError("Timeout ignored"); }
            catch (PlanningBudget.Exhausted expected) { check(expected.limit() == PlanningBudget.Limit.TIMEOUT, "Wrong timeout"); }
            check(cache.cacheMetrics().entry().entries() == 0 && cache.cacheMetrics().preparation().estimatedBytes() == 0,
                    "Timed retention was published or leaked");
        }
    }

    private static void concurrency(boolean remembering) throws Exception {
        var cache = new CountRecoveryTemplates<String>();
        try (var initial = model(RECIPES, 1, 1, Set.of(), budget())) {
            cache.reuse(initial); cache.reuse(initial); cache.remember(initial, MACROS, MACROS, BODIES);
        }
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var armed = new AtomicBoolean();
        var b = new PlanningBudget(0, 4_000_000, 128L << 20, () -> {
            if (armed.get()) {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Recovery gate timed out"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }
            return false;
        }, System::nanoTime);
        var pool = Executors.newFixedThreadPool(2);
        try (var left = model(RECIPES, 3, 3, Set.of(), b); var right = model(RECIPES, 5, 5, Set.of(), budget())) {
            armed.set(true);
            var first = pool.submit(() -> { if (remembering) cache.remember(left, MACROS, MACROS, BODIES); else cache.reuse(left); });
            check(entered.await(5, TimeUnit.SECONDS), "No recovery callback");
            var second = pool.submit(() -> {
                check(cache.cacheMetrics().entry().entries() >= 1, "Complete entry disappeared");
                check(cache.reuse(right) != null, "Concurrent current-stock request lost its template");
                if (remembering) {
                    check(cache.cacheMetrics().preparation().estimatedBytes() > 0, "Unaccounted recovery preparation");
                    check(cache.remember(right, MACROS, MACROS, BODIES) == null && right.budget.cachePreparationWork() == 0,
                            "Contending retention repeated preparation");
                }
            });
            try { second.get(2, TimeUnit.SECONDS); } finally { release.countDown(); }
            first.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); }
        check(b.reservedBytes() == 0, "Concurrent snapshot leaked");
        check(cache.cacheMetrics().preparation().estimatedBytes() == 0, "Completed preparation leaked");
    }

    private static void evictedSnapshot() throws Exception {
        var cache = new CountRecoveryTemplates<String>();
        try (var model = model(RECIPES, 1, 1, Set.of(), budget())) {
            cache.reuse(model); cache.reuse(model); check(cache.remember(model, MACROS, MACROS, BODIES) != null, "No eviction fixture");
        }
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var armed = new AtomicBoolean();
        var b = new PlanningBudget(0, 4_000_000, 128L << 20, () -> {
            if (armed.get()) {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Eviction gate timed out"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }
            return false;
        }, System::nanoTime);
        var pool = Executors.newSingleThreadExecutor();
        try (var waiting = model(RECIPES, 1, 1, Set.of(), b)) {
            armed.set(true); var old = pool.submit(() -> cache.reuse(waiting));
            check(entered.await(5, TimeUnit.SECONDS), "Snapshot did not pause");
            for (int i = 0; i < 8; i++) try (var next = model(RECIPES, 1, 1, Set.of("boundary" + i), budget())) {
                check(cache.remember(next, MACROS, MACROS, BODIES) != null, "Replacement failed");
            }
            var before = cache.cacheMetrics().entry();
            check(before.entries() == 8 && before.evictions() == 1, "Template cache is unbounded");
            release.countDown(); check(old.get(5, TimeUnit.SECONDS) != null, "Valid evicted snapshot was lost");
            try (var fresh = model(RECIPES, 1, 1, Set.of(), budget())) {
                check(cache.reuse(fresh) == null, "Evicted snapshot was resurrected");
            }
            var after = cache.cacheMetrics().entry();
            check(before.entries() == after.entries() && before.estimatedBytes() == after.estimatedBytes(), "Snapshot changed residency");
        } finally { release.countDown(); pool.shutdownNow(); }
        check(b.reservedBytes() == 0, "Evicted snapshot leaked");
    }

    private static RecipeCountModel<String> model(List<GraphRecipe<String>> recipes, long amount, long stock, Set<String> external, PlanningBudget b) {
        return RecipeCountModel.forShell(recipes, Map.of("out", BigInteger.valueOf(amount)), Map.of("ore", stock), external, b);
    }
    private static GraphRecipe<String> recipe(String id, String from, String to) {
        return new GraphRecipe<>(id, id, List.of(new GraphRecipe.Slot<>(from, 1)), Map.of(to, 1L));
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 4_000_000, 128L << 20, () -> false, System::nanoTime); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
