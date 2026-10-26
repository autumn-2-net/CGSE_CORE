// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;

/** Fast physical returns and tick jitter must not be mistaken for saturated growth. */
public final class FastGrowthSchedulingTest {
    private static final List<String> failures = new ArrayList<>();
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) failures.add(message);
    }

    private static void timingRecovery() {
        var feedback = new AdaptiveScheduling<String>();
        long tick = 0;
        feedback.tick(tick, true);
        feedback.watch("return", Set.of("seed"));
        int recovered = 0;
        for (int sample = 1; sample <= 33; sample++) {
            feedback.begin(sample, "return", 16, Map.of("seed", 32L));
            feedback.confirm(sample);
            int physicalDuration = sample == 1 ? 1 : 4;
            // AE returns can arrive before the CPU's next scheduling tick.
            for (int elapsed = 1; elapsed < physicalDuration; elapsed++) feedback.tick(++tick, true);
            feedback.returned(sample, "seed", 32);
            feedback.tick(++tick, true);
            if (sample > 17 && feedback.growing("return")) recovered++;
        }
        check(recovered == 16, "Stable fast returns never recovered after a one-tick startup sample: " + recovered);

        var synchronous = new AdaptiveScheduling<String>();
        synchronous.tick(0, true);
        synchronous.watch("return", Set.of("seed"));
        for (int sample = 1; sample <= 32; sample++) {
            synchronous.begin(sample, "return", sample, Map.of("seed", 2L * sample));
            synchronous.returned(sample, "seed", 2L * sample);
            synchronous.confirm(sample);
            check(synchronous.growing("return"), "Synchronous completion disabled growth");
        }
    }

    private static void providerRecovery() {
        var feedback = new AdaptiveScheduling<String>();
        feedback.tick(0, true);
        feedback.watch("return", Set.of("seed"));
        for (int probe = 0; probe < 100; probe++) {
            feedback.blocked("return");
            feedback.tick(0, true);
        }
        check(feedback.growing("return"), "Same-tick provider probes were mistaken for sustained saturation");
        for (int tick = 1; tick < 100; tick++) feedback.tick(tick, false);
        feedback.tick(1_000_000, true);
        feedback.blocked("return");
        check(feedback.growing("return"), "Paused time turned a transient provider refusal into saturation");
        boolean released = false;
        for (int tick = 1; tick <= 100; tick++) {
            feedback.tick(1_000_000 + tick, true);
            feedback.blocked("return");
            released |= !feedback.growing("return");
        }
        check(released, "Sustained provider refusal never released downstream growth reservation");

        feedback.begin(1, "return", 16, Map.of("seed", 32L));
        feedback.returned(1, "seed", 32);
        feedback.confirm(1);
        for (int tick = 101; tick <= 200; tick++) feedback.tick(1_000_000 + tick, true);
        feedback.blocked("return");
        check(feedback.growing("return"), "Successful provider recovery retained stale consecutive failures");

        feedback.enabled(false);
        feedback.enabled(true);
        feedback.watch("return", Set.of("seed"));
        feedback.tick(2_000_000, true);
        feedback.begin(2, "return", 16, Map.of("seed", 32L));
        feedback.returned(2, "seed", 32);
        feedback.reject(2);
        for (int tick = 1; tick <= 200; tick++) feedback.tick(2_000_000 + tick, true);
        check(feedback.growing("return"), "Rejected synchronous dispatch left a pending growth timer");
    }

    private enum Returns { SYNCHRONOUS, NEXT_TICK, JITTER, BUFFERED }
    private record Result(int ticks, long peak, int firstConsumer, long checks) {}

    private static GraphRecipe<String> recipe(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        return new GraphRecipe<>(id, id,
                inputs.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), outputs);
    }

    private static GraphPlan<String> plan(int amount) {
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
        recipes.put("grow", recipe("grow", Map.of("A", 1L, "raw", 1L), Map.of("B", 1L)));
        recipes.put("return", recipe("return", Map.of("B", 1L), Map.of("A", 2L)));
        recipes.put("consume", recipe("consume", Map.of("A", 1L), Map.of("P", 1L)));
        var body = new PlanStep.Sequence(List.of(new PlanStep.Batch("grow", 1),
                new PlanStep.Batch("return", 1), new PlanStep.Batch("consume", 1)));
        return new GraphPlan<>("P", amount, true, new PlanStep.Repeat(body, amount), recipes,
                Map.of("A", 1L, "raw", (long) amount), Map.of("A", 1L), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
    }

    private static Result run(Returns mode, boolean enabled) {
        int amount = mode == Returns.BUFFERED ? 1024 : 4096;
        GraphPlan<String> plan = plan(amount);
        class Host implements GraphJobRuntime.Adapter<String> {
            final GraphJobRuntime<String> runtime = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            final Map<String, Long> physical = new HashMap<>(plan.initial()), sent = new HashMap<>(), refunded = new HashMap<>();
            final Map<String, Integer> busyUntil = new HashMap<>(), dispatches = new HashMap<>();
            final TreeMap<Integer, List<Map<String, Long>>> returns = new TreeMap<>();
            long peak, delivered;
            int tick, firstConsumer = -1;

            public long capacity(GraphRecipe<String> recipe, long requested) { return requested; }

            public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
                for (var input : inputs.entrySet()) {
                    long have = physical.getOrDefault(input.getKey(), 0L);
                    if (have < input.getValue()) throw new AssertionError("Spent future material: " + input.getKey());
                    physical.put(input.getKey(), have - input.getValue());
                }
                sent.merge(recipe.id(), runs, Math::addExact);
                if (recipe.id().equals("grow")) peak = Math.max(peak, runs);
                if (recipe.id().equals("consume") && firstConsumer < 0) firstConsumer = tick;
                int sequence = dispatches.merge(recipe.id(), 1, Integer::sum);
                var output = new LinkedHashMap<String, Long>();
                recipe.outputs().forEach((key, count) -> output.put(key, Math.multiplyExact(count, runs)));
                if (mode == Returns.SYNCHRONOUS) receive(output);
                else {
                    int duration = mode == Returns.JITTER ? new int[]{1, 4, 2, 3}[sequence % 4] : 1;
                    int due = tick + duration;
                    if (mode == Returns.BUFFERED) due = Math.max(tick, busyUntil.getOrDefault(recipe.id(), tick))
                            + 2 * Math.toIntExact((runs + 6) / 7);
                    busyUntil.put(recipe.id(), due);
                    returns.computeIfAbsent(due, ignored -> new ArrayList<>()).add(output);
                }
                return GraphJobRuntime.Outcome.ACCEPTED;
            }

            void receive(Map<String, Long> output) {
                for (var entry : output.entrySet()) {
                    physical.merge(entry.getKey(), entry.getValue(), Math::addExact);
                    check(runtime.accept(entry.getKey(), entry.getValue(), false) == entry.getValue(), "Lost physical return");
                }
            }

            public long deliver(String key, long count) {
                check(key.equals("P"), "Unexpected delivery");
                delivered += count;
                physical.merge(key, -count, Math::addExact);
                return count;
            }

            public long refund(String key, long count) {
                refunded.merge(key, count, Math::addExact);
                physical.merge(key, -count, Math::addExact);
                return count;
            }
        }
        var host = new Host();
        host.runtime.intelligentScheduling(enabled);
        for (; host.tick < 50000 && !host.runtime.finished(); host.tick++) {
            var due = host.returns.remove(host.tick);
            if (due != null) due.forEach(host::receive);
            host.runtime.tick(host, host.tick, 32, () -> 0L);
        }
        check(host.runtime.finished(), "Unfinished " + mode + ": " + host.runtime.reason());
        check(host.delivered == amount, "Wrong target count: " + mode);
        check(host.refunded.getOrDefault("A", 0L) == 1, "Recovery seed lost: " + mode);
        check(host.sent.equals(Map.of("grow", (long) amount, "return", (long) amount, "consume", (long) amount)),
                "Recipe counts changed: " + mode);
        check(host.physical.values().stream().allMatch(n -> n == 0), "Physical ownership unsettled: " + mode);
        check(host.returns.isEmpty(), "Completed before pending returns: " + mode);
        return new Result(host.tick, host.peak, host.firstConsumer, host.runtime.checks());
    }

    public static void main(String[] args) {
        timingRecovery();
        providerRecovery();
        for (Returns mode : Returns.values()) {
            Result old = run(mode, false), smart = run(mode, true);
            System.out.println(mode + " plain=" + old + " intelligent=" + smart);
            if (mode == Returns.BUFFERED) {
                check(smart.firstConsumer >= 0 && smart.firstConsumer < smart.ticks / 2,
                        "Sustained buffered slowdown starved downstream");
                // Matched pre-change intelligent-scheduler baseline, measured
                // in deterministic simulated ticks, not machine wall time.
                check(smart.ticks <= 697, "Fast-machine repair regressed the established buffered-provider case: " + smart);
            } else {
                check(smart.peak >= 128, "Fast cycle remained confined to small batches: " + mode + " " + smart);
                check(smart.ticks <= old.ticks, "Intelligent scheduling slowed fast cycle: " + mode);
            }
        }
        if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
        System.out.println("FastGrowthSchedulingTest " + checks + " checks passed");
    }
}
