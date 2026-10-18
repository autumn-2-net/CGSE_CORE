// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Concrete cyclic prefixes, independent primitive replay and incumbent retention. */
public final class RefillSupplySearchTest {
    private static int checks;
    private static final BigInteger ZERO = BigInteger.ZERO;
    private static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) { return MissingMaterialExpansionTest.recipe(id, in, out); }
    private record Fixture(List<GraphRecipe<String>> recipes, GraphPlan<String> original) {}
    private static Fixture fixture(Map<String, Long> stock, long seed, boolean joint, int length) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        recipes.add(r("make", Map.of("A", 1L, "feed", 1L), joint ? Map.of("B", 1L, "joint", 1L) : Map.of("B", 1L)));
        for (int i = 0; i < length - 2; i++) recipes.add(r("bridge" + i, Map.of(i == 0 ? "B" : "m" + i, 1L), Map.of("m" + (i + 1), 1L)));
        var back = r("return", Map.of(length == 2 ? "B" : "m" + (length - 2), 1L), Map.of("A", 2L)); recipes.add(back);
        var finish = r("finish", joint ? Map.of("A", 10L, "joint", 4L) : Map.of("A", 10L), Map.of("G", 1L)); recipes.add(finish);
        var word = new PlanStep.Sequence(List.of(new PlanStep.Batch("return", 5), new PlanStep.Batch("finish", 1)));
        return new Fixture(recipes, RefillWordPruningTest.literalPlan(List.of(back, finish), word, seed == 0 ? Map.of() : Map.of("A", seed), stock, Set.of()));
    }
    private static GraphPlan<String> improve(Fixture f, Map<String, Long> stock, Set<String> excluded, Set<String> external, boolean force, boolean ignore) {
        var b = MissingMaterialExpansionTest.budget(100_000); GraphPlan<String> result;
        try (var order = new RefillProducerOrder<>(new GraphCompiler<>(f.recipes), f.original.missingExact().keySet(), external, excluded, b)) {
            while (!order.step()) {}
            try (var search = new RefillSupplySearch<>(f.original, order, stock, external, force, ignore, b)) {
                while (!search.step()) {} result = search.result();
            }
        }
        check(b.reservedBytes() == 0, "Search workspace leaked");
        RefillWordPruningTest.literalVerify(result, stock, external);
        for (var e : result.missingExact().entrySet()) check(e.getValue().compareTo(f.original.missingExact().getOrDefault(e.getKey(), ZERO)) <= 0, "Increased supplies");
        return result;
    }
    public static void main(String[] args) throws Exception {
        quantities(); obligations(); mainAndFallback(); restrictions(); largeCounts(); limits(); parentRetention(); scheduler(false); scheduler(true); constructorLimits();
        System.out.println("RefillSupplySearchTest: assertions=" + checks);
    }
    private static void quantities() {
        for (int length : new int[] {2, 3, 5}) for (long held : new long[] {0, 1}) for (long feed : new long[] {0, 4, 6, 8, 9, 10}) {
            String start = length == 2 ? "B" : "m" + (length - 2);
            var stock = Map.of(start, held, "feed", feed); var f = fixture(stock, 0, false, length);
            var p = improve(f, stock, Set.of(), Set.of(), true, false);
            if (length == 2 && feed >= 8) check(p.missingExact().equals(held == 0 ? Map.of("B", BigInteger.ONE) : Map.of()), "Funded two-step cycle " + p.missingExact());
            if (feed == 0) check(p == f.original, "Introduced an unfunded feedstock");
        }
        var stock = Map.of("feed", 6L); var f = fixture(stock, 0, false, 2);
        check(improve(f, stock, Set.of(), Set.of(), true, false).missing().equals(Map.of("B", 2L)), "Did not use a later quantity trial");
        // Reverse source/catalog order without altering the fixed suffix.
        var reversed = new ArrayList<>(f.recipes); Collections.reverse(reversed);
        check(improve(new Fixture(reversed, f.original), stock, Set.of(), Set.of(), true, false).missing().equals(Map.of("B", 2L)), "Catalog order changed this witness");
    }
    private static void obligations() {
        for (long seed : new long[] {0, 1, 4}) for (boolean joint : List.of(false, true)) {
            var stock = Map.of("feed", 9L); var f = fixture(stock, seed, joint, 2);
            var p = improve(f, stock, Set.of(), Set.of(), true, false);
            check(p.seeds().equals(f.original.seeds()), "Lost explicit final seed");
            if (seed == 0 && !joint) check(p.missing().equals(Map.of("B", 1L)), "Unnecessary seed retained");
        }
        var first = r("make", Map.of("B", 1L, "G", 1L), Map.of("A", 1L));
        var second = r("back", Map.of("A", 1L), Map.of("B", 10L)); var finish = r("finish", Map.of("B", 10L), Map.of("G", 1L));
        var stock = Map.of("G", 1L);
        var original = RefillWordPruningTest.literalPlan(List.of(finish), new PlanStep.Batch("finish", 1), Map.of(), stock, Set.of());
        var f = new Fixture(List.of(first, second, finish), original);
        check(improve(f, stock, Set.of(), Set.of(), false, false).missing().equals(Map.of("B", 1L)), "Non-forced prefix could not use target stock");
        check(improve(f, stock, Set.of(), Set.of(), true, false) == original, "Spent forced target credit");
        check(improve(f, stock, Set.of(), Set.of(), false, true) == original, "Used ignored target stock");
        check(improve(f, Map.of(), Set.of(), Set.of("G"), true, false) == original, "External target weakened force credit");
    }
    private static void mainAndFallback() {
        for (long seed : new long[] {0, 1}) {
            var stock = Map.of("feed", 9L); var f = fixture(stock, seed, false, 2); var b = MissingMaterialExpansionTest.budget(4_000_000);
            var mandatory = seed == 0 ? Map.<String, Long>of() : Map.of("A", seed);
            var work = new GraphPlanningWork<>(new GraphCompiler<>(f.recipes), "G", 1, stock, Set.of(), mandatory, seed > 0, true, b);
            try {
                while (!work.step()) {}
                var p = work.result(); RefillWordPruningTest.literalVerify(p, stock, Set.of());
                check(p.missing().getOrDefault("B", 0L) < 5 && p.missing().getOrDefault("feed", 0L) == 0, "Main planner missed cyclic prefix " + p.missing());
            } finally { work.close(); }
            check(b.reservedBytes() == 0, "Main lifecycle");
            b = MissingMaterialExpansionTest.budget(4_000_000);
            var p = GraphFallback.plan(new GraphCompiler<>(f.recipes), "G", 1, stock, Set.of(), mandatory, seed > 0, true, b);
            check(p.missing().equals(Map.of("B", seed == 0 ? 5L : 6L)), "Fallback started full cyclic search");
            check(b.reservedBytes() == 0 && !b.diagnostics().contains("refill_supply"), "Fallback search budget changed");
        }
    }
    private static void restrictions() {
        var stock = Map.of("feed", 9L); var f = fixture(stock, 0, false, 2);
        check(improve(f, stock, Set.of("make"), Set.of(), true, false) == f.original, "Used an excluded source");
        var slots = List.of(new GraphRecipe.Slot<>("A", 1), new GraphRecipe.Slot<>("feed", 1, 0, true, false));
        var sensitive = new GraphRecipe<>("make", "machine", slots, Map.of("B", 1L));
        check(improve(new Fixture(List.of(sensitive, f.recipes.get(1), f.recipes.get(2)), f.original), stock, Set.of(), Set.of(), true, false) == f.original, "Changed configuration push contract");
        var conditional = GraphPlan.ownedRefill(f.original, f.original.steps(), f.original.recipes(), f.original.initialExact(), f.original.missingExact(), GraphPlan.Result.INCOMPLETE_MODEL, 0);
        check(improve(new Fixture(f.recipes, conditional), stock, Set.of(), Set.of(), true, false).conditionalPreview(), "Upgraded incomplete-model proof");
        var conflict = new LinkedHashMap<>(f.original.recipes()); conflict.put("make", r("make", Map.of("bad", 1L), Map.of("B", 1L)));
        var bound = GraphPlan.ownedRefill(f.original, f.original.steps(), conflict, f.original.initialExact(), f.original.missingExact(), f.original.result(), 0);
        check(improve(new Fixture(f.recipes, bound), stock, Set.of(), Set.of(), true, false) == bound, "Rebound physical recipe ID");
        var wide = new LinkedHashMap<String, Long>(); wide.put("A", 1L); for (int i = 0; i < 4096; i++) wide.put("raw" + i, 1L);
        check(improve(new Fixture(List.of(r("make", wide, Map.of("B", 1L)), f.recipes.get(1), f.recipes.get(2)), f.original), stock, Set.of(), Set.of(), true, false) == f.original, "Entered unbounded constructor support");
    }
    private static Object field(Object x, String name) throws Exception { var f = x.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(x); }
    private static void largeCounts() {
        BigInteger demand = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.valueOf(101));
        var recipes = List.of(r("make", Map.of("A", 1L, "feed", 1L), Map.of("B", 1L)),
                r("return", Map.of("B", 1L), Map.of("A", 2L)), r("finish", Map.of("B", 1L), Map.of("G", 1L)));
        var original = new GraphPlan<>("G", 1, false, PlanStep.batch("finish", demand), Map.of("finish", recipes.get(2)),
                Map.of("B", demand), Map.of(), Map.of("B", demand), GraphPlan.Result.MISSING_INPUT, 0, 0);
        var b = MissingMaterialExpansionTest.budget(100_000); var stock = Map.of("feed", Long.MAX_VALUE);
        try (var order = new RefillProducerOrder<>(new GraphCompiler<>(recipes), original.missingExact().keySet(), Set.of(), Set.of(), b)) {
            while (!order.step()) {}
            try (var search = new RefillSupplySearch<>(original, order, stock, Set.of(), true, false, b)) {
                while (!search.step()) {}
                var p = search.result();
                check(p.missingExact().get("B").compareTo(demand) < 0, "Exact large supply was not reduced");
                check(p.patternTimesExact().get("finish").equals(demand), "Clipped original exact target credit");
                var net = p.patternTimesExact().getOrDefault("make", ZERO).subtract(p.patternTimesExact().getOrDefault("return", ZERO));
                check(p.initialExact().get("B").add(net).equals(demand), "Large B balance");
                check(p.initialExact().getOrDefault("feed", ZERO).equals(p.patternTimesExact().get("make")) &&
                        p.initialExact().get("feed").compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0, "Large feed balance");
                try (var verification = new PlanVerification<>(GraphPlan.ownedRefill(p, p.steps(), p.recipes(), p.initialExact(), Map.of(), GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, 0), b)) {
                    while (!verification.step()) {}
                }
            }
        }
        check(b.reservedBytes() == 0, "Large exact lifecycle");
    }
    private static void reach(RefillSupplySearch<String> search, String phase) throws Exception {
        for (int i = 0; !field(search, "phase").toString().equals(phase); i++) check(i < 10_000 && !search.step(), "Did not reach " + phase);
    }
    private static void limits() throws Exception {
        var stock = Map.of("feed", 9L); var f = fixture(stock, 0, false, 2);
        for (String at : List.of("SEARCH", "SUMMARY", "VERIFY")) for (String mode : List.of("work", "time", "memory", "cancel")) {
            var clock = new AtomicLong(); var cancelled = new AtomicBoolean(); var b = new PlanningBudget(1, 100_000, 4L << 20, cancelled::get, clock::get); long held = 0;
            try (var order = new RefillProducerOrder<>(new GraphCompiler<>(f.recipes), f.original.missingExact().keySet(), Set.of(), Set.of(), b)) {
                while (!order.step()) {}
                b.failureDetail("retained original proof");
                try (var search = new RefillSupplySearch<>(f.original, order, stock, Set.of(), true, false, b)) {
                    reach(search, at);
                    switch (mode) {
                        case "work" -> b.charge(b.remainingWork());
                        case "time" -> clock.set(2_000_000);
                        case "memory" -> { held = b.availableBytes(); b.reserve(held); }
                        case "cancel" -> cancelled.set(true);
                    }
                    try { while (!search.step()) {} check(!mode.equals("cancel"), "Cancellation swallowed"); RefillWordPruningTest.literalVerify(search.result(), stock, Set.of()); }
                    catch (CancellationException expected) { check(mode.equals("cancel"), "Unexpected cancellation"); }
                    check(b.failureDetail().equals("retained original proof"), "Replaced original proof diagnostic");
                } finally { b.release(held); }
            }
            check(b.reservedBytes() == 0, "Interrupted workspace " + at + "/" + mode);
        }
    }
    private static void scheduler(boolean cancel) throws Exception {
        var stock = Map.of("feed", 9L); var f = fixture(stock, 0, false, 2); var cancelled = new AtomicBoolean();
        var b = new PlanningBudget(0, 100_000, 4L << 20, cancelled::get, System::nanoTime);
        try (var order = new RefillProducerOrder<>(new GraphCompiler<>(f.recipes), f.original.missingExact().keySet(), Set.of(), Set.of(), b)) {
            while (!order.step()) {}
            try (var search = new RefillSupplySearch<>(f.original, order, stock, Set.of(), true, false, b);
                 var scheduler = new PlanningScheduler(1, 8, 64, 1_000_000_000)) {
                reach(search, "SEARCH"); long before = b.nodes(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
                var future = scheduler.submit(new PlanningScheduler.Work<GraphPlan<String>>() {
                    boolean started;
                    public boolean advance(PlanningScheduler.Slice slice) {
                        if (!started) { started = true; entered.countDown(); try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Handoff timeout"); } catch (InterruptedException e) { throw new AssertionError(e); } }
                        while (slice.nextUncharged()) if (search.step()) return true;
                        return false;
                    }
                    public GraphPlan<String> result() { return search.result(); }
                    public void close() { search.close(); }
                }, b);
                check(entered.await(5, TimeUnit.SECONDS), "Worker did not start");
                var marker = scheduler.submit(new PlanningScheduler.Work<Long>() {
                    long seen;
                    public boolean advance(PlanningScheduler.Slice slice) { seen = b.nodes() - before; if (cancel) cancelled.set(true); return true; }
                    public Long result() { return seen; }
                }, MissingMaterialExpansionTest.budget(1000));
                release.countDown(); check(marker.get(10, TimeUnit.SECONDS) <= 2304, "Restricted solver monopolized scheduler");
                try { var p = future.get(10, TimeUnit.SECONDS); check(!cancel, "Cancellation lost"); RefillWordPruningTest.literalVerify(p, stock, Set.of()); }
                catch (CancellationException e) { check(cancel, "Unexpected cancellation"); }
                catch (ExecutionException e) { check(cancel && e.getCause() instanceof CancellationException, "Unexpected worker failure"); }
            }
        }
        check(b.reservedBytes() == 0, "Scheduled workspace");
    }
    private static void parentRetention() throws Exception {
        var stock = Map.of("feed", 9L); var f = fixture(stock, 1, false, 2); var b = MissingMaterialExpansionTest.budget(4_000_000);
        var work = new GraphPlanningWork<>(new GraphCompiler<>(f.recipes), "G", 1, stock, Set.of(), Map.of("A", 1L), true, true, b);
        try {
            GraphPlan<String> accepted = null;
            for (int i = 0; accepted == null; i++) {
                check(i < 100_000 && !work.step(), "No completed intermediate improvement");
                Object expansion = field(work, "missingExpansion"); if (expansion == null) continue;
                Object search = field(expansion, "cycleSearch"); if (search == null) continue;
                @SuppressWarnings("unchecked") var s = (RefillSupplySearch<String>) search;
                if (s.result() != field(search, "original")) accepted = s.result();
            }
            b.charge(b.remainingWork());
            while (!work.step()) {}
            check(work.result() == accepted, "Parent limit lost a verified cyclic improvement");
            RefillWordPruningTest.literalVerify(work.result(), stock, Set.of());
        } finally { work.close(); }
        check(b.reservedBytes() == 0, "Parent publication lifecycle");
    }
    private static void constructorLimits() {
        var recipes = fixture(Map.of(), 0, false, 2).recipes.subList(0, 2);
        var stock = new LinkedHashMap<String, BigInteger>();
        // The first coordinates pass, so a small work cap interrupts after
        // remember() reserves a node but before construction can return it.
        stock.put("feed", BigInteger.TEN); stock.put("A", ZERO); stock.put("B", BigInteger.ONE);
        int constructorsInterrupted = 0;
        for (boolean witnessOnly : List.of(false, true)) for (long cap = 1; cap <= 8; cap++) {
            var b = MissingMaterialExpansionTest.budget(cap);
            BackwardCoverability<String> search = null;
            try {
                search = new BackwardCoverability<>(recipes, stock, Map.of("B", BigInteger.TEN), Set.of(), List.of(), b, 2048, witnessOnly);
                while (!search.step()) {}
                check(!witnessOnly || search.result() != BackwardCoverability.Result.CLOSED, "Restricted search exported absence proof");
            } catch (PlanningBudget.Exhausted expected) { if (search == null) constructorsInterrupted++; }
            finally { if (search != null) search.close(); }
            check(b.reservedBytes() == 0, "Throwing solver constructor leaked");
        }
        check(constructorsInterrupted > 0, "Only interrupted an already constructed search");
    }
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
}
