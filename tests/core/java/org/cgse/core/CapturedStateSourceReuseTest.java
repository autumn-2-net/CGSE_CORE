// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Distinct targets can reuse physical source discovery, never old stock or failures. */
public final class CapturedStateSourceReuseTest {
    private static int checks, cases;
    private static long maxHotCompile, maxHotBytes;
    private record Wear(int kind) implements StateFamily<String> {
        public String id() { return "wear:" + kind; }
        public boolean accepts(String key) {
            if (!key.startsWith("tool:")) return false;
            long n = Long.parseLong(key.substring(5));
            return n > 0 && (kind == 0 || kind == 1 && n <= 8 || kind == 2 && n >= 8);
        }
        public long capacity(String key) { long n = Long.parseLong(key.substring(5)); return kind == 2 ? n - 7 : n; }
        public String nominalAfterOne(String key) { long n = Long.parseLong(key.substring(5)); return n == 1 ? "empty" : "tool:" + (n - 1); }
    }
    private record Noise(int number) implements StateFamily<String> {
        public String id() { return "noise:" + number; }
        public boolean accepts(String key) { return key.startsWith("noise-tool:" + number + ":"); }
        public long capacity(String key) { return Long.parseLong(key.substring(key.lastIndexOf(':') + 1)); }
        public String nominalAfterOne(String key) { return capacity(key) == 1 ? "shell:" + number : "noise-tool:" + number + ":1"; }
    }
    private record Input(List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock) {}
    private record Run(GraphPlan<String> plan, boolean hinted, boolean recovered, long compile, long bytes) {}

    public static void main(String[] args) {
        matrix(); mixedTools(); staleSupport(); limits();
        System.out.println("CapturedStateSourceReuseTest: cases=" + cases + "; assertions=" + checks +
                "; max_hot_compile=" + maxHotCompile + "; max_hot_bytes=" + maxHotBytes);
    }

    private static void matrix() {
        for (int filler : new int[]{512, 16_384}) for (long n : new long[]{2, 8192})
            for (boolean stateNoise : List.of(false, true)) for (boolean reverse : List.of(false, true)) {
                var input = input(filler, n, stateNoise, reverse, 1, false);
                var catalog = new CapturedCatalog<>(input.entries()); int pass = 0;
                for (long cap : new long[]{40_000_000, 40_000_000, 20_000, 60_000, 250_000, 2_000_000}) {
                    String target = "product:" + Math.max(0, pass - 1);
                    var run = solve(input, catalog, target, input.stock(), Map.of(), cap, true, false);
                    if (pass == 0) check(catalog.stateSourceMetrics().sources() == 0, "One-off capture retained discovery hints");
                    if (pass >= 2) {
                        check(run.hinted() && !run.recovered(), "Cross-target query rescanned the full capture");
                        check(run.compile() < 2000 && run.bytes() < (2L << 20), "Hot small support ballooned");
                        maxHotCompile = Math.max(maxHotCompile, run.compile()); maxHotBytes = Math.max(maxHotBytes, run.bytes());
                    }
                    pass++;
                }
                check(catalog.stateSourceMetrics().sources() == 1 && catalog.stateSourceMetrics().hits() == 4, "Retained unrelated sources / wrong capture scope");
                check(catalog.stateSupportMetrics().hits() == 0, "Matrix reused identical target supports instead of cross-target sources");
            }
    }

    private static void mixedTools() {
        for (int tools : new int[]{1, 2}) for (boolean manufacture : List.of(false, true)) for (boolean reverse : List.of(false, true)) {
            var input = input(512, Long.MAX_VALUE, false, reverse, tools, manufacture); var catalog = new CapturedCatalog<>(input.entries());
            for (int pass = 0; pass < 2; pass++) solve(input, catalog, "product:0", input.stock(), Map.of(), 40_000_000, true, false);
            var run = solve(input, catalog, "product:1", input.stock(), Map.of(), 20_000, true, false);
            check(run.hinted() && !run.recovered(), "Simultaneous / manufactured tools lost source reuse");
            CapturedStateRecoveryPipelineTest.runtime(run.plan());
        }
    }

