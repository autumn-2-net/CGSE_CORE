// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.cgse.core.PlanningCostTrace.Origin.*;
import static org.cgse.core.PlanningCostTrace.Stage.*;

/** Cost attribution is an observer: exact charges, isolated workers and unchanged executable plans. */
public final class PlanningCostTraceTest {
    public static void main(String[] args) throws Exception {
        exclusiveCharges();
        terminalCharges();
        forkedCharges();
        graphEquivalence();
        migratedContinuation();
        fallbackAccounts();
        System.out.println("Pipeline costs: exact exclusive ticks, separate budgets, inherited forks, limits, worker migration, 12 graph pairs and fallback accounts passed");
    }

    private static PlanningBudget budget(boolean enabled) {
        var budget = new PlanningBudget(0, 5_000_000, 64L << 20, () -> false, System::nanoTime);
        if (enabled) budget.enableMetrics();
        return budget;
    }

    private static void exclusiveCharges() {
        var budget = budget(true);
        var foreign = budget(true);
        budget.operation(PlanningBudget.Operation.SCAN, 1); // Four unattributed ticks.
        try (var parent = budget.trace(SOURCE_GRAPH, SEARCH)) {
            budget.operation(PlanningBudget.Operation.INTEGER, 1);
            foreign.charge(1000); // Must not enter this request's thread-local scope.
            try (var child = budget.trace(VERIFY)) {
                budget.operation(PlanningBudget.Operation.SCAN, 1);
                budget.compilationScan();
                budget.enableMetrics(); // Enabling twice must not reset an active trace.
                try (var nested = budget.trace(INTEGER_COUNTS, RESTORE)) { budget.charge(2); }
                budget.operation(PlanningBudget.Operation.INTEGER, 1);
            }
            budget.charge(1);
        }
        var snapshot = budget.pipelineMetrics();
        eq(cost(snapshot, UNATTRIBUTED, OTHER).searchTicks(), 4, "unattributed fraction");
        eq(cost(snapshot, SOURCE_GRAPH, SEARCH).searchTicks(), 24, "exclusive parent");
        eq(cost(snapshot, SOURCE_GRAPH, VERIFY).searchTicks(), 12, "exclusive child");
        eq(cost(snapshot, SOURCE_GRAPH, VERIFY).compilationTicks(), 4, "separate compilation");
        eq(cost(snapshot, INTEGER_COUNTS, RESTORE).searchTicks(), 32, "nested restore");
        eq(sum(foreign.pipelineMetrics(), false), 16000, "foreign budget");
        check(budget.traceContext() == null && foreign.traceContext() == null, "Worker scope leaked");
        conserved(budget);
        var disabled = budget(false);
        try (var ignored = disabled.trace(SOURCE_GRAPH, SEARCH)) { disabled.check(); }
        check(disabled.pipelineMetrics().costs().isEmpty(), "Disabled observer retained a scope");
        try { snapshot.costs().clear(); throw new AssertionError("Mutable snapshot"); }
        catch (UnsupportedOperationException expected) {}
    }

    private static void terminalCharges() {
        for (boolean compilation : new boolean[] { false, true }) {
            var limited = new PlanningBudget(0, 1, () -> false);
            limited.enableMetrics();
            try (var scope = limited.trace(ALLOCATION, SEARCH)) {
                if (compilation) limited.compilationCharge(2); else limited.charge(2);
                throw new AssertionError("Limit not exercised");
            } catch (PlanningBudget.Exhausted expected) {}
            eq(sum(limited.pipelineMetrics(), compilation), 32, "exhausting charge must be counted");
            check(limited.traceContext() == null, "Limit leaked scope");
            var overflow = new PlanningBudget(0, Long.MAX_VALUE, () -> false);
            overflow.enableMetrics();
            try (var scope = overflow.trace(ALLOCATION, SEARCH)) {
                if (compilation) overflow.compilationCharge(Long.MAX_VALUE); else overflow.charge(Long.MAX_VALUE);
            } catch (PlanningBudget.Exhausted expected) {}
            eq(sum(overflow.pipelineMetrics(), compilation), Long.MAX_VALUE, "terminal saturation lost/wrapped");
            var boundary = new PlanningBudget(0, Long.MAX_VALUE, () -> false);
            boundary.enableMetrics();
            try (var scope = boundary.trace(ALLOCATION, SEARCH)) {
                if (compilation) { boundary.compilationCharge(Long.MAX_VALUE / 16); boundary.compilationCheck(); }
                else { boundary.charge(Long.MAX_VALUE / 16); boundary.check(); }
            } catch (PlanningBudget.Exhausted expected) {}
            eq(sum(boundary.pipelineMetrics(), compilation), Long.MAX_VALUE, "CAS saturation lost/wrapped");
        }
        var cancelled = budget(true);
        try (var scope = cancelled.trace(SOURCE_GRAPH, SEARCH)) {
            cancelled.check();
            cancelled.cancel();
            cancelled.check();
            throw new AssertionError("Cancellation not exercised");
        } catch (CancellationException expected) {}
        eq(sum(cancelled.pipelineMetrics(), false), 16, "Cancellation billed an unperformed step");
        check(cancelled.traceContext() == null, "Cancellation leaked scope");
    }

