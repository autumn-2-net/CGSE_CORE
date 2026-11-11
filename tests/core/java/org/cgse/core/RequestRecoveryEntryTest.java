package org.cgse.core;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Independent budgets and the real scheduled FULL -> ACYCLIC handoff. */
public final class RequestRecoveryEntryTest {
    private static int checks;
    private static final long WORK = 1_000_000, BYTES = 32L << 20;

    public static void main(String[] args) throws Exception {
        budgetIdentity();
        for (int workers : new int[]{1, 4}) {
            for (String cause : List.of("work", "compilation", "time", "memory", "graph", "unknown"))
                transition(cause, workers);
            stopsOnResult(workers);
            ordinaryRecoveryDoesNotInferSeeds(workers);
        }
        failedRecovery();
        System.out.println("RequestRecoveryEntryTest: " + checks + " checks");
    }

    private static void budgetIdentity() {
        var clock = new AtomicLong();
        var old = new PlanningBudget(20, 100, 80, 0, 4096, () -> false, clock::get);
        old.start(); clock.set(20_000_000L); old.cancel();
        var next = old.independentSolve(() -> false);
        next.check();
        check(next.searchWork() == 1 && next.compilationWork() == 0, "Fresh counters");
        clock.addAndGet(19_000_000L); next.check();
        clock.addAndGet(1_000_000L);
        expectLimit(next::check, PlanningBudget.Limit.TIMEOUT);
        var work = old.independentSolve(() -> false);
        for (int i = 0; i < 100; i++) work.check();
        expectLimit(work::check, PlanningBudget.Limit.SEARCH_LIMIT);
        var compilation = old.independentSolve(() -> false);
        for (int i = 0; i < 80; i++) compilation.compilationCheck();
        expectLimit(compilation::compilationCheck, PlanningBudget.Limit.SEARCH_LIMIT);
        var memory = old.independentSolve(() -> false);
        memory.reserve(4096);
        expectLimit(() -> memory.reserve(1), PlanningBudget.Limit.MEMORY_LIMIT);
        memory.release(4096);
        try { old.independentSolve(() -> true).check(); throw new AssertionError("Player cancellation lost"); }
        catch (CancellationException expected) { checks++; }
    }

    private static void transition(String cause, int workers) throws Exception {
        var clock = new AtomicLong();
        var budget = new PlanningBudget(cause.equals("time") ? 20 : 0, WORK, BYTES, () -> false, clock::get);
        budget.enableMetrics();
        var request = request(chain(540), "k540", budget);
        if (cause.equals("work")) for (int i = 0; i < WORK; i++) budget.check();
        if (cause.equals("compilation")) for (int i = 0; i < WORK; i++) budget.compilationCheck();
        if (cause.equals("time")) { budget.start(); clock.set(20_000_000L); }
        if (cause.equals("memory")) {
            budget.reserve(BYTES);
            set(request, "current", new PlanningScheduler.Work<GraphPlan<String>>() {
                public boolean advance(PlanningScheduler.Slice slice) { budget.reserve(1); return false; }
                public GraphPlan<String> result() { throw new AssertionError(); }
                public GraphPlan<String> limited(PlanningBudget.Exhausted limit) { return failure("k540", GraphPlan.Result.MEMORY_LIMIT); }
                public void close() { budget.release(BYTES); }
            });
        }
        if (cause.equals("graph") || cause.equals("unknown"))
            set(request, "current", completed(failure("k540", cause.equals("graph") ? GraphPlan.Result.GRAPH_LIMIT : GraphPlan.Result.UNKNOWN)));
        try (var scheduler = new PlanningScheduler(workers, 4, 32, 1_000_000L)) {
            var plan = scheduler.submit(request, budget).get(45, TimeUnit.SECONDS);
            check(plan.feasible() && plan.patternTimes().size() == 540, cause + ": fresh solve failed");
            PlanVerifier.verify(plan);
            check(request.fallbackMode(), cause + ": no ACYCLIC result");
        }
        var second = (PlanningBudget) get(request, "fallbackBudget");
        check(second != null && second.searchWork() > 0 && second.compilationWork() > 0, cause + ": no second account");
        check(second.searchWork() <= WORK && second.compilationWork() <= WORK, cause + ": wrong caps");
        check(budget.reservedBytes() == 0 && second.reservedBytes() == 0, cause + ": leaked memory");
        check(budget.diagnostics().contains("mode=ACYCLIC"), "Missing progress diagnostic");
    }

