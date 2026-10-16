// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Cross-target producer reuse with independent physical execution of each witness. */
public final class CapturedStateProducerReuseTest {
    private static final AtomicInteger checks = new AtomicInteger();
    private static int feasible;
    private static final StateFamily<String> RULE = new StateFamily<>() {
        public String id() { return "wear"; }
        public boolean accepts(String key) { return key.startsWith("tool:"); }
        public long capacity(String key) { return accepts(key) ? Long.parseLong(key.substring(5)) : 0; }
        public String nominalAfterOne(String key) { long n = capacity(key); return n > 1 ? "tool:" + (n - 1) : null; }
    };

    public static void main(String[] args) throws Exception {
        budgets(); currentInputs(); cancelledScans(); concurrent();
        System.out.println("CapturedStateProducerReuseTest: feasible=" + feasible + "; assertions=" + checks);
    }

    private static void budgets() {
        for (int filler : new int[]{512, 16384}) for (boolean manufacture : List.of(false, true)) for (boolean reverse : List.of(false, true)) {
            var sources = entries(filler, 5, false); if (reverse) Collections.reverse(sources);
            var catalog = new CapturedCatalog<>(sources); int run = 0;
            for (long cap : new long[]{40_000_000, 40_000_000, 20_000, 60_000, 250_000, 2_000_000}) {
                long n = 7 + run % 2; String target = "product:" + Math.max(0, run - 1);
                var stock = manufacture ? Map.of("raw", n, "metal", 1L) : Map.of("raw", n, "tool:16", 1L);
                var b = budget(cap, 64L << 20); var plan = solve(catalog, target, n, stock, Set.of(), Map.of(), false, false, b).plan();
                check(plan.feasible(), "Cross-target state request lost a funded witness: " + b.diagnostics());
                verify(plan, stock, target, n, Set.of(), Map.of(), false); feasible++;
                if (run == 0) check(catalog.stateProducerMetrics().entries() == 0 && b.cachePreparationWork() == 0, "One-off capture retained a rule projection");
                if (run >= 2) {
                    check(b.diagnostics().contains("candidate_state_producers") && b.diagnostics().contains("reused; keys=2;"), "New target rescanned unrelated producer keys");
                    check(b.compilationWork() < 1000, "Cross-target selection repeated whole-catalog analysis");
                    check(catalog.stateProducerMetrics().hits() == run - 1, "Unexpected cross-target rule scope");
                }
                run++;
            }
            check(catalog.stateSupportMetrics().hits() == 0, "Matrix accidentally reused identical target supports");
            check(catalog.stateProducerMetrics().entries() == 1 && catalog.stateProducerMetrics().bytes() < 512, "Small producer boundary retained unrelated structure");
        }
    }

