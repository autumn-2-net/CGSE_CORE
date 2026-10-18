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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Whole-word recovery must be able to reroute simultaneous entity assignments. */
public final class CapturedStateMatchingCostsTest {
    private static int checks, cases, optimizedCases, combinedCases;
    private static long maxWork, maxBytes;
    private record Fixture(List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock,
                           int n, int mode, long cohort, long amount, boolean manufactured) {}
    private record Rule(String id, int mode) implements StateFamily<String> {
        public boolean accepts(String key) {
            return switch (id) {
                case "warm" -> key.startsWith("a") || key.startsWith("b") || key.equals("c");
                case "u" -> key.startsWith("b") || key.equals("c");
                case "v" -> (key.startsWith("a") || key.startsWith("b")) && (mode != 2 || durability(key) > 1);
                case "read-c" -> key.equals("c");
                default -> mode == 2 ? key.startsWith("a") || key.startsWith("b") : key.equals("a");
            };
        }
        public long capacity(String key) {
            if (!accepts(key)) return 0;
            if (id.equals("v") || id.equals("u") && mode == 1) return 1;
            if (id.equals("read") && mode == 2) return durability(key);
            return id.equals("u") && key.equals("c") ? Long.MAX_VALUE / 2 : Long.MAX_VALUE;
        }
        public String nominalAfterOne(String key) {
            if (id.equals("v")) return mode == 2 ? key.substring(0, 1) + ":1" : null;
            if (id.equals("u") && mode == 1) return null;
            if (id.equals("read") && mode == 2) return durability(key) == 1 ? null : key.substring(0, 1) + ":" + (durability(key) - 1);
            return key;
        }
    }

    public static void main(String[] args) throws Exception {
        for (int mode : new int[]{0, 1}) for (int n : new int[]{1, 2, 7, 31}) for (int order = 0; order < 12; order++)
            solve(fixture(n, mode, 1, true, order, false), true, Map.of(), false);
        for (int n : new int[]{2, 7, 31}) for (int order = 0; order < 4; order++)
            solve(fixture(n, 2, 1, true, order, false), true, Map.of(), false);
        for (int mode : new int[]{0, 1, 2}) {
            var f = fixture(2, mode, 3, true, 0, false); runtime(solve(f, false, Map.of(), false), f.amount());
        }
        solve(fixture(1, 0, Long.MAX_VALUE / 4, true, 0, false), false, Map.of(), false);
        // Whole-key groups retain the original exact allocator, with a source
        // order that has a literal positive witness without splitting a key.
        solve(fixture(2, 0, 2, false, 1, false), true, Map.of("a", 2L), false);
        var f = fixture(2, 0, 1, true, 0, true); solve(f, true, Map.of("a", 1L), false);
        var stocked = new LinkedHashMap<>(f.stock()); stocked.put("product", f.amount());
        solve(new Fixture(f.entries(), stocked, f.n(), f.mode(), f.cohort(), f.amount(), f.manufactured()), true, Map.of("a", 1L), true);
        boundaries(); handoff(); cancelled();
        check(optimizedCases > 0 && combinedCases > 0, "Joint / quantitative compound recovery was not exercised");
        System.out.println("CapturedStateMatchingCostsTest: cases=" + cases + "; assertions=" + checks + "; optimized_cases=" + optimizedCases +
                "; combined_cases=" + combinedCases + "; max_work=" + maxWork + "; max_bytes=" + maxBytes);
    }

    private static GraphPlan<String> solve(Fixture f, boolean publicEntry, Map<String, Long> seeds, boolean force) {
        oracle(f); var budget = budget(40_000_000); budget.enableMetrics(); GraphPlan<String> plan;
        if (publicEntry) {
            try (var work = new CapturedCatalog<>(f.entries()).begin("product", f.amount(), f.stock(), Set.of(), seeds, false, force, budget)) {
                while (!work.step()) {} plan = work.result().plan();
                check(plan.feasible() && !work.result().coverage().complete(), "Lost joint assignment: " + plan.result() + "; " + budget.diagnostics());
            }
        } else {
            try (var work = new CapturedStatePlanning<>(f.entries(), "product", f.amount(), f.stock(), Set.of(), seeds, false, force, budget)) {
                while (!work.step()) {} plan = work.result(); check(plan != null, "Lost exact allocation: " + work.outcome() + "; " + budget.diagnostics());
            }
        }
        check(budget.reservedBytes() == 0 && budget.searchWork() < 500_000 && budget.peakBytes() < (48L << 20), "Joint optimization leaked / inflated cost");
        PlanningCostTraceTest.conserved(budget);
        if (budget.diagnostics().contains("state_matching@")) { optimizedCases++; if (budget.diagnostics().contains("feedback=quantified_uses")) combinedCases++; }
        maxWork = Math.max(maxWork, budget.searchWork()); maxBytes = Math.max(maxBytes, budget.peakBytes()); cases++;
        verify(plan, f, seeds); return plan;
    }

