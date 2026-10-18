// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Selected proposals may be pruned only by necessary bounds, never by a local cutoff. */
public final class GraphViewBoundsTest {
    private static int checks, witnesses;

    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream()
                .map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }

    private static PlanningBudget budget(long bytes) {
        return new PlanningBudget(0, 4_000_000, bytes, () -> false, System::nanoTime);
    }

    private static GraphCompiler.Compiled<String> compile(List<GraphRecipe<String>> recipes) {
        var budget = budget(64L << 20);
        try (var work = new GraphCompiler<>(recipes).begin("target", Set.of(), Map.of(), Set.of(), budget)) {
            while (!work.step()) {}
            return work.result();
        } finally { check(budget.reservedBytes() == 0, "Compilation leaked"); }
    }

    private static boolean blocked(GraphCompiler.Compiled<String> graph, long amount, Map<String, Long> stock,
                                   Map<String, Long> seeds, Set<String> external, boolean force) {
        var budget = budget(64L << 20);
        try (var bounds = GraphViewBounds.create(graph, "target", amount, stock, seeds, external, force, budget, 4096)) {
            check(bounds != null, "Small selected graph refused admission");
            while (!bounds.step()) {}
            check(budget.searchWork() < 8192, "Necessary precheck escaped its bounded trial");
            return bounds.blocked();
        } finally { check(budget.reservedBytes() == 0, "Necessary check leaked"); }
    }

    public static void main(String[] args) throws Exception {
        upstreamSupply();
        productionAndConfiguration();
        literalWitnesses();
        lifecycle();
        threadedCutoff();
        System.out.println("GraphViewBoundsTest: witnesses=" + witnesses + "; assertions=" + checks);
    }

    private static void upstreamSupply() {
        var run = recipe("run", Map.of("feed", 2L, "work", 1L), Map.of("feed", 1L, "target", 1L));
        var fresh = recipe("fresh", Map.of("ore", 1L), Map.of("work", 1L, "feed", 1L));
        var graph = compile(List.of(run, fresh));
        check(graph.regions().size() == 2, "Fixture must supply feed from another selected region");
        // Each fresh/run pair retains one feed token and delivers one target.
        // Looking only at run would incorrectly reject all seven deliveries.
        for (var stock : List.of(Map.of("feed", 1L, "ore", 7L), Map.of("feed", 1L), Map.of("feed", 1L, "ore", 7L))) {
            boolean impossible = blocked(graph, 7, stock, Map.of(), Set.of(), false);
            check(impossible == !stock.containsKey("ore"), "Lost upstream coproduct or reused stale stock bounds");
        }
        check(!blocked(graph, 7, Map.of("feed", 1L), Map.of(), Set.of("ore"), false), "External feed constrained as absent");
        check(blocked(graph, 8, Map.of("feed", 1L, "ore", 7L), Map.of(), Set.of(), false), "Missed finite material capacity");
        var plain = compile(List.of(recipe("plain", Map.of("raw", 1L), Map.of("target", 1L))));
        check(blocked(plain, Long.MAX_VALUE, Map.of("raw", Long.MAX_VALUE - 1), Map.of(), Set.of(), false),
                "Large exact quantity lost its one-unit shortage");
        check(!blocked(plain, Long.MAX_VALUE, Map.of("raw", Long.MAX_VALUE), Map.of(), Set.of(), false),
                "Large exact feasible quantity was rounded");
        check(blocked(plain, 1, Map.of("target", Long.MAX_VALUE), Map.of("target", Long.MAX_VALUE), Set.of(), false),
                "Target plus final reserve overflowed into an available goal");
    }

    private static void productionAndConfiguration() {
        var graph = compile(List.of(recipe("seed", Map.of("target", 1L), Map.of("seed", 1L)),
                recipe("grow", Map.of("seed", 1L, "raw", 1L), Map.of("target", 2L))));
        // One startup target is spent to make two: retaining that initial target
        // in addition to delivery would invent a third-target requirement.
        check(!blocked(graph, 2, Map.of("target", 1L, "raw", 1L), Map.of(), Set.of(), true), "Forced startup stock was preserved twice");
        var turnover = compile(List.of(recipe("there", Map.of("target", 1L), Map.of("part", 1L)),
                recipe("back", Map.of("part", 1L), Map.of("target", 1L))));
        check(!blocked(turnover, 1, Map.of("target", 1L), Map.of(), Set.of(), false), "Ordinary stocked target rejected");
        check(blocked(turnover, 1, Map.of("target", 1L), Map.of(), Set.of(), true), "Turnover became forced net production");
        var configured = new GraphRecipe<>("configured", "configured", List.of(
                new GraphRecipe.Slot<>("raw", 1), new GraphRecipe.Slot<>("card", 1, 1, true),
                new GraphRecipe.Slot<>("tool", 1, 2, true, true)), Map.of("target", 1L, "tool", 1L));
        check(!blocked(compile(List.of(configured)), 32, Map.of("raw", 32L, "card", 1L, "tool", 1L), Map.of(), Set.of(), true),
                "One batched configuration/reusable tool became 32 consumptions");
    }

    private static void literalWitnesses() {
        // Independent forward execution, not another linear relaxation. Check
        // every reached final marking against the necessary material filter.
        var random = new Random(0x51ec7ed);
        var keys = List.of("a", "b", "target");
        for (int sample = 0; sample < 96; sample++) {
            var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
            for (int i = 0; i < 4; i++) {
                Map<String, Long> in = new LinkedHashMap<>(), out = new LinkedHashMap<>();
                in.merge(keys.get(random.nextInt(3)), 1L + random.nextInt(2), Long::sum);
                out.merge(keys.get(random.nextInt(3)), 1L + random.nextInt(2), Long::sum);
                if (random.nextBoolean()) in.merge(keys.get(random.nextInt(3)), 1L, Long::sum);
                if (random.nextBoolean()) out.merge(keys.get(random.nextInt(3)), 1L, Long::sum);
                recipes.put("r" + i, recipe("r" + i, in, out));
            }
            var graph = new GraphCompiler.Compiled<>(Map.copyOf(recipes), Map.of(), List.of());
            Map<String, Long> initial = Map.of("a", 1L + random.nextInt(3), "b", 1L + random.nextInt(3));
            Set<Map<String, Long>> reached = new LinkedHashSet<>();
            reached.add(initial);
            var frontier = Set.of(initial);
            for (int depth = 0; depth < 5; depth++) {
                Set<Map<String, Long>> next = new LinkedHashSet<>();
                for (var marking : frontier) for (var recipe : recipes.values()) {
                    if (recipe.inputs().entrySet().stream().anyMatch(e -> marking.getOrDefault(e.getKey(), 0L) < e.getValue())) continue;
                    var after = new LinkedHashMap<>(marking);
                    recipe.inputs().forEach((key, value) -> after.merge(key, -value, Long::sum));
                    recipe.outputs().forEach((key, value) -> after.merge(key, value, Long::sum));
                    after.values().removeIf(value -> value == 0);
                    var frozen = Map.copyOf(after);
                    if (reached.add(frozen)) next.add(frozen);
                }
                frontier = next;
            }
            for (var marking : reached) {
                long delivered = marking.getOrDefault("target", 0L);
                if (delivered == 0) continue;
                var seeds = new LinkedHashMap<>(marking);
                seeds.remove("target");
                check(!blocked(graph, delivered, initial, seeds, Set.of(), true), "Rejected literal execution: " + sample + "/" + marking);
                witnesses++;
            }
        }
        check(witnesses >= 500, "Literal exploration did not exercise enough final markings");
    }

    private static void lifecycle() {
        var graph = compile(List.of(recipe("grow", Map.of("target", 1L, "raw", 1L), Map.of("target", 2L))));
        var stock = Map.of("target", 1L, "raw", 8L);
        for (long bytes : new long[] {1, 512, 2048, 4096, 16384, 1L << 20}) {
            var budget = budget(bytes);
            budget.failureDetail("keep previous diagnostic");
            try (var bounds = GraphViewBounds.create(graph, "target", 8, stock, Map.of(), Set.of(), true, budget, 4096)) {
                if (bounds != null) { while (!bounds.step()) {} check(!bounds.blocked(), "Memory refusal became contradiction"); }
            }
            check(budget.reservedBytes() == 0 && budget.peakBytes() <= bytes, "Memory admission/release unbalanced");
            check(budget.failureDetail().equals("keep previous diagnostic"), "Optional refusal overwrote failure");
        }
        for (int stop = 1; stop <= 200; stop++) {
            int at = stop;
            var calls = new AtomicInteger();
            var budget = new PlanningBudget(0, 4_000_000, 64L << 20, () -> calls.incrementAndGet() >= at, System::nanoTime);
            try (var bounds = GraphViewBounds.create(graph, "target", 8, stock, Map.of(), Set.of(), true, budget, 4096)) {
                if (bounds != null) while (!bounds.step()) {}
            } catch (CancellationException expected) { /* A cancelled check has no negative result. */ }
            check(budget.reservedBytes() == 0, "Cancellation leaked at " + stop);
        }
        var budget = budget(64L << 20);
        try (var bounds = GraphViewBounds.create(graph, "target", 8, stock, Map.of(), Set.of(), true, budget, 4096)) {
            check(bounds != null, "Lifecycle fixture not admitted");
            budget.charge(budget.remainingWork());
            try { bounds.step(); throw new AssertionError("Exhausted parent budget continued"); }
            catch (PlanningBudget.Exhausted expected) { check(!bounds.blocked(), "Work limit became contradiction"); }
        }
        check(budget.reservedBytes() == 0, "Parent exhaustion leaked");
    }

    private static void threadedCutoff() throws Exception {
        // A lossy unseeded cycle can tighten lower bounds indefinitely. Resume
        // on alternating workers; the local cutoff must not depend on a thread's
        // absolute counter, and reaching it must remain inconclusive.
        var graph = compile(List.of(recipe("a", Map.of("a", 1000L), Map.of("b", 999L, "target", 1L)),
                recipe("b", Map.of("b", 1000L), Map.of("a", 1000L))));
        var budget = budget(64L << 20);
        var first = Executors.newSingleThreadExecutor();
        var second = Executors.newSingleThreadExecutor();
        try (var bounds = GraphViewBounds.create(graph, "target", 1, Map.of(), Map.of(), Set.of(), false, budget, 1024)) {
            check(bounds != null, "Feedback cutoff fixture not admitted");
            int steps = 0;
            while (!(++steps % 2 == 0 ? first : second).submit(bounds::step).get())
                check(steps < 1000, "A resumed precheck renewed its allowance");
            check(!bounds.blocked(), "Local cutoff became contradiction");
            check(budget.searchWork() >= 1024 && budget.searchWork() < 1100, "Precheck did not retain work across workers");
        } finally { first.shutdownNow(); second.shutdownNow(); }
        check(budget.reservedBytes() == 0, "Migrated continuation leaked");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
