// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An observed exact word, scoped to one unchanged auxiliary variant. Tool
 * pools must recur in iteration order as well as quantity: allocation ties
 * may depend on that order. Ordinary stock is guarded by exact prefix needs. */
final class StateRestorationCycle<K> implements AutoCloseable {
    private record Entity<K>(K key, BigInteger count) {}
    private record Pool<K>(String family, List<Entity<K>> entities) {}

    private final String anchor;
    private final PlanningBudget budget;
    private final List<Pool<K>> pools = new ArrayList<>();
    private final List<PlanStep> word = new ArrayList<>();
    private final Map<K, BigInteger> required = new LinkedHashMap<>(), delta = new LinkedHashMap<>();
    private long memory;

    private StateRestorationCycle(String anchor, PlanningBudget budget) { this.anchor = anchor; this.budget = budget; }

    static <K> StateRestorationCycle<K> start(String anchor, Map<String, Map<K, BigInteger>> owned, PlanningBudget budget) {
        var cycle = new StateRestorationCycle<K>(anchor, budget);
        try {
            long bytes = 256;
            for (var pool : owned.values()) {
                budget.check();
                bytes = Math.addExact(bytes, 96L + 128L * pool.size());
            }
            if (!cycle.reserve(bytes)) { cycle.close(); return null; }
            for (var pool : owned.entrySet()) {
                var entities = new ArrayList<Entity<K>>();
                for (var entity : pool.getValue().entrySet()) {
                    budget.check(); entities.add(new Entity<>(entity.getKey(), entity.getValue()));
                }
                cycle.pools.add(new Pool<>(pool.getKey(), List.copyOf(entities)));
            }
            return cycle;
        } catch (RuntimeException | Error failure) { cycle.close(); throw failure; }
    }

    /** Only already executed exact firings enter this word. Reusable
     * configurations are required but absent from spent; consumed ones remain
     * charged once for every serial firing, including within a repeated word. */
    boolean record(String transition, Map<K, BigInteger> needed, Map<K, BigInteger> spent, Map<K, BigInteger> produced) {
        if (!reserve(96)) return false;
        word.add(new PlanStep.Batch(transition, 1));
        for (var value : needed.entrySet()) {
            budget.check(); if (!key(value.getKey())) return false;
            required.put(value.getKey(), required.get(value.getKey()).max(value.getValue().subtract(delta.get(value.getKey()))));
        }
        for (var value : spent.entrySet()) {
            budget.check(); if (!key(value.getKey())) return false;
            delta.put(value.getKey(), delta.get(value.getKey()).subtract(value.getValue()));
        }
        for (var value : produced.entrySet()) {
            budget.check(); if (!key(value.getKey())) return false;
            delta.put(value.getKey(), delta.get(value.getKey()).add(value.getValue()));
        }
        return true;
    }

    boolean matches(String transition, Map<String, Map<K, BigInteger>> owned) {
        if (word.isEmpty() || !anchor.equals(transition) || owned.size() != pools.size()) return false;
        var rows = owned.entrySet().iterator();
        for (var expected : pools) {
            budget.check();
            var actual = rows.next();
            if (!expected.family().equals(actual.getKey()) || expected.entities().size() != actual.getValue().size()) return false;
            var entities = actual.getValue().entrySet().iterator();
            for (var wanted : expected.entities()) {
                budget.check(); var got = entities.next();
                if (!wanted.key().equals(got.getKey()) || !wanted.count().equals(got.getValue())) return false;
            }
        }
        return true;
    }

    long copies(long remainingFirings, Map<K, BigInteger> free, Set<K> external) {
        if (word.isEmpty()) return 0;
        long copies = remainingFirings / word.size();
        for (var value : required.entrySet()) {
            budget.check();
            K key = value.getKey();
            if (external.contains(key)) continue;
            BigInteger available = free.getOrDefault(key, BigInteger.ZERO);
            if (available.compareTo(value.getValue()) < 0) return 0;
            BigInteger change = delta.get(key);
            if (change.signum() < 0) copies = Math.min(copies,
                    ExactAmounts.capped(available.subtract(value.getValue()).divide(change.negate()).add(BigInteger.ONE)));
        }
        return copies;
    }

    void apply(long copies, Map<K, BigInteger> free) {
        BigInteger count = BigInteger.valueOf(copies);
        for (var value : delta.entrySet()) {
            budget.check();
            free.put(value.getKey(), free.getOrDefault(value.getKey(), BigInteger.ZERO).add(value.getValue().multiply(count)).max(BigInteger.ZERO));
        }
    }

    int firings() { return word.size(); }
    PlanStep word() { return new PlanStep.Sequence(word); }

    private boolean key(K key) {
        if (delta.containsKey(key)) return true;
        if (!reserve(256)) return false;
        required.put(key, BigInteger.ZERO); delta.put(key, BigInteger.ZERO); return true;
    }
    private boolean reserve(long bytes) {
        if (!budget.tryReserve(bytes)) return false;
        memory += bytes; return true;
    }
    @Override public void close() {
        pools.clear(); word.clear(); required.clear(); delta.clear();
        budget.release(memory); memory = 0;
    }
}
