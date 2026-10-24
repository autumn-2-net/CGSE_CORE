// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;

/** Replacing a running suffix must retain the order's preservation policy. */
public final class ExecutionPreserveReplanTest {
    private static int checks;

    public static void main(String[] args) {
        for (boolean restored : List.of(false, true)) {
            rejectedDowngrade(restored);
            for (boolean original : List.of(false, true)) for (boolean replacement : List.of(false, true))
                if (!original || replacement) acceptedMode(original, replacement, restored);
        }
        System.out.println("ExecutionPreserveReplanTest PASS: " + checks + " checks");
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream()
                .map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }

    private static Fake started(boolean preserve, boolean restored) {
        var prefix = recipe("prefix", Map.of("R", 1L), Map.of("I", 1L));
        var grow = recipe("grow", Map.of("A", 1L, "ore", 1L), Map.of("A", 2L));
        var finish = recipe("old-finish", Map.of("A", 3L, "I", 1L), Map.of("T", 1L));
        // No explicit seed map: the original word leaves one real A token.
        // Replanning must preserve the policy even in this implicit case.
        var plan = new GraphPlan<>("T", 1, preserve, new PlanStep.Sequence(List.of(
                new PlanStep.Batch("prefix", 1), new PlanStep.Batch("old-finish", 1))),
                Map.of("prefix", prefix, "grow", grow, "old-finish", finish),
                Map.of("A", 4L, "R", 1L, "external", 2L), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        var fake = new Fake(new GraphJobRuntime<>(plan, Map.of("A", 4L, "R", 1L), Map.of("external", 2L)));
        eq(1, fake.runtime.tick(fake, 0, 1), "accepted prefix");
        eq(Map.of("A", 4L), fake.runtime.owned(), "prefix input ownership");
        eq(Map.of("I", 1L, "external", 2L), fake.runtime.expected(), "prefix and external forecasts");
        if (restored) fake.runtime = new GraphJobRuntime<>(fake.runtime.snapshot());
        return fake;
    }

    private static GraphPlan<String> replacement(boolean preserve) {
        var finish = recipe("new-finish", Map.of("A", preserve ? 3L : 4L, "I", 1L), Map.of("T", 1L));
        return new GraphPlan<>("T", 1, preserve, new PlanStep.Batch("new-finish", 1), Map.of("new-finish", finish),
                finish.inputs(), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
    }

    private static void rejectedDowngrade(boolean restored) {
        var fake = started(true, restored);
        var checkpoint = fake.runtime.beginReplan();
        fake.runtime.waitForReplanDependency(checkpoint.epoch(), "WAIT_FOR_VERIFIED_SUFFIX");
        var saved = fake.runtime.snapshot();
        var forecast = fake.runtime.forecastInventory();
        var pending = fake.runtime.pendingRuns();
        var pendingKeys = fake.runtime.pendingKeys();
        long pendingTarget = fake.runtime.pendingOutput("T"), version = fake.runtime.version();
        fake.runtime.drainChangedKeys();
        boolean rejected = false;
        try {
            fake.runtime.replaceSuffix(checkpoint.epoch(), replacement(false),
                    Map.of("bonus", 7L), Map.of("future", 5L));
        } catch (IllegalArgumentException expected) { rejected = true; }
        eq(true, rejected, "PRESERVE order accepted an ordinary replacement");
        eq(saved, fake.runtime.snapshot(), "rejection changed inventory, obligations, history or saved replan state");
        eq(version, fake.runtime.version(), "rejection changed runtime version");
        eq(forecast, fake.runtime.forecastInventory(), "rejection changed forecast");
        eq(pending, fake.runtime.pendingRuns(), "rejection changed pending runs");
        eq(pendingKeys, fake.runtime.pendingKeys(), "rejection changed pending keys");
        eq(pendingTarget, fake.runtime.pendingOutput("T"), "rejection changed pending output");
        eq(Set.of(), fake.runtime.drainChangedKeys(), "rejection emitted inventory changes");
        eq(true, fake.runtime.replanCurrent(checkpoint.epoch()), "rejection consumed the active replan epoch");
        if (restored) {
            fake.runtime = new GraphJobRuntime<>(saved);
            eq(true, fake.runtime.plan().preserveSeeds(), "restore lost preservation policy");
            eq(saved, fake.runtime.snapshot(), "restore changed rejected suffix state");
            checkpoint = fake.runtime.beginReplan();
        }
        eq(true, fake.runtime.replaceSuffix(checkpoint.epoch(), replacement(true), Map.of(), Map.of()),
                "rejected proposal poisoned a valid same-mode retry");
        fake.complete();
        eq(Map.of("A", 1L, "external", 2L), fake.refunded, "valid retry lost startup or accepted external ownership");
    }

    private static void acceptedMode(boolean original, boolean replacement, boolean restored) {
        var fake = started(original, restored);
        var checkpoint = fake.runtime.beginReplan();
        eq(true, fake.runtime.replaceSuffix(checkpoint.epoch(), replacement(replacement),
                Map.of("bonus", 7L), Map.of("future", 5L)), "legal mode replacement refused");
        eq(replacement, fake.runtime.plan().preserveSeeds(), "accepted mode changed");
        eq(false, fake.runtime.replanCurrent(checkpoint.epoch()), "accepted replacement retained active epoch");
        eq(Map.of("prefix", 1L), fake.runtime.committedRuns(), "accepted prefix authorization changed");
        eq(Map.of("I", 1L), fake.runtime.inFlight(), "accepted physical prefix output lost");
        eq(Map.of("external", 2L, "future", 5L), fake.runtime.externalWaiting(), "external owners changed");
        eq(Map.of("A", 4L, "bonus", 7L), fake.runtime.owned(), "extra held inventory changed");
        fake.runtime = new GraphJobRuntime<>(fake.runtime.snapshot());
        eq(replacement, fake.runtime.plan().preserveSeeds(), "accepted mode lost on restore");
        fake.complete();
        var refund = new HashMap<>(Map.of("external", 2L, "future", 5L, "bonus", 7L));
        if (replacement) refund.put("A", 1L);
        eq(refund, fake.refunded, "legal replacement did not conserve owned inventory");
    }

    private static final class Fake implements GraphJobRuntime.Adapter<String> {
        GraphJobRuntime<String> runtime;
        final Map<String, Long> delivered = new HashMap<>(), refunded = new HashMap<>(), pushed = new HashMap<>();
        Fake(GraphJobRuntime<String> runtime) { this.runtime = runtime; }
        public long capacity(GraphRecipe<String> recipe, long requested) { return requested; }
        public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
            pushed.merge(recipe.id(), runs, Long::sum);
            return GraphJobRuntime.Outcome.ACCEPTED;
        }
        public long deliver(String key, long amount) { delivered.merge(key, amount, Long::sum); return amount; }
        public long refund(String key, long amount) { refunded.merge(key, amount, Long::sum); return amount; }
        void complete() {
            for (int tick = 1; tick < 20 && !runtime.finished(); tick++) {
                runtime.tick(this, tick, 32);
                runtime.expected().forEach((key, amount) -> eq(amount, runtime.accept(key, amount, false), "physical receipt"));
            }
            eq(GraphJobRuntime.State.COMPLETED, runtime.state(), "replacement did not complete");
            eq(Map.of("T", 1L), delivered, "replacement delivery changed");
            eq(Map.of("prefix", 1L, "new-finish", 1L), pushed, "accepted prefix was replayed or suffix omitted");
            eq(pushed, runtime.committedRuns(), "committed physical history changed");
            eq(Map.of(), runtime.expected(), "completed job retained expectations");
            eq(Map.of(), runtime.owned(), "completed job retained owned inventory");
        }
    }

    private static void eq(Object expected, Object actual, String message) {
        checks++;
        if (!Objects.equals(expected, actual)) throw new AssertionError(message + ": " + expected + " != " + actual);
    }
}
