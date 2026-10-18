// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Boundary facts must not become proofs, change a bill, or spend solving quotas. */
public final class RefillBoundaryTest {
    private static final AtomicInteger checks = new AtomicInteger();
    private static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return MissingMaterialExpansionTest.recipe(id, in, out);
    }
    private static PlanningBudget budget() { return MissingMaterialExpansionTest.budget(4_000_000); }
    private static void check(boolean ok, String why) { checks.incrementAndGet(); if (!ok) throw new AssertionError(why); }
    private static void source(GraphPlan<String> p, String key, GraphPlan.MissingSource source, boolean seed) {
        check(p.missingBoundary(key) != null && p.missingBoundary(key).source() == source &&
                p.missingBoundary(key).explicitFinalSeed() == seed, key + ": " + p.missingBoundary(key) + " expected " + source);
    }
    private static GraphPlan<String> partial(GraphRecipe<String> finish) {
        return MissingMaterialExpansionTest.partial(finish, Map.of());
    }
    private static GraphPlan<String> expand(List<GraphRecipe<String>> recipes, GraphPlan<String> p, Set<String> excluded) {
        var b = budget();
        try (var work = new MissingMaterialExpansion<>(new GraphCompiler<>(recipes), p, Map.of(), Set.of(), excluded, true, false, b)) {
            while (!work.step()) {}
            var result = work.result(); MissingMaterialExpansionTest.verify(result, Map.of()); return result;
        } finally { check(b.reservedBytes() == 0, "Expansion workspace"); }
    }
    public static void main(String[] args) throws Exception {
        boundaries(); publicPaths(); capturedScope(); permutations(); copies(); limits(); parentLimits(); budgetIsolation(); scheduler(false); scheduler(true);
        System.out.println("RefillBoundaryTest: assertions=" + checks);
    }
    private static void boundaries() {
        var finish = r("finish", Map.of("A", 2L), Map.of("G", 1L));
        var a = r("a", Map.of("B", 1L), Map.of("A", 1L));
        var b = r("b", Map.of("A", 1L), Map.of("B", 1L));
        var raw = r("raw", Map.of("ore", 1L), Map.of("A", 1L));
        var p = partial(finish);
        source(p, "A", GraphPlan.MissingSource.UNASSESSED, false);
        check(p.missingBoundary("G") == null, "Nonmissing goal has a boundary");
        source(expand(List.of(finish), p, Set.of()), "A", GraphPlan.MissingSource.TERMINAL_UNDER_CURRENT_COVERAGE, false);
        source(expand(List.of(finish, a, b), p, Set.of()), "A", GraphPlan.MissingSource.CYCLE_BOUNDARY, false);
        source(expand(List.of(finish, raw), p, Set.of("raw")), "A", GraphPlan.MissingSource.EXCLUDED_SOURCES, false);
        source(expand(List.of(finish, raw), p, Set.of()), "ore", GraphPlan.MissingSource.TERMINAL_UNDER_CURRENT_COVERAGE, false);
        var returnTool = r("tool", Map.of("A", 1L, "ore", 1L), Map.of("A", 1L, "other", 1L));
        source(expand(List.of(finish, returnTool), p, Set.of()), "A", GraphPlan.MissingSource.UNEXPANDED_SOURCE, false);
        // A physical binding conflict is not evidence that no producer exists.
        var conflicting = r("finish", Map.of("ore", 1L), Map.of("A", 1L));
        source(expand(List.of(conflicting), p, Set.of()), "A", GraphPlan.MissingSource.UNEXPANDED_SOURCE, false);
        var seed = new GraphPlan<>("G", 1, false, p.steps(), p.recipes(), Map.of("A", 3L), Map.of("A", 1L),
                Map.of("A", 3L), p.result(), 0, 0);
        source(expand(List.of(finish, a, b), seed, Set.of()), "A", GraphPlan.MissingSource.CYCLE_BOUNDARY, true);
        source(expand(List.of(finish), seed, Set.of()), "A", GraphPlan.MissingSource.TERMINAL_UNDER_CURRENT_COVERAGE, true);
    }
    private static void publicPaths() {
        var finish = r("finish", Map.of("A", 2L), Map.of("G", 1L));
        for (boolean cyclic : List.of(false, true)) for (boolean fallback : List.of(false, true)) {
            var recipes = new ArrayList<>(List.of(finish));
            if (cyclic) { recipes.add(r("a", Map.of("B", 1L), Map.of("A", 1L))); recipes.add(r("b", Map.of("A", 1L), Map.of("B", 1L))); }
            var compiler = new GraphCompiler<>(recipes); var budget = budget();
            var plan = fallback ? GraphFallback.plan(compiler, "G", 1, Map.of(), Set.of(), Map.of(), false, true, budget) :
                    new GraphPlanner<>(compiler).plan("G", 1, Map.of(), false, true, budget);
            check(!plan.missingExact().isEmpty(), "No public refill");
            for (String key : plan.missingExact().keySet()) source(plan, key, cyclic ? GraphPlan.MissingSource.CYCLE_BOUNDARY :
                    GraphPlan.MissingSource.TERMINAL_UNDER_CURRENT_COVERAGE, false);
            MissingMaterialExpansionTest.verify(plan, Map.of()); check(budget.reservedBytes() == 0, "Public workspace");
        }
    }
    private static void permutations() {
        // Independent structural oracle: only the A/B pair cycles. Multiple
        // excluded producers and returned-only tools must not become terminals.
        for (int trial = 0; trial < 40; trial++) {
            var inputs = new LinkedHashMap<String, Long>();
            var keys = new ArrayList<>(List.of("A", "raw", "blocked", "tool")); Collections.shuffle(keys, new Random(trial));
            keys.forEach(k -> inputs.put(k, 1L));
            var finish = r("finish", inputs, Map.of("G", 1L));
            var recipes = new ArrayList<>(List.of(finish, r("a", Map.of("B", 1L), Map.of("A", 1L)),
                    r("b", Map.of("A", 1L), Map.of("B", 1L)), r("blocked1", Map.of("ore", 1L), Map.of("blocked", 1L)),
                    r("blocked2", Map.of("ore2", 1L), Map.of("blocked", 1L)),
                    r("use", Map.of("tool", 1L), Map.of("tool", 1L, "other", 1L))));
            Collections.shuffle(recipes, new Random(200 + trial));
            var p = expand(recipes, partial(finish), Set.of("blocked1", "blocked2"));
            source(p, "A", GraphPlan.MissingSource.CYCLE_BOUNDARY, false);
            source(p, "raw", GraphPlan.MissingSource.TERMINAL_UNDER_CURRENT_COVERAGE, false);
            source(p, "blocked", GraphPlan.MissingSource.EXCLUDED_SOURCES, false);
            source(p, "tool", GraphPlan.MissingSource.UNEXPANDED_SOURCE, false);
            check(p.missing().equals(inputs), "Source ordering changed independent boundaries");
        }
    }
    private static void capturedScope() {
        for (boolean bounded : List.of(false, true)) {
            var input = new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("raw", 2), null, false)));
            var recipe = new CapturedRecipe<>(List.of(input), List.of(new CapturedRecipe.Amount<>("G", 1)), false, bounded);
            var catalog = new CapturedCatalog<>(List.of(new CapturedCatalog.Entry<>("finish", "finish", recipe))); var b = budget();
            try (var work = catalog.begin("G", 1, Map.of(), false, true, b).conditionalPreviews(true)) {
                while (!work.step()) {}
                var plan = work.result().plan();
                check(plan.conditionalPreview() == bounded && work.coverage().complete() != bounded, "Annotation changed captured scope");
                source(plan, "raw", GraphPlan.MissingSource.TERMINAL_UNDER_CURRENT_COVERAGE, false);
                MissingMaterialExpansionTest.verify(plan, Map.of());
            }
            check(b.reservedBytes() == 0, "Captured boundary workspace");
        }
    }
    private static void copies() {
        var finish = r("finish", Map.of("A", 2L), Map.of("G", 1L));
        var original = partial(finish); var p = expand(List.of(finish), original, Set.of());
        source(original, "A", GraphPlan.MissingSource.UNASSESSED, false);
        var proof = new GraphPlan.SeedOptimality(0, 0, false, false, true);
        p = p.withSeedOptimality(proof).withAlternatives(List.of(original));
        source(p, "A", GraphPlan.MissingSource.TERMINAL_UNDER_CURRENT_COVERAGE, false);
        var copy = p.withMissingSources(GraphPlan.MissingSource.UNASSESSED, Map.of("A", GraphPlan.MissingSource.UNEXPANDED_SOURCE));
        check(copy.steps() == p.steps() && copy.initialExact() == p.initialExact() && copy.recipes() == p.recipes() &&
                copy.missingExact() == p.missingExact() && copy.seedOptimality() == proof && copy.alternatives().equals(p.alternatives()),
                "Annotation copied or changed execution data/proofs");
        source(p, "A", GraphPlan.MissingSource.TERMINAL_UNDER_CURRENT_COVERAGE, false);
        var conditional = new GraphPlan<>(p.target(), p.amount(), p.preserveSeeds(), p.steps(), p.recipes(), p.initialExact(), p.seeds(),
                p.missingExact(), GraphPlan.Result.INCOMPLETE_MODEL, 0, 0).withMissingSourcesFrom(p);
        check(conditional.conditionalPreview() && conditional.seedOptimality() == null, "Boundary promoted coverage");
        source(conditional, "A", GraphPlan.MissingSource.TERMINAL_UNDER_CURRENT_COVERAGE, false);
        var changed = GraphPlan.ownedRefill(p, p.steps(), p.recipes(), p.initialExact(), p.missingExact(), p.result(), 0);
        source(changed, "A", GraphPlan.MissingSource.UNASSESSED, false);
    }
    private static GraphPlan<String> wide(int size) {
        var keys = new LinkedHashMap<String, Long>(); for (int i = 0; i < size; i++) keys.put("raw" + i, 1L);
        return partial(r("finish", keys, Map.of("G", 1L)));
    }
    private static void limits() {
        var p = wide(4096); var compiler = new GraphCompiler<>(List.copyOf(p.recipes().values()));
        var b = budget(); long search = b.nodes(), compilation = b.compilationWork(), cache = b.cachePreparationWork();
        try (var d = new RefillBoundaryDiagnostics<>(p, compiler, null, b)) {
            boolean done;
            do { long before = b.refillDiagnosticWork(); done = d.step(); check(b.refillDiagnosticWork() - before <= 64, "Unbounded diagnostic turn"); } while (!done);
            check(b.refillDiagnosticWork() == 2048, "Diagnostic cap");
            for (int i = 0; i < 4096; i++) source(d.result(), "raw" + i, i < 2048 ? GraphPlan.MissingSource.TERMINAL_UNDER_CURRENT_COVERAGE : GraphPlan.MissingSource.UNASSESSED, false);
            check(d.result().missingExact() == p.missingExact(), "Truncated annotation truncated bill");
        }
        check(b.nodes() == search && b.compilationWork() == compilation && b.cachePreparationWork() == cache, "Diagnostics stole another allowance");
        try (var d = new RefillBoundaryDiagnostics<>(p, compiler, null, b)) {
            check(d.step() && d.result() == p, "A second view reset the request cap");
        }
        check(b.reservedBytes() == 0, "Diagnostic cap workspace");
        for (String limit : List.of("memory", "time", "cancel")) {
            var cancelled = new AtomicBoolean(); var clock = new AtomicLong();
            b = new PlanningBudget(1, 1_000_000, limit.equals("memory") ? 96 * 65 : 1L << 20, cancelled::get, clock::get);
            b.failureDetail("retained proof");
            try (var d = new RefillBoundaryDiagnostics<>(p, compiler, null, b)) {
                check(!d.step(), "Fixture did not yield");
                if (limit.equals("time")) clock.set(2_000_000);
                if (limit.equals("cancel")) cancelled.set(true);
                try {
                    check(d.step(), "Limit did not complete optional annotation"); check(!limit.equals("cancel"), "Ignored cancellation");
                    check(d.result().missingExact() == p.missingExact() && b.failureDetail().equals("retained proof"), "Limit changed bill/proof");
                    source(d.result(), "raw0", GraphPlan.MissingSource.TERMINAL_UNDER_CURRENT_COVERAGE, false);
                    source(d.result(), "raw4095", GraphPlan.MissingSource.UNASSESSED, false);
                } catch (CancellationException expected) { check(limit.equals("cancel") && d.result() == p, "Cancellation published partial work"); }
            }
            check(b.reservedBytes() == 0, "Interrupted diagnostics leaked");
        }
    }
    private static void budgetIsolation() throws Exception {
        var b = budget(); b.threadWork(); long local = b.threadWork(), search = b.threadSearchWork();
        var pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 4; i++) tasks.add(pool.submit(() -> {
                long before = b.threadWork(), solving = b.threadSearchWork(); int admitted = 0;
                while (b.tryRefillDiagnostic()) admitted++;
                check(b.threadWork() - before == admitted && b.threadSearchWork() == solving, "Wrong worker accounting"); return admitted;
            }));
            int total = 0; for (var task : tasks) total += task.get(10, TimeUnit.SECONDS);
            check(total == 2048 && b.refillDiagnosticWork() == 2048, "Concurrent diagnostic allowance");
        } finally { pool.shutdownNow(); }
        check(b.threadWork() == local && b.threadSearchWork() == search && b.nodes() == 0 && b.compilationWork() == 0 &&
                b.cachePreparationWork() == 0, "Diagnostic accounting leaked across worker/quota");
    }
    private static void parentLimits() throws Exception {
        var recipes = List.of(r("grow", Map.of("A", 1000L, "raw", 1L), Map.of("A", 1001L)), r("finish", Map.of("A", 1001L), Map.of("G", 1L)));
        var field = GraphPlanningWork.class.getDeclaredField("missingExpansion"); field.setAccessible(true);
        for (String mode : List.of("work", "time", "memory", "cancel", "handoff")) {
            var clock = new AtomicLong(); var cancelled = new AtomicBoolean();
            var b = new PlanningBudget(1, 4_000_000, 64L << 20, cancelled::get, clock::get); long held = 0;
            var work = new GraphPlanningWork<>(new GraphCompiler<>(recipes), "G", 1, Map.of(), false, false, b);
            try {
                MissingMaterialExpansion<?> child;
                for (int i = 0; ; i++) {
                    child = (MissingMaterialExpansion<?>) field.get(work);
                    if (child != null && child.diagnosing()) break;
                    check(i < 100_000 && !work.step(), "Never reached optional diagnostics");
                }
                var retained = child.result(); String detail = b.failureDetail();
                switch (mode) {
                    case "work" -> b.charge(b.remainingWork());
                    case "time" -> clock.set(2_000_000);
                    case "memory" -> { held = b.availableBytes(); b.reserve(held); }
                    case "cancel" -> cancelled.set(true);
                    case "handoff" -> check(work.limited(new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT)) == retained,
                            "Parent publication discarded incumbent during annotation");
                }
                try {
                    if (!mode.equals("handoff")) while (!work.step()) {}
                    check(!mode.equals("cancel"), "Parent ignored cancellation");
                    var actual = work.result();
                    check(actual.steps() == retained.steps() && actual.missingExact() == retained.missingExact() &&
                            actual.result() == retained.result() && b.failureDetail().equals(detail), "Metadata limit changed proof/bill");
                    MissingMaterialExpansionTest.verify(actual, Map.of());
                } catch (CancellationException expected) { check(mode.equals("cancel"), "Unexpected parent cancellation"); }
            } finally { work.close(); b.release(held); }
            check(b.reservedBytes() == 0, "Parent diagnostics leaked");
        }
    }
    private static void scheduler(boolean cancel) throws Exception {
        var p = wide(4096); var compiler = new GraphCompiler<>(List.copyOf(p.recipes().values())); var cancelled = new AtomicBoolean();
        var b = new PlanningBudget(0, 100_000, 4L << 20, cancelled::get, System::nanoTime);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var d = new RefillBoundaryDiagnostics<>(p, compiler, null, b); var scheduler = new PlanningScheduler(1, 8, 64, 1_000_000_000)) {
            var future = scheduler.submit(new PlanningScheduler.Work<GraphPlan<String>>() {
                boolean started;
                public boolean advance(PlanningScheduler.Slice slice) {
                    if (!started) { started = true; entered.countDown(); try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Gate"); }
                        catch (InterruptedException e) { throw new AssertionError(e); } }
                    while (slice.nextUncharged()) if (d.step()) return true; return false;
                }
                public GraphPlan<String> result() { return d.result(); }
                public void close() { d.close(); }
            }, b);
            check(entered.await(5, TimeUnit.SECONDS), "Worker not started");
            var marker = scheduler.submit(new PlanningScheduler.Work<Long>() {
                long seen;
                public boolean advance(PlanningScheduler.Slice slice) { seen = b.refillDiagnosticWork(); if (cancel) cancelled.set(true); return true; }
                public Long result() { return seen; }
            }, budget());
            release.countDown(); check(marker.get(10, TimeUnit.SECONDS) <= 64, "Metadata monopolized worker");
            try { var result = future.get(10, TimeUnit.SECONDS); check(!cancel && result.missingExact() == p.missingExact(), "Cancelled/changed result"); }
            catch (CancellationException e) { check(cancel, "Unexpected cancel"); }
            catch (ExecutionException e) { check(cancel && e.getCause() instanceof CancellationException, "Worker failed"); }
        } finally { release.countDown(); }
        check(b.reservedBytes() == 0, "Scheduled diagnostics leaked");
    }
}
