// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Goal-aware reduction of a completed refill word, within its original supply envelope.
 * Walking right to left leaves the original prefix fixed. At each boundary its
 * inventory C funds the already shortened suffix. For a unit with net delta d,
 * each positive d imposes n >= ceil((suffixNeed - C) / d). Reducing the old count
 * to their maximum cannot worsen a nonpositive-d resource's prefix requirement.
 * Exact backward requirements then replace that suffix's need. This induction
 * preserves executability without requiring all unrequested final coproducts.
 */
final class RefillWordPruning<K> implements AutoCloseable {
    private enum Phase { RECIPES, ORIGINAL, INITIAL, FINAL, GOALS, NEXT, UNWRAP, SUMMARY, SUPPORT, APPLY, EMIT, STEPS, DEFICITS, VERIFY, DONE }
    private final GraphPlan<K> original;
    private final Map<K, Long> stock;
    private final Set<K> external;
    private final boolean forceCraft, ignoreTargetStock;
    private final PlanningBudget budget;
    private final long started, allowance;
    private final String retainedFailure;
    private final Map<K, BigInteger> inventory = new LinkedHashMap<>(), required = new LinkedHashMap<>(), missing = new LinkedHashMap<>();
    private final List<PlanStep> reversed = new ArrayList<>(), steps = new ArrayList<>();
    private final Deque<Cursor> stack = new ArrayDeque<>();
    private Iterator<GraphRecipe<K>> recipes;
    private Iterator<Map.Entry<K, BigInteger>> entries;
    private Iterator<Map.Entry<K, Long>> seeds;
    private PlanVerification<K> verification;
    private SummaryComputation<K> computation;
    private SequenceSummary<K> summary, unitSummary;
    private GraphPlan<K> candidate, result;
    private PlanStep atom, unit;
    private BigInteger oldRuns, runs, targetCredit = BigInteger.ZERO, tailTarget = BigInteger.ZERO;
    private boolean feedback, changed, allocated;
    private Phase phase = Phase.RECIPES;
    private int reverseIndex;
    private long memory, summaryBytes, unitBytes;

    RefillWordPruning(GraphPlan<K> original, Map<K, Long> stock, Set<K> external, boolean forceCraft,
                      boolean ignoreTargetStock, PlanningBudget budget, long allowance) {
        this(original, stock, external, forceCraft, ignoreTargetStock, budget, allowance, null);
    }

    /** A completed verifier of this exact word/binding/initial tuple owns and
     * charges a borrowed summary until this pass closes. No cross-request reuse. */
    RefillWordPruning(GraphPlan<K> original, Map<K, Long> stock, Set<K> external, boolean forceCraft,
                      boolean ignoreTargetStock, PlanningBudget budget, long allowance, SequenceSummary<K> verified) {
        this.original = original; this.result = original; this.stock = stock; this.external = external;
        this.forceCraft = forceCraft; this.ignoreTargetStock = ignoreTargetStock; this.budget = budget;
        this.allowance = allowance; started = budget.nodes(); retainedFailure = budget.failureDetail();
        summary = verified;
        recipes = original.recipes().values().iterator();
    }

    boolean step() {
        try {
            budget.checkpoint();
            if (phase == Phase.DONE) return true;
            if (budget.nodes() - started >= allowance) return finish(false);
            if (!allocated) { reserve(256); allocated = true; }
            // Child folds/verifiers each own their slice; never append them to
            // a full cursor slice. The optional cap can overshoot by one slice.
            if (phase == Phase.ORIGINAL) {
                if (!verification.step()) return false;
                summary = verification.summary(); verification.close(); verification = null;
                summaryBytes = retain(summary);
                return beginOriginal();
            }
            if (phase == Phase.SUMMARY) {
                if (!computation.step()) return false;
                unitSummary = computation.result(); computation.close(); computation = null;
                unitBytes = retain(unitSummary);
                entries = unitSummary.delta().entrySet().iterator(); runs = BigInteger.ZERO; phase = Phase.SUPPORT; return false;
            }
            if (phase == Phase.VERIFY) {
                if (!verification.step()) return false;
                if (forceCraft && verification.summary().delta(original.target()).compareTo(targetCredit) < 0) return finish(false);
                return finish(true);
            }
            for (int i = 0; i < 64; i++) {
                budget.check();
                if (advance()) return true;
                if (phase == Phase.ORIGINAL || phase == Phase.SUMMARY || phase == Phase.VERIFY) return false;
            }
            return false;
        } catch (PlanningBudget.Exhausted limit) {
            // A verified bill is already available. Optional quality work must
            // not turn it into a limit result; cancellation still propagates.
            budget.failureDetail(retainedFailure); return finish(false);
        }
    }

