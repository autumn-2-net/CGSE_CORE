// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/** Enumerates original whole-slot assignments, independently of cohort flow. */
public final class StateEntityWholeCostTest {
    private record Price(BigInteger rank, BigInteger wear) implements Comparable<Price> {
        public int compareTo(Price p) { int c = rank.compareTo(p.rank); return c == 0 ? wear.compareTo(p.wear) : c; }
        Price add(int rank, long wear, long amount) { return new Price(this.rank.add(BigInteger.valueOf(rank).multiply(BigInteger.valueOf(amount))), this.wear.add(BigInteger.valueOf(wear).multiply(BigInteger.valueOf(amount)))); }
    }
    private static final Price ZERO = new Price(BigInteger.ZERO, BigInteger.ZERO);
    private static int checks, improved;

    public static void main(String[] args) {
        var random = new Random(0x181042cL);
        for (int sample = 0; sample < 1600; sample++) {
            int q = 2 + random.nextInt(4); int[] stock = new int[3], ranks = new int[9]; long[] costs = new long[9];
            for (int s = 0; s < 3; s++) stock[s] = random.nextInt(4 * q);
            for (int e = 0; e < 9; e++) { ranks[e] = random.nextInt(3); costs[e] = random.nextBoolean() ? random.nextInt(4) : Long.MAX_VALUE - random.nextInt(4); }
            int edges = sample % 2 == 0 ? 511 : random.nextInt(512);
            var expected = enumerate(0, stock.clone(), q, edges, ranks, costs, ZERO);
            var demands = new ArrayList<StateEntityAllocation.Demand>(); var supplies = new ArrayList<StateEntityAllocation.Supply<Integer>>();
            for (int i = 0; i < 3; i++) {
                demands.add(new StateEntityAllocation.Demand(i, q, false));
                if (stock[i] > 0) supplies.add(new StateEntityAllocation.Supply<>(i, BigInteger.valueOf(stock[i]), 3 - i));
            }
            if ((sample & 1) != 0) { Collections.reverse(demands); Collections.reverse(supplies); }
            Price ordinary = null;
            for (boolean optimize : List.of(false, true)) {
                var budget = budget();
                try (var work = StateEntityMatching.create(demands, supplies, (d, s) -> edge(edges, d.slot(), s),
                        (d, s) -> ranks[d.slot() * 3 + s], (d, s) -> costs[d.slot() * 3 + s], budget)) {
                    check(work.canMinimizePreferences() == (supplies.size() > 1), "Equal whole cohort admission changed");
                    if (optimize) work.minimizePreferences((d, s) -> ranks[d.slot() * 3 + s], (d, s) -> costs[d.slot() * 3 + s]);
                    int steps = 0; while (!work.step()) check(++steps < 500, "Lost whole matching continuation");
                    check(work.feasible() == (expected != null), "Changed whole-key feasibility");
                    if (expected != null) {
                        var used = new long[3]; var seen = new boolean[3]; Price got = ZERO;
                        for (var pick : work.result()) {
                            int d = pick.slot(), s = pick.state();
                            check(!seen[d] && pick.amount() == q && edge(edges, d, s), "Split, duplicate or ineligible whole slot");
                            seen[d] = true; used[s] += pick.amount(); got = got.add(ranks[d * 3 + s], costs[d * 3 + s], q);
                        }
                        for (int i = 0; i < 3; i++) { check(seen[i], "Missing whole slot"); check(used[i] <= stock[i], "Used an unfunded cohort"); }
                        if (!optimize) ordinary = got;
                        else { check(got.equals(expected), "Lost literal whole-key minimum: " + got + " != " + expected); if (got.compareTo(ordinary) < 0) improved++; }
                    }
                }
                check(budget.reservedBytes() == 0, "Whole optimizer leaked");
            }
        }
        boundaries(); wide(); limits();
        check(improved > 0, "Whole exchange was never exercised");
        System.out.println("StateEntityWholeCostTest: oracles=1600; improved=" + improved + "; assertions=" + checks);
    }