    private static void stopsOnResult(int workers) throws Exception {
        var compiler = new GraphCompiler<>(chain(1));
        GraphPlan<String> supply;
        try (var planning = new WorkClose<>(new GraphPlanningWork<>(compiler, "k1", 1, Map.of(), false, true,
                new PlanningBudget(0, WORK, () -> false)))) {
            while (!planning.work.step()) {}
            supply = planning.work.result();
        }
        check(!supply.missingExact().isEmpty(), "Expected verified supply bill");
        PlanVerifier.verify(new GraphPlan<>(supply.target(), supply.amount(), supply.preserveSeeds(), supply.steps(),
                supply.recipes(), supply.initialExact(), supply.seeds(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0));
        for (var plan : List.of(supply, failure("k1", GraphPlan.Result.INFEASIBLE),
                failure("k1", GraphPlan.Result.UNSUPPORTED_PATTERN_SEMANTICS))) {
            var b = new PlanningBudget(0, WORK, () -> false);
            var r = request(chain(1), "k1", b);
            set(r, "current", completed(plan));
            try (var scheduler = new PlanningScheduler(workers, 4, 32, 1_000_000)) {
                try {
                    var actual = scheduler.submit(r, b).get(10, TimeUnit.SECONDS);
                    check(actual == supply, "Invalid negative became success");
                } catch (ExecutionException e) {
                    check(plan != supply && e.getCause() instanceof PlanningFailure, "Wrong final result: " + e.getCause());
                }
            }
            check(get(r, "fallbackBudget") == null, "Valid preview/proof/unsupported started second solve");
        }
    }

    private static void failedRecovery() throws Exception {
        var b = new PlanningBudget(0, 1, 1, () -> false, System::nanoTime);
        var r = request(chain(1), "k1", b);
        try (var scheduler = new PlanningScheduler(1, 4, 32, 1_000_000)) {
            try { scheduler.submit(r, b).get(10, TimeUnit.SECONDS); throw new AssertionError("Invented plan"); }
            catch (ExecutionException expected) {
                check(expected.getCause() instanceof PlanningBudget.Exhausted, "Lost original limit or null-work crash: " + expected.getCause());
            }
        }
        check(get(r, "fallbackBudget") != null, "Limit did not start recovery");
    }

    private static void ordinaryRecoveryDoesNotInferSeeds(int workers) throws Exception {
        var recipes = List.of(
                new GraphRecipe<>("grow", "grow", List.of(new GraphRecipe.Slot<>("A", 1)), Map.of("A", 2L)),
                new GraphRecipe<>("finish", "finish", List.of(new GraphRecipe.Slot<>("A", 1)), Map.of("T", 1L)));
        for (var policy : List.of(CatalystPolicy.MINIMAL, CatalystPolicy.STOCK, new CatalystPolicy(16, 8))) {
            for (boolean explicitReserve : new boolean[]{false, true}) {
                var budget = new PlanningBudget(0, WORK, BYTES, () -> false, System::nanoTime);
                var checkpoint = explicitReserve
                        ? new GraphJobRuntime.ReplanCheckpoint<>(1, "T", 1, Map.of("A", 1L), Map.<String, Long>of())
                        : null;
                var request = new RequestPlanningWork<>(new GraphCompiler<>(recipes), "T", 1, Map.of("A", 1L), Set.of(),
                        checkpoint, true, false, "REPORT_MISSING_ITEMS", policy, true, false, budget, () -> false,
                        ignored -> {});
                budget.charge(WORK);
                GraphPlan<String> plan;
                try (var scheduler = new PlanningScheduler(workers, 4, 32, 1_000_000)) {
                    plan = scheduler.submit(request, budget).get(15, TimeUnit.SECONDS);
                }
                check(request.fallbackMode(), "Exhausted FULL did not enter ordinary recovery");
                check(plan.patternTimes().equals(Map.of("finish", 1L)), "Recovery used a growth recipe");
                check(plan.seeds().equals(explicitReserve ? Map.of("A", 1L) : Map.of()),
                        "Recovery inferred a seed or lost an explicit final reserve");
                check(plan.missing().equals(explicitReserve ? Map.of("A", 1L) : Map.of()),
                        "Ordinary material requirements changed");
                check(plan.feasible() != explicitReserve, "Ordinary reserve bill became executable");
                PlanVerifier.verify(new GraphPlan<>(plan.target(), plan.amount(), plan.preserveSeeds(), plan.steps(),
                        plan.recipes(), plan.initialExact(), plan.seeds(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0));
                var second = (PlanningBudget) get(request, "fallbackBudget");
                check(!second.diagnostics().contains("catalysts"), "Recovery ran catalyst-policy trials");
                check(budget.reservedBytes() == 0 && second.reservedBytes() == 0, "Ordinary recovery leaked workspace");
            }
        }
    }

    private static RequestPlanningWork<String> request(List<GraphRecipe<String>> recipes, String target, PlanningBudget budget) {
        return new RequestPlanningWork<>(new GraphCompiler<>(recipes), target, 1, Map.of("k0", 1L), Set.of(), null,
                false, false, "REPORT_MISSING_ITEMS", CatalystPolicy.MINIMAL, true, false, budget, () -> false, ignored -> {});
    }
    private static List<GraphRecipe<String>> chain(int count) {
        var result = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < count; i++) result.add(new GraphRecipe<>("r" + i, "r" + i,
                List.of(new GraphRecipe.Slot<>("k" + i, 1)), Map.of("k" + (i + 1), 1L)));
        return result;
    }
    private static GraphPlan<String> failure(String target, GraphPlan.Result reason) {
        return new GraphPlan<>(target, 1, false, new PlanStep.Sequence(List.of()), Map.of(), Map.of(), Map.of(), Map.of(), reason, 0, 0);
    }
    private static PlanningScheduler.Work<GraphPlan<String>> completed(GraphPlan<String> plan) {
        return new PlanningScheduler.Work<>() {
            public boolean advance(PlanningScheduler.Slice slice) { return true; }
            public GraphPlan<String> result() { return plan; }
            public GraphPlan<String> limited(PlanningBudget.Exhausted limit) { return plan; }
        };
    }
    private record WorkClose<K>(GraphPlanningWork<K> work) implements AutoCloseable { public void close() { work.close(); } }
    private static void expectLimit(Runnable action, PlanningBudget.Limit expected) {
        try { action.run(); throw new AssertionError("Expected " + expected); }
        catch (PlanningBudget.Exhausted e) { check(e.limit() == expected, "Wrong limit " + e.limit()); }
    }
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static Object get(Object obj, String name) {
        try { Field f = obj.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(obj); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void set(Object obj, String name, Object value) {
        try { Field f = obj.getClass().getDeclaredField(name); f.setAccessible(true); f.set(obj, value); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
