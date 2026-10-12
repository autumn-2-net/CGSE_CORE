// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.nio.file.Files;
import java.util.*;

/** Independent finite-state oracles for consumable-resource coverage. */
public final class ForwardDominanceTest {
    public static void main(String[] args) throws Exception {
        randomFuelNets();
        certificates();
        System.out.println("Forward dominance: 480 dense BFS controls, primitive witnesses, original-arc closure, changed supply and serialized proof negatives passed");
    }

    private static void randomFuelNets() {
        var random = new Random(1013026);
        int witnessed = 0, closed = 0, covered = 0;
        for (int test = 0; test < 160; test++) {
            int n = 3 + random.nextInt(3);
            var recipes = new ArrayList<GraphRecipe<String>>();
            for (int r = 0; r < 8; r++) {
                var in = new LinkedHashMap<String, Long>();
                var out = new LinkedHashMap<String, Long>();
                in.put("F", 1L + random.nextInt(3));
                in.merge("k" + random.nextInt(n), 1L, Long::sum);
                out.merge("k" + random.nextInt(n), 1L, Long::sum);
                if (random.nextBoolean()) {
                    String read = "k" + random.nextInt(n);
                    in.merge(read, 1L, Long::sum); out.merge(read, 1L, Long::sum);
                }
                recipes.add(recipe("r" + r, in, out));
            }
            var stock = new LinkedHashMap<String, Long>();
            stock.put("F", 4L + random.nextInt(9));
            for (int k = 0; k < 3; k++) stock.merge("k" + random.nextInt(n), 1L, Long::sum);
            var goal = new LinkedHashMap<String, BigInteger>();
            goal.put("F", BigInteger.valueOf(random.nextInt(3)));
            goal.put("k" + random.nextInt(n), BigInteger.valueOf(1 + random.nextInt(4)));
            boolean possible = bfs(recipes, stock, goal);
            for (int order = 0; order < 3; order++) {
                Collections.shuffle(recipes, random);
                var budget = new PlanningBudget(0, 8_000_000, 64L << 20, () -> false, System::nanoTime);
                var journal = new CountProof.Journal(8L << 20); budget.proofJournal(journal);
                try (var model = RecipeCountModel.forShell(recipes, goal, stock, Set.of(), budget);
                     var search = CountReachability.create(model, 1024).retained()) {
                    do {
                        while (!search.step()) {}
                        if (!search.paused()) break;
                        check(journal.executions().isEmpty(), "partial boundary published");
                        search.resume(1024);
                    } while (true);
                    check(search.result() == (possible ? BackwardCoverability.Result.WITNESS : BackwardCoverability.Result.CLOSED),
                            "dense BFS mismatch at " + test + "/" + order + ": " + search.result());
                    if (possible) {
                        witnessed++;
                        var byId = new LinkedHashMap<String, GraphRecipe<String>>();
                        recipes.forEach(r -> byId.put(r.id(), r));
                        var summary = GraphFixtureRegression.summary(search.witness(), byId, new IdentityHashMap<>());
                        for (var need : summary.need().entrySet())
                            check(BigInteger.valueOf(stock.getOrDefault(need.getKey(), 0L)).compareTo(need.getValue()) >= 0, "fabricated covered-state witness");
                        for (var entry : goal.entrySet())
                            check(BigInteger.valueOf(stock.getOrDefault(entry.getKey(), 0L)).add(summary.delta().getOrDefault(entry.getKey(), BigInteger.ZERO)).compareTo(entry.getValue()) >= 0, "wrong concrete goal");
                    } else {
                        closed++;
                        check(journal.executions().size() == 1, "missing closure certificate");
                        var proof = journal.executions().get(0);
                        if (proof.kind() == ExecutionProof.Kind.FORWARD_DOMINANCE) covered++;
                        check(ExecutionProof.verify(proof, 1_000_000) == CountProof.Verdict.VERIFIED, "independent replay failed");
                    }
                }
                check(budget.reservedBytes() == 0, "state/index/checker memory leaked");
            }
        }
        check(witnessed > 50 && closed > 50 && covered > 50, "lost positive, negative or dominance coverage");
    }

