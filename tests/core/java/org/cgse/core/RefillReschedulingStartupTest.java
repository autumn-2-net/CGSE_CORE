// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;

/** A cheaper finite bill must not spend the last seed of an unused producer. */
public final class RefillReschedulingStartupTest {
    private static final Map<String, Long> STOCK = Map.of("A", 2L, "tool", 1L);
    private static int assertions;

    public static void main(String[] args) {
        for (boolean reversed : List.of(false, true)) {
            var fixture = fixture(reversed, true);
            check(remainingA(fixture.plan) == 1, "Original witness does not retain the startup");
            var guarded = run(fixture, Set.of("source"), 100_000, 8L << 20);
            check(guarded.missingExact().equals(Map.of("R", BigInteger.TWO)), "Accepted a cheaper bill that consumes the growth seed");
            check(remainingA(guarded) == 1 && guarded.seeds().equals(Map.of("tool", 1L)), "Changed the verified incumbent");
            var renewable = run(fixture, Set.of(), 100_000, 8L << 20);
            check(renewable.missingExact().equals(Map.of("R", BigInteger.ONE)), "Lost a valid terminal-source improvement");
            check(remainingA(renewable) == 0, "Control no longer exercises startup depletion");
            var raw = run(fixture, Set.of("source", "grow"), 100_000, 8L << 20);
            check(raw.missingExact().equals(Map.of("R", BigInteger.ONE)), "Excluded production was treated as an active loop");
            var ordinary = run(fixture(reversed, false), Set.of("source"), 100_000, 8L << 20);
            check(ordinary.missingExact().equals(Map.of("R", BigInteger.ONE)), "Ordinary mode inherited automatic preservation");

            for (long work : new long[]{1, 64, 512, 4096}) for (long bytes : new long[]{512, 2048, 8192, 32768}) {
                var limited = run(fixture, Set.of("source"), work, bytes);
                check(remainingA(limited) >= 1, "A work or memory cutoff published a depleted seed");
            }
            for (int stop : new int[]{0, 1, 2, 4, 8, 16, 32, 64}) {
                var budget = budget(100_000, 8L << 20);
                try (var search = new RefillRescheduling<>(fixture.plan, STOCK, Set.of(), true, false,
                        fixture.catalog, Set.of("source"), budget)) {
                    boolean done = false;
                    for (int i = 0; i < stop && !done; i++) done = search.step();
                    var retained = search.result(); budget.cancel();
                    if (!done) {
                        try { search.step(); throw new AssertionError("Cancellation swallowed"); }
                        catch (CancellationException expected) { }
                    }
                    check(search.result() == retained && remainingA(retained) >= 1, "Cancellation published a pending candidate");
                }
                check(budget.reservedBytes() == 0, "Cancelled startup guard leaked");
            }
        }
        System.out.println("RefillReschedulingStartupTest PASS: " + assertions + " checks");
    }

    private record Fixture(GraphPlan<String> plan, List<GraphRecipe<String>> catalog) {}

    private static Fixture fixture(boolean reversed, boolean preserve) {
        var safe = recipe("safe", Map.of("R", 1L, "tool", 1L), Map.of("G", 1L, "tool", 1L));
        var spend = recipe("spend", Map.of("A", 1L, "tool", 1L), Map.of("G", 1L, "tool", 1L));
        var grow = recipe("grow", Map.of("A", 1L), Map.of("A", 2L));
        var source = recipe("source", Map.of("raw", 1L), Map.of("A", 1L));
        var catalog = new ArrayList<>(List.of(safe, spend, grow, source));
        if (reversed) Collections.reverse(catalog);
        var bindings = new LinkedHashMap<String, GraphRecipe<String>>();
        for (var recipe : catalog) if (recipe == safe || recipe == spend) bindings.put(recipe.id(), recipe);
        var word = new PlanStep.Sequence(List.of(new PlanStep.Batch("safe", 2), new PlanStep.Batch("spend", 1)));
        var seeds = Map.of("tool", 1L);
        var plan = new GraphPlan<>("G", 3, preserve, word, bindings,
                PlanVerifier.initialExact(word, bindings, "G", 3, seeds), seeds, Map.of("R", BigInteger.TWO),
                GraphPlan.Result.MISSING_INPUT, 0, 0);
        verifyFunded(plan);
        return new Fixture(plan, catalog);
    }

    private static GraphPlan<String> run(Fixture fixture, Set<String> excluded, long work, long bytes) {
        var budget = budget(work, bytes);
        GraphPlan<String> result;
        try (var search = new RefillRescheduling<>(fixture.plan, STOCK, Set.of(), true, false,
                fixture.catalog, excluded, budget)) {
            while (!search.step()) { }
            result = search.result();
        }
        check(budget.reservedBytes() == 0, "Rescheduling startup verification leaked");
        verifyFunded(result);
        return result;
    }

    private static long remainingA(GraphPlan<String> plan) {
        return BigInteger.TWO.add(plan.missingExact().getOrDefault("A", BigInteger.ZERO))
                .add(SequenceSummary.of(plan.steps(), plan.recipes()).delta("A")).longValueExact();
    }

    private static void verifyFunded(GraphPlan<String> plan) {
        PlanVerifier.verify(new GraphPlan<>(plan.target(), plan.amount(), plan.preserveSeeds(), plan.steps(), plan.recipes(),
                plan.initialExact(), plan.seeds(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0));
        plan.initialExact().forEach((key, required) -> check(required.compareTo(BigInteger.valueOf(STOCK.getOrDefault(key, 0L))
                .add(plan.missingExact().getOrDefault(key, BigInteger.ZERO))) <= 0, "Unfunded initial inventory: " + key));
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static PlanningBudget budget(long work, long bytes) { return new PlanningBudget(0, work, bytes, () -> false, System::nanoTime); }
    private static void check(boolean ok, String why) { assertions++; if (!ok) throw new AssertionError(why); }
}
