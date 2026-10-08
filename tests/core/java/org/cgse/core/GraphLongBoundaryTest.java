package org.cgse.core;

import org.cgse.core.*;

import java.math.BigInteger;
import java.util.*;

/** Local-only boundary checks, including a resumable task with more than long recipe work. */
final class GraphLongBoundaryTest {
    static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);

    static void run() {
        var one = recipe("one", Map.of("R", 1L), Map.of("P", 1L));
        var direct = plan(List.of(one), "P", Long.MAX_VALUE, Map.of("R", Long.MAX_VALUE));
        check(direct.feasible(), "Long maximum 1:1 plan");
        check(direct.initialExact().get("R").equals(MAX), "Exact raw input");
        var runtime = new GraphJobRuntime<>(direct, direct.initial(), Map.of());
        var adapter = new Immediate(runtime);
        runtime.tick(adapter, 0, 10);
        runtime.tick(adapter, 1, 10);
        check(runtime.state() == GraphJobRuntime.State.COMPLETED, "Long maximum actually settles");
        check(adapter.delivered.equals(MAX), "No target clipping");

        var wide = plan(List.of(recipe("wide", Map.of("R", 32L), Map.of("P", 1L))), "P", Long.MAX_VALUE, Map.of("R", Long.MAX_VALUE));
        check(wide.result() == GraphPlan.Result.MISSING_INPUT, "Insufficient stock is a missing plan, not numeric failure");
        check(wide.initialExact().get("R").equals(MAX.multiply(BigInteger.valueOf(32))), "Exact 32 * long input");
        check(wide.missingExact().get("R").equals(MAX.multiply(BigInteger.valueOf(31))), "Exact deficit");

        var expand = recipe("expand", Map.of("R", 1L), Map.of("I", 32L));
        var pack = recipe("pack", Map.of("I", 32L), Map.of("P", 1L));
        var middle = plan(List.of(expand, pack), "P", Long.MAX_VALUE, Map.of("R", Long.MAX_VALUE));
        check(middle.feasible(), "Intermediate total exceeds long");
        PlanVerifier.verify(middle);
        check(middle.initialExact().get("R").equals(MAX), "Intermediate multiplication did not saturate");
        execute(middle);

        var shrink = recipe("unit", Map.of("I", 1L), Map.of("P", 1L));
        var consume = recipe("consume", Map.of("P", 32L), Map.of("T", 1L));
        var count = plan(List.of(expand, shrink, consume), "T", Long.MAX_VALUE, Map.of("R", Long.MAX_VALUE));
        check(count.feasible(), "Recipe work exceeds long");
        check(count.patternTimesExact().get("unit").equals(MAX.multiply(BigInteger.valueOf(32))), "Exact recipe work");
        execute(count);

        var loop = plan(List.of(recipe("grow", Map.of("C", 4L), Map.of("C", 64L))), "C", Long.MAX_VALUE, Map.of("C", 4L));
        check(loop.feasible(), "Long target growth ring");
        check(loop.patternTimesExact().get("grow").equals(CheckedAmounts.ceilDiv(MAX, BigInteger.valueOf(60))), "Growth work rounded exactly");
        PlanVerifier.verify(loop);
        execute(loop);
        execute(plan(List.of(recipe("round", Map.of("R", 1L), Map.of("P", 2L))), "P", Long.MAX_VALUE,
                Map.of("R", Long.MAX_VALUE)));

        var restored = new GraphJobRuntime<>(nearFinish());
        adapter = new Immediate(restored);
        for (int tick = 0; tick < 12 && !restored.finished(); tick++) restored.tick(adapter, tick, 16);
        check(restored.state() == GraphJobRuntime.State.COMPLETED, "Wide accepted work survives reload and completes");
        check(adapter.delivered.equals(MAX), "Wide work delivery is exact");
        check(adapter.refunded.equals(BigInteger.ONE), "Seed returned once");
        check(restored.snapshot().acceptedRuns().get("a").equals(MAX.multiply(BigInteger.TWO)), "Accepted total is not clipped");
        System.out.println("Graph long boundaries: exact demands, counts, growth, execution and restore passed");
    }

    static void execute(GraphPlan<String> plan) {
        var runtime = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
        var adapter = new Immediate(runtime);
        for (int tick = 0; tick < 1000 && !runtime.finished(); tick++) {
            runtime.tick(adapter, tick, 16);
            runtime.owned().forEach((key, value) -> check(value <= Long.MAX_VALUE - adapter.runtime.waiting(key), "Physical headroom checked"));
        }
        check(runtime.state() == GraphJobRuntime.State.COMPLETED, "Large physical work completes: " + runtime.reason());
        check(adapter.delivered.equals(BigInteger.valueOf(plan.amount())), "Exact physical long delivery");
        check(runtime.snapshot().acceptedRuns().equals(plan.patternTimesExact()), "Exact accepted work above long");
    }

    static GraphJobRuntime.Snapshot<String> nearFinish() {
        return org.cgse.fixtures.LongBoundaryFixture.nearFinish();
    }

    private static GraphPlan<String> plan(List<GraphRecipe<String>> recipes, String target, long count, Map<String, Long> stock) {
        return new GraphPlanner<>(new GraphCompiler<>(recipes)).plan(target, count, stock, true, true, new PlanningBudget(5000, 10_000_000, () -> false));
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }

    private static final class Immediate implements GraphJobRuntime.Adapter<String> {
        final GraphJobRuntime<String> runtime;
        BigInteger delivered = BigInteger.ZERO, refunded = BigInteger.ZERO;
        Immediate(GraphJobRuntime<String> runtime) { this.runtime = runtime; }
        public long capacity(GraphRecipe<String> recipe, long requested) { return requested; }
        public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> input) {
            recipe.outputs().forEach((key, amount) -> {
                long count = Math.multiplyExact(amount, runs);
                check(runtime.accept(key, count, false) == count, "Physical batch fully accepted");
            });
            return GraphJobRuntime.Outcome.ACCEPTED;
        }
        public long deliver(String key, long amount) { delivered = delivered.add(BigInteger.valueOf(amount)); return amount; }
        public long refund(String key, long amount) { refunded = refunded.add(BigInteger.valueOf(amount)); return amount; }
    }
}
