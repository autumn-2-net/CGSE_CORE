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

/** Fragmented whole-key slots can need manufacturing within a single family. */
public final class CapturedStateReplacementTest {
    private static int requests, checks;
    private static final StateFamily<String> FAMILY = new StateFamily<>() {
        public String id() { return "wear"; }
        public boolean accepts(String key) { return key.startsWith("tool:"); }
        public long capacity(String key) { return accepts(key) ? Long.parseLong(key.substring(5)) : 0; }
        public String nominalAfterOne(String key) { long n = capacity(key); return n <= 1 ? null : "tool:" + (n - 1); }
    };

    public static void main(String[] args) {
        for (int slots : new int[]{3, 5, 9}) for (int copies : new int[]{2, 3})
            for (long amount : new long[]{1, 2, 4}) for (boolean reverse : List.of(false, true))
                for (boolean direct : List.of(false, true)) {
                    var entries = entries(slots, copies, false, reverse);
                    var stock = stock(slots, copies, amount, reverse, true);
                    // Construct a physical witness before asking the solver:
                    // manufacture one long-lived tool, use the resulting full
                    // cohorts, and return all states only after each firing.
                    witness(slots, copies, amount, stock);
                    var budget = budget(40_000_000, 64L << 20); budget.enableMetrics();
                    GraphPlan<String> plan;
                    if (direct) try (var work = new CapturedStatePlanning<>(entries, "product", amount, stock,
                            Set.of(), Map.of(), false, false, budget)) {
                        while (!work.step()) {} plan = work.result();
                        check(work.outcome().status() == CapturedStatePlanning.Status.VERIFIED, "Lost single-family recovery: " + work.outcome());
                    } else plan = new CapturedCatalog<>(entries).plan("product", amount, stock, false, false, budget).plan();
                    check(plan != null && plan.feasible(), "Lost funded fragmented slots: " + budget.diagnostics());
                    replay(plan, entries, stock, amount);
                    check(budget.searchWork() < 150_000 && budget.peakBytes() < (16L << 20), "Small replacement consumed the main allowance");
                    check(budget.reservedBytes() == 0, "Replacement workspace leak");
                    PlanningCostTraceTest.conserved(budget); requests++;
                    if (slots == 3 && copies == 2 && amount == 4 && direct && !reverse) runtime(plan, amount);
                }
        changingStock(); limits();
        System.out.println("CapturedStateReplacementTest: requests=" + requests + "; assertions=" + checks);
    }

    private static void changingStock() {
        for (boolean reverse : List.of(false, true)) for (boolean mixed : List.of(false, true)) {
            var entries = entries(3, 2, mixed, reverse);
            var catalog = new CapturedCatalog<>(entries);
            for (boolean funded : List.of(true, false, true)) {
                var stock = stock(3, 2, 4, reverse, funded);
                var budget = budget(40_000_000, 64L << 20);
                try (var work = catalog.begin("product", 4, stock, true, true, budget)) {
                    while (!work.step()) {}
                    var plan = work.result().plan();
                    check(plan.feasible() == (funded || mixed), "Reused a stale manufactured tool or lost mixed allocation");
                    if (plan.feasible()) replay(plan, entries, stock, 4);
                    else check(!work.result().coverage().complete() && plan.result() != GraphPlan.Result.INFEASIBLE,
                            "Restricted count refinement became a global negative proof");
                }
                check(budget.reservedBytes() == 0, "Warm request retained entity floors"); requests++;
            }
        }
        // An exact final-state seed cannot be satisfied with capacity tokens.
        var entries = entries(3, 2, false, false);
        var stock = stock(3, 2, 4, false, true);
        for (boolean possible : List.of(true, false)) {
            var seeds = Map.of("tool:96", possible ? 4L : 5L);
            var budget = budget(40_000_000, 64L << 20);
            try (var work = new CapturedCatalog<>(entries).begin("product", 4, stock, Set.of(), seeds, true, true, budget)) {
                while (!work.step()) {}
                var plan = work.result().plan();
                check(plan.feasible() == possible, "Lost exact remaining tool-state seed");
                if (possible) {
                    var finalStock = replay(plan, entries, stock, 4);
                    check(finalStock.getOrDefault("tool:96", 0L) >= 4, "Physical seed absent");
                }
            }
            check(budget.reservedBytes() == 0, "Seed recovery leaked"); requests++;
        }
    }

