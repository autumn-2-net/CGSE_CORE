// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exhaustive tiny entity oracle, including cases where best-fit must backtrack. */
public final class StateEntityAllocationTest {
    private static int checks, cases;

    public static void main(String[] args) {
        for (int encoded = 0; encoded < 125; encoded++) {
            int[] stock = {encoded % 5, encoded / 5 % 5, encoded / 25};
            for (int sizes = 0; sizes < 27; sizes++) for (int modes = 0; modes < 8; modes++) {
                int[] demand = {1 + sizes % 3, 1 + sizes / 3 % 3, 1 + sizes / 9};
                boolean[] mixed = {(modes & 1) != 0, (modes & 2) != 0, (modes & 4) != 0};
                boolean expected = oracle(stock.clone(), demand, mixed, 0);
                check(run(stock, demand, mixed, false) == expected, "Entity oracle mismatch");
                if (sizes == 17) check(run(stock, demand, mixed, true) == expected, "Supply order changed feasibility");
            }
        }
        check(run(new int[]{6, 4}, new int[]{3, 3, 2, 2}, new boolean[4], false), "Best-fit did not backtrack");
        check(!run(new int[]{3, 3}, new int[]{2, 2, 2}, new boolean[3], false), "Indivisible keys were mixed");
        largeAndInterrupted();
        System.out.println("StateEntityAllocationTest: " + cases + " oracle cases; " + checks + " assertions passed");
    }

    private static boolean run(int[] stock, int[] amounts, boolean[] modes, boolean reverse) {
        var supply = new ArrayList<StateEntityAllocation.Supply<Integer>>();
        for (int i = 0; i < stock.length; i++) if (stock[i] > 0)
            supply.add(new StateEntityAllocation.Supply<>(i, BigInteger.valueOf(stock[i]), 100L - i));
        if (reverse) Collections.reverse(supply);
        var demands = new ArrayList<StateEntityAllocation.Demand>();
        for (int i = 0; i < amounts.length; i++) demands.add(new StateEntityAllocation.Demand(i, amounts[i], modes[i]));
        var budget = budget();
        boolean feasible;
        try (var allocation = StateEntityAllocation.create(demands, supply, budget)) {
            check(allocation != null, "Allocation refused a small workspace");
            int steps = 0;
            while (!allocation.step()) check(++steps < 1000, "Lost retained search progress");
            feasible = allocation.feasible();
            if (feasible) {
                long[] used = new long[stock.length], filled = new long[amounts.length];
                var key = new HashMap<Integer, Integer>();
                for (var pick : allocation.result()) {
                    check(pick.amount() > 0, "Empty physical pick");
                    used[pick.state()] += pick.amount(); filled[pick.slot()] += pick.amount();
                    Integer previous = key.put(pick.slot(), pick.state());
                    check(modes[pick.slot()] || previous == null || previous.equals(pick.state()), "Mixed an indivisible input");
                }
                for (int i = 0; i < used.length; i++) check(used[i] <= stock[i], "Shared one entity between inputs");
                for (int i = 0; i < filled.length; i++) check(filled[i] == amounts[i], "Lost slot demand");
            }
        }
        check(budget.reservedBytes() == 0, "Allocation memory leaked");
        cases++; return feasible;
    }

    // A deliberately unsorted enumeration in original slot order. Mixed inputs
    // enumerate all distributions, unlike the production residual-fill path.
    private static boolean oracle(int[] stock, int[] demand, boolean[] mixed, int slot) {
        if (slot == demand.length) return true;
        if (mixed[slot]) return split(stock, demand, mixed, slot, 0, demand[slot]);
        for (int i = 0; i < stock.length; i++) if (stock[i] >= demand[slot]) {
            stock[i] -= demand[slot]; boolean result = oracle(stock, demand, mixed, slot + 1); stock[i] += demand[slot];
            if (result) return true;
        }
        return false;
    }
    private static boolean split(int[] stock, int[] demand, boolean[] mixed, int slot, int key, int left) {
        if (key == stock.length) return left == 0 && oracle(stock, demand, mixed, slot + 1);
        for (int n = 0; n <= Math.min(left, stock[key]); n++) {
            stock[key] -= n; boolean result = split(stock, demand, mixed, slot, key + 1, left - n); stock[key] += n;
            if (result) return true;
        }
        return false;
    }

    private static void largeAndInterrupted() {
        var supply = List.of(new StateEntityAllocation.Supply<>("tool", BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO), Long.MAX_VALUE));
        var demands = List.of(new StateEntityAllocation.Demand(0, Long.MAX_VALUE, false), new StateEntityAllocation.Demand(1, Long.MAX_VALUE, true));
        var budget = budget();
        try (var allocation = StateEntityAllocation.create(demands, supply, budget)) {
            while (!allocation.step()) {}
            check(allocation.feasible() && allocation.result().size() == 2, "Overflowed entity inventory");
        }
        check(budget.reservedBytes() == 0, "Large allocation leaked");
        var deep = new ArrayList<StateEntityAllocation.Demand>();
        for (int i = 0; i < 4096; i++) deep.add(new StateEntityAllocation.Demand(i, 1, false));
        budget = budget();
        try (var allocation = StateEntityAllocation.create(deep, supply, budget)) {
            while (!allocation.step()) {}
            check(allocation.feasible(), "Deep allocation used recursive Java frames");
        }
        check(budget.reservedBytes() == 0, "Deep allocation leaked");
        for (int stop = 0; stop < 10; stop++) {
            budget = budget();
            try (var allocation = StateEntityAllocation.create(demands, supply, budget)) {
                for (int i = 0; i < stop && !allocation.step(); i++) {}
            }
            check(budget.reservedBytes() == 0 && supply.get(0).count().equals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO)), "Abandonment changed the source inventory");
        }
        var cancelled = new AtomicBoolean();
        budget = new PlanningBudget(0, 100_000, 4L << 20, cancelled::get, System::nanoTime);
        try (var allocation = StateEntityAllocation.create(demands, supply, budget)) {
            cancelled.set(true);
            boolean caught = false;
            try { allocation.step(); } catch (java.util.concurrent.CancellationException expected) { caught = true; }
            check(caught, "Ignored cancellation");
        }
        check(budget.reservedBytes() == 0, "Cancelled allocation leaked");
        budget = new PlanningBudget(0, 100_000, 128, () -> false, System::nanoTime);
        check(StateEntityAllocation.create(demands, supply, budget) == null && budget.reservedBytes() == 0, "Workspace refusal reserved memory");
    }

    private static PlanningBudget budget() { return new PlanningBudget(0, 100_000, 4L << 20, () -> false, System::nanoTime); }
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
