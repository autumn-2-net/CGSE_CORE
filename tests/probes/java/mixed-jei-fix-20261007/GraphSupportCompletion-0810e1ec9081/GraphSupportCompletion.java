package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Bounded producer completion of a failed support; supplies witnesses, never exclusions. */
final class GraphSupportCompletion<K> implements AutoCloseable {

    private final GraphCompiler<K> compiler;
    private final Map<K, Long> stock;
    private final Set<K> external;
    private final Set<String> excluded;
    private final PlanningBudget budget;
    private final Map<String, GraphRecipe<K>> recipes = new LinkedHashMap<>();
    private final Map<K, BigInteger> needs = new LinkedHashMap<>();
    private final ArrayDeque<K> pending = new ArrayDeque<>();
    private final Set<K> expanded = new HashSet<>();
    private long memory, work, started;
    private int levels;

    GraphSupportCompletion(GraphCompiler<K> compiler, Map<K, Long> stock, Set<K> external,
                           Set<String> excluded, PlanningBudget budget) {
        this.compiler = compiler;
        this.stock = stock;
        this.external = external;
        this.excluded = excluded;
        this.budget = budget;
    }

    List<GraphRecipe<K>> complete(GraphCompiler.Compiled<K> graph, GraphPlan<K> candidate, K target, long amount) {
        started = budget.threadSearchWork();
        try {
            if (!reserve(1024)) return List.of();
            for (String id : candidate.patternTimesExact().keySet()) {
                if (!scan()) return List.of();
                add(graph.recipes().get(id));
            }
            enqueue(target, BigInteger.valueOf(amount));
            for (var missing : candidate.missingExact().entrySet()) {
                if (!scan()) return List.of();
                enqueue(missing.getKey(), missing.getValue().add(BigInteger.valueOf(stock.getOrDefault(missing.getKey(), 0L))));
            }
            // A missing frontier can be several producers away from its input
            // stock. Complete successive graph layers instead of only adding
            // the producer selected by the failed view.
            while (!pending.isEmpty() && levels++ < 6 && recipes.size() < 192) {
                int size = pending.size();
                for (int i = 0; i < size; i++) {
                    if (!scan()) return List.copyOf(recipes.values());
                    K key = pending.removeFirst();
                    if (!expanded.add(key)) continue;
                    var sources = choose(key);
                    for (var recipe : sources) {
                        if (!scan()) return List.copyOf(recipes.values());
                        add(recipe);
                        if (!recipes.containsKey(recipe.id())) continue;
                        BigInteger output = BigInteger.valueOf(Math.max(1, recipe.executionOutputs().getOrDefault(key, 1L)));
                        BigInteger demand = needs.get(key).subtract(BigInteger.valueOf(key.equals(target) ? 0 : stock.getOrDefault(key, 0L))).max(BigInteger.ONE);
                        BigInteger runs = demand.add(output).subtract(BigInteger.ONE).divide(output);
                        for (var input : recipe.inputs().entrySet()) {
                            if (!scan()) return List.copyOf(recipes.values());
                            BigInteger needed = BigInteger.valueOf(input.getValue()).multiply(runs);
                            if (!external.contains(input.getKey()) && needed.compareTo(BigInteger.valueOf(stock.getOrDefault(input.getKey(), 0L))) > 0)
                                enqueue(input.getKey(), needed);
                        }
                    }
                }
            }
            return List.copyOf(recipes.values());
        } finally {
            work += budget.threadSearchWork() - started;
        }
    }

    private List<GraphRecipe<K>> choose(K key) {
        // Keep several different producers, including the old support. A
        // relaxed score only picks a small subproblem, never removes a source
        // from the authoritative catalog.
        var best = new ArrayList<GraphRecipe<K>>();
        var scores = new ArrayList<Double>();
        int examined = 0;
        for (var recipe : compiler.producers(key)) {
            if (!scan() || examined++ >= 256) break;
            if (excluded.contains(recipe.id())) continue;
            double score = 0;
            double output = Math.max(1, recipe.executionOutputs().getOrDefault(key, 0L) - recipe.inputs().getOrDefault(key, 0L));
            double batches = Math.max(1, Math.ceil(needs.get(key).doubleValue() / output));
            for (var input : recipe.inputs().entrySet()) {
                if (!scan()) return best;
                if (external.contains(input.getKey())) continue;
                double required = batches * input.getValue();
                long available = stock.getOrDefault(input.getKey(), 0L);
                if (required > available) {
                    score += 1 + Math.log1p(required / Math.max(1, available));
                    if (compiler.producers(input.getKey()).isEmpty()) score += 1024;
                    if (expanded.contains(input.getKey())) score += 16;
                }
            }
            int at = 0;
            while (at < scores.size() && scores.get(at) <= score) at++;
            if (at < 3) {
                best.add(at, recipe);
                scores.add(at, score);
                if (best.size() > 3) { best.remove(3); scores.remove(3); }
            }
        }
        return best;
    }

    private boolean add(GraphRecipe<K> recipe) {
        if (recipe == null || recipes.containsKey(recipe.id()) || excluded.contains(recipe.id()) || recipes.size() >= 192) return false;
        if (!reserve(256L + 96L * (recipe.inputs().size() + (long) recipe.outputs().size()))) return false;
        recipes.put(recipe.id(), recipe);
        return true;
    }

    private void enqueue(K key, BigInteger need) {
        if (expanded.contains(key) || external.contains(key)) return;
        BigInteger old = needs.get(key);
        if (old != null) { needs.put(key, old.max(need)); return; }
        if (!reserve(192L + need.bitLength() / 8)) return;
        needs.put(key, need);
        pending.addLast(key);
    }

    private boolean scan() {
        if (work + budget.threadSearchWork() - started >= 16384) return false;
        budget.operation(PlanningBudget.Operation.SCAN, 1);
        return true;
    }

    private boolean reserve(long bytes) {
        if (bytes > (2L << 20) - memory || !budget.tryReserve(bytes)) return false;
        memory += bytes;
        return true;
    }

    @Override
    public void close() { budget.release(memory); memory = 0; }
}