    private static void limits() {
        var entries = entries(3, 2, false, false);
        var stock = stock(3, 2, 4, false, true);
        for (long cap : new long[]{100, 2048, 20_000, 80_000, 120_000, 240_000}) {
            var budget = budget(cap, 64L << 20);
            try (var work = new CapturedStatePlanning<>(entries, "product", 4, stock, Set.of(), Map.of(), false, false, budget)) {
                while (!work.step()) {}
                if (work.result() != null) replay(work.result(), entries, stock, 4);
                check(work.outcome().status() != CapturedStatePlanning.Status.RUNNING, "Lost limited status");
            } catch (PlanningBudget.Exhausted expected) { /* Parent work remains binding. */ }
            check(budget.reservedBytes() == 0 && budget.searchWork() <= cap, "Renewed a retry allowance"); requests++;
        }
        for (long bytes : new long[]{2048, 16_384, 1L << 20}) {
            var budget = budget(40_000_000, bytes);
            try (var work = new CapturedStatePlanning<>(entries, "product", 4, stock, Set.of(), Map.of(), false, false, budget)) {
                while (!work.step()) {}
                if (work.result() != null) replay(work.result(), entries, stock, 4);
            }
            check(budget.reservedBytes() == 0 && budget.peakBytes() <= bytes, "Exceeded or retained workspace"); requests++;
        }
        var cancelled = new AtomicBoolean();
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, cancelled::get, System::nanoTime);
        budget.enableMetrics();
        try (var work = new CapturedStatePlanning<>(entries, "product", 4, stock, Set.of(), Map.of(), false, false, budget)) {
            boolean refined = false;
            while (!work.step()) if (budget.diagnostics().contains("refine_entity_count")) { refined = true; break; }
            check(refined, "Cancellation did not reach a replacement proposal");
            cancelled.set(true);
            try { work.step(); throw new AssertionError("Ignored cancellation"); } catch (CancellationException expected) {}
        }
        check(budget.reservedBytes() == 0, "Cancelled replacement retained workspace"); requests++;
    }

    private static void witness(int slots, int copies, long amount, Map<String, Long> initial) {
        var inventory = new LinkedHashMap<>(initial);
        check(inventory.getOrDefault("metal", 0L) >= 1, "Witness has no forging material");
        inventory.merge("metal", -1L, Long::sum); inventory.merge("tool:100", 1L, Long::sum);
        for (int run = 0; run < amount; run++) {
            String a = "tool:" + (100 - run), b = "tool:" + (90 - run);
            long first = copies * (slots / 2L + 1), second = copies * (slots / 2L);
            check(inventory.getOrDefault(a, 0L) >= first && inventory.getOrDefault(b, 0L) >= second, "Invalid independent cohort witness");
            inventory.merge(a, -first, Long::sum); inventory.merge(b, -second, Long::sum);
            inventory.merge("tool:" + (99 - run), first, Long::sum); inventory.merge("tool:" + (89 - run), second, Long::sum);
            inventory.merge("raw", -1L, Long::sum); inventory.merge("product", 1L, Long::sum);
        }
        check(inventory.get("product") == amount && inventory.values().stream().allMatch(n -> n >= 0), "Independent witness unfunded");
    }

    private static Map<String, Long> replay(GraphPlan<String> plan, List<CapturedCatalog.Entry<String>> sources,
                                             Map<String, Long> stock, long amount) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        var inventory = new LinkedHashMap<String, Long>();
        plan.initialExact().forEach((key, n) -> {
            check(n.signum() >= 0 && n.compareTo(BigInteger.valueOf(stock.getOrDefault(key, 0L))) <= 0, "Invented initial physical entities");
            inventory.put(key, n.longValueExact());
        });
        var cursor = new PlanCursor(plan.steps()); long firings = 0;
        while (cursor.current() != null) {
            var batch = cursor.current(); var recipe = plan.recipes().get(batch.recipe());
            var source = sources.stream().filter(s -> s.binding().equals(recipe.binding())).findFirst().orElseThrow();
            for (long run = 0; run < batch.runs(); run++) {
                long[] filled = new long[source.recipe().inputs().size()]; String[] chosen = new String[filled.length];
                var owed = new LinkedHashMap<String, Long>();
                for (var slot : recipe.slots()) {
                    var input = source.recipe().inputs().get(slot.inputSlot());
                    var candidate = input.candidates().get(0);
                    check(inventory.getOrDefault(slot.key(), 0L) >= slot.amount(), "Reused an entity within a firing");
                    inventory.merge(slot.key(), -slot.amount(), Long::sum); filled[slot.inputSlot()] += slot.amount();
                    check(source.recipe().mixed(input) || chosen[slot.inputSlot()] == null || chosen[slot.inputSlot()].equals(slot.key()), "Split a whole-key slot");
                    chosen[slot.inputSlot()] = slot.key();
                    if (candidate.family() == null) check(candidate.stack().what().equals(slot.key()), "Changed ordinary input");
                    else {
                        check(FAMILY.accepts(slot.key()) && FAMILY.capacity(slot.key()) > 0, "Wrong tool state");
                        String after = FAMILY.nominalAfterOne(slot.key());
                        if (after != null) owed.merge(after, slot.amount(), Long::sum);
                    }
                }
                for (int i = 0; i < filled.length; i++) check(filled[i] == source.recipe().inputs().get(i).multiplier(), "Missing simultaneous slot demand");
                source.recipe().outputs().forEach(output -> owed.merge(output.what(), output.amount(), Long::sum));
                check(owed.equals(recipe.executionOutputs()), "Changed exact returned state");
                owed.forEach((key, n) -> inventory.merge(key, n, Long::sum));
                check(++firings < 100, "Expanded a small entity witness");
            }
            cursor.dispatched(batch.runs());
        }
        check(inventory.getOrDefault("product", 0L) >= amount, "Physical witness misses target"); return inventory;
    }

    private static void runtime(GraphPlan<String> plan, long amount) {
        final class Host implements GraphJobRuntime.Adapter<String> {
            GraphJobRuntime<String> job = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            final List<Map<String, Long>> pending = new ArrayList<>(); long delivered;
            public long capacity(GraphRecipe<String> recipe, long requested) { return requested; }
            public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
                var outputs = new LinkedHashMap<String, Long>();
                recipe.executionOutputs().forEach((key, n) -> outputs.put(key, Math.multiplyExact(n, runs)));
                pending.add(outputs); return GraphJobRuntime.Outcome.ACCEPTED;
            }
            public long deliver(String key, long n) { delivered += n; return n; }
            public long refund(String key, long n) { return n; }
        }
        var host = new Host();
        for (int tick = 0; tick < 100 && host.delivered < amount; tick++) {
            host.job.tick(host, tick * 20L, 64, () -> 0L);
            host.job = new GraphJobRuntime<>(host.job.snapshot()); Collections.reverse(host.pending);
            for (var outputs : host.pending) outputs.forEach((key, n) -> check(host.job.accept(key, n, false) == n, "Lost asynchronous replacement return"));
            host.pending.clear();
        }
        check(host.delivered == amount, "Replacement plan stalled after save/load");
    }

    private static List<CapturedCatalog.Entry<String>> entries(int slots, int copies, boolean mixed, boolean reverse) {
        var inputs = new ArrayList<CapturedRecipe.Input<String>>();
        for (int i = 0; i < slots; i++) inputs.add(new CapturedRecipe.Input<>(copies, List.of(new CapturedRecipe.Candidate<>(
                new CapturedRecipe.Amount<>("tool:100", 1), "tool:99", false, false, FAMILY)),
                mixed ? CapturedRecipe.InputMode.MIXED : CapturedRecipe.InputMode.INDIVISIBLE));
        inputs.add(ordinary("raw")); if (reverse) Collections.reverse(inputs);
        var entries = new ArrayList<>(List.of(entry("use", inputs, "product"), entry("forge", List.of(ordinary("metal")), "tool:100")));
        if (reverse) Collections.reverse(entries); return entries;
    }
    private static LinkedHashMap<String, Long> stock(int slots, int copies, long amount, boolean reverse, boolean funded) {
        var keys = new ArrayList<>(List.of("tool:100", "tool:90", "raw", "metal")); if (reverse) Collections.reverse(keys);
        var stock = new LinkedHashMap<String, Long>();
        for (String key : keys) stock.put(key, key.equals("raw") ? amount : key.equals("metal") ? funded ? 1L : 0L : copies * (slots / 2L) + copies - 1);
        return stock;
    }
    private static CapturedRecipe.Input<String> ordinary(String key) {
        return new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false)), CapturedRecipe.InputMode.INDIVISIBLE);
    }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, String output) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>(output, 1)), false, false));
    }
    private static PlanningBudget budget(long work, long bytes) { return new PlanningBudget(0, work, bytes, () -> false, System::nanoTime); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
