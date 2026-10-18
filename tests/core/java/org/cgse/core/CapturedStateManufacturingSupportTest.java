// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** An auxiliary tool count cannot hide its funded manufacturing source. */
public final class CapturedStateManufacturingSupportTest {
    private static int checks, cases, manufactured;
    private static long maxWork, maxBytes;

    public static void main(String[] args) throws Exception {
        for (int mode : new int[]{0, 1, 2}) for (long q : new long[]{2, 3, 16}) for (int n : new int[]{1, 2, 7, 31}) {
            for (int order = 0; order < 12; order++) {
                var f = fixture(n, mode, q, order);
                solve(f, true, mode == 0 ? Map.of("a", q) : Map.of(), false);
            }
        }
        for (int mode : new int[]{0, 1, 2}) {
            var f = fixture(2, mode, 3, 0);
            CapturedStateMatchingCostsTest.runtime(solve(f, false, Map.of(), false), f.amount());
        }
        var f = fixture(2, 0, 3, 0);
        var stock = new LinkedHashMap<>(f.stock()); stock.put("product", 2L);
        solve(with(f, f.entries(), stock), true, Map.of("a", 3L), true);
        // Manufacturing itself has an ordinary multi-step dependency. The
        // metal -> refined -> c path has the same literal funded witness.
        var entries = new ArrayList<>(f.entries());
        entries.set(3, entry("build", List.of(input("refined", 1)), "c", 3));
        entries.add(entry("smelt", List.of(input("metal", 1)), "refined", 1));
        solve(with(f, entries, f.stock()), true, Map.of("a", 3L), false);
        long wide = Long.MAX_VALUE / 8;
        solve(fixture(2, 0, wide, 0), false, Map.of("a", wide), false);
        failures(); limits(); workspaceRefusal(); cancelled(); handoff();
        check(manufactured > 0, "Manufacturing support was not exercised");
        System.out.println("CapturedStateManufacturingSupportTest: cases=" + cases + "; assertions=" + checks + "; manufactured=" + manufactured +
                "; max_work=" + maxWork + "; max_bytes=" + maxBytes);
    }

    static CapturedStateMatchingCostsTest.Fixture fixture(int n, int mode, long q, int order) {
        var f = CapturedStateMatchingCostsTest.fixture(n, mode, q, false, order, true);
        var stock = new LinkedHashMap<>(f.stock());
        for (String key : new ArrayList<>(stock.keySet())) if (!key.equals("raw") && !key.equals("metal")) stock.merge(key, q - 1, Math::addExact);
        return with(f, f.entries(), stock);
    }

    private static CapturedStateMatchingCostsTest.Fixture with(CapturedStateMatchingCostsTest.Fixture f,
            List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock) {
        return new CapturedStateMatchingCostsTest.Fixture(List.copyOf(entries), stock, f.n(), f.mode(), f.cohort(), f.amount(), true);
    }

    private static GraphPlan<String> solve(CapturedStateMatchingCostsTest.Fixture f, boolean publicEntry, Map<String, Long> seeds, boolean force) {
        CapturedStateMatchingCostsTest.oracle(f); var budget = budget(40_000_000, 64L << 20); budget.enableMetrics(); GraphPlan<String> plan;
        if (publicEntry) try (var work = new CapturedCatalog<>(f.entries()).begin("product", f.amount(), f.stock(), Set.of(), seeds, false, force, budget)) {
            while (!work.step()) {} plan = work.result().plan();
            check(plan.feasible() && !work.result().coverage().complete(), "Lost funded manufacture mode=" + f.mode() + " q=" + f.cohort() + " n=" + f.n() + ": " + budget.diagnostics());
        } else try (var work = new CapturedStatePlanning<>(f.entries(), "product", f.amount(), f.stock(), Set.of(), seeds, false, force, budget)) {
            while (!work.step()) {} plan = work.result(); check(plan != null, "Lost direct manufacture: " + budget.diagnostics());
        }
        CapturedStateMatchingCostsTest.verify(plan, f, seeds); PlanningCostTraceTest.conserved(budget);
        check(budget.reservedBytes() == 0 && budget.searchWork() < 600_000 && budget.peakBytes() < (48L << 20), "Manufacturing repair leaked / inflated work");
        if (budget.diagnostics().contains("candidate_state_manufacture@")) manufactured++;
        maxWork = Math.max(maxWork, budget.searchWork()); maxBytes = Math.max(maxBytes, budget.peakBytes()); cases++; return plan;
    }

    private static void failures() throws Exception {
        var f = fixture(2, 0, 3, 0);
        var empty = new LinkedHashMap<>(f.stock()); empty.remove("metal");
        rejects(with(f, f.entries(), empty), Map.of("a", 3L)); // No manufacture funding.
        var entries = new ArrayList<>(f.entries());
        entries.set(3, entry("build", List.of(input("metal", 1), input("c", 1)), "c", 3));
        rejects(with(f, entries, f.stock()), Map.of("a", 3L)); // Net-positive count vector, no startable c seed.
        entries.set(3, entry("build", List.of(input("metal", 1), input("a", 3)), "c", 3));
        rejects(with(f, entries, f.stock()), Map.of("a", 3L)); // Manufacture cannot spend the same a as a final tool.
        rejects(f, Map.of("c", 4L)); // Only three c can exist; exact seed verification is mandatory.
    }

