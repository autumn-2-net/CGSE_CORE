// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** Independent refresh negatives for forecast ownership, policy scope and exact work limits. */
public final class PlanReuseScopeTest {
    private static final GraphRecipe<String> MAKE = new GraphRecipe<>("make", "provider",
            List.of(new GraphRecipe.Slot<>("ore", 2), new GraphRecipe.Slot<>("tool", 1, 0, true, true)),
            Map.of("product", 2L, "tool", 1L));
    private static final GraphCompiler<String> COMPILER = new GraphCompiler<>(List.of(MAKE));
    private static final GraphPlan<String> PLAN = new GraphPlan<>("product", 2, true,
            new PlanStep.Batch("make", 1), Map.of("make", MAKE), Map.of("ore", 2L, "tool", 1L),
            Map.of("tool", 1L), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);

    public static void main(String[] args) throws Exception {
        forecastRefresh();
        externalAndPolicyScope();
        exactRepetitionAndWorkLimits();
        System.out.println("Plan reuse scope: changed forecast/ownership, immutable request policy, external removal, exact repeated programs and 192 work limits passed");
    }

    private static void forecastRefresh() throws Exception {
        var firstBudget = budget();
        var first = request(COMPILER, Map.of("tool", 1L), Set.of(),
                checkpoint(1, Map.of("ore", 2L)), firstBudget).reuseCandidate(PLAN);
        check(run(first, firstBudget).feasible() && accepted(firstBudget), "forecast funds the retained suffix");
        var handle = first.reuseCandidate();
        check(handle != null, "completed suffix lost its handle");

        var sameBudget = budget();
        var same = request(COMPILER, Map.of("tool", 1L, "ore", 1L), Set.of(),
                checkpoint(2, Map.of("ore", 1L)), sameBudget).reuseCandidate(handle);
        check(run(same, sameBudget).feasible() && accepted(sameBudget), "new epoch and forecast/network split should be checked on current availability");
        var lostBudget = budget();
        var lost = request(COMPILER, Map.of("tool", 1L), Set.of(),
                checkpoint(3, Map.of("ore", 1L)), lostBudget).reuseCandidate(handle);
        check(!run(lost, lostBudget).feasible() && !accepted(lostBudget), "old forecast cannot fund the new suffix");
        check(lostBudget.diagnostics().contains("reason=stock"), "missing forecast must reject at current inventory");

        var seeds = new HashMap<>(Map.of("tool", 1L));
        var forecast = new HashMap<>(Map.of("ore", 2L));
        var network = new HashMap<>(Map.of("tool", 1L));
        var capturedBudget = budget();
        var captured = request(COMPILER, network, Set.of(),
                new GraphJobRuntime.ReplanCheckpoint<>(4, "product", 2, seeds, forecast), capturedBudget).reuseCandidate(handle);
        seeds.put("tool", 9L);
        forecast.clear();
        network.clear();
        check(run(captured, capturedBudget).feasible() && accepted(capturedBudget), "caller mutation changed captured request inputs");

        var changedBudget = budget();
        var changed = request(COMPILER, Map.of("tool", 2L, "ore", 2L), Set.of(),
                new GraphJobRuntime.ReplanCheckpoint<>(5, "product", 2, Map.of("tool", 2L), Map.of()), changedBudget).reuseCandidate(handle);
        check(run(changed, changedBudget).feasible() && !accepted(changedBudget), "new recovery obligation must rebuild a suffix");
        check(changedBudget.diagnostics().contains("reason=request_configuration"), "recovery-seed scope wasn't captured");
    }

