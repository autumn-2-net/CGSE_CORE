// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

/** Independent primitive reachability and compressed-prefix checks for reverse candidates. */
public final class CountReverseScheduleTest {
    private static int checks, witnessed;

    public static void main(String[] args) {
        forwardDrain();
        for (int order = 0; order < 8; order++) for (BigInteger n : List.of(BigInteger.valueOf(7),
                BigInteger.valueOf(1_000_000_000L), BigInteger.TEN.pow(30).add(BigInteger.ONE)))
            large(order, n);
        sourceAndExternal(); configurations(); randomOracle(); limits();
        System.out.println("Reverse count schedules: random=1200; witnessed=" + witnessed + "; checks=" + checks);
    }

    private static List<GraphRecipe<String>> fork() {
        return new ArrayList<>(List.of(recipe("left", Map.of("a", 1L), Map.of("b", 1L)),
                recipe("right", Map.of("a", 1L), Map.of("c", 1L)),
                recipe("return", Map.of("b", 1L, "c", 1L), Map.of("a", 2L, "product", 1L))));
    }

    private static void forwardDrain() {
        var recipes = fork(); var counts = fill(3, BigInteger.valueOf(1000)); var budget = budget();
        try (var model = RecipeCountModel.forShell(recipes, Map.of("product", BigInteger.valueOf(1000)), Map.of("a", 2L), Set.of(), budget);
             var topology = new CountScheduleTopology<>(model, counts, budget)) {
            while (!topology.step()) {}
            try (var greedy = CountSchedule.componentOrdered(model, counts, topology.result(), budget)) {
                while (!greedy.step()) {}
                check(greedy.witness() == null, "Fixture no longer drains the forward shared seed");
            }
            try (var reverse = new CountReverseSchedule<>(model, counts, budget)) {
                while (!reverse.step()) {}
                check(reverse.witness() != null, "Reverse did not recover the same fixed counts");
                verify(reverse.witness(), recipes, Map.of("a", BigInteger.TWO), Set.of(), counts);
            }
        }
        check(budget.reservedBytes() == 0, "Forward-drain probe leaked");
    }

    private static void large(int order, BigInteger n) {
        var recipes = fork();
        recipes.set(0, new GraphRecipe<>("left", "left", List.of(new GraphRecipe.Slot<>("a", 1),
                new GraphRecipe.Slot<>("tool", 2, 0, true, true), new GraphRecipe.Slot<>("feed", 1)),
                Map.of("b", 1L, "tool", 2L, "waste", 3L)));
        Collections.shuffle(recipes, new Random(7801 + order));
        var counts = fill(recipes.size(), n);
        var exact = Map.of("a", BigInteger.TWO, "tool", BigInteger.TWO);
        var budget = budget();
        try (var model = RecipeCountModel.forShell(recipes, Map.of("product", n), PlanningInventory.exactSnapshot(exact), Set.of("feed"), budget);
             var reverse = new CountReverseSchedule<>(model, counts, budget)) {
            while (!reverse.step()) {}
            check(reverse.witness() != null, "Large reordered return path failed: " + order + "/" + n);
            verify(reverse.witness(), recipes, exact, Set.of("feed"), counts);
            check(budget.searchWork() < 50_000, "Cost depends on expanded firing count");
        }
        check(budget.reservedBytes() == 0, "Large schedule leaked");
    }

    private static void sourceAndExternal() {
        var recipes = List.of(recipe("source", Map.of("feed", 1L), Map.of("a", 1L)),
                recipe("sink", Map.of("a", 1L), Map.of("waste", 1L)),
                recipe("supplied", Map.of("feed", 1L), Map.of("waste", 1L)));
        var counts = fill(3, BigInteger.valueOf(9)); var external = Set.of("feed", "waste"); var budget = budget();
        try (var model = RecipeCountModel.forShell(recipes, Map.of(), Map.of(), external, budget);
             var reverse = new CountReverseSchedule<>(model, counts, budget)) {
            while (!reverse.step()) {}
            check(reverse.witness() != null, "Projected source/sink was lost");
            verify(reverse.witness(), recipes, Map.of(), external, counts);
        }
        check(budget.reservedBytes() == 0, "External projection leaked");
        var impossible = List.of(recipe("need", Map.of("missing", 2L), Map.of("product", 1L)));
        budget = budget();
        try (var model = RecipeCountModel.forShell(impossible, Map.of(), Map.of("missing", 1L), Set.of(), budget);
             var reverse = new CountReverseSchedule<>(model, new BigInteger[]{BigInteger.ONE}, budget)) {
            while (!reverse.step()) {}
            check(reverse.witness() == null, "Negative final inventory became a witness");
        }
        check(budget.reservedBytes() == 0, "Negative final inventory leaked");
    }