    private static void forkedCharges() throws Exception {
        for (int width : new int[] { 2, 4 }) {
            var budget = budget(true);
            class Work implements PlanningScheduler.Work<Boolean> {
                CompletableFuture<List<Boolean>> pending;
                public boolean advance(PlanningScheduler.Slice slice) {
                    if (pending != null) return pending.isDone();
                    try (var scope = budget.trace(INTEGER_COUNTS, SEARCH)) {
                        List<Supplier<Boolean>> partitions = new ArrayList<>();
                        for (int i = 0; i < width * 2; i++) partitions.add(() -> {
                            for (int n = 0; n < 1001; n++) budget.operation(PlanningBudget.Operation.SCAN, 1);
                            try (var child = budget.trace(RESTORE)) { budget.compilationCharge(7); }
                            return true;
                        });
                        pending = slice.forkStealing(PlanningBudget.Phase.SOLVE, partitions);
                    }
                    return false;
                }
                public CompletableFuture<?> waitingFor() { return pending; }
                public Boolean result() { return pending.join().stream().allMatch(Boolean::booleanValue); }
            }
            try (var scheduler = new PlanningScheduler(width, 2, 1, 1_000_000)) {
                check(scheduler.submit(new Work(), budget).get(30, TimeUnit.SECONDS), "Fork failed");
            }
            eq(cost(budget.pipelineMetrics(), INTEGER_COUNTS, SEARCH).searchTicks(), width * 2L * 1001 * 4, "fork labels/exclusivity");
            eq(cost(budget.pipelineMetrics(), INTEGER_COUNTS, RESTORE).compilationTicks(), width * 2L * 7 * 16, "fork child stage");
            conserved(budget);
        }
    }

    private record Result(Map<String, Long> counts, Map<String, Long> initial, long search, long compilation) {}

    private static void graphEquivalence() {
        for (int size : new int[] { 1, 8, 65 }) for (boolean cycle : new boolean[] { false, true })
            for (boolean force : new boolean[] { false, true })
                check(graph(size, cycle, force, false).equals(graph(size, cycle, force, true)), "Telemetry changed a graph result/work");
    }

    private static Result graph(int size, boolean cycle, boolean force, boolean enabled) {
        var recipes = chain(size);
        Map<String, Long> stock = new LinkedHashMap<>(Map.of("item0", 10L));
        if (cycle) {
            recipes.add(recipe("double", Map.of("item0", 1L), Map.of("item0", 2L)));
            stock.put("item0", 1L);
        }
        var budget = budget(enabled);
        GraphPlan<String> plan;
        var work = new GraphPlanningWork<>(new GraphCompiler<>(recipes), "item" + size, 10, stock, cycle, force, budget);
        try {
            while (!work.step()) {}
            plan = work.result();
        } finally { work.close(); }
        check(plan.feasible(), "Graph not feasible: " + budget.diagnostics());
        PlanVerifier.verify(plan);
        PlanVerifier.verifyRuntimeInventory(plan);
        if (enabled) {
            conserved(budget);
            check(cost(budget.pipelineMetrics(), SOURCE_GRAPH, ASSEMBLE).searchTicks() > 0, "Assembly unobserved");
            check(cost(budget.pipelineMetrics(), SOURCE_GRAPH, VERIFY).searchTicks() > 0, "Verification unobserved");
            check(budget.candidates().firstVerifiedSearchWork() > 0, "No first executable witness");
        } else check(budget.pipelineMetrics().costs().isEmpty(), "Disabled graph trace populated");
        eq(budget.reservedBytes(), 0, "Graph leaked memory");
        return new Result(plan.patternTimes(), plan.initial(), budget.searchWork(), budget.compilationWork());
    }

