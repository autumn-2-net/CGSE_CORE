// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Finite reordering must repay final seeds, reduce the whole bill and survive interruption. */
public final class RefillReschedulingTest {
    private static final List<GraphRecipe<String>> RECIPES = List.of(
            recipe("make", Map.of("A", 1L, "feed", 1L), Map.of("B", 1L)),
            recipe("return", Map.of("B", 1L), Map.of("A", 2L)),
            recipe("finish", Map.of("A", 10L), Map.of("G", 1L)));
    private static final Map<String, GraphRecipe<String>> BINDINGS = new LinkedHashMap<>();
    static { RECIPES.forEach(r -> BINDINGS.put(r.id(), r)); }
    private static final Map<String, Long> STOCK = Map.of("feed", 9L);
    private static final GraphPlan<String> ORIGINAL = RefillWordPruningTest.literalPlan(RECIPES,
            new PlanStep.Sequence(List.of(new PlanStep.Repeat(new PlanStep.Sequence(List.of(
                    new PlanStep.Batch("return", 1), new PlanStep.Batch("make", 2))), 4),
                    new PlanStep.Batch("return", 6), new PlanStep.Batch("finish", 1))), Map.of("A", 1L), STOCK, Set.of());

    public static void main(String[] args) {
        check(ORIGINAL.missing().equals(Map.of("B", 2L)), "Rounded fixture changed");
        finiteGoals();
        var b = budget(100_000, 8L << 20);
        Map<String, Long> supportOnly = new AbstractMap<>() {
            @Override public Long get(Object k) { return STOCK.get(k); }
            @Override public boolean containsKey(Object k) { return STOCK.containsKey(k); }
            @Override public Set<Entry<String, Long>> entrySet() { throw new AssertionError("Enumerated whole inventory"); }
        };
        try (var search = new RefillRescheduling<>(ORIGINAL, supportOnly, Set.of(), true, false, b)) {
            while (!search.step()) {}
            var p = search.result();
            check(p.missing().equals(Map.of("B", 1L)), "Did not use the odd feed unit: " + p.missing());
            check(p.seeds().equals(Map.of("A", 1L)), "Final seed changed");
            RefillWordPruningTest.literalVerify(p, STOCK, Set.of());
            for (var option : p.alternatives()) check(!SeedPortfolio.dominates(p, option), "Retained dominated bill");
        }
        check(b.reservedBytes() == 0, "Successful search leaked");
        for (int stop : new int[]{0, 1, 2, 4, 8, 16, 32, 64, 128}) {
            b = budget(100_000, 8L << 20);
            try (var search = new RefillRescheduling<>(ORIGINAL, STOCK, Set.of(), true, false, b)) {
                boolean done = false;
                for (int i = 0; i < stop && !done; i++) done = search.step();
                var retained = search.result(); b.cancel();
                if (!done) {
                    try { search.step(); throw new AssertionError("Cancellation swallowed"); }
                    catch (CancellationException expected) { }
                }
                check(search.result() == retained, "Interrupted proposal published");
                RefillWordPruningTest.literalVerify(retained, STOCK, Set.of());
            }
            check(b.reservedBytes() == 0, "Cancelled search leaked at " + stop);
        }
        for (long cap : new long[]{1, 64, 1024, 8192, 16_384}) for (long bytes : new long[]{512, 2048, 8192, 32768}) {
            b = budget(cap, bytes);
            try (var search = new RefillRescheduling<>(ORIGINAL, STOCK, Set.of(), true, false, b)) {
                while (!search.step()) {}
                var p = search.result();
                check(p.missing().getOrDefault("B", 0L) <= 2 && !p.missing().containsKey("feed"), "Limit fabricated purchases");
                RefillWordPruningTest.literalVerify(p, STOCK, Set.of());
            }
            check(b.reservedBytes() == 0, "Limited search leaked: " + cap + "/" + bytes);
        }
        System.out.println("RefillReschedulingTest PASS: finite bills, goal reserves, literal execution, cancellation and limits");
    }
    private static void finiteGoals() {
        for (long feed : new long[]{8, 9}) for (boolean reversed : List.of(false, true)) {
            var goals = new LinkedHashMap<String, BigInteger>();
            goals.put(reversed ? "G" : "A", BigInteger.ONE); goals.put(reversed ? "A" : "G", BigInteger.ONE);
            var b = budget(100_000, 8L << 20);
            var search = new DemandExpansion<>(BINDINGS, Map.of("feed", BigInteger.valueOf(feed), "B", BigInteger.ONE), goals, b).protectFinalGoals();
            try {
                while (!search.step()) {}
                check((search.result() != null) == (feed == 9), "Invented or lost a finite executable word");
                if (search.result() != null) {
                    var p = RefillWordPruningTest.literalPlan(RECIPES, search.result(), Map.of("A", 1L), STOCK, Set.of());
                    check(p.missing().equals(Map.of("B", 1L)), "Goal ordering added purchases");
                    RefillWordPruningTest.literalVerify(p, STOCK, Set.of());
                }
            } finally { search.close(); }
            check(b.reservedBytes() == 0, "Demand search leaked");
        }
    }
    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return MissingMaterialExpansionTest.recipe(id, in, out);
    }
    private static PlanningBudget budget(long cap, long bytes) { return new PlanningBudget(0, cap, bytes, () -> false, System::nanoTime); }
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
}