    private static void staleSupport() {
        for (boolean reverse : List.of(false, true)) {
            var entries = new ArrayList<CapturedCatalog.Entry<String>>();
            entries.add(entry("use:A", List.of(state(8, 1, new Wear(1)), ordinary("raw:A", 1)), "part:A"));
            entries.add(entry("use:B", List.of(state(10, 1, new Wear(2)), ordinary("raw:B", 1)), "part:B"));
            for (int i = 0; i < 3; i++) entries.add(entry("finish:" + i, List.of(ordinary("tool:8", 1)), "product:" + i));
            addNoise(entries, 512, false); if (reverse) Collections.reverse(entries);
            var input = new Input(entries, Map.of("tool:8", 1L, "raw:A", 2L)); var catalog = new CapturedCatalog<>(entries);
            for (int pass = 0; pass < 2; pass++) solve(input, catalog, "part:A", input.stock(), Map.of(), 40_000_000, true, false);
            check(catalog.stateSourceMetrics().sources() == 1, "Control already retained the omitted alternative");
            var replacement = Map.of("tool:10", 1L, "raw:B", 2L);
            var run = solve(input, catalog, "product:0", replacement, Map.of(), 40_000_000, true, true);
            check(run.hinted() && run.recovered(), "Unfunded old hint prevented original source recovery");
            check(run.plan().recipes().values().stream().anyMatch(r -> r.binding().equals("use:B")), "Did not use newly funded source");
            check(catalog.stateSourceMetrics().sources() == 2, "Verified alternative was not retained");
            run = solve(input, catalog, "product:1", replacement, Map.of(), 60_000, true, true);
            check(run.hinted() && !run.recovered(), "Newly learned source was not usable on another target");
            CapturedStateRecoveryPipelineTest.runtime(run.plan());
            solve(input, catalog, "product:2", Map.of("tool:10", 1L, "raw:B", 1L), Map.of(), 40_000_000, false, true);
            solve(input, catalog, "product:2", Map.of("raw:B", 2L), Map.of(), 40_000_000, false, true);
            solve(input, catalog, "product:2", replacement, Map.of("tool:8", 1L), 40_000_000, false, true);
            // A replacement capture with identical IDs/rule names must start
            // without the old pool. Changing ordinary inputs cannot keep stock.
            var changed = new ArrayList<>(entries);
            for (int i = 0; i < changed.size(); i++) if (changed.get(i).id().equals("use:B"))
                changed.set(i, entry("use:B", List.of(state(10, 1, new Wear(2)), ordinary("other-raw", 1)), "part:B"));
            var other = new CapturedCatalog<>(changed);
            var miss = solve(new Input(changed, replacement), other, "product:0", replacement, Map.of(), 40_000_000, false, true);
            check(!miss.hinted() && other.stateSourceMetrics().sources() == 0, "Source hints crossed a replaced capture");
        }
    }

    private static void limits() {
        var input = input(512, 8, true, true, 1, false); var catalog = new CapturedCatalog<>(input.entries());
        for (int pass = 0; pass < 2; pass++) solve(input, catalog, "product:0", input.stock(), Map.of(), 40_000_000, true, false);
        for (long cap : new long[]{1024, 4096, 8192, 16_384}) {
            var b = budget(cap);
            try (var work = catalog.begin("product:1", 1, input.stock(), false, false, b)) {
                try { while (!work.step()) {} } catch (PlanningBudget.Exhausted expected) { /* Inconclusive work limit. */ }
            }
            check(b.reservedBytes() == 0 && b.searchWork() <= cap + 1, "Hint trial renewed parent search quota / leaked");
        }
        var cancelled = new AtomicBoolean();
        var b = new PlanningBudget(0, 40_000_000, 64L << 20, cancelled::get, System::nanoTime); b.enableMetrics();
        try (var work = catalog.begin("product:4", 1, input.stock(), false, false, b)) {
            while (!work.step()) if (b.diagnostics().contains("candidate_state_source_hints")) { cancelled.set(true); break; }
            check(cancelled.get(), "Did not enter source-hint trial");
            try { work.step(); throw new AssertionError("Hint trial ignored cancellation"); }
            catch (java.util.concurrent.CancellationException expected) { checks++; }
        }
        check(b.reservedBytes() == 0, "Cancelled source-hint trial leaked");
        solve(input, catalog, "product:4", input.stock(), Map.of(), 20_000, true, false);
        // Another user of the shared compilation clock can exhaust the local
        // hint allowance while it is suspended. Its partial work must close,
        // then resume original recovery using the unspent parent allowance.
        b = budget(40_000_000); b.enableMetrics(); boolean injected = false, limited = false, original = false;
        try (var work = catalog.begin("product:3", 1, input.stock(), false, false, b)) {
            while (!work.step()) {
                var trace = b.diagnostics();
                if (!injected && trace.contains("candidate_state_source_hints")) {
                    try (var costs = b.trace(PlanningCostTrace.Origin.ALLOCATION, PlanningCostTrace.Stage.PREPARE)) { b.compilationCharge(70_000); }
                    injected = true;
                }
                limited |= trace.contains("limited; remaining="); original |= trace.contains("candidate_state_support_recovery");
            }
            check(injected && limited && original && work.result().plan().feasible(), "Expired source hint discarded original recovery");
            CapturedStateRecoveryPipelineTest.verify(work.result().plan(), input.entries(), input.stock(), "product:3", Map.of());
        }
        check(b.reservedBytes() == 0 && b.nodes() < 200_000, "Expired hint leaked / renewed its allowance");
        PlanningCostTraceTest.conserved(b); cases++;
    }

