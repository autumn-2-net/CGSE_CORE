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
import java.util.concurrent.atomic.AtomicBoolean;

/** A forced material order can still have more than one physical tool assignment. */
public final class CapturedStateChoiceTest {
    private static int checks, cases;
    private static long maxWork, maxBytes;

    private record Rule(String id) implements StateFamily<String> {
        public boolean accepts(String key) {
            if (id.startsWith("read-")) return key.equals(id.substring(5));
            if (id.equals("hold")) return key.equals("anchor");
            if (key.equals("anchor")) return id.equals("warm");
            return key.startsWith("red:") || key.equals("blue") || key.startsWith("worn-");
        }
        public long capacity(String key) { return accepts(key) ? Long.MAX_VALUE : 0; }
        public String nominalAfterOne(String key) { return id.equals("use") && !key.startsWith("worn-") ? "worn-" + key : key; }
    }

    public static void main(String[] args) {
        for (int colors : new int[]{1, 2, 3}) for (int permutation = 0; permutation < 6; permutation++)
            for (long n : new long[]{1, 7, 1_000_000_000L, Long.MAX_VALUE}) {
                var entries = entries(colors, 1, false, false, permutation);
                var stock = stock(colors, 1, false, n, permutation);
                var plan = solve(entries, stock, n, Map.of("red:0", 1L), false, false);
                if (n == 7 && permutation == 0) runtime(plan, n);
            }
        for (int tools : new int[]{2, 3}) for (boolean mixed : List.of(false, true))
            for (int permutation = 0; permutation < 6; permutation++) {
                var entries = entries(1, tools, mixed, true, permutation);
                var plan = solve(entries, stock(1, tools, true, 7, permutation), 7,
                        Map.of("red:0", (long) tools), false, false);
                if (permutation == 0) runtime(plan, 7);
            }
        boundaries(); cancelled();
        System.out.println("CapturedStateChoiceTest: cases=" + cases + "; assertions=" + checks +
                "; max_work=" + maxWork + "; max_bytes=" + maxBytes);
    }

    private static GraphPlan<String> solve(List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock,
            long n, Map<String, Long> seeds, boolean preserve, boolean force) {
        var budget = budget(40_000_000); budget.enableMetrics(); GraphPlan<String> plan;
        try (var work = new CapturedStatePlanning<>(entries, "product", n, stock, Set.of(), seeds, preserve, force, budget)) {
            while (!work.step()) {}
            check(work.result() != null, "Lost a known physical assignment: " + work.outcome() + "; " + budget.diagnostics());
            plan = work.result();
        }
        check(budget.reservedBytes() == 0, "Choice feedback leaked");
        check(budget.searchWork() < 1_000_000 && budget.peakBytes() < (32L << 20), "Small restoration inflated");
        PlanningCostTraceTest.conserved(budget); verify(plan, entries, stock, n);
        maxWork = Math.max(maxWork, budget.searchWork()); maxBytes = Math.max(maxBytes, budget.peakBytes()); cases++;
        return plan;
    }

    private static void boundaries() {
        var entries = entries(1, 1, false, false, 0);
        var stock = stock(1, 1, false, 7, 0);
        stock.put("product", 7L);
        solve(entries, stock, 7, Map.of("red:0", 1L), false, true);
        stock.remove("product");
        // Requiring both exact fresh tools is impossible: the required use
        // always changes one. Preference scores cannot waive an exact seed.
        var budget = budget(40_000_000);
        try (var work = new CapturedStatePlanning<>(entries, "product", 7, stock, Set.of(),
                Map.of("red:0", 1L, "blue", 1L), false, false, budget)) {
            while (!work.step()) {}
            check(work.result() == null, "Choice feedback discarded a physical seed");
        }
        check(budget.reservedBytes() == 0, "Rejected exact seed leaked");
        // Warm all tools, change one, then read both original keys. Material
        // gates force the change before either read; no two-tool witness exists.
        var impossible = new ArrayList<>(entries);
        impossible.set(2, entry("read0", List.of(tool("read-red:0", 1, false), ordinary("part")), "before-blue"));
        impossible.add(entry("read-blue", List.of(tool("read-blue", 1, false), ordinary("before-blue")), "product"));
        budget = budget(40_000_000); budget.enableMetrics();
        try (var work = new CapturedStatePlanning<>(impossible, "product", 1, stock(1, 1, false, 1, 0), Set.of(), Map.of(), false, false, budget)) {
            while (!work.step()) {}
            check(work.result() == null, "Preservation preferences invented a third tool");
            check(budget.diagnostics().contains("retry_entity_choices"), "Did not exercise conflicting preferences");
        }
        check(budget.reservedBytes() == 0 && budget.searchWork() < 500_000, "Unrepairable preferences renewed work");
        budget = budget(40_000_000);
        try (var work = new CapturedCatalog<>(impossible).begin("product", 1, stock(1, 1, false, 1, 0), false, false, budget)) {
            while (!work.step()) {}
            check(!work.result().plan().feasible() && !work.result().coverage().complete() &&
                    work.result().plan().result() != GraphPlan.Result.INFEASIBLE, "Private preference failure escaped its scope");
        }
        check(budget.reservedBytes() == 0, "Public failure leaked");
        budget = budget(40_000_000);
        try (var work = new CapturedCatalog<>(entries).begin("product", 7, stock, false, true, budget)) {
            while (!work.step()) {}
            check(work.result().plan().feasible(), "Choice restoration not connected to public requests");
            verify(work.result().plan(), entries, stock, 7);
        }
        check(budget.reservedBytes() == 0, "Public success leaked");
        for (long cap : new long[]{2_000, 16_000, 48_000}) {
            budget = budget(cap);
            try (var work = new CapturedStatePlanning<>(entries, "product", 7, stock, Set.of(), Map.of(), false, false, budget)) {
                try { while (!work.step()) {} } catch (PlanningBudget.Exhausted limit) { /* Inconclusive. */ }
                if (work.result() != null) verify(work.result(), entries, stock, 7);
            }
            check(budget.reservedBytes() == 0 && budget.searchWork() <= cap + 1, "Choice restart renewed parent budget");
        }
    }

