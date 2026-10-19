// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Exact trace, conservation and incumbent-lifecycle checks for long tandem hints. */
public final class RefillWordAnchorsTest {
    private static int checks;
    private static final java.lang.reflect.Field PHASE;
    static {
        try { PHASE = RefillWordPruning.class.getDeclaredField("phase"); PHASE.setAccessible(true); }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    record Fixture(List<GraphRecipe<String>> recipes, GraphPlan<String> plan, Map<String, Long> stock, long needed) {}
    static Fixture fixture(int length, long goal, long seed, long joint, boolean reverse, int shape) {
        var recipes = new ArrayList<GraphRecipe<String>>(); var word = new ArrayList<PlanStep>();
        long needed = Math.max(goal + seed, joint);
        for (int i = 0; i < length; i++) {
            var in = new LinkedHashMap<String, Long>(); in.put(i == 0 ? "A" : "m" + i, i == 0 ? 3L : 2L);
            if (i == 0) { in.put("feed", 1L); in.put("tool", 1L); }
            var out = new LinkedHashMap<String, Long>(); out.put(i == length - 1 ? "A" : "m" + (i + 1), i == length - 1 ? 4L : 2L);
            if (i == length - 1) { out.put("tool", 1L); out.put("joint", 1L); }
            recipes.add(MissingMaterialExpansionTest.recipe("r" + i, in, out));
        }
        var finish = new LinkedHashMap<String, Long>(); finish.put("A", 3 + goal); if (joint > 0) finish.put("joint", joint);
        recipes.add(MissingMaterialExpansionTest.recipe("finish", finish, Map.of("G", 1L)));
        if (reverse) Collections.reverse(recipes);
        for (int j = 0; j < needed + 4; j++) {
            var body = new ArrayList<PlanStep>();
            for (int i = 0; i < length; i++) body.add(new PlanStep.Batch("r" + i, 1));
            if (shape == 0) word.addAll(body); else word.add(new PlanStep.Sequence(List.of(new PlanStep.Sequence(body))));
        }
        word.add(new PlanStep.Batch("finish", 1));
        var seeds = new LinkedHashMap<String, Long>(); seeds.put("tool", 1L); if (seed > 0) seeds.put("A", seed);
        var stock = Map.of("feed", 2L, "tool", 1L);
        return new Fixture(recipes, RefillWordPruningTest.literalPlan(recipes, new PlanStep.Sequence(word), seeds, stock, Set.of()), stock, needed);
    }
    public static void main(String[] args) throws Exception {
        families(); contracts(); traces(); opaque(); limits(); ownership();
        System.out.println("RefillWordAnchorsTest: assertions=" + checks);
    }
    private static void families() {
        for (int length : new int[] {17, 24, 32}) for (long goal : new long[] {1, 3}) for (long seed : new long[] {0, 3})
        for (long joint : new long[] {0, 5}) for (boolean reverse : List.of(false, true)) for (int shape = 0; shape < 2; shape++) {
            var f = fixture(length, goal, seed, joint, reverse, shape);
            var b = budget(100_000); GraphPlan<String> result;
            // A real verifier owns the borrowed vectors throughout child work.
            try (var owner = owner(f.plan, b)) {
                while (!owner.step()) {} long start = b.nodes();
                try (var work = new RefillWordPruning<>(f.plan, f.stock, Set.of(), true, false, b, 8192, owner.summary())) {
                    while (!work.step()) {} result = work.result();
                }
                check(b.nodes() - start <= 8256, "Exceeded existing optional quota");
            }
            check(b.reservedBytes() == 0, "Borrowed fold leaked"); verify(f, result);
            check(result.initialExact().get("feed").equals(BigInteger.valueOf(f.needed)), "Missed feed conservation bound");
            for (int i = 0; i < length; i++) check(result.patternTimesExact().get("r" + i).equals(BigInteger.valueOf(f.needed)), "Split ordered loop");
        }
        // Exercise the shared production refinement entry, including a finite
        // upstream chain for feed. Its work-scaled allowance remains unchanged.
        for (int length : new int[] {17, 24, 32}) {
            var f = fixture(length, 1, 0, 0, false, 0); var recipes = new ArrayList<>(f.recipes);
            for (int i = 0; i < 16; i++) recipes.add(MissingMaterialExpansionTest.recipe("feed" + i,
                    Map.of(i == 15 ? "raw" : "f" + (i + 1), 1L), Map.of(i == 0 ? "feed" : "f" + i, 1L)));
            var b = budget(100_000);
            try (var work = new MissingMaterialExpansion<>(new GraphCompiler<>(recipes), f.plan, f.stock, Set.of(), Set.of(), true, false, b)) {
                while (!work.step()) {}
                var result = work.result(); RefillWordPruningTest.literalVerify(result, f.stock, Set.of());
                check(result.missingExact().equals(Map.of("A", BigInteger.valueOf(3))), "Retained unnecessary feed/raw shortage");
            }
            check(b.reservedBytes() == 0, "Expanded prefix leaked");
        }
    }
    private static void contracts() {
        var f = fixture(24, 1, 0, 0, false, 0);
        var recipes = new ArrayList<>(f.recipes); var last = recipes.get(23);
        var outputs = new LinkedHashMap<>(last.outputs()); outputs.put("G", 1L);
        recipes.set(23, new GraphRecipe<>(last.id(), last.binding(), last.slots(), outputs));
        var original = RefillWordPruningTest.literalPlan(recipes, f.plan.steps(), f.plan.seeds(), f.stock, Set.of());
        var forced = prune(original, f.stock, Set.of(), true);
        check(forced.patternTimesExact().equals(original.patternTimesExact()), "Lost existing forced production credit");
        var unforced = prune(original, f.stock, Set.of(), false);
        check(unforced.initialExact().get("feed").compareTo(original.initialExact().get("feed")) < 0, "Unrequested surplus could not shrink");

        recipes = new ArrayList<>(f.recipes); var first = recipes.get(0); var slots = new ArrayList<>(first.slots());
        slots.add(new GraphRecipe.Slot<>("configuration", 1L, 0, true));
        recipes.set(0, new GraphRecipe<>(first.id(), "configured", slots, first.outputs()));
        original = RefillWordPruningTest.literalPlan(recipes, f.plan.steps(), f.plan.seeds(), f.stock, Set.of());
        check(prune(original, f.stock, Set.of(), true) == original, "Rewrote consumed per-push configuration");

        original = RefillWordPruningTest.literalPlan(f.recipes, f.plan.steps(), f.plan.seeds(), f.stock, Set.of("feed"));
        var supplied = prune(original, f.stock, Set.of("feed"), true);
        check(!supplied.missingExact().containsKey("feed") && supplied.initialExact().get("feed").equals(BigInteger.ONE), "External supply/ordered loop contract");
    }
    private static GraphPlan<String> prune(GraphPlan<String> original, Map<String, Long> stock, Set<String> external, boolean force) {
        var b = budget(100_000); GraphPlan<String> result;
        try (var owner = owner(original, b)) {
            while (!owner.step()) {}
            try (var work = new RefillWordPruning<>(original, stock, external, force, false, b, 8192, owner.summary())) {
                while (!work.step()) {} result = work.result();
            }
        }
        check(b.reservedBytes() == 0, "Contract workspace leak");
        RefillWordPruningTest.literalVerify(result, stock, external);
        return result;
    }
    private static void traces() {
        var random = new Random(2026101903L);
        for (int test = 0; test < 200; test++) {
            var flat = new ArrayList<PlanStep>(); flat.add(new PlanStep.Batch("prefix", 2));
            for (int group = 0; group < 2; group++) {
                int width = 17 + random.nextInt(80), copies = 2 + random.nextInt(5);
                var body = new ArrayList<PlanStep>();
                for (int i = 0; i < width; i++) body.add(new PlanStep.Batch("r" + group + "/" + i, random.nextInt(5)));
                for (int i = 0; i < copies; i++) flat.addAll(body);
                flat.add(new PlanStep.Batch("separator" + group, 1));
            }
            regroup(flat, Set.of(), true);
        }
        // These identifiers collide in String.hashCode. Every accepted hint
        // still checks complete recipe/count equality, including zero batches.
        regroup(List.of(new PlanStep.Batch("FB", 0), new PlanStep.Batch("x", 1), new PlanStep.Batch("Ea", 0),
                new PlanStep.Batch("x", 2), new PlanStep.Batch("FB", 0), new PlanStep.Batch("y", 1)), Set.of(), false);
        for (int test = 0; test < 100; test++) {
            var flat = new ArrayList<PlanStep>();
            for (int i = 0; i < 300; i++) flat.add(new PlanStep.Batch("r" + random.nextInt(9), random.nextInt(4)));
            regroup(flat, Set.of(), false);
        }
    }
    private static void opaque() {
        PlanStep deep = new PlanStep.Batch("a", 1), other = new PlanStep.Batch("a", 1);
        for (int i = 0; i < 10_000; i++) { deep = new PlanStep.Repeat(deep, Long.MAX_VALUE); other = new PlanStep.Repeat(other, Long.MAX_VALUE); }
        var atoms = Collections.newSetFromMap(new IdentityHashMap<PlanStep, Boolean>()); atoms.add(deep); atoms.add(other);
        var flat = new ArrayList<PlanStep>();
        for (int copy = 0; copy < 3; copy++) {
            flat.add(deep); for (int i = 0; i < 20; i++) flat.add(new PlanStep.Batch("r" + i, i));
        }
        flat.add(other); regroup(flat, atoms, true);
    }
    private static void regroup(List<PlanStep> flat, Set<PlanStep> opaque, boolean mustGroup) {
        var reverse = new ArrayList<>(flat); Collections.reverse(reverse); var b = budget(100_000);
        try (var work = new RefillWordAnchors(reverse, b, 8192)) {
            while (!work.step()) {}
            if (mustGroup) check(work.result() != null, "Failed long tandem recognition");
            if (work.result() != null) {
                var trace = new ArrayList<PlanStep>(); var todo = new ArrayDeque<PlanStep>(); todo.push(work.result());
                while (!todo.isEmpty()) {
                    var s = todo.pop();
                    if (opaque.contains(s) || s instanceof PlanStep.Batch) trace.add(s);
                    else if (s instanceof PlanStep.Sequence sequence) for (int i = sequence.children().size() - 1; i >= 0; i--) todo.push(sequence.children().get(i));
                    else { var r = (PlanStep.Repeat) s; check(r.times() < 1000, "Unrolled opaque repeat"); for (int i = 0; i < r.times(); i++) todo.push(r.body()); }
                }
                check(trace.size() == flat.size(), "Changed occurrence count");
                for (int i = 0; i < trace.size(); i++) {
                    var a = trace.get(i); var c = flat.get(i);
                    check(a == c || a instanceof PlanStep.Batch x && c instanceof PlanStep.Batch y && x.runs() == y.runs() && x.recipe().equals(y.recipe()), "Changed ordered batch boundary");
                }
            }
        }
        check(b.reservedBytes() == 0, "Recognition memory leak");
    }
    private static PlanVerification<String> owner(GraphPlan<String> p, PlanningBudget b) {
        return PlanVerification.refill(new GraphPlan<>(p.target(), p.amount(), p.preserveSeeds(), p.steps(), p.recipes(),
                p.initialExact(), p.seeds(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0), b);
    }
    private static String phase(RefillWordPruning<?> work) {
        try { return PHASE.get(work).toString(); } catch (IllegalAccessException e) { throw new AssertionError(e); }
    }
    private static void reach(RefillWordPruning<?> work, String at) {
        for (int i = 0; !phase(work).equals(at); i++) check(i < 10_000 && !work.step(), "No suspended " + at);
    }
    private static void limits() {
        var f = fixture(24, 1, 0, 0, false, 0);
        for (String at : List.of("ANCHORS", "BLOCK_PRUNE")) for (String mode : List.of("work", "time", "memory", "cancel")) {
            var clock = new AtomicLong(); var cancel = new AtomicBoolean();
            var b = new PlanningBudget(1, 100_000, 8L << 20, cancel::get, clock::get); b.failureDetail("retained proof"); long held = 0;
            try (var work = new RefillWordPruning<>(f.plan, f.stock, Set.of(), true, false, b, 8192, SequenceSummary.of(f.plan.steps(), f.plan.recipes()))) {
                reach(work, at); var retained = work.result();
                switch (mode) {
                    case "work" -> b.charge(b.remainingWork());
                    case "time" -> clock.set(2_000_000);
                    case "memory" -> { held = b.availableBytes(); b.reserve(held); }
                    case "cancel" -> cancel.set(true);
                }
                try { while (!work.step()) {} check(!mode.equals("cancel") && work.result() == retained, "Discarded incumbent"); }
                catch (CancellationException expected) { check(mode.equals("cancel"), "Unexpected cancellation"); }
                finally { b.release(held); }
            }
            check(b.reservedBytes() == 0 && b.failureDetail().equals("retained proof"), "Limit cleanup changed proof/accounting");
        }
        for (long cap : new long[] {0, 64, 512, 1024, 2048, 3072, 4096, 8192}) {
            var b = budget(100_000);
            try (var work = new RefillWordPruning<>(f.plan, f.stock, Set.of(), true, false, b, cap)) { while (!work.step()) {} verify(f, work.result()); }
            check(b.nodes() <= cap + 64 && b.reservedBytes() == 0, "Optional quota/reset changed");
        }
    }
    private static void ownership() throws Exception {
        var f = fixture(24, 1, 0, 0, false, 0); var b = budget(100_000);
        try (var owner = owner(f.plan, b)) {
            while (!owner.step()) {}
            try (var work = new RefillWordPruning<>(f.plan, f.stock, Set.of(), true, false, b, 8192, owner.summary())) {
                reach(work, "ANCHORS");
                try (var scheduler = new PlanningScheduler(1, 4, 64, 1_000_000_000)) {
                    var future = scheduler.submit(new PlanningScheduler.Work<GraphPlan<String>>() {
                        public boolean advance(PlanningScheduler.Slice slice) { while (slice.nextUncharged()) if (work.step()) return true; return false; }
                        public GraphPlan<String> result() { return work.result(); }
                        public void close() { work.close(); }
                    }, b);
                    var p = future.get(10, TimeUnit.SECONDS); verify(f, p);
                    check(p.initialExact().get("feed").equals(BigInteger.ONE), "Lost scheduled refinement");
                }
            }
        }
        check(b.reservedBytes() == 0, "Transferred borrowed fold leaked");
    }
    private static void verify(Fixture f, GraphPlan<String> result) {
        RefillWordPruningTest.literalVerify(result, f.stock, Set.of());
        result.initialExact().forEach((k,n) -> check(n.compareTo(f.plan.initialExact().getOrDefault(k, BigInteger.ZERO)) <= 0, "Increased withdrawal"));
        result.missingExact().forEach((k,n) -> check(n.compareTo(f.plan.missingExact().getOrDefault(k, BigInteger.ZERO)) <= 0, "Increased shortage"));
        check(result.seeds().equals(f.plan.seeds()), "Changed final seeds");
    }
    private static PlanningBudget budget(long work) { return MissingMaterialExpansionTest.budget(work); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
