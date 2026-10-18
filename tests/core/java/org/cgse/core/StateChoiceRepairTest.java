// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/** Arbitrary search preferences must preserve the exact one-firing domain. */
public final class StateChoiceRepairTest {
    private static int checks, cases;

    public static void main(String[] args) {
        var random = new Random(0x161016cL);
        for (int sample = 0; sample < 3000; sample++) {
            int[] stock = new int[3], need = new int[3], rank = new int[9];
            for (int i = 0; i < 3; i++) { stock[i] = random.nextInt(5); need[i] = 1 + random.nextInt(4); }
            for (int i = 0; i < rank.length; i++) rank[i] = random.nextInt(5) - 2;
            int modes = random.nextInt(8), edges = sample % 2 == 0 ? 511 : random.nextInt(512);
            compare(stock, need, modes, edges, rank);
        }
        wide(); feedback(); capacityFeedback(); failures();
        System.out.println("StateChoiceRepairTest: allocations=" + cases + "; assertions=" + checks);
    }

    private static void compare(int[] stock, int[] need, int modes, int edges, int[] ranks) {
        boolean expected = assign(0, stock.clone(), need, modes, edges);
        var demands = new ArrayList<StateEntityAllocation.Demand>();
        var supplies = new ArrayList<StateEntityAllocation.Supply<Integer>>();
        for (int d = 0; d < need.length; d++) demands.add(new StateEntityAllocation.Demand(d, need[d], (modes & (1 << d)) != 0));
        for (int s = 0; s < stock.length; s++) if (stock[s] > 0)
            supplies.add(new StateEntityAllocation.Supply<>(s, BigInteger.valueOf(stock[s]), 100 - s));
        var budget = budget();
        try (var work = StateEntityMatching.create(demands, supplies, (d, s) -> edge(edges, d.slot(), s),
                (d, s) -> ranks[3 * d.slot() + s], budget)) {
            int steps = 0; while (!work.step()) check(++steps < 2000, "Matching preference lost retained progress");
            check(work.feasible() == expected, "Preferred flow changed feasibility: " + Arrays.toString(stock) + "; " + Arrays.toString(need));
            if (expected) verify(work.result(), stock, need, modes, edges);
        }
        check(budget.reservedBytes() == 0, "Preferred flow leaked"); cases++;
        if (edges != 511) return;
        budget = budget();
        try (var work = StateEntityAllocation.create(demands, supplies, s -> ranks[s], budget)) {
            int steps = 0; while (!work.step()) check(++steps < 2000, "Preferred whole-key allocation lost progress");
            check(work.feasible() == expected, "Preferred allocation changed feasibility");
            if (expected) verify(work.result(), stock, need, modes, edges);
        }
        check(budget.reservedBytes() == 0, "Preferred allocation leaked"); cases++;
    }

    private static void verify(List<StateEntityAllocation.Pick<Integer>> picks, int[] stock, int[] need, int modes, int edges) {
        long[] used = new long[stock.length], filled = new long[need.length]; int[] keys = new int[need.length]; Arrays.fill(keys, -1);
        for (var pick : picks) {
            int d = pick.slot(), s = pick.state();
            check(pick.amount() > 0 && edge(edges, d, s), "Preference bypassed eligibility");
            check((modes & (1 << d)) != 0 || keys[d] < 0 || keys[d] == s, "Split indivisible demand");
            keys[d] = s; used[s] += pick.amount(); filled[d] += pick.amount();
        }
        for (int s = 0; s < stock.length; s++) check(used[s] <= stock[s], "Double-used entity");
        for (int d = 0; d < need.length; d++) check(filled[d] == need[d], "Unfilled demand");
    }

