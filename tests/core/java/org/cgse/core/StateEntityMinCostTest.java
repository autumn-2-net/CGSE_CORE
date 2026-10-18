// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/** Literal allocation enumeration is independent of residual-cycle optimization. */
public final class StateEntityMinCostTest {
    private static int checks, cases, improved;
    private record Price(BigInteger primary, BigInteger secondary) implements Comparable<Price> {
        public int compareTo(Price other) { int c = primary.compareTo(other.primary); return c != 0 ? c : secondary.compareTo(other.secondary); }
        Price add(int primary, long secondary, long amount) { return new Price(this.primary.add(BigInteger.valueOf(primary).multiply(BigInteger.valueOf(amount))), this.secondary.add(BigInteger.valueOf(secondary).multiply(BigInteger.valueOf(amount)))); }
    }
    private static final Price ZERO = new Price(BigInteger.ZERO, BigInteger.ZERO);

    public static void main(String[] args) {
        var random = new Random(0x181031cL);
        for (int sample = 0; sample < 1200; sample++) {
            int[] stock = new int[3], need = new int[3], primary = new int[9]; long[] secondary = new long[9];
            for (int i = 0; i < 3; i++) { stock[i] = random.nextInt(4); need[i] = 1 + random.nextInt(2); }
            for (int i = 0; i < 9; i++) { primary[i] = random.nextInt(3); secondary[i] = random.nextBoolean() ? random.nextInt(4) : Long.MAX_VALUE - random.nextInt(4); }
            int edges = sample % 2 == 0 ? 511 : random.nextInt(512);
            var best = enumerate(0, 0, need[0], stock.clone(), need, edges, primary, secondary, ZERO);
            var demands = new ArrayList<StateEntityAllocation.Demand>(); var supplies = new ArrayList<StateEntityAllocation.Supply<Integer>>();
            for (int d = 0; d < 3; d++) demands.add(new StateEntityAllocation.Demand(d, need[d], need[d] > 1 || random.nextBoolean()));
            for (int s = 0; s < 3; s++) if (stock[s] > 0) supplies.add(new StateEntityAllocation.Supply<>(s, BigInteger.valueOf(stock[s]), 3 - s));
            Price ordinary = null;
            for (boolean optimize : List.of(false, true)) {
                var budget = budget(1_000_000);
                try (var work = StateEntityMatching.create(demands, supplies, (d, s) -> edge(edges, d.slot(), s),
                        (d, s) -> primary[d.slot() * 3 + s], (d, s) -> secondary[d.slot() * 3 + s], budget)) {
                    if (optimize) work.minimizePreferences((d, s) -> primary[d.slot() * 3 + s], (d, s) -> secondary[d.slot() * 3 + s]);
                    int steps = 0; while (!work.step()) check(++steps < 500, "Cost optimizer lost retained progress");
                    check(work.feasible() == (best != null), "Optional cost optimizer changed feasibility");
                    if (best != null) {
                        var got = verify(work.result(), stock, need, edges, primary, secondary);
                        if (!optimize) ordinary = got;
                        else { check(got.equals(best), "Small flow missed literal minimum: " + got + " != " + best); if (got.compareTo(ordinary) < 0) improved++; }
                    }
                }
                check(budget.reservedBytes() == 0, "Optimized matching leaked");
            }
            cases++;
        }
        wide(); limits(); lowerBoundGate();
        check(improved > 0, "No alternating-path improvement exercised");
        System.out.println("StateEntityMinCostTest: oracles=" + cases + "; improved=" + improved + "; assertions=" + checks);
    }

