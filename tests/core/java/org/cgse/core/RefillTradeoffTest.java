// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Supply tradeoffs use exact replay and the existing componentwise Pareto oracle. */
public final class RefillTradeoffTest {
    private static int checks;
    private static final BigInteger ZERO = BigInteger.ZERO;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return MissingMaterialExpansionTest.recipe(id, in, out);
    }
    private static PlanningBudget budget() { return MissingMaterialExpansionTest.budget(1_000_000); }
    private record Fixture(List<GraphRecipe<String>> recipes, GraphPlan<String> plan) {}
    private static Fixture fixture(int length, long seed, boolean feedRecipe, boolean tool, boolean joint, Map<String, Long> stock) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        var in = new LinkedHashMap<String, Long>(); in.put("A", 1L); in.put("feed", 1L); if (tool) in.put("tool", 1L);
        var out = new LinkedHashMap<String, Long>(); out.put("B", 1L); if (tool) out.put("tool", 1L); if (joint) out.put("joint", 1L);
        recipes.add(r("make", in, out));
        for (int i = 1; i < length - 1; i++) recipes.add(r("bridge" + i, Map.of(i == 1 ? "B" : "m" + (i - 1), 1L), Map.of("m" + i, 1L)));
        String last = length == 2 ? "B" : "m" + (length - 2);
        var back = r("return", Map.of(last, 1L), Map.of("A", 2L)); recipes.add(back);
        var finish = r("finish", joint ? Map.of("A", 10L, "joint", 2L) : Map.of("A", 10L), Map.of("G", 1L)); recipes.add(finish);
        if (feedRecipe) recipes.add(r("fuel", Map.of("ore", 1L), Map.of("feed", 2L)));
        var steps = new ArrayList<PlanStep>(); var used = new ArrayList<GraphRecipe<String>>();
        for (int i = 1; i < length - 1; i++) { steps.add(new PlanStep.Batch("bridge" + i, (10 + seed + 1) / 2)); used.add(recipes.get(i)); }
        steps.add(new PlanStep.Batch("return", (10 + seed + 1) / 2)); steps.add(new PlanStep.Batch("finish", 1));
        used.add(back); used.add(finish);
        var word = new PlanStep.Sequence(steps);
        var plan = RefillWordPruningTest.literalPlan(used, word, seed == 0 ? Map.of() : Map.of("A", seed), stock, Set.of());
        return new Fixture(recipes, plan);
    }
    private static GraphPlan<String> solve(Fixture f, Map<String, Long> stock, Set<String> excluded, Set<String> external, boolean force, boolean ignore) {
        var b = budget(); var compiler = new GraphCompiler<>(f.recipes); GraphPlan<String> answer;
        try (var order = new RefillProducerOrder<>(compiler, f.plan.missingExact().keySet(), external, excluded, b)) {
            while (!order.step()) {}
            try (var work = new RefillSupplySearch<>(f.plan, order, stock, external, force, ignore, b).tradeoffs(compiler, excluded)) {
                while (!work.step()) {} answer = work.result();
            }
        }
        check(b.reservedBytes() == 0, "Tradeoff workspace"); verify(answer, stock, external);
        for (var e : answer.missingExact().entrySet()) check(e.getValue().compareTo(f.plan.missingExact().getOrDefault(e.getKey(), ZERO)) <= 0, "Primary supply regressed");
        return answer;
    }
    private static void verify(GraphPlan<String> p, Map<String, Long> stock, Set<String> external) {
        var all = new ArrayList<GraphPlan<String>>(); all.add(p); all.addAll(p.alternatives());
        check(p.alternatives().size() <= 4, "Unbounded archive");
        for (var q : all) {
            RefillWordPruningTest.literalVerify(q, stock, external);
            check(q.seeds().equals(p.seeds()), "Changed final seed obligations");
            if (q != p) check(q.alternatives().isEmpty(), "Nested archive");
        }
        for (int i = 0; i < all.size(); i++) for (int j = i + 1; j < all.size(); j++)
            check(!SeedPortfolio.dominates(all.get(i), all.get(j)) && !SeedPortfolio.dominates(all.get(j), all.get(i)), "Dominated/equivalent archive member");
    }
    public static void main(String[] args) throws Exception {
        routes(); mainAndFallback(); restrictions(); rotatedBoundary(); exactCounts(); comparisonOracle(); archive(); limits(); scheduler(false); scheduler(true);
        System.out.println("RefillTradeoffTest: assertions=" + checks);
    }
    private static void routes() {
        for (int length : new int[]{2, 3, 4}) for (long seed : new long[]{0, 1, 4}) for (boolean raw : List.of(false, true))
            for (boolean reverse : List.of(false, true)) {
                var f = fixture(length, seed, raw, false, false, Map.of());
                if (reverse) Collections.reverse(f.recipes);
                var p = solve(f, Map.of(), Set.of(), Set.of(), true, false);
                check(p.steps() == f.plan.steps() && p.missingExact() == f.plan.missingExact(), "Incomparable tradeoff displaced primary");
                check(!p.alternatives().isEmpty(), "Missing cyclic refill alternatives: " + length + "/" + seed + "/" + raw + "/" + reverse + " " + p.missing());
                String root = f.plan.missingExact().keySet().iterator().next();
                check(p.alternatives().stream().anyMatch(q -> q.missing().getOrDefault(root, 0L) == 1), "Did not reach fixed-word startup plateau");
                for (var option : p.alternatives()) {
                    check(option.missing().containsKey(raw ? "ore" : "feed"), "Tradeoff hid required feedstock");
                    check(!raw || !option.missing().containsKey("feed"), "New feedstock stopped at a craftable intermediate");
                }
            }
        for (boolean tool : List.of(false, true)) for (boolean joint : List.of(false, true)) {
            var f = fixture(2, 1, true, tool, joint, Map.of());
            var p = solve(f, Map.of(), Set.of(), Set.of(), true, false);
            check(!p.alternatives().isEmpty(), "Lost tool/coproduct tradeoff");
            if (tool) for (var option : p.alternatives()) check(option.initial().get("tool") == 1, "Multiplied returned tool");
        }
        var f = fixture(2, 0, false, false, false, Map.of());
        var p = solve(f, Map.of(), Set.of(), Set.of(), true, false);
        check(p.missing().equals(Map.of("B", 5L)) && p.alternatives().stream().anyMatch(q -> q.missing().equals(Map.of("B", 1L, "feed", 8L))), "Review growth tradeoff");
        // External feed can improve the primary; it must never be billed.
        p = solve(f, Map.of(), Set.of(), Set.of("feed"), true, false);
        check(!p.missing().containsKey("feed"), "External supply billed");
    }
    private static void mainAndFallback() {
        for (int length : new int[]{2, 3, 4}) for (long amount : new long[]{1, 2, 5}) for (boolean reverse : List.of(false, true)) {
            var f = fixture(length, 0, true, false, false, Map.of()); if (reverse) Collections.reverse(f.recipes);
            var b = budget(); var compiler = new GraphCompiler<>(f.recipes);
            var p = new GraphPlanner<>(compiler).plan("G", amount, Map.of(), false, true, b);
            verify(p, Map.of(), Set.of()); check(!p.alternatives().isEmpty(), "Main planner omitted archive");
            check(b.reservedBytes() == 0, "Main workspace");
            b = budget(); p = GraphFallback.plan(compiler, "G", amount, Map.of(), Set.of(), Map.of(), false, true, b);
            check(p.alternatives().isEmpty() && !b.diagnostics().contains("refill_supply"), "Fallback entered cyclic portfolio");
            check(b.reservedBytes() == 0, "Fallback workspace");
        }
    }
    private static void restrictions() {
        var f = fixture(2, 0, false, false, false, Map.of());
        check(solve(f, Map.of(), Set.of("make"), Set.of(), true, false) == f.plan, "Used excluded source");
        var recipes = new ArrayList<>(f.recipes);
        recipes.set(0, new GraphRecipe<>("make", "make", List.of(new GraphRecipe.Slot<>("A", 1),
                new GraphRecipe.Slot<>("feed", 1, 0, true, false)), Map.of("B", 1L)));
        check(solve(new Fixture(recipes, f.plan), Map.of(), Set.of(), Set.of(), true, false) == f.plan, "Changed per-push configuration");
        var bindings = new LinkedHashMap<>(f.plan.recipes()); bindings.put("make", r("make", Map.of("other", 1L), Map.of("B", 1L)));
        var conflict = GraphPlan.ownedRefill(f.plan, f.plan.steps(), bindings, f.plan.initialExact(), f.plan.missingExact(), f.plan.result(), 0);
        check(solve(new Fixture(f.recipes, conflict), Map.of(), Set.of(), Set.of(), true, false) == conflict, "Changed physical binding");
        var conditional = GraphPlan.ownedRefill(f.plan, f.plan.steps(), f.plan.recipes(), f.plan.initialExact(), f.plan.missingExact(), GraphPlan.Result.INCOMPLETE_MODEL, 0);
        var p = solve(new Fixture(f.recipes, conditional), Map.of(), Set.of(), Set.of(), true, false);
        check(p.conditionalPreview() && !p.alternatives().isEmpty(), "Lost conditional archive");
        for (var option : p.alternatives()) check(option.conditionalPreview() && option.seedOptimality() == null, "Tradeoff promoted coverage/optimality");
        var existing = solve(f, Map.of(), Set.of(), Set.of(), true, false);
        check(solve(new Fixture(f.recipes, existing), Map.of(), Set.of(), Set.of(), true, false) == existing, "Overwrote existing frontier");
        var make = r("make", Map.of("A", 1L, "G", 1L), Map.of("B", 1L)); recipes = new ArrayList<>(f.recipes); recipes.set(0, make);
        check(solve(new Fixture(recipes, f.plan), Map.of(), Set.of(), Set.of("G"), true, false) == f.plan, "Spent forced production credit");
    }
    private static void exactCounts() {
        BigInteger amount = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(Long.MAX_VALUE)).add(BigInteger.TEN);
        var f = fixture(2, 0, false, false, false, Map.of()); var finish = r("finish", Map.of("B", 1L), Map.of("G", 1L));
        var recipes = new ArrayList<>(f.recipes); recipes.set(2, finish);
        var p = new GraphPlan<>("G", 1, false, PlanStep.batch("finish", amount), Map.of("finish", finish), Map.of("B", amount), Map.of(), Map.of("B", amount), GraphPlan.Result.MISSING_INPUT, 0, 0);
        var b = budget(); var compiler = new GraphCompiler<>(recipes);
        try (var order = new RefillProducerOrder<>(compiler, p.missingExact().keySet(), Set.of(), Set.of(), b)) {
            while (!order.step()) {}
            try (var work = new RefillSupplySearch<>(p, order, Map.of(), Set.of(), true, false, b).tradeoffs(compiler, Set.of())) {
                while (!work.step()) {} var answer = work.result();
                check(answer.missingExact().equals(p.missingExact()), "Clipped exact primary");
                var best = answer.alternatives().stream().filter(q -> q.missingExact().getOrDefault("B", ZERO).equals(BigInteger.ONE)).findFirst().orElseThrow();
                check(best.missingExact().get("feed").equals(amount.subtract(BigInteger.ONE).multiply(BigInteger.TWO)), "Clipped exact tradeoff");
                PlanVerifier.verify(GraphPlan.ownedRefill(best, best.steps(), best.recipes(), best.initialExact(), Map.of(), GraphPlan.Result.FEASIBLE, 0));
            }
        }
        check(b.reservedBytes() == 0, "Exact-count workspace");
    }
    private static void rotatedBoundary() {
        var f = fixture(3, 0, false, false, false, Map.of());
        var suffix = new PlanStep.Sequence(List.of(new PlanStep.Batch("return", 5), new PlanStep.Batch("finish", 1)));
        var original = RefillWordPruningTest.literalPlan(f.recipes, suffix, Map.of(), Map.of(), Set.of());
        // This valid rotated cycle is intentionally kept as evidence of the
        // remaining hint-coverage boundary. Passing replay is not a claim that
        // the bounded two-component shortcut archive enumerates this word.
        var body = new PlanStep.Sequence(List.of(new PlanStep.Batch("return", 1), new PlanStep.Batch("make", 2), new PlanStep.Batch("bridge1", 2)));
        var known = RefillWordPruningTest.literalPlan(f.recipes, new PlanStep.Sequence(List.of(new PlanStep.Repeat(body, 4), suffix)), Map.of(), Map.of(), Set.of());
        check(known.missing().equals(Map.of("m1", 1L, "feed", 8L)), "Rotated-cycle oracle");
        RefillWordPruningTest.literalVerify(known, Map.of(), Set.of());
        solve(new Fixture(f.recipes, original), Map.of(), Set.of(), Set.of(), true, false);
    }
    private static GraphPlan<String> vector(Map<String, ? extends Number> initial, Map<String, ? extends Number> missing, Map<String, Long> seeds) {
        return new GraphPlan<>("G", 1, false, new PlanStep.Batch("unused", 1), Map.of(), initial, seeds, missing,
                missing.isEmpty() ? GraphPlan.Result.FEASIBLE : GraphPlan.Result.MISSING_INPUT, 0, 0);
    }
    private static void comparisonOracle() {
        var random = new Random(19102026); var b = budget();
        for (int trial = 0; trial < 600; trial++) {
            var a = new LinkedHashMap<String, BigInteger>(); var d = new LinkedHashMap<String, BigInteger>();
            var am = new LinkedHashMap<String, BigInteger>(); var dm = new LinkedHashMap<String, BigInteger>();
            var as = new LinkedHashMap<String, Long>(); var ds = new LinkedHashMap<String, Long>();
            for (int i = 0; i < 8; i++) {
                String key = "k" + i; BigInteger scale = trial % 2 == 0 ? BigInteger.ONE : BigInteger.ONE.shiftLeft(100);
                for (var map : List.of(a, d, am, dm)) { int n = random.nextInt(4); if (n > 0) map.put(key, scale.multiply(BigInteger.valueOf(n))); }
                if (random.nextBoolean()) as.put(key, 1L); if (random.nextBoolean()) ds.put(key, 1L);
            }
            var left = vector(a, am, as); var right = vector(d, dm, ds); var comparison = new RefillFrontier.Comparison<>(left, right, b);
            boolean done; do { long before = b.nodes(); done = comparison.step(); check(b.nodes() - before <= 64, "Unbounded Pareto turn"); } while (!done);
            check(comparison.leftDominates() == SeedPortfolio.dominates(left, right) && comparison.rightDominates() == SeedPortfolio.dominates(right, left), "Changed existing Pareto order");
            boolean less = !am.equals(dm) && am.entrySet().stream().allMatch(e -> e.getValue().compareTo(dm.getOrDefault(e.getKey(), ZERO)) <= 0);
            check(comparison.improvesMissing() == less, "Wrong missing-only improvement");
        }
    }
    private static void offer(RefillFrontier<String> frontier, GraphPlan<String> plan) { frontier.offer(plan); while (!frontier.step()) {} }
    private static void archive() {
        var original = vector(Map.of("seed", 10L), Map.of("seed", 10L), Map.of()); var b = budget(); var frontier = new RefillFrontier<>(original, b);
        for (long n = 9; n >= 1; n--) offer(frontier, vector(Map.of("seed", n, "raw", 10 - n), Map.of("seed", n, "raw", 10 - n), Map.of()));
        check(frontier.primary() == original && frontier.result().alternatives().size() == 4, "Archive cap or primary priority");
        var better = vector(Map.of("seed", 1L, "raw", 1L), Map.of("seed", 1L, "raw", 1L), Map.of()); offer(frontier, better);
        check(frontier.primary() == original && frontier.result().alternatives().equals(List.of(better)), "Did not evict dominated alternatives");
        var before = frontier.result(); offer(frontier, better); check(frontier.result() == before, "Duplicate rebuilt archive");
        var feasible = vector(Map.of("seed", 1L, "raw", 1L), Map.of(), Map.of()); offer(frontier, feasible);
        check(frontier.primary() == feasible && frontier.result().feasible(), "Funded witness did not replace missing primary");
        var old = vector(Map.of("raw", 10L), Map.of("raw", 1L), Map.of()); frontier = new RefillFrontier<>(old, b);
        var lessStock = vector(Map.of("raw", 2L), Map.of("raw", 1L), Map.of()); offer(frontier, lessStock);
        check(frontier.primary() == lessStock && frontier.result().alternatives().isEmpty(), "Retained dominated primary stock use");
    }
    private static Object field(Object owner, String name) throws Exception {
        var f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static void limits() throws Exception {
        for (boolean published : List.of(false, true)) for (String phase : List.of("OPTIONS", "REFINE", "FRONTIER"))
            for (String mode : List.of("work", "time", "memory", "cancel")) {
            var f = fixture(2, 0, true, false, false, Map.of()); var compiler = new GraphCompiler<>(f.recipes);
            var cancelled = new AtomicBoolean(); var clock = new AtomicLong();
            var b = new PlanningBudget(1, 1_000_000, 64L << 20, cancelled::get, clock::get); long held = 0;
            try (var order = new RefillProducerOrder<>(compiler, f.plan.missingExact().keySet(), Set.of(), Set.of(), b)) {
                while (!order.step()) {}
                try (var work = new RefillSupplySearch<>(f.plan, order, Map.of(), Set.of(), true, false, b).tradeoffs(compiler, Set.of())) {
                    for (int i = 0; !field(work, "phase").toString().equals(phase) || published && work.result().alternatives().isEmpty(); i++)
                        check(i < 100_000 && !work.step(), "Never reached " + phase + "/published=" + published);
                    var retained = work.result();
                    switch (mode) {
                        case "work" -> b.charge(b.remainingWork());
                        case "time" -> clock.set(2_000_000);
                        case "memory" -> { held = b.availableBytes(); b.reserve(held); }
                        case "cancel" -> cancelled.set(true);
                    }
                    try {
                        while (!work.step()) {}
                        check(!mode.equals("cancel"), "Ignored cancellation");
                        var result = work.result(); verify(result, Map.of(), Set.of());
                        check(result.missingExact().equals(retained.missingExact()), "Optional cutoff changed primary bill");
                        if (!mode.equals("memory")) check(result == retained, "Discarded completed frontier at a limit");
                    } catch (CancellationException expected) { check(mode.equals("cancel") && work.result() == retained, "Cancellation changed publication"); }
                }
            } finally { b.release(held); }
            check(b.reservedBytes() == 0, "Interrupted tradeoff workspace");
        }
    }
    private static void scheduler(boolean cancel) throws Exception {
        var a = new LinkedHashMap<String, Long>(); var d = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 4096; i++) { a.put("raw" + i, 2L); d.put("raw" + i, 1L); }
        var original = vector(a, a, Map.of()); var proposed = vector(d, d, Map.of()); var cancelled = new AtomicBoolean();
        var b = new PlanningBudget(0, 100_000, 4L << 20, cancelled::get, System::nanoTime); var frontier = new RefillFrontier<>(original, b); frontier.offer(proposed);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var scheduler = new PlanningScheduler(1, 8, 64, 1_000_000_000)) {
            var future = scheduler.submit(new PlanningScheduler.Work<GraphPlan<String>>() {
                boolean started;
                public boolean advance(PlanningScheduler.Slice slice) {
                    if (!started) { started = true; entered.countDown(); try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Gate"); }
                        catch (InterruptedException e) { throw new AssertionError(e); } }
                    while (slice.nextUncharged()) if (frontier.step()) return true; return false;
                }
                public GraphPlan<String> result() { return frontier.result(); }
            }, b);
            check(entered.await(5, TimeUnit.SECONDS), "Worker did not start");
            var marker = scheduler.submit(new PlanningScheduler.Work<Long>() {
                long seen;
                public boolean advance(PlanningScheduler.Slice slice) { seen = b.nodes(); if (cancel) cancelled.set(true); return true; }
                public Long result() { return seen; }
            }, budget());
            release.countDown(); check(marker.get(10, TimeUnit.SECONDS) <= 64, "Pareto comparison monopolized worker");
            try { check(future.get(10, TimeUnit.SECONDS) == proposed && !cancel, "Wrong frontier result"); }
            catch (CancellationException expected) { check(cancel && frontier.result() == original, "Cancelled comparison changed archive"); }
            catch (ExecutionException e) { check(cancel && e.getCause() instanceof CancellationException && frontier.result() == original, "Worker failure"); }
        } finally { release.countDown(); }
    }
}
