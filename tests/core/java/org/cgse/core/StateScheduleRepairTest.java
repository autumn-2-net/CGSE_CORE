// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixed counts and physical prefixes survive failure-guided order changes. */
public final class StateScheduleRepairTest {
    private static int checks, witnesses;

    public static void main(String[] args) {
        var random = new Random(161016);
        for (int sample = 0; sample < 250; sample++) randomOrders(random, sample);
        wide(); limits();
        check(witnesses >= 250, "Missing random fixed-count witnesses");
        System.out.println("StateScheduleRepairTest: random=250; witnesses=" + witnesses + "; assertions=" + checks);
    }

    private static void randomOrders(Random random, int sample) {
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
        var counts = new LinkedHashMap<String, BigInteger>(); var word = new ArrayList<String>();
        for (int r = 0; r < 2 + sample % 5; r++) {
            var inputs = new LinkedHashMap<String, Long>(); var outputs = new LinkedHashMap<String, Long>();
            for (int k = 0; k < 4; k++) {
                long in = random.nextInt(4), out = random.nextInt(4);
                if (in > 0) inputs.put("k" + k, in); if (out > 0) outputs.put("k" + k, out);
            }
            outputs.put("product", 1L);
            String id = "r" + r; long count = 1 + random.nextInt(3);
            recipes.put(id, recipe(id, inputs, outputs)); counts.put(id, BigInteger.valueOf(count));
            for (int i = 0; i < count; i++) word.add(id);
        }
        Collections.shuffle(word, random);
        var stock = new LinkedHashMap<String, Long>(); var inventory = new LinkedHashMap<String, Long>();
        // Independently construct one executable word, retaining only its
        // minimal added initial stock. Other orders may need different prefixes.
        for (String id : word) {
            var recipe = recipes.get(id);
            recipe.inputs().forEach((key, count) -> {
                long lack = Math.max(0, count - inventory.getOrDefault(key, 0L));
                stock.merge(key, lack, Long::sum); inventory.merge(key, lack - count, Long::sum);
            });
            recipe.executionOutputs().forEach((key, count) -> inventory.merge(key, count, Long::sum));
        }
        Set<String> external = sample % 3 == 0 ? Set.of("k2") : Set.of();
        var budget = budget(400_000, 16L << 20);
        try (var work = StateScheduleRepair.create(recipes, counts, stock, external, word.get(word.size() - 1), budget)) {
            check(work != null, "Small fixed-count scout was not admitted");
            int emitted = 0;
            do {
                while (!work.step()) {}
                if (work.witness() == null) break;
                var actual = new LinkedHashMap<String, BigInteger>(); var held = new LinkedHashMap<>(stock);
                var cursor = new PlanCursor(work.witness());
                while (cursor.current() != null) {
                    var batch = cursor.current(); var recipe = recipes.get(batch.recipe());
                    actual.merge(recipe.id(), BigInteger.valueOf(batch.runs()), BigInteger::add);
                    for (long i = 0; i < batch.runs(); i++) {
                        recipe.inputs().forEach((key, need) -> {
                            if (external.contains(key)) return;
                            check(held.getOrDefault(key, 0L) >= need, "Order scout invented a material prefix");
                            held.merge(key, -need, Long::sum);
                        });
                        recipe.executionOutputs().forEach((key, count) -> held.merge(key, count, Long::sum));
                    }
                    cursor.dispatched(batch.runs());
                }
                check(actual.equals(counts), "Order repair changed the proposal counts"); witnesses++; emitted++;
            } while (emitted < 4 && work.reject("r" + random.nextInt(recipes.size())));
            check(emitted > 0, "Lost a known small material witness");
        }
        check(budget.reservedBytes() == 0 && budget.searchWork() < 50_000, "Order feedback renewed work or retained memory");
    }

