// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/** Quantitative preferences cannot remove edges or split physical whole-key slots. */
public final class StateChoiceCostsTest {
    private static int checks, allocations;

    public static void main(String[] args) {
        var random = new Random(0x181018cL);
        for (int sample = 0; sample < 1000; sample++) {
            int[] stock = new int[3], need = new int[3], categories = new int[9]; long[] costs = new long[9];
            for (int s = 0; s < 3; s++) { stock[s] = random.nextInt(5); need[s] = 1 + random.nextInt(4); }
            for (int e = 0; e < 9; e++) { categories[e] = random.nextInt(3); costs[e] = random.nextBoolean() ? random.nextInt(5) : Long.MAX_VALUE - random.nextInt(5); }
            int modes = random.nextInt(8), edges = sample % 2 == 0 ? 511 : random.nextInt(512);
            boolean expected = assign(0, stock.clone(), need, modes, edges);
            var demands = new ArrayList<StateEntityAllocation.Demand>(); var supplies = new ArrayList<StateEntityAllocation.Supply<Integer>>();
            for (int d = 0; d < 3; d++) demands.add(new StateEntityAllocation.Demand(d, need[d], (modes & (1 << d)) != 0));
            for (int s = 0; s < 3; s++) if (stock[s] > 0) supplies.add(new StateEntityAllocation.Supply<>(s, BigInteger.valueOf(stock[s]), 100 - s));
            var budget = budget();
            try (var work = StateEntityMatching.create(demands, supplies, (d, s) -> edge(edges, d.slot(), s),
                    (d, s) -> categories[d.slot() * 3 + s], (d, s) -> costs[d.slot() * 3 + s], budget)) {
                int steps = 0; while (!work.step()) check(++steps < 2000, "Matching lost progress");
                check(work.feasible() == expected, "Cost changed matching feasibility");
                if (expected) verify(work.result(), stock, need, modes, edges); allocations++;
            }
            check(budget.reservedBytes() == 0, "Matching cost workspace leaked");
            if (edges != 511) continue;
            budget = budget();
            try (var work = StateEntityAllocation.create(demands, supplies, s -> categories[s], s -> costs[s], budget)) {
                int steps = 0; while (!work.step()) check(++steps < 2000, "Allocation lost progress");
                check(work.feasible() == expected, "Cost changed whole-key feasibility");
                if (expected) verify(work.result(), stock, need, modes, edges); allocations++;
            }
            check(budget.reservedBytes() == 0, "Allocation cost workspace leaked");
        }
        order(); policy(); limits();
        System.out.println("StateChoiceCostsTest: allocations=" + allocations + "; assertions=" + checks);
    }

    private static void order() {
        var supplies = List.of(new StateEntityAllocation.Supply<>(0, BigInteger.ONE, 100), new StateEntityAllocation.Supply<>(1, BigInteger.ONE, 1));
        for (boolean mixed : List.of(false, true)) for (boolean primary : List.of(false, true)) {
            var demands = List.of(new StateEntityAllocation.Demand(0, 1, mixed)); int expected = primary ? 0 : 1;
            var budget = budget();
            try (var work = StateEntityAllocation.create(demands, supplies, s -> primary ? s : 0,
                    s -> Long.MAX_VALUE - s, budget)) {
                while (!work.step()) {} check(work.result().get(0).state() == expected, "Loss overrode primary rank or narrowed long");
            }
            check(budget.reservedBytes() == 0, "Ordered allocation leaked"); budget = budget();
            try (var work = StateEntityMatching.create(demands, supplies, (d, s) -> true,
                    (d, s) -> primary ? s : 0, (d, s) -> Long.MAX_VALUE - s, budget)) {
                while (!work.step()) {} check(work.result().get(0).state() == expected, "Matching loss overrode primary rank or narrowed long");
            }
            check(budget.reservedBytes() == 0, "Ordered matching leaked");
        }
    }

    private record Wear(String id, boolean destructive) implements StateFamily<Long> {
        public boolean accepts(Long state) { return state > 0; }
        public long capacity(Long state) { return destructive ? 1 : state; }
        public Long nominalAfterOne(Long state) { return destructive ? 1L : state - 1; }
    }

    private static void policy() {
        var used = new Wear("use", true); var needed = new Wear("needed", false); var budget = budget();
        try (var policy = StateChoiceRepair.<Long>create(budget)) {
            check(!policy.refineCosts(needed, List.of(used), List.of(9L, Long.MAX_VALUE)), "Unprotected rule acquired cost priority");
            check(policy.protect(needed) && !policy.protect(needed), "Qualitative feedback renewed");
            check(policy.refineCosts(needed, List.of(used), List.of(9L, Long.MAX_VALUE)), "Equal qualitative wear hid unequal cost");
            check(policy.costsEnabled() && policy.cost(used, Long.MAX_VALUE) == Long.MAX_VALUE - 1, "Wide cost overflowed");
            check(policy.cost(used, 9L) == 8, "Wrong exact one-step loss");
            check(!policy.refineCosts(needed, List.of(used), List.of(9L, Long.MAX_VALUE)), "Cost retry renewed allowance");
            policy.finishCosts(); check(!policy.costsEnabled() && !policy.canRefineCosts(), "Rejected trial did not restore ordinary preference");
            check(policy.score(used, 9L) == policy.score(used, Long.MAX_VALUE), "Cost corrupted qualitative cache");
        }
        check(budget.reservedBytes() == 0, "Cost policy leaked"); budget = budget();
        try (var policy = StateChoiceRepair.<Long>create(budget)) {
            policy.protect(needed);
            check(!policy.refineCosts(needed, List.of(used), List.of(9L, 9L)), "Equal losses started a redundant replay");
            check(!policy.canRefineCosts(), "Unhelpful analysis repeated indefinitely");
        }
        check(budget.reservedBytes() == 0, "Equal-loss analysis leaked"); budget = budget();
        try (var policy = StateChoiceRepair.<Long>create(budget)) {
            policy.protect(needed);
            for (int i = 0; i < 32_769; i++) budget.check();
            check(!policy.refineCosts(needed, List.of(used), List.of(9L, Long.MAX_VALUE)), "Analysis renewed scout lifetime");
        }
        check(budget.reservedBytes() == 0, "Expired cost policy leaked");
        budget = new PlanningBudget(0, 1_000_000, 900, () -> false, System::nanoTime);
        try (var policy = StateChoiceRepair.<Long>create(budget)) {
            check(policy.protect(needed), "Ordinary policy should fit");
            check(!policy.refineCosts(needed, List.of(used), List.of(9L, Long.MAX_VALUE)), "Unfunded cost analysis started");
            check(!policy.costsEnabled(), "Unfunded cost preference published");
        }
        check(budget.reservedBytes() == 0, "Declined cost workspace leaked");
    }

