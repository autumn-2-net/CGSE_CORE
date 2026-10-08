package org.cgse.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Inventory-scoped compiler view, admitted only after a complete optimistic startup fixpoint. */
final class GraphStartupPruning<K> implements AutoCloseable {

    private final GraphCompiler<K> original;
    private final K target;
    private final PlanningBudget budget;
    private final long allowance;
    private final Map<K, Long> stock;
    private final Set<K> external, required;
    private final Set<String> excluded;
    private MissingStockAnalysis<K> analysis;
    private Set<String> unreachable;
    private final List<GraphRecipe<K>> recipes = new ArrayList<>();
    private final Map<K, List<GraphRecipe<K>>> sources = new LinkedHashMap<>();
    private Iterator<K> keys;
    private int cursor;
    private long work, memory, pruningMemory;
    private boolean complete;
    private GraphCompiler<K> result;

    GraphStartupPruning(GraphCompiler<K> compiler, K target, Map<K, Long> stock, Set<K> external,
                        Set<K> required, Set<String> excluded, PlanningBudget budget) {
        original = compiler;
        this.target = target;
        this.budget = budget;
        this.stock = stock;
        this.external = external;
        this.required = required;
        this.excluded = excluded;
        allowance = Math.min(262144, budget.remainingWork() / 16);
        result = original;
        if (allowance < 8192 || budget.proofJournal() != null) complete = true;
        else {
            var known = compiler.startupView(stock, external, required, excluded, budget);
            if (known != null && !known.compiler().producers(target).isEmpty() && budget.tryReserve(known.bytes())) {
                memory = known.bytes();
                result = known.compiler();
                complete = true;
                budget.note("startup_pruning", "snapshot_reused; recipes=" + compiler.catalog().size() + "->" + result.catalog().size());
            } else analysis = new MissingStockAnalysis<>(compiler, target, stock, external, required, excluded, false, budget);
        }
    }

    boolean step() {
        if (complete) return true;
        if (work >= allowance) return decline("work");
        long before = budget.threadWork();
        String failure = budget.failureDetail();
        try {
            return advance();
        } catch (PlanningBudget.Exhausted exhausted) {
            if (exhausted.limit() != PlanningBudget.Limit.MEMORY_LIMIT) throw exhausted;
            budget.failureDetail(failure);
            return decline("memory");
        } finally {
            work += budget.threadWork() - before;
        }
    }

    private boolean advance() {
        if (analysis != null) {
            if (!analysis.step()) return false;
            // Preserve the original material preview if even the optimistic
            // model cannot fund the target. Pruning is for search, not display.
            if (analysis.blocked()) return decline("target_blocked");
            unreachable = analysis.unreachableRecipes();
            analysis.close();
            analysis = null;
            if (unreachable.size() < Math.max(64, original.catalog().size() / 20) ||
                    original.producers(target).stream().allMatch(r -> unreachable.contains(r.id()))) return decline("little_reduction");
            pruningMemory = 128L + 96L * unreachable.size();
            reserve(pruningMemory);
            return false;
        }
        if (cursor < original.catalog().size()) {
            var recipe = original.catalog().get(cursor++);
            budget.operation(PlanningBudget.Operation.SCAN, 1);
            if (!unreachable.contains(recipe.id())) {
                reserve(32L + 16L * recipe.executionOutputs().size());
                recipes.add(recipe);
                for (K key : recipe.executionOutputs().keySet()) {
                    budget.operation(PlanningBudget.Operation.SCAN, 1);
                    if (!sources.containsKey(key)) {
                        reserve(128);
                        sources.put(key, null);
                    }
                }
            }
            return false;
        }
        if (keys == null) keys = sources.keySet().iterator();
        if (keys.hasNext()) {
            K key = keys.next();
            List<GraphRecipe<K>> kept = new ArrayList<>();
            for (var recipe : original.producers(key)) {
                budget.operation(PlanningBudget.Operation.SCAN, 1);
                if (!unreachable.contains(recipe.id())) kept.add(recipe);
            }
            reserve(48L + 16L * kept.size());
            sources.put(key, List.copyOf(kept));
            return false;
        }
        // Recipe identities, original provider priority, and all surviving
        // alternatives are preserved. No inventory fact enters original caches.
        result = new GraphCompiler<>(List.copyOf(recipes), Collections.unmodifiableMap(sources), original);
        complete = true;
        budget.note("startup_pruning", "recipes=" + original.catalog().size() + "->" + recipes.size() +
                "; impossible_startups=" + unreachable.size());
        unreachable = null;
        budget.release(pruningMemory);
        memory -= pruningMemory;
        pruningMemory = 0;
        original.rememberStartupView(stock, external, required, excluded, result, memory, budget);
        return true;
    }

    GraphCompiler<K> result() {
        if (!complete) throw new IllegalStateException("Startup analysis is incomplete");
        return result;
    }

    private void reserve(long bytes) {
        budget.reserve(bytes);
        memory += bytes;
    }

    private boolean decline(String reason) {
        budget.note("startup_pruning", "declined=" + reason + "; original_catalog_retained");
        result = original;
        complete = true;
        close();
        return true;
    }

    @Override
    public void close() {
        if (analysis != null) analysis.close();
        analysis = null;
        unreachable = null;
        if (result == original) {
            recipes.clear();
            sources.clear();
            keys = null;
        }
        // A completed compiler owns the same immutable map until its request
        // ends, so never mutate it here. Release only this owner's reservation.
        budget.release(memory);
        memory = 0;
        pruningMemory = 0;
    }
}
