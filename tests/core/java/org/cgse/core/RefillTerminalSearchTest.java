// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** The terminal proposal must restore a physical word before changing a bill. */
public final class RefillTerminalSearchTest {
    private static final int LENGTH = 550;
    private static final String ENTRY = "x" + (LENGTH - 1);
    private static int checks;

    public static void main(String[] args) {
        var recipes = recipes();
        for (boolean reverse : List.of(false, true)) {
            var ordered = new ArrayList<>(recipes);
            if (reverse) Collections.reverse(ordered);
            var compiler = new GraphCompiler<>(ordered);
            for (int state = 0; state < 3; state++) {
                var stock = state == 1 ? Map.<String, Long>of() : Map.of("seed", 1L);
                var result = solve(compiler, original(recipes), stock, Set.of(), Set.of(), true, 32_000_000, 64L << 20);
                if (state == 1) check(result == null, "An unavailable read-arc seed was invented");
                else {
                    check(result != null && result.missingExact().equals(Map.of("raw", BigInteger.valueOf(64))),
                            "Large cyclic catalog did not reach its terminal purchase: " + (result == null ? null : result.missingExact()));
                    physical(result, stock, Set.of());
                    check(result.seeds().equals(Map.of("seed", 1L)), "Preserved seed obligation changed");
                }
            }
            check(solve(compiler, original(recipes), Map.of("seed", 1L), Set.of(), Set.of("source"), true,
                    32_000_000, 64L << 20) == null, "Excluded terminal route was used");
            var external = solve(compiler, original(recipes), Map.of("seed", 1L), Set.of("raw"), Set.of(), true,
                    32_000_000, 64L << 20);
            check(external != null && external.feasible() && external.missingExact().isEmpty(), "External supply was charged as missing");
            physical(external, Map.of("seed", 1L), Set.of("raw"));
            var forced = solve(compiler, original(recipes), Map.of("seed", 1L, "g", 64L), Set.of(), Set.of(), true,
                    32_000_000, 64L << 20);
            check(forced != null && forced.patternTimesExact().getOrDefault("r0", BigInteger.ZERO).equals(BigInteger.valueOf(64)),
                    "Existing target stock bypassed forced production");
            physical(forced, Map.of("seed", 1L, "g", 64L), Set.of());
        }
        var compiler = new GraphCompiler<>(recipes);
        for (long work : new long[] {64, 1024, 32768})
            check(solve(compiler, original(recipes), Map.of("seed", 1L), Set.of(), Set.of(), true, work, 64L << 20) == null,
                    "Short allowance fabricated a terminal plan");
        check(solve(compiler, original(recipes), Map.of("seed", 1L), Set.of(), Set.of(), true, 32_000_000, 16) == null,
                "Memory refusal fabricated a terminal plan");
        lifecycle(compiler, recipes);
        requestedSeeds();
        requestClaim();
        constructionBudget();
        System.out.println("RefillTerminalSearchTest PASS: checks=" + checks);
    }

    private static GraphPlan<String> solve(GraphCompiler<String> compiler, GraphPlan<String> original,
            Map<String, Long> stock, Set<String> external, Set<String> excluded, boolean force, long work, long bytes) {
        var budget = new PlanningBudget(0, work, bytes, () -> false, System::nanoTime);
        GraphPlan<String> result;
        try (var search = new RefillTerminalSearch<>(compiler, original, stock, external, Set.of("raw"), excluded, force, budget)) {
            while (!search.step()) {}
            result = search.result();
        }
        check(original.missingExact().equals(Map.of(ENTRY, BigInteger.valueOf(64))), "Terminal attempt mutated the retained bill");
        check(budget.reservedBytes() == 0, "Terminal proposal retained a reservation");
        return result;
    }

