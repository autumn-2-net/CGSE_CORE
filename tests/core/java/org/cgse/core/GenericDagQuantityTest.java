// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Closed-form DAG quantities and an independent compressed physical inventory fold. */
public final class GenericDagQuantityTest {
    private static int checks, requests;
    private static final BigInteger ZERO = BigInteger.ZERO;
    private static void check(boolean okay, String reason) { checks++; if (!okay) throw new AssertionError(reason); }
    private static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static Map<String, Long> ordered(Object... values) {
        var result = new LinkedHashMap<String, Long>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], (Long) values[i + 1]);
        return result;
    }
    private static BigInteger n(long value) { return BigInteger.valueOf(value); }
    private static BigInteger ceil(BigInteger value, long divisor) {
        return value.signum() <= 0 ? ZERO : value.add(n(divisor - 1)).divide(n(divisor));
    }
    private static Map<String, BigInteger> positive(Map<String, BigInteger> values) {
        var result = new LinkedHashMap<String, BigInteger>();
        values.forEach((key, value) -> { if (value.signum() > 0) result.put(key, value); });
        return result;
    }
    private static void solve(String label, List<GraphRecipe<String>> recipes, long amount, Map<String, Long> stock,
                              Map<String, Long> finalReserves, Map<String, BigInteger> expectedCounts,
                              Map<String, BigInteger> expectedMissing) {
        for (var mode : GraphMode.values()) for (boolean preserve : List.of(false, true)) {
            var budget = new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime);
            var work = new GraphPlanningWork<>(new GraphCompiler<>(recipes), "T", amount, stock, Set.of(), finalReserves,
                    preserve, true, budget, mode).catalysts(CatalystPolicy.MINIMAL);
            try {
                while (!work.step()) {}
                var plan = work.result(); requests++;
                String context = label + "; mode=" + mode + "; preserve=" + preserve + "; result=" + plan.result() +
                        "; counts=" + plan.patternTimesExact() + "; missing=" + plan.missingExact() + "; " + budget.diagnostics();
                check(plan.feasible() || !plan.missingExact().isEmpty(), "No funded DAG result: " + context);
                physical(plan, finalReserves);
                check(plan.patternTimesExact().equals(positive(expectedCounts)), "Closed-form recipe quantity mismatch: " + context);
                check(plan.missingExact().equals(positive(expectedMissing)), "Closed-form raw/reserve bill mismatch: " + context);
                check(plan.seeds().equals(finalReserves), "Pure DAG invented automatic cycle seeds: " + context);
                for (var entry : plan.initialExact().entrySet()) {
                    var gap = entry.getValue().subtract(n(stock.getOrDefault(entry.getKey(), 0L))).max(ZERO);
                    check(gap.equals(plan.missingExact().getOrDefault(entry.getKey(), ZERO)), "Bill does not fund the exact initial ledger: " + context);
                }
            } finally { work.close(); }
            check(budget.reservedBytes() == 0, "DAG workspace leak: " + label + "; " + mode);
        }
    }
    private static void diamonds() {
        var recipes = List.of(r("source", ordered("raw", 3L), ordered("X", 2L)),
                r("left", ordered("X", 3L), ordered("A", 1L)),
                r("right", ordered("X", 5L), ordered("B", 1L)),
                r("finish", ordered("A", 1L, "B", 1L), ordered("T", 1L)));
        var random = new Random(90211);
        for (int i = 0; i < 16; i++) {
            long amount = 1 + random.nextInt(12), a = random.nextInt(8), b = random.nextInt(8), x = random.nextInt(30), raw = random.nextInt(6);
            long reserveA = random.nextInt(3), reserveB = random.nextInt(3), reserveX = random.nextInt(4), reserveRaw = random.nextInt(3);
            var stock = ordered("A", a, "B", b, "X", x, "raw", raw);
            var reserve = new LinkedHashMap<String, Long>();
            if (reserveA != 0) reserve.put("A", reserveA); if (reserveB != 0) reserve.put("B", reserveB);
            if (reserveX != 0) reserve.put("X", reserveX); if (reserveRaw != 0) reserve.put("raw", reserveRaw);
            var left = n(amount + reserveA - a).max(ZERO); var right = n(amount + reserveB - b).max(ZERO);
            var source = ceil(left.multiply(n(3)).add(right.multiply(n(5))).add(n(reserveX - x)), 2);
            solve("diamond" + i, recipes, amount, stock, reserve, Map.of("source", source, "left", left, "right", right, "finish", n(amount)),
                    Map.of("raw", source.multiply(n(3)).add(n(reserveRaw - raw)).max(ZERO)));
        }
        long amount = Long.MAX_VALUE;
        var source = n(amount).multiply(n(4));
        solve("diamond-big-integer", recipes, amount, Map.of(), Map.of(),
                Map.of("source", source, "left", n(amount), "right", n(amount), "finish", n(amount)), Map.of("raw", source.multiply(n(3))));
    }
    private static void coproductRounding() {
        var recipes = List.of(r("source", ordered("raw", 4L), ordered("A", 3L, "B", 2L)),
                r("finish", ordered("A", 3L, "B", 2L), ordered("T", 1L)));
        var random = new Random(8612);
        for (int i = 0; i < 12; i++) {
            long amount = 1 + random.nextInt(15), a = random.nextInt(20), b = random.nextInt(20), raw = random.nextInt(10);
            long reserveA = random.nextInt(4), reserveB = random.nextInt(4);
            var reserve = new LinkedHashMap<String, Long>(); if (reserveA > 0) reserve.put("A", reserveA); if (reserveB > 0) reserve.put("B", reserveB);
            var source = ceil(n(amount).multiply(n(3)).add(n(reserveA - a)), 3)
                    .max(ceil(n(amount).multiply(n(2)).add(n(reserveB - b)), 2));
            solve("coproduct" + i, recipes, amount, ordered("A", a, "B", b, "raw", raw), reserve,
                    Map.of("source", source, "finish", n(amount)), Map.of("raw", source.multiply(n(4)).subtract(n(raw)).max(ZERO)));
        }
        solve("coproduct-big-integer", recipes, Long.MAX_VALUE, Map.of(), Map.of(),
                Map.of("source", n(Long.MAX_VALUE), "finish", n(Long.MAX_VALUE)), Map.of("raw", n(Long.MAX_VALUE).multiply(n(4))));
        var reserves = ordered("unused", 3L, "T", 2L);
        solve("unused-and-target-final-reserve", recipes, 6, ordered("unused", 2L, "T", 2L), reserves,
                Map.of("source", n(6), "finish", n(6)), Map.of("raw", n(24), "unused", BigInteger.ONE));
    }
    private record Fold(Map<String, BigInteger> need, Map<String, BigInteger> delta) {}
    private static Fold fold(PlanStep step, Map<String, GraphRecipe<String>> recipes) {
        if (step instanceof PlanStep.Batch batch) {
            var recipe = recipes.get(batch.recipe()); var required = new LinkedHashMap<String, BigInteger>(); var delta = new LinkedHashMap<String, BigInteger>();
            var runs = n(batch.runs());
            recipe.inputs().forEach((key, quantity) -> { var used = n(quantity).multiply(runs); required.put(key, used); delta.merge(key, used.negate(), BigInteger::add); });
            recipe.executionOutputs().forEach((key, quantity) -> delta.merge(key, n(quantity).multiply(runs), BigInteger::add));
            return new Fold(required, delta);
        }
        if (step instanceof PlanStep.Repeat repeat) {
            if (repeat.times() == 0) return new Fold(Map.of(), Map.of());
            var unit = fold(repeat.body(), recipes); var required = new LinkedHashMap<String, BigInteger>(); var delta = new LinkedHashMap<String, BigInteger>();
            var keys = new LinkedHashSet<>(unit.need.keySet()); keys.addAll(unit.delta.keySet());
            for (var key : keys) {
                var change = unit.delta.getOrDefault(key, ZERO);
                required.put(key, unit.need.getOrDefault(key, ZERO).add(change.negate().max(ZERO).multiply(n(repeat.times() - 1))));
                delta.put(key, change.multiply(n(repeat.times())));
            }
            return new Fold(required, delta);
        }
        var required = new LinkedHashMap<String, BigInteger>(); var delta = new LinkedHashMap<String, BigInteger>();
        for (var child : ((PlanStep.Sequence) step).children()) {
            var next = fold(child, recipes);
            next.need.forEach((key, value) -> required.merge(key, value.subtract(delta.getOrDefault(key, ZERO)).max(ZERO), BigInteger::max));
            next.delta.forEach((key, value) -> delta.merge(key, value, BigInteger::add));
        }
        return new Fold(required, delta);
    }
    private static void physical(GraphPlan<String> plan, Map<String, Long> finalReserves) {
        var ledger = fold(plan.steps(), plan.recipes());
        var keys = new LinkedHashSet<>(ledger.need.keySet()); keys.addAll(ledger.delta.keySet()); keys.addAll(finalReserves.keySet()); keys.add(plan.target());
        for (var key : keys) {
            var initial = plan.initialExact().getOrDefault(key, ZERO);
            check(initial.compareTo(ledger.need.getOrDefault(key, ZERO)) >= 0, "Independent physical prefix unfunded: " + key);
            var goal = n(finalReserves.getOrDefault(key, 0L)); if (key.equals(plan.target())) goal = goal.add(n(plan.amount()));
            check(initial.add(ledger.delta.getOrDefault(key, ZERO)).compareTo(goal) >= 0, "Independent final delivery/reserve violated: " + key);
        }
        check(ledger.delta.getOrDefault(plan.target(), ZERO).compareTo(n(plan.amount())) >= 0, "Supplied target substituted for force manufacturing");
    }
    public static void main(String[] args) {
        diamonds(); coproductRounding();
        System.out.println("GenericDagQuantityTest PASS: requests=" + requests + "; checks=" + checks);
    }
}