    private static Price verify(List<StateEntityAllocation.Pick<Integer>> picks, int[] stock, int[] need, int edges, int[] primary, long[] secondary) {
        var used = new long[3]; var filled = new long[3]; Price price = ZERO;
        for (var pick : picks) {
            int d = pick.slot(), s = pick.state(); check(pick.amount() > 0 && edge(edges, d, s), "Used forbidden edge");
            used[s] += pick.amount(); filled[d] += pick.amount(); price = price.add(primary[d * 3 + s], secondary[d * 3 + s], pick.amount());
        }
        for (int i = 0; i < 3; i++) { check(used[i] <= stock[i], "Invented entity"); check(filled[i] == need[i], "Changed simultaneous demand"); }
        return price;
    }
    private static Price enumerate(int d, int s, int left, int[] stock, int[] need, int edges, int[] primary, long[] secondary, Price price) {
        if (left == 0) return d == 2 ? price : enumerate(d + 1, 0, need[d + 1], stock, need, edges, primary, secondary, price);
        if (s == 3) return null; Price best = null;
        for (int amount = 0, max = edge(edges, d, s) ? Math.min(left, stock[s]) : 0; amount <= max; amount++) {
            stock[s] -= amount; var result = enumerate(d, s + 1, left - amount, stock, need, edges, primary, secondary, price.add(primary[d * 3 + s], secondary[d * 3 + s], amount)); stock[s] += amount;
            if (result != null && (best == null || result.compareTo(best) < 0)) best = result;
        }
        return best;
    }

    private static void wide() {
        long n = Long.MAX_VALUE;
        var demands = List.of(new StateEntityAllocation.Demand(0, n, true), new StateEntityAllocation.Demand(1, n, true));
        var supplies = List.of(new StateEntityAllocation.Supply<>(0, BigInteger.valueOf(n), 3), new StateEntityAllocation.Supply<>(1, BigInteger.valueOf(n), 2), new StateEntityAllocation.Supply<>(2, BigInteger.valueOf(n), 1));
        var budget = budget(1_000_000);
        try (var work = StateEntityMatching.create(demands, supplies, (d, s) -> d.slot() == 0 ? s != 1 : s != 2,
                (d, s) -> s == 1 ? 1 : 0, (d, s) -> Long.MAX_VALUE, budget)) {
            work.minimizePreferences((d, s) -> s == 1 ? 1 : 0, (d, s) -> Long.MAX_VALUE);
            int steps = 0; while (!work.step()) check(++steps < 20, "Expanded wide cohort into entities");
            check(work.feasible(), "Lost wide feasible flow");
            for (var pick : work.result()) check(pick.state() == (pick.slot() == 0 ? 2 : 0) && pick.amount() == n, "Wide cycle changed exact bottleneck / objective");
        }
        check(budget.reservedBytes() == 0, "Wide cost flow leaked");
        // An indivisible cohort is not a splittable circulation domain.
        budget = budget(1_000_000);
        try (var work = StateEntityMatching.create(List.of(new StateEntityAllocation.Demand(0, 2, false), new StateEntityAllocation.Demand(1, 2, true)), supplies, (d, s) -> true, budget)) {
            check(!work.canMinimizePreferences(), "Whole-key branch advertised splittable optimization");
            work.minimizePreferences((d, s) -> { throw new AssertionError("Unsupported whole-key cost queried"); }, null);
            while (!work.step()) {} check(work.feasible(), "Optimizer disabled ordinary whole-key allocation");
        }
        check(budget.reservedBytes() == 0, "Whole-key fallback leaked");
    }

