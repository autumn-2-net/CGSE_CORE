// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** A large target-connected closure must refine its shared, underfunded startup pool. */
public final class CountLargeClosureRefinementTest {
    private static final int LINKS = 300;
    private static final long WORK = 60_000_000, MEMORY = 128L << 20;
    private static int checks, witnesses;

    public static void main(String[] args) {
        var selected = Set.of(args);
        for (int order = 0; order < 3; order++) for (long amount : new long[]{1, 8}) {
            var recipes = recipes(order);
            var stock = Map.of("a", 1L, "raw", 1L);
            var compiler = new GraphCompiler<>(recipes);
            var known = knownWord(amount);
            replay(known, index(recipes), stock, amount, Map.of());
            if (selected.isEmpty() || selected.contains("direct")) direct(compiler, recipes, stock, amount, order);
            if (selected.isEmpty() || selected.contains("cyclic")) cyclic(compiler, recipes, stock, amount, order);
            if (selected.isEmpty() || selected.contains("mainline")) mainline(compiler, recipes, stock, amount, order);
        }
        System.out.println("CountLargeClosureRefinementTest PASS: witnesses=" + witnesses + "; checks=" + checks);
    }

    private static List<GraphRecipe<String>> recipes(int order) {
        var result = new ArrayList<GraphRecipe<String>>();
        result.add(recipe("ab", Map.of("a", 1L), Map.of("b", 1L)));
        result.add(recipe("ba", Map.of("b", 1L), Map.of("a", 1L)));
        var outputs = new LinkedHashMap<String, Long>();
        if (order != 0) outputs.put("stage0", 1L);
        outputs.put("a", 1L); outputs.put("b", 1L); outputs.put("stage0", 1L);
        result.add(recipe("work", Map.of("a", 1L, "b", 1L), outputs));
        result.add(recipe("refill", Map.of("raw", 1L), Map.of("a", 1L)));
        for (int i = 0; i < LINKS; i++) result.add(recipe("link" + i,
                Map.of("stage" + i, 1L), Map.of("stage" + (i + 1), 1L)));
        result.add(recipe("deliver", Map.of("stage" + LINKS, 1L), Map.of("goal", 1L)));
        if (order == 1) Collections.reverse(result);
        if (order == 2) Collections.shuffle(result, new Random(20261027));
        return List.copyOf(result);
    }

    private static PlanStep knownWord(long amount) {
        var steps = new ArrayList<PlanStep>();
        steps.add(new PlanStep.Batch("refill", 1));
        steps.add(new PlanStep.Batch("ab", 1));
        steps.add(new PlanStep.Batch("work", amount));
        for (int i = 0; i < LINKS; i++) steps.add(new PlanStep.Batch("link" + i, amount));
        steps.add(new PlanStep.Batch("deliver", amount));
        return new PlanStep.Sequence(steps);
    }

