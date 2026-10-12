// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Full-model turns must remain available during retained stock-view repair. */
public final class StockCountHandoffTest {
    // Existing canonical rendezvous_00986 and evolved_rendezvous_00013 inputs.
    // Each transition consumes one fuel; exhaustive raw firing is a finite oracle.
    private static final String INITIAL = """
            t6:F,A1.2,A2.4>A1.0,A2.4
            t12:F,A1.1>A1.3
            t11:F,A0.1>A0.2
            t5:F,A2.2,A1.2>A2.4,A1.3
            t15:F,A1.1,A0.2,A2.2>A1.0,A0.1,A2.3
            t9:F,A0.1>A0.0
            t17:F,A0.0,A2.1,A1.0>A0.2,A2.3,A1.1
            finish:F,A0.0,A1.1,A2.4>GOAL
            t7:F,A1.1,A2.2>A1.3,A2.1
            t4:F,A0.2>A0.0
            t18:F,A0.2,A2.4>A0.0,A2.1
            t8:F,A0.1,A2.0,A1.0>A0.2,A2.3,A1.3
            t1:F,A2.0>A2.4
            t3:F,A1.0,A2.4,A0.2>A1.1,A2.4,A0.2
            t10:F,A0.1,A1.2>A0.1,A1.1
            t13:F,A2.1,A1.2,A0.1>A2.0,A1.3,A0.0
            t16:F,A2.2>A2.0
            t14:F,A2.2,A0.2,A1.3>A2.4,A0.1,A1.1
            t0:F,A0.0,A1.0>A0.1,A1.0
            t2:F,A0.1>A0.2
            """;
    private static final String RETAINED = """
            t17:F,A3.0,A2.4,A1.4>A3.2,A2.2,A1.0
            t16:F,A1.3,A2.0>A1.2,A2.3
            mutation23:F,A2.0,A3.2,A0.0>A2.2,A3.1,A0.2
            mutation24:F,A0.3,A3.1,A2.3>A0.1,A3.1,A2.3
            t1:F,A2.4>A2.3
            t14:F,A0.1,A3.0>A0.2,A3.1
            t11:F,A0.2,A3.2>A0.2,A3.0
            t18:F,A2.0,A1.0,A3.0>A2.1,A1.1,A3.2
            finish:F,A0.1,A1.2,A2.0,A3.1>GOAL
            t3:F,A0.3,A3.1,A1.4>A0.2,A3.1,A1.1
            t15:F,A0.3,A3.0>A0.0,A3.2
            t10:F,A3.1>A3.2
            t8:F,A2.4,A3.0>A2.0,A3.1
            t12:F,A1.3,A3.2>A1.3,A3.1
            t7:F,A3.1,A2.4,A0.3>A3.0,A2.4,A0.1
            t6:F,A0.1,A1.2>A0.3,A1.2
            t2:F,A0.0,A3.0>A0.3,A3.1
            t4:F,A1.1,A3.1,A2.3>A1.2,A3.0,A2.0
            t19:F,A1.4,A0.3,A3.2>A1.2,A0.1,A3.2
            t13:F,A3.2,A0.0,A2.2>A3.2,A0.3,A2.3
            mutation21:F,A2.2>A2.2
            t5:F,A2.0,A0.2,A3.0>A2.4,A0.1,A3.1
            mutation22:F,A0.2,A2.4,A3.2>A0.1,A2.0,A3.2
            t9:F,A3.1,A0.3,A2.0>A3.0,A0.1,A2.0
            t0:F,A0.0,A1.0,A2.0>A0.0,A1.4,A2.4
            """;

