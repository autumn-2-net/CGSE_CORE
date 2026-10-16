// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Repeated cyclic tool requests must rediscover physical stock, not the same catalog support. */
public final class CapturedStateReuseTest {
    private static int checks, feasible;
    private record Rule(boolean even) implements StateFamily<String> {
        public String id() { return even ? "even" : "odd"; }
        public boolean accepts(String k) { return k.startsWith("tool:") && (Integer.parseInt(k.substring(5)) % 2 == 0) == even; }
        public long capacity(String k) { return accepts(k) ? 1 : 0; }
        public String nominalAfterOne(String k) { return "tool:" + ((Integer.parseInt(k.substring(5)) + 1) % 4); }
    }

    public static void main(String[] args) {
        budgets(); scopes(); parentAllowance();
        System.out.println("CapturedStateReuseTest: feasible=" + feasible + "; assertions=" + checks);
    }

    private static void budgets() {
        for (int filler : new int[]{512, 16384}) for (boolean fullStock : List.of(false, true)) for (boolean reverse : List.of(false, true)) {
            var sources = entries(filler); if (reverse) Collections.reverse(sources);
            var catalog = new CapturedCatalog<>(sources); int run = 0;
            for (long cap : new long[]{40_000_000, 40_000_000, 10_000, 20_000, 60_000, 250_000}) {
                long n = 7 + run % 2; var stock = new LinkedHashMap<String, Long>();
                stock.put("tool:" + (run % 2 * 2), 1L); stock.put("raw", n);
                if (fullStock) for (int i = 0; i < filler; i++) stock.put("noise-input:" + i, 1L);
                var b = budget(cap); var result = solve(catalog, stock, n, Map.of(), false, false, b);
                check(result.plan().feasible(), "Funded state path was cut off: " + b.diagnostics());
                verify(result.plan(), stock, n, Map.of(), false); feasible++;
                check(!result.coverage().complete(), "Opaque state family acquired complete coverage");
                if (run == 0) check(catalog.stateSupportMetrics().entries() == 0, "One-off capture retained source hints");
                if (run >= 2) {
                    check(b.diagnostics().contains("reused; selected=2;"), "Repeated target rescanned its full source catalog");
                    check(b.cachePreparationWork() == 0, "Cache hit renewed retention work");
                    if (fullStock) check(b.diagnostics().contains("candidate_state_stock") && b.diagnostics().contains("projected=2;"), "Large stock did not use the positive key trial");
                    check(b.compilationWork() < 1000, "Repeated state discovery rescanned unrelated producer/stock structure");
                    check(b.peakBytes() < 600_000, "Repeated request copied unrelated source/stock structure");
                }
                check(!b.failureDetail().startsWith("PROVEN_INFEASIBLE"), "Private capacity failure published a global failure detail");
                check(b.searchWork() <= cap && b.compilationWork() <= cap, "Repeated state path renewed request allowances");
                run++;
            }
            check(catalog.stateSupportMetrics().hits() == 4 && catalog.stateSupportMetrics().entries() == 1, "Unexpected source scope reuse");
        }
    }

    private static void scopes() {
        var sources = entries(256);
        sources.add(entry("forge", List.of(ordinary("metal")), "tool:2"));
        sources.add(entry("seed", List.of(ordinary("gold")), "token"));
        var catalog = new CapturedCatalog<>(sources);
        for (int i = 0; i < 2; i++) solve(catalog, Map.of("tool:0", 1L, "raw", 7L), 7, Map.of(), false, false, budget(40_000_000));
        var stocks = List.of(Map.of("tool:0", 1L, "raw", 7L), Map.of("raw", 7L),
                Map.of("metal", 1L, "raw", 7L), Map.of("tool:2", 1L, "raw", 8L),
                Map.of("tool:0", 1L, "tool:2", 2L, "raw", 7L), Map.of("raw", 7L));
        for (var stock : stocks) {
            var b = budget(40_000_000); long n = stock.get("raw");
            long hits = catalog.stateSupportMetrics().hits();
            var hot = solve(catalog, stock, n, Map.of(), false, false, b);
            check(catalog.stateSupportMetrics().hits() == hits + 1, "Changed stock invalidated stock-independent source selection");
            var cold = solve(new CapturedCatalog<>(sources), stock, n, Map.of(), false, false, budget(40_000_000));
            check(hot.plan().result() == cold.plan().result(), "Old stock result survived structural reuse");
            if (stock.size() > 1) { check(hot.plan().feasible(), "Stock/manufactured tool was lost"); verify(hot.plan(), stock, n, Map.of(), false); }
            else check(hot.plan().result() == GraphPlan.Result.UNKNOWN && b.diagnostics().contains("retry_full_state"), "Support miss excluded original state attempt");
        }
        for (boolean preserve : List.of(false, true)) for (boolean force : List.of(false, true)) for (long seeds : new long[]{1, 2}) {
            var stock = Map.of("tool:0", 1L, "raw", 8L, "product", 2L, "gold", 2L);
            var required = Map.of("token", seeds); var b = budget(40_000_000);
            var plan = solve(catalog, stock, 8, required, preserve, force, b).plan();
            check(plan.feasible(), "Changed seed amounts/preservation/force lost feasible request: " + b.diagnostics());
            verify(plan, stock, 8, required, force);
            check(catalog.stateSupportMetrics().entries() == 2, "Seed amount/force flags duplicated structural scope");
        }
        var changed = new ArrayList<>(sources); changed.set(0, entry("even", List.of(tool(true), ordinary("other-raw")), "part"));
        var replacement = new CapturedCatalog<>(changed); var b = budget(40_000_000);
        check(!solve(replacement, Map.of("tool:0", 1L, "raw", 7L), 7, Map.of(), false, false, b).plan().feasible(), "Same recipe IDs borrowed obsolete semantics");
        check(replacement.stateSupportMetrics().hits() == 0, "Source hint crossed capture identity");
        var ordinary = new CapturedCatalog<>(List.of(entry("plain", List.of(ordinary("raw")), "product")));
        for (int i = 0; i < 3; i++) solve(ordinary, Map.of("raw", 1L), 1, Map.of(), false, false, budget(10000));
        check(ordinary.stateSupportMetrics().entries() == 0, "Ordinary capture allocated state hints");
    }

