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

/** A still-eligible tool can already have lost the uses required by a later rule. */
public final class CapturedStateCapacityChoiceTest {
    private static int checks, cases, feedback;
    private static long maxWork, maxBytes;
    private record Fixture(List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock,
                           int colors, int n, long tools, int surplus) {}
    private record Rule(String id) implements StateFamily<String> {
        public boolean accepts(String key) {
            return key.startsWith("blue:") && !id.startsWith("finish") || key.startsWith("red") &&
                    key.contains(":") && (!id.startsWith("finish") || key.startsWith("red" + id.substring(6) + ":"));
        }
        public long capacity(String key) { return !accepts(key) ? 0 : id.equals("warm") ? Long.MAX_VALUE : durability(key); }
        public String nominalAfterOne(String key) { return id.equals("warm") ? key : successor(key); }
    }

    public static void main(String[] args) throws Exception {
        for (int colors : new int[]{1, 2}) for (int n : new int[]{3, 7, 31})
            for (int tools : new int[]{1, 2}) for (boolean mixed : List.of(false, true)) {
                if (tools == 1 && mixed) continue;
                for (int order = 0; order < 4; order++) {
                    var fixture = fixture(colors, n, tools, mixed, order, 1);
                    var plan = solve(fixture, false, Map.of(), false);
                    if (order == 0 && n == 7) runtime(plan, n);
                }
            }
        // Quantities denote physical cohorts, not one allocation node per tool.
        solve(fixture(1, 3, Long.MAX_VALUE / 4, false, 0, 1), false, Map.of(), false);
        for (int colors : new int[]{1, 2}) {
            var fixture = fixture(colors, 7, 1, false, 0, 2);
            solve(fixture, true, Map.of("red0:2", 1L), false);
            var stock = new LinkedHashMap<>(fixture.stock()); stock.put("product", 7L);
            solve(new Fixture(fixture.entries(), stock, colors, 7, 1, 2), true, Map.of("red0:2", 1L), true);
        }
        boundaries(); handoff(); cancelled();
        check(feedback > 0, "No delayed-use feedback exercised");
        System.out.println("CapturedStateCapacityChoiceTest: cases=" + cases + "; assertions=" + checks +
                "; capacity_feedback=" + feedback + "; max_work=" + maxWork + "; max_bytes=" + maxBytes);
    }

    private static GraphPlan<String> solve(Fixture fixture, boolean publicEntry, Map<String, Long> seeds, boolean force) {
        oracle(fixture);
        var budget = budget(40_000_000); budget.enableMetrics(); GraphPlan<String> plan;
        if (publicEntry) {
            try (var work = new CapturedCatalog<>(fixture.entries()).begin("product", fixture.n(), fixture.stock(), Set.of(), seeds, false, force, budget)) {
                while (!work.step()) {}
                plan = work.result().plan();
                check(plan.feasible() && !work.result().coverage().complete(), "Lost supported physical request: " + plan.result() + "; " + budget.diagnostics());
            }
        } else {
            try (var work = new CapturedStatePlanning<>(fixture.entries(), "product", fixture.n(), fixture.stock(), Set.of(), seeds, false, force, budget)) {
                while (!work.step()) {}
                plan = work.result();
                check(plan != null, "Lost known tool assignment: " + fixture + "; " + work.outcome() + "; " + budget.diagnostics());
            }
        }
        check(budget.reservedBytes() == 0, "Restoration retained a tool pool");
        check(budget.searchWork() < 1_000_000 && budget.peakBytes() < (32L << 20), "Small feedback inflated its budget");
        if (budget.diagnostics().contains("feedback=guaranteed_uses")) feedback++;
        maxWork = Math.max(maxWork, budget.searchWork()); maxBytes = Math.max(maxBytes, budget.peakBytes()); cases++;
        verify(plan, fixture); return plan;
    }

