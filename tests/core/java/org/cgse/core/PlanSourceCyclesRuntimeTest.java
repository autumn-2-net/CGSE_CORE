// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Physical execution of a source-cycle rewrite with a configuration-consuming DAG suffix. */
public final class PlanSourceCyclesRuntimeTest {
    private static int checks;
    private record Fixture(GraphPlan<String> plan, Map<String, Long> runs) {}
    private record Flight(int due, Map<String, Long> outputs) {}

    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream()
                .map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }

    private static Fixture fixture(int rotation) {
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
        recipes.put("split", recipe("split", Map.of("seed", 1L), Map.of("grain", 5L)));
        recipes.put("mix", recipe("mix", Map.of("grain", 1L, "ore", 1L), Map.of("blend", 1L)));
        recipes.put("recover", recipe("recover", Map.of("blend", 1L), Map.of("seed", 1L)));
        recipes.put("shape", new GraphRecipe<>("shape", "shape", List.of(
                new GraphRecipe.Slot<>("grain", 2), new GraphRecipe.Slot<>("stamp", 1, 1, true)),
                Map.of("blank", 1L, "residue", 1L)));
        recipes.put("polish", recipe("polish", Map.of("blank", 1L, "residue", 1L), Map.of("part", 1L)));
        recipes.put("finish", recipe("finish", Map.of("part", 1L), Map.of("goal", 1L)));
        var passes = new ArrayList<PlanStep>(); long total = 0;
        String[] cycle = {"split", "mix", "recover"};
        for (int pass = 0; pass < 10; pass++) {
            int count = 1 + pass % 4; total += count;
            var steps = new ArrayList<PlanStep>();
            for (int i = 0; i < 3; i++) steps.add(new PlanStep.Batch(cycle[(rotation + pass + i) % 3], count));
            steps.add(new PlanStep.Batch("shape", count));
            steps.add(new PlanStep.Batch("polish", count));
            steps.add(new PlanStep.Batch("finish", count));
            passes.add(new PlanStep.Repeat(new PlanStep.Sequence(steps), 1));
        }
        PlanStep original = new PlanStep.Sequence(passes);
        var seeds = Map.of("seed", 1L, "stamp", 2L);
        var budget = new PlanningBudget(0, 4_000_000, 32L << 20, () -> false, System::nanoTime);
        PlanStep changed = PlanSourceCycles.optimize(original, recipes, budget, "goal", total, seeds, true);
        check(budget.reservedBytes() == 0, "Source rewrite retained workspace");
        check(changed != original && changed instanceof PlanStep.Sequence, "Source rewrite was not exercised");
        var root = (PlanStep.Sequence) changed;
        check(root.children().size() == 2 && root.children().get(1) instanceof PlanStep.Sequence,
                "Expected source prefix and DAG suffix");
        var suffix = (PlanStep.Sequence) root.children().get(1);
        check(suffix.children().size() == 3 && suffix.children().stream().allMatch(PlanStep.Batch.class::isInstance),
                "DAG suffix was not compacted");
        var runs = new LinkedHashMap<String, Long>();
        for (String id : recipes.keySet()) runs.put(id, total);
        var exact = new LinkedHashMap<String, BigInteger>(); runs.forEach((id, n) -> exact.put(id, BigInteger.valueOf(n)));
        check(PlanCountComputation.of(original).equals(exact) && PlanCountComputation.of(changed).equals(exact),
                "Rewriting changed the independently counted recipe firings");
        var plan = new GraphPlan<>("goal", total, true, changed, recipes,
                PlanVerifier.initial(original, recipes, "goal", total, seeds), seeds, Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        PlanVerifier.verifyRuntimeInventory(plan);
        return new Fixture(plan, runs);
    }

    private static final class Host implements GraphJobRuntime.Adapter<String> {
        final Fixture fixture;
        final int delay;
        final boolean partial;
        final Map<String, Long> stock, accepted = new LinkedHashMap<>(), debited = new LinkedHashMap<>();
        final Map<String, Long> produced = new LinkedHashMap<>(), received = new LinkedHashMap<>(), refunded = new LinkedHashMap<>();
        final Map<String, Long> lateNetworkReturns = new LinkedHashMap<>();
        final List<Flight> flights = new ArrayList<>();
        final Map<String, Integer> busy = new HashMap<>();
        GraphJobRuntime<String> runtime;
        int tick, pushes, shapePushes;
        long delivered;

        Host(Fixture fixture, int delay, boolean partial) {
            this.fixture = fixture; this.delay = delay; this.partial = partial;
            stock = new LinkedHashMap<>(fixture.plan.initial());
            runtime = new GraphJobRuntime<>(fixture.plan, fixture.plan.initial(), Map.of());
        }
        public long capacity(GraphRecipe<String> recipe, long requested) {
            if (busy.getOrDefault(recipe.id(), -1) > tick) return 0;
            return partial ? Math.min(requested, 1 + Math.floorMod(tick + recipe.id().hashCode(), 3)) : requested;
        }
        public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
            // This fixture has one consumed configuration. Its physical charge
            // is one stamp per actual push, independently of its run count.
            var physical = new LinkedHashMap<String, Long>();
            recipe.inputs().forEach((key, n) -> physical.put(key,
                    key.equals("stamp") ? 1L : Math.multiplyExact(n, runs)));
            check(inputs.equals(physical), "Wrong physical configuration charge");
            physical.forEach((key, n) -> { take(key, n); debited.merge(key, n, Math::addExact); });
            long now = accepted.merge(recipe.id(), runs, Math::addExact);
            check(now <= fixture.runs.get(recipe.id()), "A firing was accepted twice");
            var outputs = new LinkedHashMap<String, Long>();
            recipe.executionOutputs().forEach((key, n) -> {
                long count = Math.multiplyExact(n, runs); outputs.put(key, count); produced.merge(key, count, Math::addExact);
            });
            int due = tick + delay + pushes % 2;
            flights.add(new Flight(due, outputs)); busy.put(recipe.id(), due);
            pushes++; if (recipe.id().equals("shape")) shapePushes++;
            return GraphJobRuntime.Outcome.ACCEPTED;
        }
        private void take(String key, long amount) {
            long left = Math.subtractExact(stock.getOrDefault(key, 0L), amount);
            check(left >= 0, "Spent unreceived material: " + key); stock.put(key, left);
        }
        public long deliver(String key, long amount) {
            check(key.equals("goal"), "Wrong delivery key"); take(key, amount);
            delivered = Math.addExact(delivered, amount); return amount;
        }
        public long refund(String key, long amount) {
            take(key, amount); refunded.merge(key, amount, Math::addExact); return amount;
        }
        void receive() {
            for (var iterator = flights.iterator(); iterator.hasNext();) {
                var flight = iterator.next(); if (flight.due > tick) continue;
                // Reversed key order also keeps a multi-output machine from
                // relying on its first output standing in for all returns.
                var outputs = new ArrayList<>(flight.outputs.entrySet()); Collections.reverse(outputs);
                for (var entry : outputs) {
                    received.merge(entry.getKey(), entry.getValue(), Math::addExact);
                    long accepted = runtime.accept(entry.getKey(), entry.getValue(), false);
                    boolean cancelled = runtime.state() == GraphJobRuntime.State.CANCELLING ||
                            runtime.state() == GraphJobRuntime.State.CANCELLED;
                    check(accepted == (cancelled ? 0 : entry.getValue()), "Wrong physical return owner: " + entry);
                    if (accepted > 0) stock.merge(entry.getKey(), accepted, Math::addExact);
                    // Cancellation deliberately releases output obligations:
                    // late machine returns enter the network, not this CPU.
                    if (accepted < entry.getValue()) lateNetworkReturns.merge(entry.getKey(), entry.getValue() - accepted, Math::addExact);
                }
                iterator.remove();
            }
        }
        void restore() {
            var saved = runtime.snapshot();
            check(!flights.isEmpty(), "Restore did not include unfinished physical work");
            var expected = new LinkedHashMap<String, Long>();
            for (var flight : flights) flight.outputs.forEach((key, n) -> expected.merge(key, n, Math::addExact));
            if (runtime.state() == GraphJobRuntime.State.CANCELLING) expected.clear();
            check(saved.expected().equals(expected), "Snapshot changed in-flight ownership");
            check(saved.owned().equals(nonzero(stock)), "Snapshot changed physically held inventory");
            var exact = new LinkedHashMap<String, BigInteger>(); accepted.forEach((id, n) -> exact.put(id, BigInteger.valueOf(n)));
            check(saved.acceptedRuns().equals(exact), "Snapshot lost accepted firing counts");
            runtime = new GraphJobRuntime<>(saved);
        }
    }

    private static void run(Fixture fixture, int delay, boolean partial, boolean restore, boolean cancel) {
        var host = new Host(fixture, delay, partial);
        boolean restored = false, cancelled = false; int pushesAtCancel = -1;
        for (host.tick = 0; host.tick < 2000 && (!host.runtime.finished() || !host.flights.isEmpty()); host.tick++) {
            host.receive(); host.runtime.tick(host, host.tick, 64, () -> 0L);
            if (host.shapePushes > 0 && !cancelled) {
                if (restore && !restored) { host.restore(); restored = true; }
                if (cancel) {
                    host.runtime.cancel(); cancelled = true; pushesAtCancel = host.pushes;
                    if (restore) host.restore(); // CANCELLING with the same outstanding physical work.
                }
            }
        }
        check(host.runtime.finished(), "Rewritten job stalled: " + host.runtime.reason());
        check(restored == restore && cancelled == cancel, "Lifecycle branch was not exercised");
        check(host.shapePushes > 0 && host.flights.isEmpty(), "Configuration suffix or final returns were skipped");
        check(host.produced.equals(host.received), "An accepted output was lost or received twice");
        check(nonzero(host.stock).isEmpty(), "CPU still owns unsettled physical material");
        var balance = new LinkedHashMap<>(fixture.plan.initial());
        host.debited.forEach((key, n) -> balance.merge(key, -n, Math::addExact));
        host.produced.forEach((key, n) -> balance.merge(key, n, Math::addExact));
        balance.merge("goal", -host.delivered, Math::addExact);
        host.refunded.forEach((key, n) -> balance.merge(key, -n, Math::addExact));
        host.lateNetworkReturns.forEach((key, n) -> balance.merge(key, -n, Math::addExact));
        check(nonzero(balance).isEmpty(), "Independent physical conservation failed");
        check(host.debited.get("stamp") == host.shapePushes, "Configuration was charged per run instead of per push");
        check(host.refunded.get("stamp") == fixture.plan.initial().get("stamp") - host.shapePushes,
                "Configuration refunds do not match this execution's actual pushes");
        if (cancel) {
            check(host.runtime.state() == GraphJobRuntime.State.CANCELLED && host.pushes == pushesAtCancel,
                    "Cancellation accepted more prefetched work");
            check(!host.lateNetworkReturns.isEmpty(), "Cancellation did not exercise late network returns");
        } else {
            check(host.lateNetworkReturns.isEmpty(), "Active job abandoned accepted outputs");
            check(host.runtime.state() == GraphJobRuntime.State.COMPLETED && host.delivered == fixture.plan.amount(),
                    "Incorrect final delivery");
            check(host.accepted.equals(fixture.runs), "Rewritten job changed total recipe counts");
            fixture.plan.seeds().forEach((key, n) -> check(host.refunded.getOrDefault(key, 0L) >= n, "Seed refund was lost"));
            check(host.refunded.get("seed").equals(fixture.plan.initial().get("seed")), "Source cycle lost its starting seed");
            if (partial) check(host.shapePushes > 1, "Partial provider acceptance was not exercised");
        }
    }

    private static Map<String, Long> nonzero(Map<String, Long> values) {
        var result = new LinkedHashMap<>(values); result.values().removeIf(n -> n == 0); return result;
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) {
        for (int rotation = 0; rotation < 3; rotation++) {
            var fixture = fixture(rotation);
            for (int delay : new int[]{1, 4}) for (boolean partial : List.of(false, true))
                for (boolean restore : List.of(false, true)) for (boolean cancel : List.of(false, true))
                    try { run(fixture, delay, partial, restore, cancel); }
                    catch (AssertionError failure) { throw new AssertionError("rotation=" + rotation + ", delay=" + delay +
                            ", partial=" + partial + ", restore=" + restore + ", cancel=" + cancel, failure); }
        }
        System.out.println("PlanSourceCyclesRuntimeTest PASS: 48 physical lifecycle cases, " + checks + " checks");
    }
}