    private static void parentAllowance() {
        for (long allowance : new long[]{512, 1024, 4096}) {
            var b = budget(40_000_000);
            try (var work = new CapturedStatePlanning<>(entries(0), "product", 1_000_000_000L,
                    Map.of("tool:0", 1L, "raw", 1_000_000_000L), Set.of(), Map.of(), false, false, b, allowance)) {
                while (!work.step()) {}
                check(work.result() == null && b.nodes() < allowance + 512, "Child renewed parent's original allowance");
            }
            check(b.reservedBytes() == 0, "Limited state boundary leaked memory");
        }
    }

    private static CapturedCatalog.Result<String> solve(CapturedCatalog<String> catalog, Map<String, Long> stock, long n,
            Map<String, Long> seeds, boolean preserve, boolean force, PlanningBudget b) {
        CapturedCatalog.Result<String> result;
        try (var work = catalog.begin("product", n, stock, Set.of(), seeds, preserve, force, b)) {
            while (!work.step()) {} result = work.result();
        }
        check(b.reservedBytes() == 0, "Completed request retained workspace");
        PlanningCostTraceTest.conserved(b); return result;
    }

    private static void verify(GraphPlan<String> plan, Map<String, Long> stock, long n, Map<String, Long> seeds, boolean force) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        plan.initialExact().forEach((k, v) -> check(v.compareTo(BigInteger.valueOf(stock.getOrDefault(k, 0L))) <= 0, "Invented inventory"));
        var held = new HashMap<String, Long>(stock); var cursor = new PlanCursor(plan.steps()); long made = 0, firings = 0;
        while (cursor.current() != null) {
            var batch = cursor.current(); var recipe = plan.recipes().get(batch.recipe());
            check(batch.runs() <= 64 && (firings += batch.runs()) < 128, "Unexpected primitive replay size");
            for (long i = 0; i < batch.runs(); i++) {
                String binding = recipe.binding(); var out = new HashMap<String, Long>();
                if (binding.equals("even") || binding.equals("odd")) {
                    boolean even = binding.equals("even");
                    check(recipe.slots().size() == 2, "Changed source input arity");
                    for (var slot : recipe.slots()) {
                        check(slot.amount() == 1 && !slot.configuration(), "Changed physical entity demand");
                        if (slot.inputSlot() == 0) {
                            check(slot.key().startsWith("tool:") && (Integer.parseInt(slot.key().substring(5)) % 2 == 0) == even, "Used an ineligible tool");
                            out.put("tool:" + ((Integer.parseInt(slot.key().substring(5)) + 1) % 4), 1L);
                        } else check(slot.inputSlot() == 1 && slot.key().equals(even ? "raw" : "part"), "Changed ordinary input");
                    }
                    out.put(even ? "part" : "product", 1L); if (!even) made++;
                } else {
                    check(binding.equals("forge") || binding.equals("seed"), "Executed unrelated source");
                    check(recipe.inputs().equals(Map.of(binding.equals("forge") ? "metal" : "gold", 1L)), "Changed manufacturing input");
                    out.put(binding.equals("forge") ? "tool:2" : "token", 1L);
                }
                check(out.equals(recipe.executionOutputs()), "Changed physical return contract");
                for (var slot : recipe.slots()) {
                    long have = held.getOrDefault(slot.key(), 0L); check(have >= slot.amount(), "Used a future return or one tool twice");
                    held.put(slot.key(), have - slot.amount());
                }
                out.forEach((k, v) -> held.merge(k, v, Math::addExact));
            }
            cursor.dispatched(batch.runs());
        }
        check(held.getOrDefault("product", 0L) >= n && (!force || made >= n), "Target/forced production not met");
        seeds.forEach((k, v) -> check(held.getOrDefault(k, 0L) >= v, "Lost required seed"));
    }

    private static ArrayList<CapturedCatalog.Entry<String>> entries(int filler) {
        var entries = new ArrayList<>(List.of(entry("even", List.of(tool(true), ordinary("raw")), "part"),
                entry("odd", List.of(tool(false), ordinary("part")), "product")));
        for (int i = 0; i < filler; i++) entries.add(entry("noise:" + i, List.of(ordinary("noise-input:" + i)), "noise-output:" + i));
        return entries;
    }
    private static CapturedRecipe.Input<String> ordinary(String k) {
        return new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(k, 1), null, false)), CapturedRecipe.InputMode.INDIVISIBLE);
    }
    private static CapturedRecipe.Input<String> tool(boolean even) {
        String k = even ? "tool:0" : "tool:3"; var f = new Rule(even);
        return new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(k, 1), f.nominalAfterOne(k), false, false, f)), CapturedRecipe.InputMode.INDIVISIBLE);
    }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> in, String out) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(in, List.of(new CapturedRecipe.Amount<>(out, 1)), false, false));
    }
    private static PlanningBudget budget(long work) { var b = new PlanningBudget(0, work, 64L << 20, () -> false, System::nanoTime); b.enableMetrics(); return b; }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
