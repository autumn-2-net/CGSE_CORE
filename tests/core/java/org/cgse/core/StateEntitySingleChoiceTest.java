// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/** Compare the linear whole-slot choice with physical enumeration and the
 * retained allocator, including rank, exact cost, best fit and stable ties. */
public final class StateEntitySingleChoiceTest {
    private static int cases, checks;

    private record Family(int mask, long[] capacities) implements StateFamily<Integer> {
        public String id() { return "single"; }
        public boolean accepts(Integer key) { return (mask & (1 << key)) != 0; }
        public long capacity(Integer key) { return capacities[key]; }
        public Integer nominalAfterOne(Integer key) { throw new AssertionError("Selection executed a transition"); }
    }

    public static void main(String[] args) {
        for (int encoded = 0; encoded < 125; encoded++) for (int amount = 1; amount <= 5; amount++)
            for (int mask = 0; mask < 8; mask++) for (boolean reversed : List.of(false, true)) {
                var owned = new LinkedHashMap<Integer, BigInteger>();
                int[] counts = {encoded % 5, encoded / 5 % 5, encoded / 25};
                for (int at = 0; at < 3; at++) {
                    int key = reversed ? 2 - at : at;
                    if (counts[key] > 0) owned.put(key, BigInteger.valueOf(counts[key]));
                }
                compare(owned, amount, new Family(mask, new long[]{10, 10, Long.MAX_VALUE}), null, null);
            }
        var random = new Random(0x181018dL);
        for (int sample = 0; sample < 2000; sample++) {
            var owned = new LinkedHashMap<Integer, BigInteger>();
            int[] rank = new int[3]; long[] costs = new long[3], capacities = new long[3];
            for (int key = 0; key < 3; key++) {
                owned.put(key, sample % 11 == 0 ? BigInteger.ONE.shiftLeft(80).add(BigInteger.valueOf(key)) : BigInteger.valueOf(1 + random.nextInt(7)));
                rank[key] = random.nextInt(5) - 2;
                costs[key] = random.nextBoolean() ? random.nextInt(5) : Long.MAX_VALUE - random.nextInt(5);
                capacities[key] = random.nextInt(4);
            }
            compare(owned, sample % 11 == 0 ? Long.MAX_VALUE : 1 + random.nextInt(7), new Family(random.nextInt(8), capacities),
                    key -> rank[key], key -> costs[key]);
        }
        boundaries();
        lazyCosts();
        System.out.println("StateEntitySingleChoiceTest: cases=" + cases + "; assertions=" + checks);
    }

    private static void compare(Map<Integer, BigInteger> owned, long amount, StateFamily<Integer> family,
                                ToIntFunction<Integer> preference, ToLongFunction<Integer> cost) {
        var original = new LinkedHashMap<>(owned);
        var demands = List.of(new StateEntityAllocation.Demand(7, amount, false));
        var supplies = new ArrayList<StateEntityAllocation.Supply<Integer>>();
        boolean physicallyPossible = false;
        for (var entry : owned.entrySet()) if (family.accepts(entry.getKey()) && family.capacity(entry.getKey()) > 0) {
            supplies.add(new StateEntityAllocation.Supply<>(entry.getKey(), entry.getValue(), family.capacity(entry.getKey())));
            physicallyPossible |= entry.getValue().compareTo(BigInteger.valueOf(amount)) >= 0;
        }
        var budget = budget();
        var picked = StateEntitySingleChoice.select(demands.get(0), owned, family, true, preference, cost, budget);
        check(picked != null && (picked.pick() != null) == physicallyPossible, "Invented or lost a whole physical cohort");
        try (var general = StateEntityAllocation.create(demands, supplies, preference, cost, budget)) {
            while (!general.step()) {}
            check(picked.failure() == general.failure(), "Changed shortage / fragmentation classification");
            if (physicallyPossible) check(general.result().equals(List.of(picked.pick())), "Changed general allocator's first assignment");
        }
        check(owned.equals(original) && budget.reservedBytes() == 0, "Selection changed stock / retained workspace");
        cases++;
    }

