// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Host-selected limits must change admission without transferring work between accounts. */
public final class ConfigurableBudgetTest {
    public static void main(String[] args) throws Exception {
        independentLimits();
        largeLimits();
        sharedRetention();
        fallbackLimits();
        System.out.println("Configurable budgets: independent caps, zero retention, parallel/overflow boundaries and bounded fallback passed");
    }

    private static PlanningBudget budget(long search, long compilation, long cache) {
        return new PlanningBudget(0, search, compilation, cache, 16L << 20, () -> false, System::nanoTime);
    }

    private static void independentLimits() {
        var b = budget(7, 3, 2);
        b.threadWork(); b.threadSearchWork();
        check(b.tryCacheCharge(2) && !b.tryCacheScan(1), "Optional retention cap was not applied");
        check(b.nodes() == 0 && b.threadWork() == 0 && b.remainingWork() == 7, "Retention spent solver quota");
        b.compilationCharge(3);
        expectLimit(b::compilationCheck, "compilation_work=4/3");
        check(b.searchWork() == 0 && b.remainingWork() == 7, "Compiler cap consumed search");
        b.charge(7);
        expectLimit(b::check, "search_work=8/7");
        b = budget(3, 7, 0);
        check(!b.tryCacheCharge(1) && !b.tryCacheScan(1) && b.cachePreparationWork() == 0,
                "Zero retention cap admitted work");
        check(b.failureDetail().isEmpty(), "Optional refusal reported an order failure");
        b.charge(3);
        expectLimit(b::check, "search_work=4/3");
        b.compilationCharge(7);
        expectLimit(b::compilationCheck, "compilation_work=8/7");
        for (long[] limits : List.of(new long[]{0, 1, 1}, new long[]{1, 0, 1}, new long[]{1, 1, -1})) {
            try { budget(limits[0], limits[1], limits[2]); throw new AssertionError("Invalid limit accepted"); }
            catch (IllegalArgumentException expected) {}
        }
    }

    private static void largeLimits() {
        long expanded = PlanningBudget.parallelWorkLimit(Integer.MAX_VALUE, 4, true);
        check(expanded == 4_294_967_294L, "Parallel int cap overflowed");
        var b = budget(expanded, expanded, Integer.MAX_VALUE);
        b.charge(expanded); b.compilationCharge(expanded);
        check(b.tryCacheCharge(Integer.MAX_VALUE) && !b.tryCacheScan(1), "Large retention cap overflowed");
        check(b.searchWork() == expanded && b.compilationWork() == expanded &&
                b.cachePreparationWork() == Integer.MAX_VALUE, "Large accounts were truncated");
        expectLimit(b::check, "search_work=");
        expectLimit(b::compilationCheck, "compilation_work=");
        var largest = budget(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
        check(!largest.tryCacheCharge(Long.MAX_VALUE), "Unrepresentable retention charge wrapped");
        check(largest.cachePreparationWork() == 0, "Refused charge changed account");
        check(largest.tryCacheCharge(Long.MAX_VALUE / PlanningBudget.WORK_SCALE), "Largest representable charge refused");
        check(!largest.tryCacheScan(1) && !largest.tryCacheCharge(Long.MAX_VALUE), "Full retention cap reopened");
        check(largest.nodes() == 0 && largest.cachePreparationWork() > 0, "Large retention contaminated work");
    }

    private static void sharedRetention() throws Exception {
        var b = budget(100, 100, 37);
        var pool = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(1);
        try {
            var jobs = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 4; i++) jobs.add(pool.submit(() -> {
                start.await(); int admitted = 0;
                while (b.tryCacheScan(1)) admitted++;
                return admitted;
            }));
            start.countDown(); int total = 0;
            for (var job : jobs) total += job.get(10, TimeUnit.SECONDS);
            check(total == 148 && b.cachePreparationWork() == 37 && b.nodes() == 0,
                    "Workers multiplied the configured retention cap");
        } finally { pool.shutdownNow(); }
    }

    private static RequestPlanningWork<String> request(PlanningBudget primary) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < 5; i++) recipes.add(new GraphRecipe<>("r" + i, "r" + i,
                List.of(new GraphRecipe.Slot<>("x" + i, 1)), Map.of("x" + (i + 1), 1L)));
        return new RequestPlanningWork<>(new GraphCompiler<>(recipes), "x5", 2, Map.of("x0", 2L), Set.of(),
                null, true, false, "REPORT_MISSING_ITEMS", CatalystPolicy.MINIMAL, true, false,
                primary, () -> false, report -> {});
    }

    private static void fallbackLimits() {
        var defaults = RequestPlanningWork.FallbackLimits.DEFAULT;
        check(defaults.milliseconds() == 250 && defaults.maxWork() == 131_072 && defaults.maxBytes() == 16L << 20,
                "Legacy fallback defaults changed");
        var primary = budget(1, 1, 0);
        primary.check();
        var request = request(primary).fallbackLimits(new RequestPlanningWork.FallbackLimits(0, 10_000, 1L << 20));
        try {
            var plan = request.limited(new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT));
            check(request.fallbackMode() && plan.feasible() && plan.patternTimes().size() == 5,
                    "Configured fallback failed to recover an ordinary chain");
            PlanVerifier.verify(plan);
            check(primary.searchWork() == 1 && primary.compilationWork() == 0 && primary.cachePreparationWork() == 0,
                    "Fallback spent or refunded primary work");
            try { request.fallbackLimits(defaults); throw new AssertionError("Running request limits changed"); }
            catch (IllegalStateException expected) {}
        } finally { request.close(); }
        for (var limit : List.of(new RequestPlanningWork.FallbackLimits(0, 1, 1L << 20),
                new RequestPlanningWork.FallbackLimits(0, 10_000, 1))) {
            primary = budget(1, 1, 0);
            request = request(primary).fallbackLimits(limit);
            try {
                var cause = new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT);
                try { request.limited(cause); throw new AssertionError("Fallback ignored its cap"); }
                catch (PlanningBudget.Exhausted failure) { check(failure == cause, "Fallback replaced primary failure"); }
                check(!request.fallbackMode() && request.costs().fallback() != null, "Failed fallback was not accounted");
                check(primary.diagnostics().contains(limit.maxWork() == 1 ? "stopped=SEARCH_LIMIT" : "stopped=MEMORY_LIMIT"),
                        "Wrong fallback failure");
            } finally { request.close(); }
        }
        for (long[] limits : List.of(new long[]{-1, 1, 1}, new long[]{Long.MAX_VALUE, 1, 1},
                new long[]{0, 0, 1}, new long[]{0, 1, 0})) {
            try { new RequestPlanningWork.FallbackLimits(limits[0], limits[1], limits[2]); throw new AssertionError("Invalid fallback cap"); }
            catch (IllegalArgumentException expected) {}
        }
    }

    private static void expectLimit(Runnable operation, String detail) {
        try { operation.run(); throw new AssertionError("Expected work limit"); }
        catch (PlanningBudget.Exhausted limit) {
            check(limit.limit() == PlanningBudget.Limit.SEARCH_LIMIT && limit.getMessage().contains(detail), "Wrong limit: " + limit);
        }
    }

    private static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
}
