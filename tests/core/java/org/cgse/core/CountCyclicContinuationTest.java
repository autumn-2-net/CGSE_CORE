// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Optional endpoint assembly must preserve the funded forward continuation. */
public final class CountCyclicContinuationTest {
    private static final long MEMORY = 64L << 20;
    private static int checks;

    public static void main(String[] args) throws Exception {
        for (String exit : List.of("resume", "cancel", "ceiling", "forward_memory")) reverseMemory(exit);
        for (String exit : List.of("finish", "cancel", "ceiling")) startupOverlay(exit);
        System.out.println("CountCyclicContinuationTest PASS: checks=" + checks);
    }

    private static void reverseMemory(String exit) throws Exception {
        var recipe = r("craft", Map.of("raw", 1L), Map.of("goal", 1L));
        var budget = budget();
        var search = new CountCyclicStockSearch<>(new GraphCompiler<>(List.of(recipe)), "goal", 1,
                Map.of("raw", 1L), Set.of(), Map.of(), Set.of(), true, true, budget, System.nanoTime());
        long held = 0;
        try {
            until(search, () -> get(search, "schedule") != null);
            Object forward = get(search, "schedule");
            // The production trigger is pass 64. Enter that exact transition
            // early; the actual count vector and both schedulers stay intact.
            invoke(search, "tryReverse");
            until(search, () -> {
                Object candidate = get(search, "candidate");
                return candidate != null && (int) get(candidate, "stage") == 7;
            });
            check(get(search, "schedule") == forward, "Reverse computation replaced the forward scan");
            long allowance = (long) get(search, "allowance");
            long work = (long) get(search, "work");
            budget.failureDetail("prior detail");
            held = MEMORY - budget.reservedBytes() - 32;
            budget.reserve(held);
            check(!search.step(), "Reverse candidate memory ended the entire count proposal");
            check(get(search, "schedule") == forward, "Reverse candidate memory discarded the forward scan");
            check(get(search, "candidate") == null && !(boolean) get(search, "reverseCandidate"),
                    "Reverse candidate state survived its failed workspace");
            check(budget.failureDetail().equals("prior detail"), "Optional memory failure replaced request evidence");
            check((long) get(search, "allowance") == allowance && (long) get(search, "work") >= work,
                    "Continuation replenished its account");
            if (!exit.equals("forward_memory")) { budget.release(held); held = 0; }
            if (exit.equals("cancel")) {
                budget.cancel();
                boolean cancelled = false;
                try { search.step(); } catch (CancellationException expected) { cancelled = true; }
                check(cancelled, "Reverse recovery bypassed cancellation");
            } else if (exit.equals("ceiling")) {
                set(search, "work", allowance);
                check(search.step() && search.result() == null, "Reverse recovery bypassed its fixed ceiling");
            } else {
                finish(search);
                if (exit.equals("forward_memory"))
                    check(search.result() == null, "An unverified forward word escaped under memory pressure");
                else {
                    check(search.result() != null, "Preserved forward continuation lost a funded witness");
                    verify(search.result(), Map.of("raw", 1L));
                    check(search.result().patternTimesExact().get("craft").equals(BigInteger.ONE), "Counts changed");
                }
            }
        } finally {
            if (held != 0) budget.release(held);
            search.close(); search.close();
        }
        check(budget.reservedBytes() == 0, "Reverse continuation leaked a workspace");
    }

