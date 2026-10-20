// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Literal tuple enumeration, independent of the reachable-sum implementation. */
public final class CountEqualityDomainTest {
    private static int boxes, holes;
    private static long tuples;

    public static void main(String[] args) {
        interiorAndCache();
        var random = new Random(2026102041L);
        for (int seed = 0; seed < 512; seed++) {
            int n = 2 + seed % 3;
            var lo = new BigInteger[n]; var hi = new BigInteger[n]; var masks = new int[n];
            var terms = new LinkedHashMap<Integer, BigInteger>();
            BigInteger rhs = BigInteger.ZERO;
            BigInteger factor = seed % 7 == 0 ? BigInteger.ONE.shiftLeft(90).add(BigInteger.ONE) : BigInteger.ONE;
            for (int i = 0; i < n; i++) {
                lo[i] = BigInteger.valueOf(random.nextInt(7) - 3);
                if (seed % 5 == 0) lo[i] = lo[i].add(BigInteger.ONE.shiftLeft(900).multiply(BigInteger.valueOf(i % 2 == 0 ? 1 : -1)));
                int width = seed % 17 == 0 ? 15 : 1 + random.nextInt(4);
                hi[i] = lo[i].add(BigInteger.valueOf(width));
                masks[i] = random.nextInt(1 << (width + 1));
                BigInteger a = BigInteger.valueOf((2 + random.nextInt(11)) * (random.nextBoolean() ? 1 : -1)).multiply(factor);
                terms.put(i, a);
                rhs = rhs.add(a.multiply(lo[i].add(BigInteger.valueOf(random.nextInt(width + 1)))));
            }
            if (seed % 11 == 0) rhs = rhs.add(BigInteger.ONE);
            var rows = CountEqualitySupportTest.pair(terms, rhs);
            var budget = new PlanningBudget(0, 4_000_000, 16L << 20, () -> false, System::nanoTime);
            try (var support = CountEqualitySupport.create(rows, lo, hi, budget)) {
                audit(support, rows.get(0), lo, lo, hi, masks, 0);
                audit(support, rows.get(0), lo, lo, hi, masks, 1);
                var child = lo.clone(); child[0] = hi[0];
                audit(support, rows.get(0), lo, child, hi, masks, 0);
                var other = masks.clone(); other[0] ^= 1;
                audit(support, rows.get(0), lo, lo, hi, other, 0);
                audit(support, rows.get(0), lo, lo, hi, masks, 0);
            }
            check(budget.reservedBytes() == 0, "Masked support workspace leaked");
        }
        check(holes > 100, "Missing discrete support coverage");
        System.out.println("Equality domains: boxes=" + boxes + "; tuples=" + tuples + "; holes=" + holes +
                "; signed sums, large offsets/factors, restored bounds and same-box mask changes passed");
    }

    private static void interiorAndCache() {
        BigInteger[] lo = {BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO};
        BigInteger[] hi = {BigInteger.valueOf(3), BigInteger.ONE, BigInteger.ONE};
        var rows = CountEqualitySupportTest.pair(Map.of(0, BigInteger.ONE, 1, BigInteger.TWO, 2, BigInteger.valueOf(3)), BigInteger.valueOf(3));
        var budget = new PlanningBudget(0, 4_000_000, 16L << 20, () -> false, System::nanoTime);
        try (var support = CountEqualitySupport.create(rows, lo, hi, budget)) {
            check(support.propagate(0, lo, hi) == null, "Example should have unchanged hulls");
            int[] masks = {15, 3, 3};
            var result = support.propagate(0, lo, hi, masks);
            check(result != null && !result.empty(), "Lost interior support");
            for (int k = 0; k < result.variables().length; k++)
                if (result.variables()[k] == 0) check(result.supports()[k] == 11, "x=2 must be removed, x=0,1,3 retained");
            check(support.propagate(1, lo, hi, masks) == result, "Opposite rows lost exact domain reuse");
            masks[0] = 4;
            check(support.propagate(0, lo, hi, masks).empty(), "Same bounds with changed mask reused old support");
            masks[0] = 15;
            check(!support.propagate(0, lo, hi, masks).empty(), "Restored domain poisoned by empty child");
            check(support.propagate(0, lo, hi) == null, "Masked result leaked into hull-only caller");
        }
        check(budget.reservedBytes() == 0, "Example leaked");
    }

    private static void audit(CountEqualitySupport support, ExactLinearProgram.Constraint row, BigInteger[] anchors,
                              BigInteger[] lo, BigInteger[] hi, int[] masks, int rowId) {
        int[] expected = new int[lo.length];
        enumerate(row, anchors, lo, hi, masks, lo.clone(), 0, expected);
        var result = support.propagate(rowId, lo, hi, masks);
        boxes++;
        int free = 0;
        for (int i = 0; i < lo.length; i++) if (!lo[i].equals(hi[i])) free++;
        if (expected[0] == 0) {
            if (free >= 2) check(result != null && result.empty(), "Missed empty domain");
            if (result != null) check(result.empty(), "Invented support");
            return;
        }
        check(result == null || !result.empty(), "Discarded original feasible tuple");
        for (int k = 0; k < lo.length; k++) {
            int i = result == null ? k : result.variables()[k];
            int first = lo[i].subtract(anchors[i]).intValueExact(), last = hi[i].subtract(anchors[i]).intValueExact();
            int span = ((1 << (last - first + 1)) - 1) << first;
            if (result == null) {
                if (free >= 2) check(expected[i] == (masks[i] & span), "Lost supported-value deletion");
            } else {
                check((result.supports()[k] & span) == expected[i], "Wrong discrete support");
                check((result.supports()[k] & ~span) == (masks[i] & ~span), "Removed value outside explained box");
                check(result.lower()[k].equals(anchors[i].add(BigInteger.valueOf(Integer.numberOfTrailingZeros(expected[i])))), "Wrong minimum");
                check(result.upper()[k].equals(anchors[i].add(BigInteger.valueOf(31 - Integer.numberOfLeadingZeros(expected[i])))), "Wrong maximum");
                holes += Integer.bitCount((masks[i] & span) & ~expected[i]);
            }
        }
    }

    private static void enumerate(ExactLinearProgram.Constraint row, BigInteger[] anchors, BigInteger[] lo, BigInteger[] hi,
                                  int[] masks, BigInteger[] point, int at, int[] expected) {
        if (at < point.length) {
            for (BigInteger value = lo[at]; value.compareTo(hi[at]) <= 0; value = value.add(BigInteger.ONE)) {
                if ((masks[at] & (1 << value.subtract(anchors[at]).intValueExact())) == 0) continue;
                point[at] = value;
                enumerate(row, anchors, lo, hi, masks, point, at + 1, expected);
            }
            return;
        }
        tuples++;
        BigInteger sum = BigInteger.ZERO;
        for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(point[term.getKey()]));
        if (sum.equals(row.upper()))
            for (int i = 0; i < point.length; i++) expected[i] |= 1 << point[i].subtract(anchors[i]).intValueExact();
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
