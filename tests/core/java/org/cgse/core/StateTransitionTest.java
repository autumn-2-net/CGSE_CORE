// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Physical states are candidates, not extra inventory or interchangeable keys. */
public final class StateTransitionTest {

    private static int checks;

    public static void main(String[] args) {
        contracts();
        closureBoundaries();
        interruptedCapture();
        deterministicExecution();
        cancellation();
        planner();
        System.out.println("StateTransitionTest: PASS assertions=" + checks);
    }

    private static void contracts() {
        var mutable = new LinkedHashMap<String, Long>();
        mutable.put("tool:1", 1L);
        var exact = StateReturn.exact("wear:one/v1", mutable);
        mutable.put("tool:2", 1L);
        check(exact.requireExact().equals(Map.of("tool:1", 1L)), "captured outcome is immutable");
        check(exact.requireSingleRemainder().equals("tool:1"), "exact single-item host bridge");
        check(exact.permits(Map.of("tool:1", 1L)), "exact returned state is admitted");
        check(!exact.permits(Map.of("tool:0", 1L)), "another physical state is not silently equivalent");
        check(StateReturn.<String>consumed("break/v1").requireSingleRemainder() == null, "break is a complete empty return");
        var random = StateReturn.alternatives("unbreaking:3/v1",
                List.of(Map.of("tool:0", 1L), Map.of("tool:1", 1L), Map.of()), true);
        check(random.complete() && !random.deterministic(), "complete finite stochastic outcomes remain nondeterministic");
        check(random.permits(Map.of()) && random.permits(Map.of("tool:0", 1L)), "including no physical return");
        check(!random.permits(Map.of("tool:0", 2L)), "return contracts preserve physical multiplicity");
        unsupported(random::requireExact);
        unsupported(StateReturn.<String>unknown("unknown/v1")::requireExact);
        unsupported(StateReturn.alternatives("one-observation/v1", List.of(Map.of("tool:1", 1L)), false)::requireExact);
        unsupported(StateReturn.exact("two-pieces/v1", Map.of("shard", 2L))::requireSingleRemainder);
        check(StateReturn.alternatives("same/v1", List.of(Map.of("tool", 1L), Map.of("tool", 1L)), true).deterministic(),
                "duplicate declarations do not invent stochastic behavior");
        expect(IllegalArgumentException.class, () -> StateReturn.exact("", Map.of()));
        expect(IllegalArgumentException.class, () -> StateReturn.exact("bad", Map.of("tool", -1L)));
        expect(IllegalArgumentException.class, () -> StateReturn.alternatives("empty", List.of(), true));
    }

    private static void closureBoundaries() {
        var budget = budget();
        try (var closure = new StateClosure<String>(budget, 32, state -> {
            if (state.equals("A")) return StateReturn.exact("cycle/v1", Map.of("B", 1L));
            if (state.equals("B")) return StateReturn.exact("cycle/v1", Map.of("A", 1L));
            return null;
        })) {
            closure.offer("A");
            closure.offer("not-an-input");
            drain(closure);
            check(closure.coverage().complete(), "same-key and cyclic successor discovery terminates");
            check(closure.coverage().resolved() == 2 && closure.coverage().rejected() == 1, "invalid outputs are not input variants");
            check(closure.captured().keySet().equals(java.util.Set.of("A", "B")), "reachable candidates only");
            closure.incomplete();
            check(!closure.coverage().complete() && !closure.coverage().seedsComplete(), "omitted producer domains remain explicit");
            check(closure.coverage().returnsComplete(), "seed coverage is distinct from return coverage");
        }
        check(budget.reservedBytes() == 0 && budget.searchWork() == 0 && budget.compilationWork() > 0,
                "closure releases its memory and charges compilation only");
        try (var closure = new StateClosure<Integer>(budget, 3, state -> StateReturn.exact("next/v1", Map.of(state + 1, 1L)))) {
            closure.offer(0);
            drain(closure);
            check(closure.coverage().stateLimit() && !closure.coverage().complete(), "state limit is coverage loss, never a proof");
            check(closure.coverage().discovered() == 3, "unbounded state spaces retain a finite footprint");
        }
        try (var closure = new StateClosure<String>(budget, 8, state -> state.equals("A") ?
                StateReturn.alternatives("coin/v1", List.of(Map.of("A", 1L), Map.of("B", 1L), Map.of()), true) :
                StateReturn.consumed("break/v1"))) {
            closure.offer("A");
            drain(closure);
            check(closure.coverage().complete() && !closure.coverage().deterministic(), "complete support is not an exact fixed-output model");
            unsupported(closure.captured().get("A")::requireExact);
        }
        try (var closure = new StateClosure<String>(budget, 8, state -> StateReturn.unknown("opaque/v1"))) {
            closure.offer("A");
            drain(closure);
            check(!closure.coverage().complete() && !closure.coverage().returnsComplete(), "unknown outcomes cannot establish complete capture");
        }
        check(budget.reservedBytes() == 0, "all bounded paths release memory");
    }

