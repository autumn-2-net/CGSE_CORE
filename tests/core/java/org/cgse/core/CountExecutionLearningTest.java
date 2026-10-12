// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** A dead primal candidate excludes exactly its original multiset, never its neighborhood. */
public final class CountExecutionLearningTest {
    public static void main(String[] args) {
        for (boolean unknown : new boolean[]{false, true}) candidate(unknown);
        System.out.println("Execution learning: dead speculative counts, alternative startup recipe, UNKNOWN scope, publication memory and refreshed inventory passed");
    }

    private static void candidate(boolean unknown) {
        var recipes = List.of(recipe("ab", Map.of("a", 1L), Map.of("b", 1L, "goal", 1L)),
                recipe("ba", Map.of("b", 1L), Map.of("a", 1L)),
                recipe("seed", Map.of("raw", 1L), Map.of("a", 1L)));
        var budget = new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime);
        var stock = Map.of("raw", 1L);
        try (var model = RecipeCountModel.create(new GraphCompiler<>(recipes), "goal", 1, stock, Map.of(), Set.of(), Set.of(), true, budget);
             var execution = new CountExecution<>(model, budget);
             var branch = new IntegerCountBranch<>(model, execution, "goal", 1, stock, Map.of(), Set.of(), false, true, budget, 0, List.of())) {
            BigInteger[] dead = model.recipes.stream().map(r -> r.id().equals("seed") ? BigInteger.ZERO : BigInteger.ONE).toArray(BigInteger[]::new);
            branch.initialized = true;
            branch.structuralCandidate = branch.structureResuming = true;
            branch.counts = dead;
            long held = unknown ? budget.availableBytes() : 0;
            budget.reserve(held);
            try {
                branch.scheduling = new CountSchedule<>(model, dead, budget);
            } finally { budget.release(held); }
            // The scheduler has already recorded its allocation refusal. Fund
            // the branch again so UNKNOWN actually reaches the publication
            // boundary, instead of stopping at the branch's own workspace lease.
            while (branch.state == IntegerCountBranch.State.OPEN)
                branch.run(256, List.of(), List.of(), List.of(), null, () -> false);
            check(branch.state == IntegerCountBranch.State.UNRESOLVED && !branch.proofContradiction, "primal failure closed the count domain");
            if (unknown) check(branch.learnedChoices.isEmpty(), "UNKNOWN exported a count exclusion");
            else {
                check(branch.learnedChoices.size() == 1, "dead speculative vector bypassed publication");
                var conflict = branch.learnedChoices.iterator().next();
                check(conflict.impliedBy(dead, dead, budget), "old multiset not excluded");
                var sessions = new CountSessions();
                sessions.remember(model, List.of(conflict), budget);
                check(sessions.reuse(model, budget).isEmpty(), "execution-only fact persisted as a count-row proof");
                var repaired = dead.clone();
                for (int i = 0; i < repaired.length; i++) if (model.recipes.get(i).id().equals("seed")) repaired[i] = BigInteger.ONE;
                check(!conflict.impliedBy(repaired, repaired, budget), "exclusion removed the startup repair");
                try (var schedule = new CountSchedule<>(model, repaired, budget)) {
                    while (!schedule.step()) {}
                    check(schedule.result() == CountSchedule.Result.WITNESS, "alternative source cannot repair test candidate");
                }
                long before = budget.reservedBytes();
                branch.learnedChoices.clear(); branch.releaseExecutionConflicts();
                check(budget.reservedBytes() < before, "publication retained the staging reservation");
                try (var refreshed = RecipeCountModel.create(new GraphCompiler<>(recipes), "goal", 1, Map.of("raw", 1L, "a", 1L), Map.of(), Set.of(), Set.of(), true, budget);
                     var schedule = new CountSchedule<>(refreshed, refreshed.recipes.stream().map(r -> r.id().equals("seed") ? BigInteger.ZERO : BigInteger.ONE).toArray(BigInteger[]::new), budget)) {
                    check(sessions.reuse(refreshed, budget).isEmpty(), "execution conflict survived refreshed inventory");
                    while (!schedule.step()) {}
                    check(schedule.result() == CountSchedule.Result.WITNESS, "old inventory exclusion escaped its request");
                }
            }
        }
        check(budget.reservedBytes() == 0, "candidate learning leaked memory");
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
