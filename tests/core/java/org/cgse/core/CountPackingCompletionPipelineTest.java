// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** A retained packing completion must reach the original graph's executable witness. */
public final class CountPackingCompletionPipelineTest {
    public static void main(String[] args) throws Exception {
        String input = String.join("\n", Files.readAllLines(Path.of("tests/core/data/packing-completion-100.txt"))
                .stream().filter(line -> !line.startsWith("#")).toList());
        try (var scanner = new Scanner(input)) {
            int n = scanner.nextInt(), m = scanner.nextInt();
            check(n == 100 && m == 11, "Unexpected canonical fixture dimensions");
            var rows = new BigInteger[m][n];
            var bounds = new BigInteger[m];
            for (int r = 0; r < m; r++) {
                bounds[r] = scanner.nextBigInteger();
                for (int i = 0; i < n; i++) rows[r][i] = scanner.nextBigInteger();
            }
            var oracle = new BigInteger[n];
            for (int i = 0; i < n; i++) oracle[i] = scanner.nextBigInteger();
            check(!scanner.hasNext(), "Trailing fixture data");
            // This independently checked point establishes SAT. It is never
            // supplied to planning, continuation admission or branch selection.
            verify(rows, bounds, oracle);
            var stock = new LinkedHashMap<String, Long>();
            for (int r = 0; r < m - 1; r++) stock.put("CAP" + r, bounds[r].longValueExact());
            for (int i = 0; i < n; i++) stock.put("ONCE" + i, 1L);
            var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
            for (int i = 0; i < n; i++) {
                var inputs = new LinkedHashMap<String, Long>();
                for (int r = 0; r < m - 1; r++) inputs.put("CAP" + r, rows[r][i].longValueExact());
                inputs.put("ONCE" + i, 1L);
                String id = "pick" + i;
                recipes.put(id, HistoricalFixtures.r(id, inputs, Map.of("GOAL", rows[m - 1][i].negate().longValueExact())));
            }
            long amount = bounds[m - 1].negate().longValueExact();
            var budget = new PlanningBudget(0, 60_000_000, 256L << 20, () -> false, System::nanoTime);
            GraphPlan<String> plan;
            var work = new GraphPlanner<>(new GraphCompiler<>(new ArrayList<>(recipes.values())))
                    .begin("GOAL", amount, stock, Set.of(), false, true, budget);
            try {
                while (!work.step()) {}
                plan = work.result();
            } finally { work.close(); }
            check(plan.feasible() && plan.missingExact().isEmpty(), "Canonical packing witness was lost: " + plan.result());
            var counts = new BigInteger[n];
            for (int i = 0; i < n; i++) counts[i] = plan.patternTimesExact().getOrDefault("pick" + i, BigInteger.ZERO);
            check(recipes.keySet().containsAll(plan.patternTimesExact().keySet()), "Plan contains an unknown recipe");
            verify(rows, bounds, counts);
            // Validate primitive prefixes independently of the planner's count
            // certificate, including one-use stock and actual target delivery.
            var summary = GraphFixtureRegression.summary(plan.steps(), recipes, new IdentityHashMap<>());
            for (var need : summary.need().entrySet()) {
                check(BigInteger.valueOf(stock.getOrDefault(need.getKey(), 0L)).compareTo(need.getValue()) >= 0,
                        "Unfunded primitive prefix: " + need);
                check(plan.initialExact().getOrDefault(need.getKey(), BigInteger.ZERO).compareTo(need.getValue()) >= 0,
                        "Reservation below primitive prefix: " + need);
            }
            check(summary.delta().getOrDefault("GOAL", BigInteger.ZERO).compareTo(BigInteger.valueOf(amount)) >= 0,
                    "Primitive execution does not deliver the target");
            check(budget.nodes() < 50_000_000, "Retained completion lost its observed cost guard: " + budget.nodes());
            check(budget.reservedBytes() == 0, "Graph completion leaked retained ownership");
            System.out.println("Packing completion pipeline: original bounds, rows and primitive prefixes passed; work=" + budget.nodes());
        }
    }

    private static void verify(BigInteger[][] rows, BigInteger[] bounds, BigInteger[] counts) {
        for (BigInteger count : counts) check(count.signum() >= 0 && count.compareTo(BigInteger.ONE) <= 0, "Invalid Boolean bound");
        for (int r = 0; r < rows.length; r++) {
            BigInteger sum = BigInteger.ZERO;
            for (int i = 0; i < counts.length; i++) sum = sum.add(rows[r][i].multiply(counts[i]));
            check(sum.compareTo(bounds[r]) <= 0, "Invalid original row " + r);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
