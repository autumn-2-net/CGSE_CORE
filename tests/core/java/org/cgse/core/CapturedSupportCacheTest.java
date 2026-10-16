// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Capture-local hints: exact scope, immutable ownership and an independent bounded account. */
public final class CapturedSupportCacheTest {
    private static final AtomicInteger checks = new AtomicInteger();
    private record Key(int id) { @Override public int hashCode() { return 1; } }

    public static void main(String[] args) throws Exception {
        scope(); limits(); cancellation(); concurrent();
        System.out.println("CapturedSupportCacheTest: assertions=" + checks.get());
    }

    private static void scope() {
        var cache = new CapturedSupportCache<Key>(); var b = budget();
        var first = entry(1); var second = entry(2);
        var sources = new ArrayList<>(List.of(second, first));
        var seeds = new LinkedHashSet<>(List.of(new Key(7), new Key(8)));
        cache.retain(new Key(0), seeds, sources, b);
        check(b.nodes() == 0 && b.cachePreparationWork() == 5, "Retention spent current-request work");
        sources.clear(); seeds.clear();
        var saved = cache.get(new Key(0), new LinkedHashSet<>(List.of(new Key(8), new Key(7))), b);
        check(saved.size() == 2 && saved.get(0) == second && saved.get(1) == first, "Changed priority or borrowed mutable scope");
        try { saved.clear(); throw new AssertionError("Mutable retained sources"); } catch (UnsupportedOperationException expected) {}
        check(cache.get(new Key(1), Set.of(new Key(7), new Key(8)), b) == null, "Target hash collision matched");
        check(cache.get(new Key(0), Set.of(new Key(7), new Key(9)), b) == null, "Seed hash collision matched");
        check(cache.get(new Key(0), Set.of(new Key(7)), b) == null, "Subset matched full scope");
        cache.retain(new Key(0), Set.of(new Key(7), new Key(8)), List.of(first), b);
        check(cache.metrics().entries() == 1 && cache.get(new Key(0), Set.of(new Key(7), new Key(8)), b).get(0) == second,
                "Duplicate replaced immutable capture's original priority");
        check(b.searchWork() == 0 && b.compilationWork() > 0 && b.reservedBytes() == 0, "Lookup costs or ownership escaped their account");
        var next = budget(); check(next.tryCacheCharge(PlanningBudget.CACHE_PREPARATION_WORK), "Cannot exhaust optional account");
        check(cache.get(new Key(0), Set.of(new Key(7), new Key(8)), next) != null, "Full retention account blocked an existing lookup");
        cache.retain(new Key(99), Set.of(), List.of(first), next);
        check(cache.metrics().entries() == 1 && next.searchWork() == 0, "Refused retention changed the cache or search allowance");
    }

    private static void limits() {
        var cache = new CapturedSupportCache<Key>(); var one = List.of(entry(1));
        for (int i = 0; i < 100; i++) {
            cache.retain(new Key(i), Set.of(), one, budget()); bounds(cache);
        }
        check(cache.metrics().entries() == 32, "Entry cap not applied");
        check(cache.get(new Key(0), Set.of(), budget()) == null && cache.get(new Key(99), Set.of(), budget()) != null, "Eviction lost newest hint");
        var seeds = new LinkedHashSet<Key>(); for (int i = 0; i < 65; i++) seeds.add(new Key(i));
        var oversized = new ArrayList<CapturedCatalog.Entry<Key>>(); for (int i = 0; i < 129; i++) oversized.add(entry(i));
        cache.retain(new Key(-1), seeds, one, budget());
        cache.retain(new Key(-2), Set.of(), oversized, budget());
        cache.retain(new Key(-3), Set.of(), List.of(), budget());
        cache.retain(new Key(-4), Set.of(), null, budget());
        check(cache.metrics().entries() == 32 && cache.get(new Key(-1), seeds, budget()) == null, "Oversized/partial scope retained");
        seeds.remove(new Key(64)); oversized.remove(128);
        for (int i = 100; i < 150; i++) { cache.retain(new Key(i), seeds, oversized, budget()); bounds(cache); }
        check(cache.metrics().entries() < 32 && cache.metrics().bytes() > 60_000, "Byte cap not independently enforced");
        check(cache.get(new Key(149), seeds, budget()).size() == 128, "Largest accepted support corrupted");
    }

    private static void cancellation() {
        var cache = new CapturedSupportCache<Key>(); var cancelled = new AtomicBoolean(true);
        var b = new PlanningBudget(0, 40_000_000, 64L << 20, cancelled::get, System::nanoTime);
        try { cache.retain(new Key(0), Set.of(), List.of(entry(0)), b); throw new AssertionError("Cancelled retention ran"); }
        catch (CancellationException expected) {}
        check(cache.metrics().entries() == 0, "Cancelled publication survived");
        cache.retain(new Key(0), Set.of(new Key(1)), List.of(entry(0)), budget());
        try { cache.get(new Key(0), Set.of(new Key(1)), b); throw new AssertionError("Cancelled lookup ran"); }
        catch (CancellationException expected) {}
        check(b.reservedBytes() == 0 && cache.metrics().entries() == 1, "Cancellation corrupted retained scope");
    }

    private static void concurrent() throws Exception {
        var cache = new CapturedSupportCache<Key>(); var pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Callable<Void>>();
            for (int worker = 0; worker < 4; worker++) {
                int w = worker;
                tasks.add(() -> {
                    for (int n = 0; n < 120; n++) {
                        int id = w * 120 + n; var key = new Key(id); var b = budget();
                        cache.retain(key, Set.of(new Key(-1)), List.of(entry(id)), b);
                        var hit = cache.get(key, Set.of(new Key(-1)), b);
                        check(hit == null || hit.size() == 1 && hit.get(0).id().equals("r" + id), "Concurrent snapshot mixed scopes");
                        bounds(cache); check(b.reservedBytes() == 0, "Concurrent request leaked");
                    }
                    return null;
                });
            }
            for (var result : pool.invokeAll(tasks)) result.get();
        } finally { pool.shutdownNow(); check(pool.awaitTermination(10, TimeUnit.SECONDS), "Workers did not terminate"); }
        bounds(cache);
    }

    private static CapturedCatalog.Entry<Key> entry(int id) {
        return new CapturedCatalog.Entry<>("r" + id, "r" + id,
                new CapturedRecipe<>(List.of(), List.of(new CapturedRecipe.Amount<>(new Key(id), 1)), false, false));
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 40_000_000, 64L << 20, () -> false, System::nanoTime); }
    private static void bounds(CapturedSupportCache<?> cache) {
        var m = cache.metrics(); check(m.entries() <= 32 && m.bytes() <= m.maximumBytes() && m.maximumBytes() == 65536, "Unbounded retained storage");
    }
    private static void check(boolean value, String message) { checks.incrementAndGet(); if (!value) throw new AssertionError(message); }
}
