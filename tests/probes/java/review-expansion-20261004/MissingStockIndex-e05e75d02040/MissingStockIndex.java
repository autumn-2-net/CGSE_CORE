package org.cgse.core;

import java.util.*;

/** Immutable all-source input incidence, owned by one exact catalog and recipe order. */
final class MissingStockIndex<K> {

    private static final int[] EMPTY = new int[0];
    private final List<GraphRecipe<K>> catalog;
    private final Map<K, int[]> consumers;

    private MissingStockIndex(List<GraphRecipe<K>> catalog, Map<K, int[]> consumers) {
        this.catalog = catalog;
        this.consumers = Collections.unmodifiableMap(consumers);
    }

    boolean belongsTo(List<GraphRecipe<K>> owner) {
        return catalog == owner;
    }

    /** Internal immutable adjacency; callers only read these recipe positions. */
    int[] consumers(K key) {
        return consumers.getOrDefault(key, EMPTY);
    }

    static final class Builder<K> implements AutoCloseable {

        private final List<GraphRecipe<K>> catalog;
        private final PlanningBudget budget;
        private final long allowance;
        private final Map<K, List<Integer>> building = new LinkedHashMap<>();
        private Map<K, int[]> frozen = new HashMap<>();
        private Iterator<Map.Entry<K, List<Integer>>> freezing;
        private MissingStockIndex<K> result;
        private int recipes, entries;
        private long memory;
        private boolean declined;

        Builder(List<GraphRecipe<K>> catalog, PlanningBudget budget) {
            this.catalog = catalog;
            this.budget = budget;
            allowance = Math.min(4L << 20, budget.availableBytes() / 8);
            if (catalog.size() > 8192 || !reserve(256L + 16L * catalog.size())) decline();
        }

        void add(GraphRecipe<K> recipe, int id) {
            if (declined) return;
            if (id != recipes || recipe != catalog.get(id)) throw new IllegalArgumentException("Catalog indexing order changed");
            recipes++;
            for (K key : recipe.inputs().keySet()) {
                budget.check();
                if (++entries > 32768 || !reserve(80)) {
                    decline();
                    return;
                }
                List<Integer> ids = building.get(key);
                if (ids == null) {
                    if (building.size() >= 8192 || !reserve(128)) {
                        decline();
                        return;
                    }
                    building.put(key, ids = new ArrayList<>());
                }
                ids.add(id);
            }
        }

        boolean step() {
            budget.check();
            if (declined || result != null) return true;
            if (recipes != catalog.size()) throw new IllegalStateException("Incomplete catalog index");
            if (freezing == null) freezing = building.entrySet().iterator();
            if (freezing.hasNext()) {
                var entry = freezing.next();
                if (!reserve(32L + 4L * entry.getValue().size())) {
                    decline();
                    return true;
                }
                int[] ids = new int[entry.getValue().size()];
                for (int i = 0; i < ids.length; i++) {
                    budget.check();
                    ids[i] = entry.getValue().get(i);
                }
                frozen.put(entry.getKey(), ids);
                return false;
            }
            result = new MissingStockIndex<>(catalog, frozen);
            frozen = null;
            close();
            return true;
        }

        MissingStockIndex<K> result() {
            return result;
        }

        private boolean reserve(long bytes) {
            if (bytes > allowance - memory || !budget.tryReserve(bytes)) return false;
            memory += bytes;
            return true;
        }

        private void decline() {
            declined = true;
            close();
        }

        @Override
        public void close() {
            building.clear();
            if (frozen != null) frozen.clear();
            freezing = null;
            budget.release(memory);
            memory = 0;
        }
    }
}