    private static Price enumerate(int d, int[] stock, int q, int edges, int[] ranks, long[] costs, Price price) {
        if (d == 3) return price; Price best = null;
        for (int s = 0; s < 3; s++) if (edge(edges, d, s) && stock[s] >= q) {
            stock[s] -= q; var candidate = enumerate(d + 1, stock, q, edges, ranks, costs, price.add(ranks[d * 3 + s], costs[d * 3 + s], q)); stock[s] += q;
            if (candidate != null && (best == null || candidate.compareTo(best) < 0)) best = candidate;
        }
        return best;
    }

    private static void boundaries() {
        var supplies = List.of(new StateEntityAllocation.Supply<>(0, BigInteger.TEN, 2), new StateEntityAllocation.Supply<>(1, BigInteger.TEN, 1));
        for (var demands : List.of(List.of(new StateEntityAllocation.Demand(0, 2, false), new StateEntityAllocation.Demand(1, 3, false)),
                List.of(new StateEntityAllocation.Demand(0, 2, false), new StateEntityAllocation.Demand(1, 2, true)))) {
            var budget = budget();
            try (var work = StateEntityMatching.create(demands, supplies, (d, s) -> true, budget)) {
                check(!work.canMinimizePreferences(), "Unequal / mixed whole groups became unit cohorts");
                work.minimizePreferences((d, s) -> { throw new AssertionError("Unsupported prices queried"); }, null);
                while (!work.step()) {} check(work.feasible(), "Disabled ordinary exact whole matching");
            }
            check(budget.reservedBytes() == 0, "Unsupported cohort path leaked");
        }
        // Two half-cohorts can fund a mixed slot but never a whole-key slot.
        var budget = budget();
        try (var work = StateEntityMatching.create(List.of(new StateEntityAllocation.Demand(0, 2, false), new StateEntityAllocation.Demand(1, 2, false)),
                List.of(new StateEntityAllocation.Supply<>(0, BigInteger.ONE, 3), new StateEntityAllocation.Supply<>(1, BigInteger.ONE, 2), new StateEntityAllocation.Supply<>(2, BigInteger.valueOf(2), 1)), (d, s) -> true, budget)) {
            work.minimizePreferences((d, s) -> s, null); while (!work.step()) {} check(!work.feasible(), "Fractional cohorts became physical tools");
        }
        check(budget.reservedBytes() == 0, "Fragmentation test leaked");
    }

    private static void wide() {
        long q = Long.MAX_VALUE; BigInteger unit = BigInteger.valueOf(q);
        var demands = List.of(new StateEntityAllocation.Demand(0, q, false), new StateEntityAllocation.Demand(1, q, false));
        var supplies = List.of(new StateEntityAllocation.Supply<>(0, unit.add(BigInteger.ONE), 3), new StateEntityAllocation.Supply<>(1, unit, 2), new StateEntityAllocation.Supply<>(2, unit.multiply(BigInteger.TWO).subtract(BigInteger.ONE), 1));
        var chosen = new int[]{0, 1}; var remaining = new BigInteger[]{BigInteger.ONE, BigInteger.ZERO, supplies.get(2).count()};
        boolean[] allowed = {true, false, true, true, true, false}; var budget = budget();
        try (var work = StateEntityMinCost.createWhole(demands, supplies, allowed, chosen, remaining, (d, s) -> s == 1 ? 1 : 0, (d, s) -> Long.MAX_VALUE, budget)) {
            check(work != null, "Wide cohort optimization declined"); int steps = 0; while (!work.step()) check(++steps < 12, "Expanded entities in a wide whole group");
            check(Arrays.equals(chosen, new int[]{2, 0}), "Failed a whole-cohort alternating path");
            check(remaining[0].equals(BigInteger.ONE) && remaining[1].equals(unit) && remaining[2].equals(unit.subtract(BigInteger.ONE)), "Lost physical remainders");
        }
        check(budget.reservedBytes() == 0 && budget.nodes() < 1000, "Wide unit view inflated / leaked");
    }

