// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Refines a concrete refill witness by exact backward prefix requirements.
 * No forward inventory loans, depth cutoff, or recipe-count cutoff establish a leaf.
 * Existing execution words, physical bindings and seed obligations are retained.
 */
final class MissingMaterialExpansion<K> implements AutoCloseable {
    private final GraphPlan<K> original;
    private final GraphCompiler<K> compiler;
    private final Map<K, Long> stock;
    private final Set<K> external;
    private final Set<String> excluded;
    private final boolean forceCraft, ignoreTargetStock;
    private final PlanningBudget budget;
    private final Map<K, BigInteger> required = new LinkedHashMap<>();
    private final Map<String, GraphRecipe<K>> recipes = new LinkedHashMap<>();
    private final List<PlanStep> prefix = new ArrayList<>();
    private final Set<K> queued = new HashSet<>();
    private RefillProducerOrder<K> order;
    private PriorityQueue<K> pending;
    private PlanVerification<K> verification;
    private GraphPlan<K> candidate, result;
    private int phase;
    private long memory;
    private BigInteger targetDelta = BigInteger.ZERO;

    MissingMaterialExpansion(GraphCompiler<K> compiler, GraphPlan<K> original, Map<K, Long> stock,
                             Set<K> external, Set<String> excluded, boolean forceCraft, boolean ignoreTargetStock, PlanningBudget budget) {
        this.compiler = compiler; this.original = original; this.stock = stock; this.external = external;
        this.excluded = excluded; this.forceCraft = forceCraft; this.ignoreTargetStock = ignoreTargetStock; this.budget = budget;
    }
    static <K> boolean needed(GraphCompiler<K> compiler, GraphPlan<K> plan) {
        if (plan.feasible()) return false;
        // Only a cheap index lookup here. Source inspection belongs to the
        // budgeted continuation, including rejected/excluded alternatives.
        for (K key : plan.missingExact().keySet()) if (!compiler.producers(key).isEmpty()) return true;
        return false;
    }
    boolean step() {
        budget.check();
        if (phase == 0) {
            reserve(256L + 128L * original.initialExact().size() + 96L * original.recipes().size());
            required.putAll(original.initialExact()); recipes.putAll(original.recipes());
            order = new RefillProducerOrder<>(compiler, original.missingExact().keySet(), external, excluded, budget);
            phase = 1; return false;
        }
        if (phase == 1) {
            if (!order.step()) return false;
            pending = new PriorityQueue<>(Comparator.<K>comparingInt(order::rank).reversed());
            for (K key : required.keySet()) { budget.check(); enqueue(key); }
            phase = 2; return false;
        }
        if (phase == 2) {
            if (pending.isEmpty()) { phase = 3; return false; }
            K key = pending.remove(); queued.remove(key);
            if (deficit(key, required.getOrDefault(key, BigInteger.ZERO), targetDelta).signum() == 0) return false;
            var node = order.get(key);
            if (node == null || node.sources.isEmpty()) return false;
            Proposal selected = null;
            try {
                // Stable source priority remains the tie-break for incomparable
                // supplies. A strict componentwise improvement may replace it.
                for (var source : node.sources) {
                    budget.check(); var recipe = source.recipe;
                    GraphRecipe<K> existing = recipes.get(recipe.id());
                    if (existing != null && !existing.equals(recipe)) continue;
                    BigInteger count = CheckedAmounts.ceilDiv(deficit(key, required.getOrDefault(key, BigInteger.ZERO), targetDelta), gain(recipe, key));
                    selected = prefer(selected, proposal(recipe, key, count));
                    BigInteger plateau = plateau(recipe, key, count);
                    if (!plateau.equals(count)) selected = prefer(selected, proposal(recipe, key, plateau));
                    if (selected != null && selected.funded) break;
                }
                if (selected == null) return false;
                reserve(160L + 128L * (selected.recipe.inputs().size() + selected.recipe.outputs().size()));
                targetDelta = selected.delta;
                // Prepending changes requirements only on this recipe's support.
                // Reverse prefix order at publication, without copying the old word.
                for (var entry : selected.needs.entrySet()) {
                    budget.check();
                    if (entry.getValue().signum() == 0) required.remove(entry.getKey());
                    else required.put(entry.getKey(), entry.getValue());
                    enqueue(entry.getKey());
                }
                recipes.put(selected.recipe.id(), selected.recipe);
                prefix.add(PlanStep.batch(selected.recipe.id(), selected.runs));
            } finally { if (selected != null) selected.close(); }
            return false;
        }
        if (phase == 3) {
            if (prefix.isEmpty()) { result = original; return true; }
            if (forceCraft && targetDelta.signum() < 0) throw new IllegalStateException("Refill lost verified production credit");
            var steps = new ArrayList<PlanStep>(prefix.size() + 1);
            for (int i = prefix.size() - 1; i >= 0; i--) { budget.check(); steps.add(prefix.get(i)); }
            steps.add(original.steps());
            var deficits = new LinkedHashMap<K, BigInteger>();
            for (var entry : required.entrySet()) {
                budget.check(); BigInteger value = missing(entry.getKey(), entry.getValue());
                if (value.signum() > 0) deficits.put(entry.getKey(), value);
            }
            candidate = new GraphPlan<>(original.target(), original.amount(), original.preserveSeeds(),
                    new PlanStep.Sequence(steps), recipes, required, original.seeds(), deficits,
                    deficits.isEmpty() ? GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL : original.result(),
                    budget.nodes(), original.planningNanos());
            var funded = new GraphPlan<>(candidate.target(), candidate.amount(), candidate.preserveSeeds(),
                    candidate.steps(), candidate.recipes(), candidate.initialExact(), candidate.seeds(), Map.of(),
                    GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, budget.nodes(), candidate.planningNanos());
            verification = new PlanVerification<>(funded, budget); phase = 4; return false;
        }
        if (!verification.step()) return false;
        result = candidate;
        budget.note("missing_expansion", "backward_requirements; prefixes=" + prefix.size() + "; missing_keys=" + result.missingExact().size());
        return true;
    }
    private void enqueue(K key) {
        var node = order.get(key);
        if (node != null && !node.sources.isEmpty() && deficit(key, required.getOrDefault(key, BigInteger.ZERO), targetDelta).signum() > 0 && queued.add(key))
            pending.add(key);
    }
    private BigInteger missing(K key, BigInteger need) {
        if (external.contains(key)) return BigInteger.ZERO;
        return need.subtract(BigInteger.valueOf(available(key))).max(BigInteger.ZERO);
    }
    private long available(K key) {
        return ignoreTargetStock && original.target().equals(key) ? 0 : stock.getOrDefault(key, 0L);
    }
    private BigInteger deficit(K key, BigInteger need, BigInteger prefixDelta) {
        BigInteger value = missing(key, need);
        // A prefix may use target stock to prepare a cyclic input, but must
        // replace that consumption. Preserve the verified suffix's production
        // credit instead of treating existing finished goods as new crafting.
        return forceCraft && original.target().equals(key) ? value.max(prefixDelta.negate()) : value;
    }
    private BigInteger gain(GraphRecipe<K> recipe, K key) {
        return BigInteger.valueOf(recipe.outputs().getOrDefault(key, 0L)).subtract(BigInteger.valueOf(recipe.inputs().getOrDefault(key, 0L)));
    }
    private Set<K> support(GraphRecipe<K> recipe) {
        var keys = new LinkedHashSet<>(recipe.inputs().keySet()); keys.addAll(recipe.outputs().keySet()); return keys;
    }
    private BigInteger before(GraphRecipe<K> recipe, K key, BigInteger runs) {
        BigInteger delta = gain(recipe, key);
        BigInteger first = BigInteger.valueOf(recipe.inputs().getOrDefault(key, 0L))
                .add(delta.negate().max(BigInteger.ZERO).multiply(runs.subtract(BigInteger.ONE)));
        return first.max(required.getOrDefault(key, BigInteger.ZERO).subtract(delta.multiply(runs)));
    }
    /** Keep the old count too: its extra firings may supply a useful coproduct. */
    private BigInteger plateau(GraphRecipe<K> recipe, K key, BigInteger old) {
        long input = recipe.inputs().getOrDefault(key, 0L), have = available(key);
        if (input <= have) return old;
        BigInteger delta = gain(recipe, key);
        BigInteger gap = required.getOrDefault(key, BigInteger.ZERO).subtract(BigInteger.valueOf(input)).max(BigInteger.ZERO);
        BigInteger runs = CheckedAmounts.ceilDiv(gap, delta).max(BigInteger.ONE);
        if (forceCraft && original.target().equals(key))
            runs = runs.max(CheckedAmounts.ceilDiv(targetDelta.negate().max(BigInteger.ZERO), delta));
        return runs;
    }

