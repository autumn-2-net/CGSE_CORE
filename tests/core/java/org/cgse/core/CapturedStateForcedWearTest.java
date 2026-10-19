// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Already stocked targets do not excuse fresh production. Long manufacture /
 * wear witnesses must finish the same local trial without redundant counting. */
public final class CapturedStateForcedWearTest {
    private static int checks, positives;
    private static long maxWork, maxBytes;

    public static void main(String[] args) throws Exception {
        for (int n : new int[]{7, 15, 31, 47, 63}) for (long q : new long[]{2, 3, 16}) for (int order = 0; order < 12; order++)
            for (boolean force : n >= 47 ? List.of(false, true) : List.of(true)) {
            var f = fixture(n, q, order, force); CapturedStateMatchingCostsTest.oracle(f); var budget = budget(); GraphPlan<String> plan;
            try (var work = new CapturedCatalog<>(f.entries()).begin("product", f.amount(), f.stock(), Set.of(), Map.of(), false, force, budget)) {
                while (!work.step()) {} plan = work.result().plan();
                check(plan.feasible() && !work.result().coverage().complete(), "Lost forced wear n=" + n + "; q=" + q + "; order=" + order + "; " + budget.diagnostics());
            }
            CapturedStateMatchingCostsTest.verify(plan, f, Map.of());
            if ((n == 31 || n == 47 || n == 63) && q == 3 && order == 0 && force) CapturedStateMatchingCostsTest.runtime(plan, f.amount());
            PlanningCostTraceTest.conserved(budget);
            check(budget.reservedBytes() == 0 && budget.searchWork() < 600_000 && budget.peakBytes() < (48L << 20), "Forced proof leaked / inflated work");
            maxWork = Math.max(maxWork, budget.searchWork()); maxBytes = Math.max(maxBytes, budget.peakBytes()); positives++;
        }
        cancelled();
        for (int ending = 0; ending < 3; ending++) completionHandoff(ending);
        System.out.println("CapturedStateForcedWearTest: positives=" + positives + "; assertions=" + checks + "; max_work=" + maxWork +
                "; max_bytes=" + maxBytes + "; longer_known_feasible=72/72; completion_handoffs=3");
    }

    static CapturedStateMatchingCostsTest.Fixture fixture(int n, long q, int order, boolean force) {
        var base = CapturedStateManufacturingSupportTest.fixture(n, 2, q, order);
        var stock = new LinkedHashMap<>(base.stock()); if (force) stock.put("product", base.amount());
        return new CapturedStateMatchingCostsTest.Fixture(base.entries(), stock, base.n(), base.mode(), base.cohort(), base.amount(), base.manufactured());
    }

    /** Finishing one restored word inherits a deadline, not a fresh search.
     * Advance the clock explicitly to test both sides of that boundary. */
    private static void completionHandoff(int ending) throws Exception {
        var f = fixture(63, 3, 0, true); var budget = budget();
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var work = new CapturedStatePlanning<>(f.entries(), "product", f.amount(), f.stock(), Set.of(), Map.of(), false, true, budget)) {
            CapturedStatePlanning<?> replay = null;
            while (!work.step()) {
                var candidate = (CapturedStatePlanning<?>) field(work, "activationReplay");
                if (candidate != null && (boolean) field(work, "manufacturingReplay") &&
                        (ending == 2 || field(candidate, "assembly") != null)) { replay = candidate; break; }
            }
            check(replay != null, "Missing manufacture completion candidate");
            long parentEnd = (long) field(work, "workStarted") + (long) field(work, "allowance");
            long replayStart = (long) field(replay, "workStarted");
            long completionAllowance = (long) field(replay, "replayCompletionAllowance");
            check(replayStart + completionAllowance == parentEnd, "Completion renewed the parent deadline");
            long oldEnd = replayStart + (long) field(replay, "allowance");
            long stopAt = ending == 1 ? parentEnd : ending == 2 ? replayStart + 8192 : oldEnd;
            budget.charge(Math.max(1, stopAt - budget.nodes() + 1));
            if (ending != 0) {
                check(replay.step() && replay.result() == null &&
                        replay.outcome().status() == CapturedStatePlanning.Status.LIMITED &&
                        replay.outcome().reason() == CapturedStatePlanning.Reason.LOCAL_WORK_LIMIT,
                        "Completion escaped the parent / speculative restoration deadline");
            } else {
                while (!worker.submit(work::step).get()) {}
                check(work.result() != null && work.result().feasible(), "Lost complete word after speculative deadline");
                CapturedStateMatchingCostsTest.verify(work.result(), f, Map.of());
            }
            check((long) field(replay, "replayCompletionAllowance") == completionAllowance,
                    "Worker movement replenished completion allowance");
        } finally { worker.shutdownNow(); }
        check(budget.reservedBytes() == 0, "Completion handoff leaked");
    }

    private static void cancelled() throws Exception {
        var f = fixture(63, 3, 0, true); var cancelled = new AtomicBoolean();
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, cancelled::get, System::nanoTime);
        try (var work = new CapturedStatePlanning<>(f.entries(), "product", f.amount(), f.stock(), Set.of(), Map.of(), false, true, budget)) {
            boolean found = false;
            while (!work.step()) {
                Object replay = field(work, "activationReplay");
                if (replay != null && (boolean) field(work, "manufacturingReplay") && field(replay, "verification") != null) { found = true; break; }
            }
            check(found, "No retained forced verifier to cancel"); cancelled.set(true);
            try { work.step(); throw new AssertionError("Ignored forced-verifier cancellation"); }
            catch (java.util.concurrent.CancellationException expected) { checks++; }
            check(work.result() == null, "Published before forced prefix verification");
        }
        check(budget.reservedBytes() == 0, "Cancelled forced verifier leaked");
    }

    private static Object field(Object owner, String name) throws Exception { var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
    private static PlanningBudget budget() {
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, () -> false, System::nanoTime);
        budget.enableMetrics();
        return budget;
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
