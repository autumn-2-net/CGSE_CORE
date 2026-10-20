// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Bounded source-choice trials normalized to complete supply boundaries.
 * The completed incumbent, not a local intermediate bill, decides publication.
 */
final class RefillSourceSearch<K> implements AutoCloseable {
    private enum Phase { BINDINGS, SCAN, PORTS, SOURCES, INPUTS, ORIGINAL, GOALS, MISSING, RANK, TRIAL, EXPAND, COMPARE, DONE }
    private record Choice<K>(K key, String recipe) {}
    private static final int MAX_CHOICES = 8;
    private final GraphPlan<K> original;
    private final GraphCompiler<K> compiler;
    private final Map<K, Long> stock;
    private final Set<K> external;
    private final Set<String> excluded;
    private final boolean force, ignoreTargetStock;
    private final PlanningBudget budget;
    private final long started, allowance, scanAllowance;
    private final String retainedFailure;
    private GraphPlan<K> result, boundary, candidate;
    private Phase phase = Phase.BINDINGS;
    private final Set<K> seen = new HashSet<>();
    private final List<Choice<K>> choices = new ArrayList<>(), local = new ArrayList<>();
    private final Map<K, String> preferred = new LinkedHashMap<>();
    private Iterator<GraphRecipe<K>> recipes;
    private Iterator<K> ports, inputs;
    private Iterator<GraphRecipe<K>> sources;
    private Iterator<? extends Map.Entry<K, ? extends Number>> entries;
    private GraphRecipe<K> recipe;
    private K key;
    private boolean outputs, multihop;
    private int next, accepted;
    private String previousPreference;
    private Choice<K> choice;
    private BigInteger credit = BigInteger.ZERO;
    private Map<K, BigInteger> initial, missing;
    private PlanVerification<K> verification;
    private RefillProducerOrder<K> order;
    private MissingMaterialExpansion<K> expansion;
    private RefillFrontier.Comparison<K> comparison;
    private boolean ranked, allocated;
    private long memory;

    RefillSourceSearch(GraphCompiler<K> compiler, GraphPlan<K> original, Map<K, Long> stock,
                       Set<K> external, Set<String> excluded, boolean force, boolean ignoreTargetStock,
                       PlanningBudget budget) {
        this.compiler = compiler; this.original = result = original; this.stock = stock;
        this.external = external; this.excluded = excluded; this.force = force; this.ignoreTargetStock = ignoreTargetStock;
        this.budget = budget; started = budget.nodes(); retainedFailure = budget.failureDetail();
        allowance = Math.min(8192, budget.remainingWork() / 4);
        scanAllowance = Math.min(1024, Math.max(128, allowance / 4));
        recipes = original.recipes().values().iterator();
    }

    boolean step() {
        try {
            budget.checkpoint();
            if (phase == Phase.DONE) return true;
            if (budget.nodes() - started >= allowance) return finish();
            if (!allocated) { reserve(1024); allocated = true; }
            // Each child owns its slice. Never append a child to a full scan.
            if (phase == Phase.ORIGINAL) {
                if (!verification.step()) return false;
                credit = force ? verification.summary().delta(original.target()) : BigInteger.ZERO;
                verification.close(); verification = null;
                // Net production cannot certify a low-net forced-craft proof.
                // Keep that existing witness rather than weakening its contract.
                if (force && credit.compareTo(BigInteger.valueOf(original.amount())) < 0) return finish();
                initial = new LinkedHashMap<>(); missing = new LinkedHashMap<>();
                entries = original.seeds().entrySet().iterator(); phase = Phase.GOALS; return false;
            }
            if (phase == Phase.RANK) {
                if (!order.step()) return false;
                ranked = true; phase = Phase.TRIAL; return false;
            }
            if (phase == Phase.EXPAND) {
                if (!expansion.step()) return false;
                candidate = expansion.result();
                if (force && expansion.prefixProduction().compareTo(credit) < 0) { endTrial(false); return false; }
                comparison = new RefillFrontier.Comparison<>(candidate, result, budget);
                phase = Phase.COMPARE; return false;
            }
            if (phase == Phase.COMPARE) {
                if (!comparison.step()) return false;
                // Equal/incomparable bills keep the old source preference.
                // Held stock may increase only for strictly smaller new supply.
                endTrial(comparison.improvesMissing());
                return false;
            }
            for (int i = 0; i < 64; i++) {
                budget.check();
                if (phase.ordinal() <= Phase.INPUTS.ordinal() && budget.nodes() - started >= scanAllowance) {
                    return beginTrials();
                }
                if (advance()) return true;
                if (phase == Phase.ORIGINAL || phase == Phase.RANK || phase == Phase.EXPAND) return false;
            }
            return false;
        } catch (PlanningBudget.Exhausted limit) {
            budget.failureDetail(retainedFailure);
            return finish();
        }
    }

