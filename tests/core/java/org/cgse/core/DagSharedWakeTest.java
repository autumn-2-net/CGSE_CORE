// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Distinct source bindings share real tools without quadratic return scans. */
public final class DagSharedWakeTest {
    private static int assertions;

    public static void main(String[] args) {
        for (int size : new int[]{8, 128, 1024, 8192}) boundedNotifications(size);
        for (int tools : new int[]{1, 4}) {
            physicalReturns(257, tools, false);
            physicalReturns(257, tools, true);
        }
        System.out.println("DagSharedWakeTest: " + assertions + " assertions passed");
    }

    private static void boundedNotifications(int size) {
        var plan = plan(size, 1, 1);
        PlanVerifier.verify(plan);
        PlanVerifier.verifyRuntimeInventory(plan);
        var dag = DagScheduler.create(plan, Map.of());
        check(dag != null, "Conserved tool must permit DAG dispatch");
        for (int i = 0; i < size; i++) {
            dag.resourceChanged("tool");
            dag.resourceChanged("raw");
        }
        check(dag.wakeupVisits() == size, "Queued consumers were visited again for another input");
        for (int i = 0; i < size; i++) {
            dag.resourceChanged("tool");
            var next = dag.poll(i);
            check(next != null && next.recipe().equals("r" + i) && next.runs() == 1, "Wake order changed");
            dag.accepted(1);
        }
        check(dag.finished() && dag.poll(size) == null, "Completed work left a queue entry");
        check(dag.wakeupVisits() == size, "Serial receipts rescanned queued or retired nodes");
        long words = dag.wakeupIndexWordVisits();
        for (int i = 0; i < size; i++) dag.resourceChanged("tool");
        check(dag.wakeupVisits() == size, "Retired consumer was visited");
        check(dag.wakeupIndexWordVisits() - words <= size, "Empty callbacks scanned sparse words");
        check(dag.wakeupIndexWordVisits() < 40L * size, "Index maintenance lost its bounded per-node cost");

        var accepted = new LinkedHashMap<String, BigInteger>();
        for (int i = 0; i < size - 1; i++) accepted.put("r" + i, BigInteger.ONE);
        dag = DagScheduler.create(plan, accepted);
        dag.resourceChanged("tool");
        check(dag.wakeupVisits() == 1, "Saved completed prefix was resubscribed");
        check(dag.poll(0).recipe().equals("r" + (size - 1)), "Saved remaining work disappeared");
        dag.accepted(1);
        check(dag.finished(), "Saved suffix did not finish");
    }

    private static void physicalReturns(int size, int tools, boolean synchronous) {
        var plan = plan(size, 3, tools);
        PlanVerifier.verifyRuntimeInventory(plan);
        var host = new Host(plan, tools, synchronous);
        boolean reloaded = false;
        for (long tick = 0; tick < 20L * size + 100 && !host.runtime.finished(); tick++) {
            host.runtime.tick(host, tick, 4, () -> 0L);
            host.checkOwnership();
            if (!reloaded && host.acceptedTotal >= size && (synchronous || !host.pending.isEmpty())) {
                host.runtime = new GraphJobRuntime<>(host.runtime.snapshot());
                check(host.runtime.snapshot().acceptedRuns().equals(host.accepted), "Reload changed the accepted prefix");
                host.checkOwnership();
                reloaded = true;
            }
            // Products and tools arrive separately; no predicted receipt can
            // fund another dispatch, including after the runtime is reloaded.
            host.releaseOne((tick & 1) == 0, false);
            host.checkOwnership();
            check(host.runtime.state() != GraphJobRuntime.State.NEEDS_ATTENTION, "Physical return lost its owner");
        }
        check(reloaded, "Save/load path was not reached");
        check(host.runtime.state() == GraphJobRuntime.State.COMPLETED, "Shared-tool job stalled");
        check(host.pending.isEmpty() && host.busy == 0, "Completion discarded an actual return");
        check(host.accepted.equals(plan.patternTimesExact()), "A source binding was repeated or skipped");
        check(host.delivered == 3L * size, "Product count changed");
        check(host.refunded.equals(Map.of("tool", (long) tools)), "Final tool ownership changed");
        check(host.rejected > 0, "Provider rejection path was not exercised");
        if (!synchronous) check(host.olderReturns > 0, "Older returns during a rejected push were not exercised");
    }

