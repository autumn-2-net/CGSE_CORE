// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Verified internal producers survive the coordinator's repeated validation boundary. */
public final class PrimaryProductionRetentionTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static PlanningBudget.Exhausted exhaust(PlanningBudget budget) {
        try { budget.charge(budget.remainingWork() + 1); }
        catch (PlanningBudget.Exhausted limit) { return limit; }
        throw new AssertionError("budget not exhausted");
    }
    private static GraphCompiler<String> compiler() {
        return new GraphCompiler<>(List.of(new GraphRecipe<>("make", "make",
                List.of(new GraphRecipe.Slot<>("raw", 1)), Map.of("T", 1L))));
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    private static void verify(GraphPlan<String> plan, GraphMode mode) {
        check(plan.feasible(), "verified manufacturing incumbent was discarded");
        check(plan.patternTimes().equals(Map.of("make", 1L)), "producer word changed");
        check(mode != GraphMode.ACYCLIC || plan.result() == GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL,
                "restricted incumbent retained full proof scope");
        PlanVerifier.verify(plan);
    }
    private static void allocationBoundary(GraphMode mode) throws Exception {
        var budget = new PlanningBudget(0, 1_000_000, 32L << 20, () -> false, System::nanoTime);
        var compiler = compiler();
        var work = new GraphPlanningWork<>(compiler, "T", 1, Map.of("raw", 1L), Set.of(), Map.of(),
                true, true, budget, mode);
        var allocation = new AllocationSearch<>(compiler, "T", 1, Map.of("raw", 1L), Set.of(), Map.of(),
                true, true, Set.of(), budget, System.nanoTime());
        try {
            while (!allocation.step()) {}
            var plan = allocation.result(); verify(plan, GraphMode.FULL);
            Method proposal = GraphPlanningWork.class.getDeclaredMethod("beginVerifiedProposal", GraphPlan.class,
                    PlanningBudget.CandidateOrigin.class);
            proposal.setAccessible(true); proposal.invoke(work, plan, PlanningBudget.CandidateOrigin.ALLOCATION);
            var limit = exhaust(budget);
            verify(work.limited(limit), mode);
            verify(work.limited(limit), mode); // A scoped export must not replace the original certificate identity.
        } finally { allocation.discard(); work.close(); }
        check(budget.reservedBytes() == 0, "allocation boundary leaked workspace");
    }
    private static void countHarvest(GraphMode mode) throws Exception {
        var budget = new PlanningBudget(0, 1_000_000, 32L << 20, () -> false, System::nanoTime);
        var compiler = compiler();
        var work = new GraphPlanningWork<>(compiler, "T", 1, Map.of("raw", 1L), Set.of(), Map.of(),
                true, true, budget, mode);
        var counts = new IntegerCountSearch<>(compiler, "T", 1, Map.of("raw", 1L), Map.of(), Set.of(), Set.of(),
                true, true, budget, System.nanoTime());
        try {
            while (!counts.step()) {}
            verify(counts.result(), GraphMode.FULL);
            set(work, "countSearch", counts);
            var limit = exhaust(budget);
            verify(work.limited(limit), mode);
            verify(work.limited(limit), mode);
        } finally { work.close(); counts.close(); }
        check(budget.reservedBytes() == 0, "count harvest boundary leaked workspace");
    }
    private static void alternativeBoundary(boolean stopAfterMain) throws Exception {
        var budget = new PlanningBudget(0, 1_000_000, 32L << 20, () -> false, System::nanoTime);
        var compiler = compiler();
        var stock = Map.of("raw", 1L, "seed", 1L);
        var seeds = Map.of("seed", 1L);
        var work = new GraphPlanningWork<>(compiler, "T", 1, stock, Set.of(), seeds,
                true, true, budget, GraphMode.ACYCLIC);
        var allocation = new AllocationSearch<>(compiler, "T", 1, stock, Set.of(), seeds,
                true, true, Set.of(), budget, System.nanoTime());
        try {
            while (!allocation.step()) {}
            var main = allocation.result(); verify(main, GraphMode.FULL);
            var targetSupply = new GraphPlan<>("T", 1, true, new PlanStep.Sequence(List.of()), Map.<String, GraphRecipe<String>>of(),
                    Map.of("T", 1L, "seed", 1L), seeds, Map.of("T", 1L), GraphPlan.Result.INCOMPLETE_MODEL, 0, 0);
            var wrongPreserve = new GraphPlan<>("T", 1, false, main.steps(), main.recipes(), main.initialExact(), seeds,
                    Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
            var missingSeed = new GraphPlan<>("T", 1, true, main.steps(), main.recipes(), main.initialExact(), Map.of(),
                    Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
            var unfunded = new GraphPlan<>("T", 1, true, main.steps(), main.recipes(), Map.of("seed", 1L), seeds,
                    Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
            var valid = new GraphPlan<>("T", 1, true, main.steps(), main.recipes(), main.initialExact(), seeds,
                    Map.of(), GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, 0, 0);
            var proposed = main.withAlternatives(List.of(targetSupply, wrongPreserve, missingSeed, unfunded, valid));
            set(work, "domainPrepared", true); // The synthetic gate seam uses this already-acyclic recipe list.
            set(work, "result", proposed);
            set(work, "productionVerified", proposed); // The producer proved the main word only.
            check(!work.step(), "optional validation was not resumed");
            try { work.result(); throw new AssertionError("unfinished alternatives were published"); }
            catch (IllegalStateException expected) {}
            if (stopAfterMain) {
                exhaust(budget);
                check(work.step(), "limit did not complete retained main handoff");
                var retained = work.result();
                verify(retained, GraphMode.ACYCLIC);
                check(retained.alternatives().isEmpty(), "unverified alternatives escaped through limited");
            } else {
                while (!work.step()) {}
                var result = work.result(); verify(result, GraphMode.ACYCLIC);
                check(result.alternatives().size() == 1, "invalid request/seed/physical alternative survived");
                var option = result.alternatives().get(0); verify(option, GraphMode.ACYCLIC);
                check(option.preserveSeeds() && option.seeds().equals(seeds), "alternative request seed scope changed");
                var limit = exhaust(budget);
                for (int export = 0; export < 2; export++) {
                    var retained = work.limited(limit); verify(retained, GraphMode.ACYCLIC);
                    check(retained.alternatives().size() == 1, "completed alternative archive lost at limit");
                    verify(retained.alternatives().get(0), GraphMode.ACYCLIC);
                }
            }
        } finally { allocation.discard(); work.close(); }
        check(budget.reservedBytes() == 0, "alternative validation retained workspace");
    }

    private static void primaryProofInterrupted() throws Exception {
        var budget = new PlanningBudget(0, 1_000_000, 32L << 20, () -> false, System::nanoTime);
        var compiler = compiler();
        var work = new GraphPlanningWork<>(compiler, "T", 1, Map.of("raw", 1L), Set.of(), Map.of(),
                true, true, budget, GraphMode.ACYCLIC);
        var proposal = new GraphPlan<>("T", 1, true, new PlanStep.Batch("make", 1),
                Map.of("make", compiler.catalog().get(0)), Map.of("raw", 1L), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        try {
            set(work, "domainPrepared", true);
            set(work, "result", proposal);
            check(!work.step(), "production proof did not retain continuation");
            exhaust(budget);
            check(work.step(), "interrupted proof did not finish with restricted failure");
            var answer = work.result();
            check(answer.result() == GraphPlan.Result.INCOMPLETE_MODEL && answer.missingExact().isEmpty() &&
                    answer.patternTimes().isEmpty(), "incomplete production proof published a witness");
        } finally { work.close(); }
        check(budget.reservedBytes() == 0, "interrupted proof retained workspace");
    }

    public static void main(String[] args) throws Exception {
        for (var mode : GraphMode.values()) { allocationBoundary(mode); countHarvest(mode); }
        alternativeBoundary(false); alternativeBoundary(true); primaryProofInterrupted();
        System.out.println("PrimaryProductionRetentionTest passed");
    }
}
