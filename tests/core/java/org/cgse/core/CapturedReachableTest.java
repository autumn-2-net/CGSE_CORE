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
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** State-changing inputs are recovered without constructing their Cartesian product. */
public final class CapturedReachableTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        for (int amount : new int[]{1, 2, 100, 768}) durableRequest(amount, false, false);
        durableRequest(100, true, false);
        durableRequest(100, false, true);
        exactInventory();
        ownershipAndLimits();
        System.out.println("CapturedReachableTest: " + assertions + " assertions passed");
    }

    private static void durableRequest(int amount, boolean reverse, boolean last) throws Exception {
        var tools = new ArrayList<CapturedRecipe.Candidate<String>>();
        for (int d = 0; d < 768; d++) tools.add(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("wear" + d, 1),
                d == 767 ? null : "wear" + (d + 1), false));
        if (reverse) Collections.reverse(tools);
        var inputs = new ArrayList<CapturedRecipe.Input<String>>();
        inputs.add(input(tools));
        inputs.add(input(List.of(candidate("ore", null))));
        var stock = new LinkedHashMap<String, Long>();
        stock.put("wear0", 1L); stock.put("ore", (long) amount);
        for (int slot = 0; slot < 8; slot++) {
            inputs.add(input(List.of(candidate("funded" + slot, null), candidate("absent" + slot, null))));
            stock.put("funded" + slot, (long) amount);
        }
        if (last) Collections.rotate(inputs, -1);
        var captured = new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>("product", 1)), false, false);
        var entries = List.of(new CapturedCatalog.Entry<>("make", "binding", captured));
        var prefix = new ArrayList<GraphRecipe<String>>();
        var expand = captured.expand(budget());
        for (int i = 0; expand.hasNext(); i++) {
            var recipe = expand.next();
            prefix.add(new GraphRecipe<>("make#" + i, "binding", recipe.slots(), recipe.outputs()));
        }
        var b = budget(); b.enableMetrics();
        var request = new RequestPlanningWork<>(new GraphCompiler<>(prefix), "product", amount, stock, Set.of(), null,
                false, false, "DEFAULT", CatalystPolicy.MINIMAL, false, true, b, () -> false, report -> {
                    throw new AssertionError("Fallback used for a funded state chain");
                }).capturedCatalog(new CapturedCatalog<>(entries));
        GraphPlan<String> plan;
        try (var scheduler = new PlanningScheduler(1, 2, 1024, 2_000_000)) {
            plan = scheduler.submit(request, b).get(30, TimeUnit.SECONDS);
        }
        check(plan.feasible(), "Durable chain not solved " + amount + "/" + reverse + "/" + last);
        check(plan.initialExact().get("wear0").equals(BigInteger.ONE), "Initial tool was multiplied");
        check(plan.initialExact().entrySet().stream().allMatch(e -> e.getValue().compareTo(BigInteger.valueOf(stock.getOrDefault(e.getKey(), 0L))) <= 0),
                "A future key was invented as initial inventory");
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        var consumed = new LinkedHashMap<String, BigInteger>();
        for (var count : plan.patternTimesExact().entrySet())
            for (String key : plan.recipes().get(count.getKey()).inputs().keySet())
                if (key.startsWith("wear")) consumed.merge(key, count.getValue(), BigInteger::add);
        for (int d = 0; d < amount; d++) check(BigInteger.ONE.equals(consumed.get("wear" + d)), "Wrong wear transition " + d);
        check(consumed.size() == amount, "Tool transitions were repeated");
        check(b.searchWork() < 2_000_000 && b.peakBytes() < (16L << 20), "Sparse support regressed into full Cartesian expansion");
        check(b.reservedBytes() == 0, "Captured request retained workspace");
        check(b.candidates().firstVerifiedSearchWork() > 0, "Reachable witness missing from candidate feedback");
        PlanningCostTraceTest.conserved(b);
    }

    private static void exactInventory() {
        var changing = entry("change", List.of(input(List.of(candidate("fresh", "used"), candidate("used", "fresh"))),
                input(List.of(candidate("raw", null)))), "product");
        var b = budget();
        try (var work = forward(List.of(changing), "product", 100, Map.of("fresh", 1L, "raw", 100L), b)) {
            while (!work.step()) {}
            check(work.result() != null && work.result().feasible(), "Alternating exact return states did not unlock");
            PlanVerifier.verify(work.result());
            check(work.result().initial().get("fresh") == 1, "Cyclic return demanded duplicate tools");
        }
        check(b.reservedBytes() == 0, "Alternating-state ownership leak");

        // Two slots share one stock row: independently valid choices do not
        // permit spending the same physical item twice in a single firing.
        var joint = entry("joint", List.of(input(List.of(candidate("shared", null))),
                input(List.of(candidate("shared", null)))), "product");
        b = budget();
        try (var work = forward(List.of(joint), "product", 1, Map.of("shared", 1L), b)) {
            while (!work.step()) {}
            check(work.result() == null, "Joint slots duplicated inventory");
        }
        check(b.reservedBytes() == 0, "Declined joint allocation leaked");

        b = budget();
        try (var work = forward(List.of(changing), "product", 2, Map.of("raw", 2L), b)) {
            while (!work.step()) {}
            check(work.result() == null, "A merely reachable tool was treated as a physical seed");
        }

        // Exact same-key returns permit one long batch, without iterating once
        // per requested unit or adding one tool per produced unit.
        var stable = entry("stable", List.of(input(List.of(candidate("tool", "tool"))),
                input(List.of(candidate("raw", null)))), "product");
        b = budget();
        try (var work = forward(List.of(stable), "product", Long.MAX_VALUE, Map.of("tool", 1L, "raw", Long.MAX_VALUE), b)) {
            while (!work.step()) {}
            check(work.result() != null, "Safe batch bound lost same-key reusable input");
            check(work.result().patternTimesExact().values().iterator().next().equals(BigInteger.valueOf(Long.MAX_VALUE)), "Long batch overflow");
            check(work.result().initial().get("tool") == 1, "Long batch multiplied a returned tool");
            PlanVerifier.verify(work.result());
        }
        check(b.reservedBytes() == 0, "Long batch retained workspace");

        var dispatch = new CapturedCatalog.Entry<>("dispatch", "dispatch", new CapturedRecipe<>(List.of(input(List.of(
                new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("raw", 1), null, true)))),
                List.of(new CapturedRecipe.Amount<>("product", 1)), true, false));
        b = budget();
        try (var work = forward(List.of(dispatch), "product", 1, Map.of("raw", 1L), b)) {
            while (!work.step()) {}
            check(work.result() == null, "Dispatch input silently linearized");
        }
        check(b.reservedBytes() == 0, "Dispatch refusal retained workspace");
    }

    private static void ownershipAndLimits() {
        var recipe = entry("make", List.of(input(List.of(candidate("raw", null)))), "product");
        for (long bytes : new long[]{1, 1024, 4096, 8192}) {
            var b = new PlanningBudget(0, 100_000, bytes, () -> false, System::nanoTime);
            try (var work = forward(List.of(recipe), "product", 10, Map.of("raw", 10L), b)) {
                while (!work.step()) {}
                if (work.result() != null) PlanVerifier.verify(work.result());
            }
            check(b.reservedBytes() == 0, "Workspace boundary leaked " + bytes);
        }
        var b = budget();
        try (var work = forward(List.of(recipe), "product", 10, Map.of("raw", 10L), b)) {
            work.step(); b.cancel();
            try { work.step(); throw new AssertionError("Cancellation ignored"); }
            catch (CancellationException expected) { check(true, "Cancellation propagated"); }
        }
        check(b.reservedBytes() == 0, "Cancellation retained state");
    }

    private static CapturedReachableWitness<String> forward(List<CapturedCatalog.Entry<String>> entries,
            String target, long amount, Map<String, Long> stock, PlanningBudget budget) {
        var producers = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < entries.size(); i++) {
            var recipe = entries.get(i).recipe();
            for (var output : recipe.outputs()) producers.computeIfAbsent(output.what(), ignored -> new ArrayList<>()).add(i);
            for (var input : recipe.inputs()) for (var candidate : input.candidates())
                if (candidate.remaining() != null) producers.computeIfAbsent(candidate.remaining(), ignored -> new ArrayList<>()).add(i);
        }
        return new CapturedReachableWitness<>(entries, producers, target, amount, stock, Set.of(), Map.of(), false, true, budget);
    }

    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, String target) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>(target, 1)), false, false));
    }

    private static CapturedRecipe.Input<String> input(List<CapturedRecipe.Candidate<String>> values) {
        return new CapturedRecipe.Input<>(1, values, CapturedRecipe.InputMode.INDIVISIBLE);
    }

    private static CapturedRecipe.Candidate<String> candidate(String key, String remaining) {
        return new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), remaining, false);
    }

    private static PlanningBudget budget() { return new PlanningBudget(0, 40_000_000, 512L << 20, () -> false, System::nanoTime); }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
