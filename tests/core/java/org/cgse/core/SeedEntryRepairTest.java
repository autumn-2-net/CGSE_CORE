// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;

/** A missing seed can need an entry at another material in its selected cycle. */
public final class SeedEntryRepairTest {
    private static int verified;

    public static void main(String[] args) throws Exception {
        for (int order = 0; order < 12; order++) {
            entryScope(order);
            for (boolean preserve : new boolean[] { false, true })
                for (long need : new long[] { 2, 17, 1000 }) repairAndVerify(order, need, preserve);
        }
        retainedScan();
        boundedAdmissionAndCancellation();
        multipleRegions();
        System.out.println("Seed entries: " + verified + " exact witnesses; stock/catalog changes, exclusions, quotas, thread handoff and cancellation passed");
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }

    private static List<GraphRecipe<String>> recipes(long need) {
        return List.of(recipe("goal", Map.of("A", need), Map.of("T", 1L)),
                recipe("back", Map.of("B", 1L), Map.of("A", 1L)),
                recipe("forward", Map.of("A", 1L), Map.of("B", 1L)),
                recipe("scrap", Map.of("scrap", 1L), Map.of("B", 1L)),
                recipe("funded", Map.of("ore", 2L), Map.of("B", 2L)),
                recipe("internal", Map.of("A", 1L), Map.of("B", 1L)),
                recipe("unfunded", Map.of("absent", 1L), Map.of("B", 100L)));
    }

    private static GraphCompiler<String> compiler(int order, long need) {
        var recipes = new ArrayList<>(recipes(need));
        Collections.shuffle(recipes, new Random(order));
        var sources = new LinkedHashMap<String, List<GraphRecipe<String>>>();
        for (var recipe : recipes) for (String key : recipe.outputs().keySet())
            sources.computeIfAbsent(key, k -> new ArrayList<>()).add(recipe);
        // Force the selected graph to contain the trap, independent of catalog
        // enumeration. The actual alternative list remains fully available.
        sources.get("B").sort(Comparator.comparingInt(r -> r.id().equals("forward") ? 0 : 1));
        return new GraphCompiler<>(recipes, sources);
    }

    private static PlanningBudget budget() {
        return new PlanningBudget(0, 2_000_000, 64L << 20, () -> false, System::nanoTime);
    }

    private static GraphCompiler.Compiled<String> compile(GraphCompiler<String> compiler) {
        return compiler.compile("T", Map.of(), Set.of(), budget());
    }

    private static List<GraphSeedEntries.Entry<String>> scan(GraphCompiler<String> compiler, GraphCompiler.Compiled<String> graph,
            Set<String> missing, Map<String, Long> stock, Set<String> external, Set<String> excluded, Map<String, Set<String>> tried) {
        var budget = budget();
        try (var entries = new GraphSeedEntries<>(compiler, graph, missing, stock, external, excluded, tried, budget, 16_384)) {
            while (!entries.step()) {}
            return List.copyOf(entries.entries());
        } finally { check(budget.reservedBytes() == 0, "Entry scan leaked"); }
    }

    private static void entryScope(int order) {
        var compiler = compiler(order, 17);
        var graph = compile(compiler);
        var stock = Map.of("A", 1_000_000L, "ore", 100_000L, "scrap", 1L);
        var entries = scan(compiler, graph, Set.of("A"), stock, Set.of(), Set.of(), Map.of());
        check(entries.size() == 1 && entries.get(0).key().equals("B") && entries.get(0).recipe().id().equals("funded"),
                "Did not repair the other material's entry, or preferred tiny scrap supply: " + entries);
        check(scan(compiler, graph, Set.of("absent"), stock, Set.of(), Set.of(), Map.of()).isEmpty(), "Unrelated region repaired");
        check(scan(compiler, graph, Set.of("A"), stock, Set.of(), Set.of("funded", "scrap"), Map.of()).isEmpty(),
                "Excluded/unfunded/internal recipe used as an entry");
        check(scan(compiler, graph, Set.of("A"), stock, Set.of(), Set.of(), Map.of("B", Set.of("funded", "scrap"))).isEmpty(),
                "Already tried source was repeated");
        check(scan(compiler, graph, Set.of("A"), Map.of("ore", 1L), Set.of(), Set.of(), Map.of()).isEmpty(),
                "An incomplete first batch was considered funded");
        var external = scan(compiler, graph, Set.of("A"), Map.of(), Set.of("ore"), Set.of(), Map.of());
        check(external.size() == 1 && external.get(0).recipe().id().equals("funded"), "External supply ignored");
        check(scan(compiler, graph, Set.of("A"), Map.of(), Set.of(), Set.of(), Map.of()).isEmpty(), "Old request stock leaked");
        var changed = new GraphCompiler<>(recipes(17).stream().filter(r -> !r.id().equals("funded")).toList());
        check(scan(changed, compile(changed), Set.of("A"), Map.of("ore", 100_000L), Set.of(), Set.of(), Map.of()).isEmpty(),
                "Replaced catalog retained a removed entry");
    }