    private static void limits() {
        var demands = List.of(new StateEntityAllocation.Demand(0, 2, false), new StateEntityAllocation.Demand(1, 2, false));
        var supplies = List.of(new StateEntityAllocation.Supply<>(0, BigInteger.TWO, 3), new StateEntityAllocation.Supply<>(1, BigInteger.TWO, 2), new StateEntityAllocation.Supply<>(2, BigInteger.TWO, 1));
        var budget = new PlanningBudget(0, 1_000_000, 3000, () -> false, System::nanoTime);
        try (var work = StateEntityMatching.create(demands, supplies, (d, s) -> d.slot() == 0 ? s != 1 : s != 2, budget)) {
            work.minimizePreferences((d, s) -> { throw new AssertionError("Unfunded cohort optimizer evaluated prices"); }, null);
            while (!work.step()) {} check(work.feasible(), "Cost workspace disabled an affordable whole witness");
        }
        check(budget.reservedBytes() == 0, "Cohort memory refusal leaked");
        var many = new ArrayList<StateEntityAllocation.Demand>(); var stock = new ArrayList<StateEntityAllocation.Supply<Integer>>();
        var chosen = new int[32]; var remaining = new BigInteger[32]; boolean[] edges = new boolean[1024]; Arrays.fill(edges, true);
        for (int i = 0; i < 32; i++) { many.add(new StateEntityAllocation.Demand(i, 3, false)); stock.add(new StateEntityAllocation.Supply<>(i, BigInteger.valueOf(5), 1)); chosen[i] = i; remaining[i] = BigInteger.TWO; }
        budget = budget();
        try (var work = StateEntityMinCost.createWhole(many, stock, edges, chosen, remaining, (d, s) -> d.slot() == s ? 1 : 0, null, budget)) {
            check(work != null, "Small unit view not admitted"); while (!work.step()) {}
            var used = new int[32]; for (int s : chosen) { check(s >= 0 && s < 32, "Lost whole choice on cutoff"); used[s] += 3; }
            for (int i = 0; i < 32; i++) check(remaining[i].add(BigInteger.valueOf(used[i])).equals(BigInteger.valueOf(5)), "Partial physical exchange escaped");
        }
        check(budget.reservedBytes() == 0 && budget.nodes() <= 4096, "Whole optimizer renewed its allowance");
        var cancel = new AtomicBoolean(); budget = new PlanningBudget(0, 1_000_000, 1L << 20, cancel::get, System::nanoTime);
        try (var work = StateEntityMatching.create(demands, supplies, (d, s) -> d.slot() == 0 ? s != 1 : s != 2, budget)) {
            work.minimizePreferences((d, s) -> s == 1 ? 1 : 0, null);
            while (!work.step()) if (budget.reservedBytes() > 4000) { cancel.set(true); break; }
            check(cancel.get(), "No retained whole optimizer to cancel");
            try { work.step(); throw new AssertionError("Ignored cancellation"); } catch (java.util.concurrent.CancellationException expected) { checks++; }
        }
        check(budget.reservedBytes() == 0, "Cancelled whole optimizer leaked");
        budget = budget(); chosen = new int[]{0, 1}; remaining = new BigInteger[]{BigInteger.ZERO, BigInteger.ZERO, BigInteger.TWO};
        try { StateEntityMinCost.createWhole(demands, supplies, new boolean[]{true, false, true, true, true, false}, chosen, remaining,
                (d, s) -> { throw new IllegalArgumentException("fixture failure"); }, null, budget); throw new AssertionError("Lost constructor failure"); }
        catch (IllegalArgumentException expected) { checks++; }
        check(budget.reservedBytes() == 0 && Arrays.equals(chosen, new int[]{0, 1}) && remaining[2].equals(BigInteger.TWO), "Failed construction changed ownership");
    }

    private static boolean edge(int mask, int d, int s) { return (mask & (1 << (d * 3 + s))) != 0; }
    private static PlanningBudget budget() { return new PlanningBudget(0, 1_000_000, 16L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
