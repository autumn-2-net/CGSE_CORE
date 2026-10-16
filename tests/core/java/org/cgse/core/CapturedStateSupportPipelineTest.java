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

/** Irrelevant captured recipes cannot prevent a small physical state witness. */
public final class CapturedStateSupportPipelineTest {
    private static int checks, cases;
    private static long largestCompile, largestBytes;
    private record Rule(int parity) implements StateFamily<String> {
        public String id() { return "phase:" + parity; }
        public boolean accepts(String key) { return key.startsWith("tool:") && (state(key) & 1) == parity; }
        public long capacity(String key) { return accepts(key) ? 1 : 0; }
        public String nominalAfterOne(String key) { return "tool:" + ((state(key) + 1) % 4); }
    }
    private record Noise(int idValue) implements StateFamily<String> {
        public String id() { return "noise-rule:" + idValue; }
        public boolean accepts(String key) { return key.equals("noise-tool:" + idValue); }
        public long capacity(String key) { return accepts(key) ? Long.MAX_VALUE : 0; }
        public String nominalAfterOne(String key) { return key; }
    }

    public static void main(String[] args) {
        for (int filler : new int[]{128, 512, 4096, 16_384}) for (boolean reverse : List.of(false, true))
            for (long n : new long[]{7, 1_000_000_000L}) {
                var entries = entries(filler, reverse, false, 1, false); var stock = stock(filler, n, 1, false);
                var plan = solve(entries, stock, n, Map.of(), false, false, 40_000_000, "selected=2;");
                if (filler == 128 && n == 7) runtime(plan, 7);
            }
        for (boolean reverse : List.of(false, true)) for (boolean mixed : List.of(false, true)) {
            var plan = solve(entries(256, reverse, false, 2, mixed), stock(256, 7, 2, mixed), 7, Map.of(), false, false, 40_000_000, "selected=2;");
            runtime(plan, 7);
        }
        for (boolean reverse : List.of(false, true)) {
            // The warm source is needed only for its tool return. Its ordinary
            // waste output is not a dependency of the requested product.
            var entries = entries(256, reverse, true, 1, false);
            solve(entries, stock(256, 7, 1, false), 7, Map.of(), false, false, 40_000_000, "selected=2;");
            entries.add(entry("forge", List.of(ordinary("metal")), "tool:2"));
            var available = stock(256, 7, 1, false); available.remove("tool:0"); available.put("metal", 1L);
            var plan = solve(entries, available, 7, Map.of(), false, false, 40_000_000, "selected=3;");
            check(plan.recipes().values().stream().anyMatch(r -> r.binding().equals("forge")), "Non-prototype manufacturing source was dropped"); runtime(plan, 7);
        }
        var entries = entries(256, false, false, 1, false);
        solve(entries, stock(256, 7, 1, false), 7, Map.of("tool:2", 1L), false, false, 40_000_000, "selected=2;");
        solve(entries, stock(256, 8, 1, false), 8, Map.of("tool:0", 1L), true, false, 40_000_000, "selected=2;");
        var available = stock(256, 7, 1, false); available.put("product", 3L);
        solve(entries, available, 7, Map.of(), false, true, 40_000_000, "selected=2;");
        solve(entries, stock(256, Long.MAX_VALUE, 1, false), Long.MAX_VALUE, Map.of(), false, false, 40_000_000, "selected=2;");
        var seededEntries = entries(256, false, false, 1, false);
        seededEntries.add(entry("make-seed", List.of(ordinary("gold")), "token"));
        var seededStock = stock(256, 7, 1, false); seededStock.put("gold", 1L);
        var seeded = solve(seededEntries, seededStock, 7, Map.of("token", 1L), false, false, 40_000_000, "selected=3;");
        check(seeded.recipes().values().stream().anyMatch(r -> r.binding().equals("make-seed")), "Dropped an independent required seed producer");
        runtime(seeded, 7);
        scopeAndLimits(); wideSourceFallback();
        System.out.println("CapturedStateSupportPipelineTest: cases=" + cases + "; assertions=" + checks +
                "; max_compile=" + largestCompile + "; max_bytes=" + largestBytes);
    }

