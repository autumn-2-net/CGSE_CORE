// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GraphDomainCompilationTest {
    public static void main(String[] args) {
        alternatives(); coproducts(); readArcs(); lifetimes(); chains(); randomized(); acyclicCoverage(); coproductEscape();
        System.out.println("Recipe domains: alternatives, coproduct DAG, strict self returns, read arcs, lifetime and linear chains passed");
    }

    private static GraphRecipe<String> recipe(String id, String input, String... outputs) {
        var map = new LinkedHashMap<String, Long>();
        for (String key : outputs) map.put(key, 1L);
        return new GraphRecipe<>(id, id, input == null ? List.of() : List.of(new GraphRecipe.Slot<>(input, 1)), map);
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 1_000_000, 128L << 20, () -> false, System::nanoTime); }
    private static GraphCompiler<String> domain(GraphCompiler<String> compiler, String target) {
        var budget = budget();
        try (var work = compiler.beginDomain(target, Set.of(), GraphMode.ACYCLIC, budget)) {
            while (!work.step()) {}
            return work.result();
        } finally { check(budget.reservedBytes() == 0, "Domain leaked its workspace"); }
    }
    private static void alternatives() {
        var a = recipe("a", "B", "A");
        var b = recipe("b", "A", "B");
        var source = recipe("source", "raw", "B");
        var alternative = recipe("alternative", "other", "A");
        var original = new GraphCompiler<>(List.of(a, b, source, alternative));
        var restricted = domain(original, "A");
        check(restricted.mode() == GraphMode.ACYCLIC, "Lost domain metadata");
        check(restricted.catalog().containsAll(List.of(a, source, alternative)), "Acyclic source alternatives discarded");
        check(!restricted.catalog().contains(b), "Cycle return retained");
        check(restricted.verificationCatalog() == original.verificationCatalog(), "Original model ownership lost");
        check(restricted.producers("A").get(0) == a, "Recipe identity or producer ordering changed");
        check(original.catalog().size() == 4 && original.mode() == GraphMode.FULL, "Original catalog mutated");
        var fullBudget = budget();
        try (var full = original.beginDomain("A", Set.of(), GraphMode.FULL, fullBudget)) {
            check(full.step() && full.result() == original, "FULL unnecessarily recompiles");
            check(fullBudget.compilationWork() == 0 && fullBudget.reservedBytes() == 0, "FULL domain added preparation cost");
        }
        for (var parent : List.of(original, restricted)) {
            var derived = new GraphCompiler<>(List.of(a), parent.mode(), parent.verificationCatalog());
            var indexed = new GraphCompiler<>(List.of(a), Map.of("A", List.of(a)), parent.mode(), parent.verificationCatalog());
            for (var child : List.of(derived, indexed)) {
                check(child.mode() == parent.mode(), "Derived compiler changed search scope");
                check(child.verificationCatalog() == original.verificationCatalog(), "Derived compiler lost original preservation model");
                check(child.catalog().equals(List.of(a)), "Derived compiler expanded the search domain");
                check(child.verificationProducerIndexAvailable() && child.hasVerificationProducer("B"),
                        "Derived compiler lost excluded original producer membership");
                check(!child.hasVerificationProducer("raw"), "Raw input acquired an original producer");
            }
        }
        var foreign = new GraphCompiler<>(List.of(a), GraphMode.ACYCLIC, List.copyOf(original.catalog()));
        check(!foreign.verificationProducerIndexAvailable(), "Foreign verification list pretended to have original producer index");
        dag(restricted.catalog());
    }
    private static void coproducts() {
        // Selected-source paths alone look acyclic; the extra physical return
        // creates B -> C -> B and must constrain the shared integer catalog.
        var first = recipe("first", "B", "A", "C");
        var second = recipe("second", "C", "B");
        var raw = recipe("raw", "ore", "C");
        var compiler = domain(new GraphCompiler<>(List.of(first, second, raw)), "A");
        dag(compiler.catalog());
        check(!(compiler.catalog().contains(first) && compiler.catalog().contains(second)), "Hidden coproduct cycle retained");
        var strict = domain(new GraphCompiler<>(List.of(recipe("catalyst", "A", "A", "B"),
                new GraphRecipe<>("growth", "growth", List.of(new GraphRecipe.Slot<>("B", 1)), Map.of("B", 2L)))), "B");
        check(strict.catalog().isEmpty(), "Physical catalyst/growth self cycle retained");
    }
    private static void readArcs() {
        var reusable = new GraphRecipe<>("virtual", "virtual",
                List.of(new GraphRecipe.Slot<>("token", 1, 0, true, true)), Map.of("token", 1L, "A", 1L));
        var domain = domain(new GraphCompiler<>(List.of(reusable)), "A");
        check(domain.catalog().equals(List.of(reusable)), "Virtual self return rejected");
        check(domain.catalog().get(0).reusableInputs().containsKey("token"), "Read arc removed");
        dag(domain.catalog());
    }
    private static void lifetimes() {
        var original = new GraphCompiler<>(List.of(recipe("a", "B", "A")));
        var cancelled = new AtomicBoolean();
        var budget = new PlanningBudget(0, 1000, 1 << 20, cancelled::get, System::nanoTime);
        var work = original.beginDomain("A", Set.of(), GraphMode.ACYCLIC, budget);
        work.step(); check(budget.reservedBytes() > 0, "Workspace unaccounted");
        check(budget.phase() == PlanningBudget.Phase.BUILD, "Domain preparation progress misclassified");
        cancelled.set(true);
        try { work.step(); throw new AssertionError("Cancellation ignored"); }
        catch (CancellationException expected) {}
        check(budget.reservedBytes() == 0, "Cancellation leaked domain memory"); work.close();
        var small = new PlanningBudget(0, 1000, 200, () -> false, System::nanoTime);
        try (var limited = original.beginDomain("A", Set.of(), GraphMode.ACYCLIC, small)) {
            try { while (!limited.step()) {} throw new AssertionError("Memory limit ignored"); }
            catch (PlanningBudget.Exhausted expected) {}
        }
        check(small.reservedBytes() == 0, "Memory exhaustion leaked domain memory");
        var tiny = new PlanningBudget(0, 1000, 2, 0, 1 << 20, () -> false, System::nanoTime);
        try (var limited = original.beginDomain("A", Set.of(), GraphMode.ACYCLIC, tiny)) {
            try { while (!limited.step()) {} throw new AssertionError("Compilation limit ignored"); }
            catch (PlanningBudget.Exhausted expected) {}
        }
        check(tiny.reservedBytes() == 0, "Work exhaustion leaked domain memory");
    }
    private static void chains() {
        int n = 10_000;
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < n; i++) recipes.add(recipe("r" + i, "k" + i, "k" + (i + 1)));
        var budget = budget();
        try (var work = new GraphCompiler<>(recipes).beginDomain("k" + n, Set.of(), GraphMode.ACYCLIC, budget)) {
            while (!work.step()) {}
            check(work.result().catalog().size() == n, "Long acyclic chain truncated");
            check(budget.compilationWork() < 30L * n, "Chain work is not linear in ports");
            System.out.println("Domain chain: recipes=" + n + "; compilation_work=" + budget.compilationWork() + "; peak_bytes=" + budget.peakBytes());
        }
        check(budget.reservedBytes() == 0, "Long chain leaked memory");
    }
    private static void randomized() {
        var random = new Random(77131);
        for (int iteration = 0; iteration < 100; iteration++) {
            var recipes = new ArrayList<GraphRecipe<String>>();
            for (int i = 0; i < 30; i++) {
                var in = new LinkedHashMap<String, GraphRecipe.Slot<String>>();
                var out = new LinkedHashMap<String, Long>();
                for (int j = 0; j < random.nextInt(4); j++) {
                    String key = "k" + random.nextInt(10); in.put(key, new GraphRecipe.Slot<>(key, 1));
                }
                for (int j = 0; j < 1 + random.nextInt(3); j++) out.put("k" + random.nextInt(10), 1L);
                recipes.add(new GraphRecipe<>("r" + i, "r" + i, List.copyOf(in.values()), out));
            }
            dag(domain(new GraphCompiler<>(recipes), "k0").catalog());
        }
    }
    private static void acyclicCoverage() {
        // Every wholly acyclic closure must survive, including diamonds,
        // coproducts, shared inputs and arbitrary producer ordering.
        var random = new Random(9127);
        for (int iteration = 0; iteration < 100; iteration++) {
            var recipes = new ArrayList<GraphRecipe<String>>();
            for (int i = 0; i < 60; i++) {
                int split = 1 + random.nextInt(18);
                var inputs = new LinkedHashMap<String, GraphRecipe.Slot<String>>();
                var outputs = new LinkedHashMap<String, Long>();
                for (int j = 0; j < 1 + random.nextInt(3); j++) {
                    String key = "k" + random.nextInt(split);
                    inputs.put(key, new GraphRecipe.Slot<>(key, 1));
                }
                for (int j = 0; j < 1 + random.nextInt(3); j++)
                    outputs.put("k" + (split + random.nextInt(20 - split)), 1L);
                recipes.add(new GraphRecipe<>("r" + i, "r" + i, List.copyOf(inputs.values()), outputs));
            }
            Collections.shuffle(recipes, random);
            var additional = new LinkedHashSet<String>();
            for (int i = 0; i < 20; i++) additional.add("k" + i);
            var budget = budget();
            try (var work = new GraphCompiler<>(recipes).beginDomain("k19", additional, GraphMode.ACYCLIC, budget)) {
                while (!work.step()) {}
                check(work.result().catalog().size() == recipes.size(), "Acyclic coproduct/diamond alternative was lost");
                dag(work.result().catalog());
            }
        }
    }
    private static void coproductEscape() {
        // A coproduct of the requested recipe is an ancestor output, even while
        // DFS is ordering another input. Its cyclic producer must not rank it
        // before that input and erase the independent raw -> B -> T route.
        for (boolean outputOrder : List.of(false, true)) {
            var source = recipe("source", "raw", "B");
            var target = outputOrder ? recipe("target", "B", "C", "T") : recipe("target", "B", "T", "C");
            var back = recipe("back", "C", "B");
            var all = List.of(source, target, back);
            for (int first = 0; first < 3; first++) for (int second = 0; second < 3; second++) {
                if (first == second) continue;
                var recipes = List.of(all.get(first), all.get(second), all.get(3 - first - second));
                var compiler = new GraphCompiler<>(recipes);
                var restricted = domain(compiler, "T");
                check(restricted.catalog().containsAll(List.of(source, target)), "Coproduct back edge erased the raw-source DAG escape");
                check(!restricted.catalog().contains(back), "Escape retained the cyclic back edge");
                dag(restricted.catalog());
                var budget = budget();
                var work = new GraphPlanningWork<>(compiler, "T", 1, Map.<String, Long>of(), Set.<String>of(),
                        Map.<String, Long>of(), false, true, budget, GraphMode.ACYCLIC);
                try {
                    while (!work.step()) {}
                    var plan = work.result();
                    check(plan.patternTimesExact().equals(Map.of("source", java.math.BigInteger.ONE, "target", java.math.BigInteger.ONE)),
                            "Primary ACYCLIC lost the ordinary coproduct escape word");
                    check(plan.missingExact().equals(Map.of("raw", java.math.BigInteger.ONE)), "Coproduct escape did not retain the exact raw bill");
                    check(plan.result() == GraphPlan.Result.INCOMPLETE_MODEL && plan.conditionalPreview(), "Restricted raw-source witness lost its conditional scope");
                    PlanVerifier.verify(new GraphPlan<>(plan.target(), plan.amount(), plan.preserveSeeds(), plan.steps(), plan.recipes(),
                            plan.initialExact(), plan.seeds(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0));
                } finally { work.close(); }
                check(budget.reservedBytes() == 0, "Coproduct escape primary workspace leaked");
            }
        }
    }
    /** Independent explicit edge expansion plus Kahn verification. */
    private static void dag(List<GraphRecipe<String>> recipes) {
        var edges = new HashMap<String, Set<String>>(); var indegree = new HashMap<String, Integer>();
        for (var recipe : recipes) {
            for (String key : recipe.inputs().keySet()) indegree.putIfAbsent(key, 0);
            for (String key : recipe.executionOutputs().keySet()) indegree.putIfAbsent(key, 0);
            for (String input : recipe.inputs().keySet()) for (String output : recipe.executionOutputs().keySet()) {
                if (edges.computeIfAbsent(input, ignored -> new HashSet<>()).add(output)) indegree.merge(output, 1, Integer::sum);
            }
        }
        var ready = new ArrayDeque<String>();
        indegree.forEach((key, degree) -> { if (degree == 0) ready.add(key); });
        int count = 0;
        while (!ready.isEmpty()) { String key = ready.remove(); count++;
            for (String next : edges.getOrDefault(key, Set.of())) if (indegree.merge(next, -1, Integer::sum) == 0) ready.add(next);
        }
        check(count == indegree.size(), "Retained physical domain has a cycle");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
