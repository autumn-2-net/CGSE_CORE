// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional stock hints have independent storage/work limits; reads belong to this request. */
public final class CapturedStockProjectionTest {
    private static int checks;
    private record Key(int id) { @Override public int hashCode() { return 1; } }

    public static void main(String[] args) {
        scope(); bounds(); preparationLimits(); projection();
        System.out.println("CapturedStockProjectionTest: assertions=" + checks);
    }

    private static void scope() {
        var cache = new CapturedSupportCache<Key>(); var sources = List.of(source());
        var target = new Key(9); var seeds = Set.of(new Key(8)); var b = budget(64L << 20);
        cache.retain(target, seeds, sources, b);
        var used = new LinkedHashSet<>(List.of(new Key(7))); long work = b.nodes();
        cache.retainStockKeys(target, seeds, sources, used, 1000, b); used.clear();
        check(b.nodes() == work && b.cachePreparationWork() > 0, "Stock hint preparation spent current solve work");
        var hint = cache.select(target, seeds, b);
        check(new HashSet<>(hint.stockKeys()).equals(Set.of(new Key(1), new Key(2), new Key(3), new Key(7), new Key(8), new Key(9))),
                "Dropped a declared input, return, output, used state, seed or target");
        try { hint.stockKeys().clear(); throw new AssertionError("Mutable key hint"); } catch (UnsupportedOperationException expected) {}
        cache.retainStockKeys(target, seeds, sources, Set.of(new Key(6)), 1000, b);
        check(cache.select(target, seeds, b).stockKeys().containsAll(List.of(new Key(6), new Key(7))), "New observed state discarded the old hint");
        check(cache.select(new Key(10), seeds, b) == null && cache.select(target, Set.of(new Key(10)), b) == null,
                "Colliding target or seed keys borrowed stock hints");
        var different = List.of(new CapturedCatalog.Entry<>("source", "other-binding", source().recipe()));
        cache.retainStockKeys(target, seeds, different, Set.of(new Key(11)), 1000, b);
        check(!cache.select(target, seeds, b).stockKeys().contains(new Key(11)), "Different source semantics updated a hint");
        check(b.reservedBytes() == 0 && cache.metrics().bytes() <= cache.metrics().maximumBytes(), "Retained keys escaped independent memory cap");
    }

    private static void bounds() {
        var sources = List.of(source()); var cache = new CapturedSupportCache<Key>();
        var many = new LinkedHashSet<Key>(); for (int i = 0; i < 240; i++) many.add(new Key(1000 + i));
        for (int i = 0; i < 40; i++) {
            var target = new Key(100 + i); var b = budget(64L << 20);
            cache.retain(target, Set.of(), sources, b);
            cache.retainStockKeys(target, Set.of(), sources, many, 2000, b);
            check(cache.select(target, Set.of(), b).stockKeys() != null, "Newest bounded hint was lost");
            check(cache.metrics().entries() <= 32 && cache.metrics().bytes() <= 65536, "Stock hints bypassed aggregate caps");
        }
        check(cache.metrics().entries() < 32, "Stock-key bytes did not affect eviction");
        var fresh = new CapturedSupportCache<Key>(); var target = new Key(9); var b = budget(64L << 20);
        fresh.retain(target, Set.of(), sources, b);
        for (int i = 240; i < 257; i++) many.add(new Key(1000 + i));
        fresh.retainStockKeys(target, Set.of(), sources, many, 2000, b);
        check(fresh.select(target, Set.of(), b).stockKeys() == null, "Oversized stock hint retained");
        fresh.retainStockKeys(target, Set.of(), sources, Set.of(new Key(7)), 2, b);
        check(fresh.select(target, Set.of(), b).stockKeys() == null, "Small inventory paid for a redundant projection");
        // Few distinct keys can still have a huge declared domain. Bound visits,
        // not just deduplicated keys; never publish partial port coverage.
        var wide = new CapturedCatalog.Entry<>("wide", "wide", new CapturedRecipe<Key>(List.of(),
                Collections.nCopies(5000, new CapturedRecipe.Amount<>(new Key(3), 1)), false, false));
        fresh.retain(new Key(4), Set.of(), List.of(wide), b);
        fresh.retainStockKeys(new Key(4), Set.of(), List.of(wide), Set.of(new Key(7)), 2000, b);
        check(fresh.select(new Key(4), Set.of(), b).stockKeys() == null, "Oversized traversal published a partial key hint");
    }

