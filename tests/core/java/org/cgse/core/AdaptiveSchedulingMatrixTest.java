// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;

/** Exact execution across packet limits, machine limits, return delays and capacity upgrades. */
public final class AdaptiveSchedulingMatrixTest {
    enum Mode { SYNC, PARALLEL, CAPPED, CHUNKED, BUFFERED, JITTER, UPGRADED }

    private static int cases;

    static Map<String, Long> amounts(Object... entries) {
        var result = new LinkedHashMap<String, Long>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], (Long) entries[i + 1]);
        return result;
    }

    static GraphRecipe<String> recipe(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        return new GraphRecipe<>(id, id, inputs.entrySet().stream()
                .map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), outputs);
    }

    static GraphPlan<String> plan(int amount) {
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
        recipes.put("grow", recipe("grow", amounts("A", 1L, "raw", 1L), amounts("B", 1L)));
        recipes.put("return", recipe("return", amounts("B", 1L), amounts("A", 2L)));
        recipes.put("consume", recipe("consume", amounts("A", 1L), amounts("P", 1L)));
        var body = new PlanStep.Sequence(List.of(new PlanStep.Batch("grow", 1),
                new PlanStep.Batch("return", 1), new PlanStep.Batch("consume", 1)));
        return new GraphPlan<>("P", amount, true, new PlanStep.Repeat(body, amount), recipes,
                amounts("A", 1L, "raw", (long) amount), amounts("A", 1L), Map.of(),
                GraphPlan.Result.FEASIBLE, 0, 0);
    }

    static void check(boolean pass, String error) { if (!pass) throw new AssertionError(error); }

    static void run(Mode mode, int amount, int cap, int delay, boolean enabled) {
        GraphPlan<String> plan = plan(amount);
        class Host implements GraphJobRuntime.Adapter<String> {
            final GraphJobRuntime<String> runtime = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            final Map<String, Long> physical = new LinkedHashMap<>(plan.initial()), sent = new LinkedHashMap<>(), refunded = new LinkedHashMap<>();
            final Map<String, Integer> busyUntil = new LinkedHashMap<>(), sequence = new LinkedHashMap<>();
            final TreeMap<Integer, List<Map<String, Long>>> returns = new TreeMap<>();
            int tick;
            long delivered, peak, upgradedPeak;

            public long capacity(GraphRecipe<String> recipe, long requested) {
                if (mode == Mode.CHUNKED) return Math.min(cap, requested);
                if (mode != Mode.CAPPED && mode != Mode.UPGRADED) return requested;
                if (busyUntil.getOrDefault(recipe.id(), 0) > tick) return 0;
                return mode == Mode.UPGRADED && tick >= 64 ? requested : Math.min(cap, requested);
            }

            public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
                for (var input : inputs.entrySet()) {
                    long available = physical.getOrDefault(input.getKey(), 0L);
                    check(available >= input.getValue(), "Unfunded physical prefix: " + input.getKey());
                    physical.put(input.getKey(), available - input.getValue());
                }
                sent.merge(recipe.id(), runs, Math::addExact);
                if (recipe.id().equals("grow")) {
                    peak = Math.max(peak, runs);
                    if (tick >= 64) upgradedPeak = Math.max(upgradedPeak, runs);
                }
                var output = new LinkedHashMap<String, Long>();
                recipe.outputs().forEach((key, count) -> output.put(key, Math.multiplyExact(count, runs)));
                int sample = sequence.merge(recipe.id(), 1, Integer::sum);
                if (mode == Mode.SYNC) receive(output);
                else {
                    int duration = mode == Mode.JITTER ? new int[] { 1, 4, 2, 3 }[(sample - 1) % 4] : delay;
                    int due = tick + duration;
                    if (mode == Mode.BUFFERED) due = Math.max(tick, busyUntil.getOrDefault(recipe.id(), tick))
                            + Math.multiplyExact(delay, Math.toIntExact((runs + cap - 1) / cap));
                    busyUntil.put(recipe.id(), due);
                    returns.computeIfAbsent(due, ignored -> new ArrayList<>()).add(output);
                }
                return GraphJobRuntime.Outcome.ACCEPTED;
            }

            void receive(Map<String, Long> output) {
                output.forEach((key, count) -> {
                    physical.merge(key, count, Math::addExact);
                    check(runtime.accept(key, count, false) == count, "Return not owned: " + key);
                });
            }

            public long deliver(String key, long count) {
                check(key.equals("P"), "Wrong delivered target");
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
        host.runtime.intelligentScheduling(enabled);
        for (; host.tick < 200000 && !host.runtime.finished(); host.tick++) {
            var due = host.returns.remove(host.tick);
            if (due != null) due.forEach(host::receive);
            host.runtime.tick(host, host.tick, 32, () -> 0L);
        }
        check(host.runtime.finished(), "Stuck " + mode + "/" + cap + "/" + amount + ": " + host.runtime.reason());
        check(host.delivered == amount, "Target count changed");
        check(host.sent.equals(amounts("grow", (long) amount, "return", (long) amount, "consume", (long) amount)), "Recipe count changed");
        check(host.refunded.equals(amounts("A", 1L)), "Final seed changed: " + host.refunded);
        check(host.physical.values().stream().allMatch(value -> value == 0) && host.returns.isEmpty(), "Physical ownership unsettled");
        // Broad deterministic guards, not a claim of globally optimal scheduling.
        // A packet-sized grant is not the machine's aggregate parallel capacity;
        // one huge accepted batch followed by a busy response is not a small cap.
        if (mode == Mode.PARALLEL || mode == Mode.CAPPED && cap == Integer.MAX_VALUE) {
            check(host.tick <= 64 * delay + 32, "Unlimited machine fell back to tiny batches: " + mode
                    + " amount=" + amount + " delay=" + delay + " ticks=" + host.tick);
            check(host.peak >= amount / 16, "Unlimited machine never grew a useful batch: " + host.peak);
        }
        if (mode == Mode.CHUNKED) {
            long dispatchFloor = (3L * ((amount + cap - 1L) / cap) + 31) / 32;
            check(host.tick <= 4 * dispatchFloor + 64L * delay + 64,
                    "Packet size was treated as an aggregate machine cap: amount=" + amount + " cap=" + cap);
        }
        if (mode == Mode.UPGRADED) {
            check(host.upgradedPeak >= 512 && host.tick < 256,
                    "A temporary provider cap prevented later growth: peak=" + host.upgradedPeak + " ticks=" + host.tick);
        }
        cases++;
    }

    public static void main(String[] args) {
        boolean enabled = true;
        for (int amount : new int[] { 256, 1024, 4096 }) {
            run(Mode.SYNC, amount, Integer.MAX_VALUE, 0, enabled);
            run(Mode.JITTER, amount, Integer.MAX_VALUE, 1, enabled);
            for (int delay : new int[] { 1, 2, 5 }) {
                run(Mode.PARALLEL, amount, Integer.MAX_VALUE, delay, enabled);
                run(Mode.CAPPED, amount, Integer.MAX_VALUE, delay, enabled);
                for (int cap : new int[] { 1, 3, 7, 32, 128 }) {
                    run(Mode.CAPPED, amount, cap, delay, enabled);
                    run(Mode.CHUNKED, amount, cap, delay, enabled);
                    run(Mode.BUFFERED, amount, cap, delay, enabled);
                }
            }
        }
        run(Mode.UPGRADED, 8192, 3, 2, true);
        System.out.println("AdaptiveSchedulingMatrixTest " + cases + " provider scenarios passed");
    }
}
