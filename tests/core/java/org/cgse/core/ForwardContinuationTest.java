// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Finite primitive-state oracle, retained work grants and independent closure replay. */
public final class ForwardContinuationTest {
    public static void main(String[] args) {
        long coldWork = replay(500_000, false);
        for (int grant : new int[]{1024, 4096, 32768}) {
            long retainedWork = replay(grant, true);
            check(retainedWork <= coldWork + 4096, "grants restarted already completed traversal/checking");
        }
        limits();
        checker();
        System.out.println("Forward continuation: exact primitive closure, partial successor/proof replay, bounded grants, memory and cancellation passed");
    }

    private static List<GraphRecipe<String>> recipes() {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (String group : List.of("A", "B")) for (int i = 0; i < 9; i++)
            recipes.add(recipe(group + i, Map.of("F", 1L, group + i, 1L), Map.of(group + (i + 1) % 9, 1L)));
        recipes.add(recipe("finish", Map.of("F", 1L, "A8", 1L, "B8", 1L), Map.of("G", 1L)));
        return recipes;
    }

    private static long replay(int grant, boolean retained) {
        var budget = budget();
        var journal = new CountProof.Journal(16L << 20);
        budget.proofJournal(journal);
        var recipes = recipes();
        var stock = Map.of("F", 16L, "A0", 1L, "B0", 1L);
        var goal = Map.of("G", BigInteger.ONE);
        long spent;
        try (var model = RecipeCountModel.forShell(recipes, goal, stock, Set.of(), budget);
             var search = CountReachability.create(model, grant)) {
            check(search != null, "ordinary small net refused");
            if (retained) search.retained();
            int pauses = 0;
            long before = budget.nodes();
            while (true) {
                while (!search.step()) {}
                if (!search.paused()) break;
                check(search.result() == BackwardCoverability.Result.UNKNOWN && journal.executions().isEmpty(),
                        "unfinished traversal/checker published a proof");
                check(++pauses < 1000, "retained traversal stopped making progress");
                search.resume(grant);
            }
            spent = budget.nodes() - before;
            check(search.result() == BackwardCoverability.Result.CLOSED, "finite closure was not completed");
            check(retained == (pauses > 0), "test failed to exercise local stops");
            check(journal.executions().size() == 1, "expected one completed root certificate");
            var proof = journal.executions().get(0);
            check(proof.states().size() == 117, "wrong finite state space: " + proof.states().size());
            check(rawClosure(proof), "independent raw-transition closure rejected the proof");
            check(ExecutionProof.verify(proof, 1_000_000) == CountProof.Verdict.VERIFIED, "serialized checker contract changed");
            search.close();
            check(!search.paused(), "closed frontier can be resumed");
        }
        check(budget.reservedBytes() == 0, "retained frontier/checker leaked budget memory");
        return spent;
    }

    private static void limits() {
        for (boolean cancel : new boolean[]{false, true}) {
            var budget = budget();
            try (var model = RecipeCountModel.forShell(recipes(), Map.of("G", BigInteger.ONE),
                    Map.of("F", 16L, "A0", 1L, "B0", 1L), Set.of(), budget);
                 var search = CountReachability.create(model, 1024).retained()) {
                while (!search.step()) {}
                check(search.paused(), "local cutoff did not retain the frontier");
                if (cancel) {
                    search.resume(4096);
                    budget.cancel();
                    try { search.step(); throw new AssertionError("cancelled continuation ran"); }
                    catch (CancellationException expected) { /* shared order remains cancelled */ }
                } else {
                    long held = budget.availableBytes();
                    budget.reserve(held);
                    search.resume(32768);
                    while (!search.step()) {}
                    check(search.result() == BackwardCoverability.Result.UNKNOWN && !search.paused(),
                            "failed frontier insertion became a proof or resumable edge");
                    budget.release(held);
                }
            }
            check(budget.reservedBytes() == 0, "abandoned retained search leaked");
        }
        // An unbounded original state space may hit the state cap. A capped set
        // is neither a complete closure nor a continuation with omitted edges.
        var budget = budget();
        var grow = List.of(recipe("grow", Map.of("A", 1L), Map.of("A", 2L)));
        try (var model = RecipeCountModel.forShell(grow, Map.of("G", BigInteger.ONE), Map.of("A", 1L), Set.of(), budget);
             var search = CountReachability.create(model, 500_000).retained()) {
            while (!search.step()) {}
            check(search.result() == BackwardCoverability.Result.UNKNOWN && !search.paused(), "state cap exported a closed boundary");
        }
        check(budget.reservedBytes() == 0, "state cap leaked");
    }