    private static void lifecycle(GraphCompiler<String> compiler, List<GraphRecipe<String>> recipes) {
        for (int stop : new int[] {0, 8, 64}) {
            var flag = new AtomicBoolean();
            var budget = new PlanningBudget(0, 32_000_000, 64L << 20, flag::get, System::nanoTime);
            var old = original(recipes);
            try (var search = new RefillTerminalSearch<>(compiler, old, Map.of("seed", 1L), Set.of(), Set.of("raw"), Set.of(), true, budget)) {
                for (int i = 0; i < stop; i++) check(!search.step(), "Cancellation control finished early");
                flag.set(true);
                try { search.step(); throw new AssertionError("Terminal proposal ignored cancellation"); }
                catch (CancellationException expected) { checks++; }
                check(search.result() == null, "Interrupted terminal proposal escaped validation");
                check(old.missingExact().equals(Map.of(ENTRY, BigInteger.valueOf(64))), "Interrupted terminal proposal discarded the old bill");
            }
            check(budget.reservedBytes() == 0, "Interrupted terminal proposal retained memory");
        }
    }

    private static void requestedSeeds() {
        var recipes = recipes();
        var oldSource = rule("old-source", Map.of("old-seed", 17L, "waste", 1L), Map.of("old-seed", 17L, ENTRY, 1L));
        recipes.add(oldSource);
        var compiler = new GraphCompiler<>(recipes);
        var suffix = original(recipes);
        var used = new LinkedHashMap<>(suffix.recipes()); used.put(oldSource.id(), oldSource);
        // This is a valid former word: its read arc really needed seventeen
        // startup tokens and returned them. An entirely different source need
        // not inherit that automatically inferred preservation obligation.
        var oldSeeds = Map.of("old-seed", 17L, "seed", 1L);
        var oldMissing = Map.of("old-seed", BigInteger.valueOf(17), "waste", BigInteger.valueOf(64));
        var old = new GraphPlan<>("g", 64, true,
                new PlanStep.Sequence(List.of(new PlanStep.Repeat(new PlanStep.Batch("old-source", 1), 64), suffix.steps())), used,
                Map.of("old-seed", BigInteger.valueOf(17), "waste", BigInteger.valueOf(64), "seed", BigInteger.ONE),
                oldSeeds, oldMissing, GraphPlan.Result.MISSING_INPUT, 0, 0);
        physical(old, Map.of("seed", 1L), Set.of());
        for (int state = 0; state < 4; state++) {
            var stock = state == 3 ? Map.of("seed", 1L, "old-seed", 17L) : Map.of("seed", 1L);
            var budget = new PlanningBudget(0, 32_000_000, 64L << 20, () -> false, System::nanoTime);
            GraphPlan<String> result;
            try (var search = new RefillTerminalSearch<>(compiler, old, stock, Set.of(), Set.of("raw", "waste"), Set.of(), true, budget)) {
                if (state != 0) search.requestedSeeds(state == 1 ? Map.of("seed", 1L) : oldSeeds);
                while (!search.step()) {}
                result = search.result();
            }
            check(budget.reservedBytes() == 0, "Seed-specific terminal proposal retained memory");
            check(old.missingExact().equals(oldMissing) && old.seeds().equals(oldSeeds), "Replacing the word mutated its incumbent seed ledger");
            if (state == 0 || state == 2) check(result == null,
                    "Default or explicitly required seed was silently dropped");
            else {
                check(result != null, "Whole-word replacement kept an obsolete automatic reserve; state=" + state);
                if (state == 1) check(result.missingExact().equals(Map.of("raw", BigInteger.valueOf(64))),
                        "Unfunded former source survived replacement: " + result.missingExact());
                else check(result.missingExact().keySet().stream().allMatch(Set.of("raw", "waste")::contains) &&
                        result.missingExact().values().stream().reduce(BigInteger.ZERO, BigInteger::add).equals(BigInteger.valueOf(64)),
                        "Funded routes did not produce a terminal-only bill: " + result.missingExact());
                physical(result, stock, Set.of());
                if (state == 1) check(!result.seeds().containsKey("old-seed") && !result.initialExact().containsKey("old-seed"),
                        "New source inherited the former word's inferred seed");
                else check(result.seeds().getOrDefault("old-seed", 0L) >= 17,
                        "Available explicitly requested seed was not preserved");
            }
        }
    }

