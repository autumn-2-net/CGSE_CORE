// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Positive-only, request-local expansion of exact cross-rule state boundaries.
 * Discovered keys are candidate domains, never stock. Same-rule wear chains are
 * not recursively unfolded. A fresh finite capture owns all changed ordinals;
 * neither its negative results nor its coverage replace the original capture. */
final class CapturedStateExpansion<K> implements AutoCloseable {
    private static final int MAX_ENTRIES = 128, MAX_PORTS = 4096, MAX_RULES = 32, MAX_STATES = 128;

    private static final class Domain<K> {
        final CapturedRecipe.Input<K> original;
        final Set<CapturedRecipe.Candidate<K>> candidates = new LinkedHashSet<>();
        Domain(CapturedRecipe.Input<K> original) { this.original = original; }
    }
    private record Use<K>(Domain<K> domain, StateFamily<K> family) {}

    private final List<CapturedCatalog.Entry<K>> entries;
    private final K target;
    private final long amount;
    private final Map<K, Long> stock, seeds;
    private final Set<K> external;
    private final boolean preserve, force;
    private final PlanningBudget budget;
    private final long started, allowance;
    private final Iterator<K> known;
    private final List<List<Domain<K>>> drafts = new ArrayList<>();
    private final List<Use<K>> uses = new ArrayList<>();
    private final Map<String, StateFamily<K>> rules = new LinkedHashMap<>();
    private final List<K> states = new ArrayList<>();
    private final Set<K> seen = new LinkedHashSet<>();
    private final List<CapturedCatalog.Entry<K>> expanded = new ArrayList<>();
    private final Map<K, List<Integer>> producers = new LinkedHashMap<>();
    private CapturedReachableWitness<K> reachable;
    private long reachableStarted;
    private CapturedCatalog<K>.Work planning;
    private PlanVerification<K> verification;
    private ForceCraftProof<K> production;
    private GraphPlan<K> proposed, result;
    private int entry, state, use, freeze, ports, added;
    private long memory;
    private boolean prepared, seeded, boundary, closed, done;

    static <K> CapturedStateExpansion<K> create(List<CapturedCatalog.Entry<K>> entries, Set<K> known,
            K target, long amount, Map<K, Long> stock, Set<K> external, Map<K, Long> seeds,
            boolean preserve, boolean force, PlanningBudget budget) {
        if (entries.size() > MAX_ENTRIES || budget.remainingWork() < 16_384 || !budget.tryReserve(2048)) return null;
        return new CapturedStateExpansion<>(entries, known, target, amount, stock, external, seeds, preserve, force, budget);
    }

    private CapturedStateExpansion(List<CapturedCatalog.Entry<K>> entries, Set<K> known, K target, long amount,
            Map<K, Long> stock, Set<K> external, Map<K, Long> seeds, boolean preserve, boolean force, PlanningBudget budget) {
        this.entries = entries; this.known = known.iterator(); this.target = target; this.amount = amount;
        this.stock = stock; this.external = external; this.seeds = seeds; this.preserve = preserve; this.force = force;
        this.budget = budget; memory = 2048; started = budget.nodes();
        // One allowance includes discovery, compilation, solve and verification.
        allowance = Math.min(262_144, budget.remainingWork() / 8);
    }

