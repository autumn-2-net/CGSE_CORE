// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Net consumption must not hide the loss of the last cycle startup token. */
public final class SeedStartupGuardTest {
    private static int checks;

    public static void main(String[] args) {
        for (int order = 0; order < 12; order++) {
            depleted(order);
            alternativeForm(order);
            replenishFromTerminal(order);
            consecutiveOrders(order);
            lossyConversions(order);
            publicStockCuts(order);
            returnedState(order);
        }
        thresholdAndConsumers();
        optimizer();
        publishingPaths();
        pruning();
        limits();
        certificateSlices();
        targetDelivery();
        System.out.println("SeedStartupGuardTest PASS: " + checks + " checks");
    }

    private static void targetDelivery() {
        var grow = recipe("grow", Map.of("A", 1L, "ore", 1L), Map.of("A", 2L));
        var recipes = Map.of("grow", grow);
        // Positive word production can still lose the final seed when the
        // requested material is itself the startup token. Stock-only delivery
        // has no required word keys at all, so check that boundary separately.
        for (boolean stockOnly : List.of(false, true)) for (long spare : new long[]{0, 1}) {
            PlanStep word = stockOnly ? new PlanStep.Sequence(List.of()) : new PlanStep.Batch("grow", 1);
            var stock = Map.of("A", (stockOnly ? 2 : 1) + spare, "ore", 1L);
            var summary = SequenceSummary.of(word, recipes);
            var actual = new LinkedHashMap<String, BigInteger>();
            stock.forEach((key, value) -> actual.put(key, BigInteger.valueOf(value)));
            execute(word, recipes, actual); actual.merge("A", BigInteger.TWO.negate(), BigInteger::add);
            check(actual.get("A").equals(BigInteger.valueOf(spare)), "target delivery fixture did not spend its seed");
            var budget = budget();
            try (var guard = new SeedStartupGuard<>(recipes.values(), summary, stock, Map.of(), Set.of(), "A", 2, budget)) {
                while (!guard.step()) {}
                check(guard.lost().equals(spare == 0 ? Map.of("A", 1L) : Map.of()),
                        "positive production or an empty word hid target seed delivery");
            }
            check(budget.reservedBytes() == 0, "target delivery guard retained memory");
        }
    }

    private static Map<String, GraphRecipe<String>> simple(int order) {
        return index(List.of(recipe("grow", Map.of("A", 1L, "ore", 1L), Map.of("A", 2L)),
                recipe("finish", Map.of("A", 3L), Map.of("T", 1L))), order);
    }

    private static PlanStep simpleWord() { return sequence("grow", "grow", "finish"); }

    private static void depleted(int order) {
        var recipes = simple(order);
        var stock = Map.of("A", 1L, "ore", 30L);
        check(candidate(simpleWord(), recipes, stock, true, false) == null, "net-consuming startup was accepted");
        var ordinary = candidate(simpleWord(), recipes, stock, false, false);
        check(ordinary != null && ordinary.seeds().isEmpty(), "ordinary consumption changed");
        check(finish(ordinary, stock).getOrDefault("A", BigInteger.ZERO).signum() == 0, "control did not consume the seed");
        var preview = candidate(simpleWord(), recipes, stock, true, true);
        check(preview != null && preview.seeds().equals(Map.of("A", 1L)), "preview hid startup loan");
        check(preview.missingExact().equals(Map.of("A", BigInteger.ONE)), "loan deficit incorrectly assembled");
        verify(preview);
        // An untouched token in network inventory also remains a valid seed.
        var surplus = Map.of("A", 2L, "ore", 30L);
        var residual = candidate(simpleWord(), recipes, surplus, true, false);
        check(residual != null && finish(residual, surplus).get("A").equals(BigInteger.ONE), "network remainder ignored");
    }

    private static void alternativeForm(int order) {
        var recipes = index(List.of(recipe("grow", Map.of("A", 1L, "ore", 1L), Map.of("B", 2L)),
                recipe("return", Map.of("B", 1L), Map.of("A", 2L)),
                recipe("finish", Map.of("A", 2L), Map.of("T", 1L))), order);
        var stock = Map.of("A", 1L, "ore", 30L);
        var plan = candidate(sequence("grow", "return", "finish"), recipes, stock, true, false);
        check(plan != null && plan.seeds().isEmpty(), "equivalent surviving form was falsely locked");
        var remainder = finish(plan, stock);
        check(remainder.get("A").signum() == 0 && remainder.get("B").equals(BigInteger.ONE), "alternative fixture incorrect");
        // Independent exact recovery word, not the optimistic closure alone.
        execute(new PlanStep.Batch("return", 1), recipes, remainder);
        check(remainder.get("A").equals(BigInteger.TWO), "surviving form could not restart");
    }

