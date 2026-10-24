// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** A funded short prefix must get a turn before a large, unrelated source cycle. */
public final class RefillCoordinateStockTest {
    private static int checks;

    public static void main(String[] args) {
        for (int size : new int[] {200, 320}) for (boolean reverse : List.of(false, true)) {
            var recipes = recipes(size);
            if (reverse) Collections.reverse(recipes);
            var preferred = new LinkedHashMap<String, List<GraphRecipe<String>>>();
            // The original catalog order remains intentional: the whole-graph
            // source choice first sees the large cycle, while stock can fund
            // the short alternative without buying any of its intermediate keys.
            for (var r : recipes) for (var key : r.executionOutputs().keySet())
                preferred.computeIfAbsent(key, ignored -> new ArrayList<>()).add(r);
            preferred.get("a0").sort(Comparator.comparingInt(r -> r.id().equals("shortcut") ? 1 : 0));
            var compiler = new GraphCompiler<>(recipes, preferred);
            for (int state = 0; state < 5; state++) {
                boolean excluded = state == 1, available = state != 3;
                var budget = new PlanningBudget(0, 8_000_000, 64L << 20, () -> false, System::nanoTime);
                var finish = recipes.stream().filter(r -> r.id().equals("finish")).findFirst().orElseThrow();
                var initial = Map.of("a0", BigInteger.ONE, "q", BigInteger.ONE);
                var original = new GraphPlan<>("g", 1, false, new PlanStep.Batch("finish", 1), Map.of("finish", finish),
                        initial, Map.of(), initial, GraphPlan.Result.MISSING_INPUT, 0, 0);
                var stock = available ? Map.of("ready", 1L) : Map.<String, Long>of();
                var excludedIds = excluded ? Set.of("shortcut") : Set.<String>of();
                GraphPlan<String> result;
                try (var order = new RefillProducerOrder<>(compiler, initial.keySet(), Set.of(), excludedIds, budget)) {
                    while (!order.step()) {}
                    try (var search = new RefillPlanningSearch<>(compiler, original, order, stock, Set.of(), excludedIds,
                            true, false, budget)) {
                        while (!search.step()) {}
                        result = search.result();
                    }
                }
                check(budget.reservedBytes() == 0, "Prefix/ranking/support workspace retained");
                if (!excluded && available) check(result.missingExact().equals(Map.of("q", BigInteger.ONE)),
                        "Funded prefix lost behind unrelated cycle: " + result.missingExact() + "; " + budget.diagnostics());
                else check(!result.missingExact().isEmpty(), "Unseeded closed cycles invented input");
                if (!available) check(result.missingExact().keySet().stream().anyMatch(k -> k.startsWith("a") || k.equals("ready")),
                        "Previously funded prefix reused after its stock disappeared");
                RefillWordPruningTest.literalVerify(result, stock, Set.of());
                check(!excluded || !result.patternTimesExact().containsKey("shortcut"), "Excluded source was executed");
            }
        }
        lifecycle();
        preservedStock();
        preservedCyclicStock();
        exactCoordinates();
        System.out.println("RefillCoordinateStockTest PASS: checks=" + checks);
    }