    private static void limits() {
        var demands = List.of(new StateEntityAllocation.Demand(0, 1, true), new StateEntityAllocation.Demand(1, 1, true));
        var supplies = List.of(new StateEntityAllocation.Supply<>(0, BigInteger.ONE, 3), new StateEntityAllocation.Supply<>(1, BigInteger.ONE, 2), new StateEntityAllocation.Supply<>(2, BigInteger.ONE, 1));
        var small = new PlanningBudget(0, 1_000_000, 3000, () -> false, System::nanoTime);
        try (var work = StateEntityMatching.create(demands, supplies, (d, s) -> d.slot() == 0 ? s != 1 : s != 2, small)) {
            work.minimizePreferences((d, s) -> { throw new AssertionError("Unfunded optimizer queried costs"); }, null);
            while (!work.step()) {} check(work.feasible(), "Cost workspace prevented affordable ordinary flow");
        }
        check(small.reservedBytes() == 0, "Cost memory fallback leaked");
        var many = new ArrayList<StateEntityAllocation.Demand>(); var stock = new ArrayList<StateEntityAllocation.Supply<Integer>>();
        boolean[] allowed = new boolean[1024]; java.util.Arrays.fill(allowed, true); long[] flow = new long[1024]; BigInteger[] remaining = new BigInteger[32];
        for (int i = 0; i < 32; i++) { many.add(new StateEntityAllocation.Demand(i, 1, true)); stock.add(new StateEntityAllocation.Supply<>(i, BigInteger.ONE, 1)); flow[i * 32 + i] = 1; remaining[i] = BigInteger.ZERO; }
        var budget = budget(1_000_000);
        try (var work = StateEntityMinCost.create(many, stock, allowed, flow, remaining, (d, s) -> d.slot() == s ? 1 : 0, null, budget)) {
            check(work != null, "Bounded flow was not admitted"); while (!work.step()) {}
            for (int i = 0; i < 32; i++) {
                long used = 0, filled = 0; for (int j = 0; j < 32; j++) { check(flow[i * 32 + j] >= 0 && flow[j * 32 + i] >= 0, "Negative partial flow"); filled += flow[i * 32 + j]; used += flow[j * 32 + i]; }
                check(filled == 1 && BigInteger.valueOf(used).add(remaining[i]).equals(BigInteger.ONE), "Cutoff exposed partial circulation");
            }
        }
        check(budget.reservedBytes() == 0 && budget.nodes() <= 4096, "Local flow cutoff renewed budget / leaked");
        var cancel = new AtomicBoolean(); budget = new PlanningBudget(0, 1_000_000, 1L << 20, cancel::get, System::nanoTime);
        try (var work = StateEntityMatching.create(demands, supplies, (d, s) -> d.slot() == 0 ? s != 1 : s != 2, budget)) {
            work.minimizePreferences((d, s) -> s == 1 ? 1 : 0, null);
            while (!work.step()) if (budget.reservedBytes() > 4000) { cancel.set(true); break; }
            check(cancel.get(), "No retained cycle optimization to cancel");
            try { work.step(); throw new AssertionError("Ignored cancellation"); } catch (java.util.concurrent.CancellationException expected) { checks++; }
        }
        check(budget.reservedBytes() == 0, "Cancelled cycle optimizer leaked");
    }

    private static void lowerBoundGate() {
        var demands = List.of(new StateEntityAllocation.Demand(0, 1, true), new StateEntityAllocation.Demand(1, 1, true));
        var supplies = List.of(new StateEntityAllocation.Supply<>(0, BigInteger.ONE, 3), new StateEntityAllocation.Supply<>(1, BigInteger.ONE, 2), new StateEntityAllocation.Supply<>(2, BigInteger.ONE, 1));
        long ordinaryWork = 0;
        for (boolean optimize : List.of(false, true)) {
            var budget = budget(1_000_000);
            try (var work = StateEntityMatching.create(demands, supplies, (d, s) -> true,
                    (d, s) -> d.slot() == s ? 0 : 1, budget)) {
                if (optimize) work.minimizePreferences((d, s) -> { throw new AssertionError("Recomputed a certified row-minimum flow"); }, null);
                while (!work.step()) {} check(work.feasible() && !work.hasPreferenceLoss(), "Lost row-minimum allocation");
                for (var pick : work.result()) check(pick.slot() == pick.state(), "Changed a cheapest exact selection");
            }
            check(budget.reservedBytes() == 0, "Row-minimum gate leaked");
            if (!optimize) ordinaryWork = budget.nodes();
            else check(budget.nodes() == ordinaryWork, "Already minimal flow spent optional matching work");
        }
    }
    private static boolean edge(int edges, int d, int s) { return (edges & (1 << (d * 3 + s))) != 0; }
    private static PlanningBudget budget(long cap) { return new PlanningBudget(0, cap, 16L << 20, () -> false, System::nanoTime); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
