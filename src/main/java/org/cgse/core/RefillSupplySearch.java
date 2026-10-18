// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Optional main-planner prefix witnesses inside a completed refill's supply ceiling. */
final class RefillSupplySearch<K> implements AutoCloseable {
    private enum Phase { ROOT, DISCOVER, INPUTS, OUTPUTS, COMPILE, MACROS, MACRO_SUMMARY, CYCLES, TRIAL, SUPPLY, SEARCH, SUMMARY, COPY, NEEDS, BINDINGS, MISSING, VERIFY, DONE }
    private final GraphPlan<K> original;
    private GraphPlan<K> result, base, candidate;
    private final RefillProducerOrder<K> order;
    private final Map<K, Long> stock;
    private final Set<K> external;
    private final boolean force, ignoreTargetStock;
    private final PlanningBudget budget;
    private final long started;
    private long passStarted, allowance;
    private final String retainedFailure;
    private Iterator<K> roots;
    private Phase phase = Phase.ROOT;
    private K root;
    private int rank, trial, incidences;
    private BigInteger missing, trialAmount;
    private Set<BigInteger> tried;
    private Map<String, GraphRecipe<K>> recipes, bindings;
    private Deque<RefillProducerOrder<K>.Node> pending;
    private Set<K> visited, domain, trialExternal;
    private Iterator<RefillProducerOrder<K>.Source> sources;
    private Iterator<K> ports;
    private GraphRecipe<K> recipe;
    private Map<K, BigInteger> supplied, goals, initial, deficits;
    private Iterator<Map.Entry<K, BigInteger>> entries;
    private Iterator<Map.Entry<String, GraphRecipe<K>>> bindingEntries;
    private BackwardCoverability<K> searching;
    private LinearMacroCompilation<K> compiling;
    private RefillCycleActions<K> cycling;
    private List<BackwardCoverability.Action<K>> macros;
    private Iterator<GraphRecipe<K>> compiledRecipes;
    private PlanStep macro;
    private SummaryComputation<K> summarizing;
    private SequenceSummary<K> summary;
    private PlanVerification<K> verifying;
    private PlanStep prefix;
    private boolean strict, mergingBindings, accelerated, shortcutsRelevant;
    private long memory;

    RefillSupplySearch(GraphPlan<K> original, RefillProducerOrder<K> order, Map<K, Long> stock,
                       Set<K> external, boolean force, boolean ignoreTargetStock, PlanningBudget budget) {
        this.original = result = original; this.order = order; this.stock = stock; this.external = external;
        this.force = force; this.ignoreTargetStock = ignoreTargetStock; this.budget = budget;
        roots = original.missingExact().keySet().iterator(); started = budget.nodes();
        passStarted = started;
        allowance = Math.min(8192, budget.remainingWork() / 4); retainedFailure = budget.failureDetail();
    }

    boolean step() {
        try {
            budget.checkpoint();
            if (phase == Phase.DONE) return true;
            if (budget.nodes() - passStarted >= allowance) return finish();
            if (phase == Phase.COMPILE) {
                if (!compiling.step()) return false;
                compiledRecipes = compiling.recipes().values().iterator(); phase = Phase.MACROS; return false;
            }
            if (phase == Phase.MACRO_SUMMARY) {
                if (!summarizing.step()) return false;
                macros.add(new BackwardCoverability.Action<>(macro, summarizing.result()));
                summarizing.close(); summarizing = null; macro = null; phase = Phase.MACROS; return false;
            }
            if (phase == Phase.CYCLES) {
                if (!cycling.step()) return false;
                macros = cycling.actions();
                if (macros.isEmpty()) endRoot(); else phase = Phase.TRIAL;
                return false;
            }
            if (phase == Phase.SEARCH) {
                if (!searching.step()) return false;
                if (searching.result() != BackwardCoverability.Result.WITNESS) { endTrial(); return false; }
                prefix = searching.witness(); searching.close(); searching = null;
                summarizing = new SummaryComputation<>(prefix, recipes, budget); phase = Phase.SUMMARY; return false;
            }
            if (phase == Phase.SUMMARY) {
                if (!summarizing.step()) return false;
                summary = summarizing.result();
                // The suffix's production credit is unchanged only if the
                // added prefix consumes no net finished target, even externally.
                if (force && summary.delta(base.target()).signum() < 0) { endTrial(); return false; }
                initial = new LinkedHashMap<>(); deficits = new LinkedHashMap<>(); bindings = new LinkedHashMap<>();
                mergingBindings = false;
                entries = base.initialExact().entrySet().iterator(); phase = Phase.COPY; return false;
            }
            if (phase == Phase.VERIFY) {
                if (!verifying.step()) return false;
                result = candidate; endRoot(); return false;
            }
            for (int i = 0; i < 64; i++) {
                budget.check();
                if (advance()) return true;
                if (phase == Phase.COMPILE || phase == Phase.MACRO_SUMMARY || phase == Phase.CYCLES || phase == Phase.SEARCH || phase == Phase.SUMMARY || phase == Phase.VERIFY) return false;
            }
            return false;
        } catch (PlanningBudget.Exhausted limit) {
            budget.failureDetail(retainedFailure); return stop();
        }
    }