    private static void direct(GraphCompiler<String> compiler, List<GraphRecipe<String>> recipes,
                                 Map<String, Long> stock, long amount, int order) {
        String id = "large/direct/order=" + order + "/amount=" + amount;
        var budget = budget();
        try (var model = RecipeCountModel.create(compiler, "goal", amount, stock, Map.of(), Set.of(), Set.of(), true, budget)) {
            check(model != null && model.recipes.size() == recipes.size() && model.recipes.size() > 256 && model.keys.size() > 192,
                    id + " target closure pruned the intended large structure");
            for (int i = 0; i < LINKS; i++) check(model.keys.contains("stage" + i), id + " missing target-connected stage " + i);
            var original = List.copyOf(model.constraints);
            var bad = counts(model, amount, false);
            check(valid(original, bad), id + " counterexample no longer satisfies original net quantities");
            try (var execution = new CountExecution<>(model, budget)) {
                var rational = Arrays.stream(bad).map(ExactRational::of).toArray(ExactRational[]::new);
                boolean pooled = false;
                for (var proof : execution.proofs()) {
                    boolean violated = proof.guard().violated(rational, budget);
                    if (proof.materials().size() == 1) check(!violated, id + " fixture was already rejected by a single-material condition");
                    if (new HashSet<>(proof.materials()).equals(Set.of("a", "b")) && violated) pooled = true;
                }
                check(pooled && budget.diagnostics().contains("sparse_feedback_pools"), id + " large shared-pool feedback was not used");
            }
            try (var first = new CountSchedule<>(model, bad, budget, true)) {
                while (!first.step()) {}
                check(first.witness() == null, id + " one conserved token started a simultaneous two-token read");
            }
            GraphPlan<String> plan = null;
            try (var refinement = new CountRefinedProjection<>(model, budget, 16_000_000, 512, true, bad)) {
                while (!refinement.step()) {}
                check(Arrays.equals(refinement.result(), bad), id + " original physical candidate was not retained");
                check(refinement.reject(), id + " failed startup discarded its refinement opportunity");
                for (int attempt = 0; attempt < 32; attempt++) {
                    while (!refinement.step()) {}
                    var proposed = refinement.result();
                    if (proposed == null) break; // No candidate is UNKNOWN, never an infeasibility proof.
                    check(valid(original, proposed), id + " refined counts violate original rows");
                    PlanStep word;
                    try (var next = new CountSchedule<>(model, proposed, budget, true)) {
                        while (!next.step()) {}
                        word = next.witness();
                    }
                    if (word != null) {
                        try (var candidate = new AllocationSearch.Candidate<>(word, index(model.recipes), "goal", amount, stock,
                                Map.of(), Set.of(), true, true, false, budget, System.nanoTime())) {
                            while (!candidate.step()) {}
                            if (candidate.plan != null && candidate.plan.feasible()) { plan = candidate.plan; break; }
                        }
                    }
                    if (!refinement.reject()) break;
                }
                check(refinement.refined() && plan != null, id + " shared-pool refinement lost the constructive witness; " + budget.diagnostics());
                verify(plan, recipes, stock, amount, id);
            }
            check(model.constraints.equals(original), id + " refinement changed the original closure constraints");
        }
        check(budget.reservedBytes() == 0, id + " request workspace leaked");
    }

    private static void cyclic(GraphCompiler<String> compiler, List<GraphRecipe<String>> recipes,
                                 Map<String, Long> stock, long amount, int order) {
        String id = "large/cyclic/order=" + order + "/amount=" + amount;
        var budget = budget();
        try (var search = new CountCyclicStockSearch<>(compiler, "goal", amount, stock, Set.of(), Map.of(), Set.of(),
                true, true, budget, System.nanoTime())) {
            while (!search.step()) {}
            check(search.result() != null, id + " count/startup integration lost the constructive witness; " + budget.diagnostics());
            verify(search.result(), recipes, stock, amount, id);
        }
        check(budget.reservedBytes() == 0, id + " request workspace leaked");
    }

    private static void mainline(GraphCompiler<String> compiler, List<GraphRecipe<String>> recipes,
                                   Map<String, Long> stock, long amount, int order) {
        String id = "large/mainline/order=" + order + "/amount=" + amount;
        var budget = budget();
        var work = new GraphPlanningWork<>(compiler, "goal", amount, stock, true, true, budget).catalysts(CatalystPolicy.MINIMAL);
        try {
            while (!work.step()) {}
            check(work.result().feasible(), id + " mainline lost the independently funded full closure; " + budget.diagnostics());
            verify(work.result(), recipes, stock, amount, id);
        } finally { work.close(); }
        check(budget.reservedBytes() == 0, id + " request workspace leaked");
    }

    private static BigInteger[] counts(RecipeCountModel<String> model, long amount, boolean funded) {
        var result = new BigInteger[model.recipes.size()];
        for (int i = 0; i < result.length; i++) {
            String id = model.recipes.get(i).id();
            if (id.equals("refill")) result[i] = funded ? BigInteger.ONE : BigInteger.ZERO;
            else if (id.equals("ab") || id.equals("ba")) result[i] = BigInteger.ONE;
            else result[i] = BigInteger.valueOf(amount);
        }
        return result;
    }

