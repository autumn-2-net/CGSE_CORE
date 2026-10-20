// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Internal loop entries are checked using independent primitive execution. */
public final class RefillCycleCompletionTest {
    private static int checks;
    static void check(boolean value, Object why) { checks++; if (!value) throw new AssertionError(why); }
    static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return MissingMaterialExpansionTest.recipe(id, in, out);
    }
    static String key(int index) { return index == 0 ? "A" : index == 1 ? "B" : "m" + (index - 1); }
    record Fixture(List<GraphRecipe<String>> recipes, GraphPlan<String> plan, String root) {}
    static Fixture fixture(int length, int entry, long amount, long seed, boolean reverse, Map<String, Long> stock) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < length; i++) recipes.add(r("edge" + i,
                i == 0 ? Map.of("A", 1L, "feed", 1L) : Map.of(key(i), 1L),
                Map.of(key((i + 1) % length), i == length - 1 ? 2L : 1L)));
        recipes.add(r("finish", Map.of("A", 10L), Map.of("G", 1L)));
        var suffix = new ArrayList<PlanStep>();
        for (int i = entry; i < length; i++) suffix.add(new PlanStep.Batch("edge" + i, (10 * amount + seed + 1) / 2));
        suffix.add(new PlanStep.Batch("finish", amount));
        if (reverse) Collections.reverse(recipes);
        var p = RefillWordPruningTest.literalPlan(recipes, new PlanStep.Sequence(suffix), seed == 0 ? Map.of() : Map.of("A", seed), stock, Set.of());
        p = new GraphPlan<>("G", amount, p.preserveSeeds(), p.steps(), p.recipes(), p.initialExact(), p.seeds(), p.missingExact(), p.result(), 0, 0);
        return new Fixture(recipes, p, key(entry));
    }
    static GraphPlan<String> solve(Fixture f, Map<String, Long> stock, PlanningBudget budget) {
        var compiler = new GraphCompiler<>(f.recipes); GraphPlan<String> p;
        try (var order = new RefillProducerOrder<>(compiler, f.plan.missingExact().keySet(), Set.of(), Set.of(), budget)) {
            while (!order.step()) {}
            try (var search = new RefillSupplySearch<>(f.plan, order, stock, Set.of(), true, false, budget).tradeoffs(compiler, Set.of())) {
                while (!search.step()) {} p = search.result();
            }
        }
        check(budget.reservedBytes() == 0, "Completion workspace"); verify(p, stock);
        for (var e : p.missingExact().entrySet()) check(e.getValue().compareTo(f.plan.missingExact().getOrDefault(e.getKey(), BigInteger.ZERO)) <= 0, "Primary supply regressed");
        return p;
    }
    static void verify(GraphPlan<String> plan, Map<String, Long> stock) {
        RefillWordPruningTest.literalVerify(plan, stock, Set.of()); check(plan.alternatives().size() <= 4, "Archive cap");
        var all = new ArrayList<GraphPlan<String>>(); all.add(plan); all.addAll(plan.alternatives());
        for (var p : plan.alternatives()) {
            RefillWordPruningTest.literalVerify(p, stock, Set.of());
            check(p.seeds().equals(plan.seeds()) && p.alternatives().isEmpty(), "Seed scope or nested archive");
        }
        for (int i = 0; i < all.size(); i++) for (int j = i + 1; j < all.size(); j++)
            check(!SeedPortfolio.dominates(all.get(i), all.get(j)) && !SeedPortfolio.dominates(all.get(j), all.get(i)), "Dominated option");
    }
    static long best(GraphPlan<String> p, String root) {
        long n = p.missing().getOrDefault(root, 0L);
        for (var option : p.alternatives()) n = Math.min(n, option.missing().getOrDefault(root, 0L));
        return n;
    }
    public static void main(String[] args) throws Exception {
        for (int length = 3; length <= 8; length++) for (int entry = 1; entry < length; entry++)
            for (boolean reverse : List.of(false, true)) for (long seed : new long[]{0, 1}) {
                var f = fixture(length, entry, 1, seed, reverse, Map.of());
                var p = solve(f, Map.of(), MissingMaterialExpansionTest.budget(1_000_000));
                check(p.missingExact().equals(f.plan.missingExact()), "Unfunded feed displaced primary");
                check(best(p, f.root) == 1, "Missing entry " + length + "/" + entry + "/" + seed + "/" + reverse + " " + p.missing() + " " + p.alternatives().stream().map(GraphPlan::missing).toList());
            }
        funded(); ratios(); exactAndReuse(); contracts(); limits(); scheduler(false); scheduler(true);
        System.out.println("RefillCycleCompletionTest: assertions=" + checks);
    }

    private static void funded() {
        for (int length = 3; length <= 8; length++) for (int entry = 1; entry < length; entry++)
            for (long amount : new long[]{1, 3}) for (long feed : new long[]{4, 20}) for (long seed : new long[]{0, 1}) {
                var stock = Map.of("feed", feed); var f = fixture(length, entry, amount, seed, false, stock);
                var p = solve(f, stock, MissingMaterialExpansionTest.budget(1_000_000));
                // Every supplied root can yield two A; each consumed feed can
                // add at most one A. Some root is needed to start any recipe.
                long lower = Math.max(1, (10 * amount + seed - feed + 1) / 2);
                check(p.missing().equals(Map.of(f.root, lower)), "Missed funded bound " + length + "/" + entry + "/" + amount + "/" + feed + "/" + seed + ": " + p.missing());
            }
    }

    private static List<GraphRecipe<String>> ratioRecipes(long a, long b, long c, long d, long e, long f) {
        return List.of(r("out", Map.of("R", a, "tool", 1L), Map.of("X", b, "tool", 1L, "joint", 1L)),
                r("middle", Map.of("X", c, "feed", 1L), Map.of("Y", d)), r("back", Map.of("Y", e), Map.of("R", f)));
    }
    private static List<BackwardCoverability.Action<String>> primitives(Map<String, GraphRecipe<String>> recipes) {
        return recipes.values().stream().map(r -> new BackwardCoverability.Action<>(new PlanStep.Batch(r.id(), 1), SequenceSummary.recipe(r))).toList();
    }
    private static void replay(BackwardCoverability.Action<String> action, Map<String, GraphRecipe<String>> recipes) {
        var oracle = RefillWordPruningTest.literalPlan(List.copyOf(recipes.values()), action.program(), Map.of(), Map.of(), Set.of());
        var keys = new HashSet<>(oracle.initialExact().keySet()); keys.addAll(action.summary().keys()); keys.remove("G");
        var held = new HashMap<>(action.summary().required());
        var todo = new ArrayDeque<PlanStep>(); todo.push(action.program());
        while (!todo.isEmpty()) {
            var step = todo.pop();
            if (step instanceof PlanStep.Batch) MissingMaterialExpansionTest.fire(step, recipes, held);
            else if (step instanceof PlanStep.Sequence sequence) {
                for (int i = sequence.children().size() - 1; i >= 0; i--) todo.push(sequence.children().get(i));
            } else {
                var repeat = (PlanStep.Repeat) step; check(repeat.times() <= 10_000, "Literal repeat size");
                for (int i = 0; i < repeat.times(); i++) todo.push(repeat.body());
            }
        }
        for (String key : keys) {
            check(action.summary().required(key).equals(oracle.initialExact().getOrDefault(key, BigInteger.ZERO)), "Wrong prefix need: " + key);
            check(held.getOrDefault(key, BigInteger.ZERO).equals(action.summary().required(key).add(action.summary().delta(key))), "Wrong final balance: " + key);
        }
    }
    private static void ratios() {
        var random = new Random(6101953);
        for (int trial = 0; trial < 300; trial++) {
            long a = 1 + random.nextInt(5), b = 1 + random.nextInt(5), c = 1 + random.nextInt(5),
                    d = 1 + random.nextInt(5), e = 1 + random.nextInt(5), f = 1 + random.nextInt(5);
            var rs = new ArrayList<>(ratioRecipes(a, b, c, d, e, f)); if (trial % 2 == 0) Collections.reverse(rs);
            var recipes = MissingMaterialExpansionTest.index(rs); var budget = MissingMaterialExpansionTest.budget(100_000);
            long first = c * e, middle = b * e, last = b * d;
            long gcd = BigInteger.valueOf(first).gcd(BigInteger.valueOf(middle)).gcd(BigInteger.valueOf(last)).longValueExact();
            first /= gcd; middle /= gcd; last /= gcd;
            try (var completion = new RefillCycleCompletion<>(recipes, primitives(recipes), Set.of("R", "X", "Y"), "R", budget)) {
                while (!completion.step()) {}
                check(!completion.actions().isEmpty() == (last * f > first * a), "Ratio growth oracle");
                for (var action : completion.actions()) {
                    replay(action, recipes);
                    check(action.summary().delta("R").equals(BigInteger.valueOf(last * f - first * a)), "Integer flow ratio");
                    check(action.summary().required("tool").equals(BigInteger.ONE), "Returned tool multiplied");
                    check(action.summary().required("feed").equals(BigInteger.valueOf(middle)), "Hidden feed");
                }
            }
            check(budget.reservedBytes() == 0, "Ratio workspace");
        }
    }
    private static void exactAndReuse() {
        long n = Long.MAX_VALUE;
        var recipes = MissingMaterialExpansionTest.index(ratioRecipes(1, n, n - 1, n, n - 2, n));
        var budget = MissingMaterialExpansionTest.budget(100_000);
        try (var completion = new RefillCycleCompletion<>(recipes, primitives(recipes), Set.of("R", "X", "Y"), "R", budget)) {
            while (!completion.step()) {} check(!completion.actions().isEmpty(), "Exact large ratio omitted");
            var a = BigInteger.valueOf(n - 1).multiply(BigInteger.valueOf(n - 2));
            var b = BigInteger.valueOf(n).multiply(BigInteger.valueOf(n - 2)); var c = BigInteger.valueOf(n).pow(2);
            var gcd = a.gcd(b).gcd(c); a = a.divide(gcd); b = b.divide(gcd); c = c.divide(gcd);
            for (var action : completion.actions()) {
                var plan = new GraphPlan<>("R", 1, false, action.program(), recipes, action.summary().required(), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
                check(plan.patternTimesExact().equals(Map.of("out", a, "middle", b, "back", c)), "Exact count clipped");
                check(action.summary().delta("R").equals(c.multiply(BigInteger.valueOf(n)).subtract(a)), "Exact net balance");
                PlanVerifier.verify(plan);
            }
        }
        check(budget.reservedBytes() == 0, "Exact workspace");
        recipes = MissingMaterialExpansionTest.index(ratioRecipes(1, 2, 1, 1, 1, 1));
        var actions = new ArrayList<>(primitives(recipes)); var first = actions.get(0); PlanStep nested = first.program();
        for (int i = 0; i < 5000; i++) nested = new PlanStep.Sequence(List.of(nested));
        actions.set(0, new BackwardCoverability.Action<>(nested, first.summary()));
        budget = MissingMaterialExpansionTest.budget(1024);
        try (var completion = new RefillCycleCompletion<>(recipes, actions, Set.of("R", "X", "Y"), "R", budget)) {
            while (!completion.step()) {}
            check(!completion.actions().isEmpty(), "Did not reuse immutable component summary");
            for (var action : completion.actions()) replay(action, recipes);
        }
        check(budget.reservedBytes() == 0 && budget.nodes() < 1024, "Refolded known component");
    }
    private static void contracts() {
        for (boolean tool : List.of(false, true)) for (boolean joint : List.of(false, true)) {
            var f = fixture(3, 2, 1, 1, false, Map.of()); var rs = new ArrayList<>(f.recipes);
            var in = new LinkedHashMap<String, Long>(rs.get(0).inputs()); var out = new LinkedHashMap<String, Long>(rs.get(0).outputs());
            if (tool) { in.put("tool", 1L); out.put("tool", 1L); }
            if (joint) out.put("joint", 1L); rs.set(0, r("edge0", in, out));
            rs.add(r("fuel", Map.of("ore", 1L), Map.of("feed", 2L)));
            if (joint) rs.set(3, r("finish", Map.of("A", 10L, "joint", 2L), Map.of("G", 1L)));
            var original = RefillWordPruningTest.literalPlan(rs, f.plan.steps(), f.plan.seeds(), Map.of(), Set.of());
            var p = solve(new Fixture(rs, original, f.root), Map.of(), MissingMaterialExpansionTest.budget(1_000_000));
            check(!p.alternatives().isEmpty(), "Lost joint/tool route");
            for (var option : p.alternatives()) {
                check(!option.missing().containsKey("feed") && option.missing().containsKey("ore"), "Unexpanded feed");
                if (tool) check(option.initial().get("tool") == 1L, "Duplicated shared tool");
            }
        }
        var f = fixture(3, 2, 1, 0, false, Map.of());
        for (String restriction : List.of("excluded", "configuration", "binding", "forced", "conditional")) {
            var rs = new ArrayList<>(f.recipes); var p = f.plan; Set<String> excluded = Set.of(), external = Set.of();
            if (restriction.equals("excluded")) excluded = Set.of("edge0");
            if (restriction.equals("configuration")) rs.set(0, new GraphRecipe<>("edge0", "edge0", List.of(new GraphRecipe.Slot<>("A", 1), new GraphRecipe.Slot<>("feed", 1, 0, true, false)), Map.of("B", 1L)));
            if (restriction.equals("binding")) rs.set(0, r("edge0", Map.of("other", 1L), Map.of("B", 1L)));
            if (restriction.equals("forced")) {
                rs.set(0, r("edge0", Map.of("A", 1L, "G", 1L), Map.of("B", 1L))); external = Set.of("G");
                p = GraphPlan.ownedRefill(p, p.steps(), MissingMaterialExpansionTest.index(rs), p.initialExact(), p.missingExact(), p.result(), 0);
            }
            if (restriction.equals("conditional")) p = GraphPlan.ownedRefill(p, p.steps(), p.recipes(), p.initialExact(), p.missingExact(), GraphPlan.Result.INCOMPLETE_MODEL, 0);
            var b = MissingMaterialExpansionTest.budget(1_000_000); var compiler = new GraphCompiler<>(rs);
            try (var order = new RefillProducerOrder<>(compiler, p.missingExact().keySet(), external, excluded, b)) {
                while (!order.step()) {}
                try (var work = new RefillSupplySearch<>(p, order, Map.of(), external, true, false, b).tradeoffs(compiler, excluded)) {
                    while (!work.step()) {} var answer = work.result();
                    if (restriction.equals("conditional")) {
                        check(!answer.alternatives().isEmpty() && answer.conditionalPreview(), "Lost conditional options");
                        for (var option : answer.alternatives()) check(option.conditionalPreview(), "Promoted conditional proof");
                    } else check(answer == p, "Ignored restriction " + restriction);
                }
            }
            check(b.reservedBytes() == 0, "Restricted workspace");
        }
    }
    private static Object field(Object owner, String name) throws Exception {
        var f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static void limits() throws Exception {
        for (String phase : List.of("COMPLETION", "OPTION_SIZE", "OPTIONS", "REFINE", "FRONTIER")) for (String mode : List.of("work", "time", "memory", "cancel")) {
            var f = fixture(3, 2, 1, 0, false, Map.of()); var compiler = new GraphCompiler<>(f.recipes);
            var cancel = new AtomicBoolean(); var clock = new AtomicLong(); var b = new PlanningBudget(1, 1_000_000, 64L << 20, cancel::get, clock::get); long held = 0;
            try (var order = new RefillProducerOrder<>(compiler, f.plan.missingExact().keySet(), Set.of(), Set.of(), b)) {
                while (!order.step()) {}
                b.failureDetail("original proof");
                try (var work = new RefillSupplySearch<>(f.plan, order, Map.of(), Set.of(), true, false, b).tradeoffs(compiler, Set.of())) {
                    for (int i = 0; !field(work, "phase").toString().equals(phase) || field(work, "completing") == null; i++)
                        check(i < 100_000 && !work.step(), "No completion phase " + phase);
                    var retained = work.result();
                    switch (mode) {
                        case "work" -> b.charge(b.remainingWork()); case "time" -> clock.set(2_000_000);
                        case "memory" -> { held = b.availableBytes(); b.reserve(held); } case "cancel" -> cancel.set(true);
                    }
                    try { while (!work.step()) {} check(!mode.equals("cancel"), "Swallowed cancellation"); verify(work.result(), Map.of());
                        if (!mode.equals("memory")) check(work.result() == retained, "Lost retained bill");
                    } catch (CancellationException expected) { check(mode.equals("cancel") && work.result() == retained, "Cancellation publication"); }
                    check(b.failureDetail().equals("original proof"), "Replaced original proof diagnostic");
                }
            } finally { b.release(held); }
            check(b.reservedBytes() == 0, "Interrupted completion workspace");
        }
        // Failed constructor admission must not leave an unowned reservation.
        var rs = MissingMaterialExpansionTest.index(ratioRecipes(1, 2, 1, 1, 1, 1)); var b = MissingMaterialExpansionTest.budget(10_000); long held = b.availableBytes(); b.reserve(held);
        try { new RefillCycleCompletion<>(rs, primitives(rs), Set.of("R", "X", "Y"), "R", b); throw new AssertionError("Constructor admitted"); }
        catch (PlanningBudget.Exhausted expected) { check(b.reservedBytes() == held, "Constructor leak"); }
        finally { b.release(held); }
    }
    private static void scheduler(boolean cancel) throws Exception {
        var rs = MissingMaterialExpansionTest.index(ratioRecipes(1, 2, 1, 1, 1, 1)); var cancelled = new AtomicBoolean();
        var b = new PlanningBudget(0, 100_000, 4L << 20, cancelled::get, System::nanoTime);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var completion = new RefillCycleCompletion<>(rs, primitives(rs), Set.of("R", "X", "Y"), "R", b);
             var scheduler = new PlanningScheduler(1, 8, 64, 1_000_000_000)) {
            var future = scheduler.submit(new PlanningScheduler.Work<Boolean>() {
                boolean started;
                public boolean advance(PlanningScheduler.Slice slice) {
                    if (!started) {
                        started = true; entered.countDown();
                        try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Gate"); }
                        catch (InterruptedException e) { throw new AssertionError(e); }
                        // Yield a still-incomplete computation explicitly. A
                        // faster fold may otherwise finish before the queued
                        // cancellation marker, which cannot cancel a past result.
                        check(!completion.step(), "Expected suspended completion");
                        return false;
                    }
                    while (slice.nextUncharged()) if (completion.step()) return true; return false;
                }
                public Boolean result() { return !completion.actions().isEmpty(); }
            }, b);
            check(entered.await(5, TimeUnit.SECONDS), "Worker did not enter");
            var marker = scheduler.submit(new PlanningScheduler.Work<Long>() {
                long seen;
                public boolean advance(PlanningScheduler.Slice slice) { seen = b.nodes(); if (cancel) cancelled.set(true); return true; }
                public Long result() { return seen; }
            }, MissingMaterialExpansionTest.budget(100_000));
            release.countDown(); check(marker.get(10, TimeUnit.SECONDS) <= 128, "Completion monopolized worker");
            try { check(future.get(10, TimeUnit.SECONDS) && !cancel, "Wrong completion result"); }
            catch (CancellationException expected) { check(cancel, "Unexpected cancellation"); }
            catch (ExecutionException e) { check(cancel && e.getCause() instanceof CancellationException, "Worker failure"); }
        } finally { release.countDown(); }
        check(b.reservedBytes() == 0, "Scheduler workspace");
    }
}