    private static void limits() {
        var demands = List.of(new StateEntityAllocation.Demand(0, 1, false));
        var supplies = List.of(new StateEntityAllocation.Supply<>(0, BigInteger.ONE, 1));
        // Retain the old qualitative preference if only its workspace fits.
        var budget = new PlanningBudget(0, 100_000, 1000, () -> false, System::nanoTime);
        try (var work = StateEntityAllocation.create(demands, supplies, s -> 0, s -> { throw new AssertionError("Unfunded cost"); }, budget)) {
            while (!work.step()) {} check(work.feasible(), "Cost memory denied an affordable old allocation");
        }
        check(budget.reservedBytes() == 0, "Cost fallback leaked");
        budget = new PlanningBudget(0, 100_000, 1320, () -> false, System::nanoTime);
        try (var work = StateEntityMatching.create(demands, supplies, (d, s) -> true, (d, s) -> 0,
                (d, s) -> { throw new AssertionError("Unfunded edge cost"); }, budget)) {
            while (!work.step()) {} check(work.feasible(), "Cost memory denied an affordable old matching");
        }
        check(budget.reservedBytes() == 0, "Matching cost fallback leaked");
        budget = budget();
        try { StateEntityAllocation.create(demands, supplies, s -> 0, s -> { throw new IllegalStateException("cost"); }, budget); throw new AssertionError("Lost callback error"); }
        catch (IllegalStateException expected) { checks++; }
        check(budget.reservedBytes() == 0, "Allocation constructor failure leaked"); budget = budget();
        try { StateEntityMatching.create(demands, supplies, (d, s) -> true, (d, s) -> 0,
                (d, s) -> { throw new IllegalStateException("cost"); }, budget); throw new AssertionError("Lost edge callback error"); }
        catch (IllegalStateException expected) { checks++; }
        check(budget.reservedBytes() == 0, "Matching constructor failure leaked");
        var cancel = new AtomicBoolean(); budget = new PlanningBudget(0, 1_000_000, 16L << 20, cancel::get, System::nanoTime);
        try (var policy = StateChoiceRepair.<Long>create(budget)) {
            var needed = new Wear("needed", false); policy.protect(needed); cancel.set(true);
            try { policy.refineCosts(needed, List.of(new Wear("use", true)), List.of(9L, Long.MAX_VALUE)); throw new AssertionError("Ignored cancellation"); }
            catch (java.util.concurrent.CancellationException expected) { checks++; }
        }
        check(budget.reservedBytes() == 0, "Cancelled cost analysis leaked");
    }

    private static void verify(List<StateEntityAllocation.Pick<Integer>> picks, int[] stock, int[] need, int modes, int edges) {
        long[] used = new long[3], filled = new long[3]; int[] keys = {-1, -1, -1};
        for (var pick : picks) {
            int d = pick.slot(), s = pick.state(); check(pick.amount() > 0 && edge(edges, d, s), "Cost bypassed eligibility");
            check((modes & (1 << d)) != 0 || keys[d] < 0 || keys[d] == s, "Split indivisible demand");
            keys[d] = s; used[s] += pick.amount(); filled[d] += pick.amount();
        }
        for (int i = 0; i < 3; i++) { check(used[i] <= stock[i], "Double used entity"); check(filled[i] == need[i], "Unfilled demand"); }
    }

    // Exhaustive primitive assignments, independent of flow and cost preferences.
    private static boolean assign(int d, int[] stock, int[] need, int modes, int edges) {
        if (d == 3) return true;
        if ((modes & (1 << d)) != 0) return split(d, 0, need[d], stock, need, modes, edges);
        for (int s = 0; s < 3; s++) if (edge(edges, d, s) && stock[s] >= need[d]) {
            stock[s] -= need[d]; boolean ok = assign(d + 1, stock, need, modes, edges); stock[s] += need[d]; if (ok) return true;
        }
        return false;
    }
    private static boolean split(int d, int s, int left, int[] stock, int[] need, int modes, int edges) {
        if (left == 0) return assign(d + 1, stock, need, modes, edges); if (s == 3) return false;
        for (int use = 0, max = edge(edges, d, s) ? Math.min(stock[s], left) : 0; use <= max; use++) {
            stock[s] -= use; boolean ok = split(d, s + 1, left - use, stock, need, modes, edges); stock[s] += use; if (ok) return true;
        }
        return false;
    }
    private static boolean edge(int edges, int d, int s) { return (edges & (1 << (3 * d + s))) != 0; }
    private static PlanningBudget budget() { return new PlanningBudget(0, 1_000_000, 16L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