    private static void requestClaim() {
        var first = new PlanningBudget(0, 32_000_000, 64L << 20, () -> false, System::nanoTime);
        check(first.claimTerminalProjection(), "Fresh request could not admit its terminal proposal");
        for (int view = 0; view < 8; view++) check(!first.claimTerminalProjection(), "Another view claimed the same request's terminal quota");
        var second = new PlanningBudget(0, 32_000_000, 64L << 20, () -> false, System::nanoTime);
        check(second.claimTerminalProjection(), "A preceding request suppressed the next request's terminal proposal");
        check(!first.claimTerminalProjection() && !second.claimTerminalProjection(), "Opening another request reset an existing claim");
    }

    private static void constructionBudget() {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < 10000; i++) recipes.add(rule("unused" + i, Map.of("raw", 1L), Map.of("unused" + i, 1L)));
        var finish = rule("finish", Map.of("middle", 1L), Map.of("g", 1L)); recipes.add(finish);
        var compiler = new GraphCompiler<>(recipes);
        var original = new GraphPlan<>("g", 1, false, new PlanStep.Batch("finish", 1), Map.of("finish", finish),
                Map.of("middle", BigInteger.ONE), Map.of(), Map.of("middle", BigInteger.ONE), GraphPlan.Result.MISSING_INPUT, 0, 0);
        var budget = new PlanningBudget(0, 131072, 32L << 20, () -> false, System::nanoTime);
        long allowance = budget.remainingWork() / 4;
        try (var search = new RefillTerminalSearch<>(compiler, original, Map.of(), Set.of(), Set.of("raw"), Set.of(), true, budget)) {
            while (!search.step()) {}
            check(search.result() == null, "Partial projected catalog escaped as a terminal plan");
            check(budget.nodes() <= allowance && budget.nodes() > allowance / 2,
                    "Catalog construction exceeded the optional work share: " + budget.nodes() + "/" + allowance);
            check(original.missingExact().equals(Map.of("middle", BigInteger.ONE)), "Abandoned catalog replaced the old bill");
        }
        check(budget.reservedBytes() == 0, "Abandoned catalog retained memory");

