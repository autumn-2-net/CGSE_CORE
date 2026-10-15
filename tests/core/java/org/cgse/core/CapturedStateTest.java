// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;

/** Capacity is a proposal only; every selected use must own exact physical entities. */
public final class CapturedStateTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        for (int amount : new int[]{1, 2, 100, 768}) request(amount, 8192, false);
        request(100, 1L << 31, false);
        request(100, 1L << 31, true);
        physicalEntities();
        newTools();
        configurationBuild();
        rejectedAlgebra();
        unchangedAndSaturated();
        scopesAndLimits();
        System.out.println("CapturedStateTest: " + assertions + " assertions passed");
    }

    private static void request(int amount, long capacity, boolean last) throws Exception {
        var family = new Family(1, false);
        var inputs = new ArrayList<CapturedRecipe.Input<String>>();
        var tool = tool(family, capacity, 1, CapturedRecipe.InputMode.INDIVISIBLE);
        if (!last) inputs.add(tool);
        inputs.add(ordinary("raw"));
        var stock = new LinkedHashMap<String, Long>();
        stock.put("tool" + capacity, 1L); stock.put("raw", (long) amount);
        for (int slot = 0; slot < 8; slot++) {
            inputs.add(new CapturedRecipe.Input<>(1, List.of(candidate("a" + slot), candidate("b" + slot)), CapturedRecipe.InputMode.INDIVISIBLE));
            stock.put("a" + slot, (long) amount);
        }
        if (last) inputs.add(tool);
        var source = entry("make", inputs, "product");
        var catalog = new CapturedCatalog<>(List.of(source));
        var budget = budget(); budget.enableMetrics();
        var request = new RequestPlanningWork<>(new GraphCompiler<String>(List.of()), "product", amount, stock, Set.of(), null,
                false, false, "DEFAULT", CatalystPolicy.MINIMAL, false, true, budget, () -> false, report -> {
                    throw new AssertionError("Capacity witness entered fallback");
                }).capturedCatalog(catalog);
        GraphPlan<String> plan;
        try (var scheduler = new PlanningScheduler(1, 2, 1024, 2_000_000)) {
            plan = scheduler.submit(request, budget).get(30, TimeUnit.SECONDS);
        }
        check(plan.feasible(), "Capacity request failed: " + amount + "/" + capacity + "/" + last);
        verify(plan, stock);
        check(plan.initialExact().get("tool" + capacity).equals(BigInteger.ONE), "Initial entity multiplied");
        check(family.transitions <= amount + 2, "Unused state space was expanded: " + family.transitions);
        check(plan.recipes().size() <= amount + 1, "Unused concrete variants retained");
        check(budget.peakBytes() < (32L << 20), "Capacity proposal workspace ballooned");
        check(budget.reservedBytes() == 0, "State request leaked budget ownership");
        PlanningCostTraceTest.conserved(budget);
        System.out.println("state amount=" + amount + "; capacity=" + capacity + "; last=" + last + "; search=" +
                budget.searchWork() + "; compile=" + budget.compilationWork() + "; peak=" + budget.peakBytes());
    }

    private static void physicalEntities() {
        var family = new Family(1, true);
        var one = entry("one", List.of(tool(family, 100, 1, CapturedRecipe.InputMode.INDIVISIBLE), ordinary("raw")), "product");
        // Different worn keys share one rule, but each activation carries its
        // own remaining capacity. No fresh tool candidate is stocked here.
        var stock = Map.of("tool2", 1L, "tool3", 1L, "raw", 5L);
        GraphPlan<String> plan = solve(List.of(one), 5, stock);
        check(plan != null, "Different worn tools were not combined as separate entities");
        verify(plan, stock);
        check(plan.initial().getOrDefault("tool100", 0L) == 0, "Declared fresh tool became stock");
        check(plan.recipes().values().stream().anyMatch(r -> r.outputs().containsKey("broken")), "Final broken return lost");
        check(solve(List.of(one), 6, Map.of("tool2", 1L, "tool3", 1L, "raw", 6L)) == null,
                "More uses than actual tool capacity were accepted");

        var pair = entry("pair", List.of(tool(family, 100, 2, CapturedRecipe.InputMode.MIXED), ordinary("raw")), "product");
        check(solve(List.of(pair), 1, Map.of("tool100", 1L, "raw", 1L)) == null,
                "One entity occupied two simultaneous slots");
        stock = Map.of("tool2", 1L, "tool3", 1L, "raw", 2L);
        plan = solve(List.of(pair), 2, stock);
        check(plan != null, "Two different worn entities could not fill mixed slots"); verify(plan, stock);
        check(solve(List.of(pair), 3, Map.of("tool2", 1L, "tool100", 1L, "raw", 3L)) == null,
                "Pooled long-lived entity masked exhausted second slot");

        var twoSlots = entry("twoSlots", List.of(tool(family, 100, 1, CapturedRecipe.InputMode.INDIVISIBLE),
                tool(family, 100, 1, CapturedRecipe.InputMode.INDIVISIBLE), ordinary("raw")), "product");
        check(solve(List.of(twoSlots), 1, Map.of("tool100", 1L, "raw", 1L)) == null,
                "A return was reused before all slots owned their input");

        var cost2 = new Family(2, false);
        var cost3 = new Family(3, false);
        var both = entry("both", List.of(tool(cost2, 6, 1, CapturedRecipe.InputMode.INDIVISIBLE),
                tool(cost3, 6, 1, CapturedRecipe.InputMode.INDIVISIBLE), ordinary("raw")), "product");
        check(solve(List.of(both), 1, Map.of("tool6", 1L, "raw", 1L)) == null,
                "One physical tool activated twice into different cost families");
        stock = Map.of("tool6", 2L, "raw", 1L);
        plan = solve(List.of(both), 1, stock);
        check(plan != null, "Independent cost families lost distinct entities"); verify(plan, stock);
    }

    private static void newTools() {
        var family = new Family(1, false);
        var use = entry("use", List.of(tool(family, 4, 1, CapturedRecipe.InputMode.INDIVISIBLE), ordinary("raw")), "product");
        var build = entry("build", List.of(ordinary("metal")), "tool4");
        var stock = Map.of("tool2", 1L, "metal", 2L, "raw", 10L);
        var plan = solve(List.of(use, build), 10, stock);
        check(plan != null, "Exhausted tools did not trigger their ordinary producer"); verify(plan, stock);
        long made = 0;
        for (var count : plan.patternTimes().entrySet())
            if (plan.recipes().get(count.getKey()).binding().equals("build")) made += count.getValue();
        check(made == 2, "New physical tool manufacture was miscounted");
    }

    private static void configurationBuild() {
        var family = new Family(1, false);
        var use = entry("use", List.of(tool(family, 1, 1, CapturedRecipe.InputMode.INDIVISIBLE), ordinary("raw")), "product");
        var configuration = new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(
                new CapturedRecipe.Amount<>("configuration", 1), null, true, true)), CapturedRecipe.InputMode.INDIVISIBLE);
        var build = entry("build", List.of(configuration, ordinary("metal")), "tool1");
        var stock = Map.of("configuration", 1L, "metal", 10L, "raw", 10L);
        var plan = solve(List.of(use, build), 10, stock);
        check(plan != null, "A multi-run ordinary producer multiplied its reusable configuration input");
        verify(plan, stock);
        check(plan.initial().get("configuration") == 1, "Virtual read arc became ten physical inputs");
    }

    private static void rejectedAlgebra() {
        StateFamily<String> bad = new StateFamily<>() {
            public String id() { return "bad"; }
            public boolean accepts(String key) { return key.startsWith("tool"); }
            public long capacity(String key) { return key.equals("tool100") ? 100 : 0; }
            public String nominalAfterOne(String key) { return "tool98"; }
        };
        var source = entry("bad", List.of(tool(bad, 100, 1, CapturedRecipe.InputMode.INDIVISIBLE), ordinary("raw")), "product");
        check(solve(List.of(source), 2, Map.of("tool100", 1L, "raw", 2L)) == null,
                "Inconsistent state algebra was trusted");
    }

    private static void unchangedAndSaturated() {
        StateFamily<String> unchanged = new StateFamily<>() {
            public String id() { return "unchanged"; }
            public boolean accepts(String key) { return key.equals("tool1"); }
            public long capacity(String key) { return Long.MAX_VALUE; }
            public String nominalAfterOne(String key) { return key; }
        };
        var source = entry("stable", List.of(tool(unchanged, 1, 1, CapturedRecipe.InputMode.INDIVISIBLE), ordinary("raw")), "product");
        var stock = Map.of("tool1", 1L, "raw", 100L);
        var plan = solve(List.of(source), 100, stock);
        check(plan != null, "Zero-wear/unchanged tool was rejected by a strict capacity decrement");
        verify(plan, stock);
        check(plan.initial().get("tool1") == 1, "Unchanged entity was multiplied");

        StateFamily<String> saturated = new StateFamily<>() {
            public String id() { return "saturated"; }
            public boolean accepts(String key) { return key.startsWith("sat"); }
            public long capacity(String key) { return new BigInteger(key.substring(3)).min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact(); }
            public String nominalAfterOne(String key) { return "sat" + new BigInteger(key.substring(3)).subtract(BigInteger.ONE); }
        };
        String initial = "sat" + BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.valueOf(500));
        var input = new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(initial, 1),
                saturated.nominalAfterOne(initial), false, false, saturated)), CapturedRecipe.InputMode.INDIVISIBLE);
        source = entry("saturated", List.of(input, ordinary("raw")), "product");
        stock = Map.of(initial, 1L, "raw", 100L);
        plan = solve(List.of(source), 100, stock);
        check(plan != null, "Saturated guaranteed capacity was treated as an exact ordinal");
        verify(plan, stock);
    }

    private static void scopesAndLimits() {
        var family = new Family(1, false);
        var source = entry("make", List.of(tool(family, 1, 1, CapturedRecipe.InputMode.INDIVISIBLE), ordinary("raw")), "product");
        var catalog = new CapturedCatalog<>(List.of(source));
        var stock = Map.of("tool1", 1L, "raw", 2L);
        var budget = budget();
        try (var work = catalog.begin("product", 2, stock, false, false, budget)) {
            while (!work.step()) {}
            check(!work.result().plan().feasible(), "One-use tool supplied two uses");
            check(!work.result().coverage().complete(), "Finite representative was published as full state coverage");
            check(work.result().plan().result() == GraphPlan.Result.UNKNOWN, "Auxiliary negative was promoted to infeasibility");
            boolean rejected = false;
            try { work.certificate(100000, 1 << 20); }
            catch (UnsupportedOperationException expected) { rejected = true; }
            check(rejected, "Finite-domain proof silently certified unexported state algebra");
        }
        check(budget.reservedBytes() == 0, "Negative state scope leaked workspace");

        budget = budget();
        try (var work = new CapturedStatePlanning<>(List.of(source), "product", 1, stock, Set.of(), Map.of(), false, false, budget)) {
            budget.cancel();
            boolean cancelled = false;
            try { work.step(); } catch (CancellationException expected) { cancelled = true; }
            check(cancelled, "Cancellation was swallowed by optional state planning");
        }
        check(budget.reservedBytes() == 0, "Cancelled state planning leaked workspace");

        budget = new PlanningBudget(0, 40_000_000, 1024, () -> false, System::nanoTime);
        try (var work = new CapturedStatePlanning<>(List.of(source), "product", 1, stock, Set.of(), Map.of(), false, false, budget)) {
            check(work.step() && work.result() == null, "Optional state workspace did not decline cleanly");
        }
        check(budget.reservedBytes() == 0, "Declined state workspace leaked");

        budget = new PlanningBudget(0, 512, 1 << 20, () -> false, System::nanoTime);
        try (var work = new CapturedStatePlanning<>(List.of(source), "product", 1, stock, Set.of(), Map.of(), false, false, budget)) {
            check(work.step() && work.result() == null, "Optional state work stole a tiny solve budget");
        }
        check(budget.searchWork() == 0 && budget.reservedBytes() == 0, "Tiny-budget refusal spent search work");

        var huge = new Family(1, false);
        var longOrder = entry("long", List.of(tool(huge, 1L << 31, 1, CapturedRecipe.InputMode.INDIVISIBLE), ordinary("raw")), "product");
        budget = new PlanningBudget(0, 50000, 64L << 20, () -> false, System::nanoTime);
        try (var work = new CapturedStatePlanning<>(List.of(longOrder), "product", 1_000_000_000,
                Map.of("tool2147483648", 1L, "raw", 1_000_000_000L), Set.of(), Map.of(), false, false, budget)) {
            while (!work.step()) {}
            check(work.result() == null, "An unverified billion-use restoration escaped the local allowance");
            check(huge.transitions < 10000, "Actually selected uses expanded without a cancellation/work boundary");
        }
        check(budget.searchWork() < 50000 && budget.reservedBytes() == 0, "Long restoration exhausted the parent or retained workspace");
    }

    private static GraphPlan<String> solve(List<CapturedCatalog.Entry<String>> entries, long amount, Map<String, Long> stock) {
        var budget = budget();
        GraphPlan<String> plan;
        try (var work = new CapturedStatePlanning<>(entries, "product", amount, stock, Set.of(), Map.of(), false, false, budget)) {
            while (!work.step()) {}
            plan = work.result();
        }
        check(budget.reservedBytes() == 0, "Auxiliary state work retained memory");
        return plan;
    }

    private static void verify(GraphPlan<String> plan, Map<String, Long> stock) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        check(plan.initialExact().entrySet().stream().allMatch(e -> e.getValue().compareTo(BigInteger.valueOf(stock.getOrDefault(e.getKey(), 0L))) <= 0),
                "A restored state was invented as initial inventory");
    }

    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, String output) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>(output, 1)), false, false));
    }
    private static CapturedRecipe.Input<String> ordinary(String key) {
        return new CapturedRecipe.Input<>(1, List.of(candidate(key)), CapturedRecipe.InputMode.INDIVISIBLE);
    }
    private static CapturedRecipe.Candidate<String> candidate(String key) {
        return new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false);
    }
    private static CapturedRecipe.Input<String> tool(StateFamily<String> family, long capacity, long copies, CapturedRecipe.InputMode mode) {
        String state = "tool" + capacity;
        return new CapturedRecipe.Input<>(copies, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(state, 1),
                family.nominalAfterOne(state), false, false, family)), mode);
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 40_000_000, 512L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }

    private static final class Family implements StateFamily<String> {
        final long cost; final boolean broken; long transitions;
        Family(long cost, boolean broken) { this.cost = cost; this.broken = broken; }
        public String id() { return "tool_cost_" + cost + "_broken_" + broken; }
        public boolean accepts(String state) { return state.startsWith("tool"); }
        public long capacity(String state) { return Long.parseLong(state.substring(4)) / cost; }
        public String nominalAfterOne(String state) {
            transitions++;
            long left = Long.parseLong(state.substring(4)) - cost;
            return left == 0 ? broken ? "broken" : null : "tool" + left;
        }
    }
}
