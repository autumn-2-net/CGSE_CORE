// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Restricted-model proofs must not escape the request's capture boundary. */
public final class RequestCoverageTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        naturalCounterexample();
        positiveWitnesses();
        missingPreviews();
        completedProofBoundaries();
        resourceLimits();
        capturedRequestDomains();
        capturedProofLimits();
        System.out.println("RequestCoverageTest: " + assertions + " assertions passed");
    }

    private static void naturalCounterexample() throws Exception {
        // With 256 search units this public request completes the captured
        // impossibility proof, but cannot finish constructing a refill program.
        // A legal omitted producer is independently shown feasible below.
        var recipes = chain();
        var b = budget(256);
        var request = request(recipes, Map.of("funded", 1L), false, true, b, report -> {});
        try {
            run(request, b);
            throw new AssertionError("Restricted proof unexpectedly became a plan");
        } catch (ExecutionException failure) {
            check(PlanningFailure.result(failure) == GraphPlan.Result.INCOMPLETE_MODEL,
                    "Natural restricted proof escaped as " + PlanningFailure.result(failure));
            check(b.failureDetail().contains("captured_result=INFEASIBLE"), "Natural proof path was not exercised");
        }
        var complete = new ArrayList<>(recipes);
        complete.add(recipe("omitted", "funded", "x0"));
        for (boolean reverse : new boolean[]{false, true}) {
            if (reverse) Collections.reverse(complete);
            b = budget(1_000_000);
            var plan = run(request(complete, Map.of("funded", 1L), false, false, b, report -> {}), b);
            check(plan.feasible(), "Explicit complete-model witness lost after reordering");
            check(plan.patternTimes().equals(Map.of("omitted", 1L)), "Wrong witness");
            PlanVerifier.verify(plan);
        }
    }

    private static void positiveWitnesses() throws Exception {
        for (boolean bounded : new boolean[]{false, true}) {
            var b = budget(1_000_000);
            var request = request(chain(), Map.of("x4", 1L), false, bounded, b, report -> {});
            var plan = run(request, b);
            check(plan.feasible(), "Capture incompleteness discarded a positive witness");
            if (bounded) {
                check(plan.result() == GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, "Restricted witness claimed optimality");
                check(plan.seedOptimality() == null, "Restricted seed proof escaped");
            }
            check(!request.fallbackMode(), "Positive witness needlessly fell back");
            check(request.reuseCandidate() != null, "Verified restricted witness could not be reused");
            PlanVerifier.verify(plan);
        }
    }

    private static void missingPreviews() throws Exception {
        for (boolean bounded : new boolean[]{false, true}) {
            for (boolean fallback : new boolean[]{false, true}) {
                var b = budget(1_000_000);
                var observed = new AtomicReference<RequestPlanningWork.FallbackReport<String>>();
                var request = request(chain(), Map.of(), fallback, bounded, b, observed::set);
                try {
                    var plan = run(request, b);
                    check(!bounded || fallback, "Restricted missing preview escaped without fallback marking");
                    check(plan.result() == GraphPlan.Result.MISSING_INPUT && !plan.missingExact().isEmpty(), "Missing preview lost");
                    check(request.fallbackMode() == bounded, "Fallback presentation flag wrong");
                    if (bounded) check(observed.get().trigger() == GraphPlan.Result.INCOMPLETE_MODEL,
                            "Incomplete capture mislabeled as exhausted search");
                    else check(observed.get() == null, "Complete missing preview needlessly fell back");
                    check(request.reuseCandidate() == null, "Missing preview exported as reusable solution");
                } catch (ExecutionException failure) {
                    check(bounded && !fallback && PlanningFailure.result(failure) == GraphPlan.Result.INCOMPLETE_MODEL,
                            "Unexpected missing-preview failure " + failure);
                }
            }
        }
    }

    private static void completedProofBoundaries() throws Exception {
        // Inject only the already-completed child outcome at the publication
        // boundary; public end-to-end coverage is exercised separately above.
        for (boolean bounded : new boolean[]{false, true}) {
            for (boolean attempted : new boolean[]{false, true}) {
                var b = budget(1_000_000);
                var request = request(chain(), Map.of(), attempted, bounded, b, report -> {});
                set(request, "selected", outcome(GraphPlan.Result.INFEASIBLE, Map.of()));
                set(request, "fallbackAttempted", attempted);
                expectFinish(request, bounded ? GraphPlan.Result.INCOMPLETE_MODEL : GraphPlan.Result.INFEASIBLE);
                request.close();
            }
        }
        var b = budget(1_000_000);
        var request = request(chain(), Map.of(), true, true, b, report -> {});
        set(request, "selected", outcome(GraphPlan.Result.INFEASIBLE, Map.of()));
        finish(request);
        check(request.fallbackMode() && !request.result().missingExact().isEmpty(), "Fallback of restricted proof did not produce scoped preview");
        request.close();

        // A retained full-order missing preview in craft-less search must cross
        // the same coverage guard when a later probe exhausts its allowance.
        for (boolean attempted : new boolean[]{false, true}) {
            b = budget(1_000_000);
            request = request(chain(), Map.of(), attempted, true, b, report -> {});
            set(request, "selected", outcome(GraphPlan.Result.MISSING_INPUT, Map.of("x4", 1L)));
            set(request, "partialSearch", true);
            set(request, "fallbackAttempted", attempted);
            try {
                request.limited(new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT));
                throw new AssertionError("Retained restricted missing preview escaped at limit");
            } catch (PlanningFailure failure) {
                check(failure.result() == GraphPlan.Result.INCOMPLETE_MODEL, "Retained preview reported wrong outcome");
            } finally { request.close(); }
        }
    }

    private static void resourceLimits() throws Exception {
        for (var limit : PlanningBudget.Limit.values()) {
            for (boolean attempted : new boolean[]{false, true}) {
                var b = budget(1_000_000);
                var request = request(chain(), Map.of(), attempted, true, b, report -> {});
                set(request, "fallbackAttempted", attempted);
                var exhausted = new PlanningBudget.Exhausted(limit);
                try {
                    request.limited(exhausted);
                    throw new AssertionError("Resource failure escaped");
                } catch (PlanningBudget.Exhausted failure) {
                    check(failure == exhausted, "Capture guard replaced actual resource limit " + limit);
                } finally { request.close(); }
            }
        }
        for (var result : List.of(GraphPlan.Result.SEARCH_LIMIT, GraphPlan.Result.MEMORY_LIMIT,
                GraphPlan.Result.UNSUPPORTED_PATTERN_SEMANTICS, GraphPlan.Result.AMOUNT_LIMIT)) {
            var b = budget(1_000_000);
            var request = request(chain(), Map.of(), false, true, b, report -> {});
            set(request, "selected", outcome(result, Map.of()));
            expectFinish(request, result);
            request.close();
        }
    }

    private static void capturedRequestDomains() throws Exception {
        var candidates = new ArrayList<CapturedRecipe.Candidate<String>>();
        for (int i = 0; i < 300; i++) candidates.add(new CapturedRecipe.Candidate<>(
                new CapturedRecipe.Amount<>("a" + i, 1), null, false));
        var captured = new CapturedRecipe<>(List.of(new CapturedRecipe.Input<>(1, candidates)),
                List.of(new CapturedRecipe.Amount<>("x0", 1)), true, false);
        var entry = new CapturedCatalog.Entry<>("r", "binding", captured);
        var catalog = new CapturedCatalog<>(List.of(entry));
        var prefix = new ArrayList<GraphRecipe<String>>();
        var expansion = captured.expand(budget(1_000_000));
        for (int ordinal = 0; expansion.hasNext(); ordinal++) {
            var value = expansion.next();
            prefix.add(new GraphRecipe<>("r#" + ordinal, "binding", value.slots(), value.outputs()));
        }
        check(prefix.size() == 256 && prefix.stream().noneMatch(r -> r.inputs().containsKey("a299")),
                "Test prefix already contains late candidate");

        for (long amount : new long[]{1, 100, Long.MAX_VALUE}) {
            var b = budget(2_000_000);
            var request = domainRequest(prefix, catalog, amount, Map.of("a299", amount), Set.of(), null, false, b);
            var plan = run(request, b);
            check(plan.feasible() && plan.patternTimesExact().get("r#299").equals(java.math.BigInteger.valueOf(amount)),
                    "Public request lost later candidate at amount " + amount);
            check(!request.fallbackMode(), "Complete-domain witness came from fallback");
            check(plan.result() == GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, "Sparse-domain witness claimed full proof");
            check(b.reservedBytes() == 0, "Captured request leaked ownership");
            PlanVerifier.verify(plan);
        }

        var b = budget(2_000_000);
        var reduced = run(domainRequest(prefix, catalog, 10, Map.of("a299", 4L), Set.of(), null, true, b), b);
        check(reduced.feasible() && reduced.amount() == 4, "Craft-less probes did not use retained candidate domain");
        check(reduced.result() == GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL && reduced.seedOptimality() == null,
                "Later complete negative probe promoted an earlier restricted positive witness");

        b = budget(2_000_000);
        var checkpoint = new GraphJobRuntime.ReplanCheckpoint<>(1, "x0", 3, Map.of(), Map.of("a299", 2L));
        var replanned = run(domainRequest(prefix, catalog, 3, Map.of("a299", 1L), Set.of(), checkpoint, false, b), b);
        check(replanned.feasible() && replanned.patternTimes().get("r#299") == 3, "Domain replan lost owned forecast");

        b = budget(2_000_000);
        var external = run(domainRequest(prefix, catalog, 2, Map.of(), Set.of("a299"), null, false, b), b);
        check(external.feasible(), "Domain request lost external supply");

        b = budget(2_000_000);
        var missing = run(domainRequest(prefix, catalog, 1, Map.of(), Set.of(), null, false, b), b);
        check(missing.result() == GraphPlan.Result.MISSING_INPUT, "Complete catalog inherited prefix incompleteness");

        b = budget(2_000_000);
        var omitted = domainRequest(prefix, new CapturedCatalog<>(List.of(entry), false), 1, Map.of(), Set.of(), null, false, b);
        try {
            run(omitted, b);
            throw new AssertionError("Incomplete provider registry published a proof");
        } catch (ExecutionException failure) {
            check(PlanningFailure.result(failure) == GraphPlan.Result.INCOMPLETE_MODEL, "Incomplete registry was not scoped");
        }

        // A return outside the 256-window can itself be the requested target.
        // The empty prefix must not make this a direct emitter/unavailable goal.
        candidates.set(299, new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("a299", 1), "x0", false));
        var returns = new CapturedRecipe<>(List.of(new CapturedRecipe.Input<>(1, candidates)),
                List.of(new CapturedRecipe.Amount<>("other", 1)), true, false);
        var returnCatalog = new CapturedCatalog<>(List.of(new CapturedCatalog.Entry<>("return", "return", returns)));
        b = budget(2_000_000);
        var returnRequest = domainRequest(List.of(), returnCatalog, 1, Map.of("a299", 1L, "x0", 5L), Set.of("x0"), null, false, b);
        check(returnRequest.availability().get("x0") == 5, "Installing complete domain failed to restore captured target stock");
        var returned = run(returnRequest, b);
        check(returned.feasible() && returned.patternTimes().getOrDefault("return#299", 0L) == 1,
                "Late return producer was bypassed by direct-emission shortcut");

        b = budget(1_000_000);
        var empty = domainRequest(List.of(), new CapturedCatalog<String>(List.of()), 5, Map.of("x0", 9L), Set.of("x0"), null, false, b);
        check(!empty.availability().containsKey("x0"), "True direct emission retained target stock");
        var emitted = run(empty, b);
        check(emitted.feasible() && emitted.initial().get("x0") == 5, "Complete-domain direct emission changed semantics");

        b = budget(1_000_000);
        var cancelled = domainRequest(prefix, catalog, 1, Map.of(), Set.of(), null, false, b);
        b.cancel();
        try {
            run(cancelled, b);
            throw new AssertionError("Cancelled captured request completed");
        } catch (ExecutionException failure) {
            check(failure.getCause() instanceof java.util.concurrent.CancellationException, "Capture reclassified cancellation");
        } catch (java.util.concurrent.CancellationException expected) {
            check(true, "Cancellation reached caller");
        }
        check(b.reservedBytes() == 0, "Cancelled captured request leaked ownership");
    }

    private static void capturedProofLimits() throws Exception {
        var entries = new ArrayList<CapturedCatalog.Entry<String>>();
        for (int i = 0; i < 4; i++) {
            var input = new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(
                    new CapturedRecipe.Amount<>("x" + (i + 1), 1), null, false)));
            entries.add(new CapturedCatalog.Entry<>("r" + i, "r" + i,
                    new CapturedRecipe<>(List.of(input), List.of(new CapturedRecipe.Amount<>("x" + i, 1)), true, false)));
        }
        var catalog = new CapturedCatalog<>(entries);
        boolean proofSeen = false, resourceLimitSeen = false;
        for (int allowance = 64; allowance <= 768; allowance += 32) {
            var b = budget(allowance);
            var request = domainRequest(chain(), catalog, 1, Map.of(), Set.of(), null, false, b);
            try {
                var plan = run(request, b);
                check(plan.result() == GraphPlan.Result.MISSING_INPUT, "Complete catalog returned wrong negative preview");
            } catch (ExecutionException failure) {
                var result = PlanningFailure.result(failure);
                check(result == GraphPlan.Result.INFEASIBLE || result == GraphPlan.Result.SEARCH_LIMIT,
                        "Complete captured proof lost its scope: " + result);
                proofSeen |= result == GraphPlan.Result.INFEASIBLE;
                resourceLimitSeen |= result == GraphPlan.Result.SEARCH_LIMIT;
            }
            check(b.reservedBytes() == 0, "Limited captured request leaked ownership");
        }
        check(proofSeen, "Completed complete-domain proof was discarded at budget handoff");
        check(resourceLimitSeen, "Unfinished proof was incorrectly promoted at budget handoff");

        // A larger craft-less probe can finish a negative proof just as it
        // reaches the budget. That must not overwrite an earlier funded amount.
        var b = budget(1_000_000);
        var best = new GraphPlanner<>(new GraphCompiler<>(chain())).plan("x0", 1, Map.of("x4", 1L), false, true, b);
        var request = domainRequest(chain(), catalog, 10, Map.of("x4", 1L), Set.of(), null, true, b);
        set(request, "selected", best);
        set(request, "selectedIncomplete", false);
        set(request, "partialSearch", true);
        set(request, "currentIncomplete", false);
        set(request, "current", new PlanningScheduler.Work<GraphPlan<String>>() {
            @Override public boolean advance(PlanningScheduler.Slice slice) { return true; }
            @Override public GraphPlan<String> result() { return outcome(GraphPlan.Result.INFEASIBLE, Map.of()); }
            @Override public GraphPlan<String> limited(PlanningBudget.Exhausted limit) { return result(); }
        });
        var retained = request.limited(new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT));
        check(retained.feasible() && retained.amount() == 1, "Negative larger probe overwrote funded smaller plan");
        PlanVerifier.verify(retained);
        request.close();
    }

    private static RequestPlanningWork<String> domainRequest(List<GraphRecipe<String>> prefix, CapturedCatalog<String> catalog,
            long amount, Map<String, Long> stock, Set<String> external, GraphJobRuntime.ReplanCheckpoint<String> checkpoint,
            boolean craftLess, PlanningBudget b) {
        return new RequestPlanningWork<>(new GraphCompiler<>(prefix), "x0", amount, stock, external, checkpoint,
                false, craftLess, "DEFAULT", CatalystPolicy.MINIMAL, false, true, b, () -> false, report -> {})
                .capturedCatalog(catalog);
    }

    private static void expectFinish(RequestPlanningWork<String> request, GraphPlan.Result result) throws Exception {
        try {
            finish(request);
            throw new AssertionError("Expected request failure " + result);
        } catch (InvocationTargetException failure) {
            check(PlanningFailure.result(failure) == result, "Wrong boundary outcome " + PlanningFailure.result(failure));
        }
    }

    private static void finish(RequestPlanningWork<String> request) throws Exception {
        var method = RequestPlanningWork.class.getDeclaredMethod("finish");
        method.setAccessible(true);
        method.invoke(request);
    }

    private static void set(Object receiver, String name, Object value) throws Exception {
        var field = RequestPlanningWork.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(receiver, value);
    }

    private static GraphPlan<String> outcome(GraphPlan.Result result, Map<String, Long> missing) {
        return new GraphPlan<>("x0", 1, false, new PlanStep.Sequence(List.of()), Map.of(), Map.of(), Map.of(), missing, result, 0, 0);
    }

    private static List<GraphRecipe<String>> chain() {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < 4; i++) recipes.add(recipe("r" + i, "x" + (i + 1), "x" + i));
        return recipes;
    }

    private static GraphRecipe<String> recipe(String id, String input, String output) {
        return new GraphRecipe<>(id, id, List.of(new GraphRecipe.Slot<>(input, 1)), Map.of(output, 1L));
    }

    private static RequestPlanningWork<String> request(List<GraphRecipe<String>> recipes, Map<String, Long> stock,
            boolean fallback, boolean bounded, PlanningBudget budget,
            java.util.function.Consumer<RequestPlanningWork.FallbackReport<String>> reporter) {
        return new RequestPlanningWork<>(new GraphCompiler<>(recipes), "x0", 1, stock, Set.of(), null,
                false, false, "DEFAULT", CatalystPolicy.MINIMAL, fallback, bounded, budget, () -> false, reporter);
    }

    private static GraphPlan<String> run(RequestPlanningWork<String> request, PlanningBudget budget) throws Exception {
        try (var scheduler = new PlanningScheduler(1, 2, 1000, 2_000_000)) {
            return scheduler.submit(request, budget).get(15, TimeUnit.SECONDS);
        }
    }

    private static PlanningBudget budget(long work) {
        return new PlanningBudget(0, work, () -> false);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