    public static void main(String[] args) throws Exception {
        boundedContinuation();
        for (String fixture : List.of(INITIAL, RETAINED)) {
            var recipes = parse(fixture);
            var stock = stock(fixture, fixture == INITIAL ? 3 : 8);
            check(!reachable(recipes, stock), "Original negative fixture is reachable");
            var compiler = new GraphCompiler<>(recipes);
            long work = fixture == INITIAL ? 1_000_000 : 4_000_000;
            // Same tight cap cold and warm. Previously these consumed 9M/7M.
            for (int repeat = 0; repeat < 2; repeat++)
                solve(compiler, stock, false, work, 0);
            for (int workers : new int[]{1, 4}) solve(new GraphCompiler<>(recipes), stock, false, 40_000_000, workers);
            var replenished = new LinkedHashMap<>(stock);
            recipes.stream().filter(r -> r.id().equals("finish")).findFirst().orElseThrow()
                    .inputs().forEach((key, amount) -> replenished.merge(key, amount, Math::max));
            check(reachable(recipes, replenished), "Funded finish has no raw witness");
            solve(compiler, replenished, true, 4_000_000, 0);
            solve(compiler, stock, false, work, 0);
            for (int seed = 0; seed < 12; seed++) {
                var shuffled = new ArrayList<>(recipes);
                Collections.shuffle(shuffled, new Random(seed + 82169));
                var inventory = stock(fixture, 1 + seed);
                solve(new GraphCompiler<>(shuffled), inventory, reachable(shuffled, inventory), 40_000_000, 0);
            }
            cancellation(fixture);
        }
        System.out.println("Stock/count handoff: first and retained count turns, cold/warm caps, 24 raw-BFS order/stock checks, workers and cancellation passed");
    }

    private static void boundedContinuation() throws Exception {
        var budget = new PlanningBudget(0, 40_000_000, 256L << 20, () -> false, System::nanoTime);
        try (var counts = new IntegerCountSearch<>(new GraphCompiler<>(parse(RETAINED)), "GOAL", 1,
                stock(RETAINED, 8), Map.of(), Set.of(), Set.of(), false, true, budget, System.nanoTime())) {
            counts.scout(1024);
            while (!counts.step()) {}
            check(counts.paused(), "Expected a real suspended count frontier");
            for (long grant : new long[]{1, 17, 32768, Long.MAX_VALUE}) {
                long before = number(counts, "work");
                counts.resumeBounded(grant);
                long ceiling = number(counts, "allowance");
                check(ceiling > before && ceiling - before <= Math.min(grant, 2_000_000),
                        "Bounded resume exceeded its funded grant");
                while (!counts.step()) {}
                check(number(counts, "allowance") == ceiling, "Local progress enlarged a bounded parent grant");
                if (!counts.paused()) break;
                check(!counts.infeasible() && counts.result() == null, "Pause became a conclusion");
                for (long invalid : new long[]{0, -1}) {
                    try { counts.resumeBounded(invalid); throw new AssertionError("Invalid grant accepted"); }
                    catch (IllegalArgumentException expected) {}
                }
            }
            while (counts.paused()) {
                counts.resume();
                while (!counts.step()) {}
            }
            check(counts.infeasible() && counts.result() == null, "Short grants lost the original root proof");
        }
        check(budget.reservedBytes() == 0, "Bounded continuation leaked memory");
    }

    private static long number(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(owner);
    }

    private static void solve(GraphCompiler<String> compiler, Map<String, Long> stock, boolean expected,
                              long maximumWork, int workers) throws Exception {
        var budget = new PlanningBudget(0, maximumWork, 256L << 20, () -> false, System::nanoTime);
        GraphPlan<String> result;
        var work = new GraphPlanner<>(compiler).begin("GOAL", 1, stock, false, true, budget);
        try {
            if (workers == 0) {
                while (!work.step()) {}
                result = work.result();
            } else try (var scheduler = new PlanningScheduler(workers, 4, 127, 1_000_000_000)) {
                result = scheduler.submit(work, budget).get(60, TimeUnit.SECONDS);
            }
            boolean negative = Set.of(GraphPlan.Result.MISSING_INPUT, GraphPlan.Result.MISSING_SEED,
                    GraphPlan.Result.INFEASIBLE).contains(result.result());
            check(expected ? result.feasible() : negative,
                    "Wrong/incomplete conclusion: " + result.result() + "; stock=" + stock + "; " + budget.diagnostics());
            if (result.feasible()) PlanVerifier.verify(result);
        } finally { work.close(); }
        check(budget.reservedBytes() == 0, "Handoff leaked memory");
    }

