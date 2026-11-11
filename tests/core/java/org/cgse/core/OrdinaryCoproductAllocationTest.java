// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Joint ordinary counts share coproduct credit without SCCs or inferred seeds. */
public final class OrdinaryCoproductAllocationTest {
    private static void check(boolean value, Object message) { if (!value) throw new AssertionError(message); }
    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static List<GraphRecipe<String>> recipes(boolean reverseOutputs, boolean reverseInputs) {
        var a = new LinkedHashMap<String, Long>(); var b = new LinkedHashMap<String, Long>();
        for (String key : reverseOutputs ? List.of("Y", "X", "A") : List.of("A", "X", "Y")) a.put(key, 1L);
        for (String key : reverseOutputs ? List.of("B", "X", "Y") : List.of("Y", "X", "B")) b.put(key, 1L);
        var inputs = new LinkedHashMap<String, Long>();
        for (String key : reverseInputs ? List.of("Y", "X", "B", "A") : List.of("A", "B", "X", "Y")) inputs.put(key, key.equals("X") || key.equals("Y") ? 2L : 1L);
        return List.of(recipe("a", Map.of("raw", 1L), a), recipe("b", Map.of("raw", 1L), b), recipe("finish", inputs, Map.of("T", 1L)));
    }
    private static void physical(GraphPlan<String> plan) {
        PlanVerifier.verify(new GraphPlan<>(plan.target(), plan.amount(), plan.preserveSeeds(), plan.steps(), plan.recipes(),
                plan.initialExact(), plan.seeds(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0));
        check(SequenceSummary.of(plan.steps(), plan.recipes()).delta("T").compareTo(BigInteger.valueOf(plan.amount())) >= 0, "not a manufacturing word");
    }
    private static void solve(List<GraphRecipe<String>> recipes, long raw, long workLimit, boolean reserve, GraphMode mode) {
        var stock = new LinkedHashMap<String, Long>(); if (raw > 0) stock.put("raw", raw);
        var seeds = reserve ? Map.of("reserve", 1L) : Map.<String, Long>of();
        var budget = new PlanningBudget(0, workLimit, 32L << 20, () -> false, System::nanoTime);
        var work = new GraphPlanningWork<>(new GraphCompiler<>(recipes), "T", 1, stock, Set.of(), seeds, true, true, budget, mode);
        try {
            while (!work.step()) {}
            var plan = work.result(); physical(plan);
            check(plan.patternTimesExact().equals(Map.of("a", BigInteger.ONE, "b", BigInteger.ONE, "finish", BigInteger.ONE)), "coproduct over-expansion: " + plan.patternTimesExact() + "; raw=" + raw + "; budget=" + workLimit + "; mode=" + mode + "; reserve=" + reserve + "; order=" + recipes.stream().map(GraphRecipe::id).toList() + "; " + budget.diagnostics());
            check(plan.initialExact().get("raw").equals(BigInteger.TWO), "raw counted twice");
            check(plan.seeds().equals(seeds) && plan.preserveSeeds(), "implicit seed inferred or explicit reserve removed");
            var missing = new LinkedHashMap<String, BigInteger>(); if (raw < 2) missing.put("raw", BigInteger.valueOf(2 - raw)); if (reserve) missing.put("reserve", BigInteger.ONE);
            check(plan.missingExact().equals(missing), "wrong finite purchases: " + plan.missingExact());
            if (mode == GraphMode.ACYCLIC) check(!budget.diagnostics().contains("seed_optimization"), "restricted domain inferred automatic seeds");
        } finally { work.close(); }
        check(budget.reservedBytes() == 0, "joint ordinary allocation leaked workspace");
    }
    private static void limitedIncumbent(boolean funded, GraphMode mode) throws Exception {
        var budget = new PlanningBudget(0, 1_000_000, 32L << 20, () -> false, System::nanoTime);
        var work = new GraphPlanningWork<>(new GraphCompiler<>(recipes(false, false)), "T", 1,
                funded ? Map.of("raw", 3L) : Map.of(), Set.of(), Map.of(), true, true, budget, mode);
        var pending = GraphPlanningWork.class.getDeclaredField("sharedCountsBase"); pending.setAccessible(true);
        try {
            for (int steps = 0; pending.get(work) == null; steps++) check(steps < 10000 && !work.step(), "ordinary joint allocation boundary not reached");
            try { budget.charge(budget.remainingWork() + 1); } catch (PlanningBudget.Exhausted expected) {}
            while (!work.step()) {}
            var retained = work.result(); physical(retained);
            check(funded ? retained.feasible() : !retained.missingExact().isEmpty(), "optional joint limit erased a verified incumbent");
        } finally { work.close(); }
        check(budget.reservedBytes() == 0, "limited ordinary joint allocation leaked workspace");
    }
    private static void skewedRatios(GraphMode mode) {
        var recipes = List.of(recipe("a", Map.of("raw", 1L), Map.of("A", 1L, "shared", 1L)),
                recipe("b", Map.of("raw", 1L), Map.of("B", 1L, "shared", 1L)),
                recipe("finish", Map.of("A", 1L, "B", 100L, "shared", 101L), Map.of("T", 1L)));
        for (long amount : new long[]{1, 7, Long.MAX_VALUE / 200}) for (boolean reserve : List.of(false, true)) {
            var seeds = reserve ? Map.of("A", 1L) : Map.<String, Long>of();
            var budget = new PlanningBudget(0, 1_000_000, 32L << 20, () -> false, System::nanoTime);
            var work = new GraphPlanningWork<>(new GraphCompiler<>(recipes), "T", amount, Map.of(), Set.of(), seeds,
                    true, true, budget, mode);
            try {
                while (!work.step()) {}
                var plan = work.result(); physical(plan);
                var a = BigInteger.valueOf(amount).add(reserve ? BigInteger.ONE : BigInteger.ZERO);
                var b = BigInteger.valueOf(amount).multiply(BigInteger.valueOf(100));
                var raw = a.add(b);
                check(plan.patternTimesExact().equals(Map.of("a", a, "b", b, "finish", BigInteger.valueOf(amount))),
                        "skewed ordinary ratio was rounded to a fixed block: " + mode + "/" + amount + "/" + reserve + "/" + plan.patternTimesExact());
                check(plan.initialExact().get("raw").equals(raw) && plan.missingExact().equals(Map.of("raw", raw)), "skewed ratio purchases changed");
                check(plan.seeds().equals(seeds), "explicit skewed-ratio reserve changed");
            } finally { work.close(); }
            check(budget.reservedBytes() == 0, "skewed ordinary counts retained workspace");
        }
    }

    public static void main(String[] args) throws Exception {
        for (boolean outputs : List.of(false, true)) for (boolean inputs : List.of(false, true)) {
            var base = recipes(outputs, inputs);
            for (int first = 0; first < 3; first++) for (int second = 0; second < 3; second++) if (second != first) {
                var order = List.of(base.get(first), base.get(second), base.get(3 - first - second));
                for (long raw : new long[]{0, 2, 3}) for (long budget : new long[]{131072, 1_000_000}) for (var mode : GraphMode.values()) solve(order, raw, budget, false, mode);
            }
        }
        for (var mode : GraphMode.values()) {
            solve(recipes(false, true), 0, 131072, true, mode);
            limitedIncumbent(false, mode); limitedIncumbent(true, mode);
            skewedRatios(mode);
        }
        System.out.println("OrdinaryCoproductAllocationTest passed");
    }
}
