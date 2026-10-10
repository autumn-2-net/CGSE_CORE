// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** Primitive BFS oracle and root handoff checks for execution-state certificates. */
public final class CountReachabilityTest {
    public static void main(String[] args) throws Exception {
        randomConservedNets();
        scopesAndLimits();
        rootHandoff();
        System.out.println("Count reachability: 600 exact BFS cases, proof replay, root handoff, stock changes, external/read/configuration arcs, cutoffs and cancellation passed");
    }

    private static void randomConservedNets() {
        var random = new Random(1011026);
        int closed = 0, witnessed = 0;
        for (int test = 0; test < 300; test++) {
            int dimensions = 2 + random.nextInt(4);
            var recipes = new ArrayList<GraphRecipe<String>>();
            for (int r = 0, size = 1 + random.nextInt(7); r < size; r++) {
                var in = new LinkedHashMap<String, Long>();
                var out = new LinkedHashMap<String, Long>();
                for (int left = 1 + random.nextInt(3); left > 0; left--) {
                    in.merge("k" + random.nextInt(dimensions), 1L, Long::sum);
                    out.merge("k" + random.nextInt(dimensions), 1L, Long::sum);
                }
                recipes.add(recipe("r" + r, in, out));
            }
            var stock = new LinkedHashMap<String, Long>();
            for (int left = random.nextInt(7); left > 0; left--) stock.merge("k" + random.nextInt(dimensions), 1L, Long::sum);
            var goals = new LinkedHashMap<String, BigInteger>();
            for (int k = 0; k < dimensions; k++) goals.put("k" + k, BigInteger.ZERO);
            for (int left = 1 + random.nextInt(4); left > 0; left--) goals.merge("k" + random.nextInt(dimensions), BigInteger.ONE, BigInteger::add);
            boolean possible = bfs(recipes, stock, goals);
            for (int order = 0; order < 2; order++) {
                Collections.shuffle(recipes, random);
                var budget = budget();
                var journal = new CountProof.Journal(16L << 20);
                budget.proofJournal(journal);
                try (var model = RecipeCountModel.forShell(recipes, goals, stock, Set.of(), budget);
                     var search = CountReachability.create(model, 500_000)) {
                    check(search != null, "small ordinary net was not admitted");
                    while (!search.step()) {}
                    check(search.result() == (possible ? BackwardCoverability.Result.WITNESS : BackwardCoverability.Result.CLOSED),
                            "BFS mismatch at " + test + "/" + order + ": " + search.result());
                    if (possible) {
                        witnessed++;
                        var primitives = new LinkedHashMap<String, GraphRecipe<String>>();
                        recipes.forEach(r -> primitives.put(r.id(), r));
                        var summary = GraphFixtureRegression.summary(search.witness(), primitives, new IdentityHashMap<>());
                        for (var key : goals.keySet()) {
                            var held = BigInteger.valueOf(stock.getOrDefault(key, 0L));
                            check(held.compareTo(summary.need().getOrDefault(key, BigInteger.ZERO)) >= 0, "unfunded primitive prefix");
                            check(held.add(summary.delta().getOrDefault(key, BigInteger.ZERO)).compareTo(goals.get(key)) >= 0, "missing goal");
                        }
                    } else {
                        closed++;
                        check(journal.executions().size() == 1, "closed root did not export its certificate");
                        var proof = journal.executions().get(0);
                        check(proof.marked().isEmpty() && ExecutionProof.verify(proof, 1_000_000) == CountProof.Verdict.VERIFIED,
                                "root proof was incomplete or failed independent replay");
                        var tampered = new ExecutionProof.Certificate(proof.scope(), proof.kind(), proof.initial(), proof.initial(),
                                proof.inputs(), proof.outputs(), proof.states(), proof.marked());
                        check(ExecutionProof.verify(tampered, 1_000_000) == CountProof.Verdict.INVALID, "reachable goal accepted as absent");
                    }
                    search.close(); // Closing an already released optional search is harmless.
                }
                check(budget.reservedBytes() == 0, "BFS case leaked");
            }
        }
        check(closed > 100 && witnessed > 100, "oracle lost either positive or negative coverage");
    }

