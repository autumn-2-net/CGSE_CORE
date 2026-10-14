// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Optional port compilation is independent; evaluating current demand still costs search work. */
public final class DemandCacheBudgetTest {
    public static void main(String[] args) throws Exception {
        accounts();
        boundaries();
        limits();
        concurrency();
        System.out.println("Demand cache: independent accounts, quota/refusal/retry, topology identity, current inventory and concurrent preparation passed");
    }

    private static void accounts() {
        var compiler = compiler(); var graph = compiler.compile("k64", Map.of(), Set.of(), budget());
        var b = budget(); b.threadWork(); b.threadSearchWork();
        check(compiler.demandProgram(graph, b) == null, "First observation prepared an unused program");
        var p = compiler.demandProgram(graph, b);
        System.out.println("demand preparation: search=" + b.searchWork() + "; compile=" + b.compilationWork() +
                "; cache=" + b.cachePreparationWork() + "; request_peak=" + b.peakBytes());
        check(p != null && b.nodes() == 0 && b.threadWork() == 0 && b.threadSearchWork() == 0 && b.peakBytes() == 0,
                "Demand cache preparation consumed solver accounts");
        check(b.cachePreparationWork() > 0, "Port preparation went uncharged");
        var equivalent = new GraphCompiler.Compiled<>(graph.recipes(), Map.copyOf(graph.selected()), graph.regions());
        check(compiler.demandProgram(equivalent, budget()) == p, "Topology reuse cloned a request-specific wrapper");
        for (long quantity : new long[]{1, 17, 1_000_000, Long.MAX_VALUE / 4}) for (long stock : new long[]{0, quantity}) {
            var inventory = stock == 0 ? Map.<String, Long>of() : Map.of("k0", stock);
            var direct = solve(graph, null, quantity, inventory); var cached = solve(equivalent, p, quantity, inventory);
            check(direct.result() == cached.result() && direct.missingExact().equals(cached.missingExact()) &&
                    direct.steps().equals(cached.steps()), "Cached ports changed current stock/quantity semantics");
            if (cached.feasible()) { PlanVerifier.verify(cached); PlanVerifier.verifyRuntimeInventory(cached); }
        }
        var foreign = compiler().compile("k64", Map.of(), Set.of(), budget());
        try { solve(foreign, p, 1, Map.of("k0", 1L)); throw new AssertionError("Foreign topology accepted"); }
        catch (IllegalArgumentException expected) {}
    }

    private static void boundaries() {
        for (boolean cacheFull : new boolean[]{false, true}) {
            var compiler = compiler(); var graph = compiler.compile("k64", Map.of(), Set.of(), budget());
            var b = new PlanningBudget(0, 1, 1, () -> false, System::nanoTime); b.check(); b.reserve(1);
            compiler.demandProgram(graph, b);
            if (cacheFull) check(b.tryCacheScan((int) PlanningBudget.CACHE_PREPARATION_WORK * 4), "Cannot consume cache quota");
            check((compiler.demandProgram(graph, b) != null) != cacheFull, "Wrong independent quota admission");
            check(b.nodes() == 1 && b.peakBytes() == 1 && b.reservedBytes() == 1 && b.failureDetail().isEmpty(), "Optional refusal changed solver state");
            b.release(1);
            check(compiler.demandProgram(graph, budget()) != null, "Refusal was cached as permanent failure");
        }
        for (int cut = 1; cut <= 260; cut++) {
            var compiler = compiler(); var graph = compiler.compile("k64", Map.of(), Set.of(), budget());
            compiler.demandProgram(graph, budget()); var checks = new AtomicInteger(); int limit = cut;
            var b = new PlanningBudget(0, 1, 1, () -> checks.incrementAndGet() >= limit, System::nanoTime);
            try { compiler.demandProgram(graph, b); } catch (CancellationException ignored) {}
            check(b.nodes() == 0 && b.peakBytes() == 0, "Cancelled preparation used request accounts");
            check(compiler.cacheMetrics().caches().get("demand_preparation").estimatedBytes() == 0, "Cancelled preparation leaked");
            check(compiler.demandProgram(graph, budget()) != null, "Cancelled preparation poisoned retry");
        }
    }