    private static boolean bfs(List<GraphRecipe<String>> recipes, Map<String, Long> stock, Map<String, BigInteger> goal) {
        var keys = new TreeSet<>(stock.keySet()); keys.addAll(goal.keySet());
        recipes.forEach(r -> { keys.addAll(r.inputs().keySet()); keys.addAll(r.outputs().keySet()); });
        var ordered = List.copyOf(keys);
        var pending = new ArrayDeque<List<Long>>();
        var seen = new HashSet<List<Long>>();
        var initial = ordered.stream().map(k -> stock.getOrDefault(k, 0L)).toList();
        pending.add(initial); seen.add(initial);
        while (!pending.isEmpty()) {
            var state = pending.remove();
            boolean reached = true;
            for (int k = 0; k < ordered.size(); k++)
                reached &= BigInteger.valueOf(state.get(k)).compareTo(goal.getOrDefault(ordered.get(k), BigInteger.ZERO)) >= 0;
            if (reached) return true;
            for (var r : recipes) {
                boolean enabled = true;
                var next = new ArrayList<Long>();
                for (int k = 0; k < ordered.size(); k++) {
                    long input = r.inputs().getOrDefault(ordered.get(k), 0L);
                    if (state.get(k) < input) enabled = false;
                    next.add(state.get(k) - input + r.outputs().getOrDefault(ordered.get(k), 0L));
                }
                if (enabled && seen.add(next)) pending.add(next);
            }
            check(seen.size() < 10_000, "unbounded test oracle");
        }
        return false;
    }

    private static void certificates() throws Exception {
        var initial = vector(2, 1, 0, 0);
        var other = vector(1, 0, 1, 0);
        var goal = vector(0, 0, 0, 1);
        var in = List.of(vector(1, 1, 0, 0), vector(1, 0, 1, 0));
        var out = List.of(vector(0, 0, 1, 0), vector(0, 1, 0, 0));
        var proof = new ExecutionProof.Certificate("test", ExecutionProof.Kind.FORWARD_DOMINANCE, initial, goal, in, out, List.of(initial, other), Set.of());
        check(ExecutionProof.verify(proof, 10_000) == CountProof.Verdict.VERIFIED, "consumable coverage refused");
        var exact = new ExecutionProof.Certificate("test", ExecutionProof.Kind.FORWARD_BOUNDARY, initial, goal, in, out, proof.states(), Set.of());
        check(ExecutionProof.verify(exact, 10_000) == CountProof.Verdict.INVALID, "old exact certificate silently acquired weaker semantics");
        for (var invalid : List.of(
                new ExecutionProof.Certificate("test", proof.kind(), vector(3, 1, 0, 0), goal, in, out, proof.states(), Set.of()),
                new ExecutionProof.Certificate("test", proof.kind(), initial, goal, in, List.of(vector(0, 0, 1, 1), out.get(1)), proof.states(), Set.of()),
                new ExecutionProof.Certificate("test", proof.kind(), initial, goal, in, List.of(vector(2, 0, 1, 0), out.get(1)), proof.states(), Set.of()),
                new ExecutionProof.Certificate("test", proof.kind(), initial, other, in, out, proof.states(), Set.of())))
            check(ExecutionProof.verify(invalid, 10_000) == CountProof.Verdict.INVALID, "changed inventory/producer/goal accepted by old cover");
        var check = new ExecutionProof.ForwardCheck(proof);
        while (!check.step(1, unused -> {})) {}
        check(check.result() == CountProof.Verdict.VERIFIED, "one-work grants lost closure progress");
        var journal = new CountProof.Journal(1L << 20); journal.add(proof);
        var path = Files.createTempFile("cgse-forward-dominance-", ".cgp");
        try {
            journal.write(path);
            var replay = CountProof.read(path);
            check(replay.executions().equals(List.of(proof)), "serialized coverage changed");
            check(ExecutionProof.verify(replay.executions().get(0), 10_000) == CountProof.Verdict.VERIFIED, "serialized proof failed");
        } finally { Files.deleteIfExists(path); }
    }

    private static List<BigInteger> vector(long... values) { return Arrays.stream(values).mapToObj(BigInteger::valueOf).toList(); }
    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
