// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Generated source portfolios with explicit executable positive oracles. */
public final class CountDemandPortfolioTest {
    private record Case(String name, List<GraphRecipe<String>> recipes, Map<String, Long> stock,
                        Map<String, Long> seeds, List<String> word, Set<String> excluded) {
        Case {
            stock = Collections.unmodifiableMap(new TreeMap<>(stock));
            seeds = Collections.unmodifiableMap(new TreeMap<>(seeds));
            excluded = Collections.unmodifiableSet(new TreeSet<>(excluded));
        }
    }
    private static int checks, solved, total;
    private static boolean survey;

    public static void main(String[] args) {
        survey = args.length != 0 && args[0].equals("--survey");
        for (int size : new int[] {4, 32, 224}) for (int order = 0; order < 6; order++) {
            run(mixed(size, order));
            run(split(size, order));
            run(shared(size, order));
            run(coproduct(size, order));
            run(ratios(size, order));
            run(matching(size, order));
            run(bootstrap(size, order));
            run(coupled(size, order));
        }
        for (int order = 0; order < 3; order++) {
            run(mixed(1024, order)); run(split(1024, order));
            run(bootstrap(1024, order)); run(coupled(1024, order));
        }
        if (!survey) mutations();
        if (!survey) lifecycle();
        fullRequests();
        System.out.println("CountDemandPortfolioTest " + (solved == total ? "PASS" : "INCOMPLETE") +
                ": solved=" + solved + "/" + total + "; checks=" + checks);
    }

    private static void run(Case input) {
        // This word is constructed from the family definition, independently
        // of the count proposal, and runs with actual tokens at every step.
        verify(input, input.word.stream().map(id -> (PlanStep) new PlanStep.Batch(id, 1)).toList());
        var compiler = new GraphCompiler<>(input.recipes);
        var budget = new PlanningBudget(0, 16_000_000, 64L << 20, () -> false, System::nanoTime);
        GraphPlan<String> result;
        try (var search = new CountDemandSearch<>(compiler, "G", 8, input.stock, Set.of(), input.seeds,
                input.excluded, true, true, budget, System.nanoTime())) {
            while (!search.step()) {}
            result = search.result();
        }
        check(budget.reservedBytes() == 0, input.name + " retained request workspace");
        total++;
        if (result != null) {
            solved++;
            check(result.feasible() && result.missingExact().isEmpty(), input.name + " invented purchases");
            verify(input, List.of(result.steps()));
        }
        if (survey) System.out.println(input.name + " " + (result == null ? "UNKNOWN" : "FEASIBLE") +
                " work=" + budget.threadWork());
        else check(result != null, input.name + " lost the known executable source portfolio");
    }

    private static Case mixed(int size, int order) {
        var recipes = ring(size);
        // Every useful source can occupy the middle; useful positions also
        // differ between materials. Blocked sources are two hops from raw.
        for (int i = 0; i < size - 1; i += Math.max(1, size / 8)) {
            recipes.add(recipe("decoyA" + i, Map.of("blockedA" + i, 1L), Map.of("k" + (i + 1), 1L)));
            recipes.add(recipe("decoyB" + i, Map.of("blockedB" + i, 1L), Map.of("k" + (i + 1), 1L)));
            recipes.add(recipe("rawA" + i, Map.of("absentA" + i, 1L), Map.of("blockedA" + i, 1L)));
            recipes.add(recipe("rawB" + i, Map.of("absentB" + i, 1L), Map.of("blockedB" + i, 1L)));
        }
        recipes.add(recipe("grow", Map.of("k" + (size - 1), 1L, "fuel", 1L), Map.of("k0", 2L)));
        return make("mixed", size, order, recipes, Map.of("k0", 1L, "fuel", 8L),
                loopWord(size, Collections.nCopies(8, "grow")), Set.of());
    }

    private static Case split(int size, int order) {
        var recipes = ring(size);
        for (int i = 0; i < 3; i++) recipes.add(recipe("grow" + i,
                Map.of("k" + (size - 1), 1L, "fuel" + i, 1L), Map.of("k0", 2L)));
        var grows = new ArrayList<String>();
        for (int i = 0; i < 3; i++) grows.addAll(Collections.nCopies(new int[] {2, 3, 3}[i], "grow" + i));
        return make("split", size, order, recipes, Map.of("k0", 1L, "fuel0", 2L, "fuel1", 3L, "fuel2", 3L),
                loopWord(size, grows), Set.of());
    }