    boolean step() {
        if (done) return true;
        try (var costs = budget.trace(PlanningCostTrace.Origin.ALLOCATION,
                verification != null ? PlanningCostTrace.Stage.VERIFY : planning != null || reachable != null ? PlanningCostTrace.Stage.SEARCH : PlanningCostTrace.Stage.PREPARE)) {
            budget.checkpoint();
            if (!active()) return decline("work_limit");
            if (!prepared) prepare();
            else if (!seeded) seed();
            else if (state < states.size()) expand();
            else if (verification != null) verify();
            else if (reachable != null) {
                // Try a short physically funded prefix before asking the count
                // portfolio to schedule this newly discovered finite graph.
                if (budget.nodes() - reachableStarted >= Math.min(8192, allowance / 16) || reachable.step()) {
                    proposed = reachable.result(); reachable.close(); reachable = null;
                    if (proposed != null) verification = new PlanVerification<>(proposed, budget);
                    else planning = new CapturedCatalog<>(expanded, false).begin(target, amount, stock, external, seeds, preserve, force, budget);
                }
            }
            else if (planning != null) {
                if (planning.step()) {
                    proposed = planning.result().plan();
                    planning.close(); planning = null;
                    if (!proposed.feasible()) return decline("finite_result");
                    verification = new PlanVerification<>(proposed, budget);
                }
            } else freeze();
            return done;
        } catch (PlanningBudget.Exhausted limit) {
            if (limit.limit() != PlanningBudget.Limit.MEMORY_LIMIT) throw limit;
            return decline("memory_limit");
        } catch (ArithmeticException overflow) {
            return decline("arithmetic_limit");
        }
    }

    private boolean active() { return budget.nodes() - started < allowance; }

    private boolean charge() {
        budget.compilationCheck();
        if (!active()) { decline("work_limit"); return false; }
        return true;
    }

    private boolean reserve(long bytes) {
        if (!budget.tryReserve(bytes)) { decline("memory_limit"); return false; }
        memory += bytes; return true;
    }

    private void prepare() {
        if (entry == entries.size()) {
            prepared = true;
            if (rules.size() < 2) decline("no_cross_rule_domain");
            return;
        }
        var source = entries.get(entry++);
        for (var output : source.recipe().outputs()) {
            if (!charge()) return;
            if (++ports > MAX_PORTS) { decline("domain_limit"); return; }
            // Covers the frozen output references and temporary producer set.
            if (!reserve(128)) return;
        }
        if (!reserve(256)) return;
        var inputs = new ArrayList<Domain<K>>(); drafts.add(inputs);
        for (var input : source.recipe().inputs()) {
            if (!charge()) return;
            if (++ports > MAX_PORTS) { decline("domain_limit"); return; }
            if (!reserve(256)) return;
            var domain = new Domain<>(input); inputs.add(domain);
            var seenRules = new LinkedHashSet<String>();
            for (var candidate : input.candidates()) {
                if (!charge()) return;
                if (++ports > MAX_PORTS) { decline("domain_limit"); return; }
                if (!reserve(256)) return;
                // Freeze original finite captures as well as derived candidates.
                // No family callback remains in the nested catalog.
                domain.candidates.add(new CapturedRecipe.Candidate<>(candidate.stack(), candidate.remaining(),
                        candidate.configuration(), candidate.reusable()));
                var family = candidate.family();
                if (family == null || candidate.stack().amount() != 1 || candidate.configuration() || candidate.reusable()) continue;
                if (seenRules.add(family.id())) {
                    if (!reserve(256)) return;
                    uses.add(new Use<>(domain, family)); rules.putIfAbsent(family.id(), family);
                    if (rules.size() > MAX_RULES) { decline("rule_limit"); return; }
                }
            }
        }
    }

    private void seed() {
        if (!known.hasNext()) { seeded = true; return; }
        K key = known.next();
        for (var family : rules.values()) {
            if (!charge()) return;
            if (eligible(family, key)) { remember(key); return; }
        }
    }

    private boolean eligible(StateFamily<K> family, K key) {
        return key != null && family.accepts(key) && family.capacity(key) > 0;
    }

    private void remember(K key) {
        if (seen.contains(key)) return;
        if (states.size() == MAX_STATES) { decline("state_limit"); return; }
        if (!reserve(192)) return;
        seen.add(key); states.add(key);
    }