    private static void limits() {
        for (int scans = 0; scans <= 320; scans++) {
            var compiler = compiler(); var graph = compiler.compile("k64", Map.of(), Set.of(), budget());
            var b = budget(); compiler.demandProgram(graph, b);
            check(b.tryCacheScan((int) PlanningBudget.CACHE_PREPARATION_WORK * 4 - scans), "Cannot set partial quota");
            check((compiler.demandProgram(graph, b) != null) == (scans == 320), "Partial program was published: " + scans);
            check(b.nodes() == 0 && b.peakBytes() == 0 && b.failureDetail().isEmpty(), "Partial quota consumed solver allowance");
            check(compiler.cacheMetrics().caches().get("demand_preparation").estimatedBytes() == 0, "Partial preparation leaked");
            check(compiler.demandProgram(graph, budget()) != null, "Partial program poisoned later requests");
        }
        var compiler = compiler(); var graph = compiler.compile("k64", Map.of(), Set.of(), budget());
        for (int i = 0; i < 12; i++) {
            var view = new GraphCompiler.Compiled<>(graph.recipes(), graph.selected(), List.copyOf(new ArrayList<>(graph.regions())));
            compiler.demandProgram(view, budget()); check(compiler.demandProgram(view, budget()) != null, "No bounded program");
        }
        var resident = compiler.cacheMetrics().caches().get("demand_program");
        check(resident.entries() == 8 && resident.evictions() == 4 && resident.estimatedBytes() <= 8L << 20, "Unbounded port residency");

        var slots = new ArrayList<GraphRecipe.Slot<String>>();
        for (int i = 0; i < 23_000; i++) slots.add(new GraphRecipe.Slot<>("in" + i, 1));
        var wide = new GraphRecipe<>("wide", "wide", slots, Map.of("out", 1L));
        var wideCompiler = new GraphCompiler<>(List.of(wide));
        var wideGraph = new GraphCompiler.Compiled<>(Map.of("wide", wide), Map.of("out", wide), List.of(new GraphCompiler.Region<>(List.of(wide), false)));
        wideCompiler.demandProgram(wideGraph, budget());
        var b = budget(); check(wideCompiler.demandProgram(wideGraph, b) == null, "Oversized port workspace admitted");
        check(b.nodes() == 0 && b.peakBytes() == 0 && wideCompiler.cacheMetrics().caches().get("demand_preparation").estimatedBytes() == 0,
                "Oversized optional program used request workspace");
        var clock = new AtomicLong(); var timed = new PlanningBudget(1, 1, 1, () -> false, clock::get); timed.start();
        wideCompiler.demandProgram(wideGraph, budget()); clock.set(1_000_000);
        try { wideCompiler.demandProgram(wideGraph, timed); throw new AssertionError("Timeout ignored"); }
        catch (PlanningBudget.Exhausted expected) { check(expected.limit() == PlanningBudget.Limit.TIMEOUT, "Wrong timeout"); }
        check(wideCompiler.cacheMetrics().caches().get("demand_preparation").estimatedBytes() == 0, "Timed preparation leaked");
    }

    private static void concurrency() throws Exception {
        var compiler = compiler(); var graph = compiler.compile("k64", Map.of(), Set.of(), budget());
        compiler.demandProgram(graph, budget()); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var b = new PlanningBudget(0, 1, 1, () -> {
            entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Preparation gate timed out"); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            return false;
        }, System::nanoTime);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> compiler.demandProgram(graph, b));
            check(entered.await(5, TimeUnit.SECONDS), "Preparation never began");
            var second = pool.submit(() -> {
                check(compiler.cacheMetrics().caches().get("demand_preparation").estimatedBytes() > 0, "Unaccounted preparation memory");
                var other = budget(); check(compiler.demandProgram(graph, other) == null && other.cachePreparationWork() == 0,
                        "Concurrent preparation repeated the same topology");
            });
            second.get(5, TimeUnit.SECONDS); release.countDown(); var program = first.get(5, TimeUnit.SECONDS);
            check(program != null && compiler.demandProgram(graph, budget()) == program, "Complete publication was lost");
            check(compiler.cacheMetrics().caches().get("demand_preparation").estimatedBytes() == 0, "Completed preparation leaked");
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private static GraphPlan<String> solve(GraphCompiler.Compiled<String> graph, GraphDemandProgram<String> program,
                                           long amount, Map<String, Long> stock) {
        var b = budget();
        try (var solve = new GraphSolve<>(graph, "k64", amount, stock, Set.of(), Map.of(), false, true,
                b, System.nanoTime(), CatalystPolicy.MINIMAL, stock).program(program)) {
            while (!solve.step()) {}
            check(b.searchWork() > 0 && b.cachePreparationWork() == 0, "Current demand became uncharged preparation");
            return solve.result();
        } finally { check(b.reservedBytes() == 0, "Demand solver leaked"); }
    }
    private static GraphCompiler<String> compiler() {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < 64; i++) recipes.add(new GraphRecipe<>("r" + i, "r" + i,
                List.of(new GraphRecipe.Slot<>("k" + i, 1)), Map.of("k" + (i + 1), 1L)));
        return new GraphCompiler<>(recipes);
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 4_000_000, 128L << 20, () -> false, System::nanoTime); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