    private static void boundaries() {
        var family = new Family(7, new long[]{1, 1, 1});
        var demand = new StateEntityAllocation.Demand(0, 2, false);
        var owned = new LinkedHashMap<Integer, BigInteger>(); owned.put(0, BigInteger.ONE); owned.put(1, BigInteger.ONE);
        var budget = budget();
        check(StateEntitySingleChoice.select(demand, owned, family, false, null, null, budget).failure() ==
                StateEntityAllocation.Failure.WHOLE_KEY_FRAGMENTATION, "Mixed two partial whole-key cohorts");
        check(StateEntitySingleChoice.select(new StateEntityAllocation.Demand(0, 2, true), owned, family, false, null, null, budget) == null,
                "Intercepted a mixed slot");
        check(StateEntitySingleChoice.select(demand, owned, new Family(1, new long[]{1, 1, 1}), false, null, null, budget) == null,
                "Hid invalid nonshared state algebra");
        owned.put(0, BigInteger.ZERO);
        check(StateEntitySingleChoice.select(demand, owned, family, true, null, null, budget) == null, "Accepted invalid entity count");
        owned.put(0, null);
        check(StateEntitySingleChoice.select(demand, owned, family, true, null, null, budget) == null, "Accepted null entity count");
        owned.remove(0); owned.put(null, BigInteger.ONE);
        check(StateEntitySingleChoice.select(demand, owned, family, true, null, null, budget) == null, "Accepted null entity key");
        var wide = new LinkedHashMap<Integer, BigInteger>();
        for (int key = 0; key < 129; key++) wide.put(key, BigInteger.TEN);
        var any = new StateFamily<Integer>() {
            public String id() { return "any"; }
            public boolean accepts(Integer key) { return true; }
            public long capacity(Integer key) { return 1; }
            public Integer nominalAfterOne(Integer key) { return key; }
        };
        check(StateEntitySingleChoice.select(demand, wide, any, true, null, null, budget) == null, "Unbounded synchronous scan");
        wide.remove(128);
        var picked = StateEntitySingleChoice.select(demand, wide, any, true, key -> 0, key -> Long.MAX_VALUE, budget);
        check(picked.pick().state() == 0, "Unstable source order for equal rank / count / capacity");
        budget.cancel();
        try { StateEntitySingleChoice.select(demand, wide, any, true, null, null, budget); throw new AssertionError("Ignored cancellation"); }
        catch (CancellationException expected) { checks++; }
        var limited = new PlanningBudget(0, 4, 512, () -> false, System::nanoTime);
        try { StateEntitySingleChoice.select(demand, wide, any, true, null, null, limited); throw new AssertionError("Uncharged scan"); }
        catch (PlanningBudget.Exhausted expected) { checks++; }
        var failed = budget();
        try {
            StateEntitySingleChoice.select(demand, wide, any, true, key -> { throw new IllegalStateException("callback"); }, null, failed);
            throw new AssertionError("Lost callback failure");
        } catch (IllegalStateException expected) { check(expected.getMessage().equals("callback"), "Unexpected callback failure"); }
        check(failed.reservedBytes() == 0 && limited.reservedBytes() == 0 && wide.size() == 128, "Interrupted selection leaked / mutated inventory");
    }

    private static void lazyCosts() {
        var family = new Family(7, new long[]{3, 4, 5});
        var demand = new StateEntityAllocation.Demand(0, 2, false);
        var owned = new LinkedHashMap<Integer, BigInteger>();
        owned.put(0, BigInteger.ONE); owned.put(1, BigInteger.TWO);
        ToIntFunction<Integer> unusedRank = key -> { throw new AssertionError("Ranked the sole feasible cohort"); };
        ToLongFunction<Integer> unusedCost = key -> { throw new AssertionError("Computed an irrelevant tie cost"); };
        check(StateEntitySingleChoice.select(demand, owned, family, true, unusedRank, unusedCost, budget()).pick().state() == 1,
                "Lost sole eligible cohort");
        owned.put(2, BigInteger.TWO);
        check(StateEntitySingleChoice.select(demand, owned, family, true, key -> key, unusedCost, budget()).pick().state() == 1,
                "Cost overrode a better rank");
        // Replace a best candidate by rank before a later tie needs its cost.
        // A stale/uninitialized best cost would incorrectly keep state 1.
        owned.put(0, BigInteger.TWO);
        int[] costs = {0};
        var selected = StateEntitySingleChoice.select(demand, owned, family, true, key -> key == 0 ? 9 : 1,
                key -> { costs[0]++; return key == 1 ? Long.MAX_VALUE : 0; }, budget());
        check(selected.pick().state() == 2 && costs[0] == 2, "Lost deferred best-candidate cost");
        var single = new LinkedHashMap<Integer, BigInteger>(); single.put(0, BigInteger.TWO);
        compare(single, 2, family, key -> -1, key -> Long.MAX_VALUE);
    }

    private static PlanningBudget budget() { return new PlanningBudget(0, 100_000, 4L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
