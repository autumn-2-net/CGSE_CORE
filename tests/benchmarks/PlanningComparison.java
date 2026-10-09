// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;

/** Runs against either revision. The aggregate cap is a harness cutoff, never an UNSAT result. */
public final class PlanningComparison {
    public static void main(String[] args) throws Exception {
        long millis = Long.parseLong(args[1]), work = Long.parseLong(args[2]), bytes = Long.parseLong(args[3]);
        String mode = args[4];
        if (!Set.of("accounts", "total", "wall").contains(mode)) throw new IllegalArgumentException(mode);
        int warmup = args.length > 5 ? Integer.parseInt(args[5]) : 0;
        int repetitions = args.length > 6 ? Integer.parseInt(args[6]) : 1;
        if (warmup < 0 || repetitions < 1) throw new IllegalArgumentException("Invalid repetitions");
        System.out.println("name\tcache\tmode\tresult\tsearch\tcompilation\ttotal\tprepare_ns\tsolve_ns\tpeak_reserved_bytes\tfirst_verified_search\tfirst_verified_compilation\tfirst_verified_ns\torigins\tlimit_detail\tcache_estimated_bytes\tcompiler_count\tactive_searches\tcache_stats");
        for (int repetition = -warmup; repetition < repetitions; repetition++)
            run(args[0], millis, work, bytes, mode, repetition >= 0);
    }

