// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/** Exhaust original integer tuples, independently of prefix/suffix bitsets. */
public final class CountEqualitySupportTest {
    private static long tuples;
    private static int tightened, empty;

    public static void main(String[] args) {
        var random = new Random(2026102007);
        for (int seed = 0; seed < 384; seed++) {
            int n = 2 + seed % 3;
            BigInteger factor = seed % 7 == 0 ? BigInteger.ONE.shiftLeft(90).add(BigInteger.valueOf(3)) : BigInteger.ONE;
            var low = new BigInteger[n]; var high = new BigInteger[n];
            var terms = new LinkedHashMap<Integer, BigInteger>();
            BigInteger min = BigInteger.ZERO, max = BigInteger.ZERO;
            for (int i = 0; i < n; i++) {
                low[i] = BigInteger.valueOf(random.nextInt(7) - 3);
                if ((seed & 1) != 0) low[i] = low[i].add(BigInteger.ONE.shiftLeft(seed % 17 == 0 ? 900 : 85)
                        .multiply(BigInteger.valueOf(i % 2 == 0 ? 1 : -1)));
                high[i] = low[i].add(BigInteger.valueOf(1 + random.nextInt(4)));
                BigInteger a = BigInteger.valueOf((2 + random.nextInt(29)) * (random.nextBoolean() ? 1 : -1)).multiply(factor);
                terms.put(i, a);
                min = min.add(a.multiply(a.signum() > 0 ? low[i] : high[i]));
                max = max.add(a.multiply(a.signum() > 0 ? high[i] : low[i]));
            }
            BigInteger rhs = min.add(factor.multiply(BigInteger.valueOf(random.nextInt(max.subtract(min).divide(factor).intValue() + 9) - 4)));
            if (seed % 11 == 0) rhs = rhs.add(BigInteger.ONE);
            var rows = pair(terms, rhs);
            var budget = budget(16L << 20);
            try (var support = CountEqualitySupport.create(rows, low, high, budget)) {
                check(support != null, "Missing exact pair");
                audit(support, rows.get(0), low, high, 0);
                // Opposite wakeups, a narrower descendant, and restored bounds
                // must all use the same exact scope, including cached failures.
                audit(support, rows.get(0), low, high, 1);
                var narrow = low.clone(); narrow[0] = high[0];
                audit(support, rows.get(0), narrow, high, 0);
                audit(support, rows.get(0), low, high, 0);
            }
            check(budget.reservedBytes() == 0, "Support workspace leaked");
        }
        boundaries();
        check(tightened > 100 && empty > 100, "Weak support coverage");
        System.out.println("Equality support: 1536 original-box audits; tuples=" + tuples + "; tightened=" + tightened + "; empty=" + empty);
    }

    private static void audit(CountEqualitySupport support, ExactLinearProgram.Constraint row,
                              BigInteger[] low, BigInteger[] high, int rowId) {
        var minimum = new BigInteger[low.length]; var maximum = new BigInteger[low.length];
        enumerate(row, low, high, low.clone(), 0, minimum, maximum);
        var result = support.propagate(rowId, low, high);
        int free = 0;
        for (int i = 0; i < low.length; i++) if (!low[i].equals(high[i])) free++;
        if (minimum[0] == null) {
            // A one-free-coordinate box is left to interval propagation.
            if (free >= 2) check(result != null && result.empty(), "Missed exhaustive empty box");
            if (result != null) { check(result.empty(), "Invented support"); empty++; }
            return;
        }
        check(result == null || !result.empty(), "Discarded feasible box");
        if (result == null) {
            if (free >= 2) for (int i = 0; i < low.length; i++)
                check(minimum[i].equals(low[i]) && maximum[i].equals(high[i]), "Missed support boundary");
            return;
        }
        for (int k = 0; k < result.variables().length; k++) {
            int i = result.variables()[k];
            check(result.lower()[k].equals(minimum[i]) && result.upper()[k].equals(maximum[i]), "Wrong support hull");
        }
        tightened++;
    }