    private boolean advance() {
        switch (phase) {
            case ROOT -> {
                if (!roots.hasNext()) return finish();
                root = roots.next(); var node = order.get(root);
                missing = result.missingExact().getOrDefault(root, BigInteger.ZERO);
                if (missing.signum() == 0 || node == null || !node.cycle) break;
                reserve(768); base = result; rank = node.rank; trial = incidences = 0;
                recipes = new LinkedHashMap<>(); pending = new ArrayDeque<>(); visited = new HashSet<>();
                domain = new LinkedHashSet<>(); tried = new HashSet<>(); sources = null;
                pending.add(node); visited.add(root); phase = Phase.DISCOVER;
            }
            case DISCOVER -> {
                if (sources != null && sources.hasNext()) {
                    recipe = sources.next().recipe;
                    if (recipes.containsKey(recipe.id())) break;
                    var bound = base.recipes().get(recipe.id());
                    if (bound != null && !bound.equals(recipe)) break;
                    // Keep this optional neighborhood small before constructing
                    // the existing exact antichain. Its constructor is not a
                    // resumable catalog compiler, so cap ports as well as keys.
                    if (recipe.batchSensitiveInputs() || recipes.size() >= 12 ||
                            (long) incidences + recipe.inputs().size() + recipe.outputs().size() > 128) { endRoot(); break; }
                    reserve(192L + 128L * (recipe.inputs().size() + recipe.outputs().size()));
                    incidences += recipe.inputs().size() + recipe.outputs().size(); recipes.put(recipe.id(), recipe);
                    ports = recipe.inputs().keySet().iterator(); phase = Phase.INPUTS;
                } else if (!pending.isEmpty()) sources = pending.removeFirst().sources.iterator();
                else {
                    sources = null; recipe = null;
                    if (recipes.isEmpty() || visited.size() < 2) endRoot();
                    else if (accelerated) {
                        macros = new ArrayList<>();
                        compiling = new LinearMacroCompilation<>(recipes, base.target(), Set.of(), budget);
                        phase = Phase.COMPILE;
                    } else { shortcutsRelevant = true; macros = List.of(); phase = Phase.TRIAL; }
                }
            }
            case INPUTS, OUTPUTS -> {
                if (ports.hasNext()) {
                    K key = ports.next();
                    if (domain.add(key) && domain.size() > 16) { endRoot(); break; }
                    if (phase == Phase.INPUTS) {
                        var node = order.get(key);
                        if (node != null && node.cycle && node.rank == rank && visited.add(key)) pending.addLast(node);
                    }
                } else if (phase == Phase.INPUTS) { ports = recipe.outputs().keySet().iterator(); phase = Phase.OUTPUTS; }
                else { ports = null; phase = Phase.DISCOVER; }
            }
            case MACROS -> {
                if (compiledRecipes.hasNext()) {
                    var compiled = compiledRecipes.next();
                    if (recipes.containsKey(compiled.id())) break;
                    // Preserve the actual chain word and all intermediate keys.
                    // The contracted endpoint recipe alone is not an exact
                    // summary when an intermediate is also a suffix obligation.
                    reserve(512L * (domain.size() + recipes.size()));
                    macro = compiling.expand(new PlanStep.Batch(compiled.id(), 1));
                    summarizing = new SummaryComputation<>(macro, recipes, budget); phase = Phase.MACRO_SUMMARY;
                } else {
                    compiledRecipes = null; compiling.close(); compiling = null;
                    cycling = new RefillCycleActions<>(recipes, macros, root, budget); phase = Phase.CYCLES;
                }
            }
            case TRIAL -> {
                if (trial == 4) { endRoot(); break; }
                trialAmount = switch (trial++) {
                    case 0 -> BigInteger.ZERO;
                    case 1 -> BigInteger.ONE;
                    case 2 -> missing.shiftRight(1);
                    default -> missing.subtract(BigInteger.ONE);
                };
                if (trialAmount.compareTo(missing) >= 0 || !tried.add(trialAmount)) break;
                // Includes the bounded child's immutable action summaries,
                // key/position vectors and our supply/goal/external maps.
                reserve(1024L + 384L * (domain.size() + incidences));
                supplied = new LinkedHashMap<>(); goals = new LinkedHashMap<>(); trialExternal = new HashSet<>();
                ports = domain.iterator(); phase = Phase.SUPPLY;
            }
            case SUPPLY -> {
                if (ports.hasNext()) {
                    K key = ports.next();
                    BigInteger have = BigInteger.valueOf(available(key)).add(key.equals(root) ? trialAmount : base.missingExact().getOrDefault(key, BigInteger.ZERO));
                    BigInteger need = base.initialExact().getOrDefault(key, BigInteger.ZERO);
                    if (force && key.equals(base.target())) need = need.max(have);
                    supplied.put(key, have); goals.put(key, need);
                    if (external.contains(key) && !(force && key.equals(base.target()))) trialExternal.add(key);
                } else {
                    ports = null;
                    searching = new BackwardCoverability<>(List.copyOf(recipes.values()), supplied, goals, trialExternal,
                            macros, budget, Math.min(2048, Math.max(0, allowance - (budget.nodes() - passStarted))), true);
                    phase = Phase.SEARCH;
                }
            }
            case COPY -> {
                if (entries.hasNext()) { var e = entries.next(); reserve(128); initial.put(e.getKey(), e.getValue()); }
                else { entries = summary.delta().entrySet().iterator(); phase = Phase.NEEDS; }
            }
            case NEEDS -> {
                if (entries.hasNext()) {
                    var e = entries.next(); K key = e.getKey();
                    BigInteger need = summary.required(key).max(base.initialExact().getOrDefault(key, BigInteger.ZERO).subtract(e.getValue()));
                    if (need.signum() == 0) initial.remove(key);
                    else { if (!initial.containsKey(key)) reserve(128); initial.put(key, need); }
                } else { entries = null; bindingEntries = base.recipes().entrySet().iterator(); phase = Phase.BINDINGS; }
            }
            case BINDINGS -> {
                if (bindingEntries.hasNext()) { var e = bindingEntries.next(); reserve(96); bindings.put(e.getKey(), e.getValue()); }
                else {
                    // The discovered binding map has at most12 entries.
                    // Merge it through the same cursor, without copying a
                    // possibly large original suffix's physical bindings twice.
                    if (!mergingBindings) {
                        mergingBindings = true; bindingEntries = recipes.entrySet().iterator();
                    } else {
                        bindingEntries = null; entries = initial.entrySet().iterator(); strict = false; phase = Phase.MISSING;
                    }
                }
            }
            case MISSING -> {
                if (entries.hasNext()) {
                    var e = entries.next();
                    BigInteger gap = external.contains(e.getKey()) ? BigInteger.ZERO : e.getValue().subtract(BigInteger.valueOf(available(e.getKey()))).max(BigInteger.ZERO);
                    int comparison = gap.compareTo(base.missingExact().getOrDefault(e.getKey(), BigInteger.ZERO));
                    if (comparison > 0) { endRoot(); break; }
                    strict |= comparison < 0;
                    if (gap.signum() > 0) { reserve(128); deficits.put(e.getKey(), gap); }
                } else {
                    entries = null; strict |= deficits.size() < base.missingExact().size();
                    if (!strict) { endRoot(); break; }
                    candidate = GraphPlan.ownedRefill(base, new PlanStep.Sequence(List.of(prefix, base.steps())), bindings, initial, deficits,
                            deficits.isEmpty() ? GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL : base.result(), budget.nodes());
                    verifying = new PlanVerification<>(GraphPlan.ownedRefill(candidate, candidate.steps(), candidate.recipes(), candidate.initialExact(), Map.of(),
                            GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, budget.nodes()), budget);
                    phase = Phase.VERIFY;
                }
            }
            default -> throw new IllegalStateException("Unexpected refill supply phase " + phase);
        }
        return false;
    }

