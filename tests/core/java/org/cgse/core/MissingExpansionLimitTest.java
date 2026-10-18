// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Real planning reaches refill expansion before resource failure is induced. */
public final class MissingExpansionLimitTest {
    private static int checks;
    private static final List<PlanningBudget.Limit> LIMITS = List.of(PlanningBudget.Limit.SEARCH_LIMIT,
            PlanningBudget.Limit.TIMEOUT, PlanningBudget.Limit.MEMORY_LIMIT);
    private static List<GraphRecipe<String>> recipes() {
        return List.of(MissingMaterialExpansionTest.recipe("grow", Map.of("A", 1000L, "raw", 1L), Map.of("A", 1001L)),
                MissingMaterialExpansionTest.recipe("finish", Map.of("A", 1001L), Map.of("G", 1L)));
    }
    private static final class Limits implements AutoCloseable {
        final AtomicLong time = new AtomicLong();
        final AtomicBoolean cancel = new AtomicBoolean();
        final PlanningBudget budget;
        long held;
        Limits() { this(64L << 20); }
        Limits(long bytes) { budget = new PlanningBudget(1, 4_000_000, bytes, cancel::get, time::get); }
        void arm(PlanningBudget.Limit limit) {
            switch (limit) {
                case SEARCH_LIMIT -> budget.charge(budget.remainingWork());
                case TIMEOUT -> time.set(2_000_000);
                case MEMORY_LIMIT -> { held = budget.availableBytes(); budget.reserve(held); }
                default -> throw new AssertionError(limit);
            }
        }
        @Override public synchronized void close() { budget.release(held); held = 0; }
    }
    // Observation only. No proof flag, child, result or state-machine phase is injected.
    private static Object read(Object work, String name) throws ReflectiveOperationException {
        var field = work.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(work);
    }
    private static void reach(GraphPlanningWork<String> work) throws Exception {
        for (int i = 0; i < 1_000_000; i++) {
            if (read(work, "missingExpansion") != null) return;
            check(!work.step(), "Fixture never entered refill expansion");
        }
        throw new AssertionError("Expansion fixture did not terminate");
    }
    public static void main(String[] args) throws Exception {
        rootProofs(); noRootProof(); completedPublication(); capturedScope(); ordinaryOwner(); cancelled();
        System.out.println("MissingExpansionLimitTest: assertions=" + checks);
    }
    private static void rootProofs() throws Exception {
        for (var limit : LIMITS) {
            var limits = new Limits(); var b = limits.budget;
            var work = new GraphPlanningWork<>(new GraphCompiler<>(recipes()), "G", 1, Map.of(), false, false, b);
            try {
                reach(work);
                check(Boolean.TRUE.equals(read(work, "quantityBlocked")), "Original proof was not completed");
                limits.arm(limit);
                while (!work.step()) {}
                check(work.result().result() == GraphPlan.Result.INFEASIBLE, "Preview limit erased the completed root proof: " + limit);
                check(work.result().missingExact().isEmpty() && work.result().recipes().isEmpty(), "Published unfinished refill");
                check(b.failureDetail().contains("PROVEN_INFEASIBLE:") && b.failureDetail().contains("MISSING_EXPANSION_INCOMPLETE: " + limit), "Lost result/preview distinction");
            } finally { limits.close(); work.close(); work.close(); }
            check(b.reservedBytes() == 0, "Root proof handoff leaked");
        }
    }
    private static void completedPublication() throws Exception {
        for (var limit : LIMITS) {
            var limits = new Limits(); var b = limits.budget;
            var work = new GraphPlanningWork<>(new GraphCompiler<>(recipes()), "G", 1, Map.of(), false, false, b);
            try {
                reach(work);
                @SuppressWarnings("unchecked") var child = (MissingMaterialExpansion<String>) read(work, "missingExpansion");
                while (!child.step()) {}
                var expected = child.result();
                check(expected != null && !expected.missingExact().isEmpty(), "No completed child refill");
                var retained = work.limited(new PlanningBudget.Exhausted(limit));
                check(retained == expected && work.limited(new PlanningBudget.Exhausted(limit)) == expected, "Completed child lost at publication");
                MissingMaterialExpansionTest.verify(retained, Map.of());
            } finally { limits.close(); work.close(); }
            check(b.reservedBytes() == 0, "Completed refill handoff leaked");
        }
    }
    private static void noRootProof() throws Exception {
        // Funded raw stock makes net balances feasible. Refused optional proof
        // workspace leaves the startup question unproved, even though a funded
        // suffix and its missing seed are available to the preview stage.
        for (long bytes : new long[] {32L << 10, 256L << 10}) for (var limit : LIMITS) {
            var limits = new Limits(bytes); var b = limits.budget;
            var work = new GraphPlanningWork<>(new GraphCompiler<>(recipes()), "G", 1, Map.of("raw", 1001L), false, false, b);
            try {
                reach(work);
                check(!Boolean.TRUE.equals(read(work, "quantityBlocked")), "Fixture unexpectedly completed a root proof");
                limits.arm(limit);
                while (!work.step()) {}
                check(work.result().result() == GraphPlan.Result.valueOf(limit.name()), "Funded suffix or startup failure became root proof");
                check(work.result().missingExact().isEmpty() && !b.failureDetail().startsWith("PROVEN_INFEASIBLE:"), "Unfinished preview/proof escaped");
            } finally { limits.close(); work.close(); }
            check(b.reservedBytes() == 0, "Unproved refill handoff leaked");
        }
    }
    private static List<CapturedCatalog.Entry<String>> entries() {
        var result = new ArrayList<CapturedCatalog.Entry<String>>();
        for (var recipe : recipes()) {
            var inputs = recipe.slots().stream().map(s -> new CapturedRecipe.Input<>(s.amount(), List.of(
                    new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(s.key(), 1), null, false)))).toList();
            var outputs = recipe.outputs().entrySet().stream().map(e -> new CapturedRecipe.Amount<>(e.getKey(), e.getValue())).toList();
            result.add(new CapturedCatalog.Entry<>(recipe.id(), recipe.binding(), new CapturedRecipe<>(inputs, outputs, false, false)));
        }
        return result;
    }
    private static void capturedScope() throws Exception {
        for (boolean complete : List.of(false, true)) for (var limit : LIMITS) {
            var limits = new Limits(); var b = limits.budget;
            try (var work = new CapturedCatalog<>(entries(), complete).begin("G", 1, Map.of(), false, false, b).conditionalPreviews(true)) {
                GraphPlanningWork<?> child = null;
                for (int i = 0; i < 1_000_000; i++) {
                    child = (GraphPlanningWork<?>) read(work, "solving");
                    if (child != null && read(child, "missingExpansion") != null) break;
                    check(!work.step(), "Capture never reached refill expansion");
                }
                check(child != null && Boolean.TRUE.equals(read(child, "quantityBlocked")), "No observed local proof");
                limits.arm(limit);
                CapturedCatalog.Result<String> result;
                try { while (!work.step()) {} result = work.result(); }
                catch (PlanningBudget.Exhausted exhausted) { result = work.limited(exhausted); }
                check(result.coverage().complete() == complete, "Handoff changed capture coverage");
                check(result.plan().missingExact().isEmpty(), "Capture published partial refill");
                check((result.plan().result() == GraphPlan.Result.INFEASIBLE) == complete, "Restricted proof escaped or complete proof was lost");
                check(complete || !b.failureDetail().startsWith("PROVEN_INFEASIBLE:"), "Restricted proof leaked through diagnostics");
            } finally { limits.close(); }
            check(b.reservedBytes() == 0, "Captured limit handoff leaked");
        }
    }
    private static void ordinaryOwner() throws Exception {
        for (boolean bounded : List.of(false, true)) for (var limit : LIMITS) {
            var limits = new Limits(); var b = limits.budget;
            var request = new RequestPlanningWork<>(new GraphCompiler<>(recipes()), "G", 1, Map.of(), Set.of(), null,
                    false, false, "REPORT_MISSING_ITEMS", CatalystPolicy.MINIMAL, false, bounded, b, limits.cancel::get,
                    report -> { throw new AssertionError("Unexpected fallback"); });
            var armed = new AtomicBoolean();
            try (var scheduler = new PlanningScheduler(1, 1, 1, 1_000_000_000)) {
                var observing = new PlanningScheduler.Work<GraphPlan<String>>() {
                    public boolean advance(PlanningScheduler.Slice slice) {
                        try {
                            var current = read(request, "current");
                            if (!armed.get() && current instanceof CatalystPlanningWork<?> child &&
                                    read(read(child, "current"), "missingExpansion") != null) {
                                armed.set(true); limits.arm(limit);
                                // Exhaust the owner boundary before it harvests
                                // the child's still-pending refill expansion.
                                if (limit == PlanningBudget.Limit.MEMORY_LIMIT) b.reserve(1);
                                else b.check();
                            }
                        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                        return request.advance(slice);
                    }
                    public GraphPlan<String> result() { return request.result(); }
                    public GraphPlan<String> limited(PlanningBudget.Exhausted exhausted) { return request.limited(exhausted); }
                    public void close() { limits.close(); request.close(); }
                };
                try { scheduler.submit(observing, b).get(15, java.util.concurrent.TimeUnit.SECONDS); throw new AssertionError("Empty negative result not surfaced"); }
                catch (java.util.concurrent.ExecutionException failure) {
                    check(armed.get(), "Scheduled request never entered expansion");
                    check(PlanningFailure.result(failure) == (bounded ? GraphPlan.Result.valueOf(limit.name()) : GraphPlan.Result.INFEASIBLE),
                            "Scheduler lost original proof/coverage: " + failure);
                    check(!bounded || !b.failureDetail().startsWith("PROVEN_INFEASIBLE:"), "Ordinary bounded owner leaked proof detail");
                }
            } finally { limits.close(); }
            check(b.reservedBytes() == 0, "Ordinary owner leaked");
        }
    }
    private static void cancelled() throws Exception {
        var limits = new Limits(); var b = limits.budget;
        var work = new GraphPlanningWork<>(new GraphCompiler<>(recipes()), "G", 1, Map.of(), false, false, b);
        try {
            reach(work); limits.cancel.set(true);
            try { work.step(); throw new AssertionError("Cancellation became a proof result"); }
            catch (CancellationException expected) { checks++; }
        } finally { limits.close(); work.close(); }
        check(b.reservedBytes() == 0, "Cancelled expansion leaked");
    }
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
}