    private boolean advance() {
        switch (phase) {
            case RECIPES -> {
                if (recipes.hasNext()) {
                    var recipe = recipes.next();
                    if (recipe.batchSensitiveInputs()) return finish(false);
                    feedback |= recipe.hasInputReturns();
                } else {
                    recipes = null;
                    // Ordinary acyclic bills need no additional fold. This is
                    // an applicability filter, not a claim that they are optimal.
                    if (!feedback) return finish(false);
                    if (summary != null) return beginOriginal();
                    verification = verify(original); phase = Phase.ORIGINAL;
                }
            }
            case INITIAL -> {
                if (entries.hasNext()) { var e = entries.next(); put(inventory, e.getKey(), e.getValue()); }
                else { entries = summary.delta().entrySet().iterator(); phase = Phase.FINAL; }
            }
            case FINAL -> {
                if (entries.hasNext()) {
                    var e = entries.next(); put(inventory, e.getKey(), value(inventory, e.getKey()).add(e.getValue()));
                } else {
                    summary = null; release(summaryBytes); summaryBytes = 0;
                    entries = null; seeds = original.seeds().entrySet().iterator(); phase = Phase.GOALS;
                    put(required, original.target(), BigInteger.valueOf(original.amount()));
                }
            }
            case GOALS -> {
                if (seeds.hasNext()) {
                    var e = seeds.next(); put(required, e.getKey(), value(required, e.getKey()).add(BigInteger.valueOf(e.getValue())));
                } else { seeds = null; push(List.of(original.steps())); phase = Phase.NEXT; }
            }
            case NEXT -> {
                if (stack.isEmpty()) {
                    if (!changed) return finish(false);
                    reverseIndex = reversed.size(); phase = Phase.STEPS;
                } else if (stack.peek().index == 0) { stack.pop(); release(64); }
                else {
                    atom = stack.peek().previous();
                    if (atom instanceof PlanStep.Sequence sequence) push(sequence.children());
                    else { unit = atom; oldRuns = BigInteger.ONE; phase = Phase.UNWRAP; }
                }
            }
            case UNWRAP -> {
                if (oldRuns.signum() == 0) { phase = Phase.NEXT; break; }
                if (unit instanceof PlanStep.Repeat repeat) { oldRuns = oldRuns.multiply(BigInteger.valueOf(repeat.times())); unit = repeat.body(); }
                else if (unit instanceof PlanStep.Sequence sequence && sequence.children().size() == 1) unit = sequence.children().get(0);
                else {
                    if (unit instanceof PlanStep.Batch batch) {
                        oldRuns = oldRuns.multiply(BigInteger.valueOf(batch.runs())); unit = new PlanStep.Batch(batch.recipe(), 1);
                    }
                    if (oldRuns.signum() == 0) { phase = Phase.NEXT; break; }
                    // Composite repeat bodies stay opaque: never unroll or
                    // reorder their internal execution into independent batches.
                    computation = new SummaryComputation<>(unit, original.recipes(), budget); phase = Phase.SUMMARY;
                }
            }
            case SUPPORT -> {
                if (entries.hasNext()) {
                    var e = entries.next(); K key = e.getKey(); BigInteger delta = e.getValue();
                    BigInteger before = value(inventory, key).subtract(delta.multiply(oldRuns));
                    put(inventory, key, before);
                    if (delta.signum() > 0) {
                        runs = runs.max(ceil(value(required, key).subtract(before), delta));
                        if (forceCraft && key.equals(original.target())) {
                            BigInteger prefix = before.subtract(original.initialExact().getOrDefault(key, BigInteger.ZERO));
                            runs = runs.max(ceil(targetCredit.subtract(prefix).subtract(tailTarget), delta));
                        }
                    }
                } else {
                    if (runs.compareTo(oldRuns) > 0) return finish(false);
                    entries = unitSummary.delta().entrySet().iterator(); phase = Phase.APPLY;
                }
            }
            case APPLY -> {
                if (runs.signum() > 0 && entries.hasNext()) {
                    var e = entries.next(); K key = e.getKey(); BigInteger delta = e.getValue();
                    BigInteger first = unitSummary.required(key).add(delta.negate().max(BigInteger.ZERO).multiply(runs.subtract(BigInteger.ONE)));
                    put(required, key, first.max(value(required, key).subtract(delta.multiply(runs))));
                } else { entries = null; phase = Phase.EMIT; }
            }
            case EMIT -> {
                changed |= !runs.equals(oldRuns);
                tailTarget = tailTarget.add(unitSummary.delta(original.target()).multiply(runs));
                if (runs.signum() > 0) {
                    reserve(64);
                    reversed.add(runs.equals(oldRuns) ? atom : PlanStep.repeat(unit, runs));
                }
                atom = null; unit = null; unitSummary = null; phase = Phase.NEXT;
                release(unitBytes); unitBytes = 0;
            }
            case STEPS -> {
                if (reverseIndex > 0) { reserve(16); steps.add(reversed.get(--reverseIndex)); }
                else { entries = required.entrySet().iterator(); phase = Phase.DEFICITS; }
            }
            case DEFICITS -> {
                if (entries.hasNext()) {
                    var e = entries.next(); K key = e.getKey(); BigInteger need = e.getValue();
                    if (need.compareTo(original.initialExact().getOrDefault(key, BigInteger.ZERO)) > 0) return finish(false);
                    long have = ignoreTargetStock && key.equals(original.target()) ? 0 : stock.getOrDefault(key, 0L);
                    BigInteger deficit = external.contains(key) ? BigInteger.ZERO : need.subtract(BigInteger.valueOf(have)).max(BigInteger.ZERO);
                    if (deficit.compareTo(original.missingExact().getOrDefault(key, BigInteger.ZERO)) > 0) return finish(false);
                    if (deficit.signum() > 0) put(missing, key, deficit);
                } else {
                    entries = null;
                    candidate = GraphPlan.ownedRefill(original, FrozenPlanSteps.owned(steps), original.recipes(), required, missing,
                            missing.isEmpty() ? GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL : original.result(), budget.nodes());
                    verification = verify(candidate); phase = Phase.VERIFY;
                }
            }
            default -> throw new IllegalStateException("Unexpected refill pruning phase " + phase);
        }
        return false;
    }