    private static void externalAndPolicyScope() throws Exception {
        var external = new HashSet<>(Set.of("ore"));
        var originalBudget = budget();
        var original = request(COMPILER, Map.of("tool", 1L), external, null, originalBudget).reuseCandidate(PLAN);
        external.clear();
        check(run(original, originalBudget).feasible() && accepted(originalBudget), "external set must be a request snapshot");
        var handle = original.reuseCandidate();
        var removedBudget = budget();
        var removed = request(COMPILER, Map.of("tool", 1L), Set.of(), null, removedBudget).reuseCandidate(handle);
        check(!run(removed, removedBudget).feasible() && !accepted(removedBudget), "removed external source retained free materials");
        check(removedBudget.diagnostics().contains("reason=request_configuration"), "external scope change went unnoticed");

        // Bypassing the typed handoff still owes current external and force checks.
        var direct = budget();
        try (var verifier = new PlanReuseVerification<>(PLAN, COMPILER, "product", 2,
                Map.of("tool", 1L), Set.of(), Map.of(), true, true, direct, System.nanoTime())) {
            while (!verifier.step()) {}
            check(verifier.result() == null && direct.diagnostics().contains("reason=stock"), "raw witness retained old external funding");
        }
        check(direct.reservedBytes() == 0, "external negative leaked");

        // A replan may reuse already-held target without new production, but a
        // normal forced order must not accept that same empty execution.
        var empty = new GraphPlan<>("product", 2, true, new PlanStep.Sequence(List.of()), Map.<String, GraphRecipe<String>>of(),
                Map.of("product", 2L, "tool", 1L), Map.of("tool", 1L), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        var replanBudget = budget();
        var replan = request(COMPILER, Map.of("product", 2L, "tool", 1L), Set.of(),
                checkpoint(6, Map.of()), replanBudget).reuseCandidate(empty);
        check(run(replan, replanBudget).feasible() && accepted(replanBudget), "remaining target in checkpoint inventory is valid");
        var forcedBudget = budget();
        var forced = request(COMPILER, Map.of("product", 2L, "ore", 2L, "tool", 1L), Set.of(),
                null, forcedBudget).reuseCandidate(replan.reuseCandidate());
        check(run(forced, forcedBudget).feasible() && !accepted(forcedBudget), "checkpoint's nonforced empty program escaped into a forced order");
    }

    private static void exactRepetitionAndWorkLimits() {
        var recipe = new GraphRecipe<>("huge", "provider", List.of(new GraphRecipe.Slot<>("ore", 1)), Map.of("product", 1L));
        var compiler = new GraphCompiler<>(List.of(recipe));
        var total = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO);
        var steps = new PlanStep.Repeat(new PlanStep.Batch("huge", Long.MAX_VALUE), 2);
        var candidate = new GraphPlan<>("product", Long.MAX_VALUE, false, steps, Map.of("huge", recipe),
                Map.of("ore", total), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        int accepted = 0, exhausted = 0;
        for (int cap = 1; cap <= 192; cap++) {
            var b = new PlanningBudget(0, cap, 1L << 20, () -> false, System::nanoTime);
            try (var verifier = new PlanReuseVerification<>(candidate, compiler, "product", Long.MAX_VALUE,
                    Map.of(), Set.of("ore"), Map.of(), false, true, b, System.nanoTime())) {
                while (!verifier.step()) {}
                var result = verifier.result();
                check(result != null && result.steps() == steps && result.initialExact().get("ore").equals(total), "exact repeated candidate was truncated");
                PlanVerifier.verify(result);
                accepted++;
            } catch (PlanningBudget.Exhausted limit) {
                check(limit.limit() == PlanningBudget.Limit.SEARCH_LIMIT && !accepted(b), "work exhaustion published a partial witness");
                exhausted++;
            }
            check(b.reservedBytes() == 0, "work exhaustion leaked");
        }
        check(accepted > 0 && exhausted > 0, "work cap coverage lost a branch");
        var cancelled = new PlanningBudget(0, 1000, 1L << 20, () -> true, System::nanoTime);
        try (var verifier = new PlanReuseVerification<>(candidate, compiler, "product", Long.MAX_VALUE,
                Map.of(), Set.of("ore"), Map.of(), false, true, cancelled, System.nanoTime())) {
            try { verifier.step(); throw new AssertionError("cancelled candidate ran"); }
            catch (CancellationException expected) { check(!accepted(cancelled), "cancelled witness was accepted"); }
        }
        check(cancelled.reservedBytes() == 0, "entry cancellation leaked");
    }

    private static GraphJobRuntime.ReplanCheckpoint<String> checkpoint(long epoch, Map<String, Long> forecast) {
        return new GraphJobRuntime.ReplanCheckpoint<>(epoch, "product", 2, Map.of("tool", 1L), forecast);
    }

    private static RequestPlanningWork<String> request(GraphCompiler<String> compiler, Map<String, Long> stock,
                                                       Set<String> external, GraphJobRuntime.ReplanCheckpoint<String> checkpoint,
                                                       PlanningBudget budget) {
        return new RequestPlanningWork<>(compiler, "product", 2, stock, external, checkpoint, true, false,
                "DEFAULT", CatalystPolicy.MINIMAL, false, false, budget, () -> false, ignored -> {});
    }

    private static GraphPlan<String> run(RequestPlanningWork<String> request, PlanningBudget budget) throws Exception {
        try (var scheduler = new PlanningScheduler(2, 4, 64, 2_000_000)) {
            return scheduler.submit(request, budget).get(15, TimeUnit.SECONDS);
        } finally { check(budget.reservedBytes() == 0, "request leaked"); }
    }

    private static PlanningBudget budget() { return new PlanningBudget(0, 1_000_000, 64L << 20, () -> false, System::nanoTime); }
    private static boolean accepted(PlanningBudget budget) { return budget.diagnostics().matches(".*plan_reuse@[0-9]+: accepted;.*"); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
