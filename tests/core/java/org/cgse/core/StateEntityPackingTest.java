// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fragmented entity inventories, exact witnesses, and retained budget boundaries. */
public final class StateEntityPackingTest {
    private static int cases, checks;

    public static void main(String[] args) {
        fragmented(); smallOracle(); boundaries();
        System.out.println("StateEntityPackingTest: cases=" + cases + "; assertions=" + checks);
    }

    private static void fragmented() {
        for (long amount : new long[]{2, 3, 7, Long.MAX_VALUE / 2})
            for (boolean reverse : List.of(false, true)) for (boolean prefer : List.of(false, true))
                for (int mode = 0; mode < 3; mode++) {
                    // Each exact key can fill only one whole slot. Total
                    // entities and splittable flow both suffice for 12 slots.
                    long[] stock = new long[11], need = new long[12];
                    Arrays.fill(stock, 2 * amount - 1); Arrays.fill(need, amount);
                    check(!run(stock, need, false, mode, reverse, prefer, budget(20_000, 4L << 20)),
                            "Fragmented inventory became a witness");
                    stock[10]++;
                    check(run(stock, need, false, mode, reverse, prefer, budget(20_000, 4L << 20)),
                            "Rejected a funded second slot on one exact key");
                }
        for (int mode = 0; mode < 3; mode++) {
            // Both threshold bounds pass: two >=6 slots fit in the two keys;
            // three >=4 slots fit in four relaxed positions. Exact packing
            // still fails, so a successful bound must resume the exact DFS.
            check(!run(new long[]{8, 8}, new long[]{6, 6, 4}, false, mode, false, false, budget(20_000, 1 << 20)),
                    "Necessary bound was used as a sufficient allocation");
        }
        for (int mode = 0; mode < 2; mode++) {
            check(run(new long[]{8, 8}, new long[]{6, 6, 4}, true, mode, false, true, budget(20_000, 1 << 20)),
                    "Mixed remainder was subjected to a whole-key bound");
            check(!run(new long[]{5, 5}, new long[]{4, 3, 3}, false, mode, true, true, budget(20_000, 1 << 20)),
                    "Lost the second distinct threshold");
            check(run(new long[]{6, 4}, new long[]{3, 3, 2, 2}, false, mode, false, true, budget(20_000, 1 << 20)),
                    "Destroyed a retained successful backtrack");
        }
    }

    private static void smallOracle() {
        var random = new Random(0x5061636bL);
        for (int i = 0; i < 1500; i++) {
            long[] stock = new long[4], need = new long[5];
            for (int s = 0; s < stock.length; s++) stock[s] = 1 + random.nextInt(8);
            for (int d = 0; d < need.length; d++) need[d] = 1 + random.nextInt(6);
            boolean expected = literal(stock.clone(), need, 0);
            for (int mode = 0; mode < 2; mode++)
                check(run(stock, need, false, mode, (i & 1) == 0, (i & 2) != 0, budget(100_000, 1 << 20)) == expected,
                        "Literal packing oracle mismatch");
        }
    }

    // Original slot order, literal placement, no sorting, floor bounds or flow.
    private static boolean literal(long[] stock, long[] need, int slot) {
        if (slot == need.length) return true;
        for (int s = 0; s < stock.length; s++) if (stock[s] >= need[slot]) {
            stock[s] -= need[slot]; boolean ok = literal(stock, need, slot + 1); stock[s] += need[slot];
            if (ok) return true;
        }
        return false;
    }

    private static boolean run(long[] stock, long[] need, boolean mixedLast, int mode, boolean reverse,
            boolean prefer, PlanningBudget budget) {
        var ds = demands(need, mixedLast); var ss = supplies(stock);
        if (reverse) { Collections.reverse(ds); Collections.reverse(ss); }
        boolean feasible;
        if (mode == 0) try (var work = StateEntityAllocation.create(ds, ss, prefer ? s -> s % 3 : null, budget)) {
            check(work != null, "Refused allocation workspace");
            while (!work.step()) {}
            feasible = work.feasible();
            if (feasible) verify(work.result(), stock, need, mixedLast, mode);
        } else try (var work = StateEntityMatching.create(ds, ss, (d, s) -> eligible(mode, need.length, d.slot(), s),
                prefer ? (d, s) -> s % 3 : null, budget)) {
            check(work != null, "Refused matching workspace");
            while (!work.step()) {}
            feasible = work.feasible();
            if (feasible) verify(work.result(), stock, need, mixedLast, mode);
            // All explicit fragmented cases here have sufficient splittable
            // flow. Random oracle cases may instead have a total shortage.
            if (mode == 2) check(work.deficit() == null, "Packing failure became a Hall certificate");
        }
        check(budget.reservedBytes() == 0, "Packing workspace leaked"); cases++;
        return feasible;
    }

    private static boolean eligible(int mode, int slots, int slot, int key) {
        return mode != 2 || slot != slots - 1 || key != 0;
    }

