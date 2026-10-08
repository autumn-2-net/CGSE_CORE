// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;
import java.util.function.Predicate;

/** Incremental catalog admission around a failed support. No negative inference leaves this view. */
final class GraphResidualSources<K> implements AutoCloseable {
    private final GraphCompiler<K> compiler;
    private final Map<K, Long> stock;
    private final Set<K> external;
    private final Set<String> excluded;
    private final Map<String, GraphRecipe<K>> pool;
    private final Predicate<GraphRecipe<K>> admit;
    private final PlanningBudget budget;
    private final Map<K, Boundary> boundaries = new LinkedHashMap<>();
    private final Deque<Boundary> pending = new ArrayDeque<>(), deferred = new ArrayDeque<>();
    private final List<Choice<K>> best = new ArrayList<>();
    private Boundary active;
    private int cursor, candidates, added, maximum;
    private long memory, scanned;

    private final class Boundary {
        final K key;
        Boundary(K key) { this.key = key; }
    }
    private record Choice<K>(GraphRecipe<K> recipe, int rawMissing, int unavailable, int covered, double pressure, int ordinal) {}

    GraphResidualSources(GraphCompiler<K> compiler, Map<K, Long> stock, Set<K> external, Set<String> excluded,
                         Map<String, GraphRecipe<K>> pool, Predicate<GraphRecipe<K>> admit, PlanningBudget budget) {
        this.compiler = compiler;
        this.stock = stock;
        this.external = external;
        this.excluded = excluded;
        this.pool = pool;
        this.admit = admit;
        this.budget = budget;
    }

    void offer(K key) {
        budget.operation(PlanningBudget.Operation.SCAN, 1);
        if (external.contains(key) || boundaries.containsKey(key) || compiler.producers(key).isEmpty()) return;
        long bytes = boundaries.isEmpty() ? 1280 : 256;
        if (boundaries.size() >= 512 || !budget.tryReserve(bytes)) return;
        memory += bytes;
        var boundary = new Boundary(key);
        boundaries.put(key, boundary);
        pending.addLast(boundary);
    }

    boolean pending() { return active != null || !pending.isEmpty() || !deferred.isEmpty(); }

    void begin(int maximum) {
        this.maximum = maximum;
        added = 0;
        pending.addAll(deferred);
        deferred.clear();
    }

    boolean step() {
        budget.checkpoint();
        if (added >= maximum) return true;
        if (active == null) {
            if (pending.isEmpty()) return true;
            active = pending.removeFirst();
            cursor = candidates = 0;
            best.clear();
        }
        var sources = compiler.producers(active.key);
        if (cursor < sources.size()) {
            budget.operation(PlanningBudget.Operation.SCAN, 1);
            var recipe = sources.get(cursor++);
            scanned++;
            if (pool.containsKey(recipe.id()) || excluded.contains(recipe.id())) return false;
            int rawMissing = 0, unavailable = 0, covered = 0;
            double pressure = 0;
            for (var input : recipe.inputs().entrySet()) {
                budget.operation(PlanningBudget.Operation.SCAN, 1);
                if (external.contains(input.getKey())) continue;
                long available = stock.getOrDefault(input.getKey(), 0L);
                if (available < input.getValue()) {
                    unavailable++;
                    if (compiler.producers(input.getKey()).isEmpty()) rawMissing++;
                }
                pressure += (double) input.getValue() / Math.max(1, available);
            }
            for (K output : recipe.executionOutputs().keySet()) {
                budget.operation(PlanningBudget.Operation.SCAN, 1);
                if (boundaries.containsKey(output)) covered++;
            }
            var choice = new Choice<>(recipe, rawMissing, unavailable, covered, pressure, cursor);
            candidates++;
            // A bounded page retains common/co-produced and stock-funded sources
            // without filling the count model with thousands of alternatives.
            // Omitted choices remain in the next page, not in a conflict cache.
            int at = 0;
            while (at < best.size() && compare(best.get(at), choice) <= 0) at++;
            if (at < 4) {
                best.add(at, choice);
                if (best.size() > 4) best.remove(4);
            }
            return false;
        }
        if (candidates > best.size()) deferred.addLast(active);
        for (var choice : best) {
            budget.operation(PlanningBudget.Operation.SCAN, 1);
            if (admit.test(choice.recipe())) {
                added++;
                for (K key : choice.recipe().inputs().keySet()) offer(key);
            }
        }
        active = null;
        best.clear();
        return false;
    }

    private static int compare(Choice<?> a, Choice<?> b) {
        int order = Integer.compare(a.rawMissing, b.rawMissing);
        if (order == 0) order = Integer.compare(b.covered, a.covered);
        if (order == 0) order = Integer.compare(a.unavailable, b.unavailable);
        if (order == 0) order = Double.compare(a.pressure, b.pressure);
        return order == 0 ? Integer.compare(a.ordinal, b.ordinal) : order;
    }

    long scanned() { return scanned; }
    int added() { return added; }

    @Override public void close() {
        pending.clear();
        deferred.clear();
        boundaries.clear();
        best.clear();
        active = null;
        budget.release(memory);
        memory = 0;
    }
}
