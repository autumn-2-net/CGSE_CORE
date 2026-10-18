// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Long flat runs cannot hide eligible nested turnover reductions. */
public final class PlanFlowPruningFlatTest {
    private static int checks;
    private static final Map<String, GraphRecipe<String>> RECIPES = Map.of(
            "fill", recipe("fill", Map.of(), Map.of("P", 1L)),
            "bc", recipe("bc", Map.of("B", 1L), Map.of("C", 1L)),
            "ca", recipe("ca", Map.of("C", 1L), Map.of("A", 1L)),
            "ab", recipe("ab", Map.of("A", 1L), Map.of("B", 1L)));
    private static final PlanStep TURN = new PlanStep.Sequence(List.of(new PlanStep.Batch("bc", 2),
            new PlanStep.Batch("ca", 2), new PlanStep.Batch("ab", 1)));

    public static void main(String[] args) {
        for (int size : new int[]{257, 484, 644, 4096}) {
            var steps = fill(size - 3); steps.addAll(((PlanStep.Sequence) TURN).children());
            var word = new PlanStep.Sequence(steps); var budget = budget();
            check(PlanFlowPruning.optimize(word, RECIPES, budget) == word, "Changed an unsupported flat run");
            check(budget.reservedBytes() == 0 && budget.peakBytes() == 0 && budget.searchWork() <= size,
                    "Allocated/traversed an ineligible identity DAG");
        }
        var steps = fill(253); steps.addAll(((PlanStep.Sequence) TURN).children());
        reduced(new PlanStep.Sequence(steps), 253);
        for (int at : new int[]{0, 128, 256}) {
            steps = fill(257); steps.set(at, TURN);
            reduced(new PlanStep.Sequence(steps), 256);
        }
        reduced(new PlanStep.Repeat(TURN, 2), 0);
        var cancelled = budget(); cancelled.cancel();
        try { PlanFlowPruning.optimize(new PlanStep.Sequence(fill(257)), RECIPES, cancelled); throw new AssertionError("Ignored cancellation"); }
        catch (CancellationException expected) { checks++; }
        check(cancelled.reservedBytes() == 0, "Cancelled admission leaked");
        System.out.println("PlanFlowPruningFlatTest: assertions=" + checks);
    }

    private static void reduced(PlanStep word, long fills) {
        var budget = budget(); var pruned = PlanFlowPruning.optimize(word, RECIPES, budget);
        var counts = PlanCountComputation.of(pruned);
        check(counts.getOrDefault("ab", BigInteger.ZERO).signum() == 0, "Skipped eligible nested/small turnover");
        check(counts.getOrDefault("fill", BigInteger.ZERO).equals(BigInteger.valueOf(fills)), "Dropped useful production");
        SequenceSummary<String> before, after;
        try (var computation = new SummaryComputation<>(word, RECIPES, budget)) { while (!computation.step()) {} before = computation.result(); }
        try (var computation = new SummaryComputation<>(pruned, RECIPES, budget)) { while (!computation.step()) {} after = computation.result(); }
        for (String key : before.keys()) check(after.required(key).compareTo(before.required(key)) <= 0 &&
                after.delta(key).compareTo(before.delta(key)) >= 0, "Worsened prefix / net balance");
        check(budget.reservedBytes() == 0, "Nested reduction leaked");
    }

    private static ArrayList<PlanStep> fill(int count) {
        var steps = new ArrayList<PlanStep>();
        for (int i = 0; i < count; i++) steps.add(new PlanStep.Batch("fill", 1));
        return steps;
    }
    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 1_000_000, 16L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
