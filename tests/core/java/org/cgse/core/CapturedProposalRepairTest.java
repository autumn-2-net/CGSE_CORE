// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Current physical rows, full execution checks and the failed-neighborhood boundary. */
public final class CapturedProposalRepairTest {
    private static int checks, requests;

    public static void main(String[] args) {
        manufacturing(); currentSemantics(); restrictedFailure(); limits();
        System.out.println("CapturedProposalRepairTest: requests=" + requests + "; assertions=" + checks);
    }

    private static void manufacturing() {
        for (long need : new long[]{1, 7, 31}) for (boolean reverse : List.of(false, true)) for (boolean force : List.of(false, true)) {
            var entries = new ArrayList<>(List.of(entry("use", List.of(input("raw", 1)), Map.of("out", 1L)),
                    entry("forge", List.of(input("metal", 1)), Map.of("tool", 1L)),
                    entry("smelt", List.of(input("ore", 2)), Map.of("metal", 1L))));
            if (reverse) Collections.reverse(entries);
            var stock = new LinkedHashMap<String, Long>(); stock.put("raw", 2L); stock.put("ore", 2 * need);
            var plan = run(entries, Map.of("use#0", BigInteger.TWO), 2, stock, Set.of(), Map.of("tool", need), true, force);
            check(plan != null, "Lost a monotone manufacturing completion");
            check(plan.patternTimesExact().equals(Map.of("use#0", BigInteger.TWO, "forge#0", BigInteger.valueOf(need),
                    "smelt#0", BigInteger.valueOf(need))), "Changed or lost the old count witness");
            check(plan.initialExact().get("ore").equals(BigInteger.valueOf(2 * need)) && plan.seeds().get("tool") == need,
                    "Did not fund the new final seed");
            check(run(entries, Map.of("use#0", BigInteger.TWO), 2, Map.of("raw", 2L, "ore", 2 * need - 1),
                    Set.of(), Map.of("tool", need), true, force) == null, "Invented replenishment stock");
        }
    }