    private static Fixture fixture(int colors, int n, long tools, boolean mixed, int order, int surplus) {
        var entries = new ArrayList<CapturedCatalog.Entry<String>>();
        entries.add(entry("warm", List.of(tool("warm", "red0:" + (n + surplus), Math.multiplyExact(colors + 1L, tools), true), ordinary("raw")), "gate", order));
        entries.add(entry("use", List.of(tool("use", "red0:" + (n + surplus), tools, mixed), ordinary("gate")), "part0", order));
        for (int c = 0; c < colors; c++) entries.add(entry("finish" + c,
                List.of(tool("finish" + c, "red" + c + ":" + (n + surplus), tools, mixed), ordinary("part" + c)),
                c + 1 == colors ? "product" : "part" + (c + 1), order));
        Collections.rotate(entries, order); if ((order & 1) != 0) Collections.reverse(entries);
        var keys = new ArrayList<String>();
        for (int c = 0; c < colors; c++) keys.add("red" + c + ":" + (n + surplus));
        keys.add("blue:" + n); Collections.rotate(keys, order);
        var stock = new LinkedHashMap<String, Long>(); for (String key : keys) stock.put(key, tools);
        stock.put("raw", (long) n);
        return new Fixture(List.copyOf(entries), stock, colors, n, tools, surplus);
    }

    // Constructive oracle independent of production selection, scoring and scheduling:
    // warm all entities first; all general uses spend blue; specialized uses spend red.
    private static void oracle(Fixture f) {
        var inventory = exact(f.stock());
        for (int c = 0; c < f.colors(); c++) check(inventory.get("red" + c + ":" + (f.n() + f.surplus())).compareTo(BigInteger.valueOf(f.tools())) >= 0, "Missing oracle tool");
        for (int i = 0; i < f.n(); i++) { take(inventory, "raw", 1); add(inventory, "gate", 1); }
        for (int i = 0; i < f.n(); i++) {
            take(inventory, "blue:" + (f.n() - i), f.tools());
            if (i + 1 < f.n()) add(inventory, "blue:" + (f.n() - i - 1), f.tools());
            take(inventory, "gate", 1); add(inventory, "part0", 1);
        }
        for (int c = 0; c < f.colors(); c++) for (int i = 0; i < f.n(); i++) {
            take(inventory, "red" + c + ":" + (f.n() + f.surplus() - i), f.tools());
            add(inventory, "red" + c + ":" + (f.n() + f.surplus() - i - 1), f.tools());
            take(inventory, "part" + c, 1); add(inventory, c + 1 == f.colors() ? "product" : "part" + (c + 1), 1);
        }
        check(inventory.get("product").compareTo(BigInteger.valueOf(f.n())) >= 0, "Broken explicit witness");
    }

