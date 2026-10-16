// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Simultaneous eligibility deficits must allow manufacturing for another slot. */
public final class CapturedStateDeficitTest {
    private static int cases;
    private record Rule(String id) implements StateFamily<String> {
        public boolean accepts(String key) {
            return switch (id) { case "A" -> key.equals("X") || key.equals("Y"); case "B" -> key.equals("X"); default -> key.equals("Z"); };
        }
        public long capacity(String key) { return accepts(key) ? 100 : 0; }
        public String nominalAfterOne(String key) { return key; }
    }

    public static void main(String[] args) {
        for (boolean reverse : List.of(false, true)) for (boolean slots : List.of(false, true))
            for (boolean stocks : List.of(false, true)) for (boolean mixed : List.of(false, true))
                for (int tools : List.of(1, 2)) for (boolean direct : List.of(false, true)) {
                    var entries = entries(reverse, slots, tools, mixed);
                    var stock = stock(stocks, tools, 3);
                    var budget = budget(40_000_000); budget.enableMetrics();
                    GraphPlan<String> plan;
                    if (direct) try (var work = new CapturedStatePlanning<>(entries, "out", 3, stock, Set.of(), Map.of(), false, false, budget)) {
                        while (!work.step()) {} plan = work.result();
                    } else plan = new CapturedCatalog<>(entries).plan("out", 3, stock, false, false, budget).plan();
                    check(plan != null && plan.feasible(), "Lost funded entity matching: " + budget.diagnostics());
                    replay(plan, entries, stock, 3);
                    check(budget.searchWork() < 150_000, "Small deficit consumed the main budget");
                    check(budget.reservedBytes() == 0, "Deficit/proposal workspace leak");
                    PlanningCostTraceTest.conserved(budget); cases++;
                }
        staleStock(); limits();
        System.out.println("CapturedStateDeficitTest: requests=" + cases);
    }

    private static void staleStock() {
        var entries = entries(false, true, 1, false);
        var catalog = new CapturedCatalog<>(entries);
        for (boolean funded : List.of(true, false, true)) {
            var stock = stock(false, 1, 1);
            if (!funded) stock.remove("metal");
            var budget = budget(40_000_000);
            var plan = catalog.plan("out", 1, stock, true, true, budget).plan();
            check(plan != null && plan.feasible() == funded, "Reused a stale tool or invented a replacement");
            if (funded) replay(plan, entries, stock, 1);
            check(budget.reservedBytes() == 0, "Warm request leaked"); cases++;
        }
        var budget = budget(40_000_000);
        try (var work = new CapturedStatePlanning<>(entries, "out", 1, stock(false, 1, 1), Set.of(),
                Map.of("X", 1L, "Y", 1L, "Z", 1L), true, true, budget)) {
            while (!work.step()) {}
            check(work.result() != null && work.result().feasible(), "Lost exact final tool seeds");
            replay(work.result(), entries, stock(false, 1, 1), 1);
        }
        check(budget.reservedBytes() == 0, "Seed request leaked"); cases++;
    }