    private static void configurations() {
        var recipe = new GraphRecipe<>("configured", "configured", List.of(new GraphRecipe.Slot<>("config", 1, 0, true, false)), Map.of("product", 1L));
        for (boolean supplied : new boolean[]{false, true}) {
            var external = supplied ? Set.of("config") : Set.<String>of(); var budget = budget();
            try (var model = RecipeCountModel.forShell(List.of(recipe), Map.of(), Map.of("config", 100L), external, budget);
                 var reverse = new CountReverseSchedule<>(model, new BigInteger[]{BigInteger.TEN}, budget)) {
                while (!reverse.step()) {}
                check((reverse.witness() != null) == supplied, "Consumed configuration was treated as a linear finite resource");
            }
            check(budget.reservedBytes() == 0, "Configuration refusal leaked");
        }
    }

    private static void randomOracle() {
        var random = new Random(91218);
        for (int sample = 0; sample < 1200; sample++) {
            var recipes = new ArrayList<GraphRecipe<String>>(); var initial = new LinkedHashMap<String, BigInteger>();
            for (int key = 0; key < 3; key++) initial.put("k" + key, BigInteger.valueOf(random.nextInt(5)));
            for (int r = 0; r < 4; r++) {
                var inputs = new LinkedHashMap<String, Long>(); var outputs = new LinkedHashMap<String, Long>();
                for (int key = 0; key < 3; key++) {
                    long in = random.nextInt(3), out = random.nextInt(3);
                    if (in > 0) inputs.put("k" + key, in);
                    if (out > 0) outputs.put("k" + key, out);
                }
                if (outputs.isEmpty()) outputs.put("unused", 1L);
                recipes.add(recipe("r" + r, inputs, outputs));
            }
            BigInteger[] counts = new BigInteger[4]; int[] remaining = new int[4];
            for (int i = 0; i < 4; i++) { remaining[i] = random.nextInt(3); counts[i] = BigInteger.valueOf(remaining[i]); }
            var external = sample % 3 == 0 ? Set.of("k0") : Set.<String>of();
            boolean possible = reachable(recipes, initial, external, remaining, new HashSet<>());
            var budget = budget();
            try (var model = RecipeCountModel.forShell(recipes, Map.of(), PlanningInventory.exactSnapshot(initial), external, budget);
                 var reverse = new CountReverseSchedule<>(model, counts, budget)) {
                while (!reverse.step()) {}
                if (reverse.witness() != null) {
                    check(possible, "Reverse returned an independently unreachable multiset");
                    verify(reverse.witness(), recipes, initial, external, counts); witnessed++;
                }
            }
            check(budget.reservedBytes() == 0, "Random schedule leaked");
        }
        check(witnessed > 200, "Too few positive random executions");
    }

    private static boolean reachable(List<GraphRecipe<String>> recipes, Map<String, BigInteger> stock,
                                     Set<String> external, int[] remaining, Set<List<Integer>> seen) {
        if (Arrays.stream(remaining).allMatch(n -> n == 0)) return true;
        if (!seen.add(Arrays.stream(remaining).boxed().toList())) return false;
        for (int i = 0; i < remaining.length; i++) if (remaining[i] > 0) {
            var after = new LinkedHashMap<>(stock);
            if (!fire(recipes.get(i), after, external)) continue;
            remaining[i]--; boolean success = reachable(recipes, after, external, remaining, seen); remaining[i]++;
            if (success) return true;
        }
        return false;
    }