    private static GraphPlan<String> plan(int size, long runs, int tools) {
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
        var steps = new ArrayList<PlanStep>();
        for (int i = 0; i < size; i++) {
            String id = "r" + i;
            // The binding is intentionally different. Transition interning
            // cannot erase these physical source choices to hide the cost.
            recipes.put(id, new GraphRecipe<>(id, "binding-" + i,
                    List.of(new GraphRecipe.Slot<>("tool", 1), new GraphRecipe.Slot<>("raw", 1)),
                    Map.of("tool", 1L, "product", 1L)));
            steps.add(new PlanStep.Batch(id, runs));
        }
        return new GraphPlan<>("product", size * runs, false, new PlanStep.Sequence(steps), recipes,
                Map.of("tool", (long) tools, "raw", size * runs), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class Flight {
        long tool, product;
        Flight(long runs) { tool = runs; product = runs; }
    }

    private static final class Host implements GraphJobRuntime.Adapter<String> {
        GraphJobRuntime<String> runtime;
        final int tools;
        final boolean synchronous;
        final ArrayDeque<Flight> pending = new ArrayDeque<>();
        final Map<String, Integer> attempts = new LinkedHashMap<>();
        final Map<String, BigInteger> accepted = new LinkedHashMap<>();
        final Map<String, Long> refunded = new LinkedHashMap<>();
        long busy, delivered, acceptedTotal;
        int rejected, olderReturns;

        Host(GraphPlan<String> plan, int tools, boolean synchronous) {
            runtime = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            this.tools = tools;
            this.synchronous = synchronous;
        }

        public long capacity(GraphRecipe<String> recipe, long requested) { return Math.min(2, requested); }

        public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
            check(inputs.equals(Map.of("tool", runs, "raw", runs)), "Batch escrow changed");
            check(busy + runs <= tools, "Predicted return funded a second use of one tool");
            if (attempts.merge(recipe.id(), 1, Integer::sum) == 1 && Integer.parseInt(recipe.id().substring(1)) % 11 == 0) {
                if (!pending.isEmpty()) {
                    // This is the older flight's output, not acceptance of
                    // the rejected handoff currently holding inputs in escrow.
                    releaseOne(true, true);
                    olderReturns++;
                }
                rejected++;
                return GraphJobRuntime.Outcome.REJECTED;
            }
            busy += runs;
            acceptedTotal += runs;
            accepted.merge(recipe.id(), BigInteger.valueOf(runs), BigInteger::add);
            if (synchronous) {
                check(runtime.accept("product", runs, false) == runs, "Synchronous product was lost");
                check(runtime.accept("tool", runs, false) == runs, "Synchronous tool was lost");
                busy -= runs;
            } else pending.addLast(new Flight(runs));
            return GraphJobRuntime.Outcome.ACCEPTED;
        }

        void releaseOne(boolean toolFirst, boolean both) {
            if (pending.isEmpty()) return;
            Flight flight = pending.peekFirst();
            if (flight.tool > 0 && (toolFirst || both || flight.product == 0)) {
                check(runtime.accept("tool", flight.tool, false) == flight.tool, "Delayed tool was lost");
                busy -= flight.tool;
                flight.tool = 0;
            } else if (flight.product > 0) {
                check(runtime.accept("product", flight.product, false) == flight.product, "Delayed product was lost");
                flight.product = 0;
            }
            if (both && flight.product > 0) {
                check(runtime.accept("product", flight.product, false) == flight.product, "Older product was lost");
                flight.product = 0;
            }
            if (flight.tool == 0 && flight.product == 0) pending.removeFirst();
        }

        void checkOwnership() {
            check(runtime.held("tool") + busy + refunded.getOrDefault("tool", 0L) == tools,
                    "Physical tool ownership changed outside handoff");
        }

        public long deliver(String key, long amount) {
            check(key.equals("product"), "Unexpected target delivery");
            delivered += amount;
            return amount;
        }
        public long refund(String key, long amount) {
            refunded.merge(key, amount, Long::sum);
            return amount;
        }
    }
}