    private static void verify(GraphPlan<String> plan, List<GraphRecipe<String>> recipes,
                                 Map<String, Long> stock, long amount, String id) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        check(plan.target().equals("goal") && plan.amount() == amount && plan.missingExact().isEmpty(), id + " changed request contract");
        var original = index(recipes);
        plan.recipes().forEach((key, value) -> check(value.equals(original.get(key)), id + " changed original recipe " + key));
        plan.initialExact().forEach((key, value) -> check(value.compareTo(BigInteger.valueOf(stock.getOrDefault(key, 0L))) <= 0,
                id + " invented startup stock " + key));
        check(plan.patternTimesExact().getOrDefault("refill", BigInteger.ZERO).equals(BigInteger.ONE), id + " did not fund exactly one extra startup token");
        replay(plan.steps(), original, stock, amount, plan.seeds());
        witnesses++;
        if (Boolean.getBoolean("cgse.family.trace")) System.out.println(id + " VERIFIED_WITNESS recipes=" + recipes.size());
    }

    /** Exact primitive replay, including read-before-return semantics. All generated words are small. */
    private static void replay(PlanStep word, Map<String, GraphRecipe<String>> recipes, Map<String, Long> stock,
                                 long amount, Map<String, Long> seeds) {
        var inventory = new HashMap<String, BigInteger>(); stock.forEach((key, value) -> inventory.put(key, BigInteger.valueOf(value)));
        execute(word, recipes, inventory);
        check(inventory.getOrDefault("goal", BigInteger.ZERO).compareTo(BigInteger.valueOf(amount)) >= 0, "Primitive replay lost target delivery");
        inventory.merge("goal", BigInteger.valueOf(amount).negate(), BigInteger::add);
        // Either convertible form can retain the pool. Requiring one of
        // each would reject a valid final conversion chosen by the planner.
        check(inventory.getOrDefault("a", BigInteger.ZERO).add(inventory.getOrDefault("b", BigInteger.ZERO))
                .compareTo(BigInteger.TWO) >= 0, "Working token pool was not returned");
        seeds.forEach((key, value) -> check(inventory.getOrDefault(key, BigInteger.ZERO).compareTo(BigInteger.valueOf(value)) >= 0,
                "Primitive replay lost final seed " + key));
    }

    private static void execute(PlanStep step, Map<String, GraphRecipe<String>> recipes, Map<String, BigInteger> inventory) {
        if (step instanceof PlanStep.Batch batch) {
            var recipe = recipes.get(batch.recipe());
            check(recipe != null && !recipe.batchSensitiveInputs() && batch.runs() <= 1024, "Invalid primitive fixture or unexpected word size");
            for (long run = 0; run < batch.runs(); run++) {
                recipe.inputs().forEach((key, value) -> check(inventory.getOrDefault(key, BigInteger.ZERO).compareTo(BigInteger.valueOf(value)) >= 0,
                        "Primitive prefix lacks " + key + " for " + recipe.id()));
                recipe.inputs().forEach((key, value) -> inventory.merge(key, BigInteger.valueOf(value).negate(), BigInteger::add));
                recipe.outputs().forEach((key, value) -> inventory.merge(key, BigInteger.valueOf(value), BigInteger::add));
            }
        } else if (step instanceof PlanStep.Repeat repeated) {
            check(repeated.times() <= 1024, "Unexpected primitive repetition count");
            for (long i = 0; i < repeated.times(); i++) execute(repeated.body(), recipes, inventory);
        } else for (var child : ((PlanStep.Sequence) step).children()) execute(child, recipes, inventory);
    }

    private static boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] counts) {
        for (var count : counts) if (count == null || count.signum() < 0) return false;
        for (var row : rows) {
            BigInteger total = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) total = total.add(term.getValue().multiply(counts[term.getKey()]));
            if (total.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> input, Map<String, Long> output) {
        return new GraphRecipe<>(id, id, input.entrySet().stream().map(entry -> new GraphRecipe.Slot<>(entry.getKey(), entry.getValue())).toList(), output);
    }
    private static Map<String, GraphRecipe<String>> index(List<GraphRecipe<String>> recipes) {
        var result = new LinkedHashMap<String, GraphRecipe<String>>(); recipes.forEach(recipe -> result.put(recipe.id(), recipe)); return result;
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, WORK, MEMORY, () -> false, System::nanoTime); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