    private static void cancelled() {
        var cancel = new AtomicBoolean();
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, cancel::get, System::nanoTime); budget.enableMetrics();
        boolean seen = false;
        try (var work = new CapturedStatePlanning<>(entries(1, 1, false, false, 0), "product", 7,
                stock(1, 1, false, 7, 0), Set.of(), Map.of(), false, false, budget)) {
            while (!work.step()) {
                if (budget.diagnostics().contains("retry_entity_choices")) { cancel.set(true); break; }
            }
            try { work.step(); } catch (java.util.concurrent.CancellationException expected) { seen = true; }
        }
        check(cancel.get() && seen && budget.reservedBytes() == 0, "Cancelled choice replay retained inventory / scores");
    }

    private static List<CapturedCatalog.Entry<String>> entries(int colors, int tools, boolean mixed, boolean paired, int permutation) {
        var entries = new ArrayList<CapturedCatalog.Entry<String>>();
        entries.add(entry("warm", List.of(tool("warm", (colors + 1 + (paired ? 1 : 0)) * tools, true), ordinary("raw")), "gate"));
        var use = new ArrayList<>(List.of(tool("use", tools, mixed), ordinary("gate")));
        if (paired) use.add(tool("hold", tools, false));
        if (permutation % 2 != 0) Collections.reverse(use);
        entries.add(entry("use", use, "part"));
        for (int color = 0; color < colors; color++) entries.add(entry("read" + color,
                List.of(tool("read-red:" + color, tools, mixed), ordinary(color == 0 ? "part" : "part" + color)),
                color == colors - 1 ? "product" : "part" + (color + 1)));
        Collections.rotate(entries, permutation / 2); if (permutation % 2 != 0) Collections.reverse(entries);
        return entries;
    }

    private static LinkedHashMap<String, Long> stock(int colors, int tools, boolean paired, long n, int permutation) {
        var keys = new ArrayList<String>(); for (int i = 0; i < colors; i++) keys.add("red:" + i);
        keys.add("blue"); if (paired) keys.add("anchor");
        if (permutation % 2 != 0) Collections.reverse(keys);
        var stock = new LinkedHashMap<String, Long>(); for (String key : keys) stock.put(key, (long) tools);
        stock.put("raw", n); return stock;
    }

    private static void verify(GraphPlan<String> plan, List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock, long n) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        var inventory = new LinkedHashMap<String, BigInteger>();
        plan.initialExact().forEach((key, count) -> {
            check(count.compareTo(BigInteger.valueOf(stock.getOrDefault(key, 0L))) <= 0, "Invented initial stock"); inventory.put(key, count);
        });
        var times = new LinkedHashMap<String, BigInteger>();
        plan.patternTimesExact().forEach((id, count) -> times.merge(plan.recipes().get(id).binding(), count, BigInteger::add));
        for (var source : entries) check(BigInteger.valueOf(n).equals(times.get(source.binding())), "Changed required firing counts");
        for (var recipe : plan.recipes().values()) {
            var source = entries.stream().filter(e -> e.binding().equals(recipe.binding())).findFirst().orElseThrow();
            var outputs = new LinkedHashMap<String, Long>(); long[] filled = new long[source.recipe().inputs().size()];
            var exactKeys = new LinkedHashMap<Integer, String>();
            for (var slot : recipe.slots()) {
                var input = source.recipe().inputs().get(slot.inputSlot()); var candidate = input.candidates().get(0);
                filled[slot.inputSlot()] += slot.amount();
                String previous = exactKeys.put(slot.inputSlot(), slot.key());
                check(source.recipe().mixed(input) || previous == null || previous.equals(slot.key()), "Split an indivisible tool slot");
                if (candidate.family() instanceof Rule rule) {
                    check(rule.accepts(slot.key()), "Assigned an ineligible entity");
                    String returned = rule.id().equals("use") && !slot.key().startsWith("worn-") ? "worn-" + slot.key() : slot.key();
                    outputs.merge(returned, slot.amount(), Long::sum);
                } else check(candidate.stack().what().equals(slot.key()), "Changed ordinary source input");
            }
            for (int i = 0; i < filled.length; i++) check(filled[i] == source.recipe().inputs().get(i).multiplier(), "Changed simultaneous demand");
            source.recipe().outputs().forEach(out -> outputs.merge(out.what(), out.amount(), Long::sum));
            check(outputs.equals(recipe.executionOutputs()), "Changed physical source returns");
        }
        if (n > 32) return;
        var cursor = new PlanCursor(plan.steps()); int fired = 0;
        while (cursor.current() != null) {
            var batch = cursor.current(); var recipe = plan.recipes().get(batch.recipe());
            for (long i = 0; i < batch.runs(); i++) {
                for (var slot : recipe.slots()) {
                    var available = inventory.getOrDefault(slot.key(), BigInteger.ZERO); var needed = BigInteger.valueOf(slot.amount());
                    check(available.compareTo(needed) >= 0, "Spent an unavailable tool / ordinary gate"); inventory.put(slot.key(), available.subtract(needed));
                }
                recipe.executionOutputs().forEach((key, count) -> inventory.merge(key, BigInteger.valueOf(count), BigInteger::add));
                check(++fired < 1000, "Expanded small physical witness");
            }
            cursor.dispatched(batch.runs());
        }
        check(inventory.getOrDefault("product", BigInteger.ZERO).compareTo(BigInteger.valueOf(n)) >= 0, "Request not fulfilled");
    }

    private static void runtime(GraphPlan<String> plan, long n) {
        final class Host implements GraphJobRuntime.Adapter<String> {
            GraphJobRuntime<String> job = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            final Map<String, Long> physical = new LinkedHashMap<>(plan.initial());
            final List<Map<String, Long>> pending = new ArrayList<>(); long delivered;
            public long capacity(GraphRecipe<String> recipe, long requested) { return requested; }
            public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
                inputs.forEach((key, count) -> {
                    check(physical.getOrDefault(key, 0L) >= count, "Used a tool still in flight");
                    physical.merge(key, -count, Long::sum);
                });
                var outputs = new LinkedHashMap<String, Long>();
                recipe.executionOutputs().forEach((key, count) -> outputs.put(key, Math.multiplyExact(count, runs)));
                pending.add(outputs); return GraphJobRuntime.Outcome.ACCEPTED;
            }
            public long deliver(String key, long count) { delivered += count; return count; }
            public long refund(String key, long count) { return count; }
        }
        var host = new Host();
        for (int tick = 0; tick < 1000 && host.delivered < n; tick++) {
            host.job.tick(host, tick * 20L, 8, () -> 0L); host.job = new GraphJobRuntime<>(host.job.snapshot());
            if (tick % 3 != 2) continue;
            Collections.reverse(host.pending);
            for (var output : host.pending) output.forEach((key, count) -> {
                host.physical.merge(key, count, Long::sum);
                check(host.job.accept(key, count, false) == count, "Lost actual return after save/load");
            });
            host.pending.clear();
        }
        check(host.delivered == n, "Reassigned program stalled at runtime");
    }

    private static CapturedRecipe.Input<String> ordinary(String key) {
        return new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false)), CapturedRecipe.InputMode.INDIVISIBLE);
    }
    private static CapturedRecipe.Input<String> tool(String id, int amount, boolean mixed) {
        var family = new Rule(id); String key = id.equals("hold") ? "anchor" : id.startsWith("read-") ? id.substring(5) : "red:0";
        return new CapturedRecipe.Input<>(amount, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1),
                family.nominalAfterOne(key), false, false, family)), mixed ? CapturedRecipe.InputMode.MIXED : CapturedRecipe.InputMode.INDIVISIBLE);
    }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, String output) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>(output, 1)), false, false));
    }
    private static PlanningBudget budget(long cap) { return new PlanningBudget(0, cap, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