    private long available(K key) { return ignoreTargetStock && original.target().equals(key) ? 0 : stock.getOrDefault(key, 0L); }
    private void endTrial() {
        if (searching != null) { searching.close(); searching = null; }
        if (summarizing != null) { summarizing.close(); summarizing = null; }
        if (verifying != null) { verifying.close(); verifying = null; }
        summary = null; prefix = null; candidate = null; entries = null; bindingEntries = null; ports = null;
        phase = Phase.TRIAL;
    }
    private void endRoot() {
        endTrial(); sources = null; recipe = null; pending = null; visited = null; domain = null;
        if (compiling != null) { compiling.close(); compiling = null; }
        if (cycling != null) { cycling.close(); cycling = null; }
        macros = null; compiledRecipes = null; macro = null;
        recipes = null; bindings = null; initial = null; deficits = null; supplied = null; goals = null; trialExternal = null; tried = null;
        phase = Phase.ROOT;
    }
    private boolean finish() {
        if (!accelerated && shortcutsRelevant && !result.missingExact().isEmpty() && budget.remainingWork() >= 4096) {
            // Keep the old bounded search's completed incumbent. A faster
            // shortcut can otherwise choose an incomparable bill early and
            // prevent a later root from retaining its previous improvement.
            endRoot(); accelerated = true; roots = result.missingExact().keySet().iterator();
            passStarted = budget.nodes(); allowance = Math.min(8192, budget.remainingWork() / 4);
            return false;
        }
        return stop();
    }
    private boolean stop() {
        phase = Phase.DONE;
        budget.note("refill_supply", (result == original ? "retained_original" : "improved") + "; work=" + (budget.nodes() - started));
        return true;
    }
    GraphPlan<K> result() { return result; }
    private void reserve(long bytes) { budget.reserve(bytes); memory += bytes; }
    @Override public void close() {
        endRoot(); phase = Phase.DONE;
        budget.release(memory); memory = 0;
    }
}
