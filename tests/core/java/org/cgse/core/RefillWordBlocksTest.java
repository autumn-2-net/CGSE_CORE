// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Literal execution and conservation oracles for tandem-block refill pruning. */
public final class RefillWordBlocksTest {
    private static int checks;
    private static final java.lang.reflect.Field PHASE;
    static {
        try { PHASE = RefillWordPruning.class.getDeclaredField("phase"); PHASE.setAccessible(true); }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    private static String phase(RefillWordPruning<?> work) {
        try { return PHASE.get(work).toString(); }
        catch (IllegalAccessException e) { throw new AssertionError(e); }
    }
    private record Fixture(List<GraphRecipe<String>> recipes, GraphPlan<String> plan, long needed) {}
    private static Fixture fixture(int length, long gain, long seed, long joint, int shape, boolean reverse, Map<String, Long> stock) {
        var recipes = new ArrayList<GraphRecipe<String>>(); var body = new ArrayList<PlanStep>();
        for (int i = 0; i < length; i++) {
            var in = new LinkedHashMap<String, Long>(); in.put(i == 0 ? "A" : "m" + i, i == 0 ? 3L : 2L);
            if (i == 0) { in.put("feed", 1L); in.put("tool", 1L); }
            var out = new LinkedHashMap<String, Long>(); out.put(i == length - 1 ? "A" : "m" + (i + 1), i == length - 1 ? 3 + gain : 2L);
            if (i == length - 1) { out.put("tool", 1L); out.put("joint", 1L); }
            recipes.add(MissingMaterialExpansionTest.recipe("r" + i, in, out)); body.add(new PlanStep.Batch("r" + i, 1));
        }
        long demand = 3 + 4 * gain, needed = Math.max((demand + seed - 3 + gain - 1) / gain, joint);
        var in = new LinkedHashMap<String, Long>(); in.put("A", demand); if (joint > 0) in.put("joint", joint);
        recipes.add(MissingMaterialExpansionTest.recipe("finish", in, Map.of("G", 1L)));
        var word = new ArrayList<PlanStep>(); var shared = new PlanStep.Sequence(body);
        for (int j = 0; j < needed + 4; j++) {
            if (shape == 0) for (int i = 0; i < length; i++) word.add(new PlanStep.Batch("r" + i, 1));
            else word.add(shape == 1 ? shared : new PlanStep.Sequence(List.of(new PlanStep.Sequence(body))));
        }
        word.add(new PlanStep.Batch("finish", 1)); if (reverse) Collections.reverse(recipes);
        var seeds = new LinkedHashMap<String, Long>(); seeds.put("tool", 1L); if (seed > 0) seeds.put("A", seed);
        return new Fixture(recipes, RefillWordPruningTest.literalPlan(recipes, new PlanStep.Sequence(word), seeds, stock, Set.of()), needed);
    }
    public static void main(String[] args) throws Exception {
        families(); exactRegrouping(); contracts(); cutoffs(); scheduler(false); scheduler(true);
        System.out.println("RefillWordBlocksTest: assertions=" + checks);
    }
    private static void families() {
        for (int length : new int[] {2, 3, 5, 8}) for (long gain : new long[] {1, 2}) for (long seed : new long[] {0, 3})
            for (long joint : new long[] {0, 8}) for (int shape = 0; shape < 3; shape++) for (boolean reverse : List.of(false, true)) {
                var stock = Map.of("feed", shape * 3L, "tool", 1L);
                var f = fixture(length, gain, seed, joint, shape, reverse, stock);
                var p = prune(f.plan, stock, Set.of(), true, 8192);
                long actual = p.patternTimesExact().get("r0").longValueExact();
                check(actual == f.needed, "Flat cycle bound " + length + "/" + gain + "/" + seed + "/" + joint + "/" + shape + ": " + actual + " != " + f.needed);
                for (int i = 1; i < length; i++) check(p.patternTimesExact().get("r" + i).longValueExact() == actual, "Split an ordered cycle");
                check(p.initialExact().get("feed").longValueExact() == actual, "Feed conservation");
                check(p.initialExact().get("tool").equals(BigInteger.ONE) && p.seeds().equals(f.plan.seeds()), "Lost shared tool or final seed");
                if (shape == 0 && !reverse) {
                    var b = MissingMaterialExpansionTest.budget(100_000);
                    var catalog = new ArrayList<>(f.recipes);
                    // Exercise the normal expanded-prefix path, whose existing
                    // work-scaled quota can also fund the optional block pass.
                    for (int i = 0; i < 8; i++) catalog.add(MissingMaterialExpansionTest.recipe("feed" + i,
                            Map.of(i == 7 ? "raw" : "feed" + (i + 1), 1L), Map.of(i == 0 ? "feed" : "feed" + i, 1L)));
                    try (var expansion = new MissingMaterialExpansion<>(new GraphCompiler<>(catalog), f.plan, stock, Set.of(), Set.of(), true, false, b)) {
                        while (!expansion.step()) {}
                        var expected = new LinkedHashMap<>(p.missingExact()); expected.put("raw", expected.remove("feed"));
                        check(expansion.result().missingExact().equals(expected), "Expansion result " + length + "/" + gain + "/" + seed + "/" + joint + ": " + expansion.result().missingExact() + " != " + expected + "; work=" + b.nodes());
                    }
                    check(b.reservedBytes() == 0, "Expansion workspace");
                }
            }
    }
    private static void exactRegrouping() {
        var random = new Random(19102931);
        for (int test = 0; test < 200; test++) {
            var flat = new ArrayList<PlanStep>(); int width = 2 + random.nextInt(15);
            var body = new ArrayList<PlanStep>();
            for (int i = 0; i < width; i++) body.add(new PlanStep.Batch("r" + random.nextInt(5), random.nextInt(4)));
            for (int i = 0; i < 2 + random.nextInt(5); i++) flat.addAll(body);
            flat.add(new PlanStep.Batch("finish", 1));
            var reversed = new ArrayList<>(flat); Collections.reverse(reversed);
            var b = MissingMaterialExpansionTest.budget(100_000);
            try (var blocks = new RefillWordBlocks(reversed, b, 8192)) {
                while (!blocks.step()) {}
                check(blocks.result() != null, "Failed to recognize a tandem block");
                check(events(blocks.result()).equals(flat), "Grouping changed order, counts or zero batch boundaries");
            }
            check(b.reservedBytes() == 0, "Regrouping workspace");
        }
        // Distinct deeply nested nodes must not invoke recursive record equality;
        // identical opaque nodes count by occurrence without expanding the repeat.
        PlanStep deep = new PlanStep.Batch("a", 1), other = new PlanStep.Batch("a", 1);
        for (int i = 0; i < 10_000; i++) { deep = new PlanStep.Repeat(deep, Long.MAX_VALUE); other = new PlanStep.Repeat(other, Long.MAX_VALUE); }
        var atom = new PlanStep.Batch("b", 2);
        var reverse = List.of(atom, deep, atom, deep, atom, other);
        var b = MissingMaterialExpansionTest.budget(1000);
        try (var blocks = new RefillWordBlocks(reverse, b, 512)) {
            while (!blocks.step()) {}
            check(blocks.result() != null && b.nodes() < 100, "Expanded opaque nested repeats");
        }
        check(b.reservedBytes() == 0, "Deep comparison workspace");
    }
    private static List<PlanStep> events(PlanStep step) {
        var events = new ArrayList<PlanStep>(); var todo = new ArrayDeque<PlanStep>(); todo.push(step);
        while (!todo.isEmpty()) {
            var next = todo.pop();
            if (next instanceof PlanStep.Batch) events.add(next);
            else if (next instanceof PlanStep.Sequence s) for (int i = s.children().size() - 1; i >= 0; i--) todo.push(s.children().get(i));
            else { var r = (PlanStep.Repeat) next; check(r.times() < 100, "Oracle bound"); for (int i = 0; i < r.times(); i++) todo.push(r.body()); }
        }
        return events;
    }
    private static void contracts() {
        var f = fixture(2, 1, 0, 0, 0, false, Map.of());
        var p = prune(f.plan, Map.of(), Set.of("feed"), true, 8192);
        check(!p.missingExact().containsKey("feed"), "External feed reported missing");
        var recipes = new ArrayList<>(f.recipes);
        var first = recipes.get(0); var slots = new ArrayList<>(first.slots());
        slots.add(new GraphRecipe.Slot<>("configuration", 1L, 0, true));
        recipes.set(0, new GraphRecipe<>(first.id(), "physical", slots, first.outputs()));
        var original = RefillWordPruningTest.literalPlan(recipes, f.plan.steps(), f.plan.seeds(), Map.of(), Set.of());
        check(prune(original, Map.of(), Set.of(), true, 8192) == original, "Changed consumed configuration pushes");
        recipes = new ArrayList<>(f.recipes); var last = recipes.get(1); var outputs = new LinkedHashMap<>(last.outputs()); outputs.put("G", 1L);
        recipes.set(1, new GraphRecipe<>(last.id(), last.binding(), last.slots(), outputs));
        original = RefillWordPruningTest.literalPlan(recipes, f.plan.steps(), f.plan.seeds(), Map.of(), Set.of());
        p = prune(original, Map.of(), Set.of(), true, 8192);
        check(p.patternTimesExact().equals(original.patternTimesExact()), "Lost forced target credit");
        check(prune(original, Map.of(), Set.of(), false, 8192).initialExact().get("feed").compareTo(original.initialExact().get("feed")) < 0, "Unforced surplus kept");
    }
    private static void reach(RefillWordPruning<String> work, String at) {
        for (int i = 0; !phase(work).equals(at); i++) check(i < 1000 && !work.step(), "No suspended " + at);
    }
    private static GraphPlan<String> sample() {
        var recipes = List.of(MissingMaterialExpansionTest.recipe("make", Map.of("A", 1L, "feed", 1L), Map.of("B", 1L)),
                MissingMaterialExpansionTest.recipe("back", Map.of("B", 1L), Map.of("A", 2L)),
                MissingMaterialExpansionTest.recipe("finish", Map.of("A", 10L), Map.of("G", 1L)));
        var steps = new ArrayList<PlanStep>();
        for (int i = 0; i < 20; i++) { steps.add(new PlanStep.Batch("make", 1)); steps.add(new PlanStep.Batch("back", 1)); }
        steps.add(new PlanStep.Batch("finish", 1));
        return RefillWordPruningTest.literalPlan(recipes, new PlanStep.Sequence(steps), Map.of(), Map.of(), Set.of());
    }
    private static void cutoffs() {
        var original = sample();
        for (String at : List.of("BLOCKS", "BLOCK_PRUNE")) for (String mode : List.of("work", "time", "memory", "cancel")) {
            var clock = new AtomicLong(); var cancel = new AtomicBoolean();
            var b = new PlanningBudget(1, 100_000, 4L << 20, cancel::get, clock::get); b.failureDetail("retained proof"); long held = 0;
            try (var work = new RefillWordPruning<>(original, Map.of(), Set.of(), true, false, b, 8192)) {
                reach(work, at); var accepted = work.result();
                check(accepted != original && accepted.initialExact().get("feed").longValueExact() == 10, "Primary pruning did not publish first");
                switch (mode) {
                    case "work" -> b.charge(b.remainingWork());
                    case "time" -> clock.set(2_000_000);
                    case "memory" -> { held = b.availableBytes(); b.reserve(held); }
                    case "cancel" -> cancel.set(true);
                }
                try { while (!work.step()) {} check(!mode.equals("cancel") && work.result() == accepted, "Lost completed incumbent"); }
                catch (CancellationException expected) { check(mode.equals("cancel"), "Unexpected cancellation"); }
                finally { b.release(held); }
            }
            check(b.reservedBytes() == 0 && b.failureDetail().equals("retained proof"), "Optional exit lifecycle");
        }
        for (long cap : new long[] {0, 64, 512, 800, 1024, 1536, 2048, 4096}) prune(original, Map.of(), Set.of(), true, cap);
    }
    private static void scheduler(boolean cancel) throws Exception {
        var original = sample(); var cancelled = new AtomicBoolean();
        var b = new PlanningBudget(0, 100_000, 4L << 20, cancelled::get, System::nanoTime);
        try (var work = new RefillWordPruning<>(original, Map.of(), Set.of(), true, false, b, 8192)) {
            reach(work, "BLOCKS"); long before = b.nodes(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            try (var scheduler = new PlanningScheduler(1, 8, 64, 1_000_000_000)) {
                var future = scheduler.submit(new PlanningScheduler.Work<GraphPlan<String>>() {
                    boolean started;
                    public boolean advance(PlanningScheduler.Slice slice) {
                        if (!started) {
                            started = true; entered.countDown();
                            try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Handoff timeout"); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                        }
                        while (slice.nextUncharged()) if (work.step()) return true;
                        return false;
                    }
                    public GraphPlan<String> result() { return work.result(); }
                    public void close() { work.close(); }
                }, b);
                check(entered.await(5, TimeUnit.SECONDS), "Scheduler did not start");
                var marker = scheduler.submit(new PlanningScheduler.Work<Long>() {
                    long observed;
                    public boolean advance(PlanningScheduler.Slice slice) { observed = b.nodes() - before; if (cancel) cancelled.set(true); return true; }
                    public Long result() { return observed; }
                }, MissingMaterialExpansionTest.budget(1000));
                // A short completed scan may hand off to one further <=64-unit
                // child continuation before the scheduler's 64-unit slice ends.
                release.countDown(); check(marker.get(10, TimeUnit.SECONDS) < 128, "Grouping monopolized worker");
                try { var p = future.get(10, TimeUnit.SECONDS); check(!cancel, "Cancellation lost"); RefillWordPruningTest.literalVerify(p, Map.of(), Set.of()); }
                catch (CancellationException e) { check(cancel, "Unexpected cancellation"); }
                catch (ExecutionException e) { check(cancel && e.getCause() instanceof CancellationException, "Unexpected failure " + e); }
            } finally { release.countDown(); }
        }
        check(b.reservedBytes() == 0, "Scheduled workspace");
    }
    private static GraphPlan<String> prune(GraphPlan<String> original, Map<String, Long> stock, Set<String> external, boolean force, long cap) {
        var b = MissingMaterialExpansionTest.budget(100_000); GraphPlan<String> result;
        try (var work = new RefillWordPruning<>(original, stock, external, force, false, b, cap)) {
            while (!work.step()) {} result = work.result();
        }
        check(b.reservedBytes() == 0 && b.nodes() <= cap + 64, "Workspace or allowance");
        RefillWordPruningTest.literalVerify(result, stock, external);
        for (var e : result.initialExact().entrySet()) check(e.getValue().compareTo(original.initialExact().getOrDefault(e.getKey(), BigInteger.ZERO)) <= 0, "Increased initial supply");
        for (var e : result.missingExact().entrySet()) check(e.getValue().compareTo(original.missingExact().getOrDefault(e.getKey(), BigInteger.ZERO)) <= 0, "Increased missing supply");
        return result;
    }
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
}