    private static void migratedContinuation() throws Exception {
        var budget = budget(true);
        var work = new GraphPlanningWork<>(new GraphCompiler<>(chain(12)), "item12", 10, Map.of("item0", 10L), false, true, budget);
        var workers = List.of(Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor());
        try {
            int i = 0;
            while (!workers.get(i++ % 2).submit(work::step).get(30, TimeUnit.SECONDS)) {}
            check(work.result().feasible(), "Migrating continuation failed");
            for (var worker : workers) check(worker.submit(() -> budget.traceContext() == null).get(), "Yield retained a worker scope");
        } finally { work.close(); workers.forEach(ExecutorService::shutdownNow); }
        conserved(budget);
        eq(budget.reservedBytes(), 0, "Migrated continuation leaked memory");
    }

    private static void fallbackAccounts() {
        for (boolean enabled : new boolean[] { false, true }) {
            var budget = budget(enabled);
            budget.charge(101);
            var compiler = new GraphCompiler<>(chain(3));
            var work = new RequestPlanningWork<>(compiler, "item3", 10, Map.of("item0", 10L), Set.of(), null,
                    false, false, "cost-test", CatalystPolicy.MINIMAL, true, false, budget, () -> false, report -> {});
            try {
                check(work.costs().fallback() == null, "Invented fallback attempt");
                check(work.limited(new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT)).feasible(), "Ordinary fallback failed");
                var costs = work.costs();
                eq(costs.primary().searchWork(), 101, "Fallback charged the primary account");
                check(costs.fallback().searchWork() > 0, "Separate fallback account missing");
                if (enabled) {
                    eq(PlanningBudget.units(sum(costs.fallbackPipeline(), false)), costs.fallback().searchWork(), "Fallback trace lost costs");
                    check(costs.fallbackPipeline().costs().keySet().stream().allMatch(k -> k.origin() == FALLBACK), "Fallback blamed a primary origin");
                } else check(costs.fallbackPipeline().costs().isEmpty(), "Disabled fallback collected timings");
            } finally { work.close(); }
        }
        var budget = budget(true);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var interrupted = new RequestPlanningWork<>(new GraphCompiler<>(chain(30)), "item30", 10, Map.of("item0", 10L), Set.of(), null,
                false, false, "cost-test", CatalystPolicy.MINIMAL, true, false, budget, () -> calls.incrementAndGet() > 20, report -> {});
        try {
            interrupted.limited(new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT));
            throw new AssertionError("Fallback cancellation not exercised");
        } catch (CancellationException expected) {
            var costs = interrupted.costs();
            check(costs.fallback() != null && !costs.fallbackPipeline().costs().isEmpty(), "Cancelled fallback lost its final cost report");
            eq(PlanningBudget.units(sum(costs.fallbackPipeline(), false)), costs.fallback().searchWork(), "Cancelled fallback lost charges");
            eq(costs.primary().searchWork(), 0, "Cancelled fallback contaminated primary budget");
        } finally { interrupted.close(); }
    }

    static void conserved(PlanningBudget budget) {
        eq(PlanningBudget.units(sum(budget.pipelineMetrics(), false)), budget.searchWork(), "Search attribution does not conserve charges");
        eq(PlanningBudget.units(sum(budget.pipelineMetrics(), true)), budget.compilationWork(), "Compilation attribution does not conserve charges");
    }

    private static long sum(PlanningCostTrace.Snapshot snapshot, boolean compilation) {
        return snapshot.costs().values().stream().mapToLong(c -> compilation ? c.compilationTicks() : c.searchTicks()).sum();
    }

    private static PlanningCostTrace.Cost cost(PlanningCostTrace.Snapshot snapshot, PlanningCostTrace.Origin origin, PlanningCostTrace.Stage stage) {
        return snapshot.costs().getOrDefault(new PlanningCostTrace.Context(origin, stage), new PlanningCostTrace.Cost(0, 0, 0));
    }

    private static List<GraphRecipe<String>> chain(int n) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < n; i++) recipes.add(recipe("r" + i, Map.of("item" + i, 1L), Map.of("item" + (i + 1), 1L)));
        return recipes;
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        return new GraphRecipe<>(id, id, inputs.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), outputs);
    }

    private static void eq(long actual, long expected, String label) { check(actual == expected, label + ": " + actual + " != " + expected); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
