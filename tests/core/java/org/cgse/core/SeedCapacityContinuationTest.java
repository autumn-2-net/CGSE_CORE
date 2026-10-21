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

/** An oversized startup proposal must leave other executable orders available. */
public final class SeedCapacityContinuationTest {
    private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);

    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("allocation")) allocationOrders();
        if (args.length == 0 || args[0].equals("region")) regionalOrders();
        if (args.length == 0 || args[0].equals("request")) requests();
        if (args.length == 0 || args[0].equals("target")) targetReservation();
        System.out.println("SeedCapacityContinuationTest PASS: alternate orders, parallel startup, exact funding and target reserves");
    }

    private static void allocationOrders() {
        var recipes = index(cycle(2, true));
        var packed = new PlanStep.Sequence(List.of(new PlanStep.Batch("a", Long.MAX_VALUE),
                new PlanStep.Batch("b", Long.MAX_VALUE)));
        var compact = PlanStep.repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("a", 1),
                new PlanStep.Batch("b", 1))), MAX);
        for (boolean preview : new boolean[]{false, true}) for (boolean force : new boolean[]{false, true}) {
            var stock = preview ? Map.<String, Long>of() : PlanningInventory.exactSnapshot(
                    Map.of("S", MAX.multiply(BigInteger.TWO), "raw", MAX.multiply(BigInteger.valueOf(3))));
            var budget = budget();
            // The very same counts have an unrepresentable returned loan when
            // grouped, and a two-item startup when interleaved. No global
            // arithmetic failure or negative feasibility proof is warranted.
            try (var candidate = new AllocationSearch.Candidate<>(packed, recipes, "G", Long.MAX_VALUE,
                    stock, Map.of("S", 5L), Set.of(), true, force, preview, budget, System.nanoTime())) {
                while (!candidate.step()) {}
                check(candidate.plan == null, "oversized seed was silently weakened");
            }
            try (var candidate = new AllocationSearch.Candidate<>(compact, recipes, "G", Long.MAX_VALUE,
                    stock, Map.of("S", 5L), Set.of(), true, force, preview, budget, System.nanoTime())) {
                while (!candidate.step()) {}
                var plan = candidate.plan;
                check(plan != null, "alternative interleaving lost after oversized proposal");
                check(plan.seeds().equals(Map.of("S", 5L)), "explicit reserve was changed");
                check(plan.initialExact().equals(Map.of("S", BigInteger.valueOf(5),
                        "raw", MAX.multiply(BigInteger.valueOf(3)))), "incorrect exact startup");
                check(plan.missingExact().equals(preview ? plan.initialExact() : Map.of()), "incorrect exact deficit");
                funded(plan, stock);
            }
            // Wide material reservations remain valid when preservation is
            // disabled. Do not turn the seed guard into a long material cap.
            try (var candidate = new AllocationSearch.Candidate<>(packed, recipes, "G", Long.MAX_VALUE,
                    stock, Map.of(), Set.of(), false, force, preview, budget, System.nanoTime())) {
                while (!candidate.step()) {}
                var plan = candidate.plan;
                check(plan != null && plan.initialExact().get("S").equals(MAX.multiply(BigInteger.TWO)),
                        "wide consumable ledger was capped");
                funded(plan, stock);
            }
            check(budget.reservedBytes() == 0, "candidate workspace retained");
        }
    }

    private static void regionalOrders() {
        var single = recipe("tool", Map.of("S", Long.MAX_VALUE), Map.of("S", Long.MAX_VALUE, "G", 1L));
        var families = List.of(List.of(single), cycle(Long.MAX_VALUE / 2 + 1, false), cycle(Long.MAX_VALUE, false));
        for (var family : families) for (boolean empty : new boolean[]{false, true}) for (boolean reverse : new boolean[]{false, true}) {
            var recipes = new ArrayList<>(family);
            if (reverse) Collections.reverse(recipes);
            var stock = empty ? Map.<String, Long>of() : Map.of("S", Long.MAX_VALUE);
            var budget = budget();
            try (var selection = new RegionSelection<>(new GraphCompiler.Region<>(recipes, true),
                    Map.of("G", BigInteger.TWO), stock, "G", 2, true, true, Set.of(), budget,
                    new CatalystPolicy(64, 64), stock)) {
                while (!selection.step()) {}
                var choice = selection.result();
                check(choice != null, "oversized optional parallel copy discarded every order");
                var steps = PlanStep.repeat(choice.body(), choice.runs());
                var initial = PlanVerifier.initialExact(steps, index(recipes), "G", 2, choice.seeds());
                var plan = new GraphPlan<>("G", 2, true, steps, index(recipes), initial, choice.seeds(), Map.of(),
                        GraphPlan.Result.FEASIBLE, budget.nodes(), 0);
                PlanVerifier.verify(plan);
                check(SequenceSummary.of(steps, index(recipes)).delta("G").compareTo(BigInteger.TWO) >= 0,
                        "parallel retry lost requested production");
                if (!empty) initial.forEach((k,v) -> check(v.compareTo(BigInteger.valueOf(stock.getOrDefault(k, 0L))) <= 0,
                        "parallel retry lost funded serial order: " + k));
            }
            check(budget.reservedBytes() == 0, "regional retry retained summary workspace");
        }
    }

    private static void requests() {
        for (boolean reverse : new boolean[]{false, true}) for (boolean missing : new boolean[]{false, true}) {
            var recipes = new ArrayList<>(cycle(2, true));
            if (reverse) Collections.reverse(recipes);
            var stock = PlanningInventory.exactSnapshot(Map.of("S", BigInteger.valueOf(5), "raw",
                    MAX.multiply(BigInteger.valueOf(3)).subtract(missing ? BigInteger.ONE : BigInteger.ZERO)));
            var compiler = new GraphCompiler<>(recipes);
            for (boolean fallback : new boolean[]{false, true}) {
                var budget = budget();
                GraphPlan<String> plan;
                if (fallback) plan = GraphFallback.plan(compiler, "G", Long.MAX_VALUE, stock, Set.of(), Map.of("S", 5L),
                        true, true, budget);
                else {
                    var work = new GraphPlanningWork<>(compiler, "G", Long.MAX_VALUE, stock, Set.of(),
                            Map.of("S", 5L), true, true, budget).catalysts(new CatalystPolicy(64, 64));
                    try {
                        while (!work.step()) {}
                        plan = work.result();
                    } finally { work.close(); }
                }
                check(plan.feasible() != missing, "request lost expected feasibility: " + plan.result());
                check(plan.missingExact().equals(missing ? Map.of("raw", BigInteger.ONE) : Map.of()),
                        "wide refill changed the one-item raw deficit: " + plan.missingExact());
                check(plan.seeds().getOrDefault("S", 0L) >= 5, "request lost mandatory reserve");
                funded(plan, stock);
                check(budget.reservedBytes() == 0, "request retained workspace");
            }
        }
    }

    private static void targetReservation() {
        var recipes = index(List.of(recipe("grow", Map.of("G", 1L, "raw", 1L), Map.of("G", 2L))));
        var budget = budget();
        var stock = Map.of("G", Long.MAX_VALUE, "raw", Long.MAX_VALUE);
        try (var candidate = new AllocationSearch.Candidate<>(new PlanStep.Batch("grow", Long.MAX_VALUE),
                recipes, "G", Long.MAX_VALUE, stock, Map.of("G", Long.MAX_VALUE), Set.of(), true, true, false,
                budget, System.nanoTime())) {
            while (!candidate.step()) {}
            var plan = candidate.plan;
            check(plan != null && plan.seeds().get("G") == Long.MAX_VALUE, "target reserve was truncated");
            check(plan.initialExact().get("G").equals(MAX), "delivery plus target seed overflowed");
            funded(plan, stock);
        }
        check(budget.reservedBytes() == 0, "target reserve retained workspace");
    }

    private static List<GraphRecipe<String>> cycle(long size, boolean raw) {
        return List.of(recipe("a", Map.of("S", size), Map.of("T", 1L)),
                recipe("b", raw ? Map.of("T", 1L, "raw", 3L) : Map.of("T", 1L), Map.of("S", size, "G", 1L)));
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        return new GraphRecipe<>(id, id, inputs.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), outputs);
    }

    private static Map<String, GraphRecipe<String>> index(List<GraphRecipe<String>> recipes) {
        var result = new LinkedHashMap<String, GraphRecipe<String>>();
        recipes.forEach(r -> result.put(r.id(), r));
        return result;
    }

    private static void funded(GraphPlan<String> plan, Map<String, Long> stock) {
        PlanVerifier.verify(new GraphPlan<>(plan.target(), plan.amount(), plan.preserveSeeds(), plan.steps(), plan.recipes(),
                plan.initialExact(), plan.seeds(), Map.of(), GraphPlan.Result.FEASIBLE, plan.searchNodes(), plan.planningNanos()));
        plan.initialExact().forEach((k,v) -> check(v.compareTo(PlanningInventory.exactAmount(stock, k)
                .add(plan.missingExact().getOrDefault(k, BigInteger.ZERO))) <= 0, "unfunded reservation: " + k));
        check(SequenceSummary.of(plan.steps(), plan.recipes()).delta(plan.target()).compareTo(BigInteger.valueOf(plan.amount())) >= 0,
                "borrowed target substituted for production");
    }

    private static PlanningBudget budget() { return new PlanningBudget(0, 8_000_000, 128L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
