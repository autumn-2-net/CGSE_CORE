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
    private enum Phase { RECIPES, STRUCTURE, CONSUMED, PRODUCED, ORIGINAL, INITIAL, FINAL, GOALS, NEXT, UNWRAP, SUMMARY, SUPPORT, APPLY, EMIT, STEPS, DEFICITS, VERIFY, BLOCKS, BLOCK_PRUNE, DONE }
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
    private Deque<Iterator<PlanStep>> structure;
    private Set<K> consumed;
    private Iterator<K> ports;
    private GraphRecipe<K> inspecting;
    private PlanVerification<K> verification;
    private SummaryComputation<K> computation;
    private RefillWordBlocks blocks;
    private RefillWordPruning<K> blockPruning;
    private SequenceSummary<K> summary, unitSummary;
    private GraphPlan<K> candidate, result;
    private GraphPlan<K> regrouped;
    private PlanStep atom, unit;
    private BigInteger oldRuns, runs, targetCredit = BigInteger.ZERO, tailTarget = BigInteger.ZERO;
    private boolean feedback, changed, allocated;
    private boolean allowBlocks = true;
    private Phase phase = Phase.RECIPES;
    private int reverseIndex, flatBatches;
    private long memory, summaryBytes, unitBytes, structureBytes, structureStarted;

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
            if (budget.nodes() - started >= allowance) return finish();
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
                if (forceCraft && verification.summary().delta(original.target()).compareTo(targetCredit) < 0) return finish();
                return complete(true);
            }
            if (phase == Phase.BLOCKS) {
                if (!blocks.step()) return false;
                if (blocks.result() == null) return finish();
                regrouped = GraphPlan.ownedRefill(result, blocks.result(), result.recipes(), result.initialExact(), result.missingExact(),
                        result.result(), budget.nodes());
                // Regrouping preserves every batch boundary and its order. The
                // completed incumbent verifier can lend the same exact fold;
                // this parent keeps that verifier's charge through child close.
                var known = verification != null && result == candidate ? verification.summary() : null;
                blockPruning = new RefillWordPruning<>(regrouped, stock, external, forceCraft, ignoreTargetStock, budget,
                        allowance - (budget.nodes() - started), known);
                blockPruning.allowBlocks = false; phase = Phase.BLOCK_PRUNE; return false;
            }
            if (phase == Phase.BLOCK_PRUNE) {
                if (!blockPruning.step()) return false;
                if (blockPruning.result() != regrouped) result = blockPruning.result();
                return finish();
            }
            for (int i = 0; i < 64; i++) {
                budget.check();
                if (advance()) return true;
                if (phase == Phase.ORIGINAL || phase == Phase.SUMMARY || phase == Phase.VERIFY || phase == Phase.BLOCKS) return false;
            }
            return false;
        } catch (PlanningBudget.Exhausted limit) {
            // A verified bill is already available. Optional quality work must
            // not turn it into a limit result; cancellation still propagates.
            budget.failureDetail(retainedFailure); return finish();
        }
    }

    private boolean advance() {
        switch (phase) {
            case RECIPES -> {
                if (recipes.hasNext()) {
                    var recipe = recipes.next();
                    if (recipe.batchSensitiveInputs()) return finish();
                    feedback |= recipe.hasInputReturns();
                } else {
                    recipes = null;
                    if (feedback) return startOriginal();
                    // A returned startup resource can cross several recipes.
                    // Inspect the actual word, not arbitrary catalog ordering.
                    // Like the flat flow pass, a later output of an already
                    // consumed key is a hint; the exact verifier remains final.
                    structure = new ArrayDeque<>(); consumed = new HashSet<>();
                    structureStarted = budget.nodes(); pushStructure(List.of(original.steps())); phase = Phase.STRUCTURE;
                }
            }
            case STRUCTURE, CONSUMED, PRODUCED -> {
                if (budget.nodes() - structureStarted > Math.min(1024, Math.max(128, allowance / 4))) return finishStructure(false);
                if (phase == Phase.CONSUMED) {
                    if (ports.hasNext()) {
                        K key = ports.next();
                        if (!consumed.contains(key)) { reserveStructure(96); consumed.add(key); }
                    } else { ports = inspecting.outputs().keySet().iterator(); phase = Phase.PRODUCED; }
                } else if (phase == Phase.PRODUCED) {
                    if (ports.hasNext()) {
                        if (consumed.contains(ports.next())) return finishStructure(true);
                    } else { ports = null; inspecting = null; phase = Phase.STRUCTURE; }
                } else if (structure.isEmpty()) return finishStructure(false);
                else if (!structure.peek().hasNext()) { structure.pop(); release(64); structureBytes -= 64; }
                else {
                    PlanStep step = structure.peek().next();
                    if (step instanceof PlanStep.Sequence sequence) pushStructure(sequence.children());
                    else if (step instanceof PlanStep.Repeat repeat) {
                        // One structural body visit regardless of its count.
                        // Never enumerate execution occurrences of a huge loop.
                        if (repeat.times() > 0) pushStructure(List.of(repeat.body()));
                    } else if (((PlanStep.Batch) step).runs() > 0) {
                        inspecting = original.recipes().get(((PlanStep.Batch) step).recipe());
                        ports = inspecting.inputs().keySet().iterator(); phase = Phase.CONSUMED;
                    }
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
                    if (!changed) return complete(false);
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
                    if (runs.compareTo(oldRuns) > 0) return finish();
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
                    if (atom instanceof PlanStep.Batch) flatBatches++;
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
                    if (need.compareTo(original.initialExact().getOrDefault(key, BigInteger.ZERO)) > 0) return finish();
                    long have = ignoreTargetStock && key.equals(original.target()) ? 0 : stock.getOrDefault(key, 0L);
                    BigInteger deficit = external.contains(key) ? BigInteger.ZERO : need.subtract(BigInteger.valueOf(have)).max(BigInteger.ZERO);
                    if (deficit.compareTo(original.missingExact().getOrDefault(key, BigInteger.ZERO)) > 0) return finish();
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

    private boolean startOriginal() {
        if (summary != null) return beginOriginal();
        verification = verify(original); phase = Phase.ORIGINAL; return false;
    }
    private void reserveStructure(long bytes) { reserve(bytes); structureBytes += bytes; }
    private void pushStructure(List<PlanStep> children) { reserveStructure(64); structure.push(children.iterator()); }
    private boolean finishStructure(boolean found) {
        // Drop private scratch without an unbounded clear loop. No published
        // plan or borrowed summary can reference these traversal containers.
        structure = null; consumed = null; ports = null; inspecting = null;
        release(structureBytes); structureBytes = 0;
        return found ? startOriginal() : finish();
    }

    private boolean beginOriginal() {
        if (forceCraft) {
            targetCredit = summary.delta(original.target());
            // Some indivisible forced plans need a separate count proof.
            // Preserve those unchanged, rather than weakening its premise.
            if (targetCredit.compareTo(BigInteger.valueOf(original.amount())) < 0) return finish();
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
        return PlanVerification.refill(GraphPlan.ownedRefill(plan, plan.steps(), plan.recipes(), plan.initialExact(), Map.of(),
                GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, budget.nodes()), budget);
    }
    private void push(List<PlanStep> children) { reserve(64); stack.push(new Cursor(children)); }
    private static final class Cursor {
        final List<PlanStep> children;
        int index;
        Cursor(List<PlanStep> children) { this.children = children; index = children.size(); }
        PlanStep previous() { return children.get(--index); }
    }
    private boolean complete(boolean accept) {
        if (accept) result = candidate;
        // The original per-batch pass runs first with its unchanged allowance.
        // Further grouping starts from that completed incumbent, and every
        // interruption keeps it. One child pass only; no recursive fixed point.
        long left = allowance - (budget.nodes() - started);
        if (allowBlocks && flatBatches >= 4 && left >= 512) {
            blocks = new RefillWordBlocks(reversed, budget, Math.min(2048, left / 2));
            phase = Phase.BLOCKS; return false;
        }
        return finish();
    }
    private boolean finish() {
        phase = Phase.DONE;
        budget.note("refill_word", (result != original ? "pruned" : "retained_original") + "; work=" + (budget.nodes() - started));
        return true;
    }
    GraphPlan<K> result() { return result; }
    private void reserve(long bytes) { budget.reserve(bytes); memory += bytes; }
    private void release(long bytes) { budget.release(bytes); memory -= bytes; }
    @Override public void close() {
        if (blockPruning != null) { blockPruning.close(); blockPruning = null; }
        if (blocks != null) { blocks.close(); blocks = null; }
        if (verification != null) { verification.close(); verification = null; }
        if (computation != null) { computation.close(); computation = null; }
        entries = null; seeds = null; recipes = null;
        structure = null; consumed = null; ports = null; inspecting = null;
        budget.release(memory); memory = 0;
    }
}
