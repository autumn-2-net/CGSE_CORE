// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Funded refill verification must retain every execution obligation. */
public final class RefillVerificationTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        randomWords(); separateObligations(); exactAndShared(); publicProduction(); limits(); scheduler(false); scheduler(true);
        System.out.println("RefillVerificationTest: assertions=" + checks);
    }
    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return MissingMaterialExpansionTest.recipe(id, in, out);
    }
    private static <K> GraphPlan<K> funded(GraphPlan<K> plan, Map<K, BigInteger> initial) {
        return GraphPlan.ownedRefill(plan, plan.steps(), plan.recipes(), initial, Map.of(), GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, 0);
    }
    private static void randomWords() {
        var random = new Random(19102093);
        for (int sample = 0; sample < 256; sample++) {
            var recipes = new ArrayList<GraphRecipe<String>>(); var steps = new ArrayList<PlanStep>();
            for (int i = 0; i < 6; i++) {
                var in = new LinkedHashMap<String, Long>(); var out = new LinkedHashMap<String, Long>();
                for (String k : List.of("A", "B", "C", "tool")) {
                    if (random.nextBoolean()) in.put(k, 1L + random.nextInt(3));
                    if (random.nextBoolean()) out.put(k, 1L + random.nextInt(3));
                }
                out.put("G", 1L); recipes.add(recipe("r" + i, in, out));
                steps.add(new PlanStep.Batch("r" + i, random.nextInt(4)));
            }
            var body = new PlanStep.Sequence(steps);
            var word = new PlanStep.Sequence(List.of(body, new PlanStep.Repeat(body, 2), new PlanStep.Repeat(body, 0)));
            var seeds = Map.of("A", 1L, "untouched", 2L, "G", (long) random.nextInt(32));
            if (sample % 2 == 0) Collections.reverse(recipes);
            var literal = RefillWordPruningTest.literalPlan(recipes, word, seeds, Map.of(), Set.of());
            var plan = funded(literal, literal.initialExact());
            compare(plan);
            // The independent literal oracle computes minimal starting stock
            // for this exact word, including delivery and untouched final seeds.
            for (var e : plan.initialExact().entrySet()) {
                var shortStock = new LinkedHashMap<>(plan.initialExact()); shortStock.put(e.getKey(), e.getValue().subtract(BigInteger.ONE));
                rejects(funded(plan, shortStock));
            }
        }
    }
    private static void separateObligations() {
        var recipes = List.of(recipe("open", Map.of("A", 1L), Map.of("B", 1L)),
                recipe("close", Map.of("B", 1L), Map.of("A", 1L, "G", 1L)));
        var word = new PlanStep.Sequence(List.of(new PlanStep.Batch("open", 1), new PlanStep.Batch("close", 1)));
        var literal = RefillWordPruningTest.literalPlan(recipes, word, Map.of("A", 1L, "untouched", 1L), Map.of(), Set.of());
        compare(funded(literal, literal.initialExact()));
        // Final net balance is sufficient, but the first step cannot execute.
        rejects(funded(literal, Map.of("untouched", BigInteger.ONE)));
        rejects(funded(literal, Map.of("A", BigInteger.ONE)));
        var empty = new GraphPlan<>("G", 1, true, new PlanStep.Sequence(List.of()), Map.<String, GraphRecipe<String>>of(),
                Map.of("G", 2L), Map.of("G", 1L), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        compare(empty); rejects(funded(empty, Map.of("G", BigInteger.ONE)));
        var b = MissingMaterialExpansionTest.budget(1000);
        try { PlanVerification.refill(literal, b); throw new AssertionError("Accepted an unfunded preview"); }
        catch (IllegalArgumentException expected) { checks++; }
        check(b.reservedBytes() == 0, "Rejected construction workspace");
    }
    private static void exactAndShared() {
        var r = recipe("r", Map.of("raw", 1L), Map.of("G", 1L));
        PlanStep huge = new PlanStep.Repeat(new PlanStep.Batch("r", Long.MAX_VALUE), Long.MAX_VALUE);
        var amount = BigInteger.valueOf(Long.MAX_VALUE).pow(2);
        var plan = new GraphPlan<>("G", 1, false, huge, Map.of("r", r), Map.of("raw", amount), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        compare(plan);
        var b = MissingMaterialExpansionTest.budget(1000);
        try (var work = PlanVerification.refill(plan, b)) {
            while (!work.step()) {}
            check(work.summary().delta("G").equals(amount) && b.nodes() < 100, "Clipped or expanded an exact huge repeat");
        }
        var shared = new PlanStep.Sequence(List.of(new PlanStep.Batch("r", 1)));
        var parts = new ArrayList<PlanStep>(); for (int i = 0; i < 512; i++) parts.add(shared);
        PlanStep deep = new PlanStep.Sequence(parts); for (int i = 0; i < 2048; i++) deep = new PlanStep.Sequence(List.of(deep));
        plan = new GraphPlan<>("G", 512, false, deep, Map.of("r", r), Map.of("raw", 512L), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        compare(plan);
    }
    private static void publicProduction() {
        var tool = new GraphRecipe<>("use", "physical", List.of(new GraphRecipe.Slot<>("raw", 1L),
                new GraphRecipe.Slot<>("tool", 1L, 0, true, true)), Map.of("tool", 1L, "G", 1L));
        var plan = new GraphPlan<>("G", 2, true, new PlanStep.Batch("use", 2), Map.of("use", tool),
                Map.of("raw", 2L, "tool", 1L), Map.of("tool", 1L), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        compare(plan);
        var b = MissingMaterialExpansionTest.budget(100_000);
        try (var full = new PlanVerification<>(plan, b)) {
            while (!full.step()) {}
            check(full.patternCounts().equals(Map.of("use", BigInteger.TWO)), "Public counts changed");
            check(full.physicalProduced("G").equals(BigInteger.TWO) && full.physicalProduced("tool").signum() == 0, "Returned token became physical production");
            try (var proof = new ForceCraftProof<>(plan, full, plan.seeds(), b)) {
                while (!proof.step()) {} check(proof.proved(), "Lost full forced-crafting proof");
            }
        }
        // Net gain below the order retains the ordinary gross-production path.
        var r = recipe("grow", Map.of("G", 1L, "raw", 1L), Map.of("G", 2L));
        plan = new GraphPlan<>("G", 2, false, new PlanStep.Batch("grow", 1), Map.of("grow", r),
                Map.of("G", 1L, "raw", 1L), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        try (var full = new PlanVerification<>(plan, b)) {
            while (!full.step()) {}
            check(full.summary().delta("G").equals(BigInteger.ONE) && full.physicalProduced("G").equals(BigInteger.TWO), "Lost startup production distinction");
            try (var proof = new ForceCraftProof<>(plan, full, Map.of(), b)) {
                while (!proof.step()) {} check(proof.proved(), "Indivisible startup proof changed");
            }
        }
        check(b.reservedBytes() == 0, "Production proof workspace");
    }
    private static <K> void compare(GraphPlan<K> plan) {
        var a = MissingMaterialExpansionTest.budget(1_000_000); var b = MissingMaterialExpansionTest.budget(1_000_000);
        try (var full = new PlanVerification<>(plan, a); var refill = PlanVerification.refill(plan, b)) {
            while (!full.step()) {} while (!refill.step()) {}
            check(full.summary().equals(refill.summary()), "Changed exact prefix/peak/delta summary");
            check(b.nodes() < a.nodes(), "Unused production work was retained");
            try { refill.patternCounts(); throw new AssertionError("Invented uncomputed counts"); }
            catch (IllegalStateException expected) { checks++; }
            try { refill.physicalProduced(plan.target()); throw new AssertionError("Invented uncomputed physical output"); }
            catch (IllegalStateException expected) { checks++; }
        }
        check(a.reservedBytes() == 0 && b.reservedBytes() == 0, "Verifier workspace");
    }
    private static void rejects(GraphPlan<String> plan) {
        for (boolean refill : List.of(false, true)) {
            var b = MissingMaterialExpansionTest.budget(100_000);
            try (var work = refill ? PlanVerification.refill(plan, b) : new PlanVerification<>(plan, b)) {
                try { while (!work.step()) {} throw new AssertionError("Accepted an unmet execution obligation"); }
                catch (IllegalArgumentException expected) { checks++; }
            }
            check(b.reservedBytes() == 0, "Rejected witness workspace");
        }
    }
    private static void limits() {
        var plan = VerificationContinuationTest.wide(128);
        for (String mode : List.of("work", "time", "memory", "cancel")) {
            var clock = new AtomicLong(); var cancel = new AtomicBoolean();
            var b = new PlanningBudget(1, 100_000, 4L << 20, cancel::get, clock::get); long held = 0;
            try (var work = PlanVerification.refill(plan, b)) {
                for (int i = 0; i < 3; i++) check(!work.step(), "Fixture completed before cutoff");
                switch (mode) {
                    case "work" -> b.charge(b.remainingWork());
                    case "time" -> clock.set(2_000_000);
                    case "memory" -> { held = b.availableBytes(); b.reserve(held); }
                    case "cancel" -> cancel.set(true);
                }
                try { while (!work.step()) {} check(mode.equals("memory"), "Ignored a work/time/cancel limit"); }
                catch (PlanningBudget.Exhausted expected) { check(!mode.equals("cancel"), "Cancellation became exhaustion"); }
                catch (CancellationException expected) { check(mode.equals("cancel"), "Unexpected cancellation"); }
                finally { b.release(held); }
            }
            check(b.reservedBytes() == 0, "Limit workspace");
        }
    }
    private static void scheduler(boolean cancel) throws Exception {
        var plan = VerificationContinuationTest.wide(1024); var cancelled = new AtomicBoolean();
        var b = new PlanningBudget(0, 100_000, 4L << 20, cancelled::get, System::nanoTime);
        try (var work = PlanVerification.refill(plan, b)) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            try (var scheduler = new PlanningScheduler(1, 8, 64, 1_000_000_000)) {
                var future = scheduler.submit(new PlanningScheduler.Work<Boolean>() {
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
                    public Boolean result() { return work.summary().delta(plan.target()).equals(BigInteger.ONE); }
                    public void close() { work.close(); }
                }, b);
                check(entered.await(5, TimeUnit.SECONDS), "Scheduler did not start");
                var marker = scheduler.submit(new PlanningScheduler.Work<Long>() {
                    long observed;
                    public boolean advance(PlanningScheduler.Slice slice) { observed = b.nodes(); if (cancel) cancelled.set(true); return true; }
                    public Long result() { return observed; }
                }, MissingMaterialExpansionTest.budget(1000));
                release.countDown(); check(marker.get(10, TimeUnit.SECONDS) < 100, "Verification monopolized worker");
                try { check(future.get(10, TimeUnit.SECONDS) && !cancel, "Scheduler result"); }
                catch (CancellationException e) { check(cancel, "Unexpected cancellation"); }
                catch (ExecutionException e) { check(cancel && e.getCause() instanceof CancellationException, "Unexpected failure " + e); }
            } finally { release.countDown(); }
        }
        check(b.reservedBytes() == 0, "Scheduler workspace");
    }
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
}