    private static void rejects(CapturedStateMatchingCostsTest.Fixture f, Map<String, Long> seeds) throws Exception {
        var budget = budget(40_000_000, 64L << 20); budget.enableMetrics();
        boolean retained = false, proposed = false; var attempts = new java.util.HashSet<String>();
        var pattern = java.util.regex.Pattern.compile("candidate_state_manufacture@[0-9]+: propose;[^|]*");
        try (var work = new CapturedStatePlanning<>(f.entries(), "product", f.amount(), f.stock(), Set.of(), seeds, false, false, budget)) {
            while (!work.step()) {
                proposed |= field(work, "supportRepair") != null;
                String notes = budget.diagnostics(); retained |= notes.contains("original_prefix_retained=true");
                var matches = pattern.matcher(notes); while (matches.find()) attempts.add(matches.group().strip());
            }
            check(work.result() == null, "Manufactured unowned tools / accepted an invalid seed");
        }
        check(proposed && retained && attempts.size() == 1, "Lost failure continuation / renewed optional manufacturing trial");
        PlanningCostTraceTest.conserved(budget);
        check(budget.reservedBytes() == 0 && budget.searchWork() < 600_000, "Rejected manufacture leaked / inflated work");
    }

    private static void limits() {
        var f = fixture(2, 0, 3, 0);
        for (long cap : new long[]{1024, 16_000, 40_000, 80_000, 160_000}) limited(f, cap, 64L << 20);
        for (long bytes : new long[]{512, 4096, 32_768, 65_536, 262_144, 6L << 20}) limited(f, 40_000_000, bytes);
    }

    private static void limited(CapturedStateMatchingCostsTest.Fixture f, long cap, long bytes) {
        var budget = budget(cap, bytes);
        try (var work = new CapturedStatePlanning<>(f.entries(), "product", f.amount(), f.stock(), Set.of(), Map.of("a", 3L), false, false, budget)) {
            try { while (!work.step()) {} } catch (PlanningBudget.Exhausted expected) { /* Inconclusive. */ }
            if (work.result() != null) CapturedStateMatchingCostsTest.verify(work.result(), f, Map.of("a", 3L));
        }
        check(budget.reservedBytes() == 0 && budget.searchWork() <= cap + 1 && budget.peakBytes() <= bytes, "Repair renewed work / leaked a refused workspace");
    }

    private static void cancelled() throws Exception {
        var f = fixture(2, 0, 3, 0);
        for (boolean restoring : List.of(false, true)) {
            var cancel = new AtomicBoolean(); var budget = new PlanningBudget(0, 40_000_000, 64L << 20, cancel::get, System::nanoTime);
            try (var work = new CapturedStatePlanning<>(f.entries(), "product", 2, f.stock(), Set.of(), Map.of(), false, false, budget)) {
                while (!work.step()) if (restoring ? (boolean) field(work, "manufacturingReplay") : field(work, "supportRepair") != null) { cancel.set(true); break; }
                check(cancel.get(), "No retained manufacturing continuation to cancel");
                try { work.step(); throw new AssertionError("Ignored cancellation"); } catch (java.util.concurrent.CancellationException expected) { checks++; }
            }
            check(budget.reservedBytes() == 0, "Cancelled manufacturing continuation leaked");
        }
    }

    private static void workspaceRefusal() throws Exception {
        var f = fixture(2, 0, 3, 0); var budget = budget(40_000_000, 64L << 20); long held = 0;
        try (var work = new CapturedStatePlanning<>(f.entries(), "product", 2, f.stock(), Set.of(), Map.of(), false, false, budget)) {
            while (!work.step() && field(work, "supportRepair") == null) {}
            check(field(work, "supportRepair") != null, "No count repair to refuse workspace");
            held = budget.availableBytes() - 128; check(budget.tryReserve(held), "Could not inject workspace refusal");
            try { while (!work.step() && field(work, "supportRepair") != null) {} }
            catch (PlanningBudget.Exhausted expected) { /* Shared request workspace is exhausted too. */ }
            check(work.result() == null, "Workspace refusal fabricated a positive witness");
        } finally { budget.release(held); }
        check(budget.reservedBytes() == 0, "Refused count repair retained its view / failed matching / child");
    }

    private static void handoff() throws Exception {
        var f = fixture(7, 0, 3, 0); var budget = budget(40_000_000, 64L << 20);
        var a = Executors.newSingleThreadExecutor(); var b = Executors.newSingleThreadExecutor();
        try (var work = new CapturedStatePlanning<>(f.entries(), "product", f.amount(), f.stock(), Set.of(), Map.of("a", 3L), false, false, budget)) {
            boolean done = false; int steps = 0;
            while (!done) done = ((steps++ & 1) == 0 ? a : b).submit(() -> { for (int i = 0; i < 16; i++) if (work.step()) return true; return false; }).get();
            check(work.result() != null && steps > 1, "Lost manufacturing continuation on worker handoff");
            CapturedStateMatchingCostsTest.verify(work.result(), f, Map.of("a", 3L));
        } finally { a.shutdownNow(); b.shutdownNow(); }
        check(budget.reservedBytes() == 0, "Worker handoff leaked");
    }

    private static CapturedRecipe.Input<String> input(String key, long count) {
        return new CapturedRecipe.Input<>(count, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false)));
    }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, String output, long count) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>(output, count)), false, false));
    }
    private static Object field(Object owner, String name) throws Exception { var f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
    private static PlanningBudget budget(long work, long bytes) { return new PlanningBudget(0, work, bytes, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
