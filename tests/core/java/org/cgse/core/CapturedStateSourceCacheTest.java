// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Verified-source hints own no stock, algebra results, or cross-capture facts. */
public final class CapturedStateSourceCacheTest {
    private static final AtomicInteger checks = new AtomicInteger();
    private static final StateFamily<String> UNUSED = new StateFamily<>() {
        public String id() { return "unused"; }
        public boolean accepts(String key) { throw new AssertionError("Retention queried algebra"); }
        public long capacity(String key) { throw new AssertionError("Retention unfolded capacity"); }
        public String nominalAfterOne(String key) { throw new AssertionError("Retention unfolded a state"); }
    };

    public static void main(String[] args) throws Exception {
        scope(); limits(); cancellation(); concurrent();
        System.out.println("CapturedStateSourceCacheTest: assertions=" + checks.get());
    }

    private static void scope() {
        var entries = List.of(entry(0, false, false, 1, null), entry(1, true, false, 1, UNUSED),
                entry(2, false, true, 1, UNUSED), entry(3, false, false, 2, UNUSED),
                entry(4, false, false, 1, UNUSED), entry(5, false, false, 1, UNUSED));
        var selected = new ArrayList<>(List.of(5, 0, 4, 1, 2, 3));
        var cache = new CapturedStateSources<String>(); var b = budget();
        cache.retain(entries, selected, b); selected.clear();
        check(b.nodes() == 0 && b.searchWork() == 0 && b.cachePreparationWork() > 0 && b.reservedBytes() == 0, "Retention spent request quota / workspace");
        var saved = cache.get(entries, b); check(saved.equals(List.of(4, 5)), "Retained non-stateful slots or changed source order");
        try { saved.clear(); throw new AssertionError("Mutable retained indices"); } catch (UnsupportedOperationException expected) { checks.incrementAndGet(); }
        var replacement = new ArrayList<>(entries);
        check(cache.get(replacement, b) == null, "Equal-looking replacement capture inherited source indices");
        cache.retain(replacement, List.of(0), budget());
        check(cache.get(entries, b) == saved && cache.get(replacement, b) == null, "Foreign publication changed capture scope");
        cache.retain(entries, List.of(5, 4), budget());
        check(cache.get(entries, b) == saved, "Unchanged hint rebuilt its snapshot");
        check(b.searchWork() == 0 && b.compilationWork() > 0, "Lookup escaped compiler accounting");
    }

    private static void limits() {
        var entries = entries(256); var cache = new CapturedStateSources<String>();
        var first = new ArrayList<Integer>(); var second = new ArrayList<Integer>();
        for (int i = 0; i < 128; i++) { first.add(i); second.add(255 - i); }
        cache.retain(entries, first, budget()); check(cache.get(entries, budget()).equals(first), "Lost initial support");
        cache.retain(entries, second, budget());
        var saved = cache.get(entries, budget());
        check(saved.size() == 128 && saved.get(0) == 128 && saved.get(127) == 255, "Source cap / recent-support retention changed"); bounds(cache);
        var b = budget(); check(b.tryCacheCharge(PlanningBudget.CACHE_PREPARATION_WORK), "Cannot fill optional retention account");
        cache.retain(entries, first, b);
        check(cache.get(entries, budget()) == saved && b.nodes() == 0, "Refused retention changed state / spent search allowance");
        cache.retain(entries, List.of(-1), budget()); cache.retain(entries, List.of(256), budget());
        first.add(128); cache.retain(entries, first, budget());
        check(cache.get(entries, budget()) == saved, "Accepted invalid / oversized source indices");
        var choices = new ArrayList<CapturedRecipe.Candidate<String>>();
        for (int i = 0; i < 4097; i++) choices.add(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("key:" + i, 1), null, false));
        choices.add(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("tool", 1), "tool", false, false, UNUSED));
        var wide = List.of(new CapturedCatalog.Entry<>("wide", "wide", new CapturedRecipe<>(List.of(new CapturedRecipe.Input<>(1, choices)), List.of(new CapturedRecipe.Amount<>("out", 1)), false, false)));
        var declined = new CapturedStateSources<String>(); b = budget(); declined.retain(wide, List.of(0), b);
        check(declined.get(wide, budget()) == null && declined.metrics().bytes() == 0 && b.nodes() == 0, "Partial port scan published a source");
    }

    private static void cancellation() {
        var entries = entries(128); var cache = new CapturedStateSources<String>(); cache.retain(entries, List.of(0), budget());
        var saved = cache.get(entries, budget()); var calls = new AtomicInteger();
        var b = new PlanningBudget(0, 40_000_000, 64L << 20, () -> calls.incrementAndGet() >= 20, System::nanoTime);
        var selected = new ArrayList<Integer>(); for (int i = 0; i < 128; i++) selected.add(i);
        try { cache.retain(entries, selected, b); throw new AssertionError("Ignored mid-copy cancellation"); }
        catch (java.util.concurrent.CancellationException expected) { checks.incrementAndGet(); }
        check(cache.get(entries, budget()) == saved && b.reservedBytes() == 0 && b.nodes() == 0, "Cancelled publication changed scope / leaked");
    }

    private static void concurrent() throws Exception {
        var entries = entries(256); var cache = new CapturedStateSources<String>(); var pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Callable<Void>>();
            for (int worker = 0; worker < 4; worker++) {
                final int w = worker;
                tasks.add(() -> {
                    for (int n = 0; n < 40; n++) {
                        var b = budget(); cache.retain(entries, List.of((w * 64 + n) % 256), b);
                        var sources = cache.get(entries, b);
                        if (sources != null) {
                            int previous = -1;
                            for (int source : sources) { check(source > previous && source < entries.size(), "Concurrent snapshot lost original order / identity"); previous = source; }
                        }
                        check(b.searchWork() == 0 && b.reservedBytes() == 0, "Concurrent retention borrowed search resources"); bounds(cache);
                    }
                    return null;
                });
            }
            for (var future : pool.invokeAll(tasks)) future.get();
        } finally { pool.shutdownNow(); check(pool.awaitTermination(10, TimeUnit.SECONDS), "Retention workers did not finish"); }
    }

    private static List<CapturedCatalog.Entry<String>> entries(int count) {
        var entries = new ArrayList<CapturedCatalog.Entry<String>>();
        for (int i = 0; i < count; i++) entries.add(entry(i, false, false, 1, UNUSED)); return List.copyOf(entries);
    }
    private static CapturedCatalog.Entry<String> entry(int n, boolean configuration, boolean reusable, long amount, StateFamily<String> family) {
        var candidate = new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("tool:" + n, amount), "tool:" + n, configuration, reusable, family);
        return new CapturedCatalog.Entry<>("r" + n, "r" + n, new CapturedRecipe<>(List.of(new CapturedRecipe.Input<>(1, List.of(candidate))), List.of(new CapturedRecipe.Amount<>("out:" + n, 1)), false, false));
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 40_000_000, 64L << 20, () -> false, System::nanoTime); }
    private static void bounds(CapturedStateSources<?> cache) { var m = cache.metrics(); check(m.sources() <= 128 && m.bytes() <= CapturedStateSources.MAX_BYTES, "Unbounded retained source pool"); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks.incrementAndGet(); }
}
