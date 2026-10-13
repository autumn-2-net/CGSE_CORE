// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

/** Independently replay compressed primitive prefixes, including counts above long. */
public final class CountChainScheduleTest {
    public static void main(String[] args) {
        for (int order = 0; order < 12; order++) {
            large(order, BigInteger.valueOf(1_000_000_007L));
            large(order, BigInteger.TEN.pow(24).add(BigInteger.valueOf(7)));
        }
        stockedSuffix();
        rejectedAndCancelled();
        System.out.println("Chain scheduling: exact prefix/count replay, permutations, large repetitions, stocked phases, memory, cancellation and incomplete discovery passed");
    }

    private static void large(int order, BigInteger n) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        var stock = new LinkedHashMap<String, Long>();
        Map<String, BigInteger> counts = new HashMap<>();
        // A common scarce catalyst must visit complete chains, rather than
        // dispatching every start and consuming all the initial catalyst.
        stock.put("C", 1L);
        for (int route = 0; route < 5; route++) {
            recipes.add(recipe("s" + route, Map.of("C", 1L), Map.of("p" + route, 1L)));
            recipes.add(recipe("m" + route, Map.of("p" + route, 1L), Map.of("q" + route, 1L)));
            recipes.add(recipe("r" + route, Map.of("q" + route, 1L), Map.of("C", 1L, "G", 1L)));
            for (String kind : List.of("s", "m", "r")) counts.put(kind + route, n);
        }
        Collections.shuffle(recipes, new Random(7901 + order));
        var budget = budget(() -> false);
        try (var model = RecipeCountModel.forShell(recipes, Map.of("G", n.multiply(BigInteger.valueOf(5))), stock, Set.of(), budget);
             var schedule = new CountChainSchedule<>(model, model.recipes.stream().map(r -> counts.get(r.id())).toArray(BigInteger[]::new), budget)) {
            long calls = 0;
            while (!schedule.step()) check(++calls < 1000, "one step per primitive execution");
            check(schedule.witness() != null, "complete chain witness missed");
            verify(schedule.witness(), recipes, stock, counts);
            check(budget.searchWork() < 100000, "repeated chain expanded by execution count");
        }
        check(budget.reservedBytes() == 0, "schedule reservation leaked");
    }

    private static void stockedSuffix() {
        var recipes = List.of(recipe("start", Map.of("C", 1L, "U", 1L), Map.of("P", 1L)),
                recipe("return", Map.of("P", 1L, "F", 1L), Map.of("C", 1L, "G", 1L)),
                recipe("burn", Map.of("P", 1L), Map.of("G", 1L)));
        for (long pending : new long[] {1, 0, 2, 1}) {
            var stock = Map.of("P", pending, "U", 100L, "F", 10L);
            var budget = budget(() -> false);
            try (var model = RecipeCountModel.forShell(recipes, Map.of("G", BigInteger.ONE), stock, Set.of(), budget);
                 var entry = CountChainSchedule.entries(model, budget)) {
                check(entry != null, "small entry analysis refused");
                while (!entry.step()) {}
                if (pending == 0) check(entry.witness() == null, "stale prefix survived inventory change");
                else {
                    var after = entry.entryStock();
                    check(after.get("C") == pending && after.get("P") == 0 && after.get("U") == 100,
                            "entry spent an unfunded raw route or reused stale inventory");
                    verify(entry.witness(), recipes, stock, Map.of("return", BigInteger.valueOf(pending)));
                }
            }
            check(budget.reservedBytes() == 0, "entry inventory leaked");
        }
    }

    private static void rejectedAndCancelled() {
        var recipes = List.of(recipe("ab", Map.of("a", 1L), Map.of("b", 1L)),
                recipe("ba", Map.of("b", 1L), Map.of("a", 1L, "G", 1L)));
        var stop = new AtomicLong(Long.MAX_VALUE);
        var holder = new PlanningBudget[1];
        var budget = budget(() -> holder[0].searchWork() >= stop.get()); holder[0] = budget;
        var thousand = BigInteger.valueOf(1000);
        try (var model = RecipeCountModel.forShell(recipes, Map.of("G", BigInteger.ONE), Map.of(), Set.of(), budget)) {
            long before = budget.reservedBytes();
            try (var schedule = new CountChainSchedule<>(model, new BigInteger[] {thousand, thousand}, budget)) {
                while (!schedule.step()) {}
                check(schedule.witness() == null, "unseeded loop got a witness");
            }
            long denied = budget.availableBytes(); budget.reserve(denied);
            try (var schedule = new CountChainSchedule<>(model, new BigInteger[] {thousand, thousand}, budget)) {
                check(schedule.step() && schedule.witness() == null, "optional memory refusal ignored");
            } finally { budget.release(denied); }
            for (long offset : new long[] {0, 5, 20, 40}) {
                stop.set(budget.searchWork() + offset);
                try (var schedule = new CountChainSchedule<>(model, new BigInteger[] {thousand, thousand}, budget)) {
                    while (!schedule.step()) {}
                } catch (CancellationException expected) { /* Cancellation must release owned state. */ }
                stop.set(Long.MAX_VALUE);
                check(budget.reservedBytes() == before, "cancelled discovery leaked");
            }
        }
        check(budget.reservedBytes() == 0, "model leaked");
    }

    static void verify(PlanStep witness, List<GraphRecipe<String>> recipes, Map<String, Long> stock, Map<String, BigInteger> expected) {
        Map<String, GraphRecipe<String>> primitives = new HashMap<>(); recipes.forEach(r -> primitives.put(r.id(), r));
        var proof = GraphFixtureRegression.summary(witness, primitives, new IdentityHashMap<>());
        for (var need : proof.need().entrySet())
            check(need.getValue().compareTo(BigInteger.valueOf(stock.getOrDefault(need.getKey(), 0L))) <= 0, "unfunded primitive prefix: " + need);
        Map<String, BigInteger> counts = new HashMap<>(); count(witness, BigInteger.ONE, counts);
        for (var r : recipes) check(counts.getOrDefault(r.id(), BigInteger.ZERO).equals(expected.getOrDefault(r.id(), BigInteger.ZERO)), "changed multiplicity: " + r.id());
    }

    private static void count(PlanStep step, BigInteger repetitions, Map<String, BigInteger> values) {
        if (step instanceof PlanStep.Batch b) values.merge(b.recipe(), repetitions.multiply(BigInteger.valueOf(b.runs())), BigInteger::add);
        else if (step instanceof PlanStep.Repeat r) count(r.body(), repetitions.multiply(BigInteger.valueOf(r.times())), values);
        else for (var child : ((PlanStep.Sequence) step).children()) count(child, repetitions, values);
    }

    static GraphRecipe<String> recipe(String id, Map<String, Long> input, Map<String, Long> output) {
        return new GraphRecipe<>(id, id, input.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), output);
    }
    static PlanningBudget budget(java.util.function.BooleanSupplier cancelled) { return new PlanningBudget(0, 4_000_000, 64L << 20, cancelled, System::nanoTime); }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