    private static void currentInputs() {
        var sources = entries(256, 20, true); var catalog = new CapturedCatalog<>(sources);
        warm(catalog);
        for (boolean preserve : List.of(false, true)) for (boolean force : List.of(false, true)) {
            int id = (preserve ? 2 : 0) + (force ? 1 : 0) + 1; String target = "product:" + id;
            var stock = Map.of("raw", 8L, "metal", 1L, "gold", 2L, target, 2L); var seeds = Map.of("token", 2L);
            var b = budget(40_000_000, 64L << 20);
            var plan = solve(catalog, target, 8, stock, Set.of(), seeds, preserve, force, b).plan();
            check(plan.feasible() && b.diagnostics().contains("candidate_state_producers"), "Changed flags/seeds lost the compatible producer boundary");
            verify(plan, stock, target, 8, Set.of(), seeds, force);
        }
        var stocks = List.of(Map.of("raw", 8L), Map.of("raw", 8L, "tool:1", 1L), Map.of("metal", 1L));
        int id = 5;
        for (var stock : stocks) {
            var b = budget(40_000_000, 64L << 20); var result = solve(catalog, "product:" + id++, 8, stock, Set.of(), Map.of(), false, false, b);
            check(!result.plan().feasible() && !result.coverage().complete(), "Old stock or restricted coverage manufactured a result");
            check(!b.failureDetail().startsWith("PROVEN_INFEASIBLE"), "Private state selection exported a global negative proof");
        }
        var stock = Map.of("metal", 1L); var b = budget(40_000_000, 64L << 20);
        var plan = solve(catalog, "product:8", 8, stock, Set.of("raw"), Map.of(), false, false, b).plan();
        check(plan.feasible(), "Current external input was dropped"); verify(plan, stock, "product:8", 8, Set.of("raw"), Map.of(), false);
        check(!solve(catalog, "product:8", 8, stock, Set.of(), Map.of(), false, false, budget(40_000_000, 64L << 20)).plan().feasible(), "Removed external input survived cache reuse");
        // Identical source IDs and rule objects do not identify a replaced catalog.
        var changed = entries(256, 20, true); changed.set(1, entry("forge", List.of(ordinary("other-metal")), "tool:16"));
        var replacement = new CapturedCatalog<>(changed);
        check(!solve(replacement, "product:1", 8, Map.of("raw", 8L, "metal", 1L), Set.of(), Map.of(), false, false, budget(40_000_000, 64L << 20)).plan().feasible(),
                "Replacement capture reused obsolete supply semantics");
        check(replacement.stateProducerMetrics().hits() == 0, "Producer projection crossed capture replacement");
        // A newly supplied worn key was not present in the previous producer projection.
        changed.add(entry("extra-forge", List.of(ordinary("metal")), "tool:24")); replacement = new CapturedCatalog<>(changed);
        plan = solve(replacement, "product:2", 8, Map.of("raw", 8L, "metal", 1L), Set.of(), Map.of(), false, false, budget(40_000_000, 64L << 20)).plan();
        check(plan.feasible() && plan.recipes().values().stream().anyMatch(r -> r.binding().equals("extra-forge")), "New producer was hidden by an old finite projection");
        verify(plan, Map.of("raw", 8L, "metal", 1L), "product:2", 8, Set.of(), Map.of(), false);
        for (long cap : new long[]{1, 64, 512, 4096, 16384, 40000}) for (long memory : new long[]{2048, 4096, 16384, 64L << 20}) {
            b = budget(cap, memory); stock = Map.of("raw", 8L, "metal", 1L);
            var result = solve(catalog, "product:9", 8, stock, Set.of(), Map.of(), false, false, b);
            if (result.plan().feasible()) verify(result.plan(), stock, "product:9", 8, Set.of(), Map.of(), false);
            check(b.searchWork() <= cap + 1 && b.compilationWork() <= cap + 1 && b.peakBytes() <= memory, "Reuse bypassed request limits");
        }
    }

    private static void cancelledScans() {
        var sources = entries(8192, 2, false); var index = CapturedStateSupportTest.index(sources);
        var cache = new CapturedStateProducers<String>(); var cancelled = new AtomicBoolean();
        var b = new PlanningBudget(0, 40_000_000, 64L << 20, cancelled::get, System::nanoTime);
        try (var work = CapturedStateSupport.create(sources, index, "product:0", Set.of(), b, cache)) {
            for (int i = 0; i < 100; i++) check(!work.step(), "Long producer scan ended early");
            cancelled.set(true);
            try { work.step(); throw new AssertionError("Cancelled family scan continued"); } catch (java.util.concurrent.CancellationException expected) {}
        }
        check(b.reservedBytes() == 0 && cache.metrics().entries() == 0, "Cancelled scan published a partial producer set");
        b = budget(16_384, 64L << 20);
        try (var work = CapturedStateSupport.create(sources, index, "product:0", Set.of(), b, cache)) {
            while (!work.step()) {} check(work.result() == null && cache.metrics().entries() == 0, "Local work limit published truncated rule support");
        }
        check(b.reservedBytes() == 0, "Limited support leaked");
    }

