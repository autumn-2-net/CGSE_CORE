// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A bounded refill search must preserve an actual program, not invent production. */
public final class MissingPreviewCompletionTest {
    public static void main(String[] args) {
        retainedPrefixes();
        returnedInputs();
        borrowedTarget();
        mutuallyConsumedSeeds();
        wideCounts();
        noInventedGoal();
        cancellation();
        nativePreview();
        System.out.println("Refill completion: retained prefixes, exact loans, wide counts, native preview and cancellation passed");
    }

    private static void retainedPrefixes() {
        for (int order = 0; order < 8; order++) for (int steps : new int[]{0, 4, 17, 80, 240}) {
            var recipes = new ArrayList<>(List.of(
                    recipe("a", Map.of("raw", 3L, "tool", 1L), Map.of("A", 1L, "tool", 1L)),
                    recipe("b", Map.of("A", 4L), Map.of("B", 1L)),
                    recipe("out", Map.of("B", 3L, "other", 2L), Map.of("G", 1L))));
            Collections.shuffle(recipes, new Random(order));
            Map<String, GraphRecipe<String>> indexed = index(recipes);
            var stock = Map.of("raw", BigInteger.valueOf(300), "tool", BigInteger.ONE);
            var budget = budget();
            var expansion = new DemandExpansion<>(indexed, stock, Map.of("G", BigInteger.valueOf(7)), budget, true);
            try {
                for (int i = 0; i < steps; i++) if (expansion.step()) break;
                expansion.completePreview("G");
                while (!expansion.step()) {}
                var program = Objects.requireNonNull(expansion.result(), "lost prefix after bounded refill");
                checkPrimitive(program, indexed, stock, "G", BigInteger.valueOf(7));
            } finally { expansion.close(); }
            check(budget.reservedBytes() == 0, "retained prefix leaked memory");
        }
    }

    private static void returnedInputs() {
        for (long returned : new long[]{1, 2, 3}) {
            // A partially consumed input needs a prefix loan, not simply total
            // gross input or the final net deficit. A virtual tool is a read arc.
            var recipe = new GraphRecipe<>("r", "r", List.of(new GraphRecipe.Slot<>("A", 3),
                    new GraphRecipe.Slot<>("tool", 1, 1, true, true)), Map.of("A", returned, "tool", 1L, "G", 1L));
            var recipes = index(List.of(recipe));
            var b = budget();
            var expansion = new DemandExpansion<>(recipes, Map.of(), Map.of("G", BigInteger.valueOf(11)), b, true);
            try {
                expansion.completePreview("G");
                while (!expansion.step()) {}
                var program = Objects.requireNonNull(expansion.result());
                var summary = SequenceSummary.of(program, recipes);
                check(summary.required("A").equals(BigInteger.valueOf(3 + (3 - returned) * 10)), "incorrect returned-input loan");
                check(summary.required("tool").equals(BigInteger.ONE), "virtual tool multiplied by runs");
                checkPrimitive(program, recipes, Map.of(), "G", BigInteger.valueOf(11));
            } finally { expansion.close(); }
            check(b.reservedBytes() == 0, "return completion leaked");
        }
    }

    private static void borrowedTarget() {
        var recipes = index(List.of(recipe("borrow", Map.of("G", 1L), Map.of("G", 1L, "A", 1L)),
                recipe("finish", Map.of("A", 3L), Map.of("G", 1L))));
        for (long existing : new long[]{0, 3}) for (int steps = 0; steps < 25; steps++) {
            var stock = Map.of("G", BigInteger.valueOf(existing));
            var b = budget();
            var e = new DemandExpansion<>(recipes, stock, Map.of("G", BigInteger.valueOf(existing + 7)), b, true);
            try {
                for (int i = 0; i < steps; i++) if (e.step()) break;
                e.completePreview("G");
                while (!e.step()) {}
                var program = Objects.requireNonNull(e.result());
                var held = new HashMap<>(stock);
                var missing = new HashMap<String, BigInteger>();
                fire(program, recipes, held, missing);
                check(held.getOrDefault("G", BigInteger.ZERO).subtract(missing.getOrDefault("G", BigInteger.ZERO))
                        .compareTo(BigInteger.valueOf(existing + 7)) >= 0, "borrowed target seed replaced production");
                var funded = new HashMap<>(stock);
                missing.forEach((k,v) -> funded.merge(k,v,BigInteger::add));
                var extra = new HashMap<String, BigInteger>();
                fire(program, recipes, funded, extra);
                check(extra.isEmpty(), "target seed refill was not sufficient");
            } finally { e.close(); }
            check(b.reservedBytes() == 0, "borrowed target completion leaked");
        }
    }

