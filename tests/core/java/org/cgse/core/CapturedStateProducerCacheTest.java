// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class CapturedStateProducerCacheTest {
    private static final AtomicInteger checks = new AtomicInteger();
    private record Rule(int kind) implements StateFamily<Integer> {
        public String id() { return "same-id"; }
        public boolean accepts(Integer key) { throw new AssertionError("Cache invoked state algebra"); }
        public long capacity(Integer key) { throw new AssertionError("Cache invoked state algebra"); }
        public Integer nominalAfterOne(Integer key) { throw new AssertionError("Cache unfolded a state"); }
    }

    public static void main(String[] args) throws Exception {
        scopes(); limits(); cancellation(); concurrent();
        System.out.println("CapturedStateProducerCacheTest: assertions=" + checks);
    }

    private static void scopes() {
        var cache = new CapturedStateProducers<Integer>(); var rule = new Rule(1);
        Map<Integer, List<Integer>> index = Map.of(7, List.of(0), 9, List.of(1));
        var b = budget();
        try (var capture = cache.begin(rule, index, b)) { capture.add(7); }
        check(cache.get(rule, index, b) == null, "Closed partial scan was published");
        retain(cache, rule, index, List.of(7, 9), b);
        var keys = cache.get(rule, index, b);
        check(keys.equals(List.of(7, 9)), "Complete projection lost order/keys");
        try { keys.clear(); throw new AssertionError("Published list is mutable"); } catch (UnsupportedOperationException expected) {}
        check(cache.get(new Rule(1), index, b) == null, "Equal rule object bypassed exact capture scope");
        check(cache.get(new Rule(2), index, b) == null, "Same rule ID reused replaced semantics");
        check(cache.get(rule, new LinkedHashMap<>(index), b) == null, "Equal/new producer index borrowed an old projection");
        var changed = new LinkedHashMap<>(index); changed.put(11, List.of(2));
        check(cache.get(rule, changed, b) == null, "New producer silently inherited old coverage");
        var empty = new Rule(3); retain(cache, empty, index, List.of(), b);
        check(cache.get(empty, index, b).isEmpty(), "Complete empty projection confused with absent entry");
        check(b.searchWork() == 0 && b.reservedBytes() == 0 && b.cachePreparationWork() > 0,
                "Retention borrowed search work/memory");
        bounds(cache);
    }

    private static void limits() {
        Map<Integer, List<Integer>> index = Map.of(0, List.of(0)); var rule = new Rule(0);
        for (int left = 0; left <= 20; left++) {
            var b = budget(); check(b.tryCacheCharge(PlanningBudget.CACHE_PREPARATION_WORK - left), "Cannot fill optional account");
            var cache = new CapturedStateProducers<Integer>();
            retain(cache, rule, index, List.of(0, 1, 2, 3, 4), b);
            var hit = cache.get(rule, index, b);
            check(hit == null || hit.equals(List.of(0, 1, 2, 3, 4)), "Quota refusal published partial keys");
            check(b.cachePreparationWork() <= PlanningBudget.CACHE_PREPARATION_WORK && b.searchWork() == 0 && b.reservedBytes() == 0,
                    "Cache quota overflow or borrowed solve resources");
        }
        var many = new ArrayList<Integer>(); for (int i = 0; i < 257; i++) many.add(i);
        var cache = new CapturedStateProducers<Integer>(); retain(cache, rule, index, many, budget());
        check(cache.get(rule, index, budget()) == null, "Truncated a wide projection");
        many.remove(256); retain(cache, rule, index, many, budget());
        check(cache.get(rule, index, budget()).size() == 256, "Dropped boundary-sized projection");
        cache = new CapturedStateProducers<>(); var first = new Rule(0); retain(cache, first, index, List.of(0), budget());
        for (int i = 1; i < 50; i++) { var next = new Rule(i); retain(cache, next, index, List.of(i), budget()); bounds(cache); }
        check(cache.metrics().entries() == 32 && cache.get(first, index, budget()) == null, "Entry eviction failed");
        for (int i = 0; i < 50; i++) { var next = new Rule(i); retain(cache, next, index, many, budget()); bounds(cache); }
        check(cache.metrics().entries() < 32 && cache.metrics().bytes() > 56_000, "Independent byte limit not exercised");
    }

    private static void cancellation() {
        var cache = new CapturedStateProducers<Integer>(); var rule = new Rule(0);
        Map<Integer, List<Integer>> index = Map.of(0, List.of(0)); var cancel = new AtomicBoolean();
        var b = new PlanningBudget(0, 40_000_000, 1024, cancel::get, System::nanoTime);
        try (var capture = cache.begin(rule, index, b)) {
            capture.add(0); cancel.set(true);
            try { capture.complete(); throw new AssertionError("Cancelled publication proceeded"); } catch (CancellationException expected) {}
        }
        check(cache.metrics().entries() == 0 && b.reservedBytes() == 0, "Cancelled preparation escaped");
        try { cache.begin(rule, index, b); throw new AssertionError("Cancelled retention admitted"); } catch (CancellationException expected) {}
        retain(cache, rule, index, List.of(0), budget());
        try { cache.get(rule, index, b); throw new AssertionError("Cancelled lookup ran"); } catch (CancellationException expected) {}
        check(cache.get(rule, index, budget()).equals(List.of(0)), "Cancellation damaged an existing snapshot");
    }

    private static void concurrent() throws Exception {
        var cache = new CapturedStateProducers<Integer>(); Map<Integer, List<Integer>> index = Map.of(0, List.of(0));
        var pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Callable<Void>>();
            for (int w = 0; w < 4; w++) {
                final int worker = w;
                tasks.add(() -> {
                    for (int i = 0; i < 100; i++) {
                        int value = worker * 100 + i; var rule = new Rule(value); var b = budget();
                        retain(cache, rule, index, List.of(value), b);
                        var hit = cache.get(rule, index, b);
                        check(hit == null || hit.equals(List.of(value)), "Racing publication mixed projections");
                        check(b.reservedBytes() == 0 && b.searchWork() == 0, "Concurrent retention borrowed solve resources");
                        bounds(cache);
                    }
                    return null;
                });
            }
            for (var task : pool.invokeAll(tasks)) task.get();
        } finally { pool.shutdownNow(); check(pool.awaitTermination(10, TimeUnit.SECONDS), "Workers did not terminate"); }
    }

    private static void retain(CapturedStateProducers<Integer> cache, Rule rule, Map<Integer, List<Integer>> index,
                               List<Integer> keys, PlanningBudget budget) {
        try (var capture = cache.begin(rule, index, budget)) {
            if (capture == null) return;
            for (int key : keys) capture.add(key);
            capture.complete(); capture.complete();
        }
    }
    private static void bounds(CapturedStateProducers<?> cache) {
        var m = cache.metrics(); check(m.entries() <= CapturedStateProducers.MAX_ENTRIES && m.bytes() <= CapturedStateProducers.MAX_BYTES,
                "Unbounded persistent projection storage");
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 40_000_000, 1, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { checks.incrementAndGet(); if (!value) throw new AssertionError(message); }
}
