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
        for (int n : new int[]{7, 15, 31}) for (long q : new long[]{2, 3, 16}) for (int order = 0; order < 12; order++) {
            var f = fixture(n, q, order, true); CapturedStateMatchingCostsTest.oracle(f); var budget = budget(); GraphPlan<String> plan;
            try (var work = new CapturedCatalog<>(f.entries()).begin("product", f.amount(), f.stock(), Set.of(), Map.of(), false, true, budget)) {
                while (!work.step()) {} plan = work.result().plan();
                check(plan.feasible() && !work.result().coverage().complete(), "Lost forced wear n=" + n + "; q=" + q + "; order=" + order + "; " + budget.diagnostics());
            }
            CapturedStateMatchingCostsTest.verify(plan, f, Map.of());
            if (n == 31 && q == 3 && order == 0) CapturedStateMatchingCostsTest.runtime(plan, f.amount());
            PlanningCostTraceTest.conserved(budget);
            check(budget.reservedBytes() == 0 && budget.searchWork() < 600_000 && budget.peakBytes() < (48L << 20), "Forced proof leaked / inflated work");
            maxWork = Math.max(maxWork, budget.searchWork()); maxBytes = Math.max(maxBytes, budget.peakBytes()); positives++;
        }
        cancelled();
        int pending = longerBoundary();
        System.out.println("CapturedStateForcedWearTest: positives=" + positives + "; assertions=" + checks + "; max_work=" + maxWork +
                "; max_bytes=" + maxBytes + "; longer_known_feasible_pending=" + pending + "/48 (not positive passes)");
    }

    static CapturedStateMatchingCostsTest.Fixture fixture(int n, long q, int order, boolean force) {
        var base = CapturedStateManufacturingSupportTest.fixture(n, 2, q, order);
        var stock = new LinkedHashMap<>(base.stock()); if (force) stock.put("product", base.amount());
        return new CapturedStateMatchingCostsTest.Fixture(base.entries(), stock, base.n(), base.mode(), base.cohort(), base.amount(), base.manufactured());
    }

    /** Keep larger independently feasible cases visible, with their local
     * restoration limitation separate from positive-regression counts. */
    private static int longerBoundary() {
        int pending = 0;
        for (int n : new int[]{47, 63}) for (long q : new long[]{2, 3, 16}) for (int order : new int[]{0, 1, 6, 7}) for (boolean force : List.of(false, true)) {
            var f = fixture(n, q, order, force); CapturedStateMatchingCostsTest.oracle(f); var budget = budget();
            try (var work = new CapturedCatalog<>(f.entries()).begin("product", f.amount(), f.stock(), Set.of(), Map.of(), false, force, budget)) {
                while (!work.step()) {} var plan = work.result().plan();
                if (plan.feasible()) CapturedStateMatchingCostsTest.verify(plan, f, Map.of());
                else {
                    check((plan.result() == GraphPlan.Result.UNKNOWN || plan.result() == GraphPlan.Result.SEARCH_LIMIT) &&
                            !work.result().coverage().complete(), "Bounded unique-state restoration became a negative proof"); pending++;
                }
            }
            check(budget.reservedBytes() == 0, "Longer boundary leaked");
        }
        return pending;
    }

    private static void cancelled() throws Exception {
        var f = fixture(31, 3, 0, true); var cancelled = new AtomicBoolean();
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
