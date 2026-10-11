// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Real candidate handoffs at a controlled local allowance boundary. */
public final class CountCandidateContinuationTest {
    public static void main(String[] args) throws Exception {
        for (String stage : List.of("scheduling", "assembling", "verifying")) {
            continuation(stage, false);
            continuation(stage, true);
        }
        spentCeiling();
        System.out.println("Candidate continuation: schedule/assembly/verification, fixed ceiling, cancellation and cleanup passed");
    }

    private static void continuation(String stage, boolean cancel) throws Exception {
        var recipe = HistoricalFixtures.r("craft", Map.of("raw", 1L), Map.of("goal", 1L));
        var budget = budget();
        try (var search = new IntegerCountSearch<>(new GraphCompiler<>(List.of(recipe)), "goal", 1,
                Map.of("raw", 1L), Map.of(), Set.of(), Set.of(), false, true, budget, System.nanoTime())) {
            search.scout(1);
            var branch = root(search);
            // Drive actual propagation and count discovery in small steps until
            // the candidate reaches the requested downstream stage.
            while (get(branch, stage) == null) {
                check(branch.state == IntegerCountBranch.State.OPEN, "Candidate ended before " + stage);
                branch.run(1, List.of(), List.of(), List.of(), null, () -> false);
            }
            // Simulate a scout whose existing local allowance is spent. Only
            // accounting observations change; the real candidate is untouched.
            set(search, "work", 1L);
            check(search.result() == null, "Unverified counts became a plan");
            if (cancel) {
                budget.cancel();
                boolean stopped = false;
                try { search.step(); } catch (CancellationException expected) { stopped = true; }
                check(stopped, "Candidate preference bypassed cancellation");
            } else {
                search.step();
                check(!search.paused(), "Lost candidate at " + stage + " handoff");
                check((long) get(search, "allowance") <= 4L, "Continuation enlarged the fixed scout ceiling");
                while (!search.step()) {}
                while (search.paused()) {
                    search.resume();
                    while (!search.step()) {}
                }
                var plan = search.result();
                check(plan != null && plan.feasible(), "Continued candidate was lost");
                var summary = HistoricalFixtures.summary(plan.steps(), Map.of(recipe.id(), recipe));
                HistoricalFixtures.verify(new HistoricalFixtures.Fixture(stage, List.of(recipe), Map.of("raw", 1L), "goal", 1, true), summary);
            }
        }
        check(budget.reservedBytes() == 0, "Candidate handoff leaked memory");
    }

    private static void spentCeiling() throws Exception {
        var recipe = HistoricalFixtures.r("craft", Map.of("raw", 1L), Map.of("goal", 1L));
        var budget = budget();
        try (var search = new IntegerCountSearch<>(new GraphCompiler<>(List.of(recipe)), "goal", 1,
                Map.of("raw", 1L), Map.of(), Set.of(), Set.of(), false, true, budget, System.nanoTime())) {
            search.scout(1);
            var branch = root(search);
            while (branch.scheduling == null) {
                check(branch.state == IntegerCountBranch.State.OPEN, "No scheduling stage");
                branch.run(1, List.of(), List.of(), List.of(), null, () -> false);
            }
            set(search, "allowance", 4L);
            set(search, "work", 4L);
            check(search.step() && search.paused(), "Live candidate bypassed the spent scout ceiling");
            check(search.result() == null && !search.infeasible(), "Suspension was treated as a conclusion");
        }
        check(budget.reservedBytes() == 0, "Capped candidate leaked memory");
    }

    @SuppressWarnings("unchecked")
    private static IntegerCountBranch<String> root(IntegerCountSearch<String> search) throws Exception {
        return ((Deque<IntegerCountBranch<String>>) get(search, "pending")).getFirst();
    }

    private static PlanningBudget budget() { return new PlanningBudget(0, 1_000_000, 64L << 20, () -> false, System::nanoTime); }
    private static Object get(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
