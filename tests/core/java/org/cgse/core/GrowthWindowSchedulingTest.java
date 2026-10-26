// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Moving windows and same-tick physical returns must not disable cycle growth. */
public final class GrowthWindowSchedulingTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        return new GraphRecipe<>(id, id, inputs.entrySet().stream()
                .map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), outputs);
    }

    private static GraphPlan<String> plan(int amount) {
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
        recipes.put("grow", recipe("grow", Map.of("A", 1L, "raw", 1L), Map.of("B", 1L)));
        recipes.put("return", recipe("return", Map.of("B", 1L), Map.of("A", 2L)));
        recipes.put("consume", recipe("consume", Map.of("A", 1L), Map.of("P", 1L)));
        PlanStep body = new PlanStep.Sequence(List.of(new PlanStep.Batch("grow", 1),
                new PlanStep.Batch("return", 1), new PlanStep.Batch("consume", 1)));
        return new GraphPlan<>("P", amount, true, new PlanStep.Repeat(body, amount), recipes,
                Map.of("A", 1L, "raw", (long) amount), Map.of("A", 1L), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
    }

    private static void movingIndex() {
        var plan = plan(8192);
        var recipes = new LinkedHashMap<>(plan.recipes());
        var counts = new LinkedHashMap<>(plan.patternTimesExact());
        for (int i = 0; i < 40; i++) {
            String id = "stage" + i;
            recipes.put(id, recipe(id, Map.of("raw" + i, 1L), Map.of("out" + i, 1L)));
            counts.put(id, BigInteger.ONE);
        }
        var feedback = new AdaptiveScheduling<String>();
        feedback.tick(0, true);
        var growth = new GrowthWindow<>(recipes, counts, feedback);
        var whole = List.of(new PlanStep.Batch("grow", 10), new PlanStep.Batch("return", 10),
                new PlanStep.Batch("consume", 10));
        var partial = List.of(new PlanStep.Batch("grow", 10), new PlanStep.Batch("consume", 10));
        growth.regroup(whole, key -> 100);
        check(growth.preferred("grow"), "Complete observed cycle was not indexed");
        growth.regroup(partial, key -> 100);
        check(growth.preferred("grow"), "One in-flight stage erased the observed cycle");
        check(growth.limit("consume", 100, partial, key -> 100) == 90,
                "Partial window lost its pending growth reservation");
        var done = List.of(new PlanStep.Batch("consume", 10));
        growth.regroup(done, key -> 100);
        check(growth.limit("consume", 100, done, key -> 100) == 100,
                "Completed growth left a reservation on downstream work");
        for (int i = 0; i < 40; i++) {
            feedback.tick(i + 1, true);
            growth.regroup(List.of(new PlanStep.Batch("stage" + i, 1)), key -> 100);
        }
        feedback.tick(41, true);
        growth.regroup(whole, key -> 100);
        check(growth.preferred("grow"), "Old acyclic stages exhausted the later cycle index");
        feedback.enabled(false);
        feedback.enabled(true);
        feedback.tick(42, true);
        growth.regroup(partial, key -> 100);
        check(growth.preferred("grow"), "Reset did not re-register retained cycle return keys");
    }

    private record Result(int ticks, long peak) {}

    private static void oversizedWindowCannotUseRetainedIndex() {
        var plan = plan(8192);
        var recipes = new LinkedHashMap<>(plan.recipes());
        var outputs = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 300; i++) outputs.put("wide" + i, 1L);
        recipes.put("wide", recipe("wide", Map.of("raw", 1L), outputs));
        var counts = new LinkedHashMap<>(plan.patternTimesExact());
        counts.put("wide", BigInteger.ONE);
        var feedback = new AdaptiveScheduling<String>();
        feedback.tick(0, true);
        var growth = new GrowthWindow<>(recipes, counts, feedback);
        var whole = List.of(new PlanStep.Batch("grow", 10), new PlanStep.Batch("return", 10),
                new PlanStep.Batch("consume", 10));
        growth.regroup(whole, key -> 100);
        var wide = new ArrayList<>(whole);
        wide.add(new PlanStep.Batch("wide", 1));
        check(growth.regroup(wide, key -> { throw new AssertionError("Unbounded optional inventory scan"); }) == null,
                "Retained cycle index admitted an oversized stock scan");
    }

    private static Result execute(boolean intelligent, int delay) {
        int amount = 8192;
        var plan = plan(amount);
        class Host implements GraphJobRuntime.Adapter<String> {
            final GraphJobRuntime<String> runtime = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            final Map<String, Long> physical = new HashMap<>(plan.initial());
            final Map<String, Long> sent = new HashMap<>(), refunded = new HashMap<>();
            final TreeMap<Integer, List<Map<String, Long>>> returns = new TreeMap<>();
            long delivered, peak;
            int tick;

            public long capacity(GraphRecipe<String> recipe, long requested) { return requested; }

            public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
                inputs.forEach((key, count) -> {
                    long available = physical.getOrDefault(key, 0L);
                    check(available >= count, "Spent an unreceived return");
                    physical.put(key, available - count);
                });
                sent.merge(recipe.id(), runs, Math::addExact);
                if (recipe.id().equals("grow")) peak = Math.max(peak, runs);
                Map<String, Long> output = new LinkedHashMap<>();
                recipe.outputs().forEach((key, count) -> output.put(key, Math.multiplyExact(count, runs)));
                if (delay == 0) receive(output);
                else returns.computeIfAbsent(tick + delay, ignored -> new ArrayList<>()).add(output);
                return GraphJobRuntime.Outcome.ACCEPTED;
            }

            void receive(Map<String, Long> output) {
                output.forEach((key, count) -> {
                    physical.merge(key, count, Math::addExact);
                    check(runtime.accept(key, count, false) == count, "Lost physical return");
                });
            }

            public long deliver(String key, long count) {
                check(key.equals("P"), "Wrong target");
                delivered = Math.addExact(delivered, count);
                physical.merge(key, -count, Math::addExact);
                return count;
            }

            public long refund(String key, long count) {
                refunded.merge(key, count, Math::addExact);
                physical.merge(key, -count, Math::addExact);
                return count;
            }
        }
        Host host = new Host();
        host.runtime.intelligentScheduling(intelligent);
        for (; host.tick < 1000 && !host.runtime.finished(); host.tick++) {
            var due = host.returns.remove(host.tick);
            if (due != null) due.forEach(host::receive);
            host.runtime.tick(host, host.tick, 256, () -> 0L);
        }
        check(host.runtime.finished(), "Execution stalled: " + host.runtime.reason());
        check(host.delivered == amount, "Target amount changed");
        check(host.sent.equals(Map.of("grow", (long) amount, "return", (long) amount, "consume", (long) amount)),
                "Regrouping changed exact recipe counts");
        check(host.refunded.equals(Map.of("A", 1L)), "Final seed changed");
        check(host.physical.values().stream().allMatch(n -> n == 0) && host.returns.isEmpty(),
                "Unsettled physical ownership");
        return new Result(host.tick, host.peak);
    }

    public static void main(String[] args) {
        movingIndex();
        oversizedWindowCannotUseRetainedIndex();
        Result disabled = execute(false, 0), immediate = execute(true, 0), nextTick = execute(true, 1);
        check(immediate.peak >= 128 && immediate.ticks < disabled.ticks / 2,
                "Same-tick completion reverted to tiny raw witness batches: " + immediate);
        check(nextTick.peak >= 2048 && nextTick.ticks < 50,
                "Moving live window prematurely diverted growth output: " + nextTick);
        System.out.println("GrowthWindowSchedulingTest disabled=" + disabled + " immediate=" + immediate + " nextTick=" + nextTick);
    }
}
