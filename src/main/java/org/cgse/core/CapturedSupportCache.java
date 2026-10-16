// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Positive source-selection hints for one immutable capture. No inventory or solver facts. */
final class CapturedSupportCache<K> {
    private static final int MAX_ENTRIES = 32, MAX_SEEDS = 64;
    static final long MAX_BYTES = 64L << 10;
    private record Hint<K>(K target, Set<K> seeds, List<CapturedCatalog.Entry<K>> sources, long bytes) {}
    private record Snapshot<K>(List<Hint<K>> hints, long bytes) {}
    record Metrics(long hits, long misses, int entries, long bytes, long maximumBytes) {}

    private final AtomicReference<Snapshot<K>> saved = new AtomicReference<>(new Snapshot<>(List.of(), 0));
    private final AtomicLong hits = new AtomicLong(), misses = new AtomicLong();

    List<CapturedCatalog.Entry<K>> get(K target, Set<K> seeds, PlanningBudget budget) {
        if (seeds.size() <= MAX_SEEDS) for (var hint : saved.get().hints) {
            budget.compilationScan();
            if (!hint.target.equals(target) || hint.seeds.size() != seeds.size()) continue;
            boolean same = true;
            for (K seed : seeds) {
                budget.compilationScan();
                if (!hint.seeds.contains(seed)) { same = false; break; }
            }
            if (same) {
                hits.updateAndGet(x -> x == Long.MAX_VALUE ? x : x + 1);
                return hint.sources;
            }
        }
        misses.updateAndGet(x -> x == Long.MAX_VALUE ? x : x + 1);
        return null;
    }

    void retain(K target, Set<K> seeds, List<CapturedCatalog.Entry<K>> sources, PlanningBudget budget) {
        if (sources == null || sources.isEmpty() || sources.size() > 128 || seeds.size() > MAX_SEEDS) return;
        var before = saved.get();
        // Copying the bounded scope and publishing references is optional work.
        // Lookup and all current-request deductions remain compilation/search.
        if (!budget.tryCacheCharge(1L + seeds.size() + sources.size() + before.hints.size())) return;
        for (var hint : before.hints) {
            if (!budget.tryCacheScan(1)) return;
            if (!hint.target.equals(target) || hint.seeds.size() != seeds.size()) continue;
            boolean same = true;
            for (K seed : seeds) {
                if (!budget.tryCacheScan(1)) return;
                if (!hint.seeds.contains(seed)) { same = false; break; }
            }
            if (same) return;
        }
        long bytes = 192L + 48L * seeds.size() + 16L * sources.size();
        var hints = new ArrayList<Hint<K>>();
        hints.add(new Hint<>(target, Set.copyOf(seeds), List.copyOf(sources), bytes));
        for (var hint : before.hints) {
            if (hints.size() == MAX_ENTRIES || hint.bytes > MAX_BYTES - bytes) break;
            hints.add(hint); bytes += hint.bytes;
        }
        // One bounded attempt. Concurrent publication can decline retention;
        // it cannot delay a request or invoke host key equality under a lock.
        saved.compareAndSet(before, new Snapshot<>(List.copyOf(hints), bytes));
    }

    Metrics metrics() {
        var snapshot = saved.get();
        return new Metrics(hits.get(), misses.get(), snapshot.hints.size(), snapshot.bytes, MAX_BYTES);
    }
}
