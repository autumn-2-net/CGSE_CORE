// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Literal integer tuples check word carries, scratch reuse and cached results. */
public final class CountEqualityWordTest {
    private static long tuples;
    private static int boxes, changes, empty;

    public static void main(String[] args) {
        var random = new Random(2026102073L);
        int[] weights = {1, 2, 31, 32, 63, 64, 65, 127, 128, 129, 255, 256, 511, 512};
        for (int seed = 0; seed < 256; seed++) {
            int n = 3 + seed % 4;
            var anchors = new BigInteger[n]; var high = new BigInteger[n]; var masks = new int[n];
            var terms = new LinkedHashMap<Integer, BigInteger>();
            BigInteger factor = seed % 5 == 0 ? BigInteger.ONE.shiftLeft(80).add(BigInteger.ONE) : BigInteger.TWO;
            BigInteger rhs = BigInteger.ZERO;
            int range = 0;
            for (int i = 0; i < n; i++) {
                anchors[i] = BigInteger.valueOf(random.nextInt(7) - 3);
                if (seed % 7 == 0) anchors[i] = anchors[i].add(BigInteger.ONE.shiftLeft(900));
                int width = 1 + random.nextInt(3);
                int weight = i == 0 ? 1 : weights[random.nextInt(weights.length)];
                if (range + weight * width > 2048 - 3 * (n - i)) weight = 1;
                range += weight * width;
                high[i] = anchors[i].add(BigInteger.valueOf(width));
                masks[i] = (1 << (width + 1)) - 1;
                BigInteger a = factor.multiply(BigInteger.valueOf(random.nextBoolean() ? weight : -weight));
                terms.put(i, a);
                rhs = rhs.add(a.multiply(anchors[i].add(BigInteger.valueOf(random.nextInt(width + 1)))));
            }
            var first = CountEqualitySupportTest.pair(terms, rhs);
            // A second row uses the same storage with different signs, arity
            // and range. It must not alter a result retained by the first row.
            var secondTerms = new LinkedHashMap<Integer, BigInteger>();
            secondTerms.put(0, factor); secondTerms.put(n - 1, factor.negate());
            BigInteger secondRhs = factor.multiply(anchors[0].subtract(anchors[n - 1]).add(BigInteger.ONE));
            var rows = new ArrayList<>(first);
            rows.addAll(CountEqualitySupportTest.pair(secondTerms, secondRhs));
            var budget = budget();
            try (var support = CountEqualitySupport.create(rows, anchors, high, budget)) {
                support.expandDomains(r -> {});
                var saved = audit(support, first.get(0), 0, anchors, anchors, high, masks);
                var savedLow = saved == null || saved.empty() ? null : saved.lower().clone();
                var savedHigh = saved == null || saved.empty() ? null : saved.upper().clone();
                var savedMasks = saved == null || saved.empty() ? null : saved.supports().clone();
                audit(support, rows.get(2), 2, anchors, anchors, high, masks);
                if (savedLow != null) {
                    check(Arrays.equals(savedLow, saved.lower()) && Arrays.equals(savedHigh, saved.upper()) &&
                            Arrays.equals(savedMasks, saved.supports()), "Scratch escaped into an earlier result");
                }
                check(support.propagate(1, anchors, high, masks) == saved, "Opposite cache lost its owned result");
                var child = anchors.clone(); child[0] = high[0];
                audit(support, first.get(0), 0, anchors, child, high, masks);
                var holes = masks.clone();
                for (int i = 0; i < n; i++) holes[i] &= random.nextInt(masks[i] + 1);
                audit(support, first.get(0), 1, anchors, anchors, high, holes);
                audit(support, first.get(0), 0, anchors, anchors, high, masks);
                // Switching back to a hull must ignore stale mask scratch.
                audit(support, rows.get(2), 3, anchors, anchors, high, null);
            }
            check(budget.reservedBytes() == 0, "Word workspace leaked");
        }
        boundaries();
        check(changes > 300 && empty > 100, "Insufficient support and empty-box coverage");
        System.out.println("Equality words: boxes=" + boxes + "; tuples=" + tuples +
                "; changes=" + changes + "; empty=" + empty + "; unsigned carries, resets and cache ownership passed");
    }