    // Independent literal assignments in original slot/key order, with no flow
    // graph, preferences, capacity ranking or production allocation helper.
    private static boolean assign(int d, int[] stock, int[] need, int modes, int edges) {
        if (d == need.length) return true;
        if ((modes & (1 << d)) != 0) return split(d, 0, need[d], stock, need, modes, edges);
        for (int s = 0; s < stock.length; s++) if (edge(edges, d, s) && stock[s] >= need[d]) {
            stock[s] -= need[d]; boolean ok = assign(d + 1, stock, need, modes, edges); stock[s] += need[d];
            if (ok) return true;
        }
        return false;
    }
    private static boolean split(int d, int s, int remaining, int[] stock, int[] need, int modes, int edges) {
        if (remaining == 0) return assign(d + 1, stock, need, modes, edges);
        if (s == stock.length) return false;
        int max = edge(edges, d, s) ? Math.min(stock[s], remaining) : 0;
        for (int use = 0; use <= max; use++) {
            stock[s] -= use; boolean ok = split(d, s + 1, remaining - use, stock, need, modes, edges); stock[s] += use;
            if (ok) return true;
        }
        return false;
    }
    private static boolean edge(int mask, int d, int s) { return (mask & (1 << (3 * d + s))) != 0; }

    private static void wide() {
        var demands = List.of(new StateEntityAllocation.Demand(0, Long.MAX_VALUE, false),
                new StateEntityAllocation.Demand(1, Long.MAX_VALUE, true));
        var supplies = List.of(new StateEntityAllocation.Supply<>(0, BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO), 10),
                new StateEntityAllocation.Supply<>(1, BigInteger.ONE, 1));
        var budget = budget();
        try (var work = StateEntityMatching.create(demands, supplies, (d, s) -> true, (d, s) -> s == 0 ? 100 : 0, budget)) {
            int steps = 0; while (!work.step()) check(++steps < 20, "Expanded wide matching");
            check(work.feasible(), "Preference suppressed unavoidable high-score supply");
        }
        check(budget.reservedBytes() == 0, "Wide matching leaked");
        budget = budget();
        try (var work = StateEntityAllocation.create(demands, supplies, s -> s == 0 ? 100 : 0, budget)) {
            int steps = 0; while (!work.step()) check(++steps < 20, "Expanded wide allocation");
            check(work.feasible(), "Preference became an allocation restriction");
        }
        check(budget.reservedBytes() == 0, "Wide allocation leaked");
    }

    private record Rule(int idNumber, boolean changing) implements StateFamily<Integer> {
        public String id() { return (changing ? "use" : "read") + idNumber; }
        public boolean accepts(Integer key) { return changing || (key & (1 << idNumber)) != 0; }
        public long capacity(Integer key) { return accepts(key) ? 1 : 0; }
        public Integer nominalAfterOne(Integer key) { return changing ? 0 : key; }
    }

    private static void feedback() {
        var budget = budget(); var use = new Rule(0, true);
        try (var policy = StateChoiceRepair.<Integer>create(budget)) {
            check(policy != null, "Small feedback rejected");
            for (int bit = 0; bit < 8; bit++) {
                var rule = new Rule(bit, false);
                check(policy.protect(rule), "Lost a distinct failed rule");
                check(!policy.protect(rule), "Repeated failure renewed a hint");
                for (int key = 0; key < 256; key++) {
                    int expected = (key & ((1 << (bit + 1)) - 1)) << 8;
                    check(policy.score(use, key) == expected, "Incorrect eligibility-loss score");
                    check(policy.score(use, key) == expected, "Cached score changed");
                }
                check(policy.score(rule, 1 << bit) == 0, "Unchanged state was penalized");
            }
            check(!policy.protect(new Rule(8, false)), "Expanded unbounded protected domain");
            check(budget.reservedBytes() < 100_000, "Score cache unbounded");
        }
        check(budget.reservedBytes() == 0, "Score cache / revision clear leaked");
        budget = budget();
        try (var policy = StateChoiceRepair.<Integer>create(budget)) {
            check(policy.protect(new Rule(0, false)), "Initial protect failed");
            for (int i = 0; i < 32_769; i++) budget.check();
            check(!policy.active() && !policy.protect(new Rule(1, false)), "Downstream work renewed feedback allowance");
        }
        check(budget.reservedBytes() == 0, "Expired policy leaked");
    }

    private static void capacityFeedback() {
        StateFamily<Long> needed = new StateFamily<>() {
            public String id() { return "positive-tool"; }
            public boolean accepts(Long state) { return state > 0; }
            public long capacity(Long state) { return Math.max(0, state); }
            public Long nominalAfterOne(Long state) { return state == 1 ? null : state - 1; }
        };
        StateFamily<Long> use = new StateFamily<>() {
            public String id() { return "either-tool"; }
            public boolean accepts(Long state) { return state != 0; }
            public long capacity(Long state) { return Math.abs(state); }
            public Long nominalAfterOne(Long state) { return Math.abs(state) == 1 ? null : state > 0 ? state - 1 : state + 1; }
        };
        var budget = budget();
        try (var policy = StateChoiceRepair.<Long>create(budget)) {
            check(policy.protect(needed), "Capacity feedback rejected");
            check(policy.score(use, -31L) == 0, "Unrelated tool was penalized");
            check(policy.score(use, 31L) > policy.score(use, -31L), "Lost uses were invisible while still eligible");
            check(policy.score(use, 1L) > policy.score(use, 31L), "Wear preference replaced old eligibility priority");
            check(policy.score(use, Long.MAX_VALUE) == policy.score(use, 31L), "Wide capacities overflowed or became entity counts");
            int cached = policy.score(use, Long.MAX_VALUE);
            check(cached == policy.score(use, Long.MAX_VALUE), "Cached capacity preference changed");
            check(!policy.protect(needed), "Capacity feedback renewed the same failed rule");
        }
        check(budget.reservedBytes() == 0, "Capacity feedback leaked");
    }

    private static void failures() {
        var tiny = new PlanningBudget(0, 100_000, 1, () -> false, System::nanoTime);
        check(StateChoiceRepair.create(tiny) == null && tiny.reservedBytes() == 0, "Policy ignored workspace limit");
        var small = new PlanningBudget(0, 1024, 1 << 20, () -> false, System::nanoTime);
        check(StateChoiceRepair.create(small) == null && small.reservedBytes() == 0, "Policy ignored low work admission");
        var cancel = new AtomicBoolean();
        var budget = new PlanningBudget(0, 1_000_000, 16 << 20, cancel::get, System::nanoTime);
        try (var policy = StateChoiceRepair.<Integer>create(budget)) {
            policy.protect(new Rule(0, false)); cancel.set(true);
            try { policy.protect(new Rule(1, false)); throw new AssertionError("Ignored cancellation"); }
            catch (java.util.concurrent.CancellationException expected) { checks++; }
        }
        check(budget.reservedBytes() == 0, "Cancelled policy leaked");
        var demands = List.of(new StateEntityAllocation.Demand(0, 1, true));
        var supplies = List.of(new StateEntityAllocation.Supply<>(0, BigInteger.ONE, 1));
        // Only the ordinary workspace fits. Optional scores must yield before
        // this exact current-firing allocator is rejected for lack of memory.
        budget = new PlanningBudget(0, 100_000, 900, () -> false, System::nanoTime);
        try (var work = StateEntityAllocation.create(demands, supplies, s -> { throw new AssertionError("Unfunded preference evaluated"); }, budget)) {
            check(work != null, "Optional preference blocked an affordable allocation");
            while (!work.step()) {}
            check(work.feasible(), "Ordinary allocation fallback lost its witness");
        }
        check(budget.reservedBytes() == 0, "Optional allocation workspace leaked");
        budget = new PlanningBudget(0, 100_000, 1281, () -> false, System::nanoTime);
        try (var work = StateEntityMatching.create(demands, supplies, (d, s) -> true,
                (d, s) -> { throw new AssertionError("Unfunded edge preference evaluated"); }, budget)) {
            check(work != null, "Optional edge preference blocked affordable matching");
            while (!work.step()) {}
            check(work.feasible(), "Ordinary matching fallback lost its witness");
        }
        check(budget.reservedBytes() == 0, "Optional matching workspace leaked");
        budget = budget();
        try { StateEntityAllocation.create(demands, supplies, s -> { throw new IllegalStateException("callback"); }, budget);
            throw new AssertionError("Swallowed preference failure"); }
        catch (IllegalStateException expected) { checks++; }
        check(budget.reservedBytes() == 0, "Failed allocation construction leaked");
        budget = budget();
        try { StateEntityMatching.create(demands, supplies, (d, s) -> true, (d, s) -> { throw new IllegalStateException("callback"); }, budget);
            throw new AssertionError("Swallowed matching preference failure"); }
        catch (IllegalStateException expected) { checks++; }
        check(budget.reservedBytes() == 0, "Failed matching construction leaked");
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 1_000_000, 16L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