    private static void lifecycle() {
        var recipes = recipes(200); var compiler = new GraphCompiler<>(recipes);
        var stock = Map.of("ready", 1L);
        var original = suffix(recipes, BigInteger.ONE, 1, Map.of());
        int cancellations = 0, limited = 0;
        for (String mode : List.of("cancel", "memory", "work")) for (int steps : new int[] {0, 8, 32, 128}) {
            var cancelled = new AtomicBoolean();
            var budget = new PlanningBudget(0, 8_000_000, 32L << 20, cancelled::get, System::nanoTime);
            long held = 0;
            try (var order = new RefillProducerOrder<>(compiler, original.missingExact().keySet(), Set.of(), Set.of(), budget)) {
                while (!order.step()) {}
                try (var search = new RefillPlanningSearch<>(compiler, original, order, stock, Set.of(), Set.of(), true, false, budget)) {
                    boolean done = false;
                    for (int i = 0; i < steps && !done; i++) done = search.step();
                    if (!done) {
                        switch (mode) {
                            case "cancel" -> cancelled.set(true);
                            case "memory" -> { held = budget.availableBytes(); budget.reserve(held); }
                            case "work" -> budget.charge(budget.remainingWork());
                            default -> throw new AssertionError(mode);
                        }
                        try {
                            while (!search.step()) {}
                            check(!mode.equals("cancel"), "Cancellation was ignored by an active prefix");
                            limited++;
                        } catch (CancellationException expected) {
                            check(mode.equals("cancel"), "Unexpected cancellation"); cancellations++;
                        }
                    }
                    // Even interruption during restoration must leave a fully
                    // verified incumbent; a partial prefix is never published.
                    verifyExact(search.result(), stock, recipes);
                }
            } finally { budget.release(held); }
            check(budget.reservedBytes() == 0, "Interrupted prefix/ranking reservation leaked: " + mode);
        }
        check(cancellations > 0 && limited > 0, "Lifecycle controls never interrupted active work");

        for (long remaining : new long[] {1, 64, 1024}) {
            var budget = new PlanningBudget(0, 8_000_000, 32L << 20, () -> false, System::nanoTime);
            try (var order = new RefillProducerOrder<>(compiler, original.missingExact().keySet(), Set.of(), Set.of(), budget)) {
                while (!order.step()) {}
                budget.charge(budget.remainingWork() - remaining);
                try (var search = new RefillPlanningSearch<>(compiler, original, order, stock, Set.of(), Set.of(), true, false, budget)) {
                    while (!search.step()) {}
                    verifyExact(search.result(), stock, recipes);
                    check(search.result().missingExact().equals(original.missingExact()), "Short quota lost the verified incumbent");
                }
            }
            check(budget.reservedBytes() == 0, "Short-budget entry retained workspace");
        }
    }

    private static void preservedStock() {
        var recipes = recipes(200);
        // This control isolates the explicit ready reserve. Its independently
        // purchased q is terminal, so consuming it has no restart obligation.
        recipes.removeIf(r -> r.id().equals("q-loop"));
        var compiler = new GraphCompiler<>(recipes);
        var original = suffix(recipes, BigInteger.ONE, 1, Map.of("ready", 1L));
        for (long available : new long[] {2, 1, 2}) {
            var stock = Map.of("ready", available);
            var result = improve(compiler, original, stock);
            verifyExact(result, stock, recipes);
            check(result.seeds().getOrDefault("ready", 0L) >= 1, "Prefix discarded the suffix's final seed obligation");
            if (available == 2) check(result.missingExact().equals(Map.of("q", BigInteger.ONE)), "Spare stock could not fund prefix");
            else check(result.missingExact().keySet().stream().anyMatch(k -> !k.equals("q")), "Prefix spent the only preserved seed");
        }
    }

    private static void preservedCyclicStock() {
        var recipes = recipes(200); var compiler = new GraphCompiler<>(recipes);
        var finish = recipes.stream().filter(r -> r.id().equals("finish")).findFirst().orElseThrow();
        // A valid preserving incumbent buys one consumed q plus the two q
        // needed to restart q-loop. A one-q bill would already destroy this
        // second cycle before the coordinate prefix had any chance to run.
        var original = new GraphPlan<>("g", 1, true, new PlanStep.Batch("finish", 1), Map.of("finish", finish),
                Map.of("a0", BigInteger.ONE, "q", BigInteger.valueOf(3), "ready", BigInteger.ONE),
                Map.of("ready", 1L, "q", 2L), Map.of("a0", BigInteger.ONE, "q", BigInteger.valueOf(3)),
                GraphPlan.Result.MISSING_INPUT, 0, 0);
        for (long available : new long[] {2, 1, 2}) {
            var stock = Map.of("ready", available);
            verifyExact(original, stock, recipes);
            var result = improve(compiler, original, stock);
            verifyExact(result, stock, recipes);
            check(result.seeds().getOrDefault("ready", 0L) >= 1, "Cyclic boundary discarded ready reserve");
            check(result.seeds().getOrDefault("q", 0L) >= 2, "Cyclic purchase lost its startup threshold");
            check(result.missingExact().getOrDefault("q", BigInteger.ZERO).compareTo(BigInteger.TWO) >= 0,
                    "Unseeded q cycle invented its starting material");
            var word = fold(result.steps(), result.recipes());
            check(result.missingExact().getOrDefault("q", BigInteger.ZERO)
                    .add(word.delta.getOrDefault("q", BigInteger.ZERO)).compareTo(BigInteger.TWO) >= 0,
                    "Finite purchase was exhausted after publication");
        }
    }

