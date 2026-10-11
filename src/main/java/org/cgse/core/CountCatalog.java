// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Immutable all-source closure in catalog recipe/material coordinates. Only
 * incidence and exact coefficients are retained; inventory, goals and branch
 * assumptions are overlays owned by the current request. The external supply
 * projection is part of the cache key, never inferred from current stock.
 * This must never be replaced by a selected-source compilation.
 */
final class CountCatalog<K> {

    final List<GraphRecipe<K>> recipes;
    final List<K> keys;
    final Map<K, Integer> ids;
    final List<Map<Integer, BigInteger>> terms;
    final Set<K> external;
    final long incidences;

    CountCatalog(RecipeCountModel<K> model, long entries, PlanningBudget budget) {
        recipes = List.copyOf(model.recipes);
        keys = List.copyOf(model.keys);
        ids = model.ids;
        external = Set.copyOf(model.external);
        var rows = new ArrayList<Map<Integer, BigInteger>>(Collections.nCopies(keys.size(), Map.of()));
        // Borrow only the immutable material coefficients already built by
        // the first request. Never retain its RHS, production obligation or
        // mutable constraint list, and do not rebuild a second cold matrix.
        for (int i = 0; i < model.rowKeys.size(); i++) {
            budget.checkpoint();
            rows.set(ids.get(model.rowKeys.get(i)), model.constraints.get(i).terms());
        }
        terms = List.copyOf(rows);
        incidences = entries;
    }

    long weight() {
        return incidences + recipes.size() + keys.size() + external.size();
    }

    long sparseBytes() {
        return 128L * weight();
    }

    /**
     * Borrow unaffected rows only after matching recipe identities at their
     * current column positions. Changed, inserted, removed or moved recipes
     * invalidate every material they touch, including net-zero catalysts.
     * No RHS, domain, source-completeness claim or proof crosses requests.
     */
    Map<K, Map<Integer, BigInteger>> unchangedRows(List<GraphRecipe<K>> current, List<K> currentKeys,
                                                Set<K> supplied, PlanningBudget budget) {
        int columns = Math.max(recipes.size(), current.size());
        // Only inspect a donor when enough work remains to profit from it.
        if (columns == 0 || incidences < 4L * columns || columns > budget.remainingWork() / 32) return Map.of();
        int unchanged = 0;
        for (int i = 0; i < Math.min(recipes.size(), current.size()); i++) {
            budget.check();
            if (recipes.get(i) == current.get(i)) unchanged++;
        }
        if (unchanged * 4L < columns * 3L) return Map.of();
        Set<K> changed = new HashSet<>();
        for (int i = 0; i < columns; i++) {
            budget.check();
            var before = i < recipes.size() ? recipes.get(i) : null;
            var after = i < current.size() ? current.get(i) : null;
            if (before == after) continue;
            for (var recipe : Arrays.asList(before, after)) if (recipe != null) {
                for (K key : recipe.inputs().keySet()) { budget.check(); changed.add(key); }
                for (K key : recipe.outputs().keySet()) { budget.check(); changed.add(key); }
            }
        }
        var shared = new LinkedHashMap<K, Map<Integer, BigInteger>>();
        long saved = 0;
        int rebuilt = 0;
        for (K key : currentKeys) {
            budget.check();
            if (supplied.contains(key)) continue;
            Integer id = ids.get(key);
            if (id == null || external.contains(key) || changed.contains(key)) { rebuilt++; continue; }
            shared.put(key, terms.get(id));
            saved += terms.get(id).size();
        }
        // Reconstructing affected rows uses direct recipe lookups. If that
        // rectangular scan costs more than the sparse pass, keep the cold path.
        if (saved <= (long) rebuilt * current.size() + columns + currentKeys.size()) return Map.of();
        return shared;
    }
}