    private static Fixture fixture(int n, int mode, long cohort, boolean mixed, int order, boolean manufacture) {
        String a = mode == 2 ? "a:" + (n + 1L) * 10 : "a", b = mode == 2 ? "b:" + (n + 1L) : "b";
        long pairs = mode == 2 ? 1 : n, amount = mode == 2 ? (n + 1L) * 10 + 1 : n, cCount = mode == 1 ? n : 1;
        var slots = new ArrayList<>(List.of(tool("u", b, cohort, mixed, mode), tool("v", a, cohort, mixed, mode), ordinary("gate", 1)));
        if ((order & 1) != 0) Collections.reverse(slots);
        var entries = new ArrayList<>(List.of(entry("warm", List.of(tool("warm", a, Math.multiplyExact(pairs + cCount + 1, cohort), true, mode), ordinary("raw", 1)), "gate", pairs),
                entry("pair", slots, "part", mode == 2 ? amount : 1), entry("read", List.of(tool("read", a, cohort, mixed, mode), ordinary("part", 1)), "product", 1)));
        if (manufacture) entries.add(entry("build", List.of(ordinary("metal", 1)), "c", Math.multiplyExact(cCount, cohort)));
        Collections.rotate(entries, order / 4); var keys = new ArrayList<>(List.of(b, a, "c")); Collections.rotate(keys, order / 2);
        var stock = new LinkedHashMap<String, Long>();
        for (String key : keys) stock.put(key, Math.multiplyExact(key.equals(b) ? pairs : key.equals("c") ? cCount : 1, cohort));
        if (manufacture) { stock.remove("c"); stock.put("metal", 1L); }
        stock.put("raw", 1L); return new Fixture(List.copyOf(entries), stock, n, mode, cohort, amount, manufacture);
    }

    private static void oracle(Fixture f) {
        var inventory = exact(f.stock()); long pairs = f.mode() == 2 ? 1 : f.n();
        String a = f.mode() == 2 ? "a:" + (f.n() + 1L) * 10 : "a", b = f.mode() == 2 ? "b:" + (f.n() + 1L) : "b";
        if (f.manufactured()) { take(inventory, "metal", 1); add(inventory, "c", Math.multiplyExact(f.mode() == 1 ? f.n() : 1, f.cohort())); }
        take(inventory, "raw", 1); add(inventory, "gate", pairs);
        for (int run = 0; run < pairs; run++) {
            take(inventory, "gate", 1); take(inventory, "c", f.cohort()); if (f.mode() != 1) add(inventory, "c", f.cohort());
            take(inventory, b, f.cohort()); if (f.mode() == 2) add(inventory, "b:1", f.cohort()); add(inventory, "part", f.mode() == 2 ? f.amount() : 1);
        }
        for (long run = 0; run < f.amount(); run++) {
            String key = f.mode() != 2 ? a : run + 1 == f.amount() ? "b:1" : "a:" + (f.amount() - run - 1);
            take(inventory, key, f.cohort()); if (f.mode() != 2) add(inventory, key, f.cohort());
            else if (durability(key) > 1) add(inventory, key.substring(0, 1) + ":" + (durability(key) - 1), f.cohort());
            take(inventory, "part", 1); add(inventory, "product", 1);
        }
        check(inventory.get("product").compareTo(BigInteger.valueOf(f.amount())) >= 0, "Invalid literal oracle");
    }