    private boolean advance() {
        switch (phase) {
            case BINDINGS -> {
                // Check every old binding before a truncated hint scan is
                // allowed to start trials. Late per-push bindings matter too.
                if (recipes.hasNext()) { if (recipes.next().batchSensitiveInputs()) return finish(); }
                else { recipes = original.recipes().values().iterator(); phase = Phase.SCAN; }
            }
            case SCAN -> {
                if (choices.size() == MAX_CHOICES || !recipes.hasNext()) return beginTrials();
                recipe = recipes.next();
                ports = recipe.inputs().keySet().iterator(); outputs = false; phase = Phase.PORTS;
            }
            case PORTS -> {
                if (!ports.hasNext() && !outputs) { outputs = true; ports = recipe.outputs().keySet().iterator(); }
                if (!ports.hasNext()) { phase = Phase.SCAN; break; }
                key = ports.next();
                if (external.contains(key) || compiler.producers(key).size() < 2 || seen.contains(key)) break;
                reserve(96); seen.add(key);
                multihop = false; local.clear(); sources = compiler.producers(key).iterator(); phase = Phase.SOURCES;
            }
            case SOURCES -> {
                if (!sources.hasNext()) {
                    if (multihop) for (var option : local) {
                        if (choices.size() == MAX_CHOICES) break;
                        // The local list is constant-sized; charge retained hints.
                        reserve(96); choices.add(option);
                    }
                    local.clear(); sources = null; phase = Phase.PORTS; break;
                }
                var source = sources.next();
                if (excluded.contains(source.id()) || source.batchSensitiveInputs() ||
                        source.outputs().getOrDefault(key, 0L) <= source.inputs().getOrDefault(key, 0L)) break;
                var binding = original.recipes().get(source.id());
                if (binding != null && !binding.equals(source)) break;
                // Recipes already present in the word are not an alternative
                // route hint. Other ports may still expose their upstream choice.
                if (binding == null && local.size() < MAX_CHOICES) local.add(new Choice<>(key, source.id()));
                inputs = source.inputs().keySet().iterator(); phase = Phase.INPUTS;
            }
            case INPUTS -> {
                if (!inputs.hasNext() || multihop) { inputs = null; phase = Phase.SOURCES; break; }
                K input = inputs.next();
                multihop = !external.contains(input) && !compiler.producers(input).isEmpty();
            }
            case GOALS -> {
                if (entries.hasNext()) {
                    var e = entries.next(); reserve(256);
                    initial.put(e.getKey(), BigInteger.valueOf(e.getValue().longValue()));
                } else {
                    reserve(256); initial.merge(original.target(), BigInteger.valueOf(original.amount()), BigInteger::add);
                    entries = initial.entrySet().iterator(); phase = Phase.MISSING;
                }
            }
            case MISSING -> {
                if (entries.hasNext()) {
                    var e = entries.next();
                    long have = ignoreTargetStock && original.target().equals(e.getKey()) ? 0 : stock.getOrDefault(e.getKey(), 0L);
                    BigInteger gap = ((BigInteger) e.getValue()).subtract(BigInteger.valueOf(have));
                    if (gap.signum() > 0 && !external.contains(e.getKey())) missing.put(e.getKey(), gap);
                } else {
                    entries = null;
                    // Existing bindings are borrowed immutably: an alternative
                    // cannot silently change the physical meaning of an old ID.
                    boundary = GraphPlan.ownedRefill(original, new PlanStep.Sequence(List.of()), original.recipes(),
                            initial, missing, original.result(), budget.nodes());
                    order = new RefillProducerOrder<>(compiler, initial.keySet(), external, excluded, budget);
                    phase = Phase.RANK;
                }
            }
            case TRIAL -> {
                if (next == choices.size() || result.feasible()) return finish();
                choice = choices.get(next++);
                previousPreference = preferred.put(choice.key(), choice.recipe());
                expansion = MissingMaterialExpansion.sourceAlternative(compiler, boundary, stock, external, excluded,
                        force, ignoreTargetStock, budget, order, credit, preferred);
                phase = Phase.EXPAND;
            }
            default -> throw new IllegalStateException("Child continuation must use its own slice");
        }
        return false;
    }

    private boolean beginTrials() {
        ports = inputs = null; sources = null; local.clear();
        if (choices.isEmpty()) return finish();
        reserve(1024);
        var funded = GraphPlan.ownedRefill(original, original.steps(), original.recipes(), original.initialExact(), Map.of(),
                GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, budget.nodes());
        verification = PlanVerification.refill(funded, budget);
        phase = Phase.ORIGINAL;
        return false;
    }

    private void endTrial(boolean accept) {
        if (accept) {
            result = candidate; accepted++;
            // The retained word still owns its builders after the child closes.
            memory += expansion.takeResultCharge();
        } else if (previousPreference == null) preferred.remove(choice.key());
        else preferred.put(choice.key(), previousPreference);
        expansion.close(); expansion = null; candidate = null; comparison = null;
        phase = Phase.TRIAL;
    }

    private boolean finish() {
        phase = Phase.DONE;
        if (!choices.isEmpty()) budget.note("refill_sources", "trials=" + next + "; accepted=" + accepted +
                "; work=" + (budget.nodes() - started));
        return true;
    }
    GraphPlan<K> result() { return result; }
    RefillProducerOrder<K> completedOrder() { return ranked ? order : null; }
    private void reserve(long bytes) { budget.reserve(bytes); memory += bytes; }
    @Override public void close() {
        if (verification != null) { verification.close(); verification = null; }
        if (expansion != null) { expansion.close(); expansion = null; }
        if (order != null) { order.close(); order = null; }
        budget.release(memory); memory = 0;
    }
}
