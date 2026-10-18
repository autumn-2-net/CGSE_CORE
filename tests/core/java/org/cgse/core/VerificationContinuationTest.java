// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Count actual material-key visits as well as work, including uncharged helpers. */
public final class VerificationContinuationTest {
    private static int checks;
    static final class Key {
        static long visits;
        static int unusedStart = Integer.MAX_VALUE;
        static long unusedVisits;
        final int id;
        Key(int id) { this.id = id; }
        @Override public int hashCode() { visits++; if (id >= unusedStart) unusedVisits++; return id; }
        @Override public boolean equals(Object other) { visits++; if (id >= unusedStart) unusedVisits++; return other instanceof Key key && key.id == id; }
        @Override public String toString() { return "k" + id; }
    }
    static GraphPlan<Key> wide(int size) {
        var outputs = new LinkedHashMap<Key, Long>(); var seeds = new LinkedHashMap<Key, Long>();
        var initial = new LinkedHashMap<Key, Long>(); initial.put(new Key(-1), 1L);
        for (int i = 0; i < size; i++) {
            outputs.put(new Key(i), 1L); seeds.put(new Key(size + i), 1L);
            initial.put(new Key(size + i), 1L); initial.put(new Key(2 * size + i), 1L);
        }
        var recipe = new GraphRecipe<>("make", "make", List.of(new GraphRecipe.Slot<>(new Key(-1), 1)), outputs);
        return new GraphPlan<>(new Key(0), 1, true, new PlanStep.Sequence(List.of(new PlanStep.Batch("make", 1))),
                Map.of("make", recipe), initial, seeds, Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
    }
    static PlanningBudget budget() { return new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime); }
    private static Object read(Object owner, String name) throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static boolean step(PlanVerification<Key> verification, PlanningBudget budget) {
        Key.visits = 0; long before = budget.nodes(); boolean done = verification.step();
        check(Key.visits <= 512, "A verifier step scanned a whole material collection: " + Key.visits);
        check(budget.nodes() - before <= 34, "Verifier did not yield after its bounded ports/keys");
        return done;
    }
    public static void main(String[] args) throws Exception {
        wideKeys(); classification(); invalidAndSeparateGoals(); counts(); lifecycle();
        for (boolean prepared : List.of(false, true)) for (boolean cancel : List.of(false, true)) scheduler(prepared, cancel);
        System.out.println("VerificationContinuationTest: assertions=" + checks);
    }
    private static void wideKeys() {
        var plan = wide(8192); var b = budget();
        Key.unusedStart = 16384; Key.unusedVisits = 0;
        try (var work = new PlanVerification<>(plan, b)) {
            while (!step(work, b)) {}
            check(work.summary().required(new Key(-1)).equals(BigInteger.ONE), "Lost ordinary input");
            check(work.summary().delta().size() == 8193, "Lost wide outputs");
            for (int i = 0; i < 8192; i++) check(work.physicalProduced(new Key(i)).equals(BigInteger.ONE), "Lost physical output");
            check(work.patternCounts().equals(Map.of("make", BigInteger.ONE)), "Wrong pattern multiplicity");
            work.close(); work.close(); check(work.step(), "Completed verifier restarted after close");
        }
        check(Key.unusedVisits == 0, "Verifier rescanned irrelevant initial inventory"); Key.unusedStart = Integer.MAX_VALUE;
        check(b.reservedBytes() == 0, "Wide verification leaked");
    }
    private static void classification() {
        var random = new Random(812645);
        for (int sample = 0; sample < 512; sample++) {
            var slots = new ArrayList<GraphRecipe.Slot<String>>(); var outputs = new LinkedHashMap<String, Long>();
            for (int i = 0; i < 12; i++) {
                if (random.nextBoolean()) slots.add(new GraphRecipe.Slot<>("k" + random.nextInt(10), 1, i, random.nextBoolean()));
                if (random.nextBoolean()) outputs.put("k" + i, (long) random.nextInt(3));
            }
            outputs.put("product", 1L);
            var recipe = new GraphRecipe<>("r", "binding", slots, outputs);
            boolean overlaps = recipe.inputs().keySet().stream().anyMatch(recipe.outputs()::containsKey);
            check(recipe.hasInputReturns() == overlaps, "Wrong immutable return classification");
            outputs.clear(); slots.clear(); check(recipe.hasInputReturns() == overlaps, "Caller mutation changed classification");
        }
        var tool = new GraphRecipe<>("tool", "tool", List.of(new GraphRecipe.Slot<>("T", 1, 0, true, true)), Map.of("T", 1L, "G", 1L));
        check(tool.hasInputReturns() && !tool.executionOutputs().containsKey("T"), "Logical tool return was treated as ordinary consumption");
        var b = budget();
        try (var work = new SummaryComputation<>(new PlanStep.Sequence(List.of(new PlanStep.Batch("tool", 100))), Map.of("tool", tool), b)) {
            while (!work.step()) {}
            check(work.result().required("T").equals(BigInteger.ONE) && work.result().delta("G").equals(BigInteger.valueOf(100)), "Reused token multiplied per batch");
        }
    }
    private static <K> void reject(GraphPlan<K> plan, String key) {
        var b = budget();
        try (var verification = new PlanVerification<>(plan, b)) {
            try { while (!verification.step()) {} throw new AssertionError("Accepted unfunded plan"); }
            catch (IllegalArgumentException failure) { check(failure.getMessage().equals("Unfunded prefix: " + key), "Changed first invalid union key: " + failure); }
        }
        check(b.reservedBytes() == 0, "Rejected verification leaked");
    }
    private static void invalidAndSeparateGoals() {
        var plan = wide(257); var initial = new LinkedHashMap<>(plan.initialExact());
        initial.remove(new Key(513));
        reject(new GraphPlan<>(plan.target(), 1, true, plan.steps(), plan.recipes(), initial, plan.seeds(), Map.of(), plan.result(), 0, 0), "k513");
        initial.remove(new Key(-1));
        reject(new GraphPlan<>(plan.target(), 1, true, plan.steps(), plan.recipes(), initial, plan.seeds(), Map.of(), plan.result(), 0, 0), "k-1");
        // Neither the target nor the independent seed appears in this word.
        var empty = new PlanStep.Sequence(List.of()); var seeds = new LinkedHashMap<String, Long>(); seeds.put("G", 2L); seeds.put("S", 1L);
        reject(new GraphPlan<>("G", 1, true, empty, Map.of(), Map.of("G", 2L), seeds, Map.of(), GraphPlan.Result.FEASIBLE, 0, 0), "G");
        reject(new GraphPlan<>("G", 1, true, empty, Map.of(), Map.of("G", 3L), seeds, Map.of(), GraphPlan.Result.FEASIBLE, 0, 0), "S");
        var valid = new GraphPlan<>("G", 1, true, empty, Map.of(), Map.of("G", 3L, "S", 1L, "unused", 42L), seeds, Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        try (var work = new PlanVerification<>(valid, budget())) {
            while (!work.step()) {} check(work.patternCounts().isEmpty() && work.physicalProduced("G").signum() == 0, "Stock delivery invented production");
        }
        var invalid = new GraphPlan<>(plan.target(), 1, true, plan.steps(), plan.recipes(), plan.initialExact(), plan.seeds(), plan.initialExact(), plan.result(), 0, 0);
        Key.visits = 0;
        try { new PlanVerification<>(invalid, budget()); throw new AssertionError("Accepted nonempty missing quantities"); }
        catch (IllegalArgumentException expected) { check(Key.visits == 0, "Constructor copied a bounded missing view just to test emptiness"); }
    }
    private static void literalCounts(PlanStep step, Map<String, BigInteger> totals) {
        if (step instanceof PlanStep.Batch batch) {
            for (long i = 0; i < batch.runs(); i++) totals.merge(batch.recipe(), BigInteger.ONE, BigInteger::add);
        } else if (step instanceof PlanStep.Repeat repeat) {
            for (long i = 0; i < repeat.times(); i++) literalCounts(repeat.body(), totals);
        } else for (var child : ((PlanStep.Sequence) step).children()) literalCounts(child, totals);
    }
    private static void counts() throws Exception {
        var random = new Random(461257);
        for (int sample = 0; sample < 400; sample++) {
            var nodes = new ArrayList<PlanStep>();
            for (int i = 0; i < 5; i++) nodes.add(new PlanStep.Batch("r" + random.nextInt(4), random.nextInt(4)));
            for (int i = 0; i < 12; i++) {
                PlanStep a = nodes.get(random.nextInt(nodes.size())), b = nodes.get(random.nextInt(nodes.size()));
                nodes.add(random.nextBoolean() ? new PlanStep.Sequence(List.of(a, b, a)) : new PlanStep.Repeat(a, random.nextInt(4)));
            }
            var root = new PlanStep.Sequence(nodes); var expected = new LinkedHashMap<String, BigInteger>(); literalCounts(root, expected);
            var budget = budget(); var work = new PlanCountComputation(root);
            while (true) { long before = budget.nodes(); boolean done = work.step(budget); check(budget.nodes() - before <= 32, "Unbounded counting continuation"); if (done) break; }
            check(work.result().equals(expected), "Shared/zero-count word changed physical occurrences");
            check(new ArrayList<>(work.result().keySet()).equals(new ArrayList<>(expected.keySet())), "Changed first-occurrence recipe ordering");
            check(read(work, "order") == null && read(work, "weights") == null, "Completed counts retained whole temporary arrays");
            check(work.step(budget) && work.result().equals(expected), "Completed counting restarted");
        }
        var huge = new PlanStep.Repeat(new PlanStep.Repeat(new PlanStep.Batch("r", Long.MAX_VALUE), Long.MAX_VALUE), Long.MAX_VALUE);
        check(PlanCountComputation.of(huge).equals(Map.of("r", BigInteger.valueOf(Long.MAX_VALUE).pow(3))), "Count cleanup narrowed exact arithmetic");
    }
    private static void lifecycle() throws Exception {
        var plan = wide(257);
        for (int stop = 0; stop < 30; stop++) for (boolean timeout : List.of(false, true)) {
            var time = new AtomicLong(); var cancel = new AtomicBoolean();
            var b = new PlanningBudget(1, 1_000_000, 64L << 20, cancel::get, time::get);
            try (var work = new PlanVerification<>(plan, b)) {
                while ((int) read(work, "phase") == 0) check(!step(work, b), "Fixture skipped key phase");
                for (int i = 0; i < stop; i++) check(!step(work, b), "Fixture finished before interruption");
                if (timeout) time.set(2_000_000); else cancel.set(true);
                try { work.step(); throw new AssertionError("Ignored verifier interruption"); }
                catch (CancellationException expected) { check(!timeout, "Wrong cancellation"); }
                catch (PlanningBudget.Exhausted expected) { check(timeout && expected.limit() == PlanningBudget.Limit.TIMEOUT, "Wrong time limit"); }
                try { work.summary(); throw new AssertionError("Incomplete verifier exposed success"); }
                catch (IllegalStateException expected) { checks++; }
                work.close();
            }
            check(b.reservedBytes() == 0, "Interrupted key traversal leaked");
        }
    }
    private static void scheduler(boolean prepared, boolean cancel) throws Exception {
        var plan = wide(8192); var b = budget();
        try (var work = new PlanVerification<>(plan, b)) {
            if (prepared) {
                // Advance the real child to completion; no summary/state is injected.
                var summary = (SummaryComputation<?>) read(work, "computation"); while (!summary.step()) {}
            }
            var entered = new CountDownLatch(1); var released = new CountDownLatch(1);
            long before = b.nodes(); Key.visits = 0;
            try (var scheduler = new PlanningScheduler(1, 8, 64, 1_000_000_000)) {
                var future = scheduler.submit(new PlanningScheduler.Work<Boolean>() {
                    boolean started;
                    public boolean advance(PlanningScheduler.Slice slice) {
                        if (!started) {
                            started = true; entered.countDown();
                            try { if (!released.await(5, TimeUnit.SECONDS)) throw new AssertionError("Handoff timed out"); }
                            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                        }
                        while (slice.nextUncharged()) if (work.step()) return true;
                        return false;
                    }
                    public Boolean result() { return true; }
                    public void close() { work.close(); }
                }, b);
                check(entered.await(5, TimeUnit.SECONDS), "Verifier did not start");
                var marker = scheduler.submit(new PlanningScheduler.Work<long[]>() {
                    long[] result;
                    public boolean advance(PlanningScheduler.Slice slice) { result = new long[] {b.nodes() - before, Key.visits}; if (cancel) b.cancel(); return true; }
                    public long[] result() { return result; }
                }, budget());
                released.countDown(); var observed = marker.get(10, TimeUnit.SECONDS);
                check(observed[0] <= 97 && observed[1] <= 1024, "Verifier hid a wide scan before scheduler handoff: " + Arrays.toString(observed));
                try { check(future.get(10, TimeUnit.SECONDS) && !cancel, "Cancelled verifier returned success"); }
                catch (CancellationException expected) { check(cancel, "Unexpected cancellation"); }
                catch (ExecutionException failure) { check(cancel && failure.getCause() instanceof CancellationException, "Unexpected scheduler failure: " + failure); }
            } finally { released.countDown(); }
        }
        check(b.reservedBytes() == 0, "Scheduled verifier leaked");
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
