// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** A completed refill is an incumbent across optional catalyst-policy retries. */
public final class CatalystRefillRetentionTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        if (args.length > 0) repeatedRequests(args[0]);
        else { comparison(); repeatedRequests(""); retryLimits(); cancellation(); fundedRetry(); }
        System.out.println("CatalystRefillRetentionTest PASS: " + checks + " checks");
    }

    private static void repeatedRequests(String mode) throws Exception {
        // A -> chain -> A forms a cycle, but the starting A is already stocked.
        // The complete word needs one raw only. A later policy's smaller local
        // quality allowance used to publish the last link as another shortage.
        for (int length : new int[] {2, 5, 12, 25}) {
            if (mode.equals("overwrite") && length != 12) continue;
            var recipes = new ArrayList<GraphRecipe<String>>();
            recipes.add(recipe("start", Map.of("A", 1L, "raw", 1L), Map.of("A", 1L, "b0", 1L)));
            for (int i = 1; i < length; i++) recipes.add(recipe("link" + i, Map.of("b" + (i - 1), 1L), Map.of("b" + i, 1L)));
            recipes.add(recipe("finish", Map.of("b" + (length - 1), 1L), Map.of("A", 1L, "G", 1L)));
            long[] caps = switch (length) {
                case 2 -> new long[] {2000, 3000, 80000};
                case 5 -> new long[] {4000, 6000, 80000};
                case 12 -> new long[] {5000, 9500, 10500, 80000};
                default -> new long[] {10000, 18500, 21500, 80000};
            };
            for (int workers : new int[] {1, 4}) for (long cap : caps) {
                if (mode.equals("overwrite") && cap != 9500) continue;
                var compiler = new GraphCompiler<>(recipes);
                try (var scheduler = new PlanningScheduler(workers, 8, 32, 1_000_000)) {
                    for (int repetition = 0; repetition < 3; repetition++) {
                        var budget = budget(cap);
                        var work = new RequestPlanningWork<>(compiler, "G", 1, Map.of("A", 1L), Set.of(), null,
                                false, false, "REPORT_MISSING_ITEMS", new CatalystPolicy(16, 10), true, false,
                                budget, () -> false, report -> { throw new AssertionError("lost refill reached fallback"); });
                        var plan = scheduler.submit(work, budget).get(20, TimeUnit.SECONDS);
                        check(plan.missingExact().equals(Map.of("raw", BigInteger.ONE)),
                                "request lost base bill: length=" + length + ", cap=" + cap + ", repeat=" + repetition + ": " + plan.missingExact());
                        var stock = new HashMap<>(Map.of("A", BigInteger.ONE, "raw", BigInteger.ONE));
                        execute(plan.steps(), plan.recipes(), stock);
                        check(stock.getOrDefault("G", BigInteger.ZERO).equals(BigInteger.ONE), "funded word failed delivery");
                        for (var seed : plan.seeds().entrySet()) check(stock.getOrDefault(seed.getKey(), BigInteger.ZERO)
                                .compareTo(BigInteger.valueOf(seed.getValue())) >= 0, "funded word lost final seed");
                        check(budget.reservedBytes() == 0 && compiler.cacheMetrics().activeSearches() == 0, "request leaked state");
                    }
                }
            }
        }
    }

    private static void execute(PlanStep step, Map<String, GraphRecipe<String>> recipes, Map<String, BigInteger> stock) {
        if (step instanceof PlanStep.Sequence sequence) {
            for (var child : sequence.children()) execute(child, recipes, stock);
        } else if (step instanceof PlanStep.Repeat repeat) {
            check(repeat.times() < 1000, "unexpected large test repeat");
            for (long i = 0; i < repeat.times(); i++) execute(repeat.body(), recipes, stock);
        } else {
            var batch = (PlanStep.Batch) step; var recipe = recipes.get(batch.recipe());
            check(batch.runs() < 1000, "unexpected large test batch");
            for (long i = 0; i < batch.runs(); i++) {
                for (var input : recipe.inputs().entrySet()) {
                    var rest = stock.getOrDefault(input.getKey(), BigInteger.ZERO).subtract(BigInteger.valueOf(input.getValue()));
                    check(rest.signum() >= 0, "unfunded primitive input: " + input.getKey()); stock.put(input.getKey(), rest);
                }
                recipe.outputs().forEach((key, count) -> stock.merge(key, BigInteger.valueOf(count), BigInteger::add));
            }
        }
    }

    private static void comparison() {
        var raw = bill(Map.of("ore", BigInteger.valueOf(96)), true);
        var cycle = bill(Map.of("intermediate", BigInteger.ONE), false);
        check(select(raw, cycle) == raw, "later cyclic loan discarded a completed raw bill");
        check(select(cycle, raw) == raw, "later raw expansion was not accepted");
        for (boolean terminal : new boolean[] {false, true}) {
            var a = bill(Map.of("A", BigInteger.ONE, "B", BigInteger.TEN), terminal);
            var b = bill(Map.of("A", BigInteger.ONE, "B", BigInteger.ONE), terminal);
            check(select(a, b) == b && select(b, a) == b, "componentwise improvement lost");
            var c = bill(Map.of("A", BigInteger.TWO), terminal);
            check(select(a, c) == c && select(c, a) == a, "incomparable item/fluid amounts changed policy priority");
            var d = bill(a.missingExact(), terminal);
            check(select(a, d) == d, "equal bills should keep the later seed/execution policy");
            BigInteger wide = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN);
            a = bill(Map.of("A", wide), terminal); b = bill(Map.of("A", wide.subtract(BigInteger.ONE)), terminal);
            check(select(a, b) == b && select(b, a) == b, "wide deficits were rounded");
        }
        var large = new LinkedHashMap<String, BigInteger>();
        for (int i = 0; i < 300; i++) large.put("raw-" + i, BigInteger.ONE);
        var a = bill(large, true); var b = bill(Map.of("cycle", BigInteger.ONE), false);
        var budget = budget(10000);
        var selection = new RefillPreviewSelection<>(a, b, budget);
        check(!selection.step() && budget.nodes() <= 64, "comparison did not yield");
        while (!selection.step()) {}
        check(selection.result() == a && selection.step(), "resumed comparison changed its publication");
        check(budget.reservedBytes() == 0, "comparison leaked workspace");
    }

    private static GraphPlan<String> select(GraphPlan<String> retained, GraphPlan<String> proposed) {
        var selection = new RefillPreviewSelection<>(retained, proposed, budget(10000));
        while (!selection.step()) {}
        return selection.result();
    }

    private static GraphPlan<String> bill(Map<String, BigInteger> missing, boolean terminal) {
        var batches = new ArrayList<PlanStep>();
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
        for (var entry : missing.entrySet()) {
            String id = "use-" + entry.getKey();
            var recipe = new GraphRecipe<>(id, id, List.of(new GraphRecipe.Slot<>(entry.getKey(), 1)), Map.of("part", 1L));
            recipes.put(id, recipe); batches.add(PlanStep.batch(id, entry.getValue()));
        }
        var finish = new GraphRecipe<>("finish", "finish", List.of(new GraphRecipe.Slot<>("part", 1)), Map.of("G", 1L));
        recipes.put("finish", finish); batches.add(new PlanStep.Batch("finish", 1));
        var plan = new GraphPlan<>("G", 1, false, new PlanStep.Sequence(batches), recipes,
                missing, Map.of(), missing, GraphPlan.Result.MISSING_INPUT, 0, 0)
                .withMissingSources(terminal ? GraphPlan.MissingSource.TERMINAL_UNDER_CURRENT_COVERAGE :
                        GraphPlan.MissingSource.CYCLE_BOUNDARY, Map.of());
        PlanVerifier.verify(new GraphPlan<>("G", 1, false, plan.steps(), recipes, missing, Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0));
        return plan;
    }

    private static void retryLimits() throws Exception {
        for (var limit : List.of(PlanningBudget.Limit.SEARCH_LIMIT, PlanningBudget.Limit.TIMEOUT, PlanningBudget.Limit.MEMORY_LIMIT)) {
            var budget = budget(1_000_000); var attempts = new AtomicInteger();
            var compiler = new GraphCompiler<>(List.of(recipe("make", Map.of("raw", 3L), Map.of("G", 1L))));
            var work = new CatalystPlanningWork<String>(new CatalystPolicy(16, 10), budget, policy -> {
                if (attempts.incrementAndGet() == 2) throw new PlanningBudget.Exhausted(limit);
                return new GraphPlanningWork<>(compiler, "G", 2, Map.of(), true, true, budget).catalysts(policy);
            });
            try (var scheduler = new PlanningScheduler(2, 8, 32, 1_000_000)) {
                var plan = scheduler.submit(work, budget).get(20, TimeUnit.SECONDS);
                check(plan.missingExact().equals(Map.of("raw", BigInteger.valueOf(6))), "retry limit lost verified refill: " + limit);
                check(!plan.feasible(), "retained refill became executable");
                check(work.limited(new PlanningBudget.Exhausted(limit)).missingExact().equals(plan.missingExact()), "publication boundary lost refill");
            }
            check(budget.reservedBytes() == 0 && compiler.cacheMetrics().activeSearches() == 0, "retry lifecycle leaked");
        }
    }

    private static void cancellation() {
        var cancel = new AtomicBoolean(); var attempts = new AtomicInteger();
        var budget = new PlanningBudget(0, 1_000_000, 64L << 20, cancel::get, System::nanoTime);
        var compiler = new GraphCompiler<>(List.of(recipe("make", Map.of("raw", 3L), Map.of("G", 1L))));
        var work = new CatalystPlanningWork<String>(new CatalystPolicy(16, 10), budget, policy -> {
            if (attempts.incrementAndGet() == 2) cancel.set(true);
            return new GraphPlanningWork<>(compiler, "G", 2, Map.of(), true, true, budget).catalysts(policy);
        });
        try {
            while (!work.step()) {}
            throw new AssertionError("cancellation was replaced by a preview");
        } catch (java.util.concurrent.CancellationException expected) {
            checks++;
        } finally { work.close(); }
        check(budget.reservedBytes() == 0, "cancelled retry leaked");
    }

    private static void fundedRetry() {
        var compiler = new GraphCompiler<>(List.of(recipe("grow", Map.of("C", 1L, "R", 1L), Map.of("C", 2L))));
        var budget = budget(1_000_000);
        var work = new CatalystPlanningWork<String>(new CatalystPolicy(16, 10), budget,
                policy -> new GraphPlanningWork<>(compiler, "C", 100, Map.of("C", 1L, "R", 105L), true, true, budget).catalysts(policy));
        try {
            while (!work.step()) {}
            var plan = work.result();
            check(plan.feasible() && plan.missingExact().isEmpty(), "earlier preview blocked an affordable policy");
            check(plan.seeds().get("C") == 6L, "optional parallel acceleration changed");
            PlanVerifier.verify(plan);
            check(work.limited(new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT)) == plan, "completed result lost at limit");
        } finally { work.close(); }
        check(budget.reservedBytes() == 0, "funded retry leaked");
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> input, Map<String, Long> output) {
        return new GraphRecipe<>(id, id, input.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), output);
    }
    private static PlanningBudget budget(long work) { return new PlanningBudget(0, work, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
}