    private static void repairAndVerify(int order, long need, boolean preserve) {
        // Existing A is plentiful and makes the relaxed closed A/B loop look
        // cheaper than ore. The goal exceeds that stock, so it still needs an
        // ore entry at B while the immediate deficit is A.
        var compiler = compiler(order, 1_000_000 + need);
        var stock = Map.of("A", 1_000_000L, "ore", 4 * need, "scrap", 1L);
        var budget = budget();
        try (var ranking = GraphSourceRanking.createQuantitative(compiler, stock, Set.of(), "T", true, budget, 100_000);
             var view = new GraphStockViewWork<>(compiler, "T", 1, stock, Set.of(), Map.of(), Set.of(), preserve, true,
                     CatalystPolicy.MINIMAL, budget, System.nanoTime(), 9, () -> ranking)) {
            while (!view.step()) {}
            check(budget.diagnostics().contains("funded_seed_entries"), "Fixture bypassed the selected-cycle entry repair");
            verify(view.result(), stock, budget);
        }
        check(budget.reservedBytes() == 0, "Full entry repair leaked");
        // One funded batch is only a proposal. It cannot justify the complete
        // request when the captured stock cannot supply the required quantity.
        var insufficient = budget();
        var smallStock = Map.of("ore", 2L);
        var work = new GraphPlanningWork<>(compiler, "T", 100, smallStock, preserve, true, insufficient);
        try {
            while (!work.step()) {}
            check(!work.result().feasible(), "Funded entry was mistaken for a complete witness");
        } finally { work.close(); }
        check(insufficient.reservedBytes() == 0, "Insufficient-entry search leaked");
    }

    private static void verify(GraphPlan<String> plan, Map<String, Long> stock, PlanningBudget budget) {
        check(plan != null && plan.feasible(), "No entry witness: " + budget.diagnostics());
        PlanVerifier.verify(plan);
        PlanVerifier.verifyRuntimeInventory(plan);
        for (var entry : plan.initialExact().entrySet())
            check(entry.getValue().compareTo(BigInteger.valueOf(stock.getOrDefault(entry.getKey(), 0L))) <= 0, "Stock overdraw");
        try (var verification = new PlanVerification<>(plan, budget)) {
            while (!verification.step()) {}
            try (var proof = new ForceCraftProof<>(plan, verification, Map.of(), budget)) {
                while (!proof.step()) {}
                check(proof.proved(), "Unproductive witness");
            }
        }
        verified++;
    }

    private static void retainedScan() throws Exception {
        var compiler = compiler(0, 17);
        var graph = compile(compiler);
        long expectedWork = -1;
        var pool = Executors.newFixedThreadPool(2);
        try {
            for (boolean migrate : new boolean[] { false, true }) {
                var budget = budget();
                try (var scan = new GraphSeedEntries<>(compiler, graph, Set.of("A"), Map.of("ore", 1000L),
                        Set.of(), Set.of(), Map.of(), budget, 16_384)) {
                    boolean complete;
                    do {
                        long before = budget.searchWork();
                        complete = migrate ? pool.submit(scan::step).get() : scan.step();
                        check(budget.searchWork() - before <= 1, "Entry scan hid an unbounded loop inside a step");
                    } while (!complete);
                    check(scan.entries().size() == 1 && scan.entries().get(0).recipe().id().equals("funded"), "Paused scan lost its entry");
                    if (expectedWork < 0) expectedWork = budget.searchWork();
                    else check(expectedWork == budget.searchWork(), "Thread handoff restarted or mischarged the scan");
                }
                check(budget.reservedBytes() == 0, "Retained scan leaked");
            }
        } finally { pool.shutdownNow(); }
    }

    private static void boundedAdmissionAndCancellation() {
        var compiler = compiler(0, 17);
        var graph = compile(compiler);
        for (long memory : new long[] { 1, 1024, 1200, 4096 }) {
            for (long allowance : new long[] { 0, 1, 8, 32, 16_384 }) {
                var budget = new PlanningBudget(0, 100_000, memory, () -> false, System::nanoTime);
                try (var scan = new GraphSeedEntries<>(compiler, graph, Set.of("A"), Map.of("ore", 1000L),
                        Set.of(), Set.of(), Map.of(), budget, allowance)) {
                    while (!scan.step()) {}
                    check(budget.searchWork() <= allowance, "Local scan allowance overflowed");
                    for (var entry : scan.entries()) check(entry.recipe().id().equals("funded"), "Partial scan manufactured an entry");
                    check(budget.failureDetail().isEmpty(), "Optional admission left a terminal failure");
                }
                check(budget.reservedBytes() == 0, "Memory/local quota cutoff leaked");
            }
        }
        for (int stop = 0; stop < 70; stop++) {
            var budget = budget();
            var scan = new GraphSeedEntries<>(compiler, graph, Set.of("A"), Map.of("ore", 1000L),
                    Set.of(), Set.of(), Map.of(), budget, 16_384);
            for (int i = 0; i < stop && !scan.step(); i++) {}
            budget.cancel();
            try {
                scan.step();
                throw new AssertionError("Cancelled scan continued");
            } catch (CancellationException expected) {
            } finally { scan.close(); scan.close(); }
            check(budget.reservedBytes() == 0, "Cancelled/closed scan leaked at " + stop);
        }
    }

    private static void multipleRegions() {
        var recipes = new ArrayList<GraphRecipe<String>>();
        var demand = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 20; i++) {
            demand.put("A" + i, 1L);
            recipes.add(recipe("back" + i, Map.of("B" + i, 1L), Map.of("A" + i, 1L)));
            recipes.add(recipe("cycle" + i, Map.of("A" + i, 1L), Map.of("B" + i, 1L)));
            recipes.add(recipe("entry" + i, Map.of("ore", 1L), Map.of("B" + i, 1L)));
        }
        recipes.add(recipe("goal", demand, Map.of("T", 1L)));
        var compiler = new GraphCompiler<>(recipes);
        var entries = scan(compiler, compile(compiler), demand.keySet(), Map.of("ore", 1000L), Set.of(), Set.of(), Map.of());
        check(entries.size() == 20 && entries.stream().map(GraphSeedEntries.Entry::key).distinct().count() == 20,
                "Completed regions were dropped or confused");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