    private static void enumerate(ExactLinearProgram.Constraint row, BigInteger[] low, BigInteger[] high,
                                  BigInteger[] point, int at, BigInteger[] minimum, BigInteger[] maximum) {
        if (at < point.length) {
            for (BigInteger x = low[at]; x.compareTo(high[at]) <= 0; x = x.add(BigInteger.ONE)) {
                point[at] = x; enumerate(row, low, high, point, at + 1, minimum, maximum);
            }
            return;
        }
        tuples++;
        BigInteger sum = BigInteger.ZERO;
        for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(point[term.getKey()]));
        if (!sum.equals(row.upper())) return;
        for (int i = 0; i < point.length; i++) {
            minimum[i] = minimum[i] == null ? point[i] : minimum[i].min(point[i]);
            maximum[i] = maximum[i] == null ? point[i] : maximum[i].max(point[i]);
        }
    }

    private static void boundaries() {
        BigInteger[] low = {BigInteger.ZERO, BigInteger.ZERO}, high = {BigInteger.valueOf(7), BigInteger.valueOf(7)};
        var rows = pair(Map.of(0, BigInteger.valueOf(7), 1, BigInteger.valueOf(11)), BigInteger.valueOf(65));
        var budget = budget(1);
        check(CountEqualitySupport.create(rows, low, high, budget) == null && budget.reservedBytes() == 0, "Memory refusal leaked");
        budget = budget(16L << 20);
        var interval = List.of(rows.get(0), new ExactLinearProgram.Constraint(rows.get(1).terms(), rows.get(1).upper().add(BigInteger.ONE)));
        check(CountEqualitySupport.create(interval, low, high, budget) == null, "Interval mistaken for equality");
        var wide = high.clone(); wide[0] = BigInteger.valueOf(8192);
        check(CountEqualitySupport.create(rows, low, wide, budget) == null, "Expanded long domain");
        var unbounded = high.clone(); unbounded[1] = null;
        check(CountEqualitySupport.create(rows, low, unbounded, budget) == null, "Expanded unbounded domain");
        try (var support = CountEqualitySupport.create(rows, low, high, budget)) {
            var before = support.propagate(0, low, high);
            check(before != null && !before.empty(), "Missing nonconvex boundary");
            check(support.propagate(1, low, high) == before, "Lost paired exact-box reuse");
            var child = low.clone(); child[0] = BigInteger.valueOf(4);
            check(support.propagate(0, child, high).empty(), "Missed empty descendant");
            check(!support.propagate(0, low, high).empty(), "Cached descendant poisoned parent");
            check(support.propagate(100, low, high) == null, "Appended row used stale pairing");
        }
        check(budget.reservedBytes() == 0, "Boundary workspace leaked");
        for (int stop : new int[] {1, 5, 20, 40}) {
            var checks = new AtomicInteger();
            budget = new PlanningBudget(0, 1_000_000, 16L << 20, () -> checks.incrementAndGet() >= stop, System::nanoTime);
            boolean cancelled = false;
            try (var support = CountEqualitySupport.create(rows, low, high, budget)) {
                if (support != null) support.propagate(0, low, high);
            } catch (CancellationException expected) { cancelled = true; }
            check(cancelled && budget.reservedBytes() == 0, "Cancellation did not release support");
        }
    }

    static List<ExactLinearProgram.Constraint> pair(Map<Integer, BigInteger> terms, BigInteger rhs) {
        var reverse = new LinkedHashMap<Integer, BigInteger>();
        terms.forEach((i, a) -> reverse.put(i, a.negate()));
        return List.of(new ExactLinearProgram.Constraint(terms, rhs), new ExactLinearProgram.Constraint(reverse, rhs.negate()));
    }
    private static PlanningBudget budget(long bytes) { return new PlanningBudget(0, 4_000_000, bytes, () -> false, System::nanoTime); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
