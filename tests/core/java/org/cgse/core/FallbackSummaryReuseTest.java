// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Reusing immutable arithmetic must still check the new prefix, goals and seeds. */
public final class FallbackSummaryReuseTest {
    private static final BigInteger ONE = BigInteger.ONE, ZERO = BigInteger.ZERO;
    private static int checks;
    public static void main(String[] args) throws Exception {
        foldedVerification(); refillSuffix(); smallRefinement(); admissionBoundary(); interruptions(); scheduler();
        System.out.println("FallbackSummaryReuseTest: assertions=" + checks);
    }
    private static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return MissingMaterialExpansionTest.recipe(id, in, out);
    }
    private static GraphRecipe<String> use(boolean tool) {
        var slots = new ArrayList<GraphRecipe.Slot<String>>(); slots.add(new GraphRecipe.Slot<>("mid", 1));
        if (tool) slots.add(new GraphRecipe.Slot<>("tool", 1, 0, true, true));
        return new GraphRecipe<>("use", "use", slots, tool ? Map.of("G", 1L, "tool", 1L) : Map.of("G", 1L));
    }
    private static GraphPlan<String> plan(PlanStep word, Map<String, GraphRecipe<String>> recipes,
            Map<String, BigInteger> initial, Map<String, Long> seeds, long amount, boolean missing) {
        return new GraphPlan<>("G", amount, true, word, recipes, initial, seeds, missing ? initial : Map.of(),
                missing ? GraphPlan.Result.MISSING_INPUT : GraphPlan.Result.FEASIBLE, 0, 0);
    }
    private static void foldedVerification() {
        for (boolean tool : List.of(false, true)) for (BigInteger count : List.of(BigInteger.valueOf(300), ONE.shiftLeft(80)))
        for (boolean summaryOnly : List.of(false, true)) {
            var recipe = use(tool); var recipes = Map.of("use", recipe);
            var word = new PlanStep.Sequence(List.of(PlanStep.batch("use", count)));
            var initial = new LinkedHashMap<String, BigInteger>(); initial.put("mid", count); if (tool) initial.put("tool", ONE);
            var seeds = tool ? Map.of("tool", 1L) : Map.<String, Long>of();
            var p = plan(word, recipes, initial, seeds, 300, false); var summary = SequenceSummary.of(word, recipes);
            var b = budget(1_000_000);
            try (var fast = summaryOnly ? PlanVerification.refillAfterWork(p, b, 0, word, summary) : PlanVerification.folded(p, b, word, summary);
                 var full = new PlanVerification<>(p, b)) {
                while (!fast.step()) {} while (!full.step()) {}
                same(fast.summary(), full.summary());
                if (!summaryOnly) {
                    check(fast.patternCounts().equals(full.patternCounts()), "Lost production counts");
                    check(fast.physicalProduced("G").equals(count) && fast.physicalProduced("tool").signum() == 0, "Invented physical tool output");
                } else {
                    try { fast.patternCounts(); throw new AssertionError("Missing metadata accepted as force credit"); }
                    catch (IllegalStateException expected) { checks++; }
                    try { fast.physicalProduced("G"); throw new AssertionError("Missing metadata accepted as production"); }
                    catch (IllegalStateException expected) { checks++; }
                }
            }
            check(b.reservedBytes() == 0, "Fold verification leaked");
            for (String kind : List.of("input", "goal", "seed", "prefix", "changed-binding")) {
                var changedInitial = new LinkedHashMap<>(initial); var changedSeeds = new LinkedHashMap<>(seeds);
                PlanStep changedWord = word; Map<String, GraphRecipe<String>> changedRecipes = recipes;
                if (kind.equals("input")) changedInitial.put("mid", count.subtract(ONE));
                if (kind.equals("goal")) changedInitial.put("G", count.negate().max(ZERO));
                if (kind.equals("seed")) changedSeeds.put("absent", 1L);
                if (kind.equals("prefix")) changedWord = new PlanStep.Sequence(List.of(new PlanStep.Batch("use", 1), word));
                if (kind.equals("changed-binding")) {
                    // Equal geometry is not the same immutable word. Public
                    // input and a fresh word must use their actual bindings.
                    changedWord = new PlanStep.Sequence(new ArrayList<>(word.children()));
                    changedRecipes = Map.of("use", r("use", Map.of("absent", 1L), Map.of("G", 1L)));
                }
                long amount = kind.equals("goal") ? 301 : 300;
                if (kind.equals("goal") && count.compareTo(BigInteger.valueOf(300)) > 0) continue;
                var bad = plan(changedWord, changedRecipes, changedInitial, changedSeeds, amount, false);
                var checkBudget = budget(1_000_000);
                try (var verification = summaryOnly ? PlanVerification.refillAfterWork(bad, checkBudget, 0, word, summary) :
                        PlanVerification.folded(bad, checkBudget, word, summary)) {
                    try { while (!verification.step()) {} throw new AssertionError("Reused stale " + kind); }
                    catch (IllegalArgumentException expected) { checks++; }
                }
                check(checkBudget.reservedBytes() == 0, "Rejected verifier leaked");
            }
        }
    }
    private static List<GraphRecipe<String>> chain(boolean tool, int length) {
        var recipes = new ArrayList<GraphRecipe<String>>(); recipes.add(use(tool));
        for (int i = 0; i < length; i++) recipes.add(r("make" + i,
                Map.of(i == length - 1 ? "raw" : "m" + (i + 1), 1L), Map.of(i == 0 ? "mid" : "m" + i, 1L)));
        return recipes;
    }
    private static GraphPlan<String> suffix(boolean tool, BigInteger runs) {
        var initial = new LinkedHashMap<String, BigInteger>(); initial.put("mid", runs); if (tool) initial.put("tool", ONE);
        var steps = new ArrayList<PlanStep>();
        if (runs.equals(BigInteger.valueOf(512))) for (int i = 0; i < 512; i++) steps.add(new PlanStep.Batch("use", 1));
        else steps.add(PlanStep.batch("use", runs));
        return plan(new PlanStep.Sequence(steps), Map.of("use", use(tool)), initial, tool ? Map.of("tool", 1L) : Map.of(),
                runs.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact(), true);
    }
    private static void refillSuffix() {
        for (boolean tool : List.of(false, true)) for (boolean reverse : List.of(false, true))
        for (BigInteger runs : List.of(BigInteger.valueOf(512), ONE.shiftLeft(70))) {
            var source = suffix(tool, runs); var summary = SequenceSummary.of(source.steps(), source.recipes());
            var catalog = chain(tool, 192); if (reverse) Collections.reverse(catalog);
            GraphPlan<String> baseline = null; long baselineWork = 0;
            for (String mode : List.of("full", "reuse", "different-plan", "refused")) {
                var b = budget(1_000_000); var stock = tool ? Map.of("tool", 1L) : Map.<String, Long>of();
                try (var expansion = new MissingMaterialExpansion<>(new GraphCompiler<>(catalog), source, stock, Set.of(), Set.of(), true, true, b)) {
                    long extra = 0;
                    if (mode.equals("refused")) { extra = b.availableBytes() - 128; b.reserve(extra); }
                    if (!mode.equals("full")) {
                        var offered = mode.equals("different-plan") ? plan(source.steps(), source.recipes(), source.initialExact(), source.seeds(), source.amount(), true) : source;
                        expansion.reuseSuffix(offered, summary);
                    }
                    if (extra != 0) { check(b.reservedBytes() == extra, "Optional cache ignored refusal"); b.release(extra); }
                    while (!expansion.step()) {}
                    var p = expansion.result();
                    check(p.missingExact().equals(Map.of("raw", runs)), "Lost raw conservation bound");
                    verify(p);
                    if (mode.equals("reuse") && runs.equals(BigInteger.valueOf(512))) literal(p);
                    if (baseline == null) { baseline = p; baselineWork = b.nodes(); }
                    else {
                        check(p.initialExact().equals(baseline.initialExact()) && p.patternTimesExact().equals(baseline.patternTimesExact()), "Changed completed word/bill");
                        if (!mode.equals("reuse")) check(b.nodes() == baselineWork, "Declined cache changed scheduling");
                        else check(b.nodes() < baselineWork, "Completed suffix was folded again");
                    }
                }
                check(b.reservedBytes() == 0, "Suffix reservation leaked");
            }
        }
    }
    private static void smallRefinement() {
        var source = suffix(true, BigInteger.valueOf(512)); var compiler = new GraphCompiler<>(chain(true, 1));
        var summary = SequenceSummary.of(source.steps(), source.recipes()); long fullWork = 0;
        for (boolean reuse : List.of(false, true)) {
            var b = budget(1_000_000);
            try (var expansion = new MissingMaterialExpansion<>(compiler, source, Map.of("tool", 1L), Set.of(), Set.of(), true, true, b)) {
                if (reuse) expansion.reuseSuffix(source, summary);
                while (!expansion.step()) {} verify(expansion.result());
                if (!reuse) fullWork = b.nodes();
                else check(b.nodes() == fullWork, "Reuse shrank a small refinement's work-scaled quality allowance");
            }
            check(b.reservedBytes() == 0, "Unused suffix lease leaked");
        }
    }
    private static void admissionBoundary() {
        for (int leaves : new int[] {254, 255, 256, 512}) {
            var recipes = new ArrayList<GraphRecipe<String>>(); var inputs = new LinkedHashMap<String, Long>();
            for (int i = 0; i < leaves; i++) { inputs.put("p" + i, 1L); recipes.add(r("r" + i, Map.of("raw", 1L), Map.of("p" + i, 1L))); }
            recipes.add(r("join", inputs, Map.of("G", 1L))); var b = budget(1_000_000);
            var p = GraphFallback.plan(new GraphCompiler<>(recipes), "G", 1, Map.of(), Set.of(), Map.of(), false, true, b);
            check(p.missingExact().equals(Map.of("raw", BigInteger.valueOf(leaves))), "Changed admission-boundary bill");
            check(p.patternTimesExact().size() == leaves + 1 && p.patternTimesExact().values().stream().allMatch(ONE::equals), "Lost branch counts");
            verify(p); check(b.reservedBytes() == 0, "Admission boundary leaked");
        }
    }
    private static void interruptions() {
        var source = suffix(true, BigInteger.valueOf(512)); var summary = SequenceSummary.of(source.steps(), source.recipes());
        var compiler = new GraphCompiler<>(chain(true, 192));
        for (String mode : List.of("work", "time", "cancel")) for (long stop : new long[] {1, 128, 1024, 4096, 8192}) {
            var cutoff = new AtomicLong(stop); PlanningBudget[] owner = new PlanningBudget[1];
            var b = new PlanningBudget(mode.equals("time") ? 1 : 0, mode.equals("work") ? stop : 1_000_000, 32L << 20,
                    () -> mode.equals("cancel") && owner[0] != null && owner[0].nodes() >= cutoff.get(),
                    () -> mode.equals("time") && owner[0] != null && owner[0].nodes() >= cutoff.get() ? 2_000_000 : 0); owner[0] = b;
            try (var expansion = new MissingMaterialExpansion<>(compiler, source, Map.of("tool", 1L), Set.of(), Set.of(), true, true, b)) {
                expansion.reuseSuffix(source, summary);
                try { while (!expansion.step()) {} verify(expansion.result()); }
                catch (PlanningBudget.Exhausted e) { check(e.limit() == (mode.equals("time") ? PlanningBudget.Limit.TIMEOUT : PlanningBudget.Limit.SEARCH_LIMIT), "Wrong interruption reason"); }
                catch (CancellationException expected) { check(mode.equals("cancel"), "Unexpected cancellation"); }
            }
            check(b.reservedBytes() == 0, "Interrupted suffix retained workspace");
        }
    }
    private static void verify(GraphPlan<String> p) {
        var required = PlanVerifier.initialExact(p.steps(), p.recipes(), p.target(), p.amount(), p.seeds());
        required.forEach((key, value) -> check(p.initialExact().getOrDefault(key, ZERO).compareTo(value) >= 0, "Unfunded prefix/goal/seed"));
    }
    private static void literal(GraphPlan<String> plan) {
        var inventory = new HashMap<>(plan.initialExact()); var pending = new ArrayDeque<PlanStep>(); pending.push(plan.steps());
        int firings = 0;
        while (!pending.isEmpty()) {
            var step = pending.pop();
            if (step instanceof PlanStep.Sequence sequence) {
                for (int i = sequence.children().size() - 1; i >= 0; i--) pending.push(sequence.children().get(i));
            } else if (step instanceof PlanStep.Repeat repeat) {
                check(repeat.times() < 100000, "Literal oracle repetition too large");
                for (long i = 0; i < repeat.times(); i++) pending.push(repeat.body());
            } else {
                var batch = (PlanStep.Batch) step; var recipe = plan.recipes().get(batch.recipe());
                for (long i = 0; i < batch.runs(); i++) {
                    check(++firings < 1_000_000, "Literal oracle too large");
                    recipe.inputs().forEach((key, amount) -> {
                        var left = inventory.getOrDefault(key, ZERO).subtract(BigInteger.valueOf(amount));
                        check(left.signum() >= 0, "Unfunded primitive firing"); inventory.put(key, left);
                    });
                    recipe.outputs().forEach((key, amount) -> inventory.merge(key, BigInteger.valueOf(amount), BigInteger::add));
                }
            }
        }
        var remaining = inventory.getOrDefault(plan.target(), ZERO).subtract(BigInteger.valueOf(plan.amount()));
        check(remaining.signum() >= 0, "Undelivered target"); inventory.put(plan.target(), remaining);
        plan.seeds().forEach((key, amount) -> check(inventory.getOrDefault(key, ZERO).compareTo(BigInteger.valueOf(amount)) >= 0, "Lost final seed"));
    }
    private static void scheduler() throws Exception {
        var source = suffix(true, BigInteger.valueOf(512)); var b = budget(1_000_000);
        try (var expansion = new MissingMaterialExpansion<>(new GraphCompiler<>(chain(true, 192)), source,
                Map.of("tool", 1L), Set.of(), Set.of(), true, true, b)) {
            expansion.reuseSuffix(source, SequenceSummary.of(source.steps(), source.recipes()));
            check(!expansion.step(), "No continuation to transfer");
            try (var scheduler = new PlanningScheduler(1, 4, 64, 1_000_000_000)) {
                var future = scheduler.submit(new PlanningScheduler.Work<GraphPlan<String>>() {
                    public boolean advance(PlanningScheduler.Slice slice) {
                        while (slice.nextUncharged()) if (expansion.step()) return true;
                        return false;
                    }
                    public GraphPlan<String> result() { return expansion.result(); }
                    public void close() { expansion.close(); }
                }, b);
                verify(future.get(10, TimeUnit.SECONDS));
            }
        }
        check(b.reservedBytes() == 0, "Transferred suffix reservation leaked");
    }
    private static void same(SequenceSummary<String> a, SequenceSummary<String> b) {
        var keys = a.keys(); keys.addAll(b.keys());
        for (var key : keys) check(a.required(key).equals(b.required(key)) && a.delta(key).equals(b.delta(key)) && a.peak(key).equals(b.peak(key)), "Wrong reused fold");
    }
    private static PlanningBudget budget(long work) { return new PlanningBudget(0, work, 32L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
