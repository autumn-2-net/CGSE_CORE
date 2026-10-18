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

/** Forced material order with unequal cross-rule wear on otherwise usable tools. */
public final class CapturedStateChoiceCostsTest {
    private static int checks, cases, costTrials;
    private static long maxWork, maxBytes;
    private record Fixture(List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock,
                           int n, int scale, long cohort, long amount) {}
    private record Wear(String id, int scale) implements StateFamily<String> {
        public boolean accepts(String key) { return id.equals("read") ? key.equals("holder") : key.startsWith("red:") || key.startsWith("blue:"); }
        public long capacity(String key) { return !accepts(key) ? 0 : id.equals("warm") || id.equals("read") ? Long.MAX_VALUE : durability(key) / cost(key); }
        long cost(String key) { return id.equals("general") ? key.startsWith("red:") ? 10L * scale : 10 : key.startsWith("red:") ? 10 : 5; }
        public String nominalAfterOne(String key) {
            if (id.equals("warm") || id.equals("read")) return key;
            long left = durability(key) - cost(key); return left <= 0 ? null : key.substring(0, key.indexOf(':') + 1) + left;
        }
    }

    public static void main(String[] args) throws Exception {
        for (int scale : new int[]{1, 2, 3, 10}) for (int n : new int[]{2, 3, 7, 31}) for (int order = 0; order < 4; order++)
            solve(fixture(n, scale, 1, true, order, false, false), true, false);
        for (boolean mixed : List.of(false, true)) for (boolean matching : List.of(false, true)) for (int order = 0; order < 4; order++) {
            var f = fixture(3, 3, 2, mixed, order, matching, false);
            var plan = solve(f, false, false); if (order == 0) runtime(plan, f.amount());
        }
        solve(fixture(3, 3, Long.MAX_VALUE / 4, false, 0, false, false), false, false);
        for (boolean matching : List.of(false, true)) {
            var f = fixture(7, 3, 1, false, 0, matching, true); solve(f, true, false);
            var stock = new LinkedHashMap<>(f.stock()); stock.put("product", f.amount());
            solve(new Fixture(f.entries(), stock, f.n(), f.scale(), f.cohort(), f.amount()), true, true);
        }
        boundaries(); handoff(); cancelled();
        check(costTrials > 0, "No quantified choice was exercised");
        System.out.println("CapturedStateChoiceCostsTest: cases=" + cases + "; assertions=" + checks +
                "; cost_trials=" + costTrials + "; max_work=" + maxWork + "; max_bytes=" + maxBytes);
    }

    private static GraphPlan<String> solve(Fixture f, boolean publicEntry, boolean force) {
        oracle(f); var budget = budget(40_000_000); budget.enableMetrics(); GraphPlan<String> plan;
        if (publicEntry) {
            try (var work = new CapturedCatalog<>(f.entries()).begin("product", f.amount(), f.stock(), false, force, budget)) {
                while (!work.step()) {} plan = work.result().plan();
                check(plan.feasible() && !work.result().coverage().complete(), "Lost physical witness: " + plan.result() + "; " + budget.diagnostics());
            }
        } else {
            try (var work = new CapturedStatePlanning<>(f.entries(), "product", f.amount(), f.stock(), Set.of(), Map.of(), false, force, budget)) {
                while (!work.step()) {} plan = work.result();
                check(plan != null, "Lost same-word choices: " + work.outcome() + "; " + budget.diagnostics());
            }
        }
        check(budget.reservedBytes() == 0, "Choice continuation leaked");
        check(budget.searchWork() < 250_000 && budget.peakBytes() < (32L << 20), "Small wear choice inflated its costs");
        PlanningCostTraceTest.conserved(budget);
        if (budget.diagnostics().contains("feedback=quantified_uses")) costTrials++;
        maxWork = Math.max(maxWork, budget.searchWork()); maxBytes = Math.max(maxBytes, budget.peakBytes()); cases++;
        verify(plan, f); return plan;
    }

