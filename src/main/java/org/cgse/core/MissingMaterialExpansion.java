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
    private static final int OPERATIONS_PER_STEP = 64;
    private enum Phase { START, REQUIREMENTS, RECIPES, RANK, QUEUE, SELECT, SOURCE, SUPPORT, COMPARE, APPLY, STEPS, DEFICITS, PUBLISH, VERIFY, DONE }
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
    private Phase phase = Phase.START;
    private Iterator<Map.Entry<K, BigInteger>> entries;
    private Iterator<Map.Entry<String, GraphRecipe<K>>> bindings;
    private Iterator<K> keys;
    private Iterator<RefillProducerOrder<K>.Source> sources;
    private K expanding;
    private GraphRecipe<K> source;
    private BigInteger plateauRuns;
    private Proposal selected, proposal;
    private boolean outputSupport, comparingSelected, strict;
    private List<PlanStep> steps;
    private Map<K, BigInteger> deficits;
    private int prefixIndex;
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
        // Do not put a complete child slice after this pass's own operations.
        if (phase == Phase.RANK) {
            if (!order.step()) return false;
            pending = new PriorityQueue<>(Comparator.<K>comparingInt(order::rank).reversed());
            keys = required.keySet().iterator(); phase = Phase.QUEUE; return false;
        }
        if (phase == Phase.VERIFY) {
            if (!verification.step()) return false;
            result = candidate; phase = Phase.DONE;
            budget.note("missing_expansion", "backward_requirements; prefixes=" + prefix.size() + "; missing_keys=" + result.missingExact().size());
            return true;
        }
        for (int i = 0; i < OPERATIONS_PER_STEP; i++) {
            budget.check();
            if (advance()) return true;
            if (phase == Phase.RANK || phase == Phase.VERIFY) return false;
        }
        return false;
    }
    private boolean advance() {
        switch (phase) {
            case START -> {
                reserve(256L + 128L * original.initialExact().size() + 96L * original.recipes().size());
                entries = original.initialExact().entrySet().iterator(); phase = Phase.REQUIREMENTS;
            }
            case REQUIREMENTS -> {
                if (entries.hasNext()) { var entry = entries.next(); required.put(entry.getKey(), entry.getValue()); }
                else { entries = null; bindings = original.recipes().entrySet().iterator(); phase = Phase.RECIPES; }
            }
            case RECIPES -> {
                if (bindings.hasNext()) { var entry = bindings.next(); recipes.put(entry.getKey(), entry.getValue()); }
                else {
                    bindings = null;
                    order = new RefillProducerOrder<>(compiler, original.missingExact().keySet(), external, excluded, budget);
                    phase = Phase.RANK;
                }
            }
            case QUEUE -> {
                if (keys.hasNext()) enqueue(keys.next());
                else { keys = null; phase = Phase.SELECT; }
            }
            case SELECT -> {
                if (pending.isEmpty()) {
                    if (prefix.isEmpty()) { result = original; phase = Phase.DONE; return true; }
                    if (forceCraft && targetDelta.signum() < 0) throw new IllegalStateException("Refill lost verified production credit");
                    steps = new ArrayList<>(); prefixIndex = prefix.size(); phase = Phase.STEPS;
                } else {
                    expanding = pending.remove(); queued.remove(expanding);
                    if (deficit(expanding, required.getOrDefault(expanding, BigInteger.ZERO), targetDelta).signum() == 0) break;
                    var node = order.get(expanding);
                    if (node == null || node.sources.isEmpty()) break;
                    sources = node.sources.iterator(); phase = Phase.SOURCE;
                }
            }
            case SOURCE -> {
                // Evaluate both counts before the funded-source early exit.
                // A plateau must not skip its old count's useful coproducts.
                if (plateauRuns != null) {
                    BigInteger runs = plateauRuns; plateauRuns = null; beginProposal(runs);
                } else if (selected != null && selected.funded || !sources.hasNext()) {
                    sources = null; source = null;
                    if (selected == null) phase = Phase.SELECT;
                    else {
                        reserve(160L + 128L * ((long) selected.recipe.inputs().size() + selected.recipe.outputs().size()));
                        targetDelta = selected.delta; entries = selected.needs.entrySet().iterator(); phase = Phase.APPLY;
                    }
                } else {
                    source = sources.next().recipe;
                    GraphRecipe<K> existing = recipes.get(source.id());
                    if (existing != null && !existing.equals(source)) break;
                    BigInteger count = CheckedAmounts.ceilDiv(deficit(expanding, required.getOrDefault(expanding, BigInteger.ZERO), targetDelta), gain(source, expanding));
                    BigInteger plateau = plateau(source, expanding, count);
                    plateauRuns = plateau.equals(count) ? null : plateau;
                    beginProposal(count);
                }
            }
            case SUPPORT -> inspectSupport();
            case COMPARE -> compare();
            case APPLY -> {
                // Only a completely evaluated/compared proposal can mutate the
                // retained suffix. No other proposal runs during partial apply.
                if (entries.hasNext()) {
                    var entry = entries.next();
                    if (entry.getValue().signum() == 0) required.remove(entry.getKey());
                    else required.put(entry.getKey(), entry.getValue());
                    enqueue(entry.getKey());
                } else {
                    recipes.put(selected.recipe.id(), selected.recipe);
                    prefix.add(PlanStep.batch(selected.recipe.id(), selected.runs));
                    selected.close(); selected = null; entries = null; phase = Phase.SELECT;
                }
            }
            case STEPS -> {
                if (prefixIndex > 0) steps.add(prefix.get(--prefixIndex));
                else {
                    steps.add(original.steps()); deficits = new LinkedHashMap<>();
                    entries = required.entrySet().iterator(); phase = Phase.DEFICITS;
                }
            }
            case DEFICITS -> {
                if (entries.hasNext()) {
                    var entry = entries.next(); BigInteger value = missing(entry.getKey(), entry.getValue());
                    if (value.signum() > 0) deficits.put(entry.getKey(), value);
                } else { entries = null; phase = Phase.PUBLISH; }
            }
            case PUBLISH -> {
                // These private builders are never mutated after transfer. The
                // public constructors still defensively copy caller collections.
                candidate = GraphPlan.ownedRefill(original, FrozenPlanSteps.owned(steps), recipes, required, deficits,
                        deficits.isEmpty() ? GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL : original.result(), budget.nodes());
                var funded = GraphPlan.ownedRefill(candidate, candidate.steps(), candidate.recipes(), candidate.initialExact(), Map.of(),
                        GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, budget.nodes());
                verification = new PlanVerification<>(funded, budget); phase = Phase.VERIFY;
            }
            case DONE -> { return true; }
            default -> throw new IllegalStateException("Child continuation must use its own slice");
        }
        return false;
    }
    private void beginProposal(BigInteger runs) {
        BigInteger nextDelta = targetDelta.add(gain(source, original.target()).multiply(runs));
        BigInteger old = deficit(expanding, required.getOrDefault(expanding, BigInteger.ZERO), targetDelta);
        if (deficit(expanding, before(source, expanding, runs), nextDelta).compareTo(old) >= 0) return;
        proposal = new Proposal(source, runs, nextDelta);
        long bytes = 192L + 128L * ((long) source.inputs().size() + source.outputs().size());
        budget.reserve(bytes); proposal.bytes = bytes;
        keys = source.inputs().keySet().iterator(); outputSupport = false; phase = Phase.SUPPORT;
    }
    private void inspectSupport() {
        if (!keys.hasNext() && !outputSupport) {
            keys = proposal.recipe.outputs().keySet().iterator(); outputSupport = true;
        }
        if (!keys.hasNext()) { finishSupport(); return; }
        K input = keys.next();
        // Scan the ordered union without allocating an unbounded temporary set.
        if (!outputSupport || !proposal.recipe.inputs().containsKey(input)) {
            BigInteger need = before(proposal.recipe, input, proposal.runs);
            proposal.needs.put(input, need);
            BigInteger previous = deficit(input, required.getOrDefault(input, BigInteger.ZERO), targetDelta);
            BigInteger next = deficit(input, need, proposal.delta);
            if (next.compareTo(previous) > 0) {
                proposal.funded = false;
                if (order.rank(input) >= order.rank(expanding)) { finishComparison(false); return; }
            }
        }
        // Iterator handoffs need no extra arithmetic/work unit or scheduler turn.
        if (!keys.hasNext() && !outputSupport) {
            keys = proposal.recipe.outputs().keySet().iterator(); outputSupport = true;
        }
        if (!keys.hasNext()) finishSupport();
    }
    private void finishSupport() {
        if (selected == null || proposal.funded && !selected.funded) finishComparison(true);
        else if (forceCraft && proposal.delta.compareTo(selected.delta) < 0) finishComparison(false);
        else {
            strict = false; comparingSelected = false;
            keys = proposal.needs.keySet().iterator(); phase = Phase.COMPARE;
        }
    }
    private void compare() {
        // Compare the full requirement vector and production credit, not a sum
        // of unlike missing items. Incomparable supplies retain source priority.
        if (!keys.hasNext() && !comparingSelected) {
            keys = selected.needs.keySet().iterator(); comparingSelected = true;
        }
        if (!keys.hasNext()) { finishComparison(strict); return; }
        K key = keys.next();
        if (!comparingSelected || !proposal.needs.containsKey(key)) {
            int comparison = proposal.need(key).compareTo(selected.need(key));
            if (comparison > 0) { finishComparison(false); return; }
            strict |= comparison < 0;
        }
        if (!keys.hasNext() && !comparingSelected) {
            keys = selected.needs.keySet().iterator(); comparingSelected = true;
        }
        if (!keys.hasNext()) finishComparison(strict);
    }
    private void finishComparison(boolean accept) {
        if (accept) {
            if (selected != null) selected.close();
            selected = proposal;
        } else proposal.close();
        proposal = null; keys = null; phase = Phase.SOURCE;
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

    GraphPlan<K> result() { return result; }
    private void reserve(long bytes) { budget.reserve(bytes); memory += bytes; }
    @Override public void close() {
        if (proposal != null) { proposal.close(); proposal = null; }
        if (selected != null) { selected.close(); selected = null; }
        keys = null; entries = null; bindings = null; sources = null; source = null;
        if (order != null) order.close();
        if (verification != null) verification.close();
        budget.release(memory); memory = 0;
    }
}