    private static Case shared(int size, int order) {
        var recipes = ring(size);
        recipes.add(recipe("grow", Map.of("k" + (size - 1), 1L, "A", 1L, "B", 1L), Map.of("k0", 2L)));
        recipes.add(recipe("aR", Map.of("R", 1L), Map.of("A", 1L)));
        recipes.add(recipe("aS", Map.of("S", 1L), Map.of("A", 1L)));
        recipes.add(recipe("bR", Map.of("R", 1L), Map.of("B", 1L)));
        var word = new ArrayList<String>();
        word.addAll(Collections.nCopies(8, "aS")); word.addAll(Collections.nCopies(8, "bR"));
        word.addAll(loopWord(size, Collections.nCopies(8, "grow")));
        return make("shared", size, order, recipes, Map.of("k0", 1L, "R", 8L, "S", 8L), word, Set.of());
    }

    private static Case coproduct(int size, int order) {
        var recipes = ring(size);
        recipes.add(recipe("grow", Map.of("k" + (size - 1), 1L, "A", 1L, "B", 1L), Map.of("k0", 2L)));
        recipes.add(recipe("a", Map.of("raw", 1L), Map.of("A", 1L)));
        recipes.add(recipe("b", Map.of("raw", 1L), Map.of("B", 1L)));
        recipes.add(recipe("both", Map.of("raw", 1L), Map.of("A", 1L, "B", 1L)));
        var word = new ArrayList<String>(Collections.nCopies(8, "both"));
        word.addAll(loopWord(size, Collections.nCopies(8, "grow")));
        return make("coproduct", size, order, recipes, Map.of("k0", 1L, "raw", 8L), word, Set.of());
    }

    private static Case ratios(int size, int order) {
        var recipes = ring(size);
        int[] gain = {2, 3, 1}, fuel = {2, 1, 1};
        var word = new ArrayList<String>();
        for (int i = 0; i < gain.length; i++) {
            recipes.add(recipe("grow" + i, Map.of("k" + (size - 1), 1L, "fuel" + i, 2L),
                    Map.of("k0", 1L + gain[i])));
            for (int run = 0; run < fuel[i]; run++) {
                for (int k = 0; k < size - 1; k++) word.add("turn" + k);
                word.add("grow" + i); word.addAll(Collections.nCopies(gain[i], "finish"));
            }
        }
        return make("ratios", size, order, recipes, Map.of("k0", 1L, "fuel0", 4L, "fuel1", 2L, "fuel2", 2L), word, Set.of());
    }

    private static Case matching(int size, int order) {
        var recipes = ring(size);
        var feed = new LinkedHashMap<String, Long>(); feed.put("k" + (size - 1), 1L);
        var stock = new LinkedHashMap<String, Long>(); stock.put("k0", 1L);
        var word = new ArrayList<String>();
        int[][] paths = {{0, 1}, {0, 2}, {0, 2}, {1, 3}};
        int[] assignment = {1, 0, 2, 3};
        for (int i = 0; i < paths.length; i++) {
            feed.put("A" + i, 1L); stock.put("R" + i, 8L);
            for (int raw : paths[i]) recipes.add(recipe("a" + i + "r" + raw, Map.of("R" + raw, 1L), Map.of("A" + i, 1L)));
            word.addAll(Collections.nCopies(8, "a" + i + "r" + assignment[i]));
        }
        recipes.add(recipe("grow", feed, Map.of("k0", 2L)));
        word.addAll(loopWord(size, Collections.nCopies(8, "grow")));
        return make("matching", size, order, recipes, stock, word, Set.of());
    }

    private static Case bootstrap(int size, int order) {
        var recipes = ring(size);
        recipes.add(recipe("grow", Map.of("k" + (size - 1), 1L, "fuel", 1L), Map.of("k0", 2L)));
        recipes.add(recipe("entry", Map.of("starter", 1L), Map.of("k0", 1L)));
        recipes.add(recipe("absent-entry", Map.of("absent", 1L), Map.of("k0", 1L)));
        var word = new ArrayList<String>(); word.add("entry");
        word.addAll(loopWord(size, Collections.nCopies(8, "grow")));
        return make("bootstrap", size, order, recipes, Map.of("starter", 1L, "fuel", 8L), word, Set.of());
    }