    private static void cancellation(String fixture) throws Exception {
        var cancelled = new AtomicBoolean();
        var budget = new PlanningBudget(0, 40_000_000, 256L << 20, cancelled::get, System::nanoTime);
        var work = new GraphPlanner<>(new GraphCompiler<>(parse(fixture)))
                .begin("GOAL", 1, stock(fixture, fixture == INITIAL ? 3 : 8), false, true, budget);
        try {
            // Cancel while both real frontiers exist, not on an empty solver.
            var views = GraphPlanningWork.class.getDeclaredField("stockView");
            var counts = GraphPlanningWork.class.getDeclaredField("countSearch");
            views.setAccessible(true); counts.setAccessible(true);
            while (views.get(work) == null || counts.get(work) == null)
                check(!work.step(), "No stock/count handoff was exercised");
            cancelled.set(true);
            try {
                while (!work.step()) {}
                throw new AssertionError("Cancelled handoff published a conclusion");
            } catch (java.util.concurrent.CancellationException expected) {
                // Synchronous cancellation may escape before result publication.
            }
        } finally { work.close(); }
        check(budget.reservedBytes() == 0, "Cancelled handoff leaked memory");
    }

    private static List<GraphRecipe<String>> parse(String text) {
        var result = new ArrayList<GraphRecipe<String>>();
        for (String line : text.strip().split("\\R")) {
            var pair = line.strip().split(":");
            var arcs = pair[1].split(">");
            var slots = Arrays.stream(arcs[0].split(",")).map(k -> new GraphRecipe.Slot<>(k, 1L)).toList();
            var outputs = new LinkedHashMap<String, Long>();
            for (String key : arcs[1].split(",")) outputs.put(key, 1L);
            result.add(new GraphRecipe<>(pair[0], pair[0], slots, outputs));
        }
        return result;
    }

    private static Map<String, Long> stock(String fixture, long fuel) {
        var result = new LinkedHashMap<String, Long>();
        result.put("F", fuel);
        for (int i = 0; i < (fixture == INITIAL ? 3 : 4); i++) result.put("A" + i + ".0", 1L);
        return result;
    }

    private static boolean reachable(List<GraphRecipe<String>> recipes, Map<String, Long> stock) {
        var keys = new TreeSet<>(stock.keySet());
        for (var r : recipes) { keys.addAll(r.inputs().keySet()); keys.addAll(r.outputs().keySet()); }
        var ordered = List.copyOf(keys);
        int target = ordered.indexOf("GOAL");
        var initial = ordered.stream().map(k -> stock.getOrDefault(k, 0L).intValue()).toList();
        var seen = new HashSet<List<Integer>>();
        var todo = new ArrayDeque<List<Integer>>();
        todo.add(initial); seen.add(initial);
        while (!todo.isEmpty()) {
            var state = todo.removeFirst();
            if (state.get(target) > 0) return true;
            for (var recipe : recipes) {
                var next = new ArrayList<Integer>();
                boolean enabled = true;
                for (int i = 0; i < ordered.size(); i++) {
                    String key = ordered.get(i);
                    int input = Math.toIntExact(recipe.inputs().getOrDefault(key, 0L));
                    if (input > state.get(i)) { enabled = false; break; }
                    next.add(state.get(i) - input + Math.toIntExact(recipe.outputs().getOrDefault(key, 0L)));
                }
                if (enabled && seen.add(next)) todo.addLast(next);
            }
        }
        return false;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