    private static void verify(GraphPlan<String> plan, Fixture f) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        var inventory = new LinkedHashMap<String, BigInteger>();
        plan.initialExact().forEach((key, amount) -> {
            check(amount.compareTo(BigInteger.valueOf(f.stock().getOrDefault(key, 0L))) <= 0, "Invented starting inventory"); inventory.put(key, amount);
        });
        var sourceCounts = new LinkedHashMap<String, BigInteger>();
        plan.patternTimesExact().forEach((id, count) -> sourceCounts.merge(plan.recipes().get(id).binding(), count, BigInteger::add));
        for (var source : f.entries()) check(BigInteger.valueOf(f.n()).equals(sourceCounts.get(source.binding())), "Changed forced material-chain counts");
        for (var recipe : plan.recipes().values()) {
            var source = f.entries().stream().filter(value -> value.binding().equals(recipe.binding())).findFirst().orElseThrow();
            var filled = new LinkedHashMap<Integer, BigInteger>(); var whole = new LinkedHashMap<Integer, String>();
            var outputs = new LinkedHashMap<String, BigInteger>();
            for (var slot : recipe.slots()) {
                var input = source.recipe().inputs().get(slot.inputSlot()); var candidate = input.candidates().get(0);
                filled.merge(slot.inputSlot(), BigInteger.valueOf(slot.amount()), BigInteger::add);
                var prior = whole.put(slot.inputSlot(), slot.key());
                check(source.recipe().mixed(input) || prior == null || prior.equals(slot.key()), "Split indivisible entity cohort");
                if (candidate.family() != null) {
                    check((slot.key().startsWith("red") || slot.key().startsWith("blue:")) && durability(slot.key()) > 0, "Used an exhausted/non-tool state");
                    if (source.binding().startsWith("finish")) check(slot.key().startsWith("red" + source.binding().substring(6) + ":"), "Wrong specialized tool");
                    String returned = source.binding().equals("warm") ? slot.key() : successor(slot.key());
                    if (returned != null) add(outputs, returned, slot.amount());
                } else check(candidate.stack().what().equals(slot.key()), "Changed ordinary input");
            }
            for (int i = 0; i < source.recipe().inputs().size(); i++) check(BigInteger.valueOf(source.recipe().inputs().get(i).multiplier()).equals(filled.get(i)), "Wrong simultaneous demand");
            source.recipe().outputs().forEach(out -> add(outputs, out.what(), out.amount()));
            check(outputs.equals(exact(recipe.executionOutputs())), "Changed literal state transition");
        }
        var cursor = new PlanCursor(plan.steps()); int fired = 0;
        while (cursor.current() != null) {
            var batch = cursor.current(); var recipe = plan.recipes().get(batch.recipe());
            for (long run = 0; run < batch.runs(); run++) {
                for (var slot : recipe.slots()) take(inventory, slot.key(), slot.amount());
                recipe.executionOutputs().forEach((key, count) -> add(inventory, key, count));
                check(++fired <= (f.colors() + 2) * f.n(), "Expanded unexpected firings");
            }
            cursor.dispatched(batch.runs());
        }
        // Existing target stock may remain outside the plan's initial reservation.
        check(inventory.getOrDefault("product", BigInteger.ZERO).compareTo(BigInteger.valueOf(f.n())) >= 0, "No final delivery");
    }

    private static void runtime(GraphPlan<String> plan, long n) {
        final class Host implements GraphJobRuntime.Adapter<String> {
            GraphJobRuntime<String> job = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            final Map<String, Long> physical = new LinkedHashMap<>(plan.initial());
            final List<Map<String, Long>> pending = new ArrayList<>(); long delivered;
            public long capacity(GraphRecipe<String> recipe, long requested) { return requested; }
            public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
                inputs.forEach((key, count) -> { check(physical.getOrDefault(key, 0L) >= count, "Tool still in flight"); physical.merge(key, -count, Long::sum); });
                var outputs = new LinkedHashMap<String, Long>();
                recipe.executionOutputs().forEach((key, count) -> outputs.put(key, Math.multiplyExact(count, runs)));
                pending.add(outputs); return GraphJobRuntime.Outcome.ACCEPTED;
            }
            public long deliver(String key, long count) { delivered += count; return count; }
            public long refund(String key, long count) { return count; }
        }
        var host = new Host();
        for (int tick = 0; tick < 2000 && host.delivered < n; tick++) {
            host.job.tick(host, tick * 20L, 8, () -> 0L); host.job = new GraphJobRuntime<>(host.job.snapshot());
            if (tick % 3 != 2) continue;
            Collections.reverse(host.pending);
            for (var output : host.pending) output.forEach((key, count) -> {
                host.physical.merge(key, count, Long::sum); check(host.job.accept(key, count, false) == count, "Lost worn return on reload");
            });
            host.pending.clear();
        }
        check(host.delivered == n, "Delayed physical return stalled");
    }

    private static void boundaries() {
        var f = fixture(1, 7, 1, false, 0, 1);
        for (int mode = 0; mode < 3; mode++) {
            var stock = new LinkedHashMap<>(f.stock());
            if (mode == 0) stock.remove("blue:7");
            if (mode == 1) stock.put("raw", 6L);
            var seeds = mode == 2 ? Map.of("red0:8", 1L) : Map.<String, Long>of();
            var budget = budget(40_000_000);
            try (var work = new CapturedCatalog<>(f.entries()).begin("product", 7, stock, Set.of(), seeds, false, false, budget)) {
                while (!work.step()) {}
                check(!work.result().plan().feasible() && work.result().plan().result() != GraphPlan.Result.INFEASIBLE,
                        "Preference invented a missing resource or exported a family-domain negative proof");
            }
            check(budget.reservedBytes() == 0, "Declined physical request leaked");
        }
        for (long cap : new long[]{1024, 16_000, 30_000}) {
            var budget = budget(cap);
            try (var work = new CapturedStatePlanning<>(f.entries(), "product", 7, f.stock(), Set.of(), Map.of(), false, false, budget)) {
                try { while (!work.step()) {} } catch (PlanningBudget.Exhausted expected) { /* Unknown. */ }
                if (work.result() != null) verify(work.result(), f);
            }
            check(budget.reservedBytes() == 0 && budget.searchWork() <= cap + 1, "Feedback renewed work or leaked on limit");
        }
    }

    private static void handoff() throws Exception {
        var f = fixture(1, 7, 1, false, 0, 1); var budget = budget(40_000_000);
        var left = Executors.newSingleThreadExecutor(); var right = Executors.newSingleThreadExecutor();
        try (var work = new CapturedStatePlanning<>(f.entries(), "product", 7, f.stock(), Set.of(), Map.of(), false, false, budget)) {
            boolean done = false; int visits = 0;
            while (!done) done = ((visits++ & 1) == 0 ? left : right).submit(() -> {
                for (int i = 0; i < 64; i++) if (work.step()) return true; return false;
            }).get();
            check(work.result() != null && visits > 1, "Lost capacity feedback across workers"); verify(work.result(), f);
        } finally { left.shutdownNow(); right.shutdownNow(); }
        check(budget.reservedBytes() == 0, "Worker handoff leaked");
    }

    private static void cancelled() {
        var f = fixture(1, 7, 1, false, 0, 1); var cancel = new AtomicBoolean();
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, cancel::get, System::nanoTime); budget.enableMetrics();
        try (var work = new CapturedStatePlanning<>(f.entries(), "product", 7, f.stock(), Set.of(), Map.of(), false, false, budget)) {
            while (!work.step()) if (budget.diagnostics().contains("feedback=guaranteed_uses")) { cancel.set(true); break; }
            check(cancel.get(), "No pending capacity feedback to cancel");
            try { work.step(); throw new AssertionError("Ignored cancellation"); }
            catch (java.util.concurrent.CancellationException expected) { checks++; }
        }
        check(budget.reservedBytes() == 0, "Cancelled capacity feedback leaked");
    }

    private static CapturedRecipe.Input<String> tool(String id, String key, long amount, boolean mixed) {
        var rule = new Rule(id);
        return new CapturedRecipe.Input<>(amount, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), rule.nominalAfterOne(key), false, false, rule)), mixed ? CapturedRecipe.InputMode.MIXED : CapturedRecipe.InputMode.INDIVISIBLE);
    }
    private static CapturedRecipe.Input<String> ordinary(String key) { return new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false))); }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, String output, int order) {
        var slots = new ArrayList<>(inputs); if ((order & 1) != 0) Collections.reverse(slots);
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(slots, List.of(new CapturedRecipe.Amount<>(output, 1)), false, false));
    }
    private static long durability(String key) { return Long.parseLong(key.substring(key.indexOf(':') + 1)); }
    private static String successor(String key) { long left = durability(key) - 1; return left == 0 ? null : key.substring(0, key.indexOf(':') + 1) + left; }
    private static LinkedHashMap<String, BigInteger> exact(Map<String, Long> values) { var out = new LinkedHashMap<String, BigInteger>(); values.forEach((key, amount) -> out.put(key, BigInteger.valueOf(amount))); return out; }
    private static void add(Map<String, BigInteger> inventory, String key, long count) { inventory.merge(key, BigInteger.valueOf(count), BigInteger::add); }
    private static void take(Map<String, BigInteger> inventory, String key, long count) {
        var left = inventory.getOrDefault(key, BigInteger.ZERO).subtract(BigInteger.valueOf(count));
        check(left.signum() >= 0, "Unfunded physical input " + key); inventory.put(key, left);
    }
    private static PlanningBudget budget(long work) { return new PlanningBudget(0, work, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
