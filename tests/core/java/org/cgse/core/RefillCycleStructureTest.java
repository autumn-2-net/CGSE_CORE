// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Multi-recipe turnover is a property of the word, not one recipe's ports. */
public final class RefillCycleStructureTest {
    private static int checks;
    private static final java.lang.reflect.Field PHASE;
    static {
        try { PHASE = RefillWordPruning.class.getDeclaredField("phase"); PHASE.setAccessible(true); }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    private static String phase(RefillWordPruning<?> p) {
        try { return PHASE.get(p).toString(); }
        catch (IllegalAccessException e) { throw new AssertionError(e); }
    }
    private static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return MissingMaterialExpansionTest.recipe(id, in, out);
    }
    private record Fixture(List<GraphRecipe<String>> recipes, PlanStep body, PlanStep word) {}
    private static Fixture cycle(int length, long startup, long gain, long demand, long coproduct, long runs) {
        var recipes = new ArrayList<GraphRecipe<String>>(); var steps = new ArrayList<PlanStep>();
        for (int i = 0; i < length; i++) {
            String input = i == 0 ? "A" : "middle" + i;
            var in = new LinkedHashMap<String, Long>(); in.put(input, i == 0 ? startup : (long) i + 1);
            if (i == 0) in.put("feed", 1L);
            var out = new LinkedHashMap<String, Long>(); out.put(i == length - 1 ? "A" : "middle" + (i + 1), i == length - 1 ? startup + gain : (long) i + 2);
            if (i == length - 1 && coproduct > 0) out.put("joint", 1L);
            recipes.add(r("r" + i, in, out)); steps.add(new PlanStep.Batch("r" + i, 1));
        }
        var in = new LinkedHashMap<String, Long>(); in.put("A", demand); if (coproduct > 0) in.put("joint", coproduct);
        recipes.add(r("finish", in, Map.of("G", 1L)));
        PlanStep body = new PlanStep.Sequence(steps);
        return new Fixture(recipes, body, new PlanStep.Sequence(List.of(new PlanStep.Repeat(body, runs), new PlanStep.Batch("finish", 1))));
    }
    public static void main(String[] args) throws Exception {
        variants(); shapes(); ordinaryAndBounded(); lifecycle(); scheduler(false); scheduler(true);
        System.out.println("RefillCycleStructureTest: assertions=" + checks);
    }
    private static void variants() {
        for (int length : new int[] {2, 3, 5}) for (long startup : new long[] {1, 3, 9}) for (long gain : new long[] {1, 2, 4})
            for (long seed : new long[] {0, startup}) for (long joint : new long[] {0, 12}) {
                long demand = startup + 4 * gain;
                long needed = Math.max((demand + seed - startup + gain - 1) / gain, joint);
                var fixture = cycle(length, startup, gain, demand, joint, needed + 7);
                check(fixture.recipes.stream().noneMatch(GraphRecipe::hasInputReturns), "Fixture has a direct self return");
                for (long feed : new long[] {0, needed - 1, needed}) {
                    var recipes = new ArrayList<>(fixture.recipes); Collections.reverse(recipes);
                    var stock = Map.of("feed", feed); var seeds = seed == 0 ? Map.<String, Long>of() : Map.of("A", seed);
                    var original = RefillWordPruningTest.literalPlan(recipes, fixture.word, seeds, stock, Set.of());
                    var result = prune(original, stock, 8192);
                    check(result.patternTimesExact().get("r0").equals(BigInteger.valueOf(needed)), "Multi-step count " + result.patternTimesExact());
                    check(result.initialExact().get("feed").equals(BigInteger.valueOf(needed)), "Multi-step feed quantity");
                    for (int i = 0; i < length; i++) check(result.patternTimesExact().get("r" + i).equals(BigInteger.valueOf(needed)), "Reordered or split a cycle body");
                    var b = MissingMaterialExpansionTest.budget(100_000);
                    try (var expansion = new MissingMaterialExpansion<>(new GraphCompiler<>(recipes), original, stock, Set.of(), Set.of(), true, false, b)) {
                        while (!expansion.step()) {}
                        check(expansion.result().missingExact().equals(result.missingExact()), "Completed expansion did not refine the loop");
                    }
                    check(b.reservedBytes() == 0, "Expansion lifecycle");
                }
            }
    }
    private static void shapes() {
        var fixture = cycle(2, 1, 1, 10, 0, 20);
        var parts = new ArrayList<PlanStep>(); for (int i = 0; i < 20; i++) parts.add(fixture.body); parts.add(new PlanStep.Batch("finish", 1));
        var old = RefillWordPruningTest.literalPlan(fixture.recipes, new PlanStep.Sequence(parts), Map.of(), Map.of(), Set.of());
        var flat = prune(old, Map.of(), 8192);
        // A flat word prunes individual batches. At the last kept return its
        // original prefix has already consumed A, so that local bound keeps
        // ten pairs. A composite repeat can remove the whole pair and needs
        // only nine. This pass neither invents blocks nor claims optimality.
        check(flat.patternTimesExact().get("r0").equals(BigInteger.TEN) && flat.initialExact().get("feed").equals(BigInteger.TEN),
                "Flat shared occurrences were skipped: " + flat.patternTimesExact() + "/" + flat.initialExact());
        PlanStep deep = fixture.word; for (int i = 0; i < 200; i++) deep = new PlanStep.Sequence(List.of(deep));
        old = RefillWordPruningTest.literalPlan(fixture.recipes, deep, Map.of(), Map.of(), Set.of());
        check(prune(old, Map.of(), 8192).patternTimesExact().get("r0").equals(BigInteger.valueOf(9)), "Deep structural scan failed");
        var huge = BigInteger.valueOf(Long.MAX_VALUE).pow(2);
        PlanStep repeated = new PlanStep.Repeat(new PlanStep.Repeat(fixture.body, Long.MAX_VALUE), Long.MAX_VALUE);
        var initial = Map.of("A", BigInteger.ONE, "feed", huge);
        var bindings = new LinkedHashMap<String, GraphRecipe<String>>(); fixture.recipes.forEach(r -> bindings.put(r.id(), r));
        var large = new GraphPlan<>("G", 1, false, new PlanStep.Sequence(List.of(repeated, new PlanStep.Batch("finish", 1))), bindings,
                initial, Map.of(), initial, GraphPlan.Result.MISSING_INPUT, 0, 0);
        check(prune(large, Map.of(), 8192).patternTimesExact().get("r0").equals(BigInteger.valueOf(9)), "Huge repeat was expanded or clipped");
        var zeroWord = new PlanStep.Sequence(List.of(new PlanStep.Repeat(fixture.body, 0), new PlanStep.Batch("r0", 0), new PlanStep.Batch("finish", 1)));
        old = RefillWordPruningTest.literalPlan(fixture.recipes, zeroWord, Map.of(), Map.of(), Set.of());
        check(prune(old, Map.of(), 8192) == old, "Zero-count cycles triggered refinement");
    }
    private static void ordinaryAndBounded() {
        var recipes = new ArrayList<GraphRecipe<String>>(); var steps = new ArrayList<PlanStep>();
        for (int i = 0; i < 64; i++) {
            recipes.add(r("r" + i, Map.of(i == 0 ? "raw" : "k" + i, 1L), Map.of(i == 63 ? "G" : "k" + (i + 1), 1L)));
            steps.add(new PlanStep.Batch("r" + i, 1));
        }
        Collections.reverse(recipes);
        var old = RefillWordPruningTest.literalPlan(recipes, new PlanStep.Sequence(steps), Map.of(), Map.of(), Set.of());
        var b = MissingMaterialExpansionTest.budget(100_000);
        try (var pruning = new RefillWordPruning<>(old, Map.of(), Set.of(), true, false, b, 8192)) {
            boolean done;
            do { done = pruning.step(); check(!phase(pruning).equals("ORIGINAL"), "Catalog order masqueraded as word turnover"); } while (!done);
            check(pruning.result() == old && b.nodes() < 1024, "Ordinary chain received a full fold");
        }
        check(b.reservedBytes() == 0, "Ordinary scan workspace");
        var wide = wide(4096); b = MissingMaterialExpansionTest.budget(100_000);
        try (var pruning = new RefillWordPruning<>(wide, Map.of(), Set.of(), true, false, b, 8192)) {
            while (!pruning.step()) {}
            check(pruning.result() == wide && b.nodes() < 1100, "Structural hint exhausted the whole improvement budget");
        }
        check(b.reservedBytes() == 0, "Structural cutoff workspace");
        var fixture = cycle(2, 1, 1, 10, 0, 20);
        old = RefillWordPruningTest.literalPlan(fixture.recipes, fixture.word, Map.of(), Map.of(), Set.of());
        for (long cap : new long[] {1, 32, 64, 128, 512}) {
            var result = prune(old, Map.of(), cap);
            check(result.missingExact().get("feed").compareTo(BigInteger.valueOf(20)) <= 0, "Cutoff published an unfinished bill");
        }
    }
    private static GraphPlan<String> wide(int size) {
        var in = new LinkedHashMap<String, Long>(); in.put("A", 1L);
        var out = new LinkedHashMap<String, Long>(); out.put("B", 1L);
        for (int i = 0; i < size; i++) { in.put("feed" + i, 1L); out.put("waste" + i, 1L); }
        var recipes = List.of(r("make", in, out), r("return", Map.of("B", 1L), Map.of("A", 2L)), r("finish", Map.of("A", 10L), Map.of("G", 1L)));
        var body = new PlanStep.Sequence(List.of(new PlanStep.Batch("make", 1), new PlanStep.Batch("return", 1)));
        return RefillWordPruningTest.literalPlan(recipes, new PlanStep.Sequence(List.of(new PlanStep.Repeat(body, 20), new PlanStep.Batch("finish", 1))), Map.of(), Map.of(), Set.of());
    }
    private static void reach(RefillWordPruning<String> pruning, String target) {
        for (int i = 0; !phase(pruning).equals(target); i++) check(i < 10_000 && !pruning.step(), "No suspended " + target);
    }
    private static void lifecycle() {
        var original = wide(128);
        for (String at : List.of("CONSUMED", "PRODUCED")) for (String mode : List.of("work", "time", "memory", "cancel")) {
            var clock = new AtomicLong(); var cancel = new AtomicBoolean(); var b = new PlanningBudget(1, 100_000, 4L << 20, cancel::get, clock::get);
            b.failureDetail("retained proof"); long held = 0;
            try (var pruning = new RefillWordPruning<>(original, Map.of(), Set.of(), true, false, b, 8192)) {
                reach(pruning, at); check(pruning.result() == original, "Hint exposed partial state");
                switch (mode) {
                    case "work" -> b.charge(b.remainingWork());
                    case "time" -> clock.set(2_000_000);
                    case "memory" -> { held = b.availableBytes(); b.reserve(held); }
                    case "cancel" -> cancel.set(true);
                }
                try { while (!pruning.step()) {} check(!mode.equals("cancel") && pruning.result() == original, "Interrupted structural pass replaced incumbent"); }
                catch (CancellationException expected) { check(mode.equals("cancel"), "Unexpected cancellation"); }
                finally { b.release(held); }
            }
            check(b.reservedBytes() == 0 && b.failureDetail().equals("retained proof"), "Hint lifecycle " + mode);
        }
    }
    private static void scheduler(boolean cancel) throws Exception {
        var original = wide(128); var cancelled = new AtomicBoolean(); var b = new PlanningBudget(0, 100_000, 4L << 20, cancelled::get, System::nanoTime);
        try (var pruning = new RefillWordPruning<>(original, Map.of(), Set.of(), true, false, b, 8192)) {
            reach(pruning, "CONSUMED"); long before = b.nodes(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            try (var scheduler = new PlanningScheduler(1, 8, 64, 1_000_000_000)) {
                var future = scheduler.submit(new PlanningScheduler.Work<GraphPlan<String>>() {
                    boolean started;
                    public boolean advance(PlanningScheduler.Slice slice) {
                        if (!started) {
                            started = true; entered.countDown();
                            try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Handoff timeout"); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                        }
                        while (slice.nextUncharged()) if (pruning.step()) return true;
                        return false;
                    }
                    public GraphPlan<String> result() { return pruning.result(); }
                    public void close() { pruning.close(); }
                }, b);
                check(entered.await(5, TimeUnit.SECONDS), "Scheduler did not start");
                var marker = scheduler.submit(new PlanningScheduler.Work<Long>() {
                    long seen;
                    public boolean advance(PlanningScheduler.Slice slice) { seen = b.nodes() - before; if (cancel) cancelled.set(true); return true; }
                    public Long result() { return seen; }
                }, MissingMaterialExpansionTest.budget(1000));
                release.countDown(); check(marker.get(10, TimeUnit.SECONDS) <= 64, "Structural scan monopolized the worker");
                try { var p = future.get(10, TimeUnit.SECONDS); check(!cancel, "Cancellation lost"); RefillWordPruningTest.literalVerify(p, Map.of(), Set.of()); }
                catch (CancellationException e) { check(cancel, "Unexpected cancellation"); }
                catch (ExecutionException e) { check(cancel && e.getCause() instanceof CancellationException, "Unexpected scheduler failure " + e); }
            } finally { release.countDown(); }
        }
        check(b.reservedBytes() == 0, "Scheduled scan workspace");
    }
    private static GraphPlan<String> prune(GraphPlan<String> original, Map<String, Long> stock, long allowance) {
        var b = MissingMaterialExpansionTest.budget(100_000); GraphPlan<String> result;
        try (var pruning = new RefillWordPruning<>(original, stock, Set.of(), true, false, b, allowance)) {
            while (!pruning.step()) {} result = pruning.result();
        }
        check(b.reservedBytes() == 0, "Pruning workspace"); RefillWordPruningTest.literalVerify(result, stock, Set.of());
        for (var e : result.initialExact().entrySet()) check(e.getValue().compareTo(original.initialExact().getOrDefault(e.getKey(), BigInteger.ZERO)) <= 0, "Increased original withdrawals");
        for (var e : result.missingExact().entrySet()) check(e.getValue().compareTo(original.missingExact().getOrDefault(e.getKey(), BigInteger.ZERO)) <= 0, "Increased original missing supply");
        return result;
    }
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
}