    private static Run solve(Input input, CapturedCatalog<String> catalog, String target, Map<String, Long> stock,
            Map<String, Long> seeds, long cap, boolean feasible, boolean observe) {
        var b = budget(cap); b.enableMetrics(); boolean hinted = false, recovered = false; GraphPlan<String> plan;
        try (var work = catalog.begin(target, 1, stock, Set.of(), seeds, false, false, b)) {
            int steps = 0;
            while (!work.step()) if (observe && (steps++ & 31) == 0) {
                var trace = b.diagnostics(); hinted |= trace.contains("candidate_state_source_hints"); recovered |= trace.contains("candidate_state_support_recovery");
            }
            var trace = b.diagnostics(); hinted |= trace.contains("candidate_state_source_hints"); recovered |= trace.contains("candidate_state_support_recovery");
            plan = work.result().plan();
            check(plan.feasible() == feasible, "Wrong cross-target result " + plan.result() + "; " + trace);
            check(!work.result().coverage().complete() && plan.result() != GraphPlan.Result.INFEASIBLE, "Private source hint exported a negative proof");
        }
        check(b.reservedBytes() == 0 && b.searchWork() <= cap + 1, "Cross-target trial leaked / renewed search work");
        PlanningCostTraceTest.conserved(b);
        if (feasible) {
            CapturedStateRecoveryPipelineTest.verify(plan, input.entries(), stock, target, seeds);
            // The stale-support control uses two independently restricted rules.
            for (var recipe : plan.recipes().values()) for (var slot : recipe.slots()) if (slot.key().startsWith("tool:")) {
                long n = Long.parseLong(slot.key().substring(5));
                check(!recipe.binding().equals("use:A") || n <= 8, "Borrowed an ineligible A tool");
                check(!recipe.binding().equals("use:B") || n >= 8, "Borrowed an ineligible B tool");
            }
        }
        cases++; return new Run(plan, hinted, recovered, b.compilationWork(), b.peakBytes());
    }

    private static Input input(int filler, long n, boolean stateNoise, boolean reverse, int tools, boolean manufacture) {
        var entries = new ArrayList<CapturedCatalog.Entry<String>>();
        entries.add(entry("use", List.of(state(n, tools, new Wear(0)), ordinary("raw", 1)), "part"));
        String last = n == 2 ? "empty" : "tool:" + (n - 2);
        for (int i = 0; i < 5; i++) entries.add(entry("finish:" + i, List.of(ordinary(last, tools)), "product:" + i));
        var stock = new LinkedHashMap<String, Long>(); stock.put("raw", 2L);
        if (manufacture) { entries.add(entry("forge", List.of(ordinary("metal", 1)), "tool:" + n)); stock.put("metal", (long) tools); }
        else stock.put("tool:" + n, (long) tools);
        addNoise(entries, filler, stateNoise); if (reverse) Collections.reverse(entries);
        return new Input(entries, stock);
    }
    private static void addNoise(List<CapturedCatalog.Entry<String>> entries, int filler, boolean stateNoise) {
        for (int i = 0; i < filler; i++) {
            var inputs = new ArrayList<CapturedRecipe.Input<String>>();
            if (stateNoise) inputs.add(new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(
                    new CapturedRecipe.Amount<>("noise-tool:" + i + ":2", 1), "noise-tool:" + i + ":1", false, false, new Noise(i)))));
            inputs.add(ordinary("absent:" + i, 1)); entries.add(entry("noise:" + i, inputs, "unused:" + i));
        }
    }
    private static CapturedRecipe.Input<String> state(long n, int tools, StateFamily<String> family) {
        return new CapturedRecipe.Input<>(tools, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("tool:" + n, 1), "tool:" + (n - 1), false, false, family)), CapturedRecipe.InputMode.INDIVISIBLE);
    }
    private static CapturedRecipe.Input<String> ordinary(String key, long count) {
        return new CapturedRecipe.Input<>(count, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false)));
    }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, String out) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>(out, 1)), false, false));
    }
    private static PlanningBudget budget(long cap) { return new PlanningBudget(0, cap, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
