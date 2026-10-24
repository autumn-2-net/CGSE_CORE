// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;
import java.math.BigInteger;

/** Physical delivery, timing, and throughput checks for a growing cycle competing with its consumer. */
public final class IntelligentSchedulingTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static GraphRecipe<String> recipe(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        return new GraphRecipe<>(id, id, inputs.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), outputs);
    }

    private static GraphPlan<String> plan(int amount, boolean flat, boolean byproduct) {
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
        recipes.put("grow", recipe("grow", Map.of("A", 1L, "raw", 1L), Map.of("B", 1L)));
        Map<String, Long> output = new LinkedHashMap<>();
        if (byproduct) output.put("decoration", 1L);
        output.put("A", 2L);
        recipes.put("return", recipe("return", Map.of("B", 1L), output));
        recipes.put("consume", recipe("consume", Map.of("A", 1L), Map.of("P", 1L)));
        var body = new PlanStep.Sequence(List.of(new PlanStep.Batch("grow", 1), new PlanStep.Batch("return", 1), new PlanStep.Batch("consume", 1)));
        PlanStep steps = flat ? new PlanStep.Sequence(Collections.nCopies(amount, body)) : new PlanStep.Repeat(body, amount);
        return new GraphPlan<>("P", amount, true, steps, recipes, Map.of("A", 1L, "raw", (long) amount),
                Map.of("A", 1L), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
    }

    private record Result(int ticks, long peak, int firstConsumer, long checks) {}

    private static Result run(boolean enabled, boolean flat, boolean byproduct, int cap, boolean buffered, boolean reload, boolean toggle) {
        int amount = 1024;
        GraphPlan<String> plan = plan(amount, flat, byproduct);
        class Host implements GraphJobRuntime.Adapter<String> {
            GraphJobRuntime<String> runtime = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            final Map<String, Long> physical = new HashMap<>(plan.initial()), sent = new HashMap<>(), refunded = new HashMap<>();
            final TreeMap<Integer, List<Map<String, Long>>> returns = new TreeMap<>();
            final Map<String, Integer> busyUntil = new HashMap<>();
            long delivered, peak;
            int tick, firstConsumer = -1;
            public long capacity(GraphRecipe<String> recipe, long requested) {
                if (!buffered && busyUntil.getOrDefault(recipe.id(), -1) > tick) return 0;
                return buffered ? requested : Math.min(cap, requested);
            }
            public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
                for (var entry : inputs.entrySet()) {
                    long available = physical.getOrDefault(entry.getKey(), 0L);
                    check(available >= entry.getValue(), "Spent an unreceived return");
                    physical.put(entry.getKey(), available - entry.getValue());
                }
                sent.merge(recipe.id(), runs, Math::addExact);
                if (recipe.id().equals("grow")) peak = Math.max(peak, runs);
                if (recipe.id().equals("consume") && firstConsumer < 0) firstConsumer = tick;
                int start = buffered ? Math.max(tick, busyUntil.getOrDefault(recipe.id(), tick)) : tick;
                int due = start + (buffered ? 2 * Math.toIntExact((runs + cap - 1) / cap) : 2);
                busyUntil.put(recipe.id(), due);
                Map<String, Long> output = new LinkedHashMap<>();
                recipe.outputs().forEach((key, count) -> output.put(key, Math.multiplyExact(count, runs)));
                returns.computeIfAbsent(due, ignored -> new ArrayList<>()).add(output);
                return GraphJobRuntime.Outcome.ACCEPTED;
            }
            public long deliver(String key, long count) {
                check(key.equals("P"), "Unexpected target");
                delivered += count; physical.merge(key, -count, Long::sum); return count;
            }
            public long refund(String key, long count) {
                refunded.merge(key, count, Math::addExact); physical.merge(key, -count, Long::sum); return count;
            }
            void receive() {
                List<Map<String, Long>> due = returns.remove(tick);
                if (due == null) return;
                // The main output can arrive before the growth material.
                for (var outputs : due) for (var entry : outputs.entrySet()) {
                    physical.merge(entry.getKey(), entry.getValue(), Math::addExact);
                    check(runtime.accept(entry.getKey(), entry.getValue(), false) == entry.getValue(), "Lost physical return");
                }
            }
        }
        Host host = new Host();
        host.runtime.intelligentScheduling(enabled);
        for (; host.tick < 20000 && !host.runtime.finished(); host.tick++) {
            host.receive();
            if (reload && host.tick == 13) {
                host.runtime = new GraphJobRuntime<>(host.runtime.snapshot());
                host.runtime.intelligentScheduling(enabled);
            }
            if (toggle && (host.tick == 9 || host.tick == 19)) host.runtime.intelligentScheduling(host.tick == 19);
            host.runtime.tick(host, host.tick, 32, () -> 0L);
        }
        check(host.runtime.finished(), "Stuck: " + host.runtime.reason());
        check(host.delivered == amount, "Incomplete target");
        check(host.refunded.getOrDefault("A", 0L) == 1, "Lost recovery seed");
        check(host.sent.equals(Map.of("grow", (long) amount, "return", (long) amount, "consume", (long) amount)), "Changed plan counts");
        check(host.physical.values().stream().allMatch(n -> n == 0), "Unsettled physical ownership");
        check(host.returns.isEmpty(), "Finished before physical returns");
        return new Result(host.tick, host.peak, host.firstConsumer, host.runtime.checks());
    }

    private static void timing() {
        AdaptiveScheduling<String> feedback = new AdaptiveScheduling<>();
        feedback.tick(0, true);
        feedback.watch("r", Set.of("seed"));
        feedback.begin(1, "r", 10, Map.of("main", 10L, "seed", 20L));
        feedback.confirm(1);
        for (int tick = 1; tick <= 10; tick++) feedback.tick(tick, true);
        feedback.returned(1, "main", 10);
        feedback.returned(1, "seed", 20);
        feedback.begin(2, "r", 20, Map.of("main", 20L, "seed", 40L));
        feedback.confirm(2);
        feedback.returned(2, "main", 20);
        for (int tick = 11; tick <= 29; tick++) feedback.tick(tick, true);
        check(feedback.growing("r"), "Relaxed before twice the learned return window");
        feedback.tick(30, false);
        feedback.tick(1_000_000, true);
        check(feedback.growing("r"), "Offline gap counted as execution time");
        feedback.tick(1_000_001, true);
        feedback.returned(2, "seed", 39);
        check(!feedback.growing("r"), "Incomplete critical byproduct did not relax growth");
        feedback.returned(2, "seed", 1);
        feedback.enabled(false);
        check(!feedback.growing("r"), "Disabled policy still gates work");
        feedback.enabled(true);
        feedback.watch("r", Set.of("seed"));
        check(feedback.growing("r"), "Re-enabled policy retained stale observation");
        feedback.begin(3, "r", 1, Map.of("seed", 2L));
        feedback.reject(3);
        for (int tick = 0; tick < 200; tick++) feedback.tick(2_000_000 + tick, true);
        check(feedback.growing("r"), "Rejected dispatch left a growth hold");
    }

    private static void windowProofs() {
        int proposals = 0;
        var plan = plan(1024, false, true);
        for (int seed = 1; seed <= 32; seed++) for (int repeat = 1; repeat <= 10; repeat++) {
            var feedback = new AdaptiveScheduling<String>();
            feedback.tick(1, true);
            var growth = new GrowthWindow<>(plan.recipes(), plan.patternTimesExact(), feedback);
            var batches = new ArrayList<PlanStep.Batch>();
            for (int i = 0; i < repeat; i++) for (String id : List.of("grow", "return", "consume")) batches.add(new PlanStep.Batch(id, 1));
            Map<String, Long> initial = Map.of("A", (long) seed, "raw", 1024L);
            var candidate = growth.regroup(batches, key -> initial.getOrDefault(key, 0L));
            if (candidate == null) continue;
            proposals++;
            PlanStep word = new PlanStep.Sequence(candidate.stream().map(b -> (PlanStep) b).toList());
            check(PlanCountComputation.of(word).equals(Map.of("grow", BigInteger.valueOf(repeat), "return", BigInteger.valueOf(repeat),
                    "consume", BigInteger.valueOf(repeat))), "Optional window changed recipe counts");
            SequenceSummary<String> proof = SequenceSummary.of(word, plan.recipes());
            for (String key : proof.keys()) {
                BigInteger stock = BigInteger.valueOf(initial.getOrDefault(key, 0L));
                check(proof.required(key).compareTo(stock) <= 0, "Independent prefix check rejected regrouping");
                check(stock.add(proof.peak().getOrDefault(key, BigInteger.ZERO)).compareTo(ExactAmounts.LONG_MAX) <= 0,
                        "Independent long-headroom check rejected regrouping");
            }
        }
        check(proposals > 100, "Window proof sampling did not exercise the optimization");
        // No double spending of the shared optimization allowance between lanes.
        var bounded = new AdaptiveScheduling<String>();
        bounded.tick(10, true);
        check(bounded.spend(4096) && !bounded.spend(1), "Unbounded optional tick work");
        bounded.tick(10, true);
        check(!bounded.spend(1), "Repeated host tick renewed optional work");
        bounded.tick(11, true);
        check(bounded.spend(4096), "Optional work did not resume on the next tick");
    }

    public static void main(String[] args) {
        timing();
        windowProofs();
        for (boolean flat : List.of(false, true)) for (boolean byproduct : List.of(false, true)) {
            Result old = run(false, flat, byproduct, Integer.MAX_VALUE, false, false, false);
            Result smart = run(true, flat, byproduct, Integer.MAX_VALUE, false, false, false);
            System.out.println("growth flat=" + flat + " byproduct=" + byproduct + " old=" + old + " smart=" + smart);
            check(smart.peak >= 128, "Growth still starved by downstream consumption");
            check(smart.ticks < old.ticks / 2, "Mixed loop did not accelerate");
            run(true, flat, byproduct, 7, false, true, true);
            Result buffered = run(true, flat, byproduct, 7, true, false, false);
            System.out.println("buffered flat=" + flat + " byproduct=" + byproduct + " smart=" + buffered);
            check(buffered.firstConsumer >= 0 && buffered.firstConsumer < buffered.ticks / 2, "Buffered machine starved downstream");
        }
        System.out.println("IntelligentSchedulingTest " + checks + " checks");
    }
}