    private void expand() {
        if (use == uses.size()) { use = 0; state++; return; }
        var transition = uses.get(use++); var family = transition.family(); K before = states.get(state);
        if (!charge() || !eligible(family, before)) return;
        long capacity = family.capacity(before); K after = family.nominalAfterOne(before);
        long remaining = after != null && family.accepts(after) ? family.capacity(after) : 0;
        if (remaining < capacity - 1) { decline("invalid_state_algebra"); return; }
        var candidate = new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(before, 1), after, false);
        if (!transition.domain().candidates.contains(candidate)) {
            if (ports + added == MAX_PORTS) { decline("domain_limit"); return; }
            if (!reserve(256)) return;
            transition.domain().candidates.add(candidate); added++;
        }
        if (after == null) return;
        for (var other : rules.values()) {
            if (!charge()) return;
            // Follow changes that unlock another use rule, not every decrement
            // of a durability / charge counter accepted by the same rules.
            if (!eligible(other, before) && eligible(other, after)) { boundary = true; remember(after); return; }
        }
    }

    private void freeze() {
        if (!boundary) { decline("no_eligibility_gain"); return; }
        if (added == 0) { decline("unchanged_domain"); return; }
        if (freeze < entries.size()) {
            var original = entries.get(freeze); var inputs = new ArrayList<CapturedRecipe.Input<K>>();
            for (var domain : drafts.get(freeze)) {
                if (!charge() || !reserve(128L + 16L * domain.candidates.size())) return;
                inputs.add(new CapturedRecipe.Input<>(domain.original.multiplier(), List.copyOf(domain.candidates), domain.original.mode()));
            }
            if (!reserve(256)) return;
            var produced = new LinkedHashSet<K>();
            for (var output : original.recipe().outputs()) {
                if (!charge()) return;
                if (output.amount() > 0) produced.add(output.what());
            }
            for (var input : inputs) for (var candidate : input.candidates()) {
                if (!charge()) return;
                if (candidate.remaining() != null) produced.add(candidate.remaining());
            }
            for (var key : produced) {
                if (!charge() || !reserve(160)) return;
                producers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(freeze);
            }
            // A new identity prevents shifted mixed-radix ordinals from aliasing
            // canonical recipes. Host dispatch keeps the original source binding.
            expanded.add(new CapturedCatalog.Entry<>("state_boundary_" + freeze++, original.binding(),
                    new CapturedRecipe<>(inputs, original.recipe().outputs(), original.recipe().external(), true)));
            return;
        }
        budget.note("candidate_state_boundary", "expanded; states=" + states.size() + "; added=" + added +
                "; allowance=" + allowance + "; negative_proofs=false");
        reachableStarted = budget.nodes();
        reachable = new CapturedReachableWitness<>(expanded, producers, target, amount, stock, external, seeds, preserve, force, budget);
    }

    private void verify() {
        if (!verification.step()) return;
        if (force) {
            if (production == null) production = new ForceCraftProof<>(proposed, verification, seeds, budget);
            if (!production.step()) return;
            if (!production.proved()) { decline("force_craft_not_proved"); return; }
        }
        result = proposed; done = true;
        budget.note("candidate_state_boundary", "verified; states=" + states.size() + "; added=" + added +
                "; work=" + (budget.nodes() - started));
    }

    private boolean decline(String reason) {
        done = true;
        budget.note("candidate_state_boundary", "declined; reason=" + reason + "; states=" + states.size() + "; added=" + added);
        return true;
    }

    GraphPlan<K> result() { return result; }
    CompletableFuture<?> waitingFor() { return planning == null ? null : planning.waitingFor(); }

    @Override public void close() {
        if (closed) return; closed = true;
        if (planning != null) planning.close(); planning = null;
        if (reachable != null) reachable.close(); reachable = null;
        if (verification != null) verification.close(); verification = null;
        if (production != null) production.close(); production = null;
        drafts.clear(); uses.clear(); rules.clear(); states.clear(); seen.clear(); expanded.clear(); producers.clear(); proposed = null;
        budget.release(memory); memory = 0; done = true;
    }
}
