// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Large catalogs must not hide a small startup obstruction or its real refill. */
public final class CountExecutionSparseTest {
    private static int checks;

    public static void main(String[] args) {
        for (int forms : new int[] {2, 96}) for (int order = 0; order < 3; order++) {
            test(forms, order, false);
            test(forms, order, true);
        }
        exactStock();
        externalPool();
        configurationAndReusable();
        returnedFanout();
        cancellationAndMemory();
        cancelDuringTraversal();
        System.out.println("CountExecutionSparseTest PASS: checks=" + checks);
    }

    private static List<GraphRecipe<String>> recipes(int forms, boolean refill, int order) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < forms; i++) recipes.add(recipe("turn" + i,
                Map.of("p" + i, 1L), Map.of("p" + ((i + 1) % forms), 1L)));
        var outputs = new LinkedHashMap<String, Long>();
        if (order == 1) outputs.put("goal", 1L);
        outputs.put("p0", 1L); outputs.put("p1", 1L); outputs.put("goal", 1L);
        recipes.add(recipe("work", Map.of("p0", 1L, "p1", 1L), outputs));
        if (refill) recipes.add(recipe("refill", Map.of("raw", 1L), Map.of("p0", 1L)));
        // These are part of the original model, not artificially removed by
        // the test. Their presence must not suppress necessary startup checks.
        for (int i = 0; i < 300; i++) recipes.add(recipe("other" + i,
                Map.of("a" + i, 1L), Map.of("b" + i, 1L)));
        if (order == 1) Collections.reverse(recipes);
        if (order == 2) Collections.shuffle(recipes, new Random(20261027));
        return recipes;
    }

    private static void test(int forms, int order, boolean refill) {
        var recipes = recipes(forms, refill, order);
        var stock = new LinkedHashMap<String, Long>(); stock.put("p0", 1L);
        if (refill) stock.put("raw", 1L);
        var budget = budget(64L << 20, new AtomicBoolean());
        try (var model = RecipeCountModel.forShell(recipes, Map.of("goal", BigInteger.ONE), stock, Set.of(), budget);
             var execution = new CountExecution<>(model, budget)) {
            // Every token makes one full turn. Net balance permits the work
            // recipe, but only one catalyst exists and work needs two at once.
            var falseCounts = counts(model, Collections.nCopies(0, "unused"));
            for (int i = 0; i < model.recipes.size(); i++) {
                String id = model.recipes.get(i).id();
                if (id.startsWith("turn") || id.equals("work")) falseCounts[i] = ExactRational.ONE;
            }
            check(validRows(model, falseCounts), "Constructed net-balance counterexample no longer balances");
            boolean joint = false;
            for (var proof : execution.proofs()) {
                if (proof.materials().size() == forms && proof.guard().violated(falseCounts, budget)) joint = true;
            }
            check(joint, "Large model lost conserved-pool startup obstruction; forms=" + forms + "; order=" + order);
            if (refill) {
                List<String> word = List.of("refill", "turn0", "work");
                var witness = replay(recipes, stock, word);
                check(witness.getOrDefault("goal", BigInteger.ZERO).equals(BigInteger.ONE), "Independent word lost target");
                var actual = counts(model, word);
                check(validRows(model, actual), "Known executable word lost original quantity rows");
                for (var proof : execution.proofs())
                    check(!proof.guard().violated(actual, budget), "Full-source guard excluded executable replenishment");
            }
            check(budget.threadWork() < 300000, "Sparse startup analysis exceeded bounded useful work");
        }
        check(budget.reservedBytes() == 0, "Large startup analysis leaked reservations");
    }

    private static void exactStock() {
        var recipes = recipes(96, false, 0);
        var stock = PlanningInventory.exactSnapshot(Map.of("p0", BigInteger.ONE.shiftLeft(90)));
        var budget = budget(64L << 20, new AtomicBoolean());
        try (var model = RecipeCountModel.forShell(recipes, Map.of("goal", BigInteger.ONE), stock, Set.of(), budget);
             var execution = new CountExecution<>(model, budget)) {
            var actual = counts(model, List.of("turn0", "work"));
            for (var proof : execution.proofs())
                check(!proof.guard().violated(actual, budget), "Exact stock above long was truncated in startup pool");
        }
        check(budget.reservedBytes() == 0, "Exact-stock analysis leaked reservations");
    }

    private static void externalPool() {
        for (int order = 0; order < 3; order++) {
            var recipes = recipes(96, false, order);
            var stock = Map.of("p0", 1L);
            var external = Set.of("p1");
            var budget = budget(64L << 20, new AtomicBoolean());
            var witness = replay(recipes, stock, List.of("work"), external);
            check(witness.getOrDefault("goal", BigInteger.ZERO).equals(BigInteger.ONE),
                    "External supply failed the independent execution fixture");
            try (var model = RecipeCountModel.forShell(recipes, Map.of("goal", BigInteger.ONE), stock, external, budget);
                 var execution = new CountExecution<>(model, budget)) {
                var actual = counts(model, List.of("work"));
                check(validRows(model, actual), "External execution failed original count rows");
                for (var proof : execution.proofs()) {
                    check(!proof.materials().contains("p1"), "An unlimited material entered a finite startup pool");
                    check(!proof.guard().violated(actual, budget), "Finite pool excluded an externally funded execution");
                }
            }
            check(budget.reservedBytes() == 0, "External pool analysis leaked reservations");
        }
    }

    private static void configurationAndReusable() {
        for (boolean reusable : List.of(false, true)) {
            var recipes = new ArrayList<>(recipes(2, true, 0));
            recipes.removeIf(recipe -> recipe.id().equals("work"));
            var work = new GraphRecipe<>("work", "work", List.of(new GraphRecipe.Slot<>("p0", 1),
                    new GraphRecipe.Slot<>("p1", 1), new GraphRecipe.Slot<>("wrench", 1, 2, true, reusable)),
                    Map.of("p0", 1L, "p1", 1L, "wrench", 1L, "goal", 1L));
            recipes.add(work);
            var stock = Map.of("p0", 1L, "raw", 1L, "wrench", 1L);
            var budget = budget(64L << 20, new AtomicBoolean());
            var witness = replay(recipes, stock, List.of("refill", "turn0", "work"));
            check(witness.getOrDefault("goal", BigInteger.ZERO).equals(BigInteger.ONE),
                    "Tool fixture lost its independently funded execution");
            try (var model = RecipeCountModel.forShell(recipes, Map.of("goal", BigInteger.ONE), stock, Set.of(), budget);
                 var execution = new CountExecution<>(model, budget)) {
                var proofs = execution.proofs();
                if (!reusable) {
                    check(work.batchSensitiveInputs() && proofs.isEmpty(),
                            "Per-push configuration was used as a per-firing absence proof");
                } else {
                    check(!work.batchSensitiveInputs(), "Reusable tool unexpectedly changed batch semantics");
                    var unfunded = counts(model, List.of("turn0", "turn1", "work"));
                    check(validRows(model, unfunded), "Reusable tool counterexample lost net balance");
                    check(proofs.stream().anyMatch(proof -> proof.materials().containsAll(List.of("p0", "p1")) &&
                                    proof.guard().violated(unfunded, budget)),
                            "A reusable tool disabled ordinary necessary startup analysis");
                    var actual = counts(model, List.of("refill", "turn0", "work"));
                    for (var proof : proofs) check(!proof.guard().violated(actual, budget),
                            "Reusable-tool guard rejected an executable original word");
                }
            }
            check(budget.reservedBytes() == 0, "Tool startup analysis leaked reservations");
        }
    }

    private static List<GraphRecipe<String>> returnedRecipes(int size) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        var sharedInputs = new LinkedHashMap<String, Long>();
        var sharedOutputs = new LinkedHashMap<String, Long>();
        for (int i = 0; i < size; i++) {
            String key = "token" + i;
            recipes.add(recipe("returned" + i, Map.of(key, 1L), Map.of(key, 1L, "goal", 1L)));
            sharedInputs.put(key, 1L); sharedOutputs.put(key, 1L);
        }
        sharedOutputs.put("goal", 1L);
        recipes.add(recipe("wide-return", sharedInputs, sharedOutputs));
        return recipes;
    }

    private static void returnedFanout() {
        var recipes = returnedRecipes(1200);
        var budget = budget(64L << 20, new AtomicBoolean());
        try (var model = RecipeCountModel.forShell(recipes, Map.of("goal", BigInteger.ONE), Map.of(), Set.of(), budget)) {
            long before = budget.threadWork();
            try (var execution = new CountExecution<>(model, budget)) {
                var actual = counts(model, List.of("returned0"));
                check(execution.violated(actual) != null, "Sparse returned-key analysis lost an absent read token");
                check(budget.threadWork() - before < 500_000,
                        "Returned-key analysis regressed to keys times the whole catalog");
            }
        }
        check(budget.reservedBytes() == 0, "Returned-key fanout retained workspace");
    }

    private static void cancelDuringTraversal() {
        var recipes = returnedRecipes(1200);
        for (int after : new int[]{1, 32, 512, 2048}) {
            var armed = new AtomicBoolean(); var visits = new AtomicInteger();
            var budget = new PlanningBudget(0, 8_000_000, 64L << 20,
                    () -> armed.get() && visits.incrementAndGet() >= after, System::nanoTime);
            try (var model = RecipeCountModel.forShell(recipes, Map.of("goal", BigInteger.ONE), Map.of(), Set.of(), budget)) {
                long held = budget.reservedBytes(); armed.set(true);
                try (var ignored = new CountExecution<>(model, budget)) {
                    throw new AssertionError("Traversal ignored cancellation at callback " + after);
                } catch (CancellationException expected) {
                    check(visits.get() >= after, "Traversal cancellation was not injected inside analysis");
                    check(budget.reservedBytes() == held, "Interrupted sparse traversal retained partial builders");
                } finally { armed.set(false); }
            }
            check(budget.reservedBytes() == 0, "Interrupted sparse model retained memory");
        }
    }

    private static void cancellationAndMemory() {
        var recipes = recipes(96, true, 0);
        var stop = new AtomicBoolean(); var budget = budget(64L << 20, stop);
        try (var model = RecipeCountModel.forShell(recipes, Map.of("goal", BigInteger.ONE), Map.of("p0", 1L), Set.of(), budget)) {
            long before = budget.reservedBytes(); stop.set(true); boolean cancelled = false;
            try (var ignored = new CountExecution<>(model, budget)) {
                throw new AssertionError("Cancelled startup construction completed");
            } catch (CancellationException expected) { cancelled = true; }
            check(cancelled && budget.reservedBytes() == before, "Cancelled startup construction leaked memory");
        }
        check(budget.reservedBytes() == 0, "Cancelled model leaked memory");
        var constrained = budget(64L << 20, new AtomicBoolean());
        try (var model = RecipeCountModel.forShell(recipes, Map.of("goal", BigInteger.ONE), Map.of("p0", 1L), Set.of(), constrained)) {
            long occupied = (64L << 20) - constrained.reservedBytes() - 32;
            check(constrained.tryReserve(occupied), "Could not reserve pressure fixture");
            try (var execution = new CountExecution<>(model, constrained)) {
                check(execution.proofs().isEmpty(), "Optional low-memory feedback invented proofs");
            }
            constrained.release(occupied);
        }
        check(constrained.reservedBytes() == 0, "Low-memory feedback leaked reservations");
    }

    private static ExactRational[] counts(RecipeCountModel<String> model, List<String> word) {
        var result = new ExactRational[model.recipes.size()]; Arrays.fill(result, ExactRational.ZERO);
        for (String id : word) for (int i = 0; i < result.length; i++) if (model.recipes.get(i).id().equals(id))
            result[i] = result[i].add(ExactRational.ONE);
        return result;
    }

    private static boolean validRows(RecipeCountModel<String> model, ExactRational[] counts) {
        for (var row : model.constraints) {
            ExactRational total = ExactRational.ZERO;
            for (var term : row.terms().entrySet()) total = total.add(counts[term.getKey()].multiply(ExactRational.of(term.getValue())));
            if (total.compareTo(ExactRational.of(row.upper())) > 0) return false;
        }
        return true;
    }

    private static Map<String, BigInteger> replay(List<GraphRecipe<String>> recipes, Map<String, Long> stock, List<String> word) {
        return replay(recipes, stock, word, Set.of());
    }

    private static Map<String, BigInteger> replay(List<GraphRecipe<String>> recipes, Map<String, Long> stock,
                                                  List<String> word, Set<String> external) {
        var byId = new HashMap<String, GraphRecipe<String>>(); recipes.forEach(r -> byId.put(r.id(), r));
        var balance = new HashMap<String, BigInteger>(); stock.forEach((k, v) -> balance.put(k, BigInteger.valueOf(v)));
        for (String id : word) {
            var recipe = byId.get(id);
            recipe.inputs().forEach((k, v) -> {
                if (external.contains(k)) return;
                BigInteger held = balance.getOrDefault(k, BigInteger.ZERO), needed = BigInteger.valueOf(v);
                check(held.compareTo(needed) >= 0, "Independent physical prefix deficit: " + id + "/" + k);
                balance.put(k, held.subtract(needed));
            });
            recipe.outputs().forEach((k, v) -> balance.merge(k, BigInteger.valueOf(v), BigInteger::add));
        }
        return balance;
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        return new GraphRecipe<>(id, id, inputs.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), outputs);
    }

    private static PlanningBudget budget(long bytes, AtomicBoolean stop) {
        return new PlanningBudget(0, 8_000_000, bytes, stop::get, System::nanoTime);
    }

    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
