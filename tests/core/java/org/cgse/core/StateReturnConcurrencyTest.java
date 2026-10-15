// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Cross-checks aggregate alternative return capacity and transient subscriptions. */
public final class StateReturnConcurrencyTest {
    private static int assertions;

    public static void main(String[] args) {
        olderFlightsReserveActualHeadroom();
        sameBatchSlotsReserveAggregateHeadroom();
        transientAlternativeSubscriptions();
        System.out.println("StateReturnConcurrencyTest: " + assertions + " assertions passed");
    }

    private static void olderFlightsReserveActualHeadroom() {
        var recipe = new GraphRecipe<>("use", "use", List.of(new GraphRecipe.Slot<>("tool", 1, 0)), Map.of("product", 1L, "next", 1L));
        var plan = new GraphPlan<>("product", 2, false, new PlanStep.Batch("use", 2), Map.of("use", recipe),
                Map.of("tool", 2L, "alternative", Long.MAX_VALUE - 1), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        var runtime = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
        var adapter = new Adapter(runtime, List.of(choice(0, "next", Set.of("next", "alternative"), 1)));
        check(runtime.tick(adapter, 0, 2, () -> 0) == 1, "Two flights overbooked one actual return slot");
        check(adapter.pushes == 1 && runtime.owned().get("tool") == 1, "Headroom refusal spent a second physical tool");
        check(runtime.reason().equals("WAIT_RETURN_CAPACITY"), "Aggregate headroom refusal is not explicit");
        check(runtime.accept("alternative", 1, false) == 1, "First legal physical return was blocked");
        check(!runtime.pendingStateReturns() && runtime.stateReplanReady(), "One return left an impossible unresolved second owner");
        runtime = new GraphJobRuntime<>(runtime.snapshot());
        check(runtime.stateReplanReady() && runtime.owned().get("alternative") == Long.MAX_VALUE, "Save lost resolved ownership or replan barrier");
    }

    private static void sameBatchSlotsReserveAggregateHeadroom() {
        var recipe = new GraphRecipe<>("pair", "pair", List.of(new GraphRecipe.Slot<>("a", 1, 0), new GraphRecipe.Slot<>("b", 1, 1)),
                Map.of("product", 1L, "next", 2L));
        var plan = new GraphPlan<>("product", 1, false, new PlanStep.Batch("pair", 1), Map.of("pair", recipe),
                Map.of("a", 1L, "b", 1L, "alternative", Long.MAX_VALUE - 1), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        var runtime = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
        var allowed = Set.of("next", "alternative");
        var adapter = new Adapter(runtime, List.of(choice(0, "next", allowed, 1), choice(1, "next", allowed, 1)));
        check(runtime.tick(adapter, 0, 1, () -> 0) == 0, "Equivalent slots ignored their aggregate actual return size");
        check(adapter.pushes == 0 && runtime.owned().equals(plan.initial()), "Rejected aggregate capacity transferred input");
        check(!runtime.pendingStateReturns(), "Capacity refusal retained nonexistent return tickets");
    }

    private static void transientAlternativeSubscriptions() {
        for (boolean synchronous : new boolean[]{false, true}) {
            var recipe = new GraphRecipe<>("use", "use", List.of(new GraphRecipe.Slot<>("tool", 1, 0)), Map.of("product", 1L, "next", 1L));
            var plan = new GraphPlan<>("product", 1, false, new PlanStep.Batch("use", 1), Map.of("use", recipe),
                    Map.of("tool", 1L), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
            var runtime = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            runtime.drainChangedKeys();
            var adapter = new Adapter(runtime, List.of(choice(0, "next", Set.of("next", "tool", "third-state"), 1)));
            adapter.synchronous = synchronous;
            adapter.outcome = synchronous ? GraphJobRuntime.Outcome.ACCEPTED : GraphJobRuntime.Outcome.REJECTED;
            runtime.tick(adapter, 0, 1, () -> 0);
            check(!runtime.receivableKeys().contains("third-state"), "Transient alternative remains requested after owner retirement");
            check(runtime.drainChangedKeys().contains("third-state"), "Host cannot remove a transient third-state subscription");
            if (!synchronous) check(runtime.owned().get("tool") == 1 && !runtime.pendingStateReturns(), "Clean rejection duplicated ownership");
        }
    }

    private static OutputObligations.ReturnChoice<String> choice(int slot, String predicted, Set<String> allowed, long amount) {
        return new OutputObligations.ReturnChoice<>(slot, predicted, allowed, amount);
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }

    private static final class Adapter implements GraphJobRuntime.Adapter<String> {
        final GraphJobRuntime<String> runtime;
        final List<OutputObligations.ReturnChoice<String>> choices;
        int pushes;
        boolean synchronous;
        GraphJobRuntime.Outcome outcome = GraphJobRuntime.Outcome.ACCEPTED;
        Adapter(GraphJobRuntime<String> runtime, List<OutputObligations.ReturnChoice<String>> choices) { this.runtime = runtime; this.choices = choices; }
        public long capacity(GraphRecipe<String> recipe, long requested) { return Math.min(1, requested); }
        public List<OutputObligations.ReturnChoice<String>> returnChoices(GraphRecipe<String> recipe, long runs) { return choices; }
        public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
            pushes++;
            if (synchronous) { runtime.accept("product", 1, false); runtime.accept("next", 1, false); }
            return outcome;
        }
        public long deliver(String key, long amount) { return amount; }
        public long refund(String key, long amount) { return amount; }
    }
}
