// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Full original tuples, independent of bounded-sum DP and its admission. */
public final class CountEqualityWideTest {
    private static long tuples;
    private static int boxes, removed;

    public static void main(String[] args) {
        expansionAndCache();
        rangeRefusal();
        var random = new Random(2026102053L);
        for (int seed = 0; seed < 192; seed++) {
            int n = 5 + seed % 8;
            var lo = new BigInteger[n]; var hi = new BigInteger[n]; var masks = new int[n];
            var terms = new LinkedHashMap<Integer, BigInteger>();
            BigInteger rhs = BigInteger.ZERO;
            BigInteger factor = seed % 7 == 0 ? BigInteger.ONE.shiftLeft(90).add(BigInteger.ONE) : BigInteger.ONE;
            for (int i = 0; i < n; i++) {
                lo[i] = BigInteger.valueOf(random.nextInt(7) - 3);
                if (seed % 11 == 0) lo[i] = lo[i].add(BigInteger.ONE.shiftLeft(900).multiply(BigInteger.valueOf(i % 2 == 0 ? 1 : -1)));
                int width = i == 0 ? 15 : 1;
                hi[i] = lo[i].add(BigInteger.valueOf(width));
                masks[i] = (1 << (width + 1)) - 1;
                if (seed % 3 != 0) masks[i] &= random.nextInt(1 << (width + 1));
                BigInteger a = BigInteger.valueOf((2 + random.nextInt(11)) * (random.nextBoolean() ? 1 : -1)).multiply(factor);
                terms.put(i, a);
                rhs = rhs.add(a.multiply(lo[i].add(BigInteger.valueOf(random.nextInt(width + 1)))));
            }
            if (seed % 13 == 0) rhs = rhs.add(BigInteger.ONE);
            var rows = CountEqualitySupportTest.pair(terms, rhs);
            var budget = budget();
            try (var support = CountEqualitySupport.create(rows, lo, hi, budget)) {
                support.expandDomains(r -> {});
                audit(support, rows.get(0), lo, lo, hi, masks, 0);
                audit(support, rows.get(0), lo, lo, hi, masks, 1);
                var child = lo.clone(); child[0] = hi[0];
                audit(support, rows.get(0), lo, child, hi, masks, 0);
                var changed = masks.clone(); changed[0] ^= 1;
                audit(support, rows.get(0), lo, lo, hi, changed, 0);
                audit(support, rows.get(0), lo, lo, hi, masks, 0);
            }
            check(budget.reservedBytes() == 0, "Expanded support leaked");
        }
        check(removed > 100, "Missing broader supported-value coverage");
        System.out.println("Wide equality: boxes=" + boxes + "; tuples=" + tuples + "; removed=" + removed +
                "; 5..12 coordinates, exact sums, masks, restored scopes and range refusal passed");
    }

    private static void expansionAndCache() {
        int n = 12;
        var lo = new BigInteger[n]; var hi = new BigInteger[n]; var masks = new int[n];
        Arrays.fill(lo, BigInteger.ZERO); Arrays.fill(hi, BigInteger.ONE); Arrays.fill(masks, 3);
        hi[0] = BigInteger.valueOf(15); masks[0] = 65535;
        var terms = new LinkedHashMap<Integer, BigInteger>();
        for (int i = 0; i < n; i++) terms.put(i, i == 0 ? BigInteger.ONE : BigInteger.TWO);
        var rows = CountEqualitySupportTest.pair(terms, BigInteger.valueOf(15));
        var budget = budget();
        try (var support = CountEqualitySupport.create(rows, lo, hi, budget)) {
            check(support.propagate(0, lo, hi, masks) == null, "Initial search was replaced");
            var wakeups = new ArrayList<Integer>();
            support.expandDomains(wakeups::add);
            check(wakeups.equals(List.of(0, 1)), "Unchanged paired rows were not woken");
            var result = support.propagate(0, lo, hi, masks);
            check(result != null && !result.empty(), "Cached initial refusal hid expanded supports");
            for (int k = 0; k < n; k++) if (result.variables()[k] == 0)
                check(result.supports()[k] == 0xaaaa, "Even x has no support in an odd total");
            check(support.propagate(1, lo, hi, masks) == result, "Lost expanded exact-cache reuse");
            support.expandDomains(r -> { throw new AssertionError("Repeated expansion renewed work"); });
            check(support.propagate(0, lo, hi) == null, "Hull-only admission changed");
            audit(support, rows.get(0), lo, lo, hi, masks, 0);
        }
        check(budget.reservedBytes() == 0, "Expansion/cache leaked");
    }

    private static void rangeRefusal() {
        BigInteger[] lo = new BigInteger[5], hi = new BigInteger[5];
        int[] masks = new int[5];
        Arrays.fill(lo, BigInteger.ZERO); Arrays.fill(hi, BigInteger.ONE); Arrays.fill(masks, 3);
        for (int weight : new int[] {1023, 1024}) {
            var rows = CountEqualitySupportTest.pair(Map.of(0, BigInteger.valueOf(weight), 1, BigInteger.valueOf(1022),
                    2, BigInteger.ONE, 3, BigInteger.ONE, 4, BigInteger.ONE), BigInteger.ONE);
            var budget = budget();
            try (var support = CountEqualitySupport.create(rows, lo, hi, budget)) {
                support.expandDomains(r -> {});
                var result = support.propagate(0, lo, hi, masks);
                if (weight == 1023) audit(support, rows.get(0), lo, lo, hi, masks, 0);
                else check(result == null, "Oversized range was expanded");
            }
            check(budget.reservedBytes() == 0, "Range refusal leaked");
        }
    }

    private static void audit(CountEqualitySupport support, ExactLinearProgram.Constraint row, BigInteger[] anchors,
                              BigInteger[] lo, BigInteger[] hi, int[] masks, int rowId) {
        int[] expected = new int[lo.length];
        enumerate(row, anchors, lo, hi, masks, lo.clone(), 0, expected);
        var result = support.propagate(rowId, lo, hi, masks);
        boxes++;
        if (expected[0] == 0) {
            check(result != null && result.empty(), "Missed empty original domain");
            return;
        }
        check(result == null || !result.empty(), "Removed original witness");
        for (int k = 0; k < lo.length; k++) {
            int i = result == null ? k : result.variables()[k];
            int first = lo[i].subtract(anchors[i]).intValueExact(), last = hi[i].subtract(anchors[i]).intValueExact();
            int span = ((1 << (last - first + 1)) - 1) << first;
            if (result == null) check(expected[i] == (masks[i] & span), "Missed deletion");
            else {
                check((result.supports()[k] & span) == expected[i], "Wrong discrete support");
                check((result.supports()[k] & ~span) == (masks[i] & ~span), "Changed unexplained outside domain");
                check(result.lower()[k].equals(anchors[i].add(BigInteger.valueOf(Integer.numberOfTrailingZeros(expected[i])))), "Wrong minimum");
                check(result.upper()[k].equals(anchors[i].add(BigInteger.valueOf(31 - Integer.numberOfLeadingZeros(expected[i])))), "Wrong maximum");
                removed += Integer.bitCount((masks[i] & span) & ~expected[i]);
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
    private static PlanningBudget budget() { return new PlanningBudget(0, 4_000_000, 16L << 20, () -> false, System::nanoTime); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
