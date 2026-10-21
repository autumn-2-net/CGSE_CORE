// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Target stock is a possible cycle seed, never a substitute for forced production. */
public final class RefillProductionCreditTest {
    public static void main(String[] args) {
        for (int order = 0; order < 6; order++) for (long targetStock : new long[]{0, 1, 100}) {
            checkCase(order, targetStock, false, false);
            checkCase(order, targetStock, false, true);
            checkCase(order, targetStock, true, false);
        }
        cancelledRepair();
        System.out.println("RefillProductionCreditTest PASS: 54 ordered cycle/escape/growth witnesses, exact production and cancellation");
    }

    private static void checkCase(int order, long targetStock, boolean growth, boolean escape) {
        var recipes = recipes(growth, escape);
        Collections.shuffle(recipes, new Random(order));
        Map<String, Long> stock = Map.of("G", targetStock);
        var original = original(recipes, stock, growth);
        var budget = budget();
        GraphPlan<String> plan;
        try (var expansion = new MissingMaterialExpansion<>(new GraphCompiler<>(recipes), original, stock,
                Set.of(), Set.of(), true, false, budget)) {
            while (!expansion.step()) {}
            plan = expansion.result();
        }
        check(budget.reservedBytes() == 0, "retained production repair workspace");
        check(plan != null, "lost funded suffix");
        // Execute primitive firings from stock plus the advertised bill. Count
        // net target production separately from the supplied target inventory.
        var held = new HashMap<String, BigInteger>();
        stock.forEach((k,v) -> held.put(k, BigInteger.valueOf(v)));
        plan.missingExact().forEach((k,v) -> held.merge(k,v,BigInteger::add));
        BigInteger before = held.getOrDefault("G", BigInteger.ZERO);
        BigInteger[] target = {before, BigInteger.ZERO};
        fire(plan.steps(), plan.recipes(), held, target);
        // Productive growth may consume an initial target as startup material.
        // Require positive net gain and newly manufactured deliverable tokens;
        // neither initial stock nor a zero-gain turnover satisfies the order.
        check(held.getOrDefault("G", BigInteger.ZERO).compareTo(before) > 0 &&
                        target[1].compareTo(BigInteger.valueOf(plan.amount())) >= 0,
                "refill spent verified production: " + plan.missingExact());
        held.merge("G", BigInteger.valueOf(plan.amount()).negate(), BigInteger::add);
        original.seeds().forEach((k,v) -> check(held.getOrDefault(k, BigInteger.ZERO).compareTo(BigInteger.valueOf(v)) >= 0,
                "lost mandatory seed: " + k));
        check(!plan.missingExact().containsKey("X"), "left an expandable downstream material");
        if (!growth) check(plan.missingExact().equals(Map.of(escape ? "raw" : "A", BigInteger.TWO)),
                "incorrect cycle/escape bill: " + plan.missingExact());
    }

    private static void cancelledRepair() {
        var recipes = recipes(false, false);
        var original = original(recipes, Map.of(), false);
        // Include stops after prefix construction and after the one-time
        // production repair, without depending on internal phase numbering.
        for (int stop = 0; stop < 65; stop++) {
            var cancel = new java.util.concurrent.atomic.AtomicBoolean();
            var budget = new PlanningBudget(0, 1_000_000, 16L << 20, cancel::get, System::nanoTime);
            try (var expansion = new MissingMaterialExpansion<>(new GraphCompiler<>(recipes), original, Map.of(),
                    Set.of(), Set.of(), true, false, budget)) {
                boolean done = false;
                for (int i = 0; i < stop && !done; i++) done = expansion.step();
                if (!done) {
                    cancel.set(true);
                    try { expansion.step(); throw new AssertionError("ignored cancellation"); }
                    catch (java.util.concurrent.CancellationException expected) { /* Cooperative repair. */ }
                }
            }
            check(budget.reservedBytes() == 0, "cancelled repair retained workspace");
        }
    }