    private static void concurrent() throws Exception {
        var catalog = new CapturedCatalog<>(entries(2048, 12, false)); warm(catalog);
        var pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 4; i++) {
                final int targetIndex = i + 1;
                tasks.add(() -> {
                    String target = "product:" + targetIndex; long n = 5 + targetIndex;
                    var stock = Map.of("raw", n, "metal", 1L); var b = budget(20_000, 64L << 20);
                    var plan = solve(catalog, target, n, stock, Set.of(), Map.of(), false, false, b).plan();
                    check(plan.feasible(), "Concurrent cross-target request lost its witness");
                    verify(plan, stock, target, n, Set.of(), Map.of(), false); return null;
                });
            }
            for (var task : pool.invokeAll(tasks)) task.get();
        } finally { pool.shutdownNow(); check(pool.awaitTermination(10, TimeUnit.SECONDS), "Workers did not terminate"); }
    }

    private static void warm(CapturedCatalog<String> catalog) {
        for (int i = 0; i < 2; i++) check(solve(catalog, "product:0", 8, Map.of("raw", 8L, "metal", 1L), Set.of(), Map.of(), false, false,
                budget(40_000_000, 64L << 20)).plan().feasible(), "Cannot prepare shared capture");
    }
    private static CapturedCatalog.Result<String> solve(CapturedCatalog<String> catalog, String target, long n, Map<String, Long> stock,
            Set<String> external, Map<String, Long> seeds, boolean preserve, boolean force, PlanningBudget b) {
        CapturedCatalog.Result<String> result;
        try (var work = catalog.begin(target, n, stock, external, seeds, preserve, force, b)) {
            try { while (!work.step()) {} result = work.result(); }
            catch (PlanningBudget.Exhausted limit) { result = work.limited(limit); }
        }
        check(b.reservedBytes() == 0, "Completed/capped request retained workspace");
        PlanningCostTraceTest.conserved(b); return result;
    }

    private static void verify(GraphPlan<String> plan, Map<String, Long> stock, String target, long n,
                               Set<String> external, Map<String, Long> seeds, boolean force) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        plan.initialExact().forEach((k, v) -> check(external.contains(k) || v.compareTo(BigInteger.valueOf(stock.getOrDefault(k, 0L))) <= 0, "Invented initial stock"));
        var held = new HashMap<>(stock); var cursor = new PlanCursor(plan.steps()); long made = 0, firings = 0;
        while (cursor.current() != null) {
            var batch = cursor.current(); var recipe = plan.recipes().get(batch.recipe()); var binding = recipe.binding();
            check(batch.runs() <= 32 && (firings += batch.runs()) < 128, "Unexpected primitive replay size");
            for (long i = 0; i < batch.runs(); i++) {
                var output = new HashMap<String, Long>();
                if (binding.equals("press")) {
                    check(recipe.slots().size() == 2, "Changed physical arity");
                    for (var slot : recipe.slots()) {
                        check(slot.amount() == 1 && !slot.configuration(), "Changed slot entity demand");
                        if (slot.inputSlot() == 0) {
                            check(slot.key().startsWith("tool:"), "Used unrelated state"); long wear = Long.parseLong(slot.key().substring(5));
                            check(wear > 0, "Used a spent tool"); if (wear > 1) output.put("tool:" + (wear - 1), 1L);
                        } else check(slot.inputSlot() == 1 && slot.key().equals("raw"), "Changed ordinary material");
                    }
                    output.put("part", 1L);
                } else {
                    String input = binding.startsWith("finish:") ? "part" : binding.equals("seed") ? "gold" : "metal";
                    String product = binding.startsWith("finish:") ? "product:" + binding.substring(7) : binding.equals("seed") ? "token" : binding.equals("extra-forge") ? "tool:24" : "tool:16";
                    check(binding.startsWith("finish:") || binding.equals("forge") || binding.equals("extra-forge") || binding.equals("seed"), "Executed unrelated source");
                    check(recipe.inputs().equals(Map.of(input, 1L)), "Changed source inputs"); output.put(product, 1L); if (product.equals(target)) made++;
                }
                check(output.equals(recipe.executionOutputs()), "Changed physical return contract");
                for (var slot : recipe.slots()) if (!external.contains(slot.key())) {
                    long have = held.getOrDefault(slot.key(), 0L); check(have >= slot.amount(), "Borrowed a future return / another entity"); held.put(slot.key(), have - slot.amount());
                }
                output.forEach((k, v) -> held.merge(k, v, Math::addExact));
            }
            cursor.dispatched(batch.runs());
        }
        check(held.getOrDefault(target, 0L) >= n && (!force || made >= n), "Target/forced output missing");
        seeds.forEach((k, v) -> check(held.getOrDefault(k, 0L) >= v, "Required seed consumed"));
    }

    private static ArrayList<CapturedCatalog.Entry<String>> entries(int filler, int targets, boolean seed) {
        var tool = new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("tool:32", 1), "tool:31", false, false, RULE)), CapturedRecipe.InputMode.INDIVISIBLE);
        var result = new ArrayList<>(List.of(entry("press", List.of(tool, ordinary("raw")), "part"), entry("forge", List.of(ordinary("metal")), "tool:16")));
        for (int i = 0; i < targets; i++) result.add(entry("finish:" + i, List.of(ordinary("part")), "product:" + i));
        if (seed) result.add(entry("seed", List.of(ordinary("gold")), "token"));
        for (int i = 0; i < filler; i++) result.add(entry("noise:" + i, List.of(ordinary("noise-input:" + i)), "noise-output:" + i));
        return result;
    }
    private static CapturedRecipe.Input<String> ordinary(String key) {
        return new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false)), CapturedRecipe.InputMode.INDIVISIBLE);
    }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, String output) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>(output, 1)), false, false));
    }
    private static PlanningBudget budget(long cap, long bytes) {
        var budget = new PlanningBudget(0, cap, bytes, () -> false, System::nanoTime); budget.enableMetrics(); return budget;
    }
    private static void check(boolean value, String message) { checks.incrementAndGet(); if (!value) throw new AssertionError(message); }
}