        // The default constructor retains its complete-catalog behavior. The
        // bounded entry point publishes the same view when its share suffices.
        for (boolean bounded : List.of(false, true)) {
            var full = new PlanningBudget(0, 2_000_000, 32L << 20, () -> false, System::nanoTime);
            try (var view = bounded ? GraphSupplyView.bounded(compiler, Set.of("raw"), full, 100000) :
                    new GraphSupplyView<>(compiler, Set.of("raw"), full)) {
                check(view != null && view.compiler().catalog().size() == recipes.size() && view.originals().size() == recipes.size(),
                        "Complete projection changed existing catalog behavior");
            }
            check(full.reservedBytes() == 0, "Completed projection retained memory");
        }
        for (long local : new long[] {0, 1, 64}) {
            var small = new PlanningBudget(0, 2_000_000, 32L << 20, () -> false, System::nanoTime);
            try (var view = GraphSupplyView.bounded(compiler, Set.of("raw"), small, local)) {
                check(view == null && small.nodes() <= local, "Tiny local share published or overran a partial view");
            }
            check(small.reservedBytes() == 0, "Tiny local refusal retained memory");
        }
        var checksBeforeCancel = new AtomicInteger();
        var cancelled = new PlanningBudget(0, 2_000_000, 32L << 20, () -> checksBeforeCancel.incrementAndGet() > 15000, System::nanoTime);
        try (var ignored = GraphSupplyView.bounded(compiler, Set.of("raw"), cancelled, 100000)) {
            throw new AssertionError("Catalog construction swallowed cancellation");
        } catch (CancellationException expected) { checks++; }
        check(cancelled.reservedBytes() == 0, "Constructor cancellation leaked its partial catalog");
        var hard = new PlanningBudget(0, 8192, 32L << 20, () -> false, System::nanoTime);
        try (var ignored = GraphSupplyView.bounded(compiler, Set.of("raw"), hard, 100000)) {
            throw new AssertionError("Local construction bypassed the request's hard budget");
        } catch (PlanningBudget.Exhausted expected) {
            check(expected.limit() == PlanningBudget.Limit.SEARCH_LIMIT && hard.nodes() <= 8193,
                    "Construction bypassed the original hard-budget checkpoint");
        }
        check(hard.reservedBytes() == 0, "Hard-budget constructor refusal leaked memory");
    }

    private static List<GraphRecipe<String>> recipes() {
        var result = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < LENGTH; i++) result.add(rule("r" + i, Map.of("x" + i, 1L), Map.of(i == 0 ? "g" : "x" + (i - 1), 1L)));
        result.add(rule("source", Map.of("raw", 1L, "seed", 1L), Map.of(ENTRY, 1L, "seed", 1L)));
        result.add(rule("cycle-out", Map.of(ENTRY, 1L), Map.of("side", 1L)));
        result.add(rule("cycle-back", Map.of("side", 1L), Map.of(ENTRY, 1L)));
        return result;
    }

    private static GraphRecipe<String> rule(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        var slots = new ArrayList<GraphRecipe.Slot<String>>();
        inputs.forEach((k, v) -> slots.add(new GraphRecipe.Slot<>(k, v)));
        return new GraphRecipe<>(id, id, slots, outputs);
    }

    private static GraphPlan<String> original(List<GraphRecipe<String>> recipes) {
        var word = new ArrayList<PlanStep>(); var used = new LinkedHashMap<String, GraphRecipe<String>>();
        for (int i = LENGTH - 1; i >= 0; i--) { word.add(new PlanStep.Batch("r" + i, 64)); used.put("r" + i, recipes.get(i)); }
        return new GraphPlan<>("g", 64, true, new PlanStep.Sequence(word), used,
                Map.of(ENTRY, BigInteger.valueOf(64), "seed", BigInteger.ONE), Map.of("seed", 1L),
                Map.of(ENTRY, BigInteger.valueOf(64)), GraphPlan.Result.MISSING_INPUT, 0, 0);
    }

    private static void physical(GraphPlan<String> plan, Map<String, Long> stock, Set<String> external) {
        var inventory = new HashMap<String, BigInteger>(); stock.forEach((k, v) -> inventory.put(k, BigInteger.valueOf(v)));
        plan.missingExact().forEach((k, v) -> inventory.merge(k, v, BigInteger::add));
        execute(plan.steps(), plan.recipes(), inventory, external);
        check(inventory.getOrDefault("g", BigInteger.ZERO).compareTo(BigInteger.valueOf(64)) >= 0, "Physical target was not produced");
        check(inventory.getOrDefault("seed", BigInteger.ZERO).compareTo(BigInteger.ONE) >= 0, "Physical word consumed a preserved seed");
        plan.seeds().forEach((k, v) -> check(inventory.getOrDefault(k, BigInteger.ZERO).compareTo(BigInteger.valueOf(v)) >= 0,
                "Physical word violated its declared seed obligation: " + k));
        for (var alternative : plan.alternatives()) physical(alternative, stock, external);
    }

    private static void execute(PlanStep step, Map<String, GraphRecipe<String>> recipes, Map<String, BigInteger> stock, Set<String> external) {
        if (step instanceof PlanStep.Sequence sequence) for (var child : sequence.children()) execute(child, recipes, stock, external);
        else if (step instanceof PlanStep.Repeat repeat) for (long n = repeat.times(); n > 0; n--) execute(repeat.body(), recipes, stock, external);
        else {
            var batch = (PlanStep.Batch) step; var recipe = recipes.get(batch.recipe());
            for (long n = batch.runs(); n > 0; n--) {
                recipe.inputs().forEach((k, v) -> {
                    if (external.contains(k)) return;
                    var left = stock.getOrDefault(k, BigInteger.ZERO).subtract(BigInteger.valueOf(v));
                    if (left.signum() < 0) throw new AssertionError("Physical word borrowed " + k);
                    stock.put(k, left);
                });
                recipe.outputs().forEach((k, v) -> { if (!external.contains(k)) stock.merge(k, BigInteger.valueOf(v), BigInteger::add); });
            }
        }
    }

    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