    private static GraphPlan<String> solve(List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock, long n,
            Map<String, Long> seeds, boolean preserve, boolean force, long cap, String exercise) {
        var budget = budget(cap); budget.enableMetrics(); GraphPlan<String> plan;
        try (var work = new CapturedCatalog<>(entries).begin("product", n, stock, Set.of(), seeds, preserve, force, budget)) {
            boolean exercised = false;
            while (!work.step()) if (!exercised) exercised = budget.diagnostics().contains(exercise);
            plan = work.result().plan();
            check(plan.feasible(), "Lost padded-catalog witness: " + budget.diagnostics());
            check(!work.result().coverage().complete(), "Selected support made original capture complete");
            // Keep the observation when emitted; a later count search can
            // legitimately rotate the bounded diagnostic ring many times.
            check(exercised, "Did not exercise support path: " + budget.diagnostics());
            try { work.certificate(100_000, 1L << 20); throw new AssertionError("Exported opaque family as finite proof"); }
            catch (UnsupportedOperationException expected) { checks++; }
        }
        check(budget.reservedBytes() == 0, "Selected state attempt leaked");
        check(budget.searchWork() < 1_000_000 && budget.peakBytes() < (16L << 20), "Small selected graph ballooned");
        if (entries.size() < 20_000) check(budget.compilationWork() < 250_000, "Whole unrelated state catalog was compiled again");
        PlanningCostTraceTest.conserved(budget); verify(plan, entries, stock, n);
        largestCompile = Math.max(largestCompile, budget.compilationWork()); largestBytes = Math.max(largestBytes, budget.peakBytes()); cases++;
        return plan;
    }

    private static void scopeAndLimits() {
        var entries = entries(256, false, false, 1, false); var catalog = new CapturedCatalog<>(entries);
        for (boolean supplied : List.of(false, true, false)) {
            var stock = stock(256, 7, 1, false); if (!supplied) stock.remove("tool:0");
            var budget = budget(40_000_000);
            try (var work = catalog.begin("product", 7, stock, false, false, budget)) {
                boolean originalResumed = false;
                while (!work.step()) if (!originalResumed) originalResumed = budget.diagnostics().contains("retry_full_state");
                check(work.result().plan().feasible() == supplied, "Selected keys became stock / cached a private miss");
                check(!work.result().coverage().complete() && work.result().plan().result() != GraphPlan.Result.INFEASIBLE, "Selected failure escaped its scope");
                check(supplied || originalResumed, "Private miss discarded the original state attempt");
            }
            check(budget.reservedBytes() == 0, "Repeated scoped request leaked");
        }
        for (long cap : new long[]{1024, 16_384, 48_000}) {
            var budget = budget(cap);
            try (var work = catalog.begin("product", 1_000_000_000L, stock(256, 1_000_000_000L, 1, false), false, false, budget)) {
                try { while (!work.step()) {} } catch (PlanningBudget.Exhausted limit) { /* Inconclusive. */ }
            }
            check(budget.reservedBytes() == 0 && budget.searchWork() <= cap + 1, "Selected/full state attempts renewed request budget");
        }
        // The explicit parent allowance also covers selection and later state
        // attempts; a fresh constructor cannot grant another half-request.
        var directBudget = budget(40_000_000);
        try (var work = new CapturedStatePlanning<>(entries.subList(0, 2), "product", 1_000_000_000L, Map.of("tool:0", 1L, "raw", 1_000_000_000L),
                Set.of(), Map.of(), false, false, directBudget, 4096)) {
            while (!work.step()) {}
            check(work.result() == null && directBudget.nodes() < 8192, "Explicit state-arm remainder was renewed");
        }
        check(directBudget.reservedBytes() == 0, "Explicit remaining allowance leaked");
    }

