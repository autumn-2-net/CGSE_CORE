// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Supply sufficiency alone does not catch dominated refill quantities. */
public final class RefillQualityTest {
    private static int checks;
    private static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return MissingMaterialExpansionTest.recipe(id, in, out);
    }
    private static GraphPlan<String> suffix(GraphRecipe<String> finish, long seed, Map<String, Long> stock) {
        var need = new LinkedHashMap<String, Long>(finish.inputs());
        if (seed > 0) need.merge("A", seed, Math::addExact);
        var missing = new LinkedHashMap<String, Long>();
        need.forEach((k, n) -> { if (n > stock.getOrDefault(k, 0L)) missing.put(k, n - stock.getOrDefault(k, 0L)); });
        return new GraphPlan<>("G", 1, seed > 0, new PlanStep.Batch(finish.id(), 1), Map.of(finish.id(), finish),
                need, seed == 0 ? Map.of() : Map.of("A", seed), missing, GraphPlan.Result.MISSING_INPUT, 0, 0);
    }
    private static GraphPlan<String> expand(List<GraphRecipe<String>> recipes, GraphPlan<String> suffix, Map<String, Long> stock) {
        return MissingMaterialExpansionTest.expand(recipes, suffix, stock);
    }
    public static void main(String[] args) {
        platforms(); jointSources(); coproductTradeoff(); fundedStockAndCredit(); lifecycle();
        System.out.println("RefillQualityTest: assertions=" + checks);
    }
    private static void platforms() {
        for (long input : new long[] {1, 17, 1000}) for (long gain : new long[] {1, 3})
            for (long seed : new long[] {0, input}) for (long have : new long[] {0, input - 1, input}) {
                long demand = input + gain;
                var grow = r("grow", Map.of("A", input, "raw", 1L), Map.of("A", input + gain));
                var finish = r("finish", Map.of("A", demand), Map.of("G", 1L));
                var stock = Map.of("A", have);
                var plan = expand(List.of(grow, finish), suffix(finish, seed, stock), stock);
                long firings = Math.max(1, (demand + seed - Math.max(input, have) + gain - 1) / gain);
                check(plan.missing().equals(have == input ? Map.of("raw", firings) : Map.of("A", input - have, "raw", firings)),
                        "Dominated growth supplies: " + input + "/" + gain + "/" + seed + "/" + have + " " + plan.missing());
                check(plan.patternTimesExact().get("grow").equals(BigInteger.valueOf(firings)), "Growth continued after its requirement plateau");
                MissingMaterialExpansionTest.verify(plan, stock);
            }
    }
    private static void jointSources() {
        for (boolean reverseInputs : List.of(false, true)) for (boolean reverseSources : List.of(false, true)) {
            var in = new LinkedHashMap<String, Long>();
            in.put(reverseInputs ? "B" : "A", 1L); in.put(reverseInputs ? "A" : "B", 1L);
            var finish = r("finish", in, Map.of("G", 1L));
            var sources = new ArrayList<>(List.of(r("costlyB", Map.of("raw", 100L), Map.of("B", 1L)),
                    r("joint", Map.of("raw", 1L), Map.of("A", 1L, "B", 1L))));
            if (reverseSources) Collections.reverse(sources);
            sources.add(finish);
            var plan = expand(sources, suffix(finish, 0, Map.of()), Map.of());
            check(plan.missing().equals(Map.of("raw", 1L)), "Ordinary enumeration order introduced dominated supplies: " + plan.missing());
            check(!plan.patternTimesExact().containsKey("costlyB"), "Dominated producer still fired");
        }
        // Incomparable materials keep source priority. No invented exchange
        // rate permits replacing one unit of R with a hundred units of S.
        var finish = r("finish", Map.of("A", 1L), Map.of("G", 1L));
        var first = r("first", Map.of("R", 1L), Map.of("A", 1L));
        var second = r("second", Map.of("S", 100L), Map.of("A", 1L));
        check(expand(List.of(first, second, finish), suffix(finish, 0, Map.of()), Map.of()).missing().equals(Map.of("R", 1L)), "Incomparable source priority changed");
        check(expand(List.of(second, first, finish), suffix(finish, 0, Map.of()), Map.of()).missing().equals(Map.of("S", 100L)), "Refill used an implicit material price");
    }
    private static void coproductTradeoff() {
        var in = new LinkedHashMap<String, Long>(); in.put("A", 11L); in.put("B", 11L);
        var finish = r("finish", in, Map.of("G", 1L));
        var grow = r("grow", Map.of("A", 10L, "raw", 1L), Map.of("A", 11L, "B", 1L));
        var plan = expand(List.of(grow, finish), suffix(finish, 0, Map.of()), Map.of());
        check(plan.missing().equals(Map.of("A", 10L, "raw", 11L)), "Blind plateau replacement lost a useful coproduct");
        check(plan.patternTimesExact().get("grow").equals(BigInteger.valueOf(11)), "Old incomparable count was discarded");
    }
    private static void fundedStockAndCredit() {
        var finish = r("finish", Map.of("A", 1L), Map.of("G", 1L));
        var unfunded = r("unfunded", Map.of("raw", 1L), Map.of("A", 1L));
        var funded = r("funded", Map.of("held", 100L), Map.of("A", 1L));
        var stock = Map.of("held", 100L);
        check(expand(List.of(unfunded, funded, finish), suffix(finish, 0, stock), stock).feasible(), "Lost fully stock-funded priority");
        var grow = r("grow", Map.of("A", 10L, "raw", 1L), Map.of("A", 11L, "G", 1L));
        var f = r("finish", Map.of("A", 11L), Map.of("G", 1L));
        var p = expand(List.of(grow, f), suffix(f, 0, Map.of()), Map.of());
        check(p.patternTimesExact().get("grow").equals(BigInteger.valueOf(11)), "Dominance discarded distinct forced target credit");
    }
    private static void lifecycle() {
        var grow = r("grow", Map.of("A", 10L, "raw", 1L), Map.of("A", 11L));
        var finish = r("finish", Map.of("A", 11L), Map.of("G", 1L));
        var compiler = new GraphCompiler<>(List.of(grow, finish));
        for (int stop = 1; stop <= 220; stop++) {
            var cancelled = new AtomicBoolean();
            var b = new PlanningBudget(0, 100_000, 1L << 20, cancelled::get, System::nanoTime);
            try (var expansion = new MissingMaterialExpansion<>(compiler, suffix(finish, 0, Map.of()), Map.of(), Set.of(), Set.of(), true, false, b)) {
                for (int i = 0; ; i++) {
                    if (i == stop) cancelled.set(true);
                    if (expansion.step()) { MissingMaterialExpansionTest.verify(expansion.result(), Map.of()); break; }
                }
            } catch (java.util.concurrent.CancellationException expected) { /* No partial bill. */ }
            check(b.reservedBytes() == 0, "Cancelled proposal workspace leaked");
        }
        for (long bytes : new long[] {1, 512, 2048, 4096, 8192, 32768}) {
            var b = new PlanningBudget(0, 100_000, bytes, () -> false, System::nanoTime);
            try (var expansion = new MissingMaterialExpansion<>(compiler, suffix(finish, 0, Map.of()), Map.of(), Set.of(), Set.of(), true, false, b)) {
                try { while (!expansion.step()) {} MissingMaterialExpansionTest.verify(expansion.result(), Map.of()); }
                catch (PlanningBudget.Exhausted expected) { check(expansion.result() == null, "Memory limit published an unfinished bill"); }
            }
            check(b.reservedBytes() == 0 && b.peakBytes() <= bytes, "Proposal memory escaped its budget");
        }
    }
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
}
