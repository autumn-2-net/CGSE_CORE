// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Scheduling must resume even if the count proposer never emits that vector again. */
public final class CountScheduleContinuationTest {
    public static void main(String[] args) throws Exception {
        for (boolean pause : new boolean[] {false, true}) resumeWithoutDuplicate(pause);
        failedProposalIsNotProof();
        cancellation();
        System.out.println("Count scheduling continuation: no-duplicate resume, exhausted slice, exclusive leases, negative scope and cancellation passed");
    }

    private static PlanningBudget budget() {
        return new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime);
    }

    private static IntegerCountSearch<String> search(PlanningBudget budget) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < 80; i++) recipes.add(new GraphRecipe<>("r" + i, "r" + i,
                List.of(new GraphRecipe.Slot<>("k" + i, 1)), Map.of("k" + (i + 1), 1L)));
        return new IntegerCountSearch<>(new GraphCompiler<>(recipes), "k80", 1, Map.of("k0", 1L),
                Map.of(), Set.of(), Set.of(), false, true, budget, 0, null, false);
    }

    private static void resumeWithoutDuplicate(boolean pause) throws Exception {
        var budget = budget();
        try (var search = search(budget)) {
            RecipeCountModel<String> model = get(search, "model");
            CountScheduleContinuations<String> pool = get(search, "scheduleContinuations");
            BigInteger[] counts = new BigInteger[model.recipes.size()];
            Arrays.fill(counts, BigInteger.ONE);
            var schedule = pool.acquire(counts);
            check(!schedule.step(), "Fixture completed before handoff");
            long spent = budget.searchWork();
            check(pool.retain(schedule), "Unfinished schedule was not retained");
            check(pool.takePending() == schedule && !pool.hasPending() && pool.takePending() == null,
                    "A continuation had two simultaneous owners");
            check(pool.retain(schedule), "Lease could not be returned");
            // Isolate the coordinator boundary: the proposer has stopped and
            // no count-producing branch remains to rediscover these counts.
            Deque<IntegerCountBranch<String>> pending = get(search, "pending");
            pending.forEach(IntegerCountBranch::close);
            pending.clear();
            field(search, "unresolved").setBoolean(search, true);
            field(search, "portfolioScheduled").setBoolean(search, true);
            field(search, "repairScheduled").setBoolean(search, true);
            field(search, "shellPrepared").setBoolean(search, true);
            if (pause) {
                field(search, "allowance").setLong(search, 0);
                check(search.step() && search.paused() && pool.hasPending(), "Slice cutoff discarded the only schedule");
                search.resume();
            }
            while (!search.step()) {}
            var plan = search.result();
            check(plan != null && plan.feasible(), "No branch continued the paid schedule: " + budget.diagnostics());
            check(plan.patternTimesExact().size() == 80 && plan.patternTimesExact().values().stream().allMatch(BigInteger.ONE::equals),
                    "Continuation changed recipe counts");
            check(plan.initial().equals(Map.of("k0", 1L)) && budget.searchWork() >= spent, "Initial inventory or work was reset");
            PlanVerifier.verify(plan);
            PlanVerifier.verifyRuntimeInventory(plan);
        }
        check(budget.reservedBytes() == 0, "Autonomous schedule leaked");
    }

    private static void failedProposalIsNotProof() {
        var budget = budget();
        var recipes = List.of(new GraphRecipe<>("ab", "ab", List.of(new GraphRecipe.Slot<>("a", 1)), Map.of("b", 1L, "goal", 1L)),
                new GraphRecipe<>("ba", "ba", List.of(new GraphRecipe.Slot<>("b", 1)), Map.of("a", 1L)));
        try (var model = RecipeCountModel.create(new GraphCompiler<>(recipes), "goal", 1, Map.of(), Map.of(), Set.of(), Set.of(), true, budget);
             var execution = new CountExecution<>(model, budget);
             var branch = new IntegerCountBranch<>(model, execution, "goal", 1, Map.of(), Map.of(), Set.of(), false, true, budget, 0, List.of())) {
            branch.scheduledCandidate(new CountSchedule<>(model, new BigInteger[] {BigInteger.ONE, BigInteger.ONE}, budget));
            while (branch.state == IntegerCountBranch.State.OPEN)
                branch.run(32, List.of(), List.of(), List.of(), null, () -> false);
            check(branch.state == IntegerCountBranch.State.UNRESOLVED && branch.plan == null,
                    "A fixed-multiset failure became a domain proof");
            check(branch.learnedChoices.isEmpty() && branch.supportConflicts.isEmpty(), "Optional schedule published negative constraints");
        }
        check(budget.reservedBytes() == 0, "Failed schedule leaked");
    }

    private static void cancellation() throws Exception {
        var budget = budget();
        try (var search = search(budget)) {
            RecipeCountModel<String> model = get(search, "model");
            CountScheduleContinuations<String> pool = get(search, "scheduleContinuations");
            BigInteger[] counts = new BigInteger[model.recipes.size()];
            Arrays.fill(counts, BigInteger.ONE);
            check(pool.retain(pool.acquire(counts)), "Failed to park cancellation fixture");
            budget.cancel();
            try { search.step(); throw new AssertionError("Cancelled search continued"); }
            catch (CancellationException expected) {}
        }
        check(budget.reservedBytes() == 0, "Cancelled queue leaked");
    }

    private static Field field(Object owner, String name) throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked") private static <T> T get(Object owner, String name) throws ReflectiveOperationException {
        return (T) field(owner, name).get(owner);
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