    private final class Proposal implements AutoCloseable {
        final GraphRecipe<K> recipe;
        final BigInteger runs, delta;
        final Map<K, BigInteger> needs = new LinkedHashMap<>();
        boolean funded = true;
        long bytes;
        Proposal(GraphRecipe<K> recipe, BigInteger runs, BigInteger delta) {
            this.recipe = recipe; this.runs = runs; this.delta = delta;
        }
        BigInteger need(K key) { return needs.getOrDefault(key, required.getOrDefault(key, BigInteger.ZERO)); }
        @Override public void close() { budget.release(bytes); bytes = 0; }
    }

    /** Evaluate all support before mutating the retained suffix requirements. */
    private Proposal proposal(GraphRecipe<K> recipe, K key, BigInteger runs) {
        BigInteger nextDelta = targetDelta.add(gain(recipe, original.target()).multiply(runs));
        BigInteger old = deficit(key, required.getOrDefault(key, BigInteger.ZERO), targetDelta);
        if (deficit(key, before(recipe, key, runs), nextDelta).compareTo(old) >= 0) return null;
        var proposal = new Proposal(recipe, runs, nextDelta);
        boolean accepted = false;
        try {
            long bytes = 192L + 128L * ((long) recipe.inputs().size() + recipe.outputs().size());
            budget.reserve(bytes); proposal.bytes = bytes;
            for (K input : support(recipe)) {
                budget.check();
                BigInteger need = before(recipe, input, runs);
                proposal.needs.put(input, need);
                BigInteger previous = deficit(input, required.getOrDefault(input, BigInteger.ZERO), targetDelta);
                BigInteger next = deficit(input, need, nextDelta);
                if (next.compareTo(previous) > 0) {
                    proposal.funded = false;
                    if (order.rank(input) >= order.rank(key)) return null;
                }
            }
            accepted = true;
            return proposal;
        } finally { if (!accepted) proposal.close(); }
    }