    private static void replenishFromTerminal(int order) {
        var recipes = new LinkedHashMap<>(simple(order));
        recipes.put("fresh", recipe("fresh", Map.of("freshOre", 1L), Map.of("A", 1L)));
        var plan = candidate(simpleWord(), recipes, Map.of("A", 1L, "ore", 30L), true, false);
        check(plan != null && plan.seeds().isEmpty(), "unused independent producer was ignored");
        // Produced material cannot be called terminal just because its recipe
        // is not in this word. This alternative still requires an A token.
        recipes.put("fresh", recipe("fresh", Map.of("C", 1L), Map.of("A", 1L)));
        recipes.put("source", recipe("source", Map.of("A", 1L), Map.of("C", 1L)));
        check(candidate(simpleWord(), recipes, Map.of("A", 1L, "ore", 30L), true, false) == null,
                "unused cyclic producer was treated as unlimited raw material");
    }

    private static void consecutiveOrders(int order) {
        var recipes = simple(order);
        var compiler = new GraphCompiler<>(new ArrayList<>(recipes.values()));
        Map<String, Long> stock = new HashMap<>(Map.of("A", 1L, "ore", 30L));
        for (int repeat = 0; repeat < 3; repeat++) {
            var budget = budget();
            var plan = new GraphPlanner<>(compiler).plan("T", 1, stock, true, true, budget);
            check(plan.feasible(), "public planner lost the existing preserving path");
            var remainder = finish(plan, stock);
            check(remainder.get("A").compareTo(BigInteger.ONE) >= 0, "consecutive order lost startup");
            stock = new HashMap<>();
            for (var entry : remainder.entrySet()) stock.put(entry.getKey(), entry.getValue().longValueExact());
            check(budget.reservedBytes() == 0, "public planner leaked guard workspace");
        }
    }

    private static void thresholdAndConsumers() {
        var recipes = index(List.of(recipe("grow", Map.of("A", 2L, "ore", 1L), Map.of("A", 3L)),
                recipe("left", Map.of("A", 1L), Map.of("L", 1L)),
                recipe("finish", Map.of("A", 3L, "L", 1L), Map.of("T", 1L))), 0);
        var word = sequence("grow", "grow", "left", "finish");
        var stock = Map.of("A", 2L, "ore", 20L);
        check(candidate(word, recipes, stock, true, false) == null, "multiple consumers hid lost startup");
        var preview = candidate(word, recipes, stock, true, true);
        check(preview.seeds().equals(Map.of("A", 2L)), "used whole-word loan instead of one startup threshold");
        verify(preview);
        // A single surviving token cannot enable a recipe requiring two.
        check(candidate(word, recipes, Map.of("A", 3L, "ore", 20L), true, false) == null, "quantity threshold ignored");
    }