    private static void mutuallyConsumedSeeds() {
        for (int size : new int[]{8, 33}) for (int order : new int[]{0, 7}) {
            var recipes = new ArrayList<GraphRecipe<String>>();
            var seeds = new LinkedHashMap<String, Long>();
            for (int i = 0; i < size; i++) {
                recipes.add(recipe("r" + i, Map.of("s" + ((i + 1) % size), 2L), Map.of("s" + i, 1L)));
                seeds.put("s" + i, 1L);
            }
            recipes.add(recipe("finish", Map.of("s0", 1L), Map.of("G", 1L)));
            Collections.shuffle(recipes, new Random(order));
            var b = new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime);
            var work = new GraphPlanningWork<>(new GraphCompiler<>(recipes), "G", 43, Map.of(), Set.of(), seeds, true, true, b);
            try {
                while (!work.step()) {}
                var plan = work.result();
                check(!plan.feasible() && !plan.missingExact().isEmpty(), "mutually consumed seeds lost refill");
                var held = new HashMap<>(plan.missingExact());
                var extra = new HashMap<String, BigInteger>();
                fire(plan.steps(), index(recipes), held, extra);
                check(extra.isEmpty() && held.getOrDefault("G", BigInteger.ZERO).compareTo(BigInteger.valueOf(43)) >= 0,
                        "seed refill did not fund target");
                seeds.forEach((k,v) -> check(held.getOrDefault(k, BigInteger.ZERO).compareTo(BigInteger.valueOf(v)) >= 0,
                        "refill failed to preserve seed " + k));
            } finally { work.close(); }
            check(b.reservedBytes() == 0, "seed completion leaked");
        }
    }

    private static void wideCounts() {
        var recipes = index(List.of(recipe("r", Map.of("A", 3L), Map.of("A", 1L, "G", 1L))));
        BigInteger count = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.valueOf(13));
        var b = budget();
        var expansion = new DemandExpansion<>(recipes, Map.of(), Map.of("G", count), b, true);
        try {
            expansion.completePreview("G");
            while (!expansion.step()) {}
            var summary = SequenceSummary.of(expansion.result(), recipes);
            check(summary.delta("G").equals(count), "wide output count truncated");
            check(summary.required("A").equals(count.multiply(BigInteger.TWO).add(BigInteger.ONE)), "wide loan truncated");
            check(b.nodes() < 200, "wide refill expanded primitive runs");
        } finally { expansion.close(); }
        check(b.reservedBytes() == 0, "wide completion leaked");
    }

    private static void noInventedGoal() {
        for (var recipes : List.of(Map.<String, GraphRecipe<String>>of(),
                index(List.of(recipe("turnover", Map.of("G", 1L), Map.of("G", 1L)))))) {
            var b = budget();
            var e = new DemandExpansion<>(recipes, Map.of(), Map.of("G", BigInteger.ONE), b, true);
            try {
                e.completePreview("G");
                while (!e.step()) {}
                check(e.result() == null, "requested output was supplied instead of produced");
            } finally { e.close(); }
            check(b.reservedBytes() == 0, "failed completion leaked");
        }
        var b = budget();
        var ordinary = new DemandExpansion<>(Map.of(), Map.of(), Map.of("G", BigInteger.ONE), b);
        try {
            try { ordinary.completePreview("G"); throw new AssertionError("ordinary search borrowed hypothetical stock"); }
            catch (IllegalStateException expected) { /* Only a proved missing-preview owner may opt in. */ }
        } finally { ordinary.close(); }
    }

    private static void cancellation() {
        for (int stop = 0; stop < 12; stop++) {
            var cancelled = new AtomicBoolean();
            var b = new PlanningBudget(0, 100_000, 16L << 20, cancelled::get, System::nanoTime);
            var rs = index(List.of(recipe("a", Map.of("raw", 1L), Map.of("A", 1L)),
                    recipe("g", Map.of("A", 100L), Map.of("G", 1L))));
            var e = new DemandExpansion<>(rs, Map.of("raw", BigInteger.TEN), Map.of("G", BigInteger.TEN), b, true);
            try {
                for (int i = 0; i < stop; i++) if (e.step()) break;
                e.completePreview("G");
                cancelled.set(true);
                try { e.step(); throw new AssertionError("cancel ignored"); }
                catch (java.util.concurrent.CancellationException expected) { /* Completion obeys the same cancellation. */ }
            } finally { e.close(); }
            check(b.reservedBytes() == 0, "cancelled summary/prefix leaked");
        }
    }

    private static void nativePreview() {
        for (int size : new int[]{8, 97, 320}) {
            var recipes = new ArrayList<GraphRecipe<String>>();
            for (int i = 0; i < size; i++) recipes.add(recipe("r" + i,
                    Map.of("s" + i, 2L, "s" + ((i + 1) % size), 1L, "raw", 1L), Map.of("s" + i, 3L)));
            recipes.add(recipe("finish", Map.of("s0", 1L), Map.of("G", 1L)));
            // Sum(s_i) never grows and starts at zero. The order is impossible,
            // but a borrowed seed has an independently executable refill plan.
            var b = new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime);
            var compiler = new GraphCompiler<>(recipes);
            var work = new GraphPlanningWork<>(compiler, "G", 3, Map.of("raw", 99L), true, true, b);
            try {
                while (!work.step()) {}
                var plan = work.result();
                check(!plan.feasible() && !plan.missingExact().isEmpty(), "native missing preview unavailable: " + size + " " + plan.result());
                checkPrimitive(plan.steps(), index(recipes), Map.of("raw", BigInteger.valueOf(99)), "G", BigInteger.valueOf(3));
                var initial = PlanVerifier.initialExact(plan.steps(), plan.recipes(), "G", 3, plan.seeds());
                initial.forEach((k,v) -> check(v.compareTo(BigInteger.valueOf(k.equals("raw") ? 99 : 0).add(plan.missingExact().getOrDefault(k, BigInteger.ZERO))) <= 0, "refill did not fund original recipe"));
            } finally { work.close(); }
            check(b.reservedBytes() == 0, "native preview leaked");
        }
    }

    private static void checkPrimitive(PlanStep step, Map<String, GraphRecipe<String>> recipes,
                                       Map<String, BigInteger> stock, String target, BigInteger amount) {
        var held = new HashMap<>(stock);
        var missing = new HashMap<String, BigInteger>();
        fire(step, recipes, held, missing);
        check(held.getOrDefault(target, BigInteger.ZERO).compareTo(amount) >= 0, "primitive replay missed output");
        // Replay again with every initial deficit supplied before the first step.
        var funded = new HashMap<>(stock);
        missing.forEach((k,v) -> funded.merge(k, v, BigInteger::add));
        var extra = new HashMap<String, BigInteger>();
        fire(step, recipes, funded, extra);
        check(extra.isEmpty(), "loan was not an executable initial refill");
        check(!missing.containsKey(target), "primitive replay fabricated requested output");
    }

    private static void fire(PlanStep step, Map<String, GraphRecipe<String>> recipes, Map<String, BigInteger> held, Map<String, BigInteger> missing) {
        if (step instanceof PlanStep.Sequence s) { for (var child : s.children()) fire(child, recipes, held, missing); return; }
        if (step instanceof PlanStep.Repeat r) { check(r.times() < 10000, "unbounded test replay"); for (int i=0; i<r.times(); i++) fire(r.body(), recipes, held, missing); return; }
        var batch = (PlanStep.Batch) step;
        check(batch.runs() < 10000, "unbounded test batch");
        var recipe = recipes.get(batch.recipe());
        for (int i=0; i<batch.runs(); i++) {
            for (var input : recipe.inputs().entrySet()) {
                BigInteger have=held.getOrDefault(input.getKey(), BigInteger.ZERO), need=BigInteger.valueOf(input.getValue());
                if (have.compareTo(need)<0) { missing.merge(input.getKey(), need.subtract(have), BigInteger::add); have=need; }
                held.put(input.getKey(), have.subtract(need));
            }
            recipe.outputs().forEach((k,v) -> held.merge(k, BigInteger.valueOf(v), BigInteger::add));
        }
    }

    private static Map<String, GraphRecipe<String>> index(List<GraphRecipe<String>> recipes) {
        var result = new LinkedHashMap<String, GraphRecipe<String>>();
        recipes.forEach(r -> result.put(r.id(), r));
        return result;
    }
    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 2_000_000, 32L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
