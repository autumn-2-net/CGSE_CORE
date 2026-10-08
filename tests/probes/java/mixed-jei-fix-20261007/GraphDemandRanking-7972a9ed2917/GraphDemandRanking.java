package org.cgse.core;

import java.util.*;

/** Bounded AND/OR lookahead for a positive source dive, with finite stock credit. */
final class GraphDemandRanking<K> {

    private final GraphCompiler<K> compiler;
    private final Map<K, Long> stock;
    private final Set<K> external;
    private final PlanningBudget budget;
    private long first, allowance, memory;
    private final Map<Demand<K>, Double> memo = new HashMap<>();
    private final Set<K> path = new HashSet<>();

    private record Demand<K>(K key, long amount, int depth) {}
    private record Source<K>(GraphRecipe<K> recipe, double cost) {}

    GraphDemandRanking(GraphCompiler<K> compiler, Map<K, Long> stock, Set<K> external, PlanningBudget budget) {
        this.compiler = compiler;
        this.stock = stock;
        this.external = external;
        this.budget = budget;
    }

    List<GraphRecipe<K>> sources(K key, long needed, long maximumWork) {
        var original = compiler.producers(key);
        if (original.size() < 2 || maximumWork < 1024) return original;
        first = budget.threadSearchWork();
        allowance = Math.min(32768, maximumWork);
        try {
            reserve(512L + 96L * original.size());
            path.add(key);
            var ordered = new ArrayList<Source<K>>();
            for (var recipe : original) {
                check();
                ordered.add(new Source<>(recipe, score(recipe, key, needed, 4)));
            }
            ordered.sort((a, b) -> {
                check();
                return Double.compare(a.cost(), b.cost());
            });
            return ordered.stream().map(Source::recipe).toList();
        } catch (Stopped ignored) {
            return original;
        } finally {
            memo.clear();
            path.clear();
            budget.release(memory);
            memory = 0;
        }
    }

    private double cost(K key, long amount, int depth) {
        check();
        if (external.contains(key)) return 0;
        long held = stock.getOrDefault(key, 0L);
        if (held >= amount) return 0;
        long needed = amount - held;
        if (path.contains(key)) return 1_000_000;
        if (depth == 0) return 1024 + Math.min(1024, needed / (1.0 + held));
        var demand = new Demand<>(key, needed, depth);
        var known = memo.get(demand);
        if (known != null) return known;
        reserve(192);
        double best = 1_000_000;
        path.add(key);
        try {
            for (var recipe : compiler.producers(key)) {
                check();
                best = Math.min(best, score(recipe, key, needed, depth - 1));
                if (best == 1) break;
            }
        } finally {
            path.remove(key);
        }
        // These are only ordering hints; an ancestry-dependent estimate may
        // overestimate another path. All providers stay in the original search.
        memo.put(demand, best);
        return best;
    }

    private double score(GraphRecipe<K> recipe, K key, long needed, int depth) {
        long gain = recipe.executionOutputs().getOrDefault(key, 0L) - recipe.inputs().getOrDefault(key, 0L);
        if (gain <= 0) return 2_000_000;
        long runs = needed / gain + (needed % gain == 0 ? 0 : 1);
        double sum = 1;
        for (var input : recipe.inputs().entrySet()) {
            check();
            long perRun = input.getValue() - recipe.configurationInputs().getOrDefault(input.getKey(), 0L);
            long fixed = input.getValue() - perRun;
            long required = perRun != 0 && runs > (Long.MAX_VALUE - fixed) / perRun ? Long.MAX_VALUE : runs * perRun + fixed;
            sum += cost(input.getKey(), required, depth);
        }
        return sum;
    }

    private void reserve(long bytes) {
        if (bytes > (1L << 20) - memory || !budget.tryReserve(bytes)) throw Stopped.INSTANCE;
        memory += bytes;
    }

    private void check() {
        budget.checkpoint();
        if (budget.threadSearchWork() - first >= allowance) throw Stopped.INSTANCE;
        budget.operation(PlanningBudget.Operation.SCAN, 1);
    }

    private static final class Stopped extends RuntimeException {
        private static final Stopped INSTANCE = new Stopped();
        private Stopped() { super(null, null, false, false); }
    }
}