    private static void verify(GraphPlan<String> plan, Fixture f, Map<String, Long> seeds) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan); var inventory = new LinkedHashMap<String, BigInteger>();
        plan.initialExact().forEach((key, amount) -> { check(amount.signum() >= 0 && amount.compareTo(BigInteger.valueOf(f.stock().getOrDefault(key, 0L))) <= 0, "Invented initial tool"); inventory.put(key, amount); });
        var counts = new LinkedHashMap<String, BigInteger>(); plan.patternTimesExact().forEach((id, count) -> counts.merge(plan.recipes().get(id).binding(), count, BigInteger::add));
        for (var source : f.entries()) check(BigInteger.valueOf(source.binding().equals("pair") ? f.mode() == 2 ? 1 : f.n() : source.binding().equals("read") ? f.amount() : 1).equals(counts.get(source.binding())), "Changed same-count source semantics");
        for (var recipe : plan.recipes().values()) {
            var source = f.entries().stream().filter(e -> e.binding().equals(recipe.binding())).findFirst().orElseThrow();
            var filled = new LinkedHashMap<Integer, BigInteger>(); var whole = new LinkedHashMap<Integer, String>(); var outputs = new LinkedHashMap<String, BigInteger>();
            for (var slot : recipe.slots()) {
                var input = source.recipe().inputs().get(slot.inputSlot()); var candidate = input.candidates().get(0);
                filled.merge(slot.inputSlot(), BigInteger.valueOf(slot.amount()), BigInteger::add); var old = whole.put(slot.inputSlot(), slot.key());
                check(source.recipe().mixed(input) || old == null || old.equals(slot.key()), "Split whole-key slot");
                if (candidate.family() instanceof Rule rule) {
                    String key = slot.key(); boolean a = key.startsWith("a"), b = key.startsWith("b"), c = key.equals("c");
                    check(rule.id().equals("warm") ? a || b || c : rule.id().equals("u") ? b || c : rule.id().equals("v") ? a || b : a || f.mode() == 2 && b, "Ineligible original entity");
                    if (rule.id().equals("v")) { if (f.mode() == 2) { check(durability(key) > 1, "Spent exhausted V tool"); add(outputs, key.substring(0, 1) + ":1", slot.amount()); } }
                    else if (rule.id().equals("u") && f.mode() == 1) { /* Literal consumption. */ }
                    else if (rule.id().equals("read") && f.mode() == 2) { check(durability(key) > 0, "Spent empty read tool"); if (durability(key) > 1) add(outputs, key.substring(0, 1) + ":" + (durability(key) - 1), slot.amount()); }
                    else add(outputs, key, slot.amount());
                } else check(slot.key().equals(candidate.stack().what()), "Changed ordinary source input");
            }
            for (int i = 0; i < source.recipe().inputs().size(); i++) check(BigInteger.valueOf(source.recipe().inputs().get(i).multiplier()).equals(filled.get(i)), "Changed simultaneous slot total");
            source.recipe().outputs().forEach(out -> add(outputs, out.what(), out.amount())); check(outputs.equals(exact(recipe.executionOutputs())), "Changed exact returned tools");
        }
        var cursor = new PlanCursor(plan.steps()); long fired = 0;
        while (cursor.current() != null) {
            var batch = cursor.current(); var recipe = plan.recipes().get(batch.recipe());
            for (long run = 0; run < batch.runs(); run++) { for (var slot : recipe.slots()) take(inventory, slot.key(), slot.amount()); recipe.executionOutputs().forEach((key, amount) -> add(inventory, key, amount)); check(++fired <= 2 + (f.mode() == 2 ? 1 : f.n()) + f.amount(), "Unexpected firing expansion"); }
            cursor.dispatched(batch.runs());
        }
        check(inventory.getOrDefault("product", BigInteger.ZERO).compareTo(BigInteger.valueOf(f.amount())) >= 0, "Unfunded delivery");
        seeds.forEach((key, amount) -> check(inventory.getOrDefault(key, BigInteger.ZERO).compareTo(BigInteger.valueOf(amount)) >= 0, "Lost exact seed"));
    }

    private static void boundaries() {
        var f = fixture(1, 1, 1, true, 0, false); var impossible = new ArrayList<>(f.entries());
        impossible.set(2, entry("read", List.of(tool("read", "a", 1, true, 1), tool("read-c", "c", 1, true, 1), ordinary("part", 1)), "product", 1));
        var budget = budget(40_000_000); budget.enableMetrics();
        try (var work = new CapturedCatalog<>(impossible).begin("product", 1, f.stock(), false, false, budget)) {
            while (!work.step()) {} check(!work.result().plan().feasible() && work.result().plan().result() != GraphPlan.Result.INFEASIBLE, "Local circulation failure became a global theorem");
        }
        check(budget.reservedBytes() == 0 && budget.searchWork() < 500_000, "Conflicting protected tools renewed work / leaked");
        f = fixture(2, 0, 1, true, 0, false);
        for (long cap : new long[]{1024, 16_000, 40_000, 80_000}) {
            budget = budget(cap);
            try (var work = new CapturedStatePlanning<>(f.entries(), "product", f.amount(), f.stock(), Set.of(), Map.of(), false, false, budget)) {
                try { while (!work.step()) {} } catch (PlanningBudget.Exhausted expected) { /* Inconclusive. */ }
                if (work.result() != null) verify(work.result(), f, Map.of());
            }
            check(budget.reservedBytes() == 0 && budget.searchWork() <= cap + 1, "Joint optimization renewed request allowance");
        }
    }
    private static void runtime(GraphPlan<String> plan, long amount) {
        final class Host implements GraphJobRuntime.Adapter<String> {
            GraphJobRuntime<String> job = new GraphJobRuntime<>(plan, plan.initial(), Map.of()); final Map<String, Long> physical = new LinkedHashMap<>(plan.initial()); final List<Map<String, Long>> pending = new ArrayList<>(); long delivered;
            public long capacity(GraphRecipe<String> recipe, long requested) { return requested; }
            public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
                inputs.forEach((key, value) -> { check(physical.getOrDefault(key, 0L) >= value, "Concurrent use of a pending tool"); physical.merge(key, -value, Long::sum); });
                var outputs = new LinkedHashMap<String, Long>(); recipe.executionOutputs().forEach((key, value) -> outputs.put(key, Math.multiplyExact(value, runs))); pending.add(outputs); return GraphJobRuntime.Outcome.ACCEPTED;
            }
            public long deliver(String key, long count) { delivered += count; return count; }
            public long refund(String key, long count) { return count; }
        }
        var host = new Host();
        for (int tick = 0; tick < 4000 && host.delivered < amount; tick++) {
            host.job.tick(host, tick * 20L, 8, () -> 0L); host.job = new GraphJobRuntime<>(host.job.snapshot());
            if (tick % 3 != 2) continue; Collections.reverse(host.pending);
            for (var outputs : host.pending) outputs.forEach((key, value) -> { host.physical.merge(key, value, Long::sum); check(host.job.accept(key, value, false) == value, "Lost physical return"); }); host.pending.clear();
        }
        check(host.delivered == amount, "Joint assignment stalled on delayed return / reload");
    }
    private static void handoff() throws Exception {
        var f = fixture(2, 0, 1, true, 0, false); var budget = budget(40_000_000); var a = Executors.newSingleThreadExecutor(); var b = Executors.newSingleThreadExecutor();
        try (var work = new CapturedStatePlanning<>(f.entries(), "product", f.amount(), f.stock(), Set.of(), Map.of(), false, false, budget)) {
            boolean done = false; int visits = 0; while (!done) done = ((visits++ & 1) == 0 ? a : b).submit(() -> { for (int i = 0; i < 16; i++) if (work.step()) return true; return false; }).get();
            check(work.result() != null && visits > 1, "Joint recovery lost worker continuation"); verify(work.result(), f, Map.of());
        } finally { a.shutdownNow(); b.shutdownNow(); }
        check(budget.reservedBytes() == 0, "Worker handoff leaked");
    }
    private static void cancelled() {
        var f = fixture(2, 0, 1, true, 0, false); var cancel = new AtomicBoolean(); var budget = new PlanningBudget(0, 40_000_000, 64L << 20, cancel::get, System::nanoTime); budget.enableMetrics();
        try (var work = new CapturedStatePlanning<>(f.entries(), "product", f.amount(), f.stock(), Set.of(), Map.of(), false, false, budget)) {
            while (!work.step()) if (budget.diagnostics().contains("state_matching@")) { cancel.set(true); break; }
            check(cancel.get(), "No joint optimization to cancel"); try { work.step(); throw new AssertionError("Ignored cancellation"); } catch (java.util.concurrent.CancellationException expected) { checks++; }
        }
        check(budget.reservedBytes() == 0, "Cancelled joint optimization leaked");
    }
    private static long durability(String key) { return Long.parseLong(key.substring(2)); }
    private static CapturedRecipe.Input<String> ordinary(String key, long count) { return new CapturedRecipe.Input<>(count, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false))); }
    private static CapturedRecipe.Input<String> tool(String id, String key, long count, boolean mixed, int mode) { var rule = new Rule(id, mode); return new CapturedRecipe.Input<>(count, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), rule.nominalAfterOne(key), false, false, rule)), mixed ? CapturedRecipe.InputMode.MIXED : CapturedRecipe.InputMode.INDIVISIBLE); }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, String output, long count) { return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>(output, count)), false, false)); }
    private static LinkedHashMap<String, BigInteger> exact(Map<String, Long> values) { var result = new LinkedHashMap<String, BigInteger>(); values.forEach((key, count) -> add(result, key, count)); return result; }
    private static void add(Map<String, BigInteger> values, String key, long count) { values.merge(key, BigInteger.valueOf(count), BigInteger::add); }
    private static void take(Map<String, BigInteger> values, String key, long count) { var left = values.getOrDefault(key, BigInteger.ZERO).subtract(BigInteger.valueOf(count)); check(left.signum() >= 0, "Unfunded physical input " + key); values.put(key, left); }
    private static PlanningBudget budget(long cap) { return new PlanningBudget(0, cap, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
