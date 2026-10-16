// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Discovery must yield before the optional state arm exhausts its parent request. */
public final class CapturedStatePreparationTest {
    private static int checks;
    private record Input(List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock, Set<String> external) {}
    private record UnusedFamily(String id) implements StateFamily<String> {
        public boolean accepts(String key) { throw new AssertionError("Discovery invoked state algebra"); }
        public long capacity(String key) { throw new AssertionError("Discovery unfolded capacity"); }
        public String nominalAfterOne(String key) { throw new AssertionError("Discovery unfolded a nominal state"); }
    }

    public static void main(String[] args) {
        for (String shape : List.of("stock", "external", "sources", "outputs", "slots", "candidates", "families")) {
            var input = input(shape);
            localAllowance(shape, input); memory(shape, input); cancellation(shape, input); timeout(shape, input);
        }
        System.out.println("CapturedStatePreparationTest: shapes=7; assertions=" + checks);
    }

    private static void localAllowance(String shape, Input input) {
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, () -> false, System::nanoTime);
        budget.enableMetrics();
        try (var work = work(input, budget)) {
            check(budget.compilationWork() == 0 && budget.searchWork() == 0 && budget.reservedBytes() == 2048, "Constructor eagerly scanned " + shape);
            int steps = 0;
            boolean finished;
            do {
                long before = budget.nodes(); finished = work.step();
                check(budget.nodes() - before <= 1, "Unbounded discovery slice for " + shape);
                check(++steps < 5000, "Discovery failed to consume its lifetime allowance for " + shape);
            } while (!finished);
            check(work.result() == null && work.outcome().status() == CapturedStatePlanning.Status.LIMITED &&
                    work.outcome().reason() == CapturedStatePlanning.Reason.LOCAL_WORK_LIMIT, "Partial discovery became a theorem for " + shape);
            check(budget.compilationWork() == 1024 && budget.searchWork() == 0, "Local state preparation consumed the parent search account for " + shape);
        }
        check(budget.reservedBytes() == 0, "Limited discovery leaked " + shape); PlanningCostTraceTest.conserved(budget);
    }

    private static void memory(String shape, Input input) {
        var budget = new PlanningBudget(0, 40_000_000, 4096, () -> false, System::nanoTime);
        try (var work = work(input, budget)) {
            int steps = 0;
            while (!work.step()) check(++steps < 1000, "Workspace admission failed to stop " + shape);
            check(work.result() == null && work.outcome().reason() == CapturedStatePlanning.Reason.WORKSPACE_DECLINED, "Workspace decline mislabeled for " + shape);
            check(budget.peakBytes() <= 4096, "Allocated discovery containers before admission for " + shape);
        }
        check(budget.reservedBytes() == 0, "Workspace decline leaked " + shape);
    }

    private static void cancellation(String shape, Input input) {
        var cancelled = new AtomicBoolean();
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, cancelled::get, System::nanoTime);
        try (var work = work(input, budget)) {
            for (int i = 0; i < 37; i++) check(!work.step(), "Short discovery unexpectedly finished " + shape);
            long before = budget.nodes(); cancelled.set(true);
            try { work.step(); throw new AssertionError("Ignored cancellation during " + shape); }
            catch (CancellationException expected) { check(budget.nodes() == before && work.result() == null, "Cancelled discovery kept running " + shape); }
        }
        check(budget.reservedBytes() == 0, "Cancelled discovery leaked " + shape);
    }

    private static void timeout(String shape, Input input) {
        var now = new AtomicLong();
        var budget = new PlanningBudget(1, 40_000_000, 64L << 20, () -> false, now::get);
        try (var work = work(input, budget)) {
            for (int i = 0; i < 37; i++) check(!work.step(), "Short discovery unexpectedly finished " + shape);
            long before = budget.nodes(); now.set(2_000_000);
            try { work.step(); throw new AssertionError("Ignored timeout during " + shape); }
            catch (PlanningBudget.Exhausted expected) {
                check(expected.limit() == PlanningBudget.Limit.TIMEOUT && budget.nodes() == before && work.result() == null, "Timeout changed meaning for " + shape);
            }
        }
        check(budget.reservedBytes() == 0, "Timed out discovery leaked " + shape);
    }

    private static CapturedStatePlanning<String> work(Input input, PlanningBudget budget) {
        return new CapturedStatePlanning<>(input.entries(), "product", 1, input.stock(), input.external(), Map.of(), false, false, budget, 1024);
    }

    private static Input input(String shape) {
        var entries = new ArrayList<CapturedCatalog.Entry<String>>();
        var stock = new LinkedHashMap<String, Long>();
        var external = new LinkedHashSet<String>();
        var inputs = new ArrayList<CapturedRecipe.Input<String>>();
        var outputs = new ArrayList<CapturedRecipe.Amount<String>>();
        var choices = new ArrayList<CapturedRecipe.Candidate<String>>();
        for (int i = 0; i < 10_000; i++) {
            String key = "key:" + i;
            switch (shape) {
                case "stock" -> stock.put(key, 1L);
                case "external" -> external.add(key);
                case "sources" -> entries.add(CapturedIndexReuseTest.entry("source:" + i, List.of(), key));
                case "outputs" -> outputs.add(new CapturedRecipe.Amount<>(key, 1));
                case "slots" -> inputs.add(CapturedIndexReuseTest.in(key));
                case "candidates" -> choices.add(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), "return:" + i, false));
                case "families" -> choices.add(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("same-tool", 1), null, false, false, new UnusedFamily(key)));
                default -> throw new AssertionError(shape);
            }
        }
        if (!choices.isEmpty()) inputs.add(new CapturedRecipe.Input<>(1, choices, CapturedRecipe.InputMode.INDIVISIBLE));
        if (outputs.isEmpty()) outputs.add(new CapturedRecipe.Amount<>("product", 1));
        entries.add(new CapturedCatalog.Entry<>("source", "source", new CapturedRecipe<>(inputs, outputs, false, false)));
        return new Input(List.copyOf(entries), Collections.unmodifiableMap(stock), Collections.unmodifiableSet(external));
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