    private static void exactCoordinates() {
        for (long runs : new long[] {1, 2}) {
            var recipes = recipes(200);
            recipes.removeIf(r -> r.id().equals("finish"));
            recipes.add(recipe("finish", Map.of("a0", Long.MAX_VALUE, "q", 1L), Map.of("g", 1L)));
            var required = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(runs));
            var original = suffix(recipes, required, runs, Map.of());
            var stock = PlanningInventory.exactSnapshot(Map.of("ready", required));
            var result = improve(new GraphCompiler<>(recipes), original, stock);
            verifyExact(result, stock, recipes);
            check(result.patternTimesExact().get("finish").equals(BigInteger.valueOf(runs)), "Exact coordinate changed the request");
            if (runs == 1) check(!result.missingExact().containsKey("a0"), "Long-sized funded coordinate was skipped");
            else if (!result.patternTimesExact().containsKey("shortcut"))
                check(result.missingExact().get("a0").equals(required), "Beyond-long coordinate was silently clamped");
        }
    }

    private static GraphPlan<String> suffix(List<GraphRecipe<String>> recipes, BigInteger required, long runs,
                                             Map<String, Long> seeds) {
        var finish = recipes.stream().filter(r -> r.id().equals("finish")).findFirst().orElseThrow();
        var initial = new LinkedHashMap<String, BigInteger>();
        initial.put("a0", required); initial.put("q", BigInteger.valueOf(runs));
        seeds.forEach((k, v) -> initial.put(k, BigInteger.valueOf(v)));
        return new GraphPlan<>("g", runs, !seeds.isEmpty(), new PlanStep.Batch("finish", runs), Map.of("finish", finish),
                initial, seeds, Map.of("a0", required, "q", BigInteger.valueOf(runs)), GraphPlan.Result.MISSING_INPUT, 0, 0);
    }

    private static GraphPlan<String> improve(GraphCompiler<String> compiler, GraphPlan<String> original, Map<String, Long> stock) {
        var budget = new PlanningBudget(0, 8_000_000, 64L << 20, () -> false, System::nanoTime);
        GraphPlan<String> result;
        try (var order = new RefillProducerOrder<>(compiler, original.missingExact().keySet(), Set.of(), Set.of(), budget)) {
            while (!order.step()) {}
            try (var search = new RefillPlanningSearch<>(compiler, original, order, stock, Set.of(), Set.of(), true, false, budget)) {
                while (!search.step()) {}
                result = search.result();
            }
        }
        check(budget.reservedBytes() == 0, "Completed exact-coordinate search retained reservations");
        return result;
    }

    private record Word(Map<String, BigInteger> need, Map<String, BigInteger> delta) {}

    /** Independent exact prefix composition also handles beyond-long batches. */
    private static Word fold(PlanStep step, Map<String, GraphRecipe<String>> recipes) {
        if (step instanceof PlanStep.Batch batch) {
            var recipe = recipes.get(batch.recipe()); check(recipe != null, "Unknown recipe binding");
            var need = new HashMap<String, BigInteger>(); var delta = new HashMap<String, BigInteger>();
            recipe.inputs().forEach((k, v) -> { need.put(k, BigInteger.valueOf(v)); delta.put(k, BigInteger.valueOf(v).negate()); });
            recipe.outputs().forEach((k, v) -> delta.merge(k, BigInteger.valueOf(v), BigInteger::add));
            return repeat(new Word(need, delta), batch.runs());
        }
        if (step instanceof PlanStep.Repeat repeated) return repeat(fold(repeated.body(), recipes), repeated.times());
        var need = new HashMap<String, BigInteger>(); var delta = new HashMap<String, BigInteger>();
        for (var child : ((PlanStep.Sequence) step).children()) {
            var next = fold(child, recipes);
            next.need.forEach((k, v) -> need.merge(k, v.subtract(delta.getOrDefault(k, BigInteger.ZERO)).max(BigInteger.ZERO), BigInteger::max));
            next.delta.forEach((k, v) -> delta.merge(k, v, BigInteger::add));
        }
        return new Word(need, delta);
    }

    private static Word repeat(Word body, long runs) {
        if (runs == 0) return new Word(Map.of(), Map.of());
        var need = new HashMap<>(body.need); var delta = new HashMap<String, BigInteger>();
        body.delta.forEach((k, v) -> {
            need.merge(k, v.negate().max(BigInteger.ZERO).multiply(BigInteger.valueOf(runs - 1)), BigInteger::add);
            delta.put(k, v.multiply(BigInteger.valueOf(runs)));
        });
        return new Word(need, delta);
    }

    private static void verifyExact(GraphPlan<String> plan, Map<String, Long> stock, List<GraphRecipe<String>> catalog) {
        var bindings = new HashMap<String, GraphRecipe<String>>(); catalog.forEach(r -> bindings.put(r.id(), r));
        for (var entry : plan.recipes().entrySet()) {
            var original = bindings.get(entry.getKey()); var actual = entry.getValue();
            check(original != null && original.binding().equals(actual.binding()) && original.slots().equals(actual.slots()) &&
                    original.outputs().equals(actual.outputs()), "Published a projected or changed recipe binding");
        }
        var word = fold(plan.steps(), bindings);
        var keys = new HashSet<>(word.need.keySet()); keys.addAll(plan.initialExact().keySet()); keys.addAll(plan.missingExact().keySet());
        for (var key : keys) {
            var initial = plan.initialExact().getOrDefault(key, BigInteger.ZERO);
            check(initial.compareTo(word.need.getOrDefault(key, BigInteger.ZERO)) >= 0, "Unfunded physical prefix: " + key);
            var gap = initial.subtract(PlanningInventory.exactAmount(stock, key)).max(BigInteger.ZERO);
            check(gap.equals(plan.missingExact().getOrDefault(key, BigInteger.ZERO)), "Incorrect finite-stock deficit: " + key);
        }
        check(word.delta.getOrDefault(plan.target(), BigInteger.ZERO).compareTo(BigInteger.valueOf(plan.amount())) >= 0,
                "Forced target was not manufactured");
        for (var seed : plan.seeds().entrySet()) check(plan.initialExact().getOrDefault(seed.getKey(), BigInteger.ZERO)
                .add(word.delta.getOrDefault(seed.getKey(), BigInteger.ZERO)).compareTo(BigInteger.valueOf(seed.getValue())) >= 0,
                "Final seed was consumed: " + seed.getKey());
        for (var alternative : plan.alternatives()) verifyExact(alternative, stock, catalog);
    }

    private static ArrayList<GraphRecipe<String>> recipes(int size) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < size; i++) recipes.add(recipe("ring" + i, Map.of("a" + i, 2L),
                Map.of("a" + ((i + 1) % size), 1L)));
        recipes.add(recipe("shortcut", Map.of("ready", 1L), Map.of("a0", 1L)));
        recipes.add(recipe("ready-return", Map.of("a0", 1L), Map.of("ready", 1L)));
        recipes.add(recipe("q-loop", Map.of("q", 2L), Map.of("q", 3L)));
        recipes.add(recipe("finish", Map.of("a0", 1L, "q", 1L), Map.of("g", 1L)));
        return recipes;
    }
    private static GraphRecipe<String> recipe(String id, Map<String, Long> input, Map<String, Long> output) {
        return new GraphRecipe<>(id, id, new TreeMap<>(input).entrySet().stream()
                .map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), new TreeMap<>(output));
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
