// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Extra physical stock cannot make a known whole-tool witness disappear. */
public final class CapturedStateActivationStockTest {
    private static int checks, cases, replays, compounded;
    private static long maxWork, maxBytes;

    public static void main(String[] args) throws Exception {
        for (long q : new long[]{2, 3, 16}) for (int n : new int[]{1, 2, 7, 31}) for (int order = 0; order < 12; order++)
            solve(fixture(n, 0, q, false, order, false, q - 1), true, Map.of(), false);
        for (long q : new long[]{2, 3}) for (int n : new int[]{2, 7}) for (int order = 0; order < 12; order++)
            solve(fixture(n, 1, q, false, order, false, q - 1), true, Map.of(), false);
        for (long q : new long[]{2, 3}) for (int n : new int[]{2, 7, 31}) for (int order = 0; order < 12; order++)
            solve(fixture(n, 0, q, true, order, false, q - 1), true, Map.of(), false);
        for (long q : new long[]{2, 3}) for (int n : new int[]{2, 7}) for (int order = 0; order < 6; order++)
            solve(fixture(n, 2, q, false, order, false, q - 1), true, Map.of(), false);
        for (int mode : new int[]{0, 1, 2}) {
            var f = fixture(2, mode, 3, false, 0, false, 2);
            CapturedStateMatchingCostsTest.runtime(solve(f, false, Map.of(), false), f.amount());
        }
        for (boolean made : List.of(false, true)) {
            var f = fixture(2, 0, 3, false, 0, made, made ? 0 : 2);
            solve(f, true, Map.of("a", 3L), false);
            var stock = new LinkedHashMap<>(f.stock()); stock.put("product", f.amount());
            solve(new CapturedStateMatchingCostsTest.Fixture(f.entries(), stock, f.n(), f.mode(), f.cohort(), f.amount(), f.manufactured()),
                    true, Map.of("a", 3L), true);
        }
        solve(fixture(2, 0, Long.MAX_VALUE / 8, false, 0, false, Long.MAX_VALUE / 8 - 1), false, Map.of(), false);
        boundaries(); cancelled(); handoff(); forkIsolation(); int pending = pendingManufacture();
        check(replays > 0 && compounded > 0, "Stock replay / additional choice feedback was not exercised");
        System.out.println("CapturedStateActivationStockTest: cases=" + cases + "; assertions=" + checks + "; replays=" + replays +
                "; compounded=" + compounded + "; max_work=" + maxWork + "; max_bytes=" + maxBytes +
                "; known_feasible_manufacture_unresolved=" + pending + "/12 (not positive passes)");
    }

    private static CapturedStateMatchingCostsTest.Fixture fixture(int n, int mode, long q, boolean mixed, int order, boolean made, long tail) {
        var f = CapturedStateMatchingCostsTest.fixture(n, mode, q, mixed, order, made);
        var stock = new LinkedHashMap<>(f.stock());
        for (String key : new ArrayList<>(stock.keySet())) if (!key.equals("raw") && !key.equals("metal")) stock.merge(key, tail, Math::addExact);
        return new CapturedStateMatchingCostsTest.Fixture(f.entries(), stock, f.n(), f.mode(), f.cohort(), f.amount(), f.manufactured());
    }

    private static GraphPlan<String> solve(CapturedStateMatchingCostsTest.Fixture f, boolean publicEntry, Map<String, Long> seeds, boolean force) {
        CapturedStateMatchingCostsTest.oracle(f); var budget = budget(40_000_000); budget.enableMetrics(); GraphPlan<String> plan;
        if (publicEntry) {
            try (var work = new CapturedCatalog<>(f.entries()).begin("product", f.amount(), f.stock(), Set.of(), seeds, false, force, budget)) {
                while (!work.step()) {} plan = work.result().plan();
                check(plan.feasible() && !work.result().coverage().complete(), "Lost funded stock witness mode=" + f.mode() + " n=" + f.n() + " q=" + f.cohort() + ": " + budget.diagnostics());
            }
        } else {
            try (var work = new CapturedStatePlanning<>(f.entries(), "product", f.amount(), f.stock(), Set.of(), seeds, false, force, budget)) {
                while (!work.step()) {} plan = work.result(); check(plan != null, "Lost direct stock witness: " + budget.diagnostics());
            }
        }
        CapturedStateMatchingCostsTest.verify(plan, f, seeds); PlanningCostTraceTest.conserved(budget);
        check(budget.reservedBytes() == 0 && budget.searchWork() < 600_000 && budget.peakBytes() < (48L << 20), "Stock recovery leaked / inflated work");
        var diagnostic = budget.diagnostics(); int start = diagnostic.indexOf("candidate_state_stock@");
        if (start >= 0) { replays++; if (diagnostic.indexOf("retry_entity_choices", start) >= 0) compounded++; }
        cases++; maxWork = Math.max(maxWork, budget.searchWork()); maxBytes = Math.max(maxBytes, budget.peakBytes()); return plan;
    }