    private static void boundaries() {
        for (int weight : new int[] {63, 64, 65, 127, 128, 129, 511, 512, 1023}) {
            BigInteger[] lo = {BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO};
            BigInteger[] hi = {BigInteger.ONE, BigInteger.ONE, BigInteger.ONE};
            int[] masks = {3, 3, 3};
            // The largest case has total range 2048, with supported sums on
            // bit 1024. Other cases straddle complete and partial word shifts.
            var terms = Map.of(0, BigInteger.ONE, 1, BigInteger.valueOf(weight), 2, BigInteger.valueOf(weight + 1));
            int total = 2 * weight + 2;
            for (int rhs : new int[] {0, 1, weight, weight + 1, weight + 2, total - 1, total}) {
                var rows = CountEqualitySupportTest.pair(terms, BigInteger.valueOf(rhs));
                var budget = budget();
                try (var support = CountEqualitySupport.create(rows, lo, hi, budget)) {
                    audit(support, rows.get(0), 0, lo, lo, hi, masks);
                    audit(support, rows.get(0), 1, lo, lo, hi, null);
                    audit(support, rows.get(0), 0, lo, lo, hi, new int[] {1, 3, 3});
                    audit(support, rows.get(0), 0, lo, lo, hi, masks);
                }
                check(budget.reservedBytes() == 0, "Boundary workspace leaked");
            }
        }
    }

    private static CountEqualitySupport.Result audit(CountEqualitySupport support, ExactLinearProgram.Constraint row,
            int rowId, BigInteger[] anchors, BigInteger[] low, BigInteger[] high, int[] masks) {
        int[] ids = row.terms().keySet().stream().mapToInt(Integer::intValue).toArray();
        int[] expected = new int[low.length];
        enumerate(row, ids, 0, low.clone(), anchors, low, high, masks, expected);
        var result = support.propagate(rowId, low, high, masks);
        boxes++;
        if (expected[ids[0]] == 0) {
            check(result != null && result.empty(), "Original empty tuple set not detected");
            empty++;
            return result;
        }
        check(result == null || !result.empty(), "Original feasible tuple removed");
        for (int i : ids) {
            int first = low[i].subtract(anchors[i]).intValueExact(), last = high[i].subtract(anchors[i]).intValueExact();
            int span = ((1 << (last - first + 1)) - 1) << first;
            int k = result == null ? -1 : index(result.variables(), i);
            BigInteger wantLow = anchors[i].add(BigInteger.valueOf(Integer.numberOfTrailingZeros(expected[i])));
            BigInteger wantHigh = anchors[i].add(BigInteger.valueOf(31 - Integer.numberOfLeadingZeros(expected[i])));
            check(wantLow.equals(result == null ? low[i] : result.lower()[k]), "Wrong original minimum");
            check(wantHigh.equals(result == null ? high[i] : result.upper()[k]), "Wrong original maximum");
            if (masks != null) {
                int actual = result == null ? masks[i] : result.supports()[k];
                check((actual & span) == expected[i], "Wrong original masked support");
                check((actual & ~span) == (masks[i] & ~span), "Unexplained outside-box deletion");
            }
        }
        if (result != null) changes++;
        return result;
    }

    private static int index(int[] ids, int wanted) {
        for (int k = 0; k < ids.length; k++) if (ids[k] == wanted) return k;
        throw new AssertionError("Missing source coordinate");
    }

    private static void enumerate(ExactLinearProgram.Constraint row, int[] ids, int at, BigInteger[] point,
            BigInteger[] anchors, BigInteger[] low, BigInteger[] high, int[] masks, int[] expected) {
        if (at < ids.length) {
            int i = ids[at];
            for (BigInteger value = low[i]; value.compareTo(high[i]) <= 0; value = value.add(BigInteger.ONE)) {
                if (masks != null && (masks[i] & (1 << value.subtract(anchors[i]).intValueExact())) == 0) continue;
                point[i] = value;
                enumerate(row, ids, at + 1, point, anchors, low, high, masks, expected);
            }
            return;
        }
        tuples++;
        BigInteger sum = BigInteger.ZERO;
        for (int i : ids) sum = sum.add(row.terms().get(i).multiply(point[i]));
        if (sum.equals(row.upper()))
            for (int i : ids) expected[i] |= 1 << point[i].subtract(anchors[i]).intValueExact();
    }

    private static PlanningBudget budget() { return new PlanningBudget(0, 8_000_000, 16L << 20, () -> false, System::nanoTime); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