    private static void interruptedCapture() {
        AtomicInteger calls = new AtomicInteger();
        var memory = new PlanningBudget(0, 10_000, 350, () -> false, System::nanoTime);
        try (var closure = new StateClosure<String>(memory, 8, state -> {
            calls.incrementAndGet();
            return StateReturn.exact("immutable/v1", Map.of("B", 1L));
        })) {
            closure.offer("A");
            expect(PlanningBudget.Exhausted.class, closure::step);
            expect(PlanningBudget.Exhausted.class, closure::step);
            check(calls.get() == 1, "memory admission retries retain captured callback result");
            check(!closure.coverage().complete() && closure.coverage().pending() == 1, "failed capture admission cannot complete the domain");
        }
        check(memory.reservedBytes() == 0, "failed admission releases earlier allocations");
        var sharedMemory = new PlanningBudget(0, 10_000, 1000, () -> false, System::nanoTime);
        sharedMemory.reserve(400);
        calls.set(0);
        try (var closure = new StateClosure<String>(sharedMemory, 8, state -> {
            calls.incrementAndGet();
            return state.equals("A") ? StateReturn.exact("next/v1", Map.of("B", 1L)) : StateReturn.consumed("end/v1");
        })) {
            closure.offer("A");
            expect(PlanningBudget.Exhausted.class, () -> drain(closure));
            check(calls.get() == 1 && closure.coverage().discovered() == 1, "successor admission can fail after callback capture");
            sharedMemory.release(400);
            drain(closure);
            check(calls.get() == 2 && closure.captured().containsKey("B") && closure.coverage().complete(),
                    "memory retry keeps the not-yet-admitted successor instead of silently dropping it");
        }
        check(sharedMemory.reservedBytes() == 0, "shared memory retry releases only closure ownership");
        AtomicBoolean cancelled = new AtomicBoolean();
        var cancellation = new PlanningBudget(0, 10_000, cancelled::get);
        calls.set(0);
        try (var closure = new StateClosure<String>(cancellation, 8, state -> {
            calls.incrementAndGet();
            return StateReturn.consumed("break/v1");
        })) {
            closure.offer("A");
            cancelled.set(true);
            expect(CancellationException.class, closure::step);
            check(calls.get() == 0, "cancel is checked before host callback");
            cancelled.set(false);
            drain(closure);
            check(calls.get() == 1 && closure.coverage().complete(), "continuation retains its uncaptured input");
        }
        check(cancellation.reservedBytes() == 0, "cancelled closure releases memory");
        var work = new PlanningBudget(0, 2, () -> false);
        try (var closure = new StateClosure<String>(work, 8, state -> StateReturn.exact("next/v1", Map.of("B", 1L)))) {
            closure.offer("A");
            closure.step();
            expect(PlanningBudget.Exhausted.class, closure::step);
            check(work.searchWork() == 0 && !closure.coverage().complete(), "capture budget exhaustion is separate from count search");
        }
    }

    private static LinkedHashMap<String, GraphRecipe<String>> recipes(int count, boolean breakLast) {
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
        var budget = budget();
        try (var closure = new StateClosure<String>(budget, count + 2, state -> {
            if (!state.startsWith("tool:")) return null;
            int at = Integer.parseInt(state.substring(5));
            if (at >= count) return null;
            return breakLast && at == count - 1 ? StateReturn.consumed("wear:one/v1") :
                    StateReturn.exact("wear:one/v1", Map.of("tool:" + (at + 1), 1L));
        })) {
            closure.offer("tool:0");
            drain(closure);
            check(closure.coverage().complete(), "finite wear domain completely captured");
            closure.captured().forEach((key, contract) -> {
                Map<String, Long> outputs = new LinkedHashMap<>(contract.requireExact());
                outputs.put("P", 1L);
                var recipe = new GraphRecipe<>(key, "physical-pattern", List.of(new GraphRecipe.Slot<>(key, 1L, 0),
                        new GraphRecipe.Slot<>("R", 1L, 1)), outputs);
                recipes.put(key, recipe);
            });
        }
        return recipes;
    }

