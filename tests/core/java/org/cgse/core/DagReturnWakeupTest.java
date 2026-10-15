// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Actual state returns must not wait for an unrelated initial DAG scan. */
public final class DagReturnWakeupTest {
    private static int assertions;

    public static void main(String[] args) {
        exactStateChain(128);
        exactStateChain(1000);
        fairReadyAndRetries();
        repeatedWakeAndStaleRetry();
        synchronousWakeRetirement();
        synchronousPartialAcceptance();
        System.out.println("DagReturnWakeupTest: " + assertions + " assertions passed");
    }

    private static void exactStateChain(int size) {
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
        var steps = new ArrayList<PlanStep>();
        for (int i = 0; i < size; i++) {
            String id = "step" + i;
            recipes.put(id, new GraphRecipe<>(id, id,
                    List.of(new GraphRecipe.Slot<>("tool" + i, 1), new GraphRecipe.Slot<>("material", 1)),
                    Map.of("product", 1L, "tool" + (i + 1), 1L)));
            steps.add(new PlanStep.Batch(id, 1));
        }
        var plan = new GraphPlan<>("product", size, false, new PlanStep.Sequence(steps), recipes,
                Map.of("tool0", 1L, "material", (long) size), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        check(DagScheduler.create(plan, Map.of()) != null, "Exact tool states must use the DAG path");
        var host = new Host(plan);
        for (int i = 0; i < size; i++) {
            long before = host.runtime.checks();
            check(host.tick(4L * i, 1) == 1, "Actual return did not dispatch next state " + i + "/" + size);
            check(host.runtime.checks() - before <= 2, "Return wake-up rescanned the initial queue at state " + i);
            check(host.lastRecipe.equals("step" + i), "A tool state dispatched out of physical order");
            check(host.tick(4L * i + 1, 1) == 0, "A predicted tool return funded a dispatch");
            if (i == size / 2) {
                var saved = host.runtime.snapshot();
                host.runtime = new GraphJobRuntime<>(saved);
                check(host.runtime.committedRuns().size() == i + 1, "Reload lost accepted prefix");
                check(host.runtime.held("tool" + i) == 0, "Reload recreated a tool held by a provider");
            }
            host.returnOutputs();
        }
        for (int i = 0; i < 4 && !host.runtime.finished(); i++) host.tick(4L * size + i, 4);
        check(host.runtime.state() == GraphJobRuntime.State.COMPLETED, "Chain did not settle");
        check(host.pushes == size && host.delivered == size, "Accepted prefix was repeated or product was lost");
        check(host.refunded.equals(Map.of("tool" + size, 1L)), "Final physical tool ownership changed");
    }

    private static void fairReadyAndRetries() {
        var plan = independentPlan("hot", "ordinaryA", "ordinaryB");
        var dag = DagScheduler.create(plan, Map.of());
        check(dag != null, "Independent work is acyclic");
        check(dag.poll(0).recipe().equals("hot"), "Initial order changed without a receipt");
        dag.retry(5);
        // Repeated resource events may bypass a timer, but cannot starve either
        // ordinary ready node. No acceptance is invented by these wakeups.
        for (String ordinary : List.of("ordinaryA", "ordinaryB")) {
            dag.resourceChanged("input-hot");
            check(dag.poll(1).recipe().equals("hot"), "Receipt did not promote a retrying node");
            dag.retry(5);
            dag.resourceChanged("input-hot");
            check(dag.poll(1).recipe().equals(ordinary), "Return stream starved ordinary ready work");
            dag.retry(5);
        }
        // Due ordinary retries retain a normal-lane turn under the same stream.
        var seen = new ArrayList<String>();
        for (int i = 0; i < 6; i++) {
            dag.resourceChanged("input-hot");
            var step = dag.poll(5);
            check(step != null, "Due retry disappeared");
            seen.add(step.recipe());
            dag.retry(10);
        }
        check(seen.contains("ordinaryA") && seen.contains("ordinaryB"), "Wake traffic starved due retries");
    }

    private static void repeatedWakeAndStaleRetry() {
        var dag = DagScheduler.create(independentPlan("a", "b", "c"), Map.of());
        check(dag.poll(0).recipe().equals("a"), "First independent node");
        dag.retry(10);
        for (int i = 0; i < 1000; i++) dag.resourceChanged("input-a");
        check(dag.poll(1).recipe().equals("a"), "Repeated wake lost node");
        dag.accepted(1);
        for (String id : List.of("b", "c")) {
            var step = dag.poll(20);
            check(step != null && step.recipe().equals(id), "Stale retry or duplicate wake displaced ordinary work");
            dag.accepted(1);
        }
        check(dag.finished() && dag.poll(100) == null, "Completed node reappeared from stale wake/timer");

        dag = DagScheduler.create(independentPlan("a"), Map.of());
        dag.poll(0);
        dag.retry(10);
        dag.resourceChanged("input-a");
        dag.poll(1);
        dag.retry(20);
        check(dag.poll(10) == null, "An old retry generation bypassed the new deadline");
        check(dag.poll(20).recipe().equals("a"), "Current retry generation was discarded");
        dag.accepted(1);
        check(dag.finished(), "Retried node did not finish");
    }

    private static void synchronousWakeRetirement() {
        var dag = DagScheduler.create(independentPlan("a", "b"), Map.of());
        dag.poll(0);
        dag.resourceChanged("input-a"); // A receipt occurs inside the provider call.
        dag.accepted(1);
        check(dag.poll(0).recipe().equals("b"), "Synchronous wake retained a completed zero-run node");
        dag.resourceChanged("input-b");
        dag.retry(5); // Refusal must retain its delay even after a synchronous event.
        check(dag.poll(0) == null, "Prepared wake defeated provider retry delay");
        check(dag.poll(5).recipe().equals("b"), "Delayed synchronous wake lost its node");
        dag.accepted(1);
        check(dag.poll(10) == null && dag.finished(), "Synchronous wake duplicated a completed dispatch");

        var plan = independentPlan("a", "b");
        dag = DagScheduler.create(plan, Map.of("a", BigInteger.ONE));
        dag.resourceChanged("input-a");
        check(dag.poll(0).recipe().equals("b"), "Restored accepted node was awakened again");
        dag.accepted(1);
        check(dag.poll(10) == null && dag.finished(), "Restored accepted count changed");
    }

    private static void synchronousPartialAcceptance() {
        var source = independentPlan("a");
        var plan = new GraphPlan<>("product", 3, false, new PlanStep.Batch("a", 3), source.recipes(),
                Map.of("input-a", 3L), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        var dag = DagScheduler.create(plan, Map.of());
        for (long remaining = 3; remaining > 0; remaining--) {
            var step = dag.poll(0);
            check(step != null && step.runs() == remaining, "Synchronous partial acceptance lost its remaining count");
            dag.resourceChanged("input-a");
            dag.resourceChanged("input-a");
            dag.accepted(1);
        }
        check(dag.poll(0) == null && dag.finished(), "Synchronous partial acceptance left duplicate ready entries");
    }

    private static GraphPlan<String> independentPlan(String... names) {
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
        var steps = new ArrayList<PlanStep>();
        var stock = new LinkedHashMap<String, Long>();
        for (String id : names) {
            recipes.put(id, new GraphRecipe<>(id, id, List.of(new GraphRecipe.Slot<>("input-" + id, 1)), Map.of("product", 1L)));
            steps.add(new PlanStep.Batch(id, 1));
            stock.put("input-" + id, 1L);
        }
        return new GraphPlan<>("product", names.length, false, new PlanStep.Sequence(steps), recipes,
                stock, Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class Host implements GraphJobRuntime.Adapter<String> {
        GraphJobRuntime<String> runtime;
        Map<String, Long> outputs = Map.of();
        final Map<String, Long> refunded = new LinkedHashMap<>();
        String lastRecipe;
        int pushes;
        long delivered;

        Host(GraphPlan<String> plan) { runtime = new GraphJobRuntime<>(plan, plan.initial(), Map.of()); }
        int tick(long tick, int work) { return runtime.tick(this, tick, work, () -> 0L); }
        public long capacity(GraphRecipe<String> recipe, long requested) { return Math.min(1, requested); }
        public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
            check(outputs.isEmpty(), "One entity was simultaneously dispatched twice");
            check(runs == 1 && inputs.equals(recipe.inputs()), "Exact physical dispatch inputs changed");
            outputs = recipe.executionOutputs();
            lastRecipe = recipe.id();
            pushes++;
            return GraphJobRuntime.Outcome.ACCEPTED;
        }
        void returnOutputs() {
            outputs.forEach((key, count) -> check(runtime.accept(key, count, false) == count, "Lost actual return " + key));
            outputs = Map.of();
        }
        public long deliver(String key, long amount) {
            check(key.equals("product"), "Unexpected delivered resource");
            delivered += amount;
            return amount;
        }
        public long refund(String key, long amount) {
            refunded.merge(key, amount, Long::sum);
            return amount;
        }
    }
}