    private static void fire(PlanStep step, Map<String, GraphRecipe<String>> recipes, Map<String, BigInteger> held,
                             BigInteger[] target) {
        if (step instanceof PlanStep.Sequence seq) {
            for (var child : seq.children()) fire(child, recipes, held, target);
        } else if (step instanceof PlanStep.Repeat repeat) {
            check(repeat.times() < 1000, "primitive oracle bound");
            for (long i=0; i<repeat.times(); i++) fire(repeat.body(), recipes, held, target);
        } else {
            var batch = (PlanStep.Batch) step;
            check(batch.runs() < 1000, "primitive oracle bound");
            var recipe = recipes.get(batch.recipe());
            for (long i=0; i<batch.runs(); i++) {
                BigInteger consumed = BigInteger.valueOf(recipe.inputs().getOrDefault("G", 0L));
                BigInteger old = target[0].min(consumed);
                target[0] = target[0].subtract(old);
                target[1] = target[1].subtract(consumed.subtract(old));
                check(target[1].signum() >= 0, "unfunded target firing");
                recipe.inputs().forEach((k,v) -> {
                    var remaining = held.getOrDefault(k, BigInteger.ZERO).subtract(BigInteger.valueOf(v));
                    check(remaining.signum() >= 0, "unfunded input " + k);
                    held.put(k, remaining);
                });
                recipe.outputs().forEach((k,v) -> held.merge(k, BigInteger.valueOf(v), BigInteger::add));
                target[1] = target[1].add(BigInteger.valueOf(recipe.outputs().getOrDefault("G", 0L)));
            }
        }
    }

    private static ArrayList<GraphRecipe<String>> recipes(boolean growth, boolean escape) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        // W is a separate mandatory reserve. Its route consumes G, while G/A
        // form a lower-rank SCC. Expansion must restore the spent G even when
        // that requires adding an actual loan at the SCC boundary.
        if (growth) recipes.add(recipe("grow", Map.of("G",1L,"feed",1L), Map.of("G",2L)));
        recipes.add(recipe("finish", Map.of("A",1L), Map.of("G",1L)));
        recipes.add(recipe("recycle", Map.of("G",1L), Map.of("A",1L)));
        recipes.add(recipe("part", Map.of("G",1L), Map.of("X",1L)));
        recipes.add(recipe("reserve", Map.of("X",1L), Map.of("W",1L)));
        if (escape) recipes.add(recipe("escape", Map.of("raw",1L), Map.of("G",1L)));
        return recipes;
    }

    private static GraphPlan<String> original(List<GraphRecipe<String>> recipes, Map<String, Long> stock, boolean growth) {
        var indexed = new LinkedHashMap<String, GraphRecipe<String>>();
        recipes.forEach(r -> indexed.put(r.id(),r));
        var steps = new ArrayList<PlanStep>();
        steps.add(new PlanStep.Batch("reserve",1)); steps.add(new PlanStep.Batch("finish",1));
        if (growth) steps.add(new PlanStep.Batch("grow",1));
        var word = new PlanStep.Sequence(steps);
        long amount = growth ? 2 : 1;
        var seeds = Map.of("W",1L);
        var initial = PlanVerifier.initialExact(word,indexed,"G",amount,seeds);
        var missing = new LinkedHashMap<String,BigInteger>();
        initial.forEach((k,v) -> {
            var gap = v.subtract(BigInteger.valueOf(stock.getOrDefault(k,0L)));
            if (gap.signum() > 0) missing.put(k,gap);
        });
        return new GraphPlan<>("G",amount,true,word,indexed,initial,seeds,missing,GraphPlan.Result.MISSING_INPUT,0,0);
    }

    private static GraphRecipe<String> recipe(String id, Map<String,Long> inputs, Map<String,Long> outputs) {
        return MissingMaterialExpansionTest.recipe(id,inputs,outputs);
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 2_000_000, 32L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