    private static void optimizer() {
        var recipes = simple(0);
        var stock = Map.of("A", 2L, "ore", 30L);
        var plan = new GraphPlan<>("T", 1, true, simpleWord(), recipes,
                Map.of("A", BigInteger.TWO, "ore", BigInteger.TWO), Map.of("A", 1L), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        var budget = budget();
        try (var optimization = new SeedOptimization<>(new GraphCompiler<>(new ArrayList<>(recipes.values())), plan,
                stock, Map.of(), Set.of(), Set.of(), true, budget)) {
            while (!optimization.step()) {}
            var result = optimization.result();
            verify(result);
            check(finish(result, stock).get("A").compareTo(BigInteger.ONE) >= 0, "optimizer erased a negative-net seed obligation");
        }
        check(budget.reservedBytes() == 0, "optimizer guard leaked");
    }

    private static void publicStockCuts(int order) {
        var recipes = simple(order);
        var compiler = new GraphCompiler<>(new ArrayList<>(recipes.values()));
        for (boolean preserve : List.of(true, false)) for (long tokens : new long[]{1, 3, 4}) {
            var stock = Map.of("A", tokens, "ore", 30L);
            var budget = budget();
            var plan = new GraphPlanner<>(compiler).plan("T", 1, stock, preserve, true, budget);
            check(plan.feasible(), "stock-cut public request lost a valid completion");
            var finalStock = finish(plan, stock);
            if (preserve) {
                check(finalStock.get("A").compareTo(BigInteger.ONE) >= 0, "stock view omitted the unused growth producer");
                if (tokens == 3) check(plan.patternTimesExact().get("grow").equals(BigInteger.ONE),
                        "stock refinement failed to carry its startup loan into ordinary demand");
            } else {
                check(finalStock.get("A").equals(BigInteger.valueOf(Math.max(0, tokens - 3))), "ordinary stocked consumption changed");
            }
            check(budget.reservedBytes() == 0, "stock view startup catalog retained memory");
        }
        var budget = budget();
        try (var guard = new SeedStartupGuard<>(recipes.values(), Set.of("grow"),
                SequenceSummary.of(new PlanStep.Batch("finish", 1), recipes), Map.of("A", 3L), Map.of(),
                Set.of(), "T", 1, budget)) {
            while (!guard.step()) {}
            check(guard.lost().isEmpty(), "excluded growth producer created a false startup obligation");
        }
        check(budget.reservedBytes() == 0, "excluded catalog guard retained memory");
    }

    private static void lossyConversions(int order) {
        // Different units require rational weights. Lossy and neutral unit
        // conversions may be consumed; only the fourth loop grows material.
        long[][] conversions = {{2, 1, 1, 1}, {2, 3, 2, 1}, {2, 3, 3, 2}, {2, 3, 1, 1}};
        for (int i = 0; i < conversions.length; i++) {
            var c = conversions[i];
            var recipes = index(List.of(recipe("convert", Map.of("A", c[0]), Map.of("B", c[1])),
                    recipe("return", Map.of("B", c[2]), Map.of("A", c[3])),
                    recipe("finish", Map.of("A", 3L), Map.of("T", 1L))), order);
            var plan = candidate(new PlanStep.Batch("finish", 1), recipes, Map.of("A", 3L), true, false);
            check((plan != null) == (i < 3), "nonrenewable conversion certificate changed productive growth");
            if (plan != null) { verify(plan); check(plan.seeds().isEmpty(), "nonrenewable conversion inferred a seed"); }
            // Explicit reserves remain obligations even when no renewable
            // production can be inferred from the surrounding conversions.
            var budget = budget();
            try (var explicit = new AllocationSearch.Candidate<>(new PlanStep.Batch("finish", 1), recipes,
                    "T", 1, Map.of("A", 3L), Map.of("A", 1L), Set.of(), true, true, false,
                    budget, System.nanoTime())) {
                while (!explicit.step()) {}
                check(explicit.plan == null, "conversion certificate erased an explicit seed");
            }
            check(budget.reservedBytes() == 0, "explicit conversion reserve leaked");
            // A neutral internal conversion that yields a coproduct does
            // provide renewable production, even if A/B themselves do not grow.
            if (i == 2) {
                recipes.put("convert", recipe("convert", Map.of("A", c[0]), Map.of("B", c[1], "Q", 1L)));
                check(candidate(new PlanStep.Batch("finish", 1), recipes, Map.of("A", 3L), true, false) == null,
                        "neutral coproduct cycle was mistaken for an unproductive conversion");
            }
            // A lossy alternative must not mask an unused productive action.
            recipes.put("grow", recipe("grow", Map.of("A", 1L, "ore", 1L), Map.of("A", 2L)));
            check(candidate(new PlanStep.Batch("finish", 1), recipes, Map.of("A", 3L), true, false) == null,
                    "loss certificate ignored another productive action");
        }
    }

    private static void returnedState(int order) {
        var recipes = index(List.of(recipe("touch", Map.of("tool:0", 1L, "raw0", 1L),
                        Map.of("tool:0", 1L, "part0", 1L)),
                recipe("advance", Map.of("tool:0", 1L, "raw1", 1L), Map.of("tool:1", 1L, "part1", 1L)),
                recipe("finish", Map.of("part0", 1L, "part1", 1L), Map.of("T", 1L))), order);
        var stock = Map.of("tool:0", 1L, "raw0", 1L, "raw1", 1L);
        var word = sequence("touch", "advance", "finish");
        var budget = budget(); GraphPlan<String> plan;
        try (var candidate = new AllocationSearch.Candidate<>(word, recipes, "T", 1, stock,
                Map.of("tool:1", 1L), Set.of(), true, true, false, budget, System.nanoTime())) {
            while (!candidate.step()) {}
            plan = candidate.plan;
            check(plan != null, "pure tool return created a false renewable producer");
        }
        check(budget.reservedBytes() == 0, "returned state candidate leaked");
        var remainder = finish(plan, stock);
        check(remainder.get("tool:0").signum() == 0 && remainder.get("tool:1").equals(BigInteger.ONE),
                "state transition did not retain the explicitly requested final tool");
        budget = budget();
        try (var candidate = new AllocationSearch.Candidate<>(word, recipes, "T", 1, stock,
                Map.of("tool:0", 1L), Set.of(), true, true, false, budget, System.nanoTime())) {
            while (!candidate.step()) {}
            check(candidate.plan == null, "net producer filtering erased an explicit original-state seed");
        }
        check(budget.reservedBytes() == 0, "rejected exact-state seed leaked");

        // Self-returns cannot manufacture stock, but real neutral conversions
        // and growth still own startup obligations even beside such returns.
        var cycles = index(List.of(recipe("touch", Map.of("A", 1L), Map.of("A", 1L, "Q", 1L)),
                recipe("forward", Map.of("A", 1L), Map.of("B", 1L)),
                recipe("back", Map.of("B", 1L), Map.of("A", 1L)),
                recipe("finish", Map.of("A", 1L), Map.of("T", 1L))), order);
        var direct = new PlanStep.Batch("finish", 1);
        check(candidate(direct, cycles, Map.of("A", 1L), true, false) == null,
                "neutral conversion lost startup protection beside a self-return");
        cycles.remove("forward"); cycles.remove("back");
        cycles.put("grow", recipe("grow", Map.of("A", 1L), Map.of("A", 2L)));
        check(candidate(direct, cycles, Map.of("A", 1L), true, false) == null,
                "positive self-growth lost startup protection");

        // The same distinction applies to sealed reusable read arcs. They
        // remain exact required inputs, while the adjacent A growth stays real.
        var read = new GraphRecipe<String>("read", "read", List.of(
                new GraphRecipe.Slot<>("token", 1, 0, true, true),
                new GraphRecipe.Slot<>("A", 1), new GraphRecipe.Slot<>("ore", 1)),
                Map.of("token", 1L, "A", 2L));
        var reusable = index(List.of(read, recipe("finish", Map.of("A", 3L), Map.of("T", 1L))), order);
        var readWord = sequence("read", "read", "finish");
        check(candidate(readWord, reusable, Map.of("token", 1L, "A", 1L, "ore", 2L), true, false) == null,
                "reusable return masked the depleted growth seed");
        var safe = candidate(readWord, reusable, Map.of("token", 1L, "A", 2L, "ore", 2L), true, false);
        check(safe != null, "safe reusable catalyst control was rejected");
        var end = finish(safe, Map.of("token", 1L, "A", 2L, "ore", 2L));
        check(end.get("token").equals(BigInteger.ONE) && end.get("A").equals(BigInteger.ONE),
                "reusable control lost its read token or startup");
    }

    private static void limits() {
        var recipes = simple(0);
        for (int stop = 0; stop < 35; stop++) {
            var budget = budget();
            try (var guard = new SeedStartupGuard<>(recipes.values(), SequenceSummary.of(simpleWord(), recipes),
                    Map.of("A", 1L, "ore", 30L), Map.of(), Set.of(), "T", 1, budget)) {
                for (int i = 0; i < stop && !guard.step(); i++) {}
                budget.cancel();
                try { guard.step(); throw new AssertionError("guard ignored cancellation"); }
                catch (CancellationException expected) { checks++; }
            }
            check(budget.reservedBytes() == 0, "partial guard retained memory");
        }
    }

    private static void certificateSlices() {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < 512; i++) recipes.add(recipe("unrelated" + i,
                Map.of("raw" + i, 1L), Map.of("output" + i, 1L)));
        var forward = recipe("forward", Map.of("A", 1L), Map.of("B", 1L));
        var back = recipe("back", Map.of("B", 1L), Map.of("A", 1L));
        recipes.add(forward); recipes.add(back);
        var producers = Map.of("A", List.of(back), "B", List.of(forward));
        var visited = Set.of("A", "B");
        var budget = budget();
        try (var certificate = new SeedConversionCertificate<>(producers, Map.of(), visited, "A", budget)) {
            while (true) {
                long before = budget.nodes(); boolean done = certificate.step();
                check(budget.nodes() - before <= 4, "conversion catalog scan monopolized a continuation");
                if (done) break;
            }
            check(certificate.certified(), "wide unrelated catalog blocked neutral conversion");
        }
        check(budget.reservedBytes() == 0, "completed conversion certificate retained memory");
        for (int stop : new int[]{0, 1, 4, 16, 64, 256, 1024}) {
            budget = budget();
            try (var certificate = new SeedConversionCertificate<>(producers, Map.of(), visited, "A", budget)) {
                for (int i = 0; i < stop && !certificate.step(); i++) {}
                budget.cancel();
                try { certificate.step(); throw new AssertionError("conversion certificate ignored cancellation"); }
                catch (CancellationException expected) { checks++; }
            }
            check(budget.reservedBytes() == 0, "cancelled conversion certificate retained memory");
        }
        for (long bytes : new long[]{1, 512, 1024, 16384}) {
            budget = new PlanningBudget(0, 100_000, bytes, () -> false, System::nanoTime);
            try (var certificate = new SeedConversionCertificate<>(producers, Map.of(), visited, "A", budget)) {
                while (!certificate.step()) {}
                if (bytes < 4096) check(!certificate.certified(), "unfunded certificate claimed a proof");
            }
            check(budget.reservedBytes() == 0, "limited conversion certificate retained memory");
        }
    }

