// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Literal tuple oracle for dense intervals, hole sets and word-boundary shifts. */
public final class CountEqualityIntervalTest {
    private static long tuples;
    private static int checks;

    public static void main(String[] args) {
        var random = new Random(2026102387L);
        for (int size = 4; size <= 16; size++) for (int variant = 0; variant < 12; variant++) {
            int n = 2 + variant % 2;
            var lo = new BigInteger[n]; var hi = new BigInteger[n]; var terms = new LinkedHashMap<Integer, BigInteger>();
            var masks = new int[n];
            BigInteger rhs = BigInteger.ZERO, factor = variant % 4 == 0 ? BigInteger.ONE.shiftLeft(120) : BigInteger.ONE;
            for (int i = 0; i < n; i++) {
                lo[i] = BigInteger.valueOf(-3 + i);
                if (variant % 3 == 0) lo[i] = lo[i].add(BigInteger.ONE.shiftLeft(800).multiply(BigInteger.valueOf(i % 2 == 0 ? 1 : -1)));
                hi[i] = lo[i].add(BigInteger.valueOf(size - 1));
                int coefficient = switch (variant / 2) { case 0 -> 1 + 63 * i; case 1 -> 7 + 6 * i; case 2 -> 63 + i; default -> 2 + random.nextInt(19); };
                var a = BigInteger.valueOf((variant + i) % 3 == 0 ? -coefficient : coefficient).multiply(factor);
                terms.put(i, a);
                rhs = rhs.add(a.multiply(lo[i].add(BigInteger.valueOf(random.nextInt(size)))));
                masks[i] = (1 << size) - 1;
            }
            var rows = pair(terms, rhs);
            var budget = new PlanningBudget(0, 2_000_000, 16L << 20, () -> false, System::nanoTime);
            try (var support = CountEqualitySupport.create(rows, lo, hi, budget)) {
                check(support != null, "Refused valid intervals");
                for (int shape = 0; shape < 5; shape++) {
                    for (int i = 0; i < n; i++) masks[i] = switch (shape) {
                        case 0, 4 -> (1 << size) - 1;
                        case 1 -> ((1 << (size - 2)) - 1) << 1;
                        case 2 -> ((1 << size) - 1) & 0x5555;
                        default -> random.nextInt((1 << size) - 1) + 1;
                    };
                    var low = lo.clone(); var high = hi.clone();
                    if (shape == 3) { low[0] = low[0].add(BigInteger.ONE); high[0] = high[0].subtract(BigInteger.ONE); }
                    verify(support, rows.get(0), lo, low, high, masks, 0);
                    verify(support, rows.get(0), lo, low, high, masks, 1);
                    if (shape == 0) verify(support, rows.get(0), lo, low, high, null, 0);
                }
            }
            check(budget.reservedBytes() == 0, "Leaked interval workspace");
        }
        System.out.println("Interval supports: " + checks + " checks, " + tuples + " literal tuples; signed/large integers, holes, restored bounds and 64-bit crossings passed");
    }

    private static void verify(CountEqualitySupport support, ExactLinearProgram.Constraint row, BigInteger[] root,
                               BigInteger[] low, BigInteger[] high, int[] masks, int orientation) {
        int[] expected = new int[low.length];
        enumerate(row, root, low, high, masks, new BigInteger[low.length], 0, expected);
        var result = support.propagate(orientation, low, high, masks);
        if (result == null) {
            BigInteger gcd = BigInteger.ZERO, range = BigInteger.ZERO;
            for (var coefficient : row.terms().values()) gcd = gcd.gcd(coefficient);
            for (var term : row.terms().entrySet()) range = range.add(term.getValue().abs().divide(gcd)
                    .multiply(high[term.getKey()].subtract(low[term.getKey()])));
            // Refused ranges may keep all values. Within the supported range,
            // a null result must mean there is no hull or mask deletion.
            if (range.compareTo(BigInteger.valueOf(2048)) <= 0) {
                check(expected[0] != 0, "Missed empty bounded interval");
                for (int i = 0; i < low.length; i++) {
                    int first = low[i].subtract(root[i]).intValueExact(), last = high[i].subtract(root[i]).intValueExact();
                    int current = ((1 << (last - first + 1)) - 1) << first;
                    check(Integer.numberOfTrailingZeros(expected[i]) == first && 31 - Integer.numberOfLeadingZeros(expected[i]) == last,
                            "Missed supported hull");
                    if (masks != null) check(expected[i] == (current & masks[i]), "Missed interior deletion");
                }
            }
            return;
        }
        check(result.empty() == (expected[0] == 0), "Wrong empty interval");
        if (result.empty()) return;
        for (int k = 0; k < result.variables().length; k++) {
            int i = result.variables()[k];
            check(result.lower()[k].equals(root[i].add(BigInteger.valueOf(Integer.numberOfTrailingZeros(expected[i])))), "Wrong lower hull");
            check(result.upper()[k].equals(root[i].add(BigInteger.valueOf(31 - Integer.numberOfLeadingZeros(expected[i])))), "Wrong upper hull");
            if (masks != null) {
                int first = low[i].subtract(root[i]).intValueExact(), last = high[i].subtract(root[i]).intValueExact();
                int span = ((1 << (last - first + 1)) - 1) << first;
                check((result.supports()[k] & span) == expected[i], "Invented or removed supported value");
            }
        }
    }

    private static void enumerate(ExactLinearProgram.Constraint row, BigInteger[] root, BigInteger[] low, BigInteger[] high,
                                  int[] masks, BigInteger[] tuple, int i, int[] expected) {
        if (i == tuple.length) {
            tuples++; BigInteger total = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) total = total.add(term.getValue().multiply(tuple[term.getKey()]));
            if (total.equals(row.upper())) for (int k = 0; k < tuple.length; k++) expected[k] |= 1 << tuple[k].subtract(root[k]).intValueExact();
            return;
        }
        for (BigInteger value = low[i]; value.compareTo(high[i]) <= 0; value = value.add(BigInteger.ONE)) {
            if (masks != null && (masks[i] & (1 << value.subtract(root[i]).intValueExact())) == 0) continue;
            tuple[i] = value; enumerate(row, root, low, high, masks, tuple, i + 1, expected);
        }
    }

    private static List<ExactLinearProgram.Constraint> pair(Map<Integer, BigInteger> terms, BigInteger rhs) {
        var opposite = new LinkedHashMap<Integer, BigInteger>(); terms.forEach((i, a) -> opposite.put(i, a.negate()));
        return List.of(new ExactLinearProgram.Constraint(terms, rhs), new ExactLinearProgram.Constraint(opposite, rhs.negate()));
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
