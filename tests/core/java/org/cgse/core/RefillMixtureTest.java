// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Complete supply bills, independent allocation oracles, and retained-witness limits. */
public final class RefillMixtureTest {
    private static int checks;
    private static final Field IMPROVEMENT, PHASE;
    static {
        try {
            IMPROVEMENT = MissingMaterialExpansion.class.getDeclaredField("improvement"); IMPROVEMENT.setAccessible(true);
            PHASE = MissingMaterialExpansion.class.getDeclaredField("phase"); PHASE.setAccessible(true);
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static Object child(MissingMaterialExpansion<?> expansion) throws IllegalAccessException { return IMPROVEMENT.get(expansion); }
    private static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return MissingMaterialExpansionTest.recipe(id, in, out);
    }
    private static Map<String, Long> ordered(Object... values) {
        var map = new LinkedHashMap<String, Long>();
        for (int i = 0; i < values.length; i += 2) map.put((String) values[i], ((Number) values[i + 1]).longValue());
        return map;
    }
    private static List<GraphRecipe<String>> simple(long demand) {
        return List.of(r("one", Map.of("R", 1L), Map.of("A", 1L)), r("two", Map.of("S", 1L), Map.of("A", 1L)),
                r("finish", Map.of("A", demand), Map.of("G", 1L)));
    }
    private static GraphPlan<String> suffix(List<GraphRecipe<String>> recipes, Map<String, Long> stock) {
        return MissingMaterialExpansionTest.partial(recipes.get(recipes.size() - 1), stock);
    }
    private static GraphPlan<String> solve(List<GraphRecipe<String>> recipes, Map<String, Long> stock) {
        return MissingMaterialExpansionTest.expand(recipes, suffix(recipes, stock), stock);
    }
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        allocation(); returnsAndSeeds(); protectWholeBill(); boundaries(); largeCounts(); lifecycle(); scheduler(false); scheduler(true);
        System.out.println("RefillMixtureTest: assertions=" + checks);
    }
    private static void allocation() {
        var random = new Random(18102426);
        for (int trial = 0; trial < 500; trial++) {
            int n = 2 + random.nextInt(3); long demand = 1 + random.nextInt(50), otherCapacity = 0;
            var recipes = new ArrayList<GraphRecipe<String>>(); var stock = new LinkedHashMap<String, Long>();
            long firstInput = 0, firstOutput = 0;
            for (int i = 0; i < n; i++) {
                long input = 1 + random.nextInt(4), output = 1 + random.nextInt(5), held = random.nextInt(12);
                recipes.add(r("source" + i, Map.of("raw" + i, input), Map.of("A", output))); stock.put("raw" + i, held);
                if (i == 0) { firstInput = input; firstOutput = output; }
                else otherCapacity += held / input * output;
            }
            recipes.add(r("finish", Map.of("A", demand), Map.of("G", 1L)));
            // With independent inputs, available capacity is additive. The
            // remaining deficit can be funded solely from the preferred input.
            long firstRuns = (Math.max(0, demand - otherCapacity) + firstOutput - 1) / firstOutput;
            long extra = Math.max(0, firstRuns * firstInput - stock.get("raw0"));
            var plan = solve(recipes, stock);
            check(plan.missing().equals(extra == 0 ? Map.of() : Map.of("raw0", extra)), "Independent allocation " + trial + ": " + plan.missing() + " expected=" + extra + " stock=" + stock + " recipes=" + recipes.stream().map(r -> r.inputs() + "->" + r.outputs()).toList());
        }
        for (boolean reversed : List.of(false, true)) {
            var recipes = new ArrayList<>(simple(10));
            if (reversed) Collections.swap(recipes, 0, 1);
            var plan = solve(recipes, Map.of("R", 5L, "S", 5L));
            check(plan.feasible() && plan.patternTimes().equals(Map.of("one", 5L, "two", 5L, "finish", 1L)), "Mixed source order");
        }
        var one = r("one", Map.of("R", 1L), Map.of("A", 1L));
        var two = r("two", Map.of("S", 1L), Map.of("A", 1L));
        var finish = r("finish", Map.of("A", 10L), Map.of("G", 1L));
        var stock = Map.of("R", 5L, "S", 10L);
        check(solve(List.of(one, two, finish), stock).patternTimes().equals(Map.of("two", 10L, "finish", 1L)), "Partial source displaced a wholly funded source");
    }
    private static void returnsAndSeeds() {
        for (long s = 0; s <= 4; s++) {
            var recipes = List.of(r("one", ordered("R", 3, "tool", 1), ordered("R", 1, "tool", 1, "A", 1)),
                    r("two", ordered("S", 1, "tool", 1), ordered("A", 1, "tool", 1)),
                    r("finish", Map.of("A", 4L), Map.of("G", 1L)));
            var plan = solve(recipes, Map.of("R", 6L, "S", s, "tool", 1L));
            long missing = Math.max(0, 3 + 2 * (4 - s - 1) - 6);
            check(plan.missing().equals(missing == 0 ? Map.of() : Map.of("R", missing)), "Returned input startup was amortized: " + plan.missing());
            check(plan.initial().get("tool") == 1, "Returned tool multiplied");
        }
        var recipes = simple(10); var stock = Map.of("R", 5L, "S", 5L);
        var finish = recipes.get(2);
        var original = new GraphPlan<>("G", 1, true, new PlanStep.Batch("finish", 1), Map.of("finish", finish),
                Map.of("A", 10L, "R", 2L), Map.of("R", 2L), Map.of("A", 10L), GraphPlan.Result.MISSING_INPUT, 0, 0);
        var plan = MissingMaterialExpansionTest.expand(recipes, original, stock);
        check(plan.missing().equals(Map.of("R", 2L)) && plan.seeds().equals(Map.of("R", 2L)), "Mix spent a final seed");
        var grow = List.of(r("one", ordered("A", 2, "R", 1), Map.of("A", 3L)),
                r("two", ordered("A", 2, "S", 1), Map.of("A", 3L)), r("finish", Map.of("A", 8L), Map.of("G", 1L)));
        check(solve(grow, Map.of("A", 2L, "R", 3L, "S", 3L)).feasible(), "Stock-funded growth mixture");
    }
    private static void protectWholeBill() {
        // Taking a stock-funded partial joint source first creates an extra
        // raw2 deficit after the remaining coproduct obligation is expanded.
        var recipes = List.of(r("part", ordered("raw2", 1, "raw3", 2), Map.of("B", 3L)),
                r("joint", Map.of("raw3", 3L), ordered("A", 3, "B", 1)),
                r("plain", Map.of("raw3", 3L), Map.of("A", 2L)),
                r("partial", ordered("raw2", 2, "raw1", 2), ordered("unused", 2, "B", 1)),
                r("finish", ordered("A", 7, "B", 5), Map.of("G", 1L)));
        var plan = solve(recipes, Map.of("raw1", 3L, "raw2", 2L, "raw3", 4L));
        check(plan.missing().equals(Map.of("raw3", 7L)), "Local partial funding worsened the final bill: " + plan.missing());
        check(plan.patternTimes().equals(Map.of("part", 1L, "joint", 3L, "finish", 1L)), "Rejected mixture changed the retained word");
        // Each alternative consumes the same physical stock. It cannot be
        // counted once per producer, even when their batch sizes differ.
        var shared = List.of(r("one", Map.of("R", 1L), Map.of("A", 1L)), r("two", Map.of("R", 2L), Map.of("A", 2L)),
                r("finish", Map.of("A", 10L), Map.of("G", 1L)));
        check(solve(shared, Map.of("R", 5L)).missing().equals(Map.of("R", 5L)), "Counted shared stock twice");
        var incomparable = List.of(
                r("r3-0", ordered("k1", 1, "raw0", 2), Map.of("k3", 2L)),
                r("r4-1", Map.of("k1", 1L), Map.of("k4", 1L)),
                r("r3-1", Map.of("k0", 2L), Map.of("k3", 3L)),
                r("r1-1", ordered("k0", 2, "raw3", 2), Map.of("k1", 4L)),
                r("r2-1", Map.of("raw3", 4L), ordered("k2", 3, "k4", 1)),
                r("r1-0", Map.of("k0", 2L), ordered("k1", 1, "k5", 1)),
                r("r0-1", Map.of("raw1", 3L), Map.of("k0", 3L)),
                r("r5-0", Map.of("k3", 3L), Map.of("k5", 3L)),
                r("r2-0", ordered("raw2", 3, "raw3", 2), Map.of("k2", 3L)),
                r("r0-0", ordered("raw0", 1, "raw3", 1), Map.of("k0", 3L)),
                r("r4-0", Map.of("raw1", 1L), Map.of("k4", 3L)),
                r("r5-1", ordered("k0", 3, "raw0", 2), Map.of("k5", 1L)),
                r("finish", ordered("k5", 2, "k4", 8), Map.of("G", 1L)));
        check(solve(incomparable, Map.of("raw0", 4L, "raw3", 10L)).missing().equals(Map.of("raw1", 6L, "raw3", 22L)),
                "A smaller sum of unlike supplies replaced an incomparable bill");
    }
    private static void boundaries() {
        var recipes = simple(10); var stock = Map.of("R", 5L, "S", 5L); var compiler = new GraphCompiler<>(recipes);
        var b = MissingMaterialExpansionTest.budget(100_000);
        try (var expansion = new MissingMaterialExpansion<>(compiler, suffix(recipes, stock), stock, Set.of(), Set.of("two"), true, false, b)) {
            while (!expansion.step()) {}
            check(expansion.result().missing().equals(Map.of("R", 5L)), "Used an excluded source");
        }
        check(b.reservedBytes() == 0, "Excluded source workspace");
        var externalRecipes = List.of(r("one", ordered("R", 1, "external", 100), Map.of("A", 1L)),
                r("two", ordered("S", 1, "external", 200), Map.of("A", 1L)), recipes.get(2));
        b = MissingMaterialExpansionTest.budget(100_000);
        try (var expansion = new MissingMaterialExpansion<>(new GraphCompiler<>(externalRecipes), suffix(externalRecipes, stock), stock,
                Set.of("external"), Set.of(), true, false, b)) {
            while (!expansion.step()) {}
            check(expansion.result().feasible(), "External supply incorrectly capped a mixture");
            MissingMaterialExpansionTest.verify(expansion.result(), Map.of("R", 5L, "S", 5L, "external", 1500L));
        }
        check(b.reservedBytes() == 0, "External mixture workspace");
        var forced = List.of(r("recycle", Map.of("G", 1L), Map.of("A", 1L)), r("raw", Map.of("S", 1L), Map.of("A", 1L)),
                r("finish", Map.of("A", 10L), Map.of("G", 1L)));
        var plan = solve(forced, Map.of("G", 100L, "S", 5L));
        check(plan.missing().equals(Map.of("S", 5L)), "Target stock substituted for forced crafting");
        check(SequenceSummary.of(plan.steps(), plan.recipes()).delta("G").compareTo(BigInteger.ONE) >= 0, "Target credit lost");
        var mainBudget = MissingMaterialExpansionTest.budget(100_000);
        var work = new GraphPlanningWork<>(compiler, "G", 1, stock, true, true, mainBudget);
        try {
            while (!work.step()) {}
            check(work.result().feasible(), "Whole planner no longer found the existing mixed solution");
        } finally { work.close(); }
        check(mainBudget.reservedBytes() == 0, "Whole planner workspace");
    }
    private static void largeCounts() {
        var recipes = simple(Long.MAX_VALUE); var finish = recipes.get(2); var stock = Map.of("R", Long.MAX_VALUE, "S", Long.MAX_VALUE);
        var demand = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO);
        var original = new GraphPlan<>("G", 2, false, new PlanStep.Batch("finish", 2), Map.of("finish", finish),
                Map.of("A", demand), Map.of(), Map.of("A", demand), GraphPlan.Result.MISSING_INPUT, 0, 0);
        var b = MissingMaterialExpansionTest.budget(100_000);
        try (var expansion = new MissingMaterialExpansion<>(new GraphCompiler<>(recipes), original, stock, Set.of(), Set.of(), true, false, b)) {
            while (!expansion.step()) {}
            var plan = expansion.result();
            check(plan.feasible(), "Exact mixture clipped to long");
            check(plan.initialExact().equals(Map.of("R", BigInteger.valueOf(Long.MAX_VALUE), "S", BigInteger.valueOf(Long.MAX_VALUE))), "Large initial supply");
            check(plan.patternTimesExact().equals(Map.of("one", BigInteger.valueOf(Long.MAX_VALUE), "two", BigInteger.valueOf(Long.MAX_VALUE), "finish", BigInteger.TWO)), "Large mixture counts");
        }
        check(b.reservedBytes() == 0, "Large mixture workspace");
    }
    private static void lifecycle() throws Exception {
        var recipes = simple(10); var compiler = new GraphCompiler<>(recipes); var stock = Map.of("R", 5L, "S", 5L);
        for (String mode : List.of("work", "time", "memory", "cancel")) {
            var cancelled = new AtomicBoolean(); var clock = new AtomicLong();
            var b = new PlanningBudget(1, 100_000, 1L << 20, cancelled::get, clock::get); long held = 0;
            try (var expansion = new MissingMaterialExpansion<>(compiler, suffix(recipes, stock), stock, Set.of(), Set.of(), true, false, b)) {
                while (child(expansion) == null) check(!expansion.step(), "No optional mixture phase");
                var retained = expansion.result(); check(retained.missing().equals(Map.of("R", 5L)), "Missing verified incumbent");
                MissingMaterialExpansionTest.verify(retained, stock);
                switch (mode) {
                    case "work" -> b.charge(b.remainingWork());
                    case "time" -> clock.set(2_000_000);
                    case "memory" -> { held = b.availableBytes(); b.reserve(held); }
                    case "cancel" -> cancelled.set(true);
                }
                try {
                    while (!expansion.step()) {}
                    var actual = expansion.result();
                    check(!mode.equals("cancel") && actual.steps() == retained.steps() && actual.initialExact() == retained.initialExact() &&
                            actual.missingExact() == retained.missingExact() && actual.recipes() == retained.recipes() &&
                            actual.result() == retained.result(), "Optional failure lost the exact incumbent");
                } catch (CancellationException expected) { check(mode.equals("cancel"), "Unexpected cancellation"); }
                finally { b.release(held); }
            }
            check(b.reservedBytes() == 0, "Optional limit leaked: " + mode);
        }
        for (long work : new long[] {80, 128, 600, 1000}) {
            var b = MissingMaterialExpansionTest.budget(work);
            try (var expansion = new MissingMaterialExpansion<>(compiler, suffix(recipes, stock), stock, Set.of(), Set.of(), true, false, b)) {
                while (!expansion.step()) {}
                var plan = expansion.result(); MissingMaterialExpansionTest.verify(plan, stock);
                check(plan.feasible() || plan.missing().equals(Map.of("R", 5L)), "Soft cutoff published a partial mixture");
            }
            check(b.reservedBytes() == 0, "Soft cutoff leaked");
        }
    }
    private static void scheduler(boolean cancel) throws Exception {
        var inputs = new LinkedHashMap<String, Long>(); var stock = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 256; i++) { inputs.put("raw" + i, 1L); stock.put("raw" + i, 5L); }
        stock.put("S", 5L);
        var recipes = List.of(r("one", inputs, Map.of("A", 1L)), r("two", Map.of("S", 1L), Map.of("A", 1L)),
                r("finish", Map.of("A", 10L), Map.of("G", 1L)));
        var cancelled = new AtomicBoolean(); var b = new PlanningBudget(0, 100_000, 4L << 20, cancelled::get, System::nanoTime);
        try (var expansion = new MissingMaterialExpansion<>(new GraphCompiler<>(recipes), suffix(recipes, stock), stock, Set.of(), Set.of(), true, false, b)) {
            for (int i = 0; child(expansion) == null || !PHASE.get(child(expansion)).toString().equals("FUNDING"); i++)
                check(i < 10_000 && !expansion.step(), "Never suspended a wide funding scan");
            long before = b.nodes(); var entered = new CountDownLatch(1); var released = new CountDownLatch(1);
            try (var scheduler = new PlanningScheduler(1, 8, 64, 1_000_000_000)) {
                var future = scheduler.submit(new PlanningScheduler.Work<GraphPlan<String>>() {
                    boolean started;
                    public boolean advance(PlanningScheduler.Slice slice) {
                        if (!started) {
                            started = true; entered.countDown();
                            try { if (!released.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test handoff timed out"); }
                            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                        }
                        while (slice.nextUncharged()) if (expansion.step()) return true;
                        return false;
                    }
                    public GraphPlan<String> result() { return expansion.result(); }
                    public void close() { expansion.close(); }
                }, b);
                check(entered.await(5, TimeUnit.SECONDS), "Mixture did not start");
                var marker = scheduler.submit(new PlanningScheduler.Work<Long>() {
                    long observed;
                    public boolean advance(PlanningScheduler.Slice slice) { observed = b.nodes() - before; if (cancel) cancelled.set(true); return true; }
                    public Long result() { return observed; }
                }, MissingMaterialExpansionTest.budget(1000));
                released.countDown();
                check(marker.get(10, TimeUnit.SECONDS) <= 64, "Funding scan monopolized the scheduler");
                try { check(future.get(10, TimeUnit.SECONDS).feasible() && !cancel, "Mixture schedule result"); }
                catch (CancellationException expected) { check(cancel, "Unexpected cancellation"); }
                catch (ExecutionException failure) { check(cancel && failure.getCause() instanceof CancellationException, "Unexpected failure " + failure); }
            } finally { released.countDown(); }
        }
        check(b.reservedBytes() == 0, "Scheduled mixture workspace");
    }
}