    private static Fixture fixture(int n, int scale, long cohort, boolean mixed, int order, boolean matching, boolean manufacture) {
        String red = "red:" + ((n + 1L) * 10 * scale), blue = "blue:" + (n * 10L + 5); long amount = (n + 1L) * scale + 1;
        var general = new ArrayList<>(List.of(tool("general", red, cohort, mixed, scale), ordinary("gate", 1)));
        if (matching) general.add(tool("read", "holder", cohort, mixed, scale));
        if ((order & 2) != 0) Collections.reverse(general);
        var entries = new ArrayList<>(List.of(entry("warm", List.of(tool("warm", red, Math.multiplyExact(2, cohort), true, scale), ordinary("raw", 1)), "gate", 1),
                entry("general", general, "part", 1), entry("open", List.of(ordinary("part", n)), "token", amount),
                entry("special", List.of(tool("special", red, cohort, mixed, scale), ordinary("token", 1)), "product", 1)));
        if (manufacture) entries.add(entry("build", List.of(ordinary("metal", 1)), blue, cohort));
        Collections.rotate(entries, order); if ((order & 1) != 0) Collections.reverse(entries);
        var stock = new LinkedHashMap<String, Long>();
        if ((order & 1) == 0) { stock.put(red, cohort); stock.put(blue, cohort); } else { stock.put(blue, cohort); stock.put(red, cohort); }
        if (matching) stock.put("holder", cohort);
        if (manufacture) { stock.remove(blue); stock.put("metal", 1L); }
        stock.put("raw", (long) n); return new Fixture(List.copyOf(entries), stock, n, scale, cohort, amount);
    }

    // Independent witness: unchanged warm firings, general on blue only, then
    // all special firings on red and the one surviving blue use. No scheduler.
    private static void oracle(Fixture f) {
        var inventory = exact(f.stock()); String red = "red:" + ((f.n() + 1L) * 10 * f.scale()), blue = "blue:" + (f.n() * 10L + 5);
        if (!inventory.containsKey(blue)) { take(inventory, "metal", 1); add(inventory, blue, f.cohort()); }
        for (String key : List.of(red, blue)) check(inventory.get(key).equals(BigInteger.valueOf(f.cohort())), "Missing witness cohort");
        for (int i = 0; i < f.n(); i++) { take(inventory, "raw", 1); add(inventory, "gate", 1); }
        for (int i = 0; i < f.n(); i++) {
            take(inventory, "blue:" + ((f.n() - i) * 10L + 5), f.cohort()); add(inventory, "blue:" + ((f.n() - i - 1) * 10L + 5), f.cohort());
            take(inventory, "gate", 1); add(inventory, "part", 1);
        }
        take(inventory, "part", f.n()); add(inventory, "token", f.amount());
        for (long i = f.amount() - 1; i > 0; i--) {
            take(inventory, "red:" + i * 10, f.cohort()); if (i > 1) add(inventory, "red:" + (i - 1) * 10, f.cohort());
            take(inventory, "token", 1); add(inventory, "product", 1);
        }
        take(inventory, "blue:5", f.cohort()); take(inventory, "token", 1); add(inventory, "product", 1);
        check(inventory.get("product").compareTo(BigInteger.valueOf(f.amount())) >= 0, "Broken literal oracle");
    }