    private boolean beginOriginal() {
        if (forceCraft) {
            targetCredit = summary.delta(original.target());
            // Some indivisible forced plans need a separate count proof.
            // Preserve those unchanged, rather than weakening its premise.
            if (targetCredit.compareTo(BigInteger.valueOf(original.amount())) < 0) return finish(false);
        }
        entries = original.initialExact().entrySet().iterator(); phase = Phase.INITIAL; return false;
    }

    private static BigInteger ceil(BigInteger gap, BigInteger gain) { return CheckedAmounts.ceilDiv(gap.max(BigInteger.ZERO), gain); }
    private BigInteger value(Map<K, BigInteger> map, K key) { return map.getOrDefault(key, BigInteger.ZERO); }
    private void put(Map<K, BigInteger> map, K key, BigInteger value) {
        if (value.signum() == 0) { if (map.remove(key) != null) release(128); return; }
        if (!map.containsKey(key)) reserve(128);
        map.put(key, value);
    }
    private long retain(SequenceSummary<K> value) {
        long bytes = 128L * ((long) value.delta().size() + value.required().size() + value.peak().size());
        reserve(bytes); return bytes;
    }
    private PlanVerification<K> verify(GraphPlan<K> plan) {
        return new PlanVerification<>(GraphPlan.ownedRefill(plan, plan.steps(), plan.recipes(), plan.initialExact(), Map.of(),
                GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, budget.nodes()), budget);
    }
    private void push(List<PlanStep> children) { reserve(64); stack.push(new Cursor(children)); }
    private static final class Cursor {
        final List<PlanStep> children;
        int index;
        Cursor(List<PlanStep> children) { this.children = children; index = children.size(); }
        PlanStep previous() { return children.get(--index); }
    }
    private boolean finish(boolean accept) {
        if (accept) result = candidate;
        phase = Phase.DONE;
        budget.note("refill_word", (accept ? "pruned" : "retained_original") + "; work=" + (budget.nodes() - started));
        return true;
    }
    GraphPlan<K> result() { return result; }
    private void reserve(long bytes) { budget.reserve(bytes); memory += bytes; }
    private void release(long bytes) { budget.release(bytes); memory -= bytes; }
    @Override public void close() {
        if (verification != null) { verification.close(); verification = null; }
        if (computation != null) { computation.close(); computation = null; }
        entries = null; seeds = null; recipes = null;
        budget.release(memory); memory = 0;
    }
}
