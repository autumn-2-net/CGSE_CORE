// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Scheduler load changes when a preview completes, never its allotted search.
 * Explicit deadlines, cancellation and verified incumbent ownership still apply. */
public final class RequestFallbackContinuationTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        stableAcrossSlices();
        deadlines();
        cancellation();
        retainedQuantity();
        provedMissingKeepsCheaperWord();
        System.out.println("RequestFallbackContinuationTest: assertions=" + checks);
    }

    private static void stableAcrossSlices() throws Exception {
        Map<String, BigInteger> expected = null;
        for (boolean funded : List.of(false, true)) for (long delay : new long[]{0, 100_000_000L}) {
            var scenario = new Scenario(0, 0, delay, funded, false);
            var plan = scenario.run();
            check(scenario.request.fallbackMode(), "Ordinary fallback was not exercised");
            check(scenario.fallbackAdvances > 3, "Fallback ran synchronously instead of retaining slices");
            check(plan.feasible() == funded, "Funded/missing result changed under scheduler delay");
            if (!funded) {
                check(plan.missingExact().equals(Map.of("x0", BigInteger.valueOf(2))), "Incomplete ordinary supply bill");
                if (expected == null) expected = plan.missingExact();
                else check(expected.equals(plan.missingExact()), "Same input changed with scheduler delay");
            }
            verify(plan);
            check(scenario.budget.searchWork() == 0, "Recovery charged the independent primary search account");
            check(scenario.request.costs().fallback().searchWork() <= 1_000_001, "Recovery exceeded the primary configured work cap");
            check(scenario.reports.get() == 1, "Fallback result was published more than once");
            if (delay > 0) check(scenario.clock.get() > 250_000_000L, "Slow scheduling control never crossed old cutoff");
            scenario.checkClosed();
        }
    }

    private static void deadlines() throws Exception {
        for (long[] caps : new long[][]{{250, 0}, {250, 150}, {250, 10_000}}) {
            var scenario = new Scenario(caps[0], caps[1], 100_000_000L, false, false);
            try { scenario.run(); throw new AssertionError("Recovery ignored its independently configured time allowance"); }
            catch (ExecutionException failure) {
                check(failure.getCause() instanceof PlanningBudget.Exhausted, "Wrong deadline failure " + failure.getCause());
                // The public failure keeps the primary trigger; diagnostic
                // evidence identifies the fallback account's own stopping cap.
                check(scenario.budget.diagnostics().contains("stopped=TIMEOUT"), "Recovery ignored configured deadline");
            }
            check(scenario.reports.get() == 0 && noWitness(scenario.request.result()), "Deadline fabricated a missing preview");
            scenario.checkClosed();
        }

    }

    private static void cancellation() throws Exception {
        var scenario = new Scenario(0, 0, 0, false, true);
        try { scenario.run(); throw new AssertionError("Cancelled fallback completed"); }
        catch (CancellationException expected) { checks++; }
        catch (ExecutionException failure) { check(failure.getCause() instanceof CancellationException, "Cancellation became proof/failure"); }
        check(scenario.reports.get() == 0 && noWitness(scenario.request.result()), "Cancelled fallback published a partial bill");
        scenario.checkClosed();
    }

    private static final class Scenario {
        final AtomicLong clock = new AtomicLong();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicInteger reports = new AtomicInteger();
        final PlanningBudget budget;
        final RequestPlanningWork<String> request;
        final long delay;
        final boolean cancel;
        int fallbackAdvances;

        Scenario(long deadline, long fallbackMillis, long delay, boolean funded, boolean cancel) {
            this.delay = delay; this.cancel = cancel;
            budget = new PlanningBudget(deadline, 1_000_000, 16L << 20, cancelled::get, clock::get);
            var recipes = new ArrayList<GraphRecipe<String>>();
            for (int i = 0; i < 30; i++) recipes.add(recipe("r" + i, "x" + i, "x" + (i + 1), 1));
            request = new RequestPlanningWork<>(new GraphCompiler<>(recipes), "x30", 2,
                    funded ? Map.of("x0", 2L) : Map.of(), Set.of(), null, false, false,
                    "REPORT_MISSING_ITEMS", CatalystPolicy.MINIMAL, true, false, budget, cancelled::get,
                    ignored -> reports.incrementAndGet());
            if (fallbackMillis != 0) request.fallbackLimits(new RequestPlanningWork.FallbackLimits(fallbackMillis, 131_072, 16L << 20));
            // Inject only the primary phase's exhausted result. Recovery then
            // runs the real solver through the scheduler with a fresh account.
            set(request, "current", new PlanningScheduler.Work<GraphPlan<String>>() {
                public boolean advance(PlanningScheduler.Slice ignored) {
                    throw new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT);
                }
                public GraphPlan<String> result() { return stopped(); }
                public GraphPlan<String> limited(PlanningBudget.Exhausted ignored) { return stopped(); }
                private GraphPlan<String> stopped() {
                    return new GraphPlan<>("x30", 2, false, new PlanStep.Sequence(List.of()), Map.of(),
                            Map.of(), Map.of(), Map.of(), GraphPlan.Result.SEARCH_LIMIT, 0, 0);
                }
            });
        }

        GraphPlan<String> run() throws Exception {
            try (var scheduler = new PlanningScheduler(1, 8, 1, 1_000_000)) {
                return scheduler.submit(new PlanningScheduler.Work<GraphPlan<String>>() {
                    public boolean advance(PlanningScheduler.Slice slice) {
                        clock.addAndGet(delay);
                        if (get(request, "fallbackWork") != null) {
                            fallbackAdvances++;
                            if (cancel && fallbackAdvances == 3) cancelled.set(true);
                        }
                        return request.advance(slice);
                    }
                    public GraphPlan<String> result() { return request.result(); }
                    public GraphPlan<String> limited(PlanningBudget.Exhausted limit) { return request.limited(limit); }
                    public void close() { request.close(); }
                    public CompletableFuture<?> waitingFor() { return request.waitingFor(); }
                }, budget).get(20, TimeUnit.SECONDS);
            }
        }

        void checkClosed() {
            check(budget.reservedBytes() == 0, "Primary workspace leaked");
            var fallback = (PlanningBudget) get(request, "fallbackBudget");
            check(fallback != null && fallback.reservedBytes() == 0, "Fallback workspace leaked");
        }
    }

    private static void retainedQuantity() {
        var b = new PlanningBudget(0, 1000, () -> false);
        var compiler = new GraphCompiler<>(List.of(recipe("make", "raw", "G", 1)));
        var request = new RequestPlanningWork<>(compiler, "G", 10, Map.of("raw", 10L), Set.of(), null,
                false, true, "CRAFT_LESS", CatalystPolicy.MINIMAL, true, false, b, () -> false,
                ignored -> { throw new AssertionError("Fallback replaced retained funded quantity"); });
        var larger = bill(8, 1, false);
        var smaller = bill(2, 1, false);
        PlanVerifier.verify(larger); PlanVerifier.verify(smaller);
        set(request, "selected", larger); set(request, "partialSearch", true);
        set(request, "current", new PlanningScheduler.Work<GraphPlan<String>>() {
            public boolean advance(PlanningScheduler.Slice ignored) { return true; }
            public GraphPlan<String> result() { return smaller; }
            public GraphPlan<String> limited(PlanningBudget.Exhausted ignored) { return smaller; }
        });
        try {
            var retained = request.limited(new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT));
            check(retained.feasible() && retained.amount() == 8, "Smaller child overwrote larger verified incumbent");
        } finally { request.close(); }
    }

    private static void provedMissingKeepsCheaperWord() throws Exception {
        for (String proof : List.of("quantityBlocked", "stockBlocked")) {
            var b = new PlanningBudget(0, 1_000_000, () -> false);
            var cheap = bill(2, 1, true);
            var costly = bill(2, 5, true);
            verify(cheap); verify(costly);
            var work = new GraphPlanningWork<>(new GraphCompiler<>(List.of(recipe("make1", "raw", "G", 1),
                    recipe("make5", "raw", "G", 5))), "G", 2, Map.of(), Set.of(), Map.of(), false, true, b)
                    .catalysts(CatalystPolicy.MINIMAL);
            set(work, "candidate", costly); set(work, "best", cheap);
            set(work, "quantityBlocked", proof.equals("quantityBlocked"));
            set(work, proof, true);
            // Reach the actual proof handoff with an independently replayed
            // retained word; do not rely on incidental source traversal order.
            var method = GraphPlanningWork.class.getDeclaredMethod("afterSolve"); method.setAccessible(true); method.invoke(work);
            try (var scheduler = new PlanningScheduler(1, 4, 64, 1_000_000)) {
                var result = scheduler.submit(work, b).get(20, TimeUnit.SECONDS);
                check(result.missingExact().equals(Map.of("raw", BigInteger.valueOf(2))), proof + " erased cheaper verified word");
                verify(result);
            }
            check(b.reservedBytes() == 0, "Proof handoff leaked workspace");
        }
    }

    private static GraphPlan<String> bill(long amount, int cost, boolean missing) {
        var recipe = recipe("make" + cost, "raw", "G", cost);
        var materials = Map.of("raw", BigInteger.valueOf(amount * cost));
        return new GraphPlan<>("G", amount, false, new PlanStep.Batch(recipe.id(), amount), Map.of(recipe.id(), recipe),
                materials, Map.of(), missing ? materials : Map.of(), missing ? GraphPlan.Result.MISSING_INPUT : GraphPlan.Result.FEASIBLE, 0, 0);
    }

    private static GraphRecipe<String> recipe(String id, String input, String output, long cost) {
        return new GraphRecipe<>(id, id, List.of(new GraphRecipe.Slot<>(input, cost)), Map.of(output, 1L));
    }

    private static void verify(GraphPlan<String> plan) {
        PlanVerifier.verify(new GraphPlan<>(plan.target(), plan.amount(), plan.preserveSeeds(), plan.steps(), plan.recipes(),
                plan.initialExact(), plan.seeds(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0));
        checks++;
    }

    private static boolean noWitness(GraphPlan<String> plan) {
        return plan == null || !plan.feasible() && plan.missingExact().isEmpty();
    }

    private static Object get(Object owner, String name) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void set(Object owner, String name, Object value) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void check(boolean condition, String detail) { checks++; if (!condition) throw new AssertionError(detail); }
}
