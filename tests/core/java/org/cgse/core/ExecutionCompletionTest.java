// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;

/** A finite original-state boundary must have a bounded chance to finish its proof. */
public final class ExecutionCompletionTest {
    private static final int[][] PREDECESSORS = {{}, {}, {0, 1}, {1}, {0, 1, 3}, {2, 3},
            {3, 4, 5}, {0, 2, 6}, {3, 6}, {8}, {3, 8}, {4, 8, 9}, {2, 10, 11}, {2, 12}};

    public static void main(String[] args) throws Exception {
        check(!reachable(5, 24) && reachable(14, 24), "independent pebbling oracle changed");
        var recipes = new ArrayList<GraphRecipe<String>>();
        var stock = new LinkedHashMap<String, Long>();
        stock.put("F", 24L);
        for (int i = 0; i < PREDECESSORS.length; i++) {
            stock.put("OFF" + i, 1L);
            var in = new LinkedHashMap<String, Long>();
            var out = new LinkedHashMap<String, Long>();
            in.put("F", 1L); in.put("W", 1L); in.put("OFF" + i, 1L); out.put("P" + i, 1L);
            for (int p : PREDECESSORS[i]) { in.put("P" + p, 1L); out.put("P" + p, 1L); }
            recipes.add(recipe("build" + i, in, out));
            recipes.add(recipe("erase" + i, Map.of("F", 1L, "P" + i, 1L), Map.of("OFF" + i, 1L, "W", 1L)));
        }
        for (int order = 0; order < 4; order++) {
            Collections.shuffle(recipes, new Random(13 + order));
            stock.put("W", order == 3 ? 14L : 5L);
            var budget = new PlanningBudget(0, 8_000_000, 256L << 20, () -> false, System::nanoTime);
            var journal = new CountProof.Journal(16L << 20);
            budget.proofJournal(journal);
            try (var search = new IntegerCountSearch<>(new GraphCompiler<>(recipes), "P13", 1, stock, Map.of(),
                    Set.of(), Set.of(), false, true, budget, 0)) {
                while (true) {
                    if (!search.step()) continue;
                    if (!search.paused()) break;
                    search.resume();
                }
                if (order == 3) {
                    check(search.result() != null && search.result().feasible(), "proof survived replenished inventory");
                    PlanVerifier.verify(search.result());
                } else {
                    check(search.infeasible(), "completed frontier lost its verification budget");
                    check(journal.executions().stream().anyMatch(p -> p.states().size() > 1000), "large original boundary not exercised");
                    for (var proof : journal.executions())
                        check(ExecutionProof.verify(proof, 4_000_000) == CountProof.Verdict.VERIFIED, "independent replay failed");
                    var path = java.nio.file.Files.createTempFile("cgse-completed-frontier-", ".cgp");
                    try {
                        journal.write(path);
                        var replay = CountProof.read(path);
                        check(!replay.truncated() && replay.executions().equals(journal.executions()), "proof serialization changed");
                        for (var proof : replay.executions())
                            check(ExecutionProof.verify(proof, 4_000_000) == CountProof.Verdict.VERIFIED, "serialized proof invalid");
                    } finally { java.nio.file.Files.deleteIfExists(path); }
                }
            }
            check(budget.reservedBytes() == 0, "completed proof leaked request memory");
        }
        System.out.println("Execution completion: independent finite-state oracle, bounded root proof completion, permutations, refill and serialized replay passed");
    }

    private static boolean reachable(int slots, int fuel) {
        int[] distance = new int[1 << PREDECESSORS.length];
        Arrays.fill(distance, -1); distance[0] = 0;
        var pending = new ArrayDeque<Integer>(); pending.add(0);
        while (!pending.isEmpty()) {
            int state = pending.removeFirst();
            if ((state & (1 << 13)) != 0) return true;
            if (distance[state] == fuel) continue;
            for (int i = 0; i < PREDECESSORS.length; i++) {
                if ((state & (1 << i)) == 0) {
                    if (Integer.bitCount(state) >= slots) continue;
                    boolean enabled = true;
                    for (int p : PREDECESSORS[i]) enabled &= (state & (1 << p)) != 0;
                    if (!enabled) continue;
                }
                int next = state ^ (1 << i);
                if (distance[next] < 0) { distance[next] = distance[state] + 1; pending.add(next); }
            }
        }
        return false;
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
