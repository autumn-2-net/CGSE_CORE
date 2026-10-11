// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

/** Share exact numeric structure across targets; every request rebuilds its RHS and column mapping. */
public final class CountRowReuseTest {
    public static void main(String[] args) throws Exception {
        immutable();
        collisions();
        semantics();
        denseOverlap();
        var recipes = recipes();
        var compiler = new GraphCompiler<>(recipes);
        snapshot(compiler, "target0", 1, 11, Set.of(), Set.of());
        var first = snapshot(compiler, "target1", 3, 17, Set.of(), Set.of());
        var second = snapshot(compiler, "target2", 7, 31, Set.of(), Set.of());
        for (int i = 0; i < 8; i++) {
            String key = "ore" + i;
            check(first.get(key).terms() == second.get(key).terms(), "Common row was copied for another target");
            check(!first.get(key).upper().equals(second.get(key).upper()), "Inventory RHS leaked across targets");
        }
        var cached = snapshot(compiler, "target2", 12, 41, Set.of(), Set.of());
        check(cached.get("ore0").terms() == second.get("ore0").terms(), "Whole-catalog RHS overlay recopied immutable terms");

        int checked = 0;
        var random = new Random(2026101118L);
        for (int sample = 0; sample < 80; sample++) {
            String target = "target" + random.nextInt(8);
            int amount = 1 + random.nextInt(20), ore = random.nextInt(80);
            Set<String> excluded = sample % 3 == 0 ? Set.of("source" + random.nextInt(12)) : Set.of();
            Set<String> external = sample % 4 == 0 ? Set.of("ore" + random.nextInt(8)) : Set.of();
            same(snapshot(new GraphCompiler<>(recipes), target, amount, ore, external, excluded),
                    snapshot(compiler, target, amount, ore, external, excluded));
            checked++;
        }
        var changed = new ArrayList<>(recipes);
        changed.add(new GraphRecipe<>("extra", "extra", List.of(new GraphRecipe.Slot<>("fuel", 3)), Map.of("ore0", 2L)));
        Collections.shuffle(changed, random);
        var updated = new GraphCompiler<>(changed);
        snapshot(updated, "target0", 1, 11, Set.of(), Set.of());
        snapshot(updated, "target1", 2, 11, Set.of(), Set.of());
        same(snapshot(new GraphCompiler<>(changed), "target2", 5, 23, Set.of(), Set.of()),
                snapshot(updated, "target2", 5, 23, Set.of(), Set.of()));

        var workers = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 32; i++) {
                int ordinal = i;
                futures.add(workers.submit(() -> {
                    String target = "target" + ordinal % 8;
                    same(snapshot(new GraphCompiler<>(recipes), target, ordinal + 1, ordinal * 3, Set.of(), Set.of()),
                            snapshot(compiler, target, ordinal + 1, ordinal * 3, Set.of(), Set.of()));
                }));
            }
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); }
        var metrics = compiler.cacheMetrics().caches().get("count_overlap");
        check(metrics.hits() > 0 && metrics.estimatedBytes() == 0, "Missing reuse of existing retained catalogs");
        var catalogs = compiler.cacheMetrics().caches().get("count_catalog");
        check(catalogs.entries() <= 32 && catalogs.weight() <= 32768 && catalogs.evictions() > 0,
                "Overlap reuse changed bounded catalog residency");
        var stopped = budget();
        stopped.cancel();
        boolean cancelled = false;
        try (var ignored = RecipeCountModel.create(compiler, "target0", 1, Map.of(), Map.of(), Set.of(), Set.of(), false, stopped)) {}
        catch (CancellationException expected) { cancelled = true; }
        check(cancelled && stopped.reservedBytes() == 0, "Cached model ignored cancellation or leaked memory");
        System.out.println("Count row reuse: " + checked + " changed requests, 32 concurrent requests; hits=" + metrics.hits() +
                "; immutable overlays, ordering, new producers and bounded residency passed");
    }

    private static void immutable() {
        var mutable = new HashMap<Integer, BigInteger>();
        mutable.put(4, BigInteger.TWO); mutable.put(0, BigInteger.ONE);
        var row = new ExactLinearProgram.Constraint(mutable, BigInteger.TEN);
        mutable.put(4, BigInteger.TEN);
        check(row.terms().get(4).equals(BigInteger.TWO), "Caller map was retained");
        check(new ArrayList<>(row.terms().keySet()).equals(List.of(0, 4)), "Coordinate order changed");
        var overlay = new ExactLinearProgram.Constraint(row.terms(), BigInteger.valueOf(-7));
        check(overlay.terms() == row.terms() && row.upper().equals(BigInteger.TEN), "RHS overlay copied or mutated its source");
        boolean refused = false;
        try { overlay.terms().entrySet().iterator().next().setValue(BigInteger.ZERO); }
        catch (UnsupportedOperationException expected) { refused = true; }
        check(refused, "A published coefficient map is mutable");
    }

    private static void collisions() {
        var a = new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE, 1, BigInteger.TWO), BigInteger.ZERO);
        var b = new ExactLinearProgram.Constraint(Map.of(0, BigInteger.TWO, 1, BigInteger.valueOf(3)), BigInteger.TEN);
        check(a.terms().hashCode() == b.terms().hashCode() && !a.terms().equals(b.terms()), "No hash collision fixture");
    }

    private static void semantics() {
        // Equal resource hashes, exact read arcs and per-push configuration
        // charges must survive reuse and an external-supply overlay change.
        check("Aa".hashCode() == "BB".hashCode(), "Resource collision fixture");
        var recipes = new ArrayList<>(recipes());
        for (int i = 0; i < 12; i++) {
            var old = recipes.get(i);
            var slots = new ArrayList<>(old.slots());
            slots.add(new GraphRecipe.Slot<>("Aa", 1L, -1, true, true));
            slots.add(new GraphRecipe.Slot<>("BB", 3L, -1, true));
            recipes.set(i, new GraphRecipe<>(old.id(), old.binding(), slots,
                    Map.of("m", 1L + i % 3, "Aa", 1L, "BB", 1L)));
        }
        var compiler = new GraphCompiler<>(recipes);
        snapshot(compiler, "target0", 1, 10, Set.of(), Set.of());
        var donor = snapshot(compiler, "target1", 2, 12, Set.of("ore0", "Aa"), Set.of());
        var current = snapshot(compiler, "target2", 5, 21, Set.of(), Set.of());
        same(snapshot(new GraphCompiler<>(recipes), "target2", 5, 21, Set.of(), Set.of()), current);
        check(current.get("ore1").terms() == donor.get("ore1").terms(), "Unaffected material was rebuilt");
        check(!current.get("ore0").terms().isEmpty(), "Previously external material lost its constraint");
        check(current.get("Aa").terms().isEmpty() && !current.get("BB").terms().isEmpty(),
                "Resource hash or read-arc semantics were confused");
    }

    private static void denseOverlap() {
        var recipes = recipes(96, 64);
        var compiler = new GraphCompiler<>(recipes);
        long coldWork = 0, warmWork = 0;
        for (int i = 0; i < 18; i++) {
            var a = budget(); var b = budget();
            String target = "target" + i % 8;
            var stock = Map.of("ore0", 100L + i, "ore1", 999L - i);
            try (var cold = RecipeCountModel.create(new GraphCompiler<>(recipes), target, i + 1, stock,
                         Map.of(), Set.of(), Set.of(), false, a);
                 var warm = RecipeCountModel.create(compiler, target, i + 1, stock,
                         Map.of(), Set.of(), Set.of(), false, b)) {
                check(cold.constraints.equals(warm.constraints) && cold.recipes.equals(warm.recipes) &&
                        cold.rowKeys.equals(warm.rowKeys), "Dense overlap changed the count model");
                if (i >= 2) { coldWork += a.nodes(); warmWork += b.nodes(); }
            }
            check(a.reservedBytes() == 0 && b.reservedBytes() == 0, "Dense overlay leaked request memory");
        }
        check(warmWork * 4 < coldWork, "Overlap rebuilt the common dense matrix: " + warmWork + " / " + coldWork);
        check(compiler.cacheMetrics().caches().get("count_catalog").evictions() > 0,
                "Fixture no longer exceeds the whole-model cache capacity");
    }

    private static Map<String, ExactLinearProgram.Constraint> snapshot(GraphCompiler<String> compiler, String target,
            int amount, int ore, Set<String> external, Set<String> excluded) {
        var stock = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 8; i++) stock.put("ore" + i, (long) ore + i);
        stock.put("m", (long) ore / 2);
        var budget = budget();
        var rows = new LinkedHashMap<String, ExactLinearProgram.Constraint>();
        try (var model = RecipeCountModel.create(compiler, target, amount, stock, Map.of(), external, excluded, false, budget)) {
            check(model != null, "Count model declined");
            for (int i = 0; i < model.rowKeys.size(); i++) rows.put(model.rowKeys.get(i), model.constraints.get(i));
            // Also compare the entire ordered recipe coordinate system.
            for (int i = 0; i < model.recipes.size(); i++) rows.put("column:" + model.recipes.get(i).id(),
                    new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), BigInteger.ZERO));
        }
        check(budget.reservedBytes() == 0, "Request memory leak");
        return rows;
    }

    private static void same(Map<String, ExactLinearProgram.Constraint> expected, Map<String, ExactLinearProgram.Constraint> actual) {
        check(new ArrayList<>(expected.keySet()).equals(new ArrayList<>(actual.keySet())) && expected.equals(actual),
                "Cross-target reuse changed rows, RHS, traversal or recipe coordinates");
    }

    private static List<GraphRecipe<String>> recipes() {
        return recipes(12, 8);
    }

    private static List<GraphRecipe<String>> recipes(int sources, int resources) {
        var result = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < sources; i++) {
            var slots = new ArrayList<GraphRecipe.Slot<String>>();
            for (int j = 0; j < resources; j++) slots.add(new GraphRecipe.Slot<>("ore" + j, 1 + (i + 3 * j) % 7));
            result.add(new GraphRecipe<>("source" + i, "source" + i, slots, Map.of("m", 1L + i % 3)));
        }
        for (int i = 0; i < 8; i++) result.add(new GraphRecipe<>("finish" + i, "finish" + i,
                List.of(new GraphRecipe.Slot<>("m", 2)), Map.of("target" + i, 1L)));
        return result;
    }

    private static PlanningBudget budget() {
        return new PlanningBudget(0, 2_000_000, 64L << 20, () -> false, System::nanoTime);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