    private static void publishingPaths() {
        var recipes = simple(0);
        var compiler = new GraphCompiler<>(new ArrayList<>(recipes.values()));
        // Borrowing stocked material may avoid executing the growth recipe
        // entirely. The available cycle must still be considered.
        var direct = new PlanStep.Batch("finish", 1);
        check(candidate(direct, recipes, Map.of("A", 3L, "ore", 30L), true, false) == null,
                "unused growth recipe hid consumed final seed");
        var budget = budget();
        check(GraphFallback.witnessByCost(compiler, "T", 1, Map.of("A", 3L, "ore", 30L), Set.of(),
                Map.of(), true, true, budget, 50000) == null, "ordinary fallback published depleted cycle");
        check(budget.reservedBytes() == 0, "fallback guard leaked");
        budget = budget();
        var fallback = GraphFallback.plan(compiler, "T", 1, Map.of("A", 3L, "ore", 30L), Set.of(),
                Map.of(), true, true, budget);
        check(!fallback.feasible() && fallback.seeds().getOrDefault("A", 0L) >= 1,
                "ordinary refill republished the seedless word rejected by its quick probe");
        verify(fallback);
        check(budget.reservedBytes() == 0, "ordinary refill startup guard leaked");
        var stale = new GraphPlan<>("T", 1, true, simpleWord(), recipes,
                Map.of("A", BigInteger.ONE, "ore", BigInteger.TWO), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        for (long a : new long[]{1, 2}) {
            budget = budget();
            try (var reuse = new PlanReuseVerification<>(stale, compiler, "T", 1, Map.of("A", a, "ore", 30L),
                    Set.of(), Map.of(), true, true, budget, System.nanoTime())) {
                while (!reuse.step()) {}
                check((reuse.result() != null) == (a == 2), "reused seedless witness ignored current restart inventory");
            }
            check(budget.reservedBytes() == 0, "reuse guard leaked");
        }
        budget = budget();
        var graph = compiler.compile("T", Map.of(), Set.of(), budget);
        try (var assembly = new PlanAssembly<>("T", 1, true, simpleWord(), recipes, Map.of(),
                Map.of("A", 1L, "ore", 30L), Set.of(), graph, budget, System.nanoTime(), CatalystPolicy.MINIMAL)) {
            while (!assembly.step()) {}
            check(!assembly.result().feasible() && assembly.result().seeds().equals(Map.of("A", 1L)),
                    "regional assembly published a seedless negative-net cycle");
            verify(assembly.result());
        }
        check(budget.reservedBytes() == 0, "regional assembly guard leaked");
    }

    private static void pruning() {
        var recipes = index(List.of(recipe("touch", Map.of("A", 3L), Map.of("A", 3L, "Q", 1L)),
                recipe("grow", Map.of("A", 1L, "ore", 1L), Map.of("A", 2L)),
                recipe("finish", Map.of("A", 3L, "Q", 1L, "X", 1L), Map.of("T", 1L))), 0);
        var original = new GraphPlan<>("T", 1, true, sequence("touch", "grow", "finish"), recipes,
                Map.of("A", BigInteger.valueOf(3), "ore", BigInteger.ONE, "X", BigInteger.ONE),
                Map.of(), Map.of("X", BigInteger.ONE), GraphPlan.Result.MISSING_INPUT, 0, 0);
        var budget = budget();
        try (var pruning = new RefillWordPruning<>(original, Map.of("A", 3L, "ore", 30L), Set.of(), true, false, budget, 100000)) {
            while (!pruning.step()) {}
            var result = pruning.result(); verify(result);
            var finalStock = finish(result, Map.of("A", 3L, "ore", 30L, "X", 1L));
            check(finalStock.get("A").compareTo(BigInteger.ONE) >= 0, "pruning deleted the only surviving startup token");
        }
        check(budget.reservedBytes() == 0, "pruning startup guard leaked");
    }

    private static GraphPlan<String> candidate(PlanStep word, Map<String, GraphRecipe<String>> recipes,
            Map<String, Long> stock, boolean preserve, boolean preview) {
        var budget = budget();
        try (var candidate = new AllocationSearch.Candidate<>(word, recipes, "T", 1, stock, Map.of(), Set.of(),
                preserve, true, preview, budget, System.nanoTime())) {
            while (!candidate.step()) {}
            return candidate.plan;
        } finally { check(budget.reservedBytes() == 0, "candidate leaked guard memory"); }
    }

    private static void verify(GraphPlan<String> plan) {
        PlanVerifier.verify(new GraphPlan<>(plan.target(), plan.amount(), plan.preserveSeeds(), plan.steps(), plan.recipes(),
                plan.initialExact(), plan.seeds(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0)); checks++;
    }
    private static Map<String, BigInteger> finish(GraphPlan<String> plan, Map<String, Long> stock) {
        verify(plan);
        var inventory = new LinkedHashMap<String, BigInteger>(); stock.forEach((key, value) -> inventory.put(key, BigInteger.valueOf(value)));
        execute(plan.steps(), plan.recipes(), inventory);
        inventory.merge(plan.target(), BigInteger.valueOf(plan.amount()).negate(), BigInteger::add);
        return inventory;
    }
    private static void execute(PlanStep step, Map<String, GraphRecipe<String>> recipes, Map<String, BigInteger> inventory) {
        if (step instanceof PlanStep.Sequence sequence) { for (var child : sequence.children()) execute(child, recipes, inventory); }
        else if (step instanceof PlanStep.Repeat repeat) { check(repeat.times() < 1000, "test repeat too large"); for (long i = 0; i < repeat.times(); i++) execute(repeat.body(), recipes, inventory); }
        else { var batch = (PlanStep.Batch) step; check(batch.runs() < 1000, "test batch too large"); var recipe = recipes.get(batch.recipe());
            for (long i = 0; i < batch.runs(); i++) { for (var input : recipe.inputs().entrySet()) {
                var left = inventory.getOrDefault(input.getKey(), BigInteger.ZERO).subtract(BigInteger.valueOf(input.getValue()));
                check(left.signum() >= 0, "unfunded physical execution"); inventory.put(input.getKey(), left);
            } recipe.outputs().forEach((key, value) -> inventory.merge(key, BigInteger.valueOf(value), BigInteger::add)); }
        }
    }
    private static PlanStep sequence(String... recipes) { return new PlanStep.Sequence(Arrays.stream(recipes).map(id -> (PlanStep) new PlanStep.Batch(id, 1)).toList()); }
    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) { return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out); }
    private static Map<String, GraphRecipe<String>> index(List<GraphRecipe<String>> recipes, int seed) { var shuffled = new ArrayList<>(recipes); Collections.shuffle(shuffled, new Random(seed)); var result = new LinkedHashMap<String, GraphRecipe<String>>(); shuffled.forEach(recipe -> result.put(recipe.id(), recipe)); return result; }
    private static PlanningBudget budget() { return new PlanningBudget(0, 2_000_000, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String detail) { checks++; if (!value) throw new AssertionError(detail); }
}