    private static void currentSemantics() {
        var old = Map.of("use#0", BigInteger.ONE);
        var entries = List.of(entry("use", List.of(input("raw", 2)), Map.of("out", 1L)));
        check(run(entries, old, 2, Map.of("raw", 4L), Set.of(), Map.of(), false, true).patternTimesExact().get("use#0").equals(BigInteger.TWO),
                "Old count became a cached conclusion after changed coefficients");
        check(run(entries, old, 2, Map.of("raw", 3L), Set.of(), Map.of(), false, false) == null, "Reused stale resource coefficients");
        check(run(entries, old, 2, Map.of(), Set.of("raw"), Map.of(), false, false) != null, "Lost an explicit external boundary");
        check(run(entries, old, 2, Map.of("raw", 4L), Set.of(), Map.of("out", 1L), true, false) == null,
                "Target seed was not added to the order");

        var returned = new CapturedRecipe.Input<String>(1, List.of(new CapturedRecipe.Candidate<>(
                new CapturedRecipe.Amount<>("catalyst", 1), "catalyst", false)), CapturedRecipe.InputMode.INDIVISIBLE);
        entries = List.of(entry("use", List.of(input("raw", 1), returned), Map.of("out", 1L)));
        check(run(entries, old, 7, Map.of("raw", 7L, "catalyst", 1L), Set.of(), Map.of(), false, true) != null, "Lost returned catalyst");
        check(run(entries, old, 7, Map.of("raw", 7L), Set.of(), Map.of(), false, false) == null, "Net counts invented catalyst startup");

        entries = List.of(entry("use", List.of(input("raw", 1)), Map.of("out", 1L)));
        var large = run(entries, old, Long.MAX_VALUE, Map.of("raw", Long.MAX_VALUE), Set.of(), Map.of(), false, true);
        check(large != null && large.patternTimesExact().get("use#0").equals(BigInteger.valueOf(Long.MAX_VALUE)), "Clamped an unbounded count domain");
        check(run(entries, Map.of("use#0", BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)), 1,
                Map.of("raw", Long.MAX_VALUE), Set.of(), Map.of(), false, false) == null, "Narrowed a mathematical lower bound");
    }

    private static void restrictedFailure() {
        var choice = new CapturedRecipe.Input<String>(1, List.of(candidate("a", 1), candidate("b", 1)), CapturedRecipe.InputMode.INDIVISIBLE);
        var entries = List.of(entry("use", List.of(choice), Map.of("out", 1L)));
        var stock = Map.of("b", 1L);
        check(run(entries, Map.of("use#0", BigInteger.ONE), 1, stock, Set.of(), Map.of(), false, false) == null,
                "Changed an old speculative lower bound silently");
        var budget = budget(1_000_000, 16L << 20);
        check(new CapturedCatalog<>(entries).plan("out", 1, stock, false, false, budget).plan().feasible(),
                "Restricted failure escaped into ordinary solving");
        check(budget.reservedBytes() == 0, "Ordinary retry leaked storage");
        check(run(entries, Map.of("removed#0", BigInteger.ONE), 1, stock, Set.of(), Map.of(), false, false) == null, "Accepted a removed variant");
        var options = new ArrayList<CapturedRecipe.Candidate<String>>();
        for (int i = 0; i < 129; i++) options.add(candidate("k" + i, 1));
        var wide = List.of(entry("use", List.of(new CapturedRecipe.Input<>(1, options, CapturedRecipe.InputMode.INDIVISIBLE)), Map.of("out", 1L)));
        check(run(wide, Map.of("use#0", BigInteger.ONE), 1, Map.of("k0", 1L), Set.of(), Map.of(), false, false) == null,
                "Expanded past the optional scout admission");
        var overflow = new CapturedRecipe.Input<String>(Long.MAX_VALUE, List.of(candidate("unused", 2)), CapturedRecipe.InputMode.INDIVISIBLE);
        var irrelevant = List.of(entry("use", List.of(input("b", 1)), Map.of("out", 1L)), entry("irrelevant", List.of(overflow), Map.of("unneeded", 1L)));
        check(run(irrelevant, Map.of("use#0", BigInteger.ONE), 1, stock, Set.of(), Map.of(), false, false) == null,
                "Unused overflowing variant escaped optional repair");
        budget = budget(1_000_000, 16L << 20);
        check(new CapturedCatalog<>(irrelevant).plan("out", 1, stock, false, false, budget).plan().feasible(),
                "Unused overflow removed a feasible ordinary support");
        check(budget.reservedBytes() == 0, "Overflow handoff retained storage");
    }

    private static void limits() {
        var entries = List.of(entry("use", List.of(input("raw", 1)), Map.of("out", 1L)));
        var old = Map.of("use#0", BigInteger.ONE); var stock = Map.of("raw", 7L);
        for (long bytes : new long[]{256, 2048, 8192, 65536}) {
            var budget = budget(40_000_000, bytes);
            try (var repair = CapturedProposalRepair.create(entries, old, "out", 7, stock, Set.of(), Map.of(), false, false, budget)) {
                if (repair != null) { while (!repair.step()) {} if (repair.result() != null) verify(repair.result(), stock, Set.of()); }
            }
            check(budget.reservedBytes() == 0 && budget.peakBytes() <= bytes, "Optional repair escaped its memory limit"); requests++;
        }
        for (long cap : new long[]{100, 2048, 16_383, 16_384}) {
            var budget = budget(cap, 16L << 20);
            try (var repair = CapturedProposalRepair.create(entries, old, "out", 7, stock, Set.of(), Map.of(), false, false, budget)) {
                if (repair != null) while (!repair.step()) {}
            }
            check(budget.reservedBytes() == 0 && budget.searchWork() < cap, "Repair renewed the original work budget"); requests++;
        }
        for (int steps : new int[]{0, 3, 8, 15}) {
            var cancel = new AtomicBoolean();
            var budget = new PlanningBudget(0, 40_000_000, 16L << 20, cancel::get, System::nanoTime);
            try (var repair = CapturedProposalRepair.create(entries, old, "out", 7, stock, Set.of(), Map.of(), false, false, budget)) {
                check(repair != null, "Repair not admitted");
                for (int i = 0; i < steps; i++) check(!repair.step(), "Cancellation fixture finished early");
                cancel.set(true);
                try { repair.step(); throw new AssertionError("Ignored cancellation"); } catch (CancellationException expected) {}
            }
            check(budget.reservedBytes() == 0, "Cancellation retained repair storage"); requests++;
        }
    }

    private static GraphPlan<String> run(List<CapturedCatalog.Entry<String>> entries, Map<String, BigInteger> previous, long amount,
            Map<String, Long> stock, Set<String> external, Map<String, Long> seeds, boolean preserve, boolean force) {
        var budget = budget(40_000_000, 16L << 20); budget.enableMetrics(); GraphPlan<String> plan;
        try (var repair = CapturedProposalRepair.create(entries, previous, "out", amount, stock, external, seeds, preserve, force, budget)) {
            check(repair != null, "Small repair not admitted");
            while (!repair.step()) {} plan = repair.result();
            if (plan != null) { verify(plan, stock, external); check(plan.amount() == amount, "Changed order amount"); }
        }
        check(budget.reservedBytes() == 0 && budget.searchWork() < 70_000, "Repair leaked or exceeded its lifetime work allowance");
        PlanningCostTraceTest.conserved(budget); requests++; return plan;
    }

    private static void verify(GraphPlan<String> plan, Map<String, Long> stock, Set<String> external) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        plan.initialExact().forEach((key, count) -> check(external.contains(key) || count.compareTo(BigInteger.valueOf(stock.getOrDefault(key, 0L))) <= 0,
                "Repair invented original inventory"));
    }
    private static CapturedRecipe.Candidate<String> candidate(String key, long amount) {
        return new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, amount), null, false);
    }
    private static CapturedRecipe.Input<String> input(String key, long amount) {
        return new CapturedRecipe.Input<>(1, List.of(candidate(key, amount)), CapturedRecipe.InputMode.INDIVISIBLE);
    }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, Map<String, Long> outputs) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs,
                outputs.entrySet().stream().map(e -> new CapturedRecipe.Amount<>(e.getKey(), e.getValue())).toList(), false, false));
    }
    private static PlanningBudget budget(long work, long bytes) { return new PlanningBudget(0, work, bytes, () -> false, System::nanoTime); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
