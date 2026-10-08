// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;
import java.util.concurrent.*;

/** Snapshot-local residency including nested recovery compilers; observation changes no cache identity. */
public final class CompilerCacheMetricsTest {
    public static void main(String[] args) throws Exception {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < 160; i++) recipes.add(new GraphRecipe<>("r" + i, "r" + i,
                List.of(new GraphRecipe.Slot<>("ore", 1L)), Map.of("T" + i, 1L)));
        var compiler = new GraphCompiler<>(recipes);
        var budget = new PlanningBudget(0, 10_000_000, 64L << 20, () -> false, System::nanoTime);
        for (int i = 0; i < 160; i++) compiler.compile("T" + i, Map.of(), Set.of(), budget);
        var warm = compiler.compile("T159", Map.of(), Set.of(), budget);
        var stats = compiler.cacheMetrics();
        var cache = stats.caches().get("compiled");
        check(cache.entries() == 128 && cache.evictions() == 32 && cache.hits() >= 1 && cache.misses() >= 160,
                "Compiled cache counters differ from actual admission: " + cache);
        check(stats.equals(compiler.cacheMetrics()), "Reading metrics mutates caches/counters");
        check(compiler.cached("T159", Map.of(), Set.of()) == warm, "Observation changed graph identity");
        var replacement = new GraphCompiler<>(recipes);
        check(replacement.cacheMetrics().caches().get("compiled").entries() == 0, "Snapshot replacement inherited caches");
        check(stats.estimatedBytes() > warm.estimatedBytes(), "Only one cache is observed");

        // The two macro views may be the same compiler; count it only once.
        try (var model = RecipeCountModel.create(compiler, "T159", 1, Map.of("ore", 1L), Map.of(), Set.of(), Set.of(), true, budget)) {
            check(model != null, "Missing test count model");
            compiler.recoveryTemplates.reuse(model);
            compiler.recoveryTemplates.reuse(model);
            var template = compiler.recoveryTemplates.remember(model, model.recipes, model.recipes,
                    Map.of("macro", new PlanStep.Batch("r159", 1)));
            check(template != null && template.completeCompiler == template.retainedCompiler, "Missing shared child");
            template.completeCompiler.compile("T159", Map.of(), Set.of(), budget);
            var nested = compiler.cacheMetrics();
            check(nested.compilers() == 2 && nested.caches().get("compiled").entries() == 129, "Nested compiler missing or counted twice");
            check(nested.caches().get("recovery_template").entries() == 1, "Template residency missing");
        }
        check(budget.reservedBytes() == 0, "Observation leaked request reservations");

        var pool = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(1);
        List<Future<?>> jobs = new ArrayList<>();
        try {
            for (int thread = 0; thread < 4; thread++) {
                final int id = thread;
                jobs.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 32; i++) {
                        String target = "T" + (id * 32 + i);
                        var requestBudget = new PlanningBudget(0, PlanningBudget.parallelWorkLimit(250_000, 4, true), 64L << 20,
                                () -> false, System::nanoTime);
                        var work = new GraphPlanningWork<>(compiler, target, 1, Map.of("ore", 1L), false, true, requestBudget);
                        try {
                            check(compiler.cacheMetrics().activeSearches() > 0, "Active search absent");
                            while (!work.step()) {}
                            check(work.result().feasible(), "Concurrent cache observation broke request");
                        } finally { work.close(); work.close(); }
                        check(requestBudget.reservedBytes() == 0, "Concurrent request leaked");
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var job : jobs) job.get(60, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        check(compiler.cacheMetrics().activeSearches() == 0, "Finished requests remain active");
        System.out.println("Compiler cache metrics: eviction/hits, immutable snapshots, nested compiler identity and 128 concurrent requests passed");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