    private static void scopesAndLimits() {
        var recipes = List.of(recipe("ab", Map.of("a", 1L), Map.of("b", 1L)),
                recipe("ba", Map.of("b", 1L), Map.of("a", 1L)),
                recipe("finish", Map.of("b", 1L, "raw", 1L), Map.of("goal", 1L)));
        for (var external : List.of(Set.<String>of(), Set.of("raw"))) {
            var budget = budget();
            try (var model = RecipeCountModel.forShell(recipes, Map.of("goal", BigInteger.ONE), Map.of("a", 1L), external, budget);
                 var search = CountReachability.create(model, 32768)) {
                while (!search.step()) {}
                check(search.result() == (external.isEmpty() ? BackwardCoverability.Result.CLOSED : BackwardCoverability.Result.WITNESS),
                        "external projection changed reachability");
            }
            check(budget.reservedBytes() == 0, "external projection leaked");
        }
        for (boolean reusable : new boolean[]{false, true}) {
            var read = new GraphRecipe<>("read", "read", List.of(new GraphRecipe.Slot<>("tool", 1, 0, true, reusable),
                    new GraphRecipe.Slot<>("raw", 1)), reusable ? Map.of("goal", 1L, "tool", 1L) : Map.of("goal", 1L));
            var budget = budget();
            try (var model = RecipeCountModel.forShell(List.of(read), Map.of("goal", BigInteger.TWO),
                    Map.of("tool", 1L, "raw", 2L), Set.of(), budget); var search = CountReachability.create(model, 32768)) {
                if (!reusable) check(search == null, "per-push configuration entered a per-firing proof");
                else {
                    while (!search.step()) {}
                    check(search.result() == BackwardCoverability.Result.WITNESS, "read arc was consumed");
                }
            }
            check(budget.reservedBytes() == 0, "configuration/read scope leaked");
        }
        var growing = List.of(recipe("grow", Map.of("a", 1L), Map.of("a", 2L)),
                recipe("missing", Map.of("never", 1L), Map.of("goal", 1L)));
        var budget = budget();
        try (var model = RecipeCountModel.forShell(growing, Map.of("goal", BigInteger.ONE), Map.of("a", 1L), Set.of(), budget)) {
            check(CountReachability.create(model, 1) == null, "tiny allowance was admitted");
            long held = budget.availableBytes();
            budget.reserve(held);
            check(CountReachability.create(model, 32768) == null, "optional memory refusal was ignored");
            budget.release(held);
            try (var search = CountReachability.create(model, 1024)) {
                long before = budget.nodes();
                while (!search.step()) {}
                check(search.result() == BackwardCoverability.Result.UNKNOWN, "local work cap became an absence proof");
                check(budget.nodes() - before <= 1025, "local search cap was reset between steps");
            }
            try (var search = CountReachability.create(model, 32768)) {
                search.step();
                budget.cancel();
                try {
                    search.step();
                    throw new AssertionError("cancelled state search continued");
                } catch (CancellationException expected) { /* outer request remains cancelled */ }
            }
        }
        check(budget.reservedBytes() == 0, "cutoff/cancellation leaked");

        // The weak inventory goal may already hold. Its empty witness still
        // cannot satisfy forced production in the ordinary candidate validator.
        budget = budget();
        var neutral = List.of(recipe("ab", Map.of("a", 1L), Map.of("b", 1L)), recipe("ba", Map.of("b", 1L), Map.of("a", 1L)));
        var stock = Map.of("a", 1L);
        try (var model = RecipeCountModel.create(new GraphCompiler<>(neutral), "a", 1, stock, Map.of(), Set.of(), Set.of(), true, budget);
             var execution = new CountExecution<>(model, budget); var search = CountReachability.create(model, 32768);
             var branch = new IntegerCountBranch<>(model, execution, "a", 1, stock, Map.of(), Set.of(), false, true, budget, 0, List.of())) {
            while (!search.step()) {}
            check(search.result() == BackwardCoverability.Result.WITNESS, "weak goal was strengthened");
            branch.compiledCandidate(search.counts(), search.witness());
            while (branch.state == IntegerCountBranch.State.OPEN)
                branch.run(7, List.of(), List.of(), List.of(), null, () -> false);
            check(branch.state != IntegerCountBranch.State.FOUND && branch.plan == null, "empty witness bypassed forced-production validation");
        }
        check(budget.reservedBytes() == 0, "forced witness handoff leaked");
    }

