// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Physical prefix and terminal-stock oracles for optional coupled-cycle proposals. */
public final class CountCyclicStockSearchTest {
    private static int checks, witnesses;
    private record Handoff(List<GraphRecipe<String>> recipes, Map<String, Long> stock, long amount) {}
    private static Handoff handoff;

    public static void main(String[] args) throws ReflectiveOperationException {
        coupled();
        asymmetricReturns();
        reverseContinuation();
        actualShortage();
        retentionIsOptional();
        largeCyclePrecheck();
        wideStockFrontier();
        quantityPivotBoundary();
        configurations();
        limits();
        System.out.println("CountCyclicStockSearchTest PASS: checks=" + checks + "; witnesses=" + witnesses);
    }

    private static List<GraphRecipe<String>> network(int chains, boolean tools) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < chains; i++) {
            String a = "a" + i, b = "b" + i, c = "c" + i;
            recipes.add(recipe("left" + i, Map.of(a, 1L), Map.of(b, 1L)));
            recipes.add(recipe("right" + i, Map.of(a, 1L), Map.of(c, 1L)));
            var slots = new ArrayList<>(List.of(new GraphRecipe.Slot<>(b, 1), new GraphRecipe.Slot<>(c, 1),
                    new GraphRecipe.Slot<>("feed" + i, 1)));
            var outputs = new LinkedHashMap<>(Map.of(a, 3L, "waste", 1L));
            if (tools) {
                slots.add(new GraphRecipe.Slot<>("tool", 1, 3, true, true));
                outputs.put("tool", 1L);
            }
            recipes.add(new GraphRecipe<>("return" + i, "return" + i, slots, outputs));
        }
        var inputs = new LinkedHashMap<String, Long>();
        for (int i = 0; i < chains; i++) inputs.put("a" + i, 1L);
        recipes.add(recipe("finish", inputs, Map.of("goal", 1L)));
        return recipes;
    }

    private static void coupled() {
        for (int chains : new int[]{1, 2, 4}) for (boolean tools : new boolean[]{false, true})
            for (long amount : new long[]{32, 1_000_000_000L}) for (int order = 0; order < 4; order++) {
                var recipes = network(chains, tools);
                Collections.shuffle(recipes, new Random(8113 + order));
                var compiler = new GraphCompiler<>(recipes);
                var stock = new LinkedHashMap<String, Long>();
                for (int i = 0; i < chains; i++) { stock.put("a" + i, 2L); stock.put("feed" + i, amount * 3); }
                if (tools) stock.put("tool", 1L);
                // A second order receives only actual residual inventory. No
                // fabricated seed credit or implicit refill between requests.
                for (int request = 0; request < 2; request++) {
                    var plan = solve(compiler, amount, stock, Set.of(), Map.of(), Set.of(), true,
                            16_000_000, 128L << 20);
                    check(plan != null, "Coupled stock proposal failed: chains=" + chains + "; tools=" + tools +
                            "; order=" + order + "; amount=" + amount + "; request=" + request);
                    var terminal = verify(plan, recipes, stock, Set.of());
                    for (int i = 0; i < chains; i++) check(terminal.get("a" + i).compareTo(BigInteger.TWO) >= 0,
                            "A shared startup token was drained");
                    var next = new LinkedHashMap<String, Long>();
                    terminal.forEach((key, value) -> next.put(key, value.longValueExact()));
                    stock = next;
                }
            }
    }

    private static void actualShortage() {
        var recipes = network(2, false);
        var compiler = new GraphCompiler<>(recipes);
        for (Map<String, Long> stock : List.of(Map.of("a0", 2L, "a1", 2L),
                Map.of("feed0", 100L, "feed1", 100L),
                Map.of("a0", 2L, "a1", 2L, "feed0", 100L, "feed1", 10L))) {
            var plan = solve(compiler, 32, stock, Set.of(), Map.of(), Set.of(), true, 2_000_000, 64L << 20);
            check(plan == null, "An unfunded cycle became a positive plan");
        }
        var stock = Map.of("a0", 2L, "a1", 2L);
        var external = Set.of("feed0", "feed1");
        var supplied = solve(compiler, 64, stock, external, Map.of("a0", 2L), Set.of(), true,
                2_000_000, 64L << 20);
        check(supplied != null, "Unlimited feed did not fund the cycles");
        verify(supplied, recipes, stock, external);
        for (String removed : new String[]{"left0", "right0", "return0", "finish"}) {
            var excluded = solve(compiler, 64, stock, external, Map.of(), Set.of(removed), true,
                    2_000_000, 64L << 20);
            check(excluded == null, "An excluded producer entered the proposed word: " + removed);
        }
        // Optional full-stock retention is not an extra caller contract.
        var consumable = new GraphCompiler<>(List.of(recipe("consume", Map.of("ore", 1L), Map.of("goal", 1L))));
        var ordinary = solve(consumable, 5, Map.of("ore", 5L), Set.of(), Map.of(), Set.of(), false,
                2_000_000, 64L << 20);
        check(ordinary != null && ordinary.seeds().isEmpty(), "Acyclic stock was turned into a required seed");
    }

    private static void asymmetricReturns() {
        // Different branch yields let an eager forward scan strand a return
        // input. These are small, independent productive cycles, not player
        // recipes. Check the published physical word, delivery and seeds.
        long[][] fixtures = {{4, 4, 5, 5, 3, 1, 4, 10}, {4, 6, 5, 6, 7, 4, 10, 17},
                {7, 7, 3, 7, 1, 5, 10, 17}, {6, 7, 4, 4, 2, 4, 12, 13},
                {2, 5, 2, 4, 6, 4, 11, 11}, {6, 5, 3, 3, 2, 2, 9, 15},
                {4, 2, 2, 6, 2, 7, 7, 10}, {2, 3, 5, 7, 6, 4, 5, 10}};
        int reversed = 0, interleaved = 0;
        for (long[] f : fixtures) for (long amount : new long[]{10_000, 1_000_000_000})
            for (int order = 0; order < 4; order++) {
                var inputs = new LinkedHashMap<String, Long>();
                inputs.put("b", f[4]); inputs.put("c", f[5]); inputs.put("feed", 1L);
                var outputs = new LinkedHashMap<String, Long>();
                outputs.put("a", f[6]); outputs.put("goal", 1L);
                var recipes = new ArrayList<>(List.of(recipe("left", Map.of("a", f[0]), Map.of("b", f[2])),
                        recipe("right", Map.of("a", f[1]), Map.of("c", f[3])), recipe("return", inputs, outputs)));
                Collections.shuffle(recipes, new Random(813 + order));
                var stock = Map.of("a", f[7], "feed", amount * 2);
                var budget = budget(2_000_000, 64L << 20);
                budget.enableMetrics();
                try (var search = new CountCyclicStockSearch<>(new GraphCompiler<>(recipes), "goal", amount,
                        stock, Set.of(), Map.of(), Set.of(), true, true, budget, System.nanoTime())) {
                    while (!search.step()) {}
                    check(search.result() != null, "Asymmetric return failed: " + Arrays.toString(f) + "/" + order + "/" + amount);
                    verify(search.result(), recipes, stock, Set.of());
                    witnesses++;
                    if (budget.diagnostics().contains("count_cyclic_stock_reverse")) reversed++;
                    if (budget.diagnostics().contains("forward_passes=64")) {
                        interleaved++;
                        if (handoff == null) handoff = new Handoff(List.copyOf(recipes), stock, amount);
                    }
                }
                check(budget.reservedBytes() == 0, "Reverse proposal retained workspace");
            }
        check(reversed > 0, "Asymmetric return fixtures stopped exercising reverse proposals");
        check(interleaved > 0, "No long forward scan interleaved a reverse proposal");
    }

    private static void reverseContinuation() throws ReflectiveOperationException {
        check(handoff != null, "No independently verified return path reached the handoff");
        var recipes = handoff.recipes();
        var stock = handoff.stock();
        // Fault injection at the handoff: refusing the extra workspace must
        // resume the physical forward prefix; cancellation must close both.
        var reverseField = CountCyclicStockSearch.class.getDeclaredField("reverse"); reverseField.setAccessible(true);
        var forwardField = CountCyclicStockSearch.class.getDeclaredField("schedule"); forwardField.setAccessible(true);
        for (boolean cancel : new boolean[]{false, true}) {
            var cancelled = new AtomicBoolean();
            var budget = new PlanningBudget(0, 2_000_000, 64L << 20, cancelled::get, System::nanoTime);
            try (var search = new CountCyclicStockSearch<>(new GraphCompiler<>(recipes), "goal", handoff.amount(), stock,
                    Set.of(), Map.of(), Set.of(), true, true, budget, System.nanoTime())) {
                while (reverseField.get(search) == null) check(!search.step(), "Fixture did not reach the reverse handoff");
                var forward = (CountSchedule<?>) forwardField.get(search);
                check(forward != null && forward.completedPasses() == 64, "Forward continuation was discarded at handoff");
                if (cancel) {
                    cancelled.set(true);
                    try { search.step(); throw new AssertionError("Interleaved cancellation was swallowed"); }
                    catch (CancellationException expected) { checks++; }
                    check(search.result() == null, "Cancelled interleaved search published a plan");
                } else {
                    long denied = budget.availableBytes(); budget.reserve(denied);
                    try {
                        while (reverseField.get(search) != null) check(!search.step(), "Memory refusal ended the whole proposal");
                    } finally { budget.release(denied); }
                    check(forwardField.get(search) == forward, "Reverse memory refusal replaced the forward prefix");
                    while (forward.completedPasses() == 64) check(!search.step(), "Forward continuation was lost after refusal");
                    check(forward.completedPasses() > 64, "Forward scan restarted instead of resuming");
                    while (!search.step()) {}
                    if (search.result() != null) verify(search.result(), recipes, stock, Set.of());
                }
            }
            check(budget.reservedBytes() == 0, "Interleaved cancellation/refusal retained workspace");
        }
    }

    private static void retentionIsOptional() {
        var recipes = List.of(recipe("grow", Map.of("a", 1L, "ore", 1L), Map.of("a", 2L)),
                recipe("finish", Map.of("a", 3L), Map.of("goal", 1L)));
        var compiler = new GraphCompiler<>(recipes);
        var stock = Map.of("a", 10L, "ore", 87L);
        // 87 grows fund 32 products and one surviving startup token. Keeping
        // all ten initial tokens would require 96 ore and is optional only.
        var proposal = solve(compiler, 32, stock, Set.of(), Map.of(), Set.of(), true,
                2_000_000, 64L << 20);
        check(proposal != null, "Failed full-stock retention discarded the funded minimum startup completion");
        check(verify(proposal, recipes, stock, Set.of()).get("a").equals(BigInteger.ONE),
                "Minimum completion retained the wrong startup quantity");
        var budget = budget(16_000_000, 64L << 20);
        try (var ordinary = new CountDemandSearch<>(compiler, "goal", 32, stock, Set.of(), Map.of(), Set.of(),
                true, true, budget, System.nanoTime())) {
            while (!ordinary.step()) {}
            check(ordinary.result() != null, "An optional retention goal contaminated the original count catalog");
            var terminal = verify(ordinary.result(), recipes, stock, Set.of());
            check(terminal.get("a").equals(BigInteger.ONE), "The original seed contract was replaced by full-stock retention");
        }
        check(budget.reservedBytes() == 0, "Optional goal restoration retained workspace");
    }

    private static void configurations() {
        for (boolean used : new boolean[]{false, true}) for (boolean external : new boolean[]{false, true}) {
            var configured = new GraphRecipe<>("configured", "configured",
                    List.of(new GraphRecipe.Slot<>("config", 1, 0, true, false)), Map.of("goal", 1L));
            var budget = budget(2_000_000, 64L << 20);
            try (var model = RecipeCountModel.forShell(List.of(configured), Map.of(), Map.of("config", 5L),
                    external ? Set.of("config") : Set.of(), budget);
                 var topology = new CountScheduleTopology<>(model,
                         new BigInteger[]{used ? BigInteger.ONE : BigInteger.ZERO}, budget, true)) {
                while (!topology.step()) {}
                check((topology.result() != null) == (!used || external), "Finite per-push configuration was treated as ordinary stock");
            }
            check(budget.reservedBytes() == 0, "Topology retained workspace");
        }
    }

    private static void largeCyclePrecheck() {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < 219; i++) recipes.add(recipe("link" + i, Map.of("s" + i, 1L), Map.of("s" + (i + 1), 1L)));
        recipes.add(recipe("grow", Map.of("s219", 1L, "ore", 1L), Map.of("s0", 2L)));
        recipes.add(recipe("finish", Map.of("s0", 1L), Map.of("goal", 1L)));
        for (int order = 0; order < 3; order++) {
            Collections.shuffle(recipes, new Random(9901 + order));
            var compiler = new GraphCompiler<>(recipes);
            // The same compiled catalog first sees a real deficit and then a
            // funded request. A failed optional proposal must not poison reuse.
            for (boolean preserve : new boolean[]{false, true}) for (boolean funded : new boolean[]{false, true}) {
                var stock = Map.of("s0", 1L, "ore", funded ? 64L : 0L);
                var budget = budget(16_000_000, 128L << 20);
                budget.enableMetrics();
                var plan = new GraphPlanner<>(compiler).plan("goal", 64, stock, preserve, true, budget);
                if (funded) {
                    check(plan.feasible(), "A prior shortage poisoned a funded large cycle");
                    verify(plan, recipes, stock, Set.of());
                } else {
                    check(!plan.feasible(), "A damaged large cycle fabricated feed");
                    check(!budget.diagnostics().contains("count_cyclic_stock"),
                            "A proven supply deficit still ran optional cyclic projection");
                }
                check(budget.reservedBytes() == 0, "Large-cycle request retained workspace");
            }
        }
    }

    private static void wideStockFrontier() {
        // Most branches can be supplied from inventory, so their lower count
        // bounds stay zero. They must not crowd a funded active loop out of the
        // projected candidate. One left/right pair grows the first branch;
        // every other branch already has enough stock for delivery and a seed.
        for (int chains : new int[]{258, 400}) for (int order = 0; order < 3; order++) {
            var recipes = new ArrayList<GraphRecipe<String>>();
            var stock = new LinkedHashMap<String, Long>();
            var delivery = new LinkedHashMap<String, Long>();
            for (int i = 0; i < chains; i++) {
                recipes.add(recipe("left" + i, Map.of("a" + i, 1L), Map.of("b" + i, 2L)));
                recipes.add(recipe("right" + i, Map.of("b" + i, 1L), Map.of("a" + i, 2L)));
                stock.put("a" + i, i == 0 ? 2L : 33L);
                stock.put("b" + i, i == 0 ? 0L : 1L);
                delivery.put("a" + i, 1L);
            }
            recipes.add(recipe("finish", delivery, Map.of("goal", 1L)));
            if (order == 1) Collections.reverse(recipes);
            if (order == 2) Collections.shuffle(recipes, new Random(20261024));
            var compiler = new GraphCompiler<>(recipes);
            for (long amount : new long[]{4, 16}) {
                var budget = budget(16_000_000, 128L << 20);
                try (var search = new CountCyclicStockSearch<>(compiler, "goal", amount, stock, Set.of(),
                        Map.of(), Set.of(), true, true, budget, System.nanoTime())) {
                    while (!search.step()) {}
                    var plan = search.result();
                    check(plan != null, "Stock-supported free frontier lost a funded word: " + chains + "/" + order + "/" + amount);
                    var terminal = verify(plan, recipes, stock, Set.of());
                    for (int i = 0; i < chains; i++) check(terminal.getOrDefault("a" + i, BigInteger.ZERO)
                            .add(terminal.getOrDefault("b" + i, BigInteger.ZERO)).signum() > 0,
                            "A stock-supported branch lost every startup token");
                    check(budget.diagnostics().contains("projected=" + (2 * chains - 2)),
                            "Wide control no longer exercises the intended free coordinates");
                    witnesses++;
                }
                check(budget.reservedBytes() == 0, "Wide projection retained workspace");
            }
            // Widening a numerical proposal does not invent the missing first
            // token, revive a removed producer, or override memory admission.
            var emptySeed = new LinkedHashMap<>(stock); emptySeed.put("a0", 0L);
            check(solve(compiler, 4, emptySeed, Set.of(), Map.of(), Set.of(), true,
                    16_000_000, 128L << 20) == null, "Wide projection invented an absent startup seed");
            check(solve(compiler, 4, stock, Set.of(), Map.of(), Set.of("left0"), true,
                    16_000_000, 128L << 20) == null, "Wide projection used an excluded return path");
            check(solve(compiler, 4, stock, Set.of(), Map.of(), Set.of(), true,
                    16_000_000, 4096) == null, "Wide projection bypassed its memory account");
        }
    }

    private static void quantityPivotBoundary() {
        var recipes = new ArrayList<>(List.of(
                recipe("makeA", Map.of("C", 1L), Map.of("A", 5L)),
                recipe("makeB", Map.of("D", 2L), Map.of("B", 5L)),
                recipe("makeC", Map.of("A", 3L), Map.of("C", 5L)),
                recipe("makeD", Map.of("ore", 1L), Map.of("D", 3L)),
                recipe("finish", Map.of("A", 3L, "B", 4L, "C", 3L, "D", 2L), Map.of("goal", 1L))));
        var stock = Map.of("A", 29L, "D", 26L, "ore", 128L);
        for (int order = 0; order < 3; order++) {
            if (order == 1) Collections.reverse(recipes);
            if (order == 2) Collections.shuffle(recipes, new Random(20261024));
            var compiler = new GraphCompiler<>(recipes);
            for (long amount : new long[]{4, 6, 8, 12}) {
                var plan = solve(compiler, amount, stock, Set.of(), Map.of(), Set.of(), true, 2_000_000, 32L << 20);
                check(plan != null, "Intermediate amount lost its funded cyclic route: " + amount + "/" + order);
                var terminal = verify(plan, recipes, stock, Set.of());
                check(terminal.getOrDefault("A", BigInteger.ZERO).add(terminal.getOrDefault("C", BigInteger.ZERO)).signum() > 0,
                        "Changing projection equalities spent the last startup token");
            }
            check(solve(compiler, 6, Map.of("D", 26L, "ore", 128L), Set.of(), Map.of(), Set.of(),
                    true, 2_000_000, 32L << 20) == null, "Repivot invented an absent cyclic seed");
            check(solve(compiler, 6, stock, Set.of(), Map.of(), Set.of("makeA"),
                    true, 2_000_000, 32L << 20) == null, "Repivot restored an excluded producer");
        }
    }

    private static void limits() {
        var compiler = new GraphCompiler<>(network(2, true));
        var stock = Map.of("a0", 2L, "a1", 2L, "feed0", 200L, "feed1", 200L, "tool", 1L);
        for (long cap : new long[]{1, 512, 32768, 131072, 524288})
            solve(compiler, 64, stock, Set.of(), Map.of(), Set.of(), true, cap, 128L << 20);
        for (long memory : new long[]{1, 4096, 32768, 131072, 524288, 600000, 750000, 1048576})
            solve(compiler, 64, stock, Set.of(), Map.of(), Set.of(), true, 2_000_000, memory);
        for (int stop : new int[]{0, 1, 5, 20, 50, 100}) {
            var cancel = new AtomicBoolean();
            var budget = new PlanningBudget(0, 16_000_000, 128L << 20, cancel::get, System::nanoTime);
            try (var search = new CountCyclicStockSearch<>(compiler, "goal", 64, stock, Set.of(), Map.of(), Set.of(),
                    true, true, budget, System.nanoTime())) {
                boolean done = false;
                for (int i = 0; i < stop && !done; i++) done = search.step();
                if (!done) {
                    cancel.set(true);
                    try { search.step(); throw new AssertionError("Cancellation was swallowed"); }
                    catch (CancellationException expected) { checks++; }
                    check(search.result() == null, "Cancelled proposal published a partial word");
                }
            }
            check(budget.reservedBytes() == 0, "Cancellation retained proposal workspace");
        }
    }

    private static GraphPlan<String> solve(GraphCompiler<String> compiler, long amount, Map<String, Long> stock,
                                            Set<String> external, Map<String, Long> seeds, Set<String> excluded,
                                            boolean preserve, long cap, long bytes) {
        var budget = budget(cap, bytes);
        GraphPlan<String> plan = null;
        try (var search = new CountCyclicStockSearch<>(compiler, "goal", amount, stock, external, seeds, excluded,
                preserve, true, budget, System.nanoTime())) {
            while (!search.step()) {}
            plan = search.result();
        } catch (PlanningBudget.Exhausted limit) {
            check(cap < 2_000_000 || bytes < 1_000_000, "Proposal exhausted ordinary budget: " + limit);
        }
        check(budget.reservedBytes() == 0, "Proposal retained workspace");
        if (plan != null) {
            check(plan.feasible() && plan.missingExact().isEmpty(), "Proposal invented missing stock");
            for (var seed : seeds.entrySet()) check(plan.seeds().getOrDefault(seed.getKey(), 0L) >= seed.getValue(),
                    "Caller seed contract was dropped");
            for (String id : plan.recipes().keySet()) check(!excluded.contains(id), "Excluded recipe used");
            witnesses++;
        }
        return plan;
    }

    private record Fold(Map<String, BigInteger> need, Map<String, BigInteger> delta) {}

    /** Exact independent composition, without production summaries or verifiers. */
    private static Fold fold(PlanStep step, Map<String, GraphRecipe<String>> recipes) {
        if (step instanceof PlanStep.Batch batch) {
            var recipe = recipes.get(batch.recipe());
            check(recipe != null, "Word used an unknown recipe");
            var need = new LinkedHashMap<String, BigInteger>();
            var delta = new LinkedHashMap<String, BigInteger>();
            recipe.inputs().forEach((key, value) -> { need.put(key, BigInteger.valueOf(value)); delta.put(key, BigInteger.valueOf(value).negate()); });
            recipe.outputs().forEach((key, value) -> delta.merge(key, BigInteger.valueOf(value), BigInteger::add));
            return repeat(new Fold(need, delta), batch.runs());
        }
        if (step instanceof PlanStep.Repeat loop) return repeat(fold(loop.body(), recipes), loop.times());
        var result = new Fold(new LinkedHashMap<>(), new LinkedHashMap<>());
        for (var child : ((PlanStep.Sequence) step).children()) {
            var next = fold(child, recipes);
            next.need.forEach((key, value) -> result.need.merge(key,
                    value.subtract(result.delta.getOrDefault(key, BigInteger.ZERO)).max(BigInteger.ZERO), BigInteger::max));
            next.delta.forEach((key, value) -> result.delta.merge(key, value, BigInteger::add));
        }
        return result;
    }

    private static Fold repeat(Fold body, long times) {
        if (times == 0) return new Fold(Map.of(), Map.of());
        var need = new LinkedHashMap<>(body.need);
        var delta = new LinkedHashMap<String, BigInteger>();
        body.delta.forEach((key, value) -> {
            if (value.signum() < 0) need.merge(key, value.negate().multiply(BigInteger.valueOf(times - 1)), BigInteger::add);
            delta.put(key, value.multiply(BigInteger.valueOf(times)));
        });
        return new Fold(need, delta);
    }

    private static Map<String, BigInteger> verify(GraphPlan<String> plan, List<GraphRecipe<String>> recipes,
                                                  Map<String, Long> stock, Set<String> external) {
        var original = new LinkedHashMap<String, GraphRecipe<String>>();
        recipes.forEach(recipe -> original.put(recipe.id(), recipe));
        var physical = fold(plan.steps(), original);
        physical.need.forEach((key, value) -> check(external.contains(key) ||
                value.compareTo(plan.initialExact().getOrDefault(key, BigInteger.ZERO)) <= 0, "Unfunded plan prefix: " + key));
        plan.initialExact().forEach((key, value) -> check(external.contains(key) ||
                value.compareTo(BigInteger.valueOf(stock.getOrDefault(key, 0L))) <= 0, "Initial stock invented: " + key));
        var terminal = new LinkedHashMap<String, BigInteger>();
        stock.forEach((key, value) -> terminal.put(key, BigInteger.valueOf(value)));
        physical.delta.forEach((key, value) -> { if (!external.contains(key)) terminal.merge(key, value, BigInteger::add); });
        check(physical.delta.getOrDefault(plan.target(), BigInteger.ZERO).compareTo(BigInteger.valueOf(plan.amount())) >= 0,
                "Forced production was lost");
        terminal.merge(plan.target(), BigInteger.valueOf(plan.amount()).negate(), BigInteger::add);
        plan.seeds().forEach((key, value) -> check(external.contains(key) ||
                terminal.getOrDefault(key, BigInteger.ZERO).compareTo(BigInteger.valueOf(value)) >= 0, "Seed lost after delivery"));
        return terminal;
    }

    private static PlanningBudget budget(long work, long bytes) { return new PlanningBudget(0, work, bytes, () -> false, System::nanoTime); }
    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
