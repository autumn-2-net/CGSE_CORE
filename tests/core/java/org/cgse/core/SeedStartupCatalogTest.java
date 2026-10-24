// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

/** Catalog reuse must never reuse an inventory decision or hide a productive return. */
public final class SeedStartupCatalogTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        reuseAndCancellation();
        concurrentRequests();
        neutralAndProductiveReturns();
        finiteStateTransitions();
        partialScouts();
        loanOrigins();
        loanHandoff();
        System.out.println("SeedStartupCatalogTest PASS: " + checks + " checks");
    }

    private static List<GraphRecipe<String>> catalog(int width) {
        var result = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < width; i++) result.add(r("unrelated" + i, Map.of("ore" + i, 1L), Map.of("part" + i, 1L)));
        result.add(r("grow", Map.of("A", 1L, "ore", 1L), Map.of("A", 2L)));
        result.add(r("finish", Map.of("A", 3L), Map.of("T", 1L)));
        return result;
    }

    private static void reuseAndCancellation() {
        var recipes = catalog(4096);
        // The priority index is deliberately empty. Startup checks must still
        // see every physical recipe, independently of source ordering.
        var compiler = new GraphCompiler<>(recipes, Map.of());
        var word = summary(recipes, new PlanStep.Batch("finish", 1));
        var budget = budget();
        var partial = new SeedStartupGuard<>(compiler.catalog(), word, Map.of("A", 3L), Map.of(), Set.of(), "T", 1, budget);
        check(!partial.step(), "fixture did not interrupt index construction");
        partial.close(); partial.close();
        check(budget.reservedBytes() == 0, "cancelled index leaked");
        check(compiler.cacheMetrics().caches().get("startup_index").entries() == 0, "partial index was published");
        long cold = checkGuard(compiler, word, Map.of("A", 3L), Set.of(), Map.of("A", 1L));
        long warm = checkGuard(compiler, word, Map.of("A", 4L), Set.of(), Map.of());
        check(warm * 4 < cold, "warm check rescanned the unrelated catalog");
        checkGuard(compiler, word, Map.of("A", 3L), Set.of(), Map.of("A", 1L));
        checkGuard(compiler, word, Map.of("A", 3L), Set.of("grow"), Map.of());
        checkGuard(compiler, word, Map.of("A", 3L), Set.of(), Map.of("A", 1L));
        var stats = compiler.cacheMetrics().caches().get("startup_index");
        check(stats.entries() == 1 && stats.estimatedBytes() > 0 && stats.estimatedBytes() <= 16L << 20,
                "retained structural index is absent or unbounded");
        var replacement = new GraphCompiler<>(List.of(recipes.get(recipes.size() - 1)));
        checkGuard(replacement, word, Map.of("A", 3L), Set.of(), Map.of());
    }

    private static void concurrentRequests() throws Exception {
        var recipes = catalog(512); var compiler = new GraphCompiler<>(recipes);
        var word = summary(recipes, new PlanStep.Batch("finish", 1));
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(4);
        var futures = new ArrayList<Future<?>>();
        try {
            for (int worker = 0; worker < 4; worker++) {
                int offset = worker;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int repeat = 0; repeat < 12; repeat++) {
                        boolean spare = (repeat + offset) % 2 == 0;
                        var b = budget();
                        try (var guard = new SeedStartupGuard<>(compiler.catalog(), word,
                                Map.of("A", spare ? 4L : 3L), Map.of(), Set.of(), "T", 1, b)) {
                            while (!guard.step()) {}
                            if (!guard.lost().equals(spare ? Map.of() : Map.of("A", 1L)))
                                throw new AssertionError("another request's inventory escaped through the cache");
                        }
                        if (b.reservedBytes() != 0) throw new AssertionError("concurrent guard leaked");
                    }
                    return null;
                }));
            }
            start.countDown(); for (var future : futures) future.get(30, TimeUnit.SECONDS);
            checks += 96;
        } finally { pool.shutdownNow(); }
    }

    private static void neutralAndProductiveReturns() {
        for (int order = 0; order < 8; order++) {
            var recipes = new ArrayList<>(List.of(r("forward", Map.of("A", 1L), Map.of("B", 1L)),
                    r("back", Map.of("B", 1L), Map.of("A", 1L)), r("finish", Map.of("A", 1L), Map.of("T", 1L)),
                    r("tap", Map.of("A", 1L), Map.of("A", 1L, "P", 1L))));
            Collections.shuffle(recipes, new Random(order));
            var compiler = new GraphCompiler<>(recipes);
            var word = summary(recipes, new PlanStep.Batch("finish", 1));
            checkGuard(compiler, word, Map.of("A", 1L), Set.of(), Map.of("A", 1L));
            checkGuard(compiler, word, Map.of("A", 1L), Set.of("tap"), Map.of());
        }
    }

    private static void finiteStateTransitions() {
        var recipes = new ArrayList<>(List.of(r("start", Map.of("T", 1L), Map.of("C", 1L)),
                r("zero", Map.of("C", 1L, "Z", 1L), Map.of("T", 1L, "O", 1L)),
                r("one", Map.of("C", 1L, "O", 1L), Map.of("H", 1L, "Z", 1L)),
                r("halt", Map.of("H", 1L), Map.of("G", 1L))));
        var word = new PlanStep.Sequence(List.of(new PlanStep.Batch("start", 1), new PlanStep.Batch("zero", 1),
                new PlanStep.Batch("start", 1), new PlanStep.Batch("one", 1), new PlanStep.Batch("halt", 1)));
        var summary = summary(recipes, word);
        // T+C+Z+O never increases; producing H spends one of these tokens.
        // This finite computation has no renewable product to preserve.
        check(summary.delta("T").equals(BigInteger.ONE.negate()) && summary.delta("Z").signum() == 0,
                "finite control fixture changed");
        var b = budget();
        try (var guard = new SeedStartupGuard<>(new GraphCompiler<>(recipes).catalog(), summary,
                Map.of("T", 1L, "Z", 1L), Map.of("Z", 1L), Set.of(), "G", 1, b)) {
            while (!guard.step()) {}
            check(guard.lost().isEmpty(), "finite state machine was mistaken for renewable production");
        }
        recipes.add(r("productive", Map.of("T", 1L), Map.of("T", 1L, "P", 1L)));
        b = budget();
        try (var guard = new SeedStartupGuard<>(new GraphCompiler<>(recipes).catalog(), summary,
                Map.of("T", 1L, "Z", 1L), Map.of("Z", 1L), Set.of(), "G", 1, b)) {
            while (!guard.step()) {}
            check(guard.lost().equals(Map.of("T", 1L)), "finite certificate hid an unused productive recipe");
        }
        check(b.reservedBytes() == 0, "state certificate leaked");
    }

    private static void partialScouts() {
        var recipes = List.of(r("grow", Map.of("A", 1L, "ore", 1L), Map.of("A", 2L)),
                r("part", Map.of("feed", 1L), Map.of("X", 1L)),
                r("finish", Map.of("A", 3L, "X", 1L), Map.of("T", 1L)));
        // One deficit initially hides the exhausted A token. Once X is funded,
        // the same private scout must check the full catalog and restore A.
        for (long a : new long[]{1, 3, 4}) {
            var b = budget();
            var w = new GraphPlanningWork<>(new GraphCompiler<>(recipes), "T", 1,
                    Map.of("A", a, "ore", 10L, "feed", 1L), true, true, b).catalysts(CatalystPolicy.MINIMAL);
            try {
                while (!w.step()) {}
                var plan = w.result(); check(plan.feasible(), "deferred scout failed to close its existing deficit");
                var delta = summary(new ArrayList<>(plan.recipes().values()), plan.steps()).delta("A");
                check(BigInteger.valueOf(a).add(delta).signum() > 0, "deferred scout published a lost seed");
                PlanVerifier.verifyRuntimeInventory(plan);
            } finally { w.close(); }
            check(b.reservedBytes() == 0, "scout leaked");
        }
    }

    private static void loanOrigins() {
        var recipes = catalog(0); var compiler = new GraphCompiler<>(recipes);
        var index = new LinkedHashMap<String, GraphRecipe<String>>(); recipes.forEach(r -> index.put(r.id(), r));
        for (long provisional : new long[]{0, 4}) {
            var b = budget(); var graph = compiler.compile("T", Map.of(), Set.of(), b);
            var seeds = provisional == 0 ? Map.<String, Long>of() : Map.of("A", provisional);
            try (var assembly = new PlanAssembly<>("T", 1, true, new PlanStep.Batch("finish", 1), index,
                    seeds, Map.of("A", 3L), Set.of(), graph, b, System.nanoTime(), CatalystPolicy.MINIMAL)
                    .startupCatalog(compiler.catalog(), Set.of())) {
                while (!assembly.step()) {}
                check(!assembly.result().feasible(), "unfunded retained boundary became executable");
                check(assembly.startupLoans().equals(provisional == 0 ? Map.of("A", 1L) : Map.of()),
                        "a working catalyst was reported as a newly discovered startup loan");
            }
            check(b.reservedBytes() == 0, "loan provenance check leaked");
        }
    }

    private static void loanHandoff() {
        var compiler = new GraphCompiler<>(catalog(0));
        var b = budget();
        // The stock projection omits grow; the full validation catalog must
        // still report its startup reserve to the next refinement round.
        var graph = compiler.compile("T", Map.of(), Set.of("grow"), b);
        var stock = Map.of("A", 3L, "ore", 30L);
        try (var solve = new GraphSolve<>(graph, "T", 1, stock, Set.of(), Map.of(),
                true, true, b, System.nanoTime(), CatalystPolicy.MINIMAL, stock)
                .startupCatalog(compiler.catalog(), Set.of())) {
            while (!solve.step()) {}
            check(solve.result().result() == GraphPlan.Result.MISSING_SEED, "lost startup was published as funded");
            check(solve.startupLoans().equals(Map.of("A", 1L)), "assembly cleanup erased the refinement loan");
        }
        check(b.reservedBytes() == 0, "loan handoff leaked");
    }

    private static long checkGuard(GraphCompiler<String> compiler, SequenceSummary<String> summary,
                                   Map<String, Long> stock, Set<String> excluded, Map<String, Long> expected) {
        var b = budget();
        var guard = new SeedStartupGuard<>(compiler.catalog(), excluded, summary, stock, Map.of(), Set.of(), "T", 1, b);
        try {
            while (true) {
                long before = b.nodes(); boolean done = guard.step();
                check(b.nodes() - before <= 50, "guard monopolized a continuation");
                if (done) break;
            }
            check(guard.lost().equals(expected), "incorrect startup boundary: " + guard.lost() + " != " + expected);
        } finally { guard.close(); guard.close(); }
        check(b.reservedBytes() == 0, "guard workspace leaked");
        return b.nodes();
    }

    private static SequenceSummary<String> summary(List<GraphRecipe<String>> recipes, PlanStep word) {
        var index = new LinkedHashMap<String, GraphRecipe<String>>(); recipes.forEach(r -> index.put(r.id(), r));
        return SequenceSummary.of(word, index);
    }
    private static GraphRecipe<String> r(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        return new GraphRecipe<>(id, id, inputs.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), outputs);
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 2_000_000, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
