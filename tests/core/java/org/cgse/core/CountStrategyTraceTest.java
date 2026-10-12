// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** The real LP/LCG continuation must not be reported as model compilation. */
public final class CountStrategyTraceTest {
    private record Result(long work, IntegerCountBranch.State state, List<BigInteger> counts) {}

    public static void main(String[] args) {
        Result without = run(false), with = run(true);
        check(without.equals(with), "Enabling path metrics changed the search or its work");
        System.out.println("Count strategy attribution: LP/LCG and candidate visits, unchanged work/results, cleanup");
    }

    private static Result run(boolean metrics) {
        int n = 32;
        var recipes = new ArrayList<GraphRecipe<String>>();
        var stock = new LinkedHashMap<String, Long>();
        for (int i = 0; i < n; i++) {
            recipes.add(new GraphRecipe<>("r" + i, "r" + i, List.of(new GraphRecipe.Slot<>("token" + i, 1)), Map.of("T", 1L)));
            stock.put("token" + i, 1L);
        }
        var random = new Random(17);
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int d = 0; d < 2; d++) {
            var terms = new TreeMap<Integer, BigInteger>();
            var opposite = new TreeMap<Integer, BigInteger>(); long bound = 0;
            for (int i = 0; i < n; i++) {
                int value = 1 + random.nextInt(1000);
                terms.put(i, BigInteger.valueOf(value)); opposite.put(i, BigInteger.valueOf(-value));
                if (i % 3 == 0) bound += value;
            }
            rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(bound)));
            rows.add(new ExactLinearProgram.Constraint(opposite, BigInteger.valueOf(-bound)));
        }
        var budget = new PlanningBudget(0, 20_000_000, 64L << 20, () -> false, System::nanoTime);
        if (metrics) budget.enableMetrics();
        try (var model = RecipeCountModel.create(new GraphCompiler<>(recipes), "T", 1, stock, Map.of(), Set.of(), Set.of(), false, budget);
             var execution = new CountExecution<>(model, budget);
             var branch = new IntegerCountBranch<>(model, execution, "T", 1, stock, Map.of(), Set.of(), false, false, budget, 0, List.of())) {
            // Prepare a real retained LP frontier independently of root dispatch.
            // Only observation is varied between the two otherwise identical runs.
            branch.initialized = branch.compiled = branch.partitioned = true;
            branch.lower = new BigInteger[n]; Arrays.fill(branch.lower, BigInteger.ZERO);
            branch.upper = new BigInteger[n]; Arrays.fill(branch.upper, BigInteger.ONE);
            branch.linearConstraints = new ArrayList<>(rows);
            branch.reduction = new CountReduction(rows, branch.lower, branch.upper, budget);
            while (!branch.reduction.step()) {}
            branch.lpSearch = CountLpSearch.create(branch.reduction, n, budget);
            check(branch.lpSearch != null, "Missing real LP frontier");
            branch.lpActive = true;
            int visits = 0;
            while (branch.lpActive && branch.state == IntegerCountBranch.State.OPEN && visits++ < 10000)
                branch.run(512, List.of(), List.of(), List.of(), null, () -> false);
            check(visits < 10000, "LP sample failed to yield");
            if (metrics) {
                var arms = budget.metrics().strategies();
                check(arms.containsKey("count_lp_lcg") && arms.get("count_lp_lcg").chargedWork() > 1000,
                        "LP/LCG work is missing from its actual strategy");
                check(arms.containsKey("count_lp_proximity") && arms.get("count_lp_proximity").chargedWork() > 1000,
                        "Candidate index/query work is missing");
                check(!arms.containsKey("count_compile"), "An already compiled LP sample was counted as compilation");
            }
            return new Result(budget.nodes(), branch.state, branch.counts == null ? List.of() : Arrays.asList(branch.counts.clone()));
        } finally { check(budget.reservedBytes() == 0, "Observed continuation leaked"); }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
