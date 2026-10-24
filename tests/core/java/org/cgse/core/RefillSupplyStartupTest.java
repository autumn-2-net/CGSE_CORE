// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Adding a funded prefix must not spend the suffix's remaining startup stock. */
public final class RefillSupplyStartupTest {
    private static int checks;

    public static void main(String[] args) {
        for (int order = 0; order < 8; order++) {
            for (boolean tradeoffs : List.of(false, true)) {
                verify(order, 4, true, tradeoffs);
                verify(order, 4, false, tradeoffs);
                verify(order, 5, true, tradeoffs);
            }
        }
        limits();
        System.out.println("RefillSupplyStartupTest PASS: " + checks + " checks");
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream()
                .map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }

    private static List<GraphRecipe<String>> catalog(int order) {
        var result = new ArrayList<>(List.of(
                recipe("grow", Map.of("A", 1L, "ore", 1L), Map.of("A", 2L)),
                recipe("toX", Map.of("A", 1L), Map.of("X", 1L)),
                recipe("toA", Map.of("X", 1L), Map.of("A", 1L)),
                recipe("finish", Map.of("A", 3L, "X", 1L), Map.of("T", 1L))));
        Collections.shuffle(result, new Random(order));
        return result;
    }

    private static GraphPlan<String> original(List<GraphRecipe<String>> catalog, boolean preserve) {
        var indexed = new LinkedHashMap<String, GraphRecipe<String>>();
        catalog.forEach(r -> indexed.put(r.id(), r));
        return new GraphPlan<>("T", 1, preserve, new PlanStep.Batch("finish", 1), indexed,
                Map.of("A", 3L, "X", 1L), Map.of(), Map.of("X", 1L), GraphPlan.Result.MISSING_INPUT, 0, 0);
    }

    private static void verify(int order, long held, boolean preserve, boolean tradeoffs) {
        var catalog = catalog(order); var compiler = new GraphCompiler<>(catalog);
        var stock = Map.of("A", held, "ore", 30L); var original = original(catalog, preserve);
        var budget = new PlanningBudget(0, 100_000, 16L << 20, () -> false, System::nanoTime);
        GraphPlan<String> result;
        try (var ordering = new RefillProducerOrder<>(compiler, original.missingExact().keySet(), Set.of(), Set.of(), budget)) {
            while (!ordering.step()) {}
            try (var search = new RefillSupplySearch<>(original, ordering, stock, Set.of(), true, false, budget)) {
                if (tradeoffs) search.tradeoffs(compiler, Set.of());
                while (!search.step()) {}
                result = search.result();
            }
        }
        check(budget.reservedBytes() == 0, "startup workspace leaked");
        var alternatives = new ArrayList<GraphPlan<String>>(); alternatives.add(result); alternatives.addAll(result.alternatives());
        for (var plan : alternatives) {
            var remaining = replay(plan, stock);
            check(remaining.getOrDefault("T", BigInteger.ZERO).equals(BigInteger.ONE), "delivery changed");
            if (preserve) check(remaining.getOrDefault("A", BigInteger.ZERO)
                    .add(remaining.getOrDefault("X", BigInteger.ZERO)).signum() > 0, "prefix consumed every restart form");
        }
        if (!preserve || held > 4) check(result.feasible(), "safe funded prefix was rejected");
        if (!preserve) check(replay(result, stock).getOrDefault("A", BigInteger.ZERO).signum() == 0,
                "ordinary mode silently preserved stock");
    }

    private static Map<String, BigInteger> replay(GraphPlan<String> plan, Map<String, Long> stock) {
        var held = new HashMap<String, BigInteger>();
        stock.forEach((k,v) -> held.put(k, BigInteger.valueOf(v)));
        plan.missingExact().forEach((k,v) -> held.merge(k,v,BigInteger::add));
        execute(plan.steps(), plan.recipes(), held);
        return held;
    }

    private static void execute(PlanStep step, Map<String, GraphRecipe<String>> recipes, Map<String, BigInteger> held) {
        if (step instanceof PlanStep.Batch batch) {
            var recipe = recipes.get(batch.recipe());
            for (long i = 0; i < batch.runs(); i++) {
                recipe.inputs().forEach((k,v) -> {
                    var left = held.getOrDefault(k,BigInteger.ZERO).subtract(BigInteger.valueOf(v));
                    check(left.signum() >= 0,"prefix borrowed future stock"); held.put(k,left);
                });
                recipe.outputs().forEach((k,v) -> held.merge(k,BigInteger.valueOf(v),BigInteger::add));
            }
        } else if (step instanceof PlanStep.Repeat repeat) {
            for (long i=0; i<repeat.times(); i++) execute(repeat.body(),recipes,held);
        } else for (var child:((PlanStep.Sequence)step).children()) execute(child,recipes,held);
    }

    private static void limits() {
        for (long limit : new long[] {80, 128, 192, 256, 512}) {
            var catalog = catalog(0); var original = original(catalog,true);
            var stock = Map.of("A",4L,"ore",30L);
            var budget = new PlanningBudget(0,limit,16L<<20,()->false,System::nanoTime);
            try (var order = new RefillProducerOrder<>(new GraphCompiler<>(catalog), original.missingExact().keySet(),Set.of(),Set.of(),budget)) {
                while (!order.step()) {}
                try (var search = new RefillSupplySearch<>(original,order,stock,Set.of(),true,false,budget)) {
                    while (!search.step()) {}
                    var held=replay(search.result(),stock);
                    check(held.getOrDefault("A",BigInteger.ZERO).add(held.getOrDefault("X",BigInteger.ZERO)).signum()>0,
                            "limit published unchecked prefix");
                }
            } catch (PlanningBudget.Exhausted expected) { }
            check(budget.reservedBytes()==0,"limited prefix leaked workspace");
        }
    }

    private static void check(boolean condition,String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
}
