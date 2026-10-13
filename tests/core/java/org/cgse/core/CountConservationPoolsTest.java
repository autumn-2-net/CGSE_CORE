// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Original resource sums, changed supplies and finite primitive-state oracles. */
public final class CountConservationPoolsTest {
    public static void main(String[] args) {
        scope();
        randomNets();
        limits();
        System.out.println("Conservation pools: exact resource sums, 96 finite nets, supply/producer/external changes, wide coefficients and limits passed");
    }

    private static List<GraphRecipe<String>> fork(long unit) {
        return List.of(recipe("left", Map.of("B", unit), Map.of("L", unit)),
                recipe("right", Map.of("B", unit), Map.of("R", unit)),
                recipe("join", Map.of("L", unit, "R", unit), Map.of("B", unit, "G", 1L)));
    }

    private static void scope() {
        for (long unit : new long[]{1, Long.MAX_VALUE / 2 + 1}) {
            var recipes = fork(unit);
            var budget = budget();
            try (var model = RecipeCountModel.forShell(recipes, Map.of("G", BigInteger.ONE), Map.of("B", unit), Set.of(), budget);
                 var execution = new CountExecution<>(model, budget)) {
                var pools = CountConservationPools.separate(model, budget);
                check(!pools.isEmpty(), "split token sum was not discovered");
                verify(pools, recipes, model.stock, Set.of());
                execution.refine(model);
                var point = recipes.stream().map(r -> ExactRational.ONE).toArray(ExactRational[]::new);
                check(execution.violated(point) != null, "pooled startup guard missed the dead multiset");
            }
            check(budget.reservedBytes() == 0, "wide resource proof leaked");
        }
        for (int variant = 0; variant < 4; variant++) {
            var recipes = new ArrayList<>(fork(1));
            var stock = Map.of("B", variant == 0 ? 2L : 1L);
            var external = variant == 1 ? Set.of("B") : Set.<String>of();
            if (variant == 2) recipes.add(recipe("supply", Map.of(), Map.of("B", 1L)));
            if (variant == 3) recipes.add(new GraphRecipe<>("config", "config",
                    List.of(new GraphRecipe.Slot<>("B", 1, 0, true, false)), Map.of("B", 2L)));
            var budget = budget();
            try (var model = RecipeCountModel.forShell(recipes, Map.of("G", BigInteger.ONE), stock, external, budget)) {
                check(CountConservationPools.separate(model, budget).isEmpty(), "old pool escaped refreshed semantics " + variant);
            }
            check(budget.reservedBytes() == 0, "refreshed pool leaked");
        }
    }

    private static void randomNets() {
        var random = new Random(20261013);
        int found = 0;
        for (int test = 0; test < 96; test++) {
            int dimensions = 3 + random.nextInt(4);
            var recipes = new ArrayList<GraphRecipe<String>>();
            for (int r = 0; r < 7; r++) {
                var in = new LinkedHashMap<String, Long>();
                var out = new LinkedHashMap<String, Long>();
                for (int n = 1 + random.nextInt(4); n > 0; n--) {
                    in.merge("k" + random.nextInt(dimensions), 1L, Long::sum);
                    out.merge("k" + random.nextInt(dimensions), 1L, Long::sum);
                }
                recipes.add(recipe("r" + r, in, out));
            }
            var stock = Map.of("k0", 2L);
            Collections.shuffle(recipes, random);
            var budget = budget();
            try (var model = RecipeCountModel.forShell(recipes, Map.of("k1", BigInteger.ONE), stock, Set.of(), budget)) {
                var pools = CountConservationPools.separate(model, budget);
                found += pools.size();
                verify(pools, recipes, stock, Set.of());
                // Independent raw firing enumeration: every encountered state
                // must obey each published sum and its disabled-input conclusion.
                var keys = model.keys;
                var initial = keys.stream().map(k -> stock.getOrDefault(k, 0L)).toList();
                var pending = new ArrayDeque<List<Long>>();
                var seen = new HashSet<List<Long>>();
                pending.add(initial); seen.add(initial);
                while (!pending.isEmpty()) {
                    var state = pending.removeFirst();
                    for (var pool : pools) {
                        long total = 0, available = 0;
                        for (String key : pool) { total += state.get(keys.indexOf(key)); available += stock.getOrDefault(key, 0L); }
                        check(total <= available, "published invariant excluded an original reachable state");
                    }
                    for (var r : recipes) {
                        boolean enabled = true;
                        var next = new ArrayList<Long>();
                        for (int k = 0; k < keys.size(); k++) {
                            long in = r.inputs().getOrDefault(keys.get(k), 0L);
                            enabled &= state.get(k) >= in;
                            next.add(state.get(k) - in + r.outputs().getOrDefault(keys.get(k), 0L));
                        }
                        if (enabled && seen.add(next)) pending.add(next);
                    }
                    check(seen.size() <= 64, "generated oracle is no longer bounded");
                }
            }
            check(budget.reservedBytes() == 0, "random separator leaked");
        }
        check(found >= 48, "random cases failed to exercise separation");
    }

    private static void verify(List<List<String>> pools, List<GraphRecipe<String>> recipes,
                               Map<String, Long> stock, Set<String> external) {
        for (var pool : pools) {
            check(pool.stream().noneMatch(external::contains), "infinite resource received a weight");
            BigInteger held = BigInteger.ZERO;
            for (String key : pool) held = held.add(BigInteger.valueOf(stock.getOrDefault(key, 0L)));
            boolean excludes = false;
            for (var r : recipes) {
                BigInteger in = BigInteger.ZERO, out = BigInteger.ZERO;
                for (String key : pool) {
                    in = in.add(BigInteger.valueOf(r.inputs().getOrDefault(key, 0L)));
                    out = out.add(BigInteger.valueOf(r.outputs().getOrDefault(key, 0L)));
                }
                check(out.compareTo(in) <= 0, "unchecked producer increases the pool");
                excludes |= in.compareTo(held) > 0;
            }
            check(excludes, "irrelevant pool was published");
        }
    }

    private static void limits() {
        var budget = budget();
        try (var model = RecipeCountModel.forShell(fork(1), Map.of("G", BigInteger.ONE), Map.of("B", 1L), Set.of(), budget)) {
            long held = budget.availableBytes(); budget.reserve(held);
            check(CountConservationPools.separate(model, budget).isEmpty(), "memory refusal exported a pool");
            budget.release(held);
            budget.cancel();
            try { CountConservationPools.separate(model, budget); throw new AssertionError("separator ignored cancellation"); }
            catch (CancellationException expected) { /* Every query shares cancellation. */ }
        }
        check(budget.reservedBytes() == 0, "cancelled separator leaked");
        budget = new PlanningBudget(0, 4096, 64L << 20, () -> false, System::nanoTime);
        try (var model = RecipeCountModel.forShell(fork(1), Map.of("G", BigInteger.ONE), Map.of("B", 1L), Set.of(), budget)) {
            check(CountConservationPools.separate(model, budget).isEmpty(), "tiny budget admitted auxiliary search");
        }
        check(budget.reservedBytes() == 0, "tiny budget leaked");
    }

    private static PlanningBudget budget() { return new PlanningBudget(0, 40_000_000, 256L << 20, () -> false, System::nanoTime); }
    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