    private static GraphPlan<String> plan(int count, int tools, boolean breakLast) {
        var recipes = recipes(count, breakLast);
        var steps = new ArrayList<PlanStep>();
        recipes.keySet().forEach(id -> steps.add(new PlanStep.Batch(id, tools)));
        var plan = new GraphPlan<>("P", (long) count * tools, false, new PlanStep.Sequence(steps), recipes,
                Map.of("tool:0", (long) tools, "R", (long) count * tools), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        PlanVerifier.verify(plan);
        return plan;
    }

    private static void deterministicExecution() {
        for (int tools : List.of(1, 2)) for (boolean breakLast : List.of(false, true)) {
            var host = new Host(plan(100, tools, breakLast));
            for (int tick = 0; tick < 400 && !host.runtime.finished(); tick++) {
                host.runtime.tick(host, tick, 16, () -> 0L);
                host.runtime = new GraphJobRuntime<>(host.runtime.snapshot());
                if (!host.pending.isEmpty()) {
                    long pushes = host.pushes;
                    host.runtime.tick(host, tick, 16, () -> 0L);
                    check(host.pushes == pushes, "returned physical tool is required before its successor dispatch");
                    check(host.runtime.accept("tool:999", 1, false) == 0, "uncontracted state cannot satisfy exact output ownership");
                    host.returns();
                    host.runtime = new GraphJobRuntime<>(host.runtime.snapshot());
                }
            }
            check(host.runtime.state() == GraphJobRuntime.State.COMPLETED, "wear chain completes across every save/restore boundary");
            check(host.delivered == 100L * tools && host.runs == 100L * tools, "exact requested production and physical uses");
            check(host.refunded.equals(breakLast ? Map.of() : Map.of("tool:100", (long) tools)), "refund only actual final tool state");
        }
    }

    private static void cancellation() {
        var host = new Host(plan(100, 1, false));
        for (int tick = 0; tick < 10; tick++) {
            host.runtime.tick(host, tick, 1, () -> 0L);
            host.returns();
        }
        host.runtime.cancel();
        host.runtime = new GraphJobRuntime<>(host.runtime.snapshot());
        host.runtime.tick(host, 20, 16, () -> 0L);
        check(host.runtime.state() == GraphJobRuntime.State.CANCELLED, "cancellation restores across saved tool state");
        check(host.refunded.equals(Map.of("R", 90L, "P", 10L, "tool:10", 1L)), "cancellation does not resurrect the original tool");
        var flight = new Host(plan(3, 1, false));
        flight.runtime.tick(flight, 0, 1, () -> 0L);
        flight.runtime.cancel();
        flight.runtime.tick(flight, 1, 16, () -> 0L);
        check(flight.refunded.equals(Map.of("R", 2L)), "in-flight physical tool is not refunded speculatively");
        check(flight.runtime.accept("tool:1", 1, false) == 0, "late cancelled return is left to host inventory");
    }

    private static void planner() {
        var recipes = recipes(12, false);
        var plan = new GraphPlanner<>(new GraphCompiler<>(List.copyOf(recipes.values())))
                .plan("P", 12, Map.of("tool:0", 1L, "R", 12L), false, true, budget());
        check(plan.feasible() && plan.missing().isEmpty(), "ordinary planner consumes the captured successor chain");
        check(plan.initial().getOrDefault("tool:0", 0L) == 1 &&
                plan.initial().keySet().stream().filter(key -> key.startsWith("tool:")).count() == 1,
                "future candidates do not turn into initial stock");
    }

    private static final class Host implements GraphJobRuntime.Adapter<String> {
        GraphJobRuntime<String> runtime;
        final Map<String, Long> pending = new LinkedHashMap<>(), refunded = new LinkedHashMap<>();
        long delivered, pushes, runs;

        Host(GraphPlan<String> plan) { runtime = new GraphJobRuntime<>(plan, plan.initial(), Map.of()); }

        public long capacity(GraphRecipe<String> recipe, long requested) { return requested; }

        public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long count, Map<String, Long> inputs) {
            check(inputs.equals(recipe.dispatchInputs(count)), "dispatch retains exact tool identity and quantity");
            check(pending.isEmpty(), "physical tool cannot be in two batches simultaneously");
            pushes++;
            runs += count;
            recipe.executionOutputs().forEach((key, amount) -> pending.merge(key, amount * count, Long::sum));
            return GraphJobRuntime.Outcome.ACCEPTED;
        }

        void returns() {
            pending.forEach((key, amount) -> check(runtime.accept(key, amount, false) == amount, "accept actual precise physical returns"));
            pending.clear();
        }

        public long deliver(String key, long amount) { delivered += amount; return amount; }

        public long refund(String key, long amount) { refunded.merge(key, amount, Long::sum); return amount; }
    }

    private static PlanningBudget budget() { return new PlanningBudget(0, 40_000_000, () -> false); }

    private static void drain(StateClosure<?> closure) {
        for (int step = 0; step < 100_000; step++) if (closure.step()) return;
        throw new AssertionError("State capture did not terminate");
    }

    private static void unsupported(Runnable work) {
        try {
            work.run();
            throw new AssertionError("Expected unsupported semantics");
        } catch (PlanningFailure failure) {
            check(failure.result() == GraphPlan.Result.UNSUPPORTED_PATTERN_SEMANTICS,
                    "unsupported return contracts never become missing input or infeasible");
        }
    }

    private static void expect(Class<? extends Throwable> type, Runnable work) {
        try {
            work.run();
            throw new AssertionError("Expected " + type.getSimpleName());
        } catch (Throwable failure) {
            if (!type.isInstance(failure)) throw failure;
            checks++;
        }
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