    private static void limits() {
        var recipes = fork(); var counts = fill(3, BigInteger.valueOf(1000)); var budget = budget();
        try (var model = RecipeCountModel.forShell(recipes, Map.of(), Map.of("a", 2L), Set.of(), budget)) {
            long denied = budget.availableBytes(); budget.reserve(denied);
            try (var reverse = new CountReverseSchedule<>(model, counts, budget)) {
                check(reverse.step() && reverse.witness() == null, "Constructor ignored memory refusal");
            } finally { budget.release(denied); }
            try (var reverse = new CountReverseSchedule<>(model, counts, budget)) {
                denied = budget.availableBytes(); budget.reserve(denied);
                try { while (!reverse.step()) {} check(reverse.witness() == null, "Partial reverse escaped memory refusal"); }
                finally { budget.release(denied); }
            }
            try (var reverse = new CountReverseSchedule<>(model, counts, budget, 1)) {
                check(reverse.step() && reverse.witness() == null, "Ignored local work refusal");
            }
        }
        check(budget.reservedBytes() == 0, "Memory refusal leaked");
        var chain = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < 180; i++) chain.add(recipe("chain" + i, Map.of("p" + i, 1L), Map.of("p" + (i + 1), 1L)));
        budget = budget();
        try (var model = RecipeCountModel.forShell(chain, Map.of(), Map.of("p0", 1L), Set.of(), budget)) {
            long before = budget.searchWork();
            try (var reverse = new CountReverseSchedule<>(model, fill(chain.size(), BigInteger.ONE), budget, 4096)) {
                while (!reverse.step()) {}
                check(reverse.witness() == null, "Interrupted structural candidate escaped as a word");
                check(budget.searchWork() - before < 5000, "Local candidate ignored its work limit");
            }
        }
        check(budget.reservedBytes() == 0, "Local structural cutoff leaked");
        for (long offset : new long[]{0, 1, 8, 40, 100, 250, 500}) {
            var stop = new AtomicLong(Long.MAX_VALUE); var holder = new PlanningBudget[1];
            budget = new PlanningBudget(0, 2_000_000, 64L << 20, () -> holder[0].searchWork() >= stop.get(), System::nanoTime); holder[0] = budget;
            try (var model = RecipeCountModel.forShell(recipes, Map.of(), Map.of("a", 2L), Set.of(), budget);
                 var reverse = new CountReverseSchedule<>(model, counts, budget)) {
                stop.set(budget.searchWork() + offset);
                try { while (!reverse.step()) {} }
                catch (CancellationException ignored) { check(reverse.witness() == null, "Cancelled prefix escaped as witness"); }
            }
            check(budget.reservedBytes() == 0, "Cancellation leaked at " + offset);
        }
    }

    private record Fold(Map<String, BigInteger> need, Map<String, BigInteger> delta, Map<String, BigInteger> counts) {}

    private static void verify(PlanStep word, List<GraphRecipe<String>> recipes, Map<String, BigInteger> stock,
                               Set<String> external, BigInteger[] expected) {
        var byId = new LinkedHashMap<String, GraphRecipe<String>>(); recipes.forEach(r -> byId.put(r.id(), r));
        Fold actual = fold(word, byId);
        for (int i = 0; i < recipes.size(); i++) check(actual.counts.getOrDefault(recipes.get(i).id(), BigInteger.ZERO).equals(expected[i]), "Wrong exact firing count");
        for (var port : actual.need.entrySet()) check(external.contains(port.getKey()) || stock.getOrDefault(port.getKey(), BigInteger.ZERO).compareTo(port.getValue()) >= 0, "Unfunded original prefix");
    }

    /** Separate arithmetic oracle; does not use the production summary or counter. */
    private static Fold fold(PlanStep step, Map<String, GraphRecipe<String>> recipes) {
        if (step instanceof PlanStep.Batch batch) {
            var r = recipes.get(batch.recipe()); var need = new LinkedHashMap<String, BigInteger>(); var delta = new LinkedHashMap<String, BigInteger>();
            r.inputs().forEach((k, v) -> { need.put(k, BigInteger.valueOf(v)); delta.merge(k, BigInteger.valueOf(v).negate(), BigInteger::add); });
            r.outputs().forEach((k, v) -> delta.merge(k, BigInteger.valueOf(v), BigInteger::add));
            return repeat(new Fold(need, delta, Map.of(batch.recipe(), BigInteger.ONE)), BigInteger.valueOf(batch.runs()));
        }
        if (step instanceof PlanStep.Repeat repeat) return repeat(fold(repeat.body(), recipes), BigInteger.valueOf(repeat.times()));
        Fold value = new Fold(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
        for (PlanStep part : ((PlanStep.Sequence) step).children()) {
            Fold next = fold(part, recipes);
            for (var e : next.need.entrySet()) value.need.merge(e.getKey(), e.getValue().subtract(value.delta.getOrDefault(e.getKey(), BigInteger.ZERO)).max(BigInteger.ZERO), BigInteger::max);
            next.delta.forEach((k, v) -> value.delta.merge(k, v, BigInteger::add));
            next.counts.forEach((k, v) -> value.counts.merge(k, v, BigInteger::add));
        }
        return value;
    }

    private static Fold repeat(Fold body, BigInteger n) {
        if (n.signum() == 0) return new Fold(Map.of(), Map.of(), Map.of());
        var need = new LinkedHashMap<>(body.need); var delta = new LinkedHashMap<String, BigInteger>(); var counts = new LinkedHashMap<String, BigInteger>();
        body.delta.forEach((k, v) -> { if (v.signum() < 0) need.merge(k, v.negate().multiply(n.subtract(BigInteger.ONE)), BigInteger::add); delta.put(k, v.multiply(n)); });
        body.counts.forEach((k, v) -> counts.put(k, v.multiply(n)));
        return new Fold(need, delta, counts);
    }

    private static boolean fire(GraphRecipe<String> recipe, Map<String, BigInteger> held, Set<String> external) {
        for (var port : recipe.inputs().entrySet()) if (!external.contains(port.getKey()) && held.getOrDefault(port.getKey(), BigInteger.ZERO).compareTo(BigInteger.valueOf(port.getValue())) < 0) return false;
        recipe.inputs().forEach((k, v) -> { if (!external.contains(k)) held.merge(k, BigInteger.valueOf(v).negate(), BigInteger::add); });
        recipe.outputs().forEach((k, v) -> { if (!external.contains(k)) held.merge(k, BigInteger.valueOf(v), BigInteger::add); });
        return true;
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) { return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out); }
    private static BigInteger[] fill(int n, BigInteger value) { var out = new BigInteger[n]; Arrays.fill(out, value); return out; }
    private static PlanningBudget budget() { return new PlanningBudget(0, 2_000_000, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