    private static void verify(GraphPlan<String> plan, Fixture f) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan); var inventory = new LinkedHashMap<String, BigInteger>();
        plan.initialExact().forEach((key, value) -> {
            check(value.signum() >= 0 && value.compareTo(BigInteger.valueOf(f.stock().getOrDefault(key, 0L))) <= 0, "Invented input"); inventory.put(key, value);
        });
        var counts = new LinkedHashMap<String, BigInteger>();
        plan.patternTimesExact().forEach((id, count) -> counts.merge(plan.recipes().get(id).binding(), count, BigInteger::add));
        for (var source : f.entries()) {
            long count = source.binding().equals("warm") || source.binding().equals("general") ? f.n() : source.binding().equals("special") ? f.amount() : 1;
            check(BigInteger.valueOf(count).equals(counts.get(source.binding())), "Changed source firing count");
        }
        for (var recipe : plan.recipes().values()) {
            var source = f.entries().stream().filter(e -> e.binding().equals(recipe.binding())).findFirst().orElseThrow();
            var filled = new LinkedHashMap<Integer, BigInteger>(); var whole = new LinkedHashMap<Integer, String>(); var outputs = new LinkedHashMap<String, BigInteger>();
            for (var slot : recipe.slots()) {
                var input = source.recipe().inputs().get(slot.inputSlot()); var candidate = input.candidates().get(0);
                filled.merge(slot.inputSlot(), BigInteger.valueOf(slot.amount()), BigInteger::add); var previous = whole.put(slot.inputSlot(), slot.key());
                check(source.recipe().mixed(input) || previous == null || previous.equals(slot.key()), "Split whole-key cohort");
                if (candidate.family() instanceof Wear wear) {
                    if (wear.id().equals("read")) { check(slot.key().equals("holder"), "Wrong read entity"); add(outputs, "holder", slot.amount()); continue; }
                    check(slot.key().startsWith("red:") || slot.key().startsWith("blue:"), "Wrong tool identity");
                    long cost = wear.id().equals("warm") ? 0 : wear.id().equals("general") ? slot.key().startsWith("red:") ? 10L * f.scale() : 10 : slot.key().startsWith("red:") ? 10 : 5;
                    long left = durability(slot.key()) - cost; check(left >= 0, "Insufficient literal durability");
                    if (left > 0) add(outputs, slot.key().substring(0, slot.key().indexOf(':') + 1) + left, slot.amount());
                } else check(slot.key().equals(candidate.stack().what()), "Changed ordinary input");
            }
            for (int i = 0; i < source.recipe().inputs().size(); i++) check(BigInteger.valueOf(source.recipe().inputs().get(i).multiplier()).equals(filled.get(i)), "Lost simultaneous demand");
            source.recipe().outputs().forEach(output -> add(outputs, output.what(), output.amount()));
            check(outputs.equals(exact(recipe.executionOutputs())), "Changed physical return");
        }
        var cursor = new PlanCursor(plan.steps()); long fired = 0;
        while (cursor.current() != null) {
            var batch = cursor.current(); var recipe = plan.recipes().get(batch.recipe());
            for (long run = 0; run < batch.runs(); run++) {
                for (var slot : recipe.slots()) take(inventory, slot.key(), slot.amount());
                recipe.executionOutputs().forEach((key, value) -> add(inventory, key, value));
                check(++fired <= f.n() * 2L + f.amount() + 2, "Expanded unexpected firings");
            }
            cursor.dispatched(batch.runs());
        }
        check(inventory.getOrDefault("product", BigInteger.ZERO).compareTo(BigInteger.valueOf(f.amount())) >= 0, "No funded delivery");
    }

    private static void boundaries() {
        var f = fixture(3, 3, 1, true, 0, false, false); var stock = new LinkedHashMap<>(f.stock()); stock.put("raw", 2L);
        var budget = budget(40_000_000);
        try (var work = new CapturedCatalog<>(f.entries()).begin("product", f.amount(), stock, false, false, budget)) {
            while (!work.step()) {} check(!work.result().plan().feasible(), "Choice costs supplied absent raw material");
        }
        check(budget.reservedBytes() == 0, "Negative control leaked");
        // Even the best tool assignment loses two special uses per general
        // firing. One additional token exceeds that independent capacity bound.
        var impossible = new ArrayList<>(f.entries());
        for (int i = 0; i < impossible.size(); i++) if (impossible.get(i).binding().equals("open"))
            impossible.set(i, entry("open", List.of(ordinary("part", f.n())), "token", f.amount() + 1));
        budget = budget(40_000_000); budget.enableMetrics();
        try (var work = new CapturedStatePlanning<>(impossible, "product", f.amount() + 1, f.stock(), Set.of(), Map.of(), false, false, budget)) {
            while (!work.step()) {}
            check(work.result() == null, "Cost trial invented extra special capacity");
            check(budget.diagnostics().contains("feedback=quantified_uses"), "Rejected cost trial was not exercised");
        }
        check(budget.reservedBytes() == 0 && budget.searchWork() < 200_000, "Repeated cost failure renewed allowance or leaked");
        for (long limit : new long[]{1024, 16_000, 30_000, 40_000}) {
            budget = budget(limit);
            try (var work = new CapturedStatePlanning<>(f.entries(), "product", f.amount(), f.stock(), Set.of(), Map.of(), false, false, budget)) {
                try { while (!work.step()) {} } catch (PlanningBudget.Exhausted expected) { /* No negative theorem. */ }
                if (work.result() != null) verify(work.result(), f);
            }
            check(budget.reservedBytes() == 0 && budget.searchWork() <= limit + 1, "Costs renewed work or leaked");
        }
    }

    private static void runtime(GraphPlan<String> plan, long amount) {
        final class Host implements GraphJobRuntime.Adapter<String> {
            GraphJobRuntime<String> job = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            final Map<String, Long> physical = new LinkedHashMap<>(plan.initial()); final List<Map<String, Long>> pending = new ArrayList<>(); long delivered;
            public long capacity(GraphRecipe<String> recipe, long requested) { return requested; }
            public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
                inputs.forEach((key, value) -> { check(physical.getOrDefault(key, 0L) >= value, "Tool still in flight"); physical.merge(key, -value, Long::sum); });
                var outputs = new LinkedHashMap<String, Long>(); recipe.executionOutputs().forEach((key, value) -> outputs.put(key, Math.multiplyExact(value, runs)));
                pending.add(outputs); return GraphJobRuntime.Outcome.ACCEPTED;
            }
            public long deliver(String key, long count) { delivered += count; return count; }
            public long refund(String key, long count) { return count; }
        }
        var host = new Host();
        for (int tick = 0; tick < 4000 && host.delivered < amount; tick++) {
            host.job.tick(host, tick * 20L, 8, () -> 0L); host.job = new GraphJobRuntime<>(host.job.snapshot());
            if (tick % 3 != 2) continue; Collections.reverse(host.pending);
            for (var outputs : host.pending) outputs.forEach((key, value) -> { host.physical.merge(key, value, Long::sum); check(host.job.accept(key, value, false) == value, "Lost delayed return"); });
            host.pending.clear();
        }
        check(host.delivered == amount, "Cost-selected entities stalled after save/load");
    }

    private static void handoff() throws Exception {
        var f = fixture(3, 3, 1, true, 0, false, false); var budget = budget(40_000_000);
        var left = Executors.newSingleThreadExecutor(); var right = Executors.newSingleThreadExecutor();
        try (var work = new CapturedStatePlanning<>(f.entries(), "product", f.amount(), f.stock(), Set.of(), Map.of(), false, false, budget)) {
            boolean done = false; int visits = 0;
            while (!done) done = ((visits++ & 1) == 0 ? left : right).submit(() -> { for (int i = 0; i < 16; i++) if (work.step()) return true; return false; }).get();
            check(work.result() != null && visits > 1, "Lost cost continuation across workers"); verify(work.result(), f);
        } finally { left.shutdownNow(); right.shutdownNow(); }
        check(budget.reservedBytes() == 0, "Handoff leaked");
    }
    private static void cancelled() {
        var f = fixture(3, 3, 1, true, 0, false, false); var cancel = new AtomicBoolean();
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, cancel::get, System::nanoTime); budget.enableMetrics();
        try (var work = new CapturedStatePlanning<>(f.entries(), "product", f.amount(), f.stock(), Set.of(), Map.of(), false, false, budget)) {
            while (!work.step()) if (budget.diagnostics().contains("feedback=quantified_uses")) { cancel.set(true); break; }
            check(cancel.get(), "No cost trial to cancel");
            try { work.step(); throw new AssertionError("Ignored cancellation"); } catch (java.util.concurrent.CancellationException expected) { checks++; }
        }
        check(budget.reservedBytes() == 0, "Cancelled cost continuation leaked");
    }
    private static long durability(String key) { return Long.parseLong(key.substring(key.indexOf(':') + 1)); }
    private static CapturedRecipe.Input<String> ordinary(String key, long count) { return new CapturedRecipe.Input<>(count, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false))); }
    private static CapturedRecipe.Input<String> tool(String id, String key, long count, boolean mixed, int scale) { var family = new Wear(id, scale); return new CapturedRecipe.Input<>(count, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), family.nominalAfterOne(key), false, false, family)), mixed ? CapturedRecipe.InputMode.MIXED : CapturedRecipe.InputMode.INDIVISIBLE); }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, String output, long count) { return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>(output, count)), false, false)); }
    private static LinkedHashMap<String, BigInteger> exact(Map<String, Long> source) { var result = new LinkedHashMap<String, BigInteger>(); source.forEach((key, amount) -> add(result, key, amount)); return result; }
    private static void add(Map<String, BigInteger> values, String key, long amount) { values.merge(key, BigInteger.valueOf(amount), BigInteger::add); }
    private static void take(Map<String, BigInteger> values, String key, long amount) { var left = values.getOrDefault(key, BigInteger.ZERO).subtract(BigInteger.valueOf(amount)); check(left.signum() >= 0, "Unfunded literal input " + key); values.put(key, left); }
    private static PlanningBudget budget(long limit) { return new PlanningBudget(0, limit, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