    private static Case coupled(int size, int order) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (String side : List.of("a", "b")) for (int k = 0; k < size - 1; k++) {
            recipes.add(recipe(side + k, Map.of(side + k, 1L), Map.of(side + (k + 1), 1L)));
            if (k % 7 == 0) recipes.add(recipe("decoy-" + side + k, Map.of("absent-" + side + k, 1L),
                    Map.of(side + (k + 1), 1L)));
        }
        recipes.add(recipe("growA", Map.of("a" + (size - 1), 1L, "fuel", 1L, "pA", 1L), Map.of("a0", 2L, "pB", 1L)));
        recipes.add(recipe("growB", Map.of("b" + (size - 1), 1L, "pB", 1L), Map.of("b0", 2L, "pA", 1L)));
        recipes.add(recipe("finish", Map.of("a0", 1L, "b0", 1L), Map.of("G", 1L)));
        var word = new ArrayList<String>();
        for (int n = 0; n < 8; n++) {
            for (int k = 0; k < size - 1; k++) word.add("a" + k);
            word.add("growA");
            for (int k = 0; k < size - 1; k++) word.add("b" + k);
            word.add("growB"); word.add("finish");
        }
        Collections.shuffle(recipes, new Random(8317L + order * 173L));
        return new Case("coupled-n" + size + "-order" + order, recipes,
                Map.of("a0", 1L, "b0", 1L, "pA", 1L, "fuel", 8L),
                Map.of("a0", 1L, "b0", 1L, "pA", 1L), word, Set.of());
    }

    private static void mutations() {
        for (int size : new int[] {4, 224}) for (int order = 0; order < 6; order++) {
            var input = split(size, order);
            input.recipes.add(recipe("bypass", Map.of("k1", 1L), Map.of("k2", 1L)));
            var compiler = new GraphCompiler<>(input.recipes);
            // One compiler, independent requests: intact, rerouted, severed,
            // unfunded, unseeded, and restored. No failed state may leak back.
            for (int mutation = 0; mutation < 6; mutation++) {
                var stock = new LinkedHashMap<>(input.stock);
                Set<String> excluded = mutation == 1 ? Set.of("turn1") :
                        mutation == 2 ? Set.of("turn1", "bypass") : Set.of();
                if (mutation == 3) stock.remove("fuel1");
                if (mutation == 4) stock.remove("k0");
                var word = input.word.stream().map(id -> mutationReplacement(id, excluded)).toList();
                var changed = new Case(input.name + "-mutation" + mutation, input.recipes, stock, input.seeds, word, excluded);
                var budget = new PlanningBudget(0, 16_000_000, 64L << 20, () -> false, System::nanoTime);
                GraphPlan<String> plan;
                try (var search = new CountDemandSearch<>(compiler, "G", 8, stock, Set.of(), input.seeds,
                        excluded, true, true, budget, System.nanoTime())) {
                    while (!search.step()) {}
                    plan = search.result();
                }
                check(budget.reservedBytes() == 0, changed.name + " retained workspace");
                boolean feasible = mutation < 2 || mutation == 5;
                check((plan != null) == feasible, changed.name + " wrong result after inventory/path mutation");
                if (plan != null) {
                    verify(changed, word.stream().map(id -> (PlanStep) new PlanStep.Batch(id, 1)).toList());
                    verify(changed, List.of(plan.steps()));
                }
            }
        }
    }

    private static String mutationReplacement(String id, Set<String> excluded) {
        return id.equals("turn1") && excluded.contains("turn1") ? "bypass" : id;
    }

    private static void fullRequests() {
        for (var input : List.of(mixed(224, 1), split(224, 2), bootstrap(224, 3), coupled(224, 4))) {
            var budget = new PlanningBudget(0, 16_000_000, 64L << 20, () -> false, System::nanoTime);
            var work = new GraphPlanningWork<>(new GraphCompiler<>(input.recipes), "G", 8, input.stock, Set.of(),
                    input.seeds, true, true, budget).catalysts(CatalystPolicy.MINIMAL);
            GraphPlan<String> plan;
            try { while (!work.step()) {} plan = work.result(); }
            finally { work.close(); }
            check(budget.reservedBytes() == 0, input.name + " full planner retained workspace");
            if (survey) System.out.println("full-" + input.name + " " + plan.result() + " work=" + budget.threadWork());
            else check(plan.feasible(), input.name + " not reached by the main planning strategy");
            if (plan.feasible()) verify(input, List.of(plan.steps()));
        }
    }

    private static void lifecycle() {
        var input = coupled(224, 2);
        for (int stop : new int[] {1, 5, 25, 100, 500, 2000}) {
            var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            var budget = new PlanningBudget(0, 16_000_000, 64L << 20, cancelled::get, System::nanoTime);
            try (var search = new CountDemandSearch<>(new GraphCompiler<>(input.recipes), "G", 8,
                    input.stock, Set.of(), input.seeds, Set.of(), true, true, budget, System.nanoTime())) {
                boolean done = false;
                for (int i = 0; i < stop && !done; i++) done = search.step();
                if (!done) {
                    cancelled.set(true);
                    try { search.step(); throw new AssertionError("Cancellation lost in an allocation/schedule phase"); }
                    catch (java.util.concurrent.CancellationException expected) { checks++; }
                }
            }
            check(budget.reservedBytes() == 0, "Abandoned allocation, topology or ready queue retained memory");
        }
        for (long memory : new long[] {8192, 1L << 20, 2L << 20, 4L << 20}) {
            var budget = new PlanningBudget(0, 16_000_000, memory, () -> false, System::nanoTime);
            try (var search = new CountDemandSearch<>(new GraphCompiler<>(input.recipes), "G", 8,
                    input.stock, Set.of(), input.seeds, Set.of(), true, true, budget, System.nanoTime())) {
                while (!search.step()) {}
                if (search.result() != null) verify(input, List.of(search.result().steps()));
            }
            check(budget.reservedBytes() == 0, "Optional workspace refusal retained reservations");
        }
    }

    private static Case make(String kind, int size, int order, List<GraphRecipe<String>> recipes,
                             Map<String, Long> stock, List<String> word, Set<String> excluded) {
        Collections.shuffle(recipes, new Random(8317L + order * 173L));
        return new Case(kind + "-n" + size + "-order" + order, recipes, stock, Map.of("k0", 1L), word, excluded);
    }

    private static ArrayList<GraphRecipe<String>> ring(int size) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < size - 1; i++) recipes.add(recipe("turn" + i, Map.of("k" + i, 1L), Map.of("k" + (i + 1), 1L)));
        recipes.add(recipe("finish", Map.of("k0", 1L), Map.of("G", 1L)));
        return recipes;
    }

    private static List<String> loopWord(int size, List<String> grows) {
        var word = new ArrayList<String>();
        for (String grow : grows) {
            for (int i = 0; i < size - 1; i++) word.add("turn" + i);
            word.add(grow); word.add("finish");
        }
        return word;
    }

    private static void verify(Case input, List<PlanStep> program) {
        var held = new LinkedHashMap<String, BigInteger>();
        input.stock.forEach((k, v) -> held.put(k, BigInteger.valueOf(v)));
        var recipes = new HashMap<String, GraphRecipe<String>>();
        input.recipes.forEach(r -> recipes.put(r.id(), r));
        for (var step : program) execute(input, step, recipes, held);
        check(held.getOrDefault("G", BigInteger.ZERO).compareTo(BigInteger.valueOf(8)) >= 0, input.name + " target absent");
        for (var seed : input.seeds.entrySet()) check(held.getOrDefault(seed.getKey(), BigInteger.ZERO)
                .compareTo(BigInteger.valueOf(seed.getValue())) >= 0, input.name + " consumed preserved seed");
    }

    private static void execute(Case input, PlanStep step, Map<String, GraphRecipe<String>> recipes,
                                Map<String, BigInteger> held) {
        if (step instanceof PlanStep.Sequence sequence) {
            for (var child : sequence.children()) execute(input, child, recipes, held);
        } else if (step instanceof PlanStep.Repeat repeat) {
            check(repeat.times() <= 100_000, input.name + " unexpectedly huge test word");
            for (long i = 0; i < repeat.times(); i++) execute(input, repeat.body(), recipes, held);
        } else if (step instanceof PlanStep.Batch batch) {
            check(!input.excluded.contains(batch.recipe()), input.name + " executed excluded recipe");
            var recipe = recipes.get(batch.recipe());
            var runs = BigInteger.valueOf(batch.runs());
            for (var port : recipe.inputs().entrySet()) {
                BigInteger left = held.getOrDefault(port.getKey(), BigInteger.ZERO).subtract(BigInteger.valueOf(port.getValue()).multiply(runs));
                check(left.signum() >= 0, input.name + " unfunded execution: " + port.getKey());
                held.put(port.getKey(), left);
            }
            recipe.outputs().forEach((k, v) -> held.merge(k, BigInteger.valueOf(v).multiply(runs), BigInteger::add));
        }
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, new TreeMap<>(in).entrySet().stream()
                .map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), new TreeMap<>(out));
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