    private static void limits() {
        for (long work : List.of(100L, 2048L, 20_000L)) {
            var budget = budget(work);
            try (var task = new CapturedStatePlanning<>(entries(false, true, 1, false), "out", 1,
                    stock(false, 1, 1), Set.of(), Map.of(), false, false, budget)) {
                while (!task.step()) {}
                check(task.result() == null || task.result().feasible(), "Restricted failure became negative proof");
            } catch (PlanningBudget.Exhausted expected) { /* Whole-request cap is still binding. */ }
            check(budget.searchWork() <= work && budget.reservedBytes() == 0, "Renewed or leaked a work allowance");
        }
        var cancelled = new AtomicBoolean();
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, cancelled::get, System::nanoTime);
        try (var task = new CapturedStatePlanning<>(entries(false, true, 1, false), "out", 1,
                stock(false, 1, 1), Set.of(), Map.of(), false, false, budget)) {
            task.step(); cancelled.set(true);
            try { task.step(); throw new AssertionError("Ignored cancellation"); } catch (CancellationException expected) {}
        }
        check(budget.reservedBytes() == 0, "Cancelled deficit work leaked");
    }

    private static void replay(GraphPlan<String> plan, List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock, long amount) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        // Independent primitive execution checks the original captured inputs,
        // eligibility, all simultaneous ownership, exact returns and inventory.
        var inventory = new LinkedHashMap<>(stock);
        var cursor = new PlanCursor(plan.steps()); int fired = 0;
        while (cursor.current() != null) {
            var batch = cursor.current(); var recipe = plan.recipes().get(batch.recipe());
            var source = entries.stream().filter(e -> e.binding().equals(recipe.binding())).findFirst().orElseThrow();
            for (long run = 0; run < batch.runs(); run++) {
                var output = new LinkedHashMap<String, Long>();
                for (var value : source.recipe().outputs()) output.merge(value.what(), value.amount(), Long::sum);
                long[] filled = new long[source.recipe().inputs().size()];
                for (var slot : recipe.slots()) {
                    check(inventory.getOrDefault(slot.key(), 0L) >= slot.amount(), "Reused an entity simultaneously");
                    inventory.merge(slot.key(), -slot.amount(), Long::sum);
                    var input = source.recipe().inputs().get(slot.inputSlot());
                    var candidate = input.candidates().get(0); filled[slot.inputSlot()] += slot.amount();
                    if (candidate.family() == null) check(candidate.stack().what().equals(slot.key()), "Changed an ordinary input");
                    else {
                        check(candidate.family().accepts(slot.key()), "Wrong physical tool for a slot");
                        output.merge(slot.key(), slot.amount(), Long::sum);
                    }
                }
                for (int i = 0; i < filled.length; i++) check(filled[i] == source.recipe().inputs().get(i).multiplier(), "Incomplete source firing");
                check(output.equals(recipe.outputs()), "Changed original source outputs");
                output.forEach((k, n) -> inventory.merge(k, n, Long::sum));
                check(++fired < 100, "Expanded a small witness unexpectedly");
            }
            cursor.dispatched(batch.runs());
        }
        check(inventory.getOrDefault("out", 0L) >= amount, "Missing physical target");
    }

    private static List<CapturedCatalog.Entry<String>> entries(boolean reverse, boolean slots, int tools, boolean mixed) {
        var inputs = new ArrayList<>(List.of(tool("A", "X", tools, mixed), tool("B", "X", tools, mixed), raw("mid")));
        if (slots) Collections.reverse(inputs);
        var entries = new ArrayList<>(List.of(entry("gate", List.of(tool("C", "Z", tools, mixed), raw("raw")), "mid"),
                entry("pair", inputs, "out"), entry("forge", List.of(raw("metal")), "Y")));
        if (reverse) Collections.reverse(entries); return entries;
    }
    private static LinkedHashMap<String, Long> stock(boolean reverse, int tools, int amount) {
        var keys = new ArrayList<>(List.of("X", "Z", "raw", "metal")); if (reverse) Collections.reverse(keys);
        var stock = new LinkedHashMap<String, Long>();
        for (String key : keys) stock.put(key, (long) (key.equals("raw") ? amount : tools)); return stock;
    }
    private static CapturedRecipe.Input<String> tool(String family, String key, int n, boolean mixed) {
        return new CapturedRecipe.Input<>(n, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), key,
                false, false, new Rule(family))), mixed ? CapturedRecipe.InputMode.MIXED : CapturedRecipe.InputMode.INDIVISIBLE);
    }
    private static CapturedRecipe.Input<String> raw(String key) {
        return new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false)), CapturedRecipe.InputMode.INDIVISIBLE);
    }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, String output) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>(output, 1)), false, false));
    }
    private static PlanningBudget budget(long work) { return new PlanningBudget(0, work, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
