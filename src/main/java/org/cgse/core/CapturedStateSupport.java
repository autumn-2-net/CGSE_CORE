// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Request-local source selection for the positive state arm. Reuses the
 * immutable captured producer index; neither membership nor absence is a
 * mathematical proof. The full capture and its ordinary state attempt survive
 * a failed selected attempt. No stock is manufactured by this analysis. */
final class CapturedStateSupport<K> implements AutoCloseable {
    private static final int MAX_SOURCES = 128, MAX_PORTS = 4096, MAX_RULES = 32, MAX_KEYS = 8192;
    private final List<CapturedCatalog.Entry<K>> entries;
    private final Map<K, List<Integer>> producers;
    private final PlanningBudget budget;
    private final long started, allowance;
    private final Set<Integer> selected = new TreeSet<>();
    private final Set<K> requested = new HashSet<>();
    private final ArrayDeque<K> keys = new ArrayDeque<>();
    private final ArrayDeque<Integer> pending = new ArrayDeque<>();
    private final Map<String, StateFamily<K>> rules = new LinkedHashMap<>();
    private final ArrayDeque<StateFamily<K>> pendingRules = new ArrayDeque<>();
    private final Iterator<K> seeds;
    private StateFamily<K> scanningRule;
    private Iterator<K> producerKeys;
    private List<Integer> providers = List.of();
    private List<CapturedCatalog.Entry<K>> result;
    private int provider, scanning = -1, output, input, candidate, ports;
    private long memory = 2048;
    private boolean done, closed;

    static <K> CapturedStateSupport<K> create(List<CapturedCatalog.Entry<K>> entries, Map<K, List<Integer>> producers,
            K target, Set<K> seeds, PlanningBudget budget) {
        if (budget.remainingWork() < 16_384 || !budget.tryReserve(2048)) return null;
        return new CapturedStateSupport<>(entries, producers, target, seeds, budget);
    }

    private CapturedStateSupport(List<CapturedCatalog.Entry<K>> entries, Map<K, List<Integer>> producers,
            K target, Set<K> seeds, PlanningBudget budget) {
        this.entries = entries; this.producers = producers; this.seeds = seeds.iterator(); this.budget = budget;
        started = budget.nodes(); allowance = Math.min(65_536, budget.remainingWork() / 16);
        try { require(target); }
        catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    boolean step() {
        if (done) return true;
        try (var costs = budget.trace(PlanningCostTrace.Origin.ALLOCATION, PlanningCostTrace.Stage.PREPARE)) {
            budget.compilationCheck(); budget.phase(PlanningBudget.Phase.BUILD);
            if (budget.nodes() - started >= allowance) return decline("work_limit");
            if (seeds.hasNext()) require(seeds.next());
            else if (scanning >= 0) scanSource();
            else if (provider < providers.size()) select(providers.get(provider++));
            else if (!pending.isEmpty()) { scanning = pending.removeFirst(); output = input = candidate = 0; }
            else if (!keys.isEmpty()) { providers = producers.getOrDefault(keys.removeFirst(), List.of()); provider = 0; }
            else if (producerKeys != null && producerKeys.hasNext()) {
                K key = producerKeys.next();
                if (scanningRule.accepts(key) && scanningRule.capacity(key) > 0) require(key);
            } else if (!pendingRules.isEmpty()) {
                scanningRule = pendingRules.removeFirst(); producerKeys = producers.keySet().iterator();
            } else finish();
            return done;
        } catch (PlanningBudget.Exhausted limit) {
            if (limit.limit() != PlanningBudget.Limit.MEMORY_LIMIT) throw limit;
            return decline("memory_limit");
        }
    }

    private void require(K key) {
        if (requested.contains(key)) return;
        if (requested.size() == MAX_KEYS) { decline("key_limit"); return; }
        if (!reserve(160)) return;
        requested.add(key); keys.addLast(key);
    }

    private void select(int index) {
        if (selected.contains(index)) return;
        if (selected.size() == MAX_SOURCES) { decline("source_limit"); return; }
        if (!reserve(160)) return;
        selected.add(index); pending.addLast(index);
    }

    private void scanSource() {
        var recipe = entries.get(scanning).recipe();
        if (output < recipe.outputs().size()) {
            output++; port();
        } else if (input < recipe.inputs().size()) {
            var slot = recipe.inputs().get(input);
            if (candidate == 0 && !port()) return;
            if (candidate < slot.candidates().size()) {
                if (!port()) return;
                var choice = slot.candidates().get(candidate++);
                require(choice.stack().what());
                if (done) return;
                var family = choice.family();
                if (family != null && choice.stack().amount() == 1 && !choice.configuration() && !choice.reusable() && !rules.containsKey(family.id())) {
                    if (rules.size() == MAX_RULES) { decline("rule_limit"); return; }
                    if (!reserve(192)) return;
                    rules.put(family.id(), family); pendingRules.addLast(family);
                }
            } else { input++; candidate = 0; }
        } else scanning = -1;
    }

    private boolean port() {
        if (++ports > MAX_PORTS) { decline("port_limit"); return false; }
        return true;
    }

    private void finish() {
        if (selected.isEmpty() || selected.size() == entries.size()) { decline("unchanged_support"); return; }
        var sources = new ArrayList<CapturedCatalog.Entry<K>>(selected.size());
        // Original priority and original entry objects survive the selection.
        // Dynamic candidates get their own identities only in the later view.
        for (int index : selected) {
            budget.compilationCheck();
            if (budget.nodes() - started >= allowance) { decline("work_limit"); return; }
            sources.add(entries.get(index));
        }
        result = List.copyOf(sources); done = true;
        budget.note("candidate_state_support", "selected=" + result.size() + "; catalog=" + entries.size() +
                "; rules=" + rules.size() + "; ports=" + ports + "; work=" + (budget.nodes() - started) + "; negative_proofs=false");
    }

    private boolean reserve(long bytes) {
        if (!budget.tryReserve(bytes)) { decline("memory_limit"); return false; }
        memory += bytes; return true;
    }

    private boolean decline(String reason) {
        done = true;
        budget.note("candidate_state_support", "declined; reason=" + reason + "; selected=" + selected.size() + "; catalog=" + entries.size());
        return true;
    }

    List<CapturedCatalog.Entry<K>> result() { return result; }

    @Override public void close() {
        if (closed) return; closed = true;
        selected.clear(); requested.clear(); keys.clear(); pending.clear(); rules.clear(); pendingRules.clear();
        producerKeys = null; scanningRule = null; providers = List.of();
        budget.release(memory); memory = 0; done = true;
    }
}