    private static void checker() {
        var initial = List.of(BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO);
        var other = List.of(BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO);
        var goal = List.of(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ONE);
        var inputs = List.of(initial, other);
        var outputs = List.of(other, initial);
        var proof = proof(initial, goal, inputs, outputs, List.of(initial, other));
        for (var value : List.of(proof,
                proof(initial, initial, inputs, outputs, proof.states()),
                proof(initial, goal, inputs, outputs, List.of(initial)),
                proof(initial, goal, inputs, outputs, List.of(other)),
                proof(initial, goal, inputs, List.of(goal, initial), proof.states()))) {
            var check = new ExecutionProof.ForwardCheck(value);
            int steps = 0;
            while (!check.step(1, unused -> {})) check(++steps < 1000, "one-unit proof grant stalled");
            check((check.result() == CountProof.Verdict.VERIFIED) == rawClosure(value), "incremental proof differs from raw replay");
        }
        var invalid = proof(initial, goal, inputs, List.of(List.of(BigInteger.valueOf(-1)), initial), proof.states());
        var check = new ExecutionProof.ForwardCheck(invalid);
        while (!check.step(3, unused -> {})) {}
        check(check.result() == CountProof.Verdict.INVALID, "malformed/negative vector accepted");
        check(ExecutionProof.verify(proof, 1) == CountProof.Verdict.INCOMPLETE, "incomplete replay became a proof");
        var unknownKind = new ExecutionProof.Certificate("test", null, initial, goal, inputs, outputs, List.of(), Set.of());
        check(ExecutionProof.verify(unknownKind, 1000) == CountProof.Verdict.INVALID, "missing certificate kind accepted");
        var budget = budget();
        check = new ExecutionProof.ForwardCheck(proof);
        check.step(7, budget::charge);
        budget.cancel();
        try { check.step(7, budget::charge); throw new AssertionError("checker ignored cancellation"); }
        catch (CancellationException expected) { /* cancellation reaches every bounded checker step */ }
    }

    /** Independent enumeration over raw certificate arcs; no solver/checker helpers. */
    private static boolean rawClosure(ExecutionProof.Certificate proof) {
        var states = new HashSet<>(proof.states());
        if (!states.contains(proof.initial())) return false;
        for (var state : states) {
            boolean goal = true;
            for (int k = 0; k < state.size(); k++) if (state.get(k).compareTo(proof.goal().get(k)) < 0) goal = false;
            if (goal) return false;
            for (int r = 0; r < proof.inputs().size(); r++) {
                boolean enabled = true;
                var next = new ArrayList<BigInteger>();
                for (int k = 0; k < state.size(); k++) {
                    if (state.get(k).compareTo(proof.inputs().get(r).get(k)) < 0) enabled = false;
                    next.add(state.get(k).subtract(proof.inputs().get(r).get(k)).add(proof.outputs().get(r).get(k)));
                }
                if (enabled && !states.contains(next)) return false;
            }
        }
        return true;
    }

    private static ExecutionProof.Certificate proof(List<BigInteger> initial, List<BigInteger> goal,
            List<List<BigInteger>> inputs, List<List<BigInteger>> outputs, List<List<BigInteger>> states) {
        return new ExecutionProof.Certificate("test", ExecutionProof.Kind.FORWARD_BOUNDARY, initial, goal, inputs, outputs, states, Set.of());
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 40_000_000, 256L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
