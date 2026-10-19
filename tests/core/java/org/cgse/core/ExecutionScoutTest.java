// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

/** Structural admission only schedules the existing, independently checked search. */
public final class ExecutionScoutTest {
    private static int checks;
    public static void main(String[] args) {
        boundaries(); finiteOracles(); limits();
        System.out.println("ExecutionScoutTest: assertions=" + checks);
    }
    private static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static List<GraphRecipe<String>> base() {
        return List.of(r("ab", Map.of("a", 1L, "fuel", 1L), Map.of("b", 1L)),
                r("ba", Map.of("b", 1L, "fuel", 1L), Map.of("a", 1L)),
                r("finish", Map.of("b", 1L, "fuel", 1L), Map.of("goal", 1L)));
    }
    private static boolean hint(List<GraphRecipe<String>> recipes, Map<String, Long> stock, Set<String> external) {
        var b = budget();
        try (var model = RecipeCountModel.forShell(recipes, Map.of("goal", BigInteger.ONE), stock, external, b)) {
            long held = b.reservedBytes();
            boolean value = CountReachability.smallClosedPopulation(model);
            check(b.reservedBytes() == held, "Admission retained scratch memory");
            return value;
        } finally { check(b.reservedBytes() == 0, "Model workspace leaked"); }
    }
    private static void boundaries() {
        for (boolean reverse : List.of(false, true)) for (var external : List.of(Set.<String>of(), Set.of("fuel"))) {
            var recipes = new ArrayList<>(base()); if (reverse) Collections.reverse(recipes);
            for (long population : new long[] {0, 1, 4, 8, 9, Long.MAX_VALUE}) {
                check(hint(recipes, Map.of("a", population, "fuel", 1000L), external) == (population > 0 && population <= 8), "Population boundary");
            }
            for (var extra : List.of(r("birth", Map.of("fuel", 1L), Map.of("a", 1L)),
                    r("split", Map.of("a", 1L), Map.of("a", 1L, "b", 1L)),
                    r("double", Map.of("a", 1L), Map.of("b", 2L)),
                    r("weighted", Map.of("a", 2L), Map.of("b", 1L)))) {
                var changed = new ArrayList<>(recipes); changed.add(extra);
                check(!hint(changed, Map.of("a", 1L, "fuel", 10L), external), "Nonclosed or weighted model admitted");
            }
        }
        var config = new GraphRecipe<>("config", "config", List.of(new GraphRecipe.Slot<>("a", 1),
                new GraphRecipe.Slot<>("config", 1, 0, true, false)), Map.of("a", 1L));
        var recipes = new ArrayList<>(base()); recipes.add(config);
        check(!hint(recipes, Map.of("a", 1L, "config", 1L), Set.of()), "Per-push consumption entered a per-firing hint");
        recipes = new ArrayList<>(base());
        for (int i = 0; i < 65; i++) recipes.add(r("copy" + i, Map.of("a", 1L), Map.of("a", 1L)));
        check(!hint(recipes, Map.of("a", 1L), Set.of()), "Unbounded recipe scan");
        recipes = new ArrayList<>(base());
        var ports = new LinkedHashMap<String, Long>(); for (int i = 0; i < 33; i++) ports.put("p" + i, 1L);
        recipes.add(r("wide", ports, ports));
        check(!hint(recipes, Map.of("a", 1L), Set.of()), "Unbounded material scan");
    }
    private static void finiteOracles() {
        var random = new Random(202610193L); int positive = 0, negative = 0;
        for (int sample = 0; sample < 120; sample++) {
            int width = 2 + random.nextInt(5), exit = random.nextInt(width);
            var recipes = new ArrayList<GraphRecipe<String>>();
            for (int i = 0; i < width; i++) {
                var in = new LinkedHashMap<String, Long>(); var out = new LinkedHashMap<String, Long>();
                in.put("p" + i, 1L); in.put("fuel", 1L); in.put("tool", 1L);
                out.put("p" + ((i + 1) % width), 1L); out.put("tool", 1L);
                recipes.add(r("r" + i, in, out));
            }
            recipes.add(r("finish", Map.of("p" + exit, 1L, "fuel", 1L, "tool", 1L), Map.of("goal", 1L, "tool", 1L)));
            Collections.shuffle(recipes, random);
            var stock = Map.of("p0", 1L, "tool", 1L, "fuel", (long) random.nextInt(width + 2));
            boolean expected = reachable(recipes, stock);
            if (expected) positive++; else negative++;
            var b = budget();
            try (var model = RecipeCountModel.forShell(recipes, Map.of("goal", BigInteger.ONE), stock, Set.of(), b)) {
                check(CountReachability.smallClosedPopulation(model), "Unit token ring not recognized");
                try (var search = CountReachability.create(model, 32768)) {
                    while (!search.step()) {}
                    check(search.result() == (expected ? BackwardCoverability.Result.WITNESS : BackwardCoverability.Result.CLOSED), "Shape replaced the independent execution oracle");
                }
            }
            check(b.reservedBytes() == 0, "Finite oracle leaked");
        }
        check(positive > 20 && negative > 20, "Lost positive/negative coverage");
    }
    private static boolean reachable(List<GraphRecipe<String>> recipes, Map<String, Long> initial) {
        var seen = new HashSet<Map<String, Long>>(); var queue = new ArrayDeque<Map<String, Long>>();
        queue.add(Map.copyOf(initial)); seen.add(queue.peek());
        while (!queue.isEmpty()) {
            var state = queue.removeFirst(); if (state.getOrDefault("goal", 0L) > 0) return true;
            for (var recipe : recipes) {
                if (recipe.inputs().entrySet().stream().anyMatch(e -> state.getOrDefault(e.getKey(), 0L) < e.getValue())) continue;
                var next = new TreeMap<>(state);
                recipe.inputs().forEach((k,n) -> next.merge(k, -n, Long::sum));
                recipe.outputs().forEach((k,n) -> next.merge(k, n, Long::sum));
                next.values().removeIf(n -> n == 0);
                if (seen.add(next)) queue.add(next);
            }
        }
        return false;
    }
    private static void limits() {
        for (String mode : List.of("memory", "work", "time", "cancel")) {
            var cutoff = new AtomicLong(Long.MAX_VALUE); PlanningBudget[] owner = new PlanningBudget[1];
            var b = new PlanningBudget(mode.equals("time") ? 1 : 0, 100_000, 8L << 20,
                    () -> mode.equals("cancel") && owner[0] != null && owner[0].nodes() >= cutoff.get(),
                    () -> owner[0] != null && owner[0].nodes() >= cutoff.get() ? 2_000_000 : 0); owner[0] = b;
            try (var model = RecipeCountModel.forShell(base(), Map.of("goal", BigInteger.ONE), Map.of("a", 1L), Set.of(), b)) {
                long held = b.reservedBytes(), extra = 0;
                if (mode.equals("memory")) { extra = b.availableBytes(); b.reserve(extra); }
                else if (mode.equals("work")) b.charge(b.remainingWork() - 2);
                else cutoff.set(b.nodes() + 3);
                try { check(!CountReachability.smallClosedPopulation(model) && mode.equals("memory"), "Ignored admission interruption"); }
                catch (PlanningBudget.Exhausted e) { check(e.limit() == (mode.equals("time") ? PlanningBudget.Limit.TIMEOUT : PlanningBudget.Limit.SEARCH_LIMIT), "Wrong admission limit"); }
                catch (CancellationException e) { check(mode.equals("cancel"), "Unexpected cancellation"); }
                finally { b.release(extra); }
                check(b.reservedBytes() == held, "Interrupted admission leaked");
            }
            check(b.reservedBytes() == 0, "Interrupted model leaked");
        }
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 1_000_000, 16L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
}
