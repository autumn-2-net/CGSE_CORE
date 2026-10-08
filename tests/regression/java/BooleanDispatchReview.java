package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Exact small-domain oracle and label/order invariance of the shared-resource counterexample. */
public final class BooleanDispatchReview {
    record Network(int vertices, List<int[]> edges) {}

    static Network network(int vertices, int seed, boolean planted) {
        Random random = new Random(seed);
        int[] assignment = new int[vertices];
        for (int i = 0; i < vertices; i++) assignment[i] = random.nextInt(3);
        List<int[]> edges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        while (edges.size() < vertices * 3) {
            int a = random.nextInt(vertices), b = random.nextInt(vertices);
            if (a == b || planted && assignment[a] == assignment[b]) continue;
            int low = Math.min(a, b), high = Math.max(a, b);
            if (seen.add(low + ":" + high)) edges.add(new int[]{low, high});
        }
        return new Network(vertices, edges);
    }

    static boolean colorable(Network graph, int[] assigned, int vertex) {
        if (vertex == assigned.length) return true;
        for (int color = 0; color < 3; color++) {
            boolean allowed = true;
            for (int[] edge : graph.edges) {
                if (edge[0] == vertex && edge[1] < vertex && assigned[edge[1]] == color ||
                        edge[1] == vertex && edge[0] < vertex && assigned[edge[0]] == color) {
                    allowed = false;
                    break;
                }
            }
            if (allowed) {
                assigned[vertex] = color;
                if (colorable(graph, assigned, vertex + 1)) return true;
            }
        }
        assigned[vertex] = -1;
        return false;
    }

    static long check(Network graph, int permutation, boolean truth, boolean certificates) {
        Random random = new Random(permutation);
        Map<String, String> names = new HashMap<>();
        java.util.function.Function<String, String> name = key -> names.computeIfAbsent(key,
                unused -> Long.toUnsignedString(random.nextLong()) + "/" + names.size());
        Map<String, Long> stock = new LinkedHashMap<>(), need = new LinkedHashMap<>();
        List<GraphRecipe<String>> recipes = new ArrayList<>();
        String target = name.apply("target");
        for (int v = 0; v < graph.vertices; v++) {
            stock.put(name.apply("u" + v), 1L);
            need.put(name.apply("d" + v), 1L);
            for (int color = 0; color < 3; color++) {
                Map<String, Long> inputs = new LinkedHashMap<>();
                inputs.put(name.apply("u" + v), 1L);
                for (int e = 0; e < graph.edges.size(); e++) if (graph.edges.get(e)[0] == v || graph.edges.get(e)[1] == v) {
                    String key = name.apply("edge" + e + "c" + color);
                    inputs.put(key, 1L);
                    stock.put(key, 1L);
                }
                String id = name.apply("recipe" + v + "c" + color);
                var slots = new ArrayList<>(inputs.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList());
                Collections.shuffle(slots, random);
                recipes.add(new GraphRecipe<>(id, id, slots, Map.of(name.apply("d" + v), 1L)));
            }
        }
        String finish = name.apply("finish");
        recipes.add(new GraphRecipe<>(finish, finish, need.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), Map.of(target, 1L)));
        Collections.shuffle(recipes, random);
        PlanningBudget budget = new PlanningBudget(0, 2_000_000, 128L << 20, () -> false, System::nanoTime);
        var journal = new CountProof.Journal(16L << 20);
        if (certificates) budget.proofJournal(journal);
        GraphPlan<String> plan = new GraphPlanner<>(new GraphCompiler<>(recipes)).plan(target, 1, stock, false, true, budget);
        if (plan.feasible() != truth || !plan.feasible() && plan.missingExact().isEmpty() && plan.result() != GraphPlan.Result.INFEASIBLE)
            throw new AssertionError("Wrong/unresolved result " + plan.result() + " permutation=" + permutation + " " + budget.diagnostics());
        if (plan.feasible() || !plan.missingExact().isEmpty()) {
            Map<String, GraphRecipe<String>> primitives = new HashMap<>();
            recipes.forEach(recipe -> primitives.put(recipe.id(), recipe));
            var summary = GraphFixtureRegression.summary(plan.steps(), primitives, new IdentityHashMap<>());
            Map<String, BigInteger> funded = new HashMap<>();
            stock.forEach((key, value) -> funded.put(key, BigInteger.valueOf(value)));
            plan.missingExact().forEach((key, value) -> funded.merge(key, value, BigInteger::add));
            for (var e : summary.need().entrySet()) if (funded.getOrDefault(e.getKey(), BigInteger.ZERO).compareTo(e.getValue()) < 0)
                throw new AssertionError("Unfunded primitive prefix " + e);
            if (summary.delta().getOrDefault(target, BigInteger.ZERO).compareTo(BigInteger.ONE) < 0)
                throw new AssertionError("Missing goal");
        }
        if (certificates) {
            if (journal.truncated() || journal.entries().stream().noneMatch(proof -> proof.closed() && proof.scope().startsWith("boolean_domain")))
                throw new AssertionError("Missing complete Boolean exclusion certificate");
            for (var proof : journal.entries()) if (CountProof.verify(proof, 20_000_000) != CountProof.Verdict.VERIFIED)
                throw new AssertionError("Independent certificate rejection");
        }
        return budget.nodes();
    }

    public static void main(String[] args) {
        Network counterexample = network(60, 2, false);
        Set<String> pairs = new HashSet<>();
        counterexample.edges.forEach(edge -> pairs.add(edge[0] + ":" + edge[1]));
        int[] clique = {19, 26, 41, 45};
        for (int a : clique) for (int b : clique) if (a < b && !pairs.contains(a + ":" + b))
            throw new AssertionError("Independent four-clique certificate differs from fixture");
        long largest = 0;
        for (int permutation = 0; permutation < 16; permutation++) largest = Math.max(largest,
                check(counterexample, permutation, false, true));
        System.out.println("BOOLEAN renamed counterexample permutations=16, largest_work=" + largest + ", original-arc and independent proof checks passed");
        Random random = new Random(602);
        int feasible = 0;
        for (int test = 0; test < 180; test++) {
            int vertices = 4 + random.nextInt(5);
            List<int[]> edges = new ArrayList<>();
            for (int a = 0; a < vertices; a++) for (int b = a + 1; b < vertices; b++) if (random.nextBoolean()) edges.add(new int[]{a, b});
            Network graph = new Network(vertices, edges);
            int[] assignment = new int[vertices];
            Arrays.fill(assignment, -1);
            boolean truth = colorable(graph, assignment, 0);
            if (truth) feasible++;
            check(graph, 1000 + test, truth, false);
        }
        for (int test = 0; test < 4; test++) check(network(60, test, true), 2000 + test, true, false);
        System.out.println("BOOLEAN exhaustive small networks=180, feasible=" + feasible + ", planted60=4 passed");
    }
}
