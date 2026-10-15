// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A completion marker cannot discard an outstanding physical return contract. */
public final class StateReturnPlaceholderTest {

    private static int assertions;

    public static void main(String[] args) {
        for (boolean reload : new boolean[]{false, true}) variableMarker(reload);
        ordinaryMarkerAfterToolReturn();
        System.out.println("StateReturnPlaceholderTest: " + assertions + " assertions passed");
    }

    private static void variableMarker(boolean reload) {
        var recipe = new GraphRecipe<>("use", "use", List.of(new GraphRecipe.Slot<>("tool", 1, 0)), Map.of("marker", 1L));
        var host = new Host(recipe, new OutputObligations.ReturnChoice<>(0, "marker", Set.of("marker", "tool"), 1));
        check(host.runtime.tick(host, 0, 1, () -> 0) == 1, "One physical tool dispatched");
        if (reload) host.reload();
        var saved = host.runtime.snapshot();
        check(!host.runtime.completePlaceholder(), "Marker cannot erase unresolved alternative return");
        check(saved.equals(host.runtime.snapshot()), "Rejected completion leaves all physical accounts unchanged");
        check(host.runtime.pendingStateReturns() && host.runtime.receivable("tool") == 1, "Tool return remains receivable");
        check(host.runtime.accept("tool", 1, false) == 1, "Actual surviving tool is accepted");
        check(host.runtime.needsStateReplan() && !host.runtime.completePlaceholder(), "Changed return still requires suffix replacement");
        if (reload) host.reload();
        host.runtime.cancel();
        host.runtime.tick(host, 1, 10, () -> 0);
        check(host.runtime.state() == GraphJobRuntime.State.CANCELLED, "Cancellation settles");
        check(host.refunded.equals(Map.of("tool", 1L)), "Cancellation refunds exactly the actual physical tool");
        check(host.delivered.isEmpty(), "No marker is fabricated or delivered");
    }

    private static void ordinaryMarkerAfterToolReturn() {
        var recipe = new GraphRecipe<>("use", "use", List.of(new GraphRecipe.Slot<>("tool", 1, 0)), Map.of("marker", 1L, "worn", 1L));
        var host = new Host(recipe, new OutputObligations.ReturnChoice<>(0, "worn", Set.of("worn", "tool"), 1));
        check(host.runtime.tick(host, 0, 1, () -> 0) == 1, "Ordinary marker batch dispatches");
        check(!host.runtime.completePlaceholder(), "Ordinary marker waits for tool receipt");
        host.runtime.accept("worn", 1, false);
        host.reload();
        check(!host.runtime.pendingStateReturns(), "Exact tool receipt resolves state contract");
        check(host.runtime.completePlaceholder(), "Resolved state contract permits existing marker completion");
        host.runtime.tick(host, 1, 10, () -> 0);
        check(host.runtime.state() == GraphJobRuntime.State.COMPLETED, "Marker task completes normally");
        check(host.refunded.equals(Map.of("worn", 1L)) && host.delivered.isEmpty(), "Exact returned tool refunded without a fake marker");
    }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    private static final class Host implements GraphJobRuntime.Adapter<String> {

        GraphJobRuntime<String> runtime;
        final OutputObligations.ReturnChoice<String> choice;
        final Map<String, Long> delivered = new LinkedHashMap<>(), refunded = new LinkedHashMap<>();

        Host(GraphRecipe<String> recipe, OutputObligations.ReturnChoice<String> choice) {
            this.choice = choice;
            var plan = new GraphPlan<>("marker", 1, false, new PlanStep.Batch(recipe.id(), 1), Map.of(recipe.id(), recipe),
                    Map.of("tool", 1L), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
            runtime = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
        }

        public long capacity(GraphRecipe<String> recipe, long requested) { return requested; }
        public List<OutputObligations.ReturnChoice<String>> returnChoices(GraphRecipe<String> recipe, long runs) { return List.of(choice); }
        public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) { return GraphJobRuntime.Outcome.ACCEPTED; }
        public long deliver(String key, long amount) { delivered.merge(key, amount, Long::sum); return amount; }
        public long refund(String key, long amount) { refunded.merge(key, amount, Long::sum); return amount; }
        void reload() { runtime = new GraphJobRuntime<>(runtime.snapshot()); }
    }
}
