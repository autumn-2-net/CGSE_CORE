package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Complete witnesses are rechecked on each stock/configuration/binding change. */
public final class PlanReuseTest {
    private static final GraphRecipe<String> GROW = recipe("grow", Map.of("seed", 1L, "fuel", 1L), Map.of("seed", 2L));
    private static final GraphRecipe<String> MAKE = recipe("make", Map.of("seed", 1L), Map.of("product", 1L));
    private static final GraphCompiler<String> COMPILER = new GraphCompiler<>(List.of(GROW, MAKE));
    private static final Map<String, Long> STOCK = Map.of("seed", 1L, "fuel", 8L);
    private static final GraphPlan<String> PLAN = plan(new PlanStep.Sequence(List.of(new PlanStep.Batch("grow", 8),
            new PlanStep.Batch("make", 8))), Map.of("grow", GROW, "make", MAKE), "product", 8, true, Map.of("seed", 1L));

    public static void main(String[] args) throws Exception {
        inventoryAndSemantics();
        recipeChanges();
        executionOrder();
        scopedRequest();
        cancellationAndMemory();
        concurrentRefreshes();
        System.out.println("PlanReuseTest: PASS");
    }

    private static void inventoryAndSemantics() {
        PlanVerifier.verify(PLAN);
        for (long fuel : new long[]{100, 9, 8}) {
            var budget = budget();
            budget.enableMetrics();
            var result = run(COMPILER, PLAN, "product", 8, Map.of("seed", 1L, "fuel", fuel, "unrelated", fuel),
                    Set.of(), Map.of(), true, true, budget);
            check(result.feasible() && accepted(budget), "still-funded stock decrease must reuse");
            check(result.steps() == PLAN.steps(), "retain immutable actual execution program");
            check(result.result() == GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL && result.seedOptimality() == null,
                    "old inventory optimality must not escape");
            check(budget.compilationWork() == 0, "successful refresh must not compile/search again");
            check(budget.searchWork() < 1000, "small witness validation should stay bounded");
            check(budget.candidates().firstVerifiedSearchWork() > 0 &&
                    budget.candidates().origins().get(PlanningBudget.CandidateOrigin.PLAN_REUSE).accepted() == 1,
                    "successful refresh belongs to end-to-end candidate metrics");
        }
        var missing = budget();
        run(COMPILER, PLAN, "product", 8, Map.of("fuel", 100L), Set.of(), Map.of(), true, true, missing);
        check(!accepted(missing) && missing.diagnostics().contains("reason=stock"), "lost startup seed must invalidate");
        var restored = budget();
        check(run(COMPILER, PLAN, "product", 8, STOCK, Set.of(), Map.of(), true, true, restored).feasible() && accepted(restored),
                "a failed refresh cannot poison later inventory");
        var external = budget();
        check(run(COMPILER, PLAN, "product", 8, Map.of("seed", 1L), Set.of("fuel"), Map.of(), true, true, external).feasible()
                && accepted(external), "explicit external supply can fund exact requirements");
        var seeds = budget();
        run(COMPILER, PLAN, "product", 8, Map.of("seed", 3L, "fuel", 8L), Set.of(), Map.of("seed", 2L), true, true, seeds);
        check(!accepted(seeds) && seeds.diagnostics().contains("reason=required_seeds"), "changed recovery seed floor");
        for (boolean preserve : new boolean[]{true, false}) {
            var b = budget();
            run(COMPILER, PLAN, "product", preserve ? 7 : 8, STOCK, Set.of(), Map.of(), preserve, true, b);
            check(!accepted(b) && b.diagnostics().contains("reason=request_semantics"), "quantity/preserve changes reject");
        }
        var empty = new GraphPlan<>("product", 8, false, new PlanStep.Sequence(List.of()), Map.of(), Map.of("product", 8L),
                Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        var force = budget();
        run(COMPILER, empty, "product", 8, Map.of("product", 8L, "seed", 1L, "fuel", 8L), Set.of(), Map.of(), false, true, force);
        check(!accepted(force) && force.diagnostics().contains("reason=force_craft_NON_PRODUCTIVE"), "stored target isn't forced production");
        var invalid = new GraphPlan<>("product", 8, true, PLAN.steps(), PLAN.recipes(), Map.of(), PLAN.seeds(), Map.of(),
                GraphPlan.Result.FEASIBLE, 0, 0);
        var malformed = budget();
        run(COMPILER, invalid, "product", 8, STOCK, Set.of(), Map.of(), true, true, malformed);
        check(!accepted(malformed) && malformed.diagnostics().contains("reason=invalid_witness"), "initial map isn't a prefix proof");
        var hugeRecipe = recipe("large", Map.of("fuel", Long.MAX_VALUE), Map.of("product", 1L));
        var huge = plan(new PlanStep.Batch("large", 2), Map.of("large", hugeRecipe), "product", 2, false, Map.of());
        check(huge.initialExact().get("fuel").compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0, "exact setup");
        var exact = budget();
        run(new GraphCompiler<>(List.of(hugeRecipe)), huge, "product", 2, Map.of("fuel", Long.MAX_VALUE), Set.of(), Map.of(), false, true, exact);
        check(!accepted(exact), "initial funding must never use saturated compatibility view");
    }

    private static void recipeChanges() {
        var mutations = List.of(
                new GraphRecipe<>("grow", "other-provider", GROW.slots(), GROW.outputs()),
                new GraphRecipe<>("grow", "grow", List.of(new GraphRecipe.Slot<>("seed", 1, 2),
                        new GraphRecipe.Slot<>("fuel", 1, 3)), GROW.outputs()),
                new GraphRecipe<>("grow", "grow", List.of(new GraphRecipe.Slot<>("seed", 1, -1, true),
                        new GraphRecipe.Slot<>("fuel", 1)), GROW.outputs()),
                new GraphRecipe<>("grow", "grow", List.of(new GraphRecipe.Slot<>("seed", 1, -1, true, true),
                        new GraphRecipe.Slot<>("fuel", 1)), GROW.outputs()),
                recipe("grow", GROW.inputs(), Map.of("seed", 3L)));
        for (var changed : mutations) {
            var b = budget();
            run(new GraphCompiler<>(List.of(changed, MAKE)), PLAN, "product", 8, STOCK, Set.of(), Map.of(), true, true, b);
            check(!accepted(b) && b.diagnostics().contains("reason=recipe_semantics"), "binding/slot/config/returns changed");
        }
        var removed = budget();
        run(new GraphCompiler<>(List.of(MAKE)), PLAN, "product", 8, STOCK, Set.of(), Map.of(), true, true, removed);
        check(!accepted(removed), "removed recipe cannot reuse");
        var added = budget();
        run(new GraphCompiler<>(List.of(GROW, MAKE, recipe("other", Map.of("else", 1L), Map.of("product", 9L)))),
                PLAN, "product", 8, STOCK, Set.of(), Map.of(), true, true, added);
        check(accepted(added), "new alternative changes optimality, not the validity of a complete witness");
    }

    private static void executionOrder() {
        var first = recipe("first", Map.of("ore", 1L), Map.of("middle", 1L));
        var second = recipe("second", Map.of("middle", 1L), Map.of("product", 1L));
        var recipes = Map.of("first", first, "second", second);
        var compiler = new GraphCompiler<>(List.of(first, second));
        var forward = plan(new PlanStep.Sequence(List.of(new PlanStep.Batch("first", 1), new PlanStep.Batch("second", 1))),
                recipes, "product", 1, false, Map.of());
        var reverse = plan(new PlanStep.Sequence(List.of(new PlanStep.Batch("second", 1), new PlanStep.Batch("first", 1))),
                recipes, "product", 1, false, Map.of());
        check(forward.patternTimesExact().equals(reverse.patternTimesExact()), "same count setup");
        var rejected = budget();
        check(run(compiler, reverse, "product", 1, Map.of("ore", 1L), Set.of(), Map.of(), false, true, rejected).feasible(),
                "reject order then find feasible order normally");
        check(!accepted(rejected), "same counts don't validate another execution prefix");
        var accepted = budget();
        run(compiler, forward, "product", 1, Map.of("ore", 1L), Set.of(), Map.of(), false, true, accepted);
        check(accepted(accepted), "independent executable order survives previous candidate rejection");
    }

    private static void scopedRequest() throws Exception {
        var initialBudget = budget();
        var initial = request(COMPILER, STOCK, "DEFAULT", CatalystPolicy.MINIMAL, initialBudget).reuseCandidate(PLAN);
        check(initial.reuseCandidate() == null, "incomplete request cannot export a handle");
        run(initial, initialBudget);
        var handle = initial.reuseCandidate();
        check(handle != null, "complete feasible request exports scoped immutable candidate");
        var nextBudget = budget();
        var next = request(COMPILER, STOCK, "DEFAULT", CatalystPolicy.MINIMAL, nextBudget).reuseCandidate(handle);
        check(run(next, nextBudget).feasible() && accepted(nextBudget), "same scoped request accepts");
        for (int change = 0; change < 2; change++) {
            var b = budget();
            var changed = request(COMPILER, STOCK, change == 0 ? "OTHER" : "DEFAULT",
                    change == 1 ? CatalystPolicy.STOCK : CatalystPolicy.MINIMAL, b).reuseCandidate(handle);
            run(changed, b);
            check(!accepted(b) && b.diagnostics().contains("reason=request_configuration"), "strategy/catalyst capture changed");
        }
        var noStock = budget();
        var failing = request(COMPILER, Map.of(), "DEFAULT", CatalystPolicy.MINIMAL, noStock).reuseCandidate(handle);
        check(!run(failing, noStock).feasible() && failing.reuseCandidate() == null, "failed request cannot export handle");
    }

    private static void cancellationAndMemory() {
        for (int at = 0; at < 180; at++) {
            var checks = new AtomicInteger();
            int cutoff = at;
            var b = new PlanningBudget(0, 100_000, 1L << 20, () -> checks.incrementAndGet() > cutoff, System::nanoTime);
            try {
                run(COMPILER, PLAN, "product", 8, STOCK, Set.of(), Map.of(), true, true, b);
            } catch (CancellationException expected) {
                check(!accepted(b), "cancelled verification must not publish candidate");
            }
            check(b.reservedBytes() == 0 && COMPILER.cacheMetrics().activeSearches() == 0, "cancellation releases all state");
        }
        for (long bytes : new long[]{256, 2048, 4096, 8192, 16384}) {
            var b = new PlanningBudget(0, 100_000, bytes, () -> false, System::nanoTime);
            try {
                run(COMPILER, PLAN, "product", 8, STOCK, Set.of(), Map.of(), true, true, b);
            } catch (PlanningBudget.Exhausted expected) {
                check(expected.limit() == PlanningBudget.Limit.MEMORY_LIMIT, "expected memory exit");
            }
            check(b.reservedBytes() == 0, "low-memory validation must not leak");
        }
    }

    private static void concurrentRefreshes() throws Exception {
        var workers = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 32; i++) {
                int round = i;
                futures.add(workers.submit(() -> {
                    var b = budget();
                    var p = run(COMPILER, PLAN, "product", 8, Map.of("seed", 1L, "fuel", 8L + round),
                            Set.of(), Map.of(), true, true, b);
                    check(p.feasible() && accepted(b) && p.steps() == PLAN.steps(), "parallel refresh owns its verifier");
                }));
            }
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); }
        check(COMPILER.cacheMetrics().activeSearches() == 0, "balanced shared compiler lifetime");
    }

    private static GraphPlan<String> run(GraphCompiler<String> compiler, GraphPlan<String> candidate, String target, long amount,
                                         Map<String, Long> stock, Set<String> external, Map<String, Long> seeds,
                                         boolean preserve, boolean force, PlanningBudget b) {
        var work = new GraphPlanningWork<>(compiler, target, amount, stock, external, seeds, preserve, force, b)
                .catalysts(CatalystPolicy.MINIMAL).reuseCandidate(candidate);
        try {
            while (!work.step()) {}
            var result = work.result();
            if (result.feasible()) PlanVerifier.verify(result);
            return result;
        } finally {
            work.close();
            check(b.reservedBytes() == 0, "request resources released");
        }
    }

    private static RequestPlanningWork<String> request(GraphCompiler<String> compiler, Map<String, Long> stock,
                                                        String strategy, CatalystPolicy policy, PlanningBudget b) {
        return new RequestPlanningWork<>(compiler, "product", 8, stock, Set.of(), null, true, false,
                strategy, policy, false, false, b, () -> false, ignored -> {});
    }

    private static GraphPlan<String> run(RequestPlanningWork<String> work, PlanningBudget b) throws Exception {
        try (var scheduler = new PlanningScheduler(2, 4, 128, 2_000_000)) {
            return scheduler.submit(work, b).get(15, TimeUnit.SECONDS);
        } finally { check(b.reservedBytes() == 0, "scheduler releases refresh state"); }
    }

    private static GraphPlan<String> plan(PlanStep steps, Map<String, GraphRecipe<String>> recipes, String target,
                                          long amount, boolean preserve, Map<String, Long> seeds) {
        return new GraphPlan<>(target, amount, preserve, steps, recipes,
                PlanVerifier.initialExact(steps, recipes, target, amount, seeds), seeds, Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        return new GraphRecipe<>(id, id, inputs.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), outputs);
    }

    private static PlanningBudget budget() { return new PlanningBudget(0, 1_000_000, 64L << 20, () -> false, System::nanoTime); }
    private static boolean accepted(PlanningBudget b) { return b.diagnostics().matches(".*plan_reuse@[0-9]+: accepted;.*"); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