    private static void verify(List<StateEntityAllocation.Pick<Integer>> picks, long[] stock, long[] need,
            boolean mixedLast, int mode) {
        BigInteger[] spent = new BigInteger[stock.length], filled = new BigInteger[need.length];
        Arrays.fill(spent, BigInteger.ZERO); Arrays.fill(filled, BigInteger.ZERO);
        int[] keys = new int[need.length]; Arrays.fill(keys, -1);
        for (var pick : picks) {
            int d = pick.slot(), s = pick.state();
            check(pick.amount() > 0 && eligible(mode, need.length, d, s), "Invalid physical pick");
            check(mixedLast && d == need.length - 1 || keys[d] < 0 || keys[d] == s, "Split a whole-key slot");
            keys[d] = s; spent[s] = spent[s].add(BigInteger.valueOf(pick.amount()));
            filled[d] = filled[d].add(BigInteger.valueOf(pick.amount()));
        }
        for (int s = 0; s < stock.length; s++) check(spent[s].compareTo(BigInteger.valueOf(stock[s])) <= 0, "Reused an entity");
        for (int d = 0; d < need.length; d++) check(filled[d].equals(BigInteger.valueOf(need[d])), "Lost slot demand");
    }

    private static void boundaries() {
        long[] stock = {3, 3, 3}, need = {2, 2, 2, 2};
        var ds = demands(need, false); var ss = supplies(stock);
        for (int mode = 0; mode < 2; mode++) {
            // Enough memory for the original exact allocator, but not for
            // the optional bound. Refusal must continue exact search.
            long bytes = mode == 0 ? 512L + 160L * need.length + 224L * stock.length :
                    768L + 192L * need.length + 224L * stock.length + 96L * need.length * stock.length;
            check(!run(stock, need, false, mode, false, false, budget(20_000, bytes)), "Memory refusal changed feasibility");
            for (int cap = 1; cap <= 320; cap++) {
                var budget = budget(cap, 1 << 20);
                try { check(!run(stock, need, false, mode, false, false, budget), "Budget exit emitted a witness"); }
                catch (PlanningBudget.Exhausted expected) { checks++; }
                check(budget.reservedBytes() == 0 && budget.searchWork() <= cap + 1L, "Work boundary leaked ownership");
            }
            for (int stop = 0; stop < 96; stop++) {
                var cancel = new AtomicBoolean();
                var budget = new PlanningBudget(0, 100_000, 1 << 20, cancel::get, System::nanoTime);
                try {
                    if (mode == 0) try (var work = StateEntityAllocation.create(ds, ss, budget)) {
                        for (int i = 0; i < stop && !work.step(); i++) {}
                        cancel.set(true); work.step();
                    } else try (var work = StateEntityMatching.create(ds, ss, (d, s) -> true, budget)) {
                        for (int i = 0; i < stop && !work.step(); i++) {}
                        cancel.set(true); work.step();
                    }
                    throw new AssertionError("Ignored cancellation");
                } catch (CancellationException expected) { checks++; }
                check(budget.reservedBytes() == 0, "Cancelled bound leaked memory");
            }
        }
        // BigInteger stock above long range must be capped by required slot
        // count, not narrowed before dividing or treated as a negative count.
        var wide = List.of(new StateEntityAllocation.Supply<>(0, BigInteger.ONE.shiftLeft(100), 1),
                new StateEntityAllocation.Supply<>(1, BigInteger.valueOf(8), 1),
                new StateEntityAllocation.Supply<>(2, BigInteger.valueOf(8), 1));
        var budget = budget(20_000, 1 << 20);
        try (var work = StateEntityMatching.create(demands(new long[]{6, 6, 4, 1}, false), wide,
                (d, s) -> d.slot() == 3 ? s == 0 : s > 0, budget)) {
            while (!work.step()) {}
            check(!work.feasible() && work.deficit() == null, "Overflowed wide stock or invented a Hall deficit");
        }
        check(budget.reservedBytes() == 0, "Wide stock ownership leaked");
    }

    private static ArrayList<StateEntityAllocation.Demand> demands(long[] need, boolean mixedLast) {
        var ds = new ArrayList<StateEntityAllocation.Demand>();
        for (int d = 0; d < need.length; d++) ds.add(new StateEntityAllocation.Demand(d, need[d], mixedLast && d == need.length - 1));
        return ds;
    }
    private static ArrayList<StateEntityAllocation.Supply<Integer>> supplies(long[] stock) {
        var ss = new ArrayList<StateEntityAllocation.Supply<Integer>>();
        for (int s = 0; s < stock.length; s++) ss.add(new StateEntityAllocation.Supply<>(s, BigInteger.valueOf(stock[s]), 100L - s));
        return ss;
    }
    private static PlanningBudget budget(long work, long bytes) { return new PlanningBudget(0, work, bytes, () -> false, System::nanoTime); }
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