    private static void rootHandoff() throws Exception {
        // Pebbling needs three simultaneous working tokens to construct P2;
        // aggregate recipe counts do not express that execution-state fact.
        int[][] predecessors = {{}, {}, {0, 1}, {0, 1, 2}, {1, 2, 3}, {0, 2}, {0, 2, 5}, {0, 2}};
        var recipes = new ArrayList<GraphRecipe<String>>();
        var stock = new LinkedHashMap<String, Long>();
        stock.put("F", 19L);
        for (int i = 0; i < predecessors.length; i++) {
            stock.put("OFF" + i, 1L);
            var in = new LinkedHashMap<String, Long>();
            var out = new LinkedHashMap<String, Long>();
            in.put("F", 1L); in.put("W", 1L); in.put("OFF" + i, 1L); out.put("P" + i, 1L);
            for (int p : predecessors[i]) { in.put("P" + p, 1L); out.put("P" + p, 1L); }
            recipes.add(recipe("build" + i, in, out));
            recipes.add(recipe("erase" + i, Map.of("F", 1L, "P" + i, 1L), Map.of("OFF" + i, 1L, "W", 1L)));
        }
        Collections.shuffle(recipes, new Random(1011));
        var compiler = new GraphCompiler<>(recipes);
        for (int order = 0; order < 3; order++) {
            stock.put("W", order == 1 ? 8L : 2L);
            var budget = budget();
            var journal = new CountProof.Journal(16L << 20);
            budget.proofJournal(journal);
            try (var search = new IntegerCountSearch<>(compiler, "P7", 1, stock, Map.of(), Set.of(), Set.of(), false, true, budget, 0)) {
                while (true) {
                    if (!search.step()) continue;
                    if (!search.paused()) break;
                    search.resume();
                }
                if (order == 1) {
                    check(search.result() != null && search.result().feasible(), "root proof leaked into replenished stock");
                    PlanVerifier.verify(search.result());
                } else {
                    check(search.infeasible() && search.result() == null, "root closure did not reach main count search");
                    check(journal.executions().stream().anyMatch(p -> p.scope().equals("forward_original_closure")), "execution proof path was not used");
                }
                for (var proof : journal.executions()) check(ExecutionProof.verify(proof, 20_000_000) == CountProof.Verdict.VERIFIED, "root journal replay failed");
                if (order == 0) {
                    var archive = Files.createTempFile("cgse-root-execution-", ".cgp");
                    try {
                        journal.write(archive);
                        var replay = CountProof.read(archive);
                        check(!replay.truncated() && replay.executions().equals(journal.executions()), "root certificate changed in serialization");
                        for (var proof : replay.executions())
                            check(ExecutionProof.verify(proof, 20_000_000) == CountProof.Verdict.VERIFIED, "serialized root proof failed replay");
                    } finally { Files.deleteIfExists(archive); }
                }
            }
            check(budget.reservedBytes() == 0, "root handoff leaked");
        }
        for (int threads : new int[]{1, 4}) {
            var budget = budget();
            try (var scheduler = new PlanningScheduler(threads, 4, 127, 1_000_000_000)) {
                var work = new GraphPlanner<>(compiler).begin("P7", 1, stock, false, true, budget);
                var result = scheduler.submit(work, budget).get(30, TimeUnit.SECONDS);
                check(Set.of(GraphPlan.Result.MISSING_INPUT, GraphPlan.Result.MISSING_SEED, GraphPlan.Result.INFEASIBLE).contains(result.result()),
                        "root closure lost on worker handoff: " + result.result());
                check(budget.reservedBytes() == 0, "scheduled root leaked");
            }
        }
    }

    /** Exhaustive finite-state oracle using raw arcs and small exact integer tokens. */
    private static boolean bfs(List<GraphRecipe<String>> recipes, Map<String, Long> stock, Map<String, BigInteger> goal) {
        var keys = List.copyOf(goal.keySet());
        var initial = keys.stream().map(k -> stock.getOrDefault(k, 0L).intValue()).toList();
        var todo = new ArrayDeque<List<Integer>>();
        var seen = new HashSet<List<Integer>>();
        todo.add(initial); seen.add(initial);
        while (!todo.isEmpty()) {
            var state = todo.remove();
            boolean covers = true;
            for (int k = 0; k < keys.size(); k++) if (state.get(k) < goal.get(keys.get(k)).intValueExact()) covers = false;
            if (covers) return true;
            for (var recipe : recipes) {
                boolean enabled = true;
                var next = new ArrayList<Integer>();
                for (int k = 0; k < keys.size(); k++) {
                    int input = recipe.inputs().getOrDefault(keys.get(k), 0L).intValue();
                    if (input > state.get(k)) enabled = false;
                    next.add(state.get(k) - input + recipe.outputs().getOrDefault(keys.get(k), 0L).intValue());
                }
                if (enabled && seen.add(next)) todo.add(next);
            }
        }
        return false;
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }

    private static PlanningBudget budget() { return new PlanningBudget(0, 20_000_000, 64L << 20, () -> false, System::nanoTime); }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