    private Proposal prefer(Proposal selected, Proposal candidate) {
        if (candidate == null) return selected;
        try {
            if (selected == null || candidate.funded && !selected.funded || dominates(candidate, selected)) {
                if (selected != null) selected.close();
                return candidate;
            }
        } catch (RuntimeException | Error failure) { candidate.close(); throw failure; }
        candidate.close();
        return selected;
    }

    private boolean dominates(Proposal candidate, Proposal selected) {
        // Retain production credit as well as the full requirement vector;
        // unchanged missing totals can otherwise hide spending extra stock or
        // losing credit needed by a later prefix. No item/fluid scalar prices.
        if (forceCraft && candidate.delta.compareTo(selected.delta) < 0) return false;
        boolean strict = false;
        for (K key : candidate.needs.keySet()) {
            budget.check();
            int comparison = candidate.need(key).compareTo(selected.need(key));
            if (comparison > 0) return false;
            strict |= comparison < 0;
        }
        for (K key : selected.needs.keySet()) {
            budget.check();
            if (candidate.needs.containsKey(key)) continue;
            int comparison = candidate.need(key).compareTo(selected.need(key));
            if (comparison > 0) return false;
            strict |= comparison < 0;
        }
        return strict;
    }
    GraphPlan<K> result() { return result; }
    private void reserve(long bytes) { budget.reserve(bytes); memory += bytes; }
    @Override public void close() {
        if (order != null) order.close();
        if (verification != null) verification.close();
        budget.release(memory); memory = 0;
    }
}