    private static void startupOverlay(String exit) throws Exception {
        var recipes = List.of(r("grow", Map.of("seed", 1L, "ore", 1L), Map.of("seed", 9L)),
                r("finish", Map.of("seed", 144L), Map.of("goal", 1L)));
        var compiler = new GraphCompiler<>(recipes);
        var stock = Map.of("seed", 144L, "ore", 1L);
        var budget = budget();
        var search = new CountCyclicStockSearch<>(compiler, "goal", 1, stock, Set.of(), Map.of(), Set.of(),
                true, true, budget, System.nanoTime());
        RecipeCountModel<?> model = null;
        Map<?, ?> originalGoals = null;
        List<?> originalRows = null;
        try {
            until(search, () -> get(search, "model") != null);
            model = (RecipeCountModel<?>) get(search, "model");
            originalGoals = new LinkedHashMap<>(model.goals);
            originalRows = new ArrayList<>(model.constraints);
            until(search, () -> get(search, "schedule") != null && (boolean) get(search, "originalTried"));
            Object originalCounts = get(search, "originalCounts"), components = get(search, "originalComponents");
            Object forward = get(search, "schedule");
            invoke(search, "tryReverse");
            until(search, () -> get(search, "candidate") != null);
            until(search, () -> get(search, "candidate") == null);
            check(get(search, "schedule") == forward && !(boolean) get(search, "reverseCandidate"),
                    "Invalid reverse seed proposal discarded the original scan");
            check(search.result() == null, "A startup debt was accepted as a funded witness");
            until(search, () -> (boolean) get(search, "startupTried"));
            check(get(search, "originalCounts") == originalCounts && get(search, "originalComponents") == components,
                    "Startup completion replaced the saved original proposal");
            check(model.goals.get("seed").equals(BigInteger.ONE), "Startup goal was not the minimum loan");
            if (exit.equals("cancel")) {
                budget.cancel();
                boolean cancelled = false;
                try { search.step(); } catch (CancellationException expected) { cancelled = true; }
                check(cancelled, "Startup repair bypassed cancellation");
            } else if (exit.equals("ceiling")) {
                set(search, "work", get(search, "allowance"));
                check(search.step() && search.result() == null, "Startup repair replenished the local ceiling");
            } else {
                finish(search);
                var plan = search.result();
                check(plan != null && plan.seeds().getOrDefault("seed", 0L) == 1, "Startup debt was not retained");
                check(plan.patternTimesExact().get("grow").equals(BigInteger.ONE), "Startup repair overproduced");
                verify(plan, stock);
            }
        } finally {
            search.close(); search.close();
        }
        check(model != null && model.goals.equals(originalGoals) && model.constraints.equals(originalRows),
                "Closing startup repair did not restore original constraints and goals");
        check(budget.reservedBytes() == 0, "Startup repair leaked memory");
        // Reusing the exact compiler after success/cancellation/limits cannot
        // inherit this request's strengthened seed goal.
        var next = budget();
        try (var retry = new CountCyclicStockSearch<>(compiler, "goal", 1, Map.of("seed", 144L), Set.of(), Map.of(),
                Set.of(), false, true, next, System.nanoTime())) {
            finish(retry);
            check(retry.result() != null && retry.result().seeds().isEmpty(), "Startup overlay contaminated the next request");
            verify(retry.result(), Map.of("seed", 144L));
        }
        check(next.reservedBytes() == 0, "Retry leaked memory");
    }

    private static void verify(GraphPlan<String> plan, Map<String, Long> stock) {
        check(plan.feasible() && plan.missingExact().isEmpty(), "Unfunded candidate escaped");
        var values = new HashMap<String, BigInteger>();
        stock.forEach((key, value) -> values.put(key, BigInteger.valueOf(value)));
        execute(plan.steps(), plan.recipes(), values);
        values.merge(plan.target(), BigInteger.valueOf(plan.amount()).negate(), BigInteger::add);
        check(values.get(plan.target()).signum() >= 0, "Delivery exceeded final inventory");
        plan.seeds().forEach((key, amount) -> check(values.getOrDefault(key, BigInteger.ZERO).compareTo(BigInteger.valueOf(amount)) >= 0,
                "Required terminal seed was consumed"));
    }
    private static void execute(PlanStep step, Map<String, GraphRecipe<String>> recipes, Map<String, BigInteger> values) {
        if (step instanceof PlanStep.Sequence s) { for (var child : s.children()) execute(child, recipes, values); }
        else if (step instanceof PlanStep.Repeat r) { for (long i = 0; i < r.times(); i++) execute(r.body(), recipes, values); }
        else {
            var batch = (PlanStep.Batch) step;
            var recipe = recipes.get(batch.recipe());
            for (long i = 0; i < batch.runs(); i++) {
                recipe.inputs().forEach((key, amount) -> {
                    check(values.getOrDefault(key, BigInteger.ZERO).compareTo(BigInteger.valueOf(amount)) >= 0, "Unfunded prefix");
                    values.merge(key, BigInteger.valueOf(amount).negate(), BigInteger::add);
                });
                recipe.outputs().forEach((key, amount) -> values.merge(key, BigInteger.valueOf(amount), BigInteger::add));
            }
        }
    }
    private interface Condition { boolean holds() throws Exception; }
    private static void until(CountCyclicStockSearch<?> search, Condition condition) throws Exception {
        for (int i = 0; !condition.holds(); i++) check(i < 100000 && !search.step(), "Count proposal ended before required transition");
    }
    private static void finish(CountCyclicStockSearch<?> search) {
        for (int i = 0; !search.step(); i++) check(i < 100000, "Continuation failed to stop");
    }
    private static GraphRecipe<String> r(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        return new GraphRecipe<>(id, id, inputs.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), outputs);
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 16_000_000, MEMORY, () -> false, System::nanoTime); }
    private static Object get(Object object, String name) throws Exception { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
    private static void set(Object object, String name, Object value) throws Exception { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object, value); }
    private static void invoke(Object object, String name) throws Exception { Method m = object.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(object); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