    private static void run(String fixture, long millis, long work, long bytes, String mode, boolean report) throws Exception {
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(Path.of(fixture))))) {
            for (int remaining = input.readInt(); remaining > 0; remaining--) {
                String name = GraphFixtureRegression.string(input), target = GraphFixtureRegression.string(input);
                long amount = input.readLong();
                String truth = GraphFixtureRegression.string(input);
                var stock = GraphFixtureRegression.amounts(input);
                List<GraphRecipe<String>> recipes = new ArrayList<>();
                for (int n = input.readInt(); n > 0; n--) {
                    String id = GraphFixtureRegression.string(input);
                    var in = GraphFixtureRegression.amounts(input);
                    var out = GraphFixtureRegression.amounts(input);
                    recipes.add(new GraphRecipe<>(id, id, in.entrySet().stream()
                            .map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out));
                }
                GraphCompiler<String> compiler = null;
                for (String cache : List.of("cold", "warm", "replaced")) {
                    long start = System.nanoTime();
                    if (!cache.equals("warm")) {
                        // A new snapshot with equivalent, newly decoded pattern objects.
                        if (cache.equals("replaced")) recipes = recipes.stream()
                                .map(r -> new GraphRecipe<>(r.id(), r.binding(), r.slots(), r.outputs())).toList();
                        compiler = new GraphCompiler<>(recipes);
                    }
                    long prepare = System.nanoTime() - start;
                    var budget = new PlanningBudget(millis, mode.equals("wall") ? Long.MAX_VALUE : work, bytes, () -> false, System::nanoTime);
                    budget.enableMetrics();
                    var planning = new GraphPlanningWork<>(compiler, target, amount, stock, false, true, budget);
                    GraphPlan<String> plan = null;
                    boolean capped = false;
                    start = System.nanoTime();
                    try {
                        while (true) {
                            if (mode.equals("total") && budget.nodes() >= work) { capped = true; break; }
                            boolean done = planning.step();
                            // One atomic step may overshoot. Report its actual cost, but do
                            // not count a witness obtained beyond the equal aggregate cap.
                            if (mode.equals("total") && budget.nodes() > work) { capped = true; break; }
                            if (done) { plan = planning.result(); break; }
                        }
                    } finally { planning.close(); }
                    long elapsed = System.nanoTime() - start;
                    if (budget.reservedBytes() != 0) throw new AssertionError(name + ": leaked " + budget.reservedBytes());
                    if (plan != null) validate(name, truth, plan, stock, recipes);
                    String origins = "unavailable";
                    long firstSearch = -1, firstCompile = -1, firstNanos = -1;
                    try {
                        Object metrics = PlanningBudget.class.getMethod("candidates").invoke(budget);
                        var type = metrics.getClass();
                        origins = type.getMethod("origins").invoke(metrics).toString();
                        firstSearch = (long) type.getMethod("firstVerifiedSearchWork").invoke(metrics);
                        firstCompile = (long) type.getMethod("firstVerifiedCompilationWork").invoke(metrics);
                        firstNanos = (long) type.getMethod("firstVerifiedElapsedNanos").invoke(metrics);
                    } catch (NoSuchMethodException olderRevision) {
                        // Keep unavailable distinct from zero or final-return cost.
                    }
                    long cacheBytes = -1, compilers = -1, active = -1;
                    String cacheStats = "unavailable";
                    try {
                        Object metrics = GraphCompiler.class.getMethod("cacheMetrics").invoke(compiler);
                        var type = metrics.getClass();
                        cacheBytes = (long) type.getMethod("estimatedBytes").invoke(metrics);
                        compilers = (int) type.getMethod("compilers").invoke(metrics);
                        active = (long) type.getMethod("activeSearches").invoke(metrics);
                        cacheStats = type.getMethod("caches").invoke(metrics).toString();
                        if (active != 0) throw new AssertionError("Closed request remains active: " + active);
                    } catch (NoSuchMethodException olderRevision) {
                        // Older revisions did not expose persistent cache estimates.
                    }
                    if (report) System.out.println(String.join("\t", name, cache, mode, capped ? "HARNESS_TOTAL_LIMIT" : plan.result().name(),
                            Long.toString(budget.searchWork()), Long.toString(budget.compilationWork()), Long.toString(budget.nodes()),
                            Long.toString(prepare), Long.toString(elapsed), Long.toString(budget.peakBytes()),
                            Long.toString(firstSearch), Long.toString(firstCompile), Long.toString(firstNanos), clean(origins), clean(budget.failureDetail()),
                            Long.toString(cacheBytes), Long.toString(compilers), Long.toString(active), clean(cacheStats)));
                }
            }
            if (input.read() != -1) throw new AssertionError("Trailing fixture bytes");
        }
    }

    private static String clean(String text) { return text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' '); }

    private static void validate(String name, String truth, GraphPlan<String> plan, Map<String, Long> stock, List<GraphRecipe<String>> recipes) {
        boolean negative = Set.of(GraphPlan.Result.MISSING_INPUT, GraphPlan.Result.MISSING_SEED, GraphPlan.Result.INFEASIBLE).contains(plan.result());
        if (truth.equals("SAT") && negative || truth.equals("UNSAT") && plan.feasible()) throw new AssertionError(name + ": false conclusion " + plan.result());
        if (!plan.feasible()) return;
        Map<String, GraphRecipe<String>> primitives = new LinkedHashMap<>();
        recipes.forEach(recipe -> primitives.put(recipe.id(), recipe));
        var summary = GraphFixtureRegression.summary(plan.steps(), primitives, new IdentityHashMap<>());
        for (var need : summary.need().entrySet()) {
            BigInteger available = BigInteger.valueOf(stock.getOrDefault(need.getKey(), 0L));
            if (available.compareTo(need.getValue()) < 0 || plan.initialExact().getOrDefault(need.getKey(), BigInteger.ZERO).compareTo(need.getValue()) < 0)
                throw new AssertionError(name + ": unfunded primitive prefix " + need.getKey());
        }
        BigInteger end = BigInteger.valueOf(stock.getOrDefault(plan.target(), 0L)).add(summary.delta().getOrDefault(plan.target(), BigInteger.ZERO));
        if (end.compareTo(BigInteger.valueOf(plan.amount())) < 0) throw new AssertionError(name + ": undelivered target");
        for (var seed : plan.seeds().entrySet()) if (BigInteger.valueOf(stock.getOrDefault(seed.getKey(), 0L))
                .add(summary.delta().getOrDefault(seed.getKey(), BigInteger.ZERO)).compareTo(BigInteger.valueOf(seed.getValue())) < 0)
            throw new AssertionError(name + ": seed not returned");
    }
}