    private static void boundaries() throws Exception {
        var f = fixture(2, 0, 3, false, 0, false, 2);
        // c is unchanged, but only five exist and none can be manufactured.
        var budget = budget(40_000_000); budget.enableMetrics();
        boolean retained = false; var trials = new java.util.HashSet<String>();
        var trialPattern = java.util.regex.Pattern.compile("candidate_state_stock@[0-9]+: replay; keys=[0-9]+");
        try (var work = new CapturedStatePlanning<>(f.entries(), "product", f.amount(), f.stock(), Set.of(), Map.of("c", 6L), false, false, budget)) {
            while (!work.step()) {
                // Diagnostics are bounded; retain observations before later
                // restricted searches can evict this optional trial's notes.
                String notes = budget.diagnostics(); retained |= notes.contains("original_prefix_retained=true");
                var matches = trialPattern.matcher(notes); while (matches.find()) trials.add(matches.group());
            }
            check(work.result() == null, "Supplement invented an exact final seed");
        }
        check(retained, "Failed replay discarded the original continuation");
        check(trials.size() == 1, "Failed stock trial renewed itself");
        check(budget.reservedBytes() == 0, "Rejected replay leaked");

        // Every c is an ordinary warm input. None is spare tool inventory.
        var entries = new ArrayList<>(f.entries()); var warm = entries.get(0); var inputs = new ArrayList<>(warm.recipe().inputs());
        inputs.add(new CapturedRecipe.Input<>(5, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("c", 1), null, false))));
        entries.set(0, new CapturedCatalog.Entry<>(warm.id(), warm.binding(), new CapturedRecipe<>(inputs, warm.recipe().outputs(), false, false)));
        budget = budget(40_000_000); boolean inspected = false;
        try (var work = new CapturedStatePlanning<>(entries, "product", 2, f.stock(), Set.of(), Map.of(), false, false, budget)) {
            while (!work.step()) {
                Object replay = field(work, "activationReplay");
                if (replay != null && !inspected) {
                    @SuppressWarnings("unchecked") var pools = (Map<String, Map<String, BigInteger>>) field(replay, "pools");
                    check(pools.values().stream().allMatch(pool -> !pool.containsKey("c")), "Claimed an ordinary input as spare tool stock"); inspected = true;
                }
            }
            check(work.result() == null, "Spent the same ordinary c as a reusable tool");
        }
        check(inspected && budget.reservedBytes() == 0, "Ordinary-stock exclusion not exercised / leaked");

        for (long cap : new long[]{1024, 16_000, 38_000, 44_000, 80_000}) {
            budget = budget(cap);
            try (var work = new CapturedStatePlanning<>(f.entries(), "product", 2, f.stock(), Set.of(), Map.of(), false, false, budget)) {
                try { while (!work.step()) {} } catch (PlanningBudget.Exhausted expected) { /* Inconclusive. */ }
                if (work.result() != null) CapturedStateMatchingCostsTest.verify(work.result(), f, Map.of());
            }
            check(budget.reservedBytes() == 0 && budget.searchWork() <= cap + 1, "Replay renewed the request budget");
        }
    }

    private static void cancelled() throws Exception {
        var f = fixture(2, 0, 3, false, 0, false, 2); var cancel = new AtomicBoolean();
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, cancel::get, System::nanoTime);
        try (var work = new CapturedStatePlanning<>(f.entries(), "product", 2, f.stock(), Set.of(), Map.of(), false, false, budget)) {
            while (!work.step()) if (field(work, "activationReplay") != null) { cancel.set(true); break; }
            check(cancel.get(), "No retained stock replay to cancel");
            try { work.step(); throw new AssertionError("Ignored cancellation"); } catch (java.util.concurrent.CancellationException expected) { checks++; }
        }
        check(budget.reservedBytes() == 0, "Cancelled nested replay leaked");
    }

    private static void handoff() throws Exception {
        var f = fixture(7, 0, 3, false, 0, false, 2); var budget = budget(40_000_000);
        var a = Executors.newSingleThreadExecutor(); var b = Executors.newSingleThreadExecutor();
        try (var work = new CapturedStatePlanning<>(f.entries(), "product", 7, f.stock(), Set.of(), Map.of(), false, false, budget)) {
            boolean done = false; int steps = 0;
            while (!done) done = ((steps++ & 1) == 0 ? a : b).submit(() -> { for (int i = 0; i < 16; i++) if (work.step()) return true; return false; }).get();
            check(work.result() != null && steps > 1, "Lost stock replay on worker handoff");
            CapturedStateMatchingCostsTest.verify(work.result(), f, Map.of());
        } finally { a.shutdownNow(); b.shutdownNow(); }
        check(budget.reservedBytes() == 0, "Worker handoff leaked");
    }

    private static void forkIsolation() {
        StateFamily<String> consume = rule("consume", false), retained = rule("retained", true);
        var budget = budget(1_000_000);
        try (var parent = StateChoiceRepair.<String>create(budget)) {
            parent.protect(retained); long before = parent.remainingWork();
            try (var child = parent.fork()) {
                check(child != null && child.protect(consume), "Choice fork lost feedback");
                check(child.score(consume, "tool") != parent.score(consume, "tool"), "Child feedback mutated parent scores");
                budget.charge(17); check(child.remainingWork() == parent.remainingWork() && parent.remainingWork() < before, "Fork renewed the feedback lifetime");
            }
            check(parent.active() && parent.protect(consume), "Closing a fork closed / modified its parent");
        }
        check(budget.reservedBytes() == 0, "Choice fork leaked");
        budget = new PlanningBudget(0, 1_000_000, 700, () -> false, System::nanoTime);
        try (var parent = StateChoiceRepair.<String>create(budget)) {
            check(parent != null && parent.fork() == null && parent.active(), "Optional fork memory refusal corrupted its parent");
        }
        check(budget.reservedBytes() == 0, "Declined fork leaked");
    }

    /** Open search gap: the extra stock hides a necessary manufacturing source.
     * Preserve the independently feasible inputs without calling UNKNOWN a
     * solved case. These checks only guard against false negative proofs. */
    static int pendingManufacture() {
        int unresolved = 0;
        for (int order = 0; order < 12; order++) {
            var f = fixture(2, 0, 3, false, order, true, 2);
            CapturedStateMatchingCostsTest.oracle(f); var budget = budget(40_000_000);
            try (var work = new CapturedCatalog<>(f.entries()).begin("product", 2, f.stock(), Set.of(), Map.of("a", 3L), false, false, budget)) {
                while (!work.step()) {} var plan = work.result().plan();
                if (plan.feasible()) CapturedStateMatchingCostsTest.verify(plan, f, Map.of("a", 3L));
                else {
                    check((plan.result() == GraphPlan.Result.UNKNOWN || plan.result() == GraphPlan.Result.SEARCH_LIMIT) && !work.result().coverage().complete(),
                            "Omitted manufacture was promoted to global infeasibility"); unresolved++;
                }
                System.out.println("Known manufacture order=" + order + "; expected=FEASIBLE; actual=" + plan.result() + "; work=" + budget.searchWork());
            }
            check(budget.reservedBytes() == 0, "Pending manufacture case leaked");
        }
        return unresolved;
    }

    private static StateFamily<String> rule(String id, boolean returned) { return new StateFamily<>() {
        public String id() { return id; } public boolean accepts(String key) { return key.equals("tool"); }
        public long capacity(String key) { return accepts(key) ? 1 : 0; }
        public String nominalAfterOne(String key) { return returned ? key : null; }
    }; }
    private static Object field(Object owner, String name) throws Exception { var f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
    private static PlanningBudget budget(long work) { return new PlanningBudget(0, work, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
