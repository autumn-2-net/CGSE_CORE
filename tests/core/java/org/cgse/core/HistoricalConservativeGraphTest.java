// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;

/** Migrate the argument-only branch/portfolio reproducers to a finite-state oracle. */
public final class HistoricalConservativeGraphTest {
    private static boolean reachable(List<GraphRecipe<String>> recipes, int[] initial, int amount) {
        var seen = new HashSet<List<Integer>>();
        var queue = new ArrayDeque<List<Integer>>();
        var root = Arrays.stream(initial).boxed().toList();
        queue.add(root);
        seen.add(root);
        while (!queue.isEmpty()) {
            var state = queue.removeFirst();
            if (state.get(initial.length - 1) >= amount) return true;
            for (var recipe : recipes) {
                if (recipe.inputs().entrySet().stream().anyMatch(e -> state.get(Integer.parseInt(e.getKey())) < e.getValue())) continue;
                var next = new ArrayList<>(state);
                recipe.inputs().forEach((key, n) -> { int i = Integer.parseInt(key); next.set(i, next.get(i) - Math.toIntExact(n)); });
                recipe.outputs().forEach((key, n) -> { int i = Integer.parseInt(key); next.set(i, next.get(i) + Math.toIntExact(n)); });
                if (seen.add(next)) queue.addLast(next);
            }
        }
        return false;
    }

    public static void main(String[] args) {
        int checked = 0, sat = 0;
        for (boolean wide : new boolean[] {false, true}) {
            var random = new Random(90525117);
            for (int sample = 0; sample < 64; sample++) {
                int size = 4 + random.nextInt(4), total = 3 + random.nextInt(8), amount = 1 + random.nextInt(total);
                int[] initial = new int[size];
                for (int i = 0; i < total; i++) initial[random.nextInt(size - 1)]++;
                var recipes = new ArrayList<GraphRecipe<String>>();
                for (int j = 0, count = (wide ? 8 : 4) + random.nextInt(wide ? 21 : 13); j < count; j++) {
                    var in = new HashMap<String, Long>();
                    var out = new HashMap<String, Long>();
                    for (int k = 0, tokens = 1 + random.nextInt(4); k < tokens; k++) {
                        in.merge("" + random.nextInt(size), 1L, Long::sum);
                        out.merge("" + random.nextInt(size), 1L, Long::sum);
                    }
                    recipes.add(HistoricalFixtures.r("r" + j, in, out));
                }
                boolean oracle = reachable(recipes, initial, amount);
                if (oracle) sat++;
                var stock = new HashMap<String, Long>();
                for (int i = 0; i < size; i++) if (initial[i] > 0) stock.put("" + i, (long) initial[i]);
                for (int order = 0; order < 3; order++) {
                    var ordered = new ArrayList<>(recipes);
                    if (order > 0) Collections.shuffle(ordered, new Random(173L * sample + order));
                    var budget = new PlanningBudget(0, 40_000_000, 128L << 20, () -> false, System::nanoTime);
                    var work = new GraphPlanningWork<>(new GraphCompiler<>(ordered), "" + (size - 1), amount, stock, false, false, budget);
                    try {
                        while (!work.step()) {}
                        var plan = work.result();
                        if (!Set.of(GraphPlan.Result.FEASIBLE, GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL,
                                GraphPlan.Result.MISSING_INPUT, GraphPlan.Result.MISSING_SEED, GraphPlan.Result.INFEASIBLE).contains(plan.result()))
                            throw new AssertionError("Unresolved historical graph " + wide + "/" + sample + "/" + order + ": " + plan.result());
                        if (plan.feasible() != oracle) throw new AssertionError("Finite-state oracle mismatch " + wide + "/" + sample + "/" + order);
                        if (plan.feasible()) {
                            PlanVerifier.verifyRuntimeInventory(plan);
                            HistoricalFixtures.verify(new HistoricalFixtures.Fixture("conserved", ordered, stock, plan.target(), amount, false),
                                    HistoricalFixtures.summary(plan.steps(), plan.recipes()));
                        }
                        checked++;
                    } finally { work.close(); }
                    if (budget.reservedBytes() != 0) throw new AssertionError("Conservative graph leaked memory");
                }
            }
        }
        System.out.println("Historical branch/portfolio reproducers: 128 exact finite-state oracles, " + sat + " SAT; " + checked + " order replays passed");
    }
}