    private static void preparationLimits() {
        for (int left = 0; left < 30; left++) {
            var cache = new CapturedSupportCache<Key>(); var sources = List.of(source());
            var target = new Key(9); cache.retain(target, Set.of(), sources, budget(64L << 20));
            var b = budget(64L << 20); check(b.tryCacheCharge(PlanningBudget.CACHE_PREPARATION_WORK - left), "Cannot fill optional account");
            cache.retainStockKeys(target, Set.of(), sources, Set.of(new Key(7)), 1000, b);
            check(b.nodes() == 0 && b.cachePreparationWork() <= PlanningBudget.CACHE_PREPARATION_WORK, "Partial preparation borrowed solve allowance");
            var hint = cache.select(target, Set.of(), b);
            check(hint != null && (hint.stockKeys() == null || hint.stockKeys().containsAll(List.of(new Key(1), new Key(2), new Key(3), new Key(7), target))),
                    "Preparation refusal damaged source scope or published partial keys");
        }
        var cache = new CapturedSupportCache<Key>(); var sources = List.of(source()); var target = new Key(9);
        cache.retain(target, Set.of(), sources, budget(64L << 20));
        var cancelled = new PlanningBudget(0, 40_000_000, 64L << 20, () -> true, System::nanoTime);
        try { cache.retainStockKeys(target, Set.of(), sources, Set.of(new Key(7)), 1000, cancelled); throw new AssertionError("Cancelled hint publication ran"); }
        catch (CancellationException expected) {}
        check(cache.select(target, Set.of(), budget(64L << 20)).stockKeys() == null, "Cancelled preparation published keys");
    }

    private static void projection() {
        var keys = List.of(new Key(7), new Key(8), new Key(9));
        var stock = new LinkedHashMap<Key, Long>(); for (int i = 0; i < 100; i++) stock.put(new Key(i), 100L + i);
        for (long memory : new long[]{255, 256, 351, 352, 543, 544, 4096}) {
            var b = budget(memory); var work = CapturedStockProjection.create(keys, stock, b);
            if (work == null) check(memory < 256, "Declined funded projection");
            else try (work) {
                check(b.nodes() == 0, "Projection constructor eagerly traversed stock");
                while (true) { long before = b.nodes(); boolean done = work.step(); check(b.nodes() - before <= 1, "Projection step rescanned the inventory"); if (done) break; }
                var view = work.result();
                check((view != null) == (memory >= 544), "Partial memory admission published stock");
                if (view != null) {
                    check(view.equals(Map.of(new Key(7), 107L, new Key(8), 108L, new Key(9), 109L)), "Projection reused old amounts");
                    try { view.clear(); throw new AssertionError("Mutable projection view"); } catch (UnsupportedOperationException expected) {}
                }
            }
            check(b.reservedBytes() == 0 && b.peakBytes() <= memory && b.searchWork() == 0 && b.cachePreparationWork() == 0,
                    "Projection lifetime or work accounts escaped limits");
        }
        stock.remove(new Key(7)); stock.put(new Key(8), 1L); stock.put(new Key(9), Long.MAX_VALUE);
        var b = budget(4096);
        try (var work = CapturedStockProjection.create(keys, stock, b)) {
            while (!work.step()) {} check(work.result().equals(Map.of(new Key(8), 1L, new Key(9), Long.MAX_VALUE)), "Removed/changed/long-sized stock was stale");
        }
        var stop = new AtomicBoolean(); b = new PlanningBudget(0, 40_000_000, 4096, stop::get, System::nanoTime);
        try (var work = CapturedStockProjection.create(keys, stock, b)) {
            work.step(); stop.set(true);
            try { work.step(); throw new AssertionError("Cancelled projection kept scanning"); } catch (CancellationException expected) {}
        }
        check(b.reservedBytes() == 0, "Cancellation leaked projection memory");
    }

    private static CapturedCatalog.Entry<Key> source() {
        var input = new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(new Key(1), 1), new Key(2), false)));
        return new CapturedCatalog.Entry<>("source", "source", new CapturedRecipe<>(List.of(input), List.of(new CapturedRecipe.Amount<>(new Key(3), 1)), false, false));
    }
    private static PlanningBudget budget(long bytes) { return new PlanningBudget(0, 40_000_000, bytes, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