    private static void wide() {
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
        for (String id : List.of("a", "b")) recipes.put(id, new GraphRecipe<>(id, id,
                List.of(new GraphRecipe.Slot<>("tool", 1, 0, true, true)), Map.of("product", 1L, "tool", 1L)));
        BigInteger n = BigInteger.TEN.pow(24).add(BigInteger.ONE);
        var counts = Map.of("a", n, "b", n); var stock = Map.of("tool", 1L);
        var budget = budget(400_000, 16L << 20);
        try (var work = StateScheduleRepair.create(recipes, counts, stock, Set.of(), "b", budget)) {
            while (!work.step()) {}
            check(work.witness() != null, "Wide-count order was unrolled / truncated");
            var actual = new LinkedHashMap<String, BigInteger>(); count(work.witness(), BigInteger.ONE, actual);
            check(actual.equals(counts), "Wide-count repair changed counts");
            var summary = SequenceSummary.of(work.witness(), recipes);
            check(summary.required().get("tool").equals(BigInteger.ONE) && summary.delta("product").equals(n.multiply(BigInteger.TWO)),
                    "Wide-count prefix or output changed");
        }
        check(budget.reservedBytes() == 0 && budget.searchWork() < 2_000, "Wide order expanded with count magnitude");
    }

    private static void limits() {
        var recipes = Map.of("a", recipe("a", Map.of("raw", 1L), Map.of("a", 1L)),
                "b", recipe("b", Map.of("raw", 1L), Map.of("b", 1L)));
        var counts = Map.of("a", BigInteger.ONE, "b", BigInteger.ONE); var stock = Map.of("raw", 2L);
        for (long bytes : new long[]{1, 1024, 8192}) {
            var budget = budget(400_000, bytes);
            try (var work = StateScheduleRepair.create(recipes, counts, stock, Set.of(), "b", budget)) {
                if (work != null) { while (!work.step()) {} check(work.witness() == null, "Insufficient workspace unexpectedly admitted a schedule"); }
            }
            check(budget.reservedBytes() == 0, "Optional memory denial leaked");
        }
        var budget = budget(1_000_000, 16L << 20);
        try (var work = StateScheduleRepair.create(recipes, counts, stock, Set.of(), "b", budget)) {
            while (!work.step()) {}
            check(work.witness() != null, "Missing allowance boundary witness");
            budget.charge(500_000); // Work spent restoring a rejected candidate.
            check(!work.reject("a"), "Downstream rejection renewed the scout's lifetime allowance");
        }
        check(budget.reservedBytes() == 0, "Expired scout leaked");
        budget = budget(400_000, 16L << 20);
        try (var work = StateScheduleRepair.create(recipes, counts, Map.of(), Set.of(), "a", budget)) {
            while (!work.step()) {}
            check(work.witness() == null, "Unfunded scheduling invented raw material");
        }
        check(budget.reservedBytes() == 0, "Failed order proof leaked");
        var cancel = new AtomicBoolean();
        budget = new PlanningBudget(0, 400_000, 16L << 20, cancel::get, System::nanoTime); boolean cancelled = false;
        try (var work = StateScheduleRepair.create(recipes, counts, stock, Set.of(), "b", budget)) {
            cancel.set(true);
            try { work.step(); } catch (java.util.concurrent.CancellationException expected) { cancelled = true; }
        }
        check(cancelled && budget.reservedBytes() == 0, "Cancelled order scout leaked");
    }

    private static void count(PlanStep step, BigInteger scale, Map<String, BigInteger> result) {
        if (step instanceof PlanStep.Batch batch) result.merge(batch.recipe(), scale.multiply(BigInteger.valueOf(batch.runs())), BigInteger::add);
        else if (step instanceof PlanStep.Repeat repeat) count(repeat.body(), scale.multiply(BigInteger.valueOf(repeat.times())), result);
        else for (var child : ((PlanStep.Sequence) step).children()) count(child, scale, result);
    }
    private static GraphRecipe<String> recipe(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        var slots = new ArrayList<GraphRecipe.Slot<String>>();
        for (var input : inputs.entrySet()) slots.add(new GraphRecipe.Slot<>(input.getKey(), input.getValue(), slots.size()));
        return new GraphRecipe<>(id, id, slots, outputs);
    }
    private static PlanningBudget budget(long work, long bytes) { return new PlanningBudget(0, work, bytes, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
