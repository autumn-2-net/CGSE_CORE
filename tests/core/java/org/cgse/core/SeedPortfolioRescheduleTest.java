// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Serial tool reuse must not require buying a solver's optional parallel seeds. */
public final class SeedPortfolioRescheduleTest {
    private static final GraphRecipe<String> MAKE = recipe("make", Map.of("S", 1L, "raw", 1L), Map.of("mid", 1L));
    private static final GraphRecipe<String> RETURN = recipe("return", Map.of("mid", 1L), Map.of("S", 1L, "G", 1L));
    private static final GraphCompiler<String> COMPILER = new GraphCompiler<>(List.of(MAKE, RETURN));
    private static final Map<String, Long> STOCK = Map.of("S", 5L, "raw", 127L);
    private static final Map<String, Long> NO_ENUMERATION = new AbstractMap<>() {
        @Override public Long get(Object key) { return Map.of("S", 5L, "raw", 127L).get(key); }
        @Override public boolean containsKey(Object key) { return get(key) != null; }
        @Override public Set<Entry<String, Long>> entrySet() { throw new AssertionError("Enumerated unrelated inventory"); }
    };

    public static void main(String[] args) throws Exception {
        nestedTerminalProbe();
        var b = budget(1_000_000, 8L << 20);
        try (var search = search(b, NO_ENUMERATION)) { check(!search.step(), "Preparation failed to yield"); }
        check(b.reservedBytes() == 0, "Preparation leaked");
        b = budget(1_000_000, 8L << 20);
        try (var search = search(b)) {
            while (!search.step()) {}
            var result = search.result();
            check(result.missingExact().equals(Map.of("raw", BigInteger.ONE)), "Optional parallel seed remained: " + result.missingExact());
            check(result.seeds().getOrDefault("S", 0L) == 5, "Changed the mandatory tool reserve");
            PlanVerifier.verify(new GraphPlan<>("G", 128, true, result.steps(), result.recipes(), result.initialExact(),
                    result.seeds(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0));
        }
        check(b.reservedBytes() == 0, "Successful reschedule leaked");
        for (int stop : new int[]{0, 1, 2, 4, 8, 16, 32, 64}) {
            b = budget(1_000_000, 8L << 20);
            try (var search = search(b)) {
                boolean done = false;
                for (int i = 0; i < stop && !done; i++) done = search.step();
                b.cancel();
                if (!done) {
                    try { search.step(); throw new AssertionError("Cancellation ignored"); }
                    catch (CancellationException expected) { }
                }
            }
            check(b.reservedBytes() == 0, "Cancelled reschedule leaked at " + stop);
        }
        for (long cap : new long[]{1, 16, 64, 256}) for (long memory : new long[]{512, 2048, 8192}) {
            b = budget(cap, memory);
            try (var search = search(b)) { while (!search.step()) {} }
            catch (PlanningBudget.Exhausted expected) { }
            check(b.reservedBytes() == 0, "Limited reschedule leaked: " + cap + "/" + memory);
        }
        System.out.println("SeedPortfolioRescheduleTest PASS: finite supply, mandatory seeds, support-only stock, cancellation and limits");
    }

    private static void nestedTerminalProbe() throws Exception {
        var input = HistoricalSourceBoundaryTest.nested(4);
        var stock = new HashMap<>(input.stock()); stock.compute("R", (k, v) -> v - 1);
        var bindings = new LinkedHashMap<String, GraphRecipe<String>>();
        input.recipes().forEach(r -> bindings.put(r.id(), r));
        var plan = new GraphPlan<>("Q0", 1, true, new PlanStep.Batch("close0", 1), bindings,
                Map.of("C1", 2L, "Q1", 2L), Map.of("C0", 1L, "C1", 1L),
                Map.of("C1", 2L, "Q1", 2L), GraphPlan.Result.MISSING_INPUT, 0, 0);
        var field = SeedPortfolio.class.getDeclaredField("probeTried"); field.setAccessible(true);
        for (String mode : List.of("complete", "cancel", "work", "memory")) {
            var b = budget(1_000_000, 8L << 20); long occupied = 0;
            try (var search = new SeedPortfolio<>(new GraphCompiler<>(input.recipes()), plan, stock,
                    Map.of("C0", 1L), Set.of(), Set.of(), true, b)) {
                int steps = 0;
                while (!(boolean) field.get(search)) {
                    check(++steps < 2000 && !search.step(), "Did not reach finite terminal hypothesis");
                }
                check(search.result().missingExact().equals(plan.missingExact()), "Published an unverified hypothesis");
                switch (mode) {
                    case "cancel" -> b.cancel();
                    case "work" -> b.charge(b.remainingWork());
                    case "memory" -> { occupied = b.availableBytes(); b.reserve(occupied); }
                }
                try {
                    while (!search.step()) {}
                    check(mode.equals("complete"), "Interrupted finite search unexpectedly completed");
                    var p = search.result();
                    check(p.missingExact().equals(Map.of("R", BigInteger.ONE)), "Artificial nested seed remained");
                    check(p.seeds().equals(Map.of("C0", 1L)), "Nested final seed changed");
                    RefillWordPruningTest.literalVerify(p, stock, Set.of());
                } catch (CancellationException expected) { check(mode.equals("cancel"), "Unexpected cancellation"); }
                catch (PlanningBudget.Exhausted expected) { check(mode.equals("work") || mode.equals("memory"), "Unexpected limit"); }
            } finally { b.release(occupied); }
            check(b.reservedBytes() == 0, "Finite terminal hypothesis leaked: " + mode);
        }
    }

    private static SeedPortfolio<String> search(PlanningBudget b) {
        return search(b, STOCK);
    }
    private static SeedPortfolio<String> search(PlanningBudget b, Map<String, Long> stock) {
        var initial = Map.of("S", 128L, "raw", 128L);
        var word = new PlanStep.Sequence(List.of(new PlanStep.Batch("make", 128), new PlanStep.Batch("return", 128)));
        var plan = new GraphPlan<>("G", 128, true, word, Map.of("make", MAKE, "return", RETURN), initial,
                Map.of("S", 128L), Map.of("S", 123L, "raw", 1L), GraphPlan.Result.MISSING_INPUT, 0, 0);
        return new SeedPortfolio<>(COMPILER, plan, stock, Map.of("S", 5L), Set.of(), Set.of(), true, b);
    }
    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static PlanningBudget budget(long work, long memory) { return new PlanningBudget(0, work, memory, () -> false, System::nanoTime); }
    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
}