    private static void wideSourceFallback() {
        var entries = new ArrayList<CapturedCatalog.Entry<String>>();
        for (int i = 0; i < 140; i++) entries.add(entry("chain:" + i, List.of(ordinary(i == 0 ? "raw" : "gate:" + (i - 1))), "gate:" + i));
        var family = new Noise(99);
        var tool = new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("noise-tool:99", 1), "noise-tool:99", false, false, family)));
        entries.add(entry("finish", List.of(ordinary("gate:139"), tool), "product"));
        var stock = Map.of("raw", 7L, "noise-tool:99", 1L);
        var plan = solve(entries, stock, 7, Map.of(), false, false, 40_000_000, "reason=source_limit");
        runtime(plan, 7);
    }

    private static ArrayList<CapturedCatalog.Entry<String>> entries(int filler, boolean reverse, boolean bridge, int tools, boolean mixed) {
        var entries = new ArrayList<CapturedCatalog.Entry<String>>();
        entries.add(entry("even", List.of(tool(0, tools, mixed), ordinary(bridge ? "permit" : "raw")), bridge ? "waste" : "part"));
        entries.add(entry("odd", List.of(tool(1, tools, mixed), ordinary(bridge ? "raw" : "part")), "product"));
        for (int i = 0; i < filler; i++) {
            var inputs = new ArrayList<>(List.of(ordinary("noise-input:" + i)));
            if (i < 16) {
                var family = new Noise(i); String key = "noise-tool:" + i;
                inputs.add(new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), key, false, false, family))));
            }
            entries.add(entry("noise:" + i, inputs, "noise-output:" + i));
        }
        if (reverse) Collections.reverse(entries);
        return entries;
    }
    private static LinkedHashMap<String, Long> stock(int filler, long n, int tools, boolean mixed) {
        var stock = new LinkedHashMap<String, Long>(); stock.put("tool:0", mixed ? 1L : (long) tools);
        if (mixed) stock.put("tool:2", (long) tools - 1);
        stock.put("raw", n); stock.put("permit", n);
        for (int i = 0; i < filler; i++) { stock.put("noise-input:" + i, 1L); if (i < 16) stock.put("noise-tool:" + i, 1L); }
        return stock;
    }
    private static CapturedRecipe.Input<String> tool(int parity, int count, boolean mixed) {
        String key = "tool:" + (parity == 0 ? 0 : 3); var family = new Rule(parity);
        return new CapturedRecipe.Input<>(count, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), family.nominalAfterOne(key), false, false, family)),
                mixed ? CapturedRecipe.InputMode.MIXED : CapturedRecipe.InputMode.INDIVISIBLE);
    }
    private static CapturedRecipe.Input<String> ordinary(String key) {
        return new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false)), CapturedRecipe.InputMode.INDIVISIBLE);
    }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, String output) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>(output, 1)), false, false));
    }

    private static void verify(GraphPlan<String> plan, List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock, long n) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        var inventory = new LinkedHashMap<String, BigInteger>();
        plan.initialExact().forEach((key, count) -> { check(count.compareTo(BigInteger.valueOf(stock.getOrDefault(key, 0L))) <= 0, "Invented selected initial stock"); inventory.put(key, count); });
        var sources = new LinkedHashMap<String, CapturedCatalog.Entry<String>>(); entries.forEach(entry -> sources.put(entry.binding(), entry));
        for (var recipe : plan.recipes().values()) {
            var original = sources.get(recipe.binding()).recipe(); var outputs = new LinkedHashMap<String, Long>();
            long[] filled = new long[original.inputs().size()]; var keys = new LinkedHashMap<Integer, String>();
            for (var slot : recipe.slots()) {
                var input = original.inputs().get(slot.inputSlot()); var candidate = input.candidates().get(0);
                filled[slot.inputSlot()] += slot.amount(); String previous = keys.put(slot.inputSlot(), slot.key());
                check(original.mixed(input) || previous == null || previous.equals(slot.key()), "Split a whole-key group");
                if (candidate.family() instanceof Rule rule) {
                    check((state(slot.key()) & 1) == rule.parity(), "Used wrong state rule");
                    outputs.merge("tool:" + ((state(slot.key()) + 1) % 4), slot.amount(), Math::addExact);
                } else {
                    check(candidate.stack().what().equals(slot.key()), "Changed original key");
                    if (candidate.remaining() != null) outputs.merge(candidate.remaining(), slot.amount(), Math::addExact);
                }
            }
            for (int i = 0; i < filled.length; i++) check(filled[i] == original.inputs().get(i).multiplier(), "Changed simultaneous demand");
            original.outputs().forEach(output -> outputs.merge(output.what(), output.amount(), Math::addExact));
            check(outputs.equals(recipe.executionOutputs()), "Changed source return / binding semantics");
        }
        if (n > 8) return;
        var cursor = new PlanCursor(plan.steps()); int fired = 0;
        while (cursor.current() != null) {
            var batch = cursor.current(); var recipe = plan.recipes().get(batch.recipe());
            for (long i = 0; i < batch.runs(); i++) {
                for (var slot : recipe.slots()) {
                    var available = inventory.getOrDefault(slot.key(), BigInteger.ZERO); var amount = BigInteger.valueOf(slot.amount());
                    check(available.compareTo(amount) >= 0, "Spent a future return / absent tool"); inventory.put(slot.key(), available.subtract(amount));
                }
                recipe.executionOutputs().forEach((key, amount) -> inventory.merge(key, BigInteger.valueOf(amount), BigInteger::add));
                check(++fired < 5000, "Small replay expanded without bound");
            }
            cursor.dispatched(batch.runs());
        }
        check(inventory.getOrDefault("product", BigInteger.ZERO).compareTo(BigInteger.valueOf(n)) >= 0, "Primitive replay did not fulfill target");
    }

    private static void runtime(GraphPlan<String> plan, long n) {
        final class Host implements GraphJobRuntime.Adapter<String> {
            GraphJobRuntime<String> job = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            final Map<String, Long> physical = new LinkedHashMap<>(plan.initial()); final List<Map<String, Long>> pending = new ArrayList<>(); long delivered;
            public long capacity(GraphRecipe<String> recipe, long requested) { return requested; }
            public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
                inputs.forEach((key, count) -> { check(physical.getOrDefault(key, 0L) >= count, "Tool still in flight was reused"); physical.merge(key, -count, Long::sum); });
                var output = new LinkedHashMap<String, Long>(); recipe.executionOutputs().forEach((key, count) -> output.put(key, Math.multiplyExact(count, runs)));
                pending.add(output); return GraphJobRuntime.Outcome.ACCEPTED;
            }
            public long deliver(String key, long count) { delivered += count; return count; }
            public long refund(String key, long count) { return count; }
        }
        var host = new Host();
        for (int tick = 0; tick < 3000 && host.delivered < n; tick++) {
            host.job.tick(host, tick * 20L, 8, () -> 0L); host.job = new GraphJobRuntime<>(host.job.snapshot());
            if (tick % 3 != 2) continue;
            Collections.reverse(host.pending);
            for (var output : host.pending) output.forEach((key, count) -> { host.physical.merge(key, count, Long::sum); check(host.job.accept(key, count, false) == count, "Dropped actual returned key"); });
            host.pending.clear();
        }
        check(host.delivered == n, "Selected plan stalled after delayed returns / save-load");
    }
    private static int state(String key) { return Integer.parseInt(key.substring(5)); }
    private static PlanningBudget budget(long cap) { return new PlanningBudget(0, cap, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
