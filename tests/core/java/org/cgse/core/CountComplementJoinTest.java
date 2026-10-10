// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Original-coordinate oracles for reusing a quarter-list table through complementation. */
public final class CountComplementJoinTest {
    private record Result(long candidate, long work, String trace) {}

    public static void main(String[] args) {
        uniqueSubsets();
        truncatedTable();
        arithmeticBoundary();
        compactWorkspace();
        retainedCoordinates();
        System.out.println("Complement join: independent subsets, partial tables, signed long boundary, " +
                "large offsets and retained reconstruction passed");
    }

    private static Result solve(long[] coefficients, long target) {
        long span = Arrays.stream(coefficients).map(Math::abs).sum();
        var shape = CountResidueJoin.Shape.create(coefficients.length, span);
        check(shape != null, "Invalid oracle input");
        var budget = new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime);
        Result result;
        try (var join = new CountResidueJoin(coefficients, target, budget, shape)) {
            while (!join.step()) {}
            long mask = join.candidate();
            if (mask >= 0) {
                check(mask < 1L << coefficients.length, "Escaped mask width");
                check(sum(coefficients, mask).equals(BigInteger.valueOf(target)), "Incorrect reflected witness");
            }
            result = new Result(mask, budget.searchWork(), budget.diagnostics());
        }
        check(budget.reservedBytes() == 0, "Complement workspace leaked");
        return result;
    }

    private static void uniqueSubsets() {
        long[] powers = new long[12];
        for (int i = 0; i < powers.length; i++) powers[i] = 1L << i;
        // Powers of two give an independent, unique witness for every target.
        // Odd targets require the other left residue; zero remains a valid code.
        int reflected = 0;
        for (int target = 0; target < 4096; target += 17) {
            Result result = solve(powers, target);
            check(result.candidate == target, "Lost a unique finite subset");
            if (!result.trace.contains("reused=0")) reflected++;
        }
        check(reflected > 100, "Complement path not exercised");
        check(solve(powers, 4096).candidate < 0, "Accepted an out-of-range target");
        // Reversed and signed coefficients change both the residue pairing and
        // the reconstruction mask. Enumerate the original coordinates afresh.
        for (int mode = 0; mode < 4; mode++) {
            long[] values = powers.clone();
            for (int i = 0; i < values.length; i++) {
                if ((mode & 1) != 0) values[i] = powers[i < 6 ? 5 - i : 17 - i];
                if ((mode & 2) != 0 && i % 3 != 0) values[i] = -values[i];
            }
            for (int mask = 1; mask < 4096; mask += 127) {
                long target = sum(values, mask).longValueExact();
                check(solve(values, target).candidate >= 0, "Lost a signed/permuted witness");
            }
        }
    }

    private static void truncatedTable() {
        long[] values = {1, 4, 8, 16, 32, 64, 128, 256, 128, 128, 128, 128, 128, 128, 128, 128};
        Result result = solve(values, 1);
        check(result.candidate == 1, "Partial complement incorrectly covered an unvisited residue");
        check(result.trace.contains("truncated=2") && result.trace.contains("reused=0"),
                "Fixture did not pass through both partial tables: " + result.trace);
        check(sum(values, result.candidate).equals(BigInteger.ONE), "Invalid truncation oracle witness");
    }

    private static void arithmeticBoundary() {
        long[] values = new long[12];
        long scale = (((Long.MAX_VALUE / 4) / 4095) - 1) | 1;
        for (int i = 0; i < values.length; i++) values[i] = (1L << i) * scale;
        for (int mode = 0; mode < 3; mode++) {
            if (mode > 0) for (int i = 0; i < values.length; i++) if (i % 2 == mode - 1) values[i] = -values[i];
            for (int mask : new int[] {0, 1, 63, 2047, 4094, 4095}) {
                long target = sum(values, mask).longValueExact();
                check(solve(values, target).candidate >= 0, "Long boundary lost its unique subset");
            }
        }
    }

    private static void compactWorkspace() {
        // A full-size hash table must fit together with all four sum lists.
        // The former parallel key/code/epoch arrays cannot enter this budget.
        long[] values = new long[48];
        for (int i = 0; i < values.length; i++) values[i] = 1L << i;
        long span = (1L << values.length) - 1;
        var shape = CountResidueJoin.Shape.create(values.length, span);
        check(shape != null && shape.capacity() == 1 << 21, "Missed the largest table");
        long target = (1L << shape.split()) - 1;
        var budget = new PlanningBudget(0, 10_000_000, 20L << 20, () -> false, System::nanoTime);
        try (var join = new CountResidueJoin(values, target, budget, shape)) {
            while (!join.step()) {}
            check(join.candidate() == target, "Compact table failed its independent unique witness");
            check(budget.diagnostics().contains("reused=1"), "Large compact test missed reflection");
            check(budget.reservedBytes() <= 20L << 20, "Compact workspace exceeded its reservation");
        }
        check(budget.reservedBytes() == 0, "Large compact table leaked");
    }

    private static void retainedCoordinates() {
        // Two equal weights above the requested sum are forced to zero.
        // They preserve the unique oracle while keeping this specifically a
        // complement-table test after adding the distinct-weight lattice scout.
        int n = 42;
        BigInteger[] low = new BigInteger[n], high = new BigInteger[n];
        var terms = new LinkedHashMap<Integer, BigInteger>();
        var reverse = new LinkedHashMap<Integer, BigInteger>();
        BigInteger target = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            low[i] = BigInteger.ONE.shiftLeft(100 + i % 3).add(BigInteger.valueOf(i - 20));
            if (i % 3 == 0) low[i] = low[i].negate();
            high[i] = low[i].add(BigInteger.ONE);
            BigInteger a = BigInteger.ONE.shiftLeft(Math.min(i, 40));
            terms.put(i, a);
            reverse.put(i, a.negate());
            target = target.add(a.multiply(i < 40 && (i == 0 || i % 5 == 0) ? high[i] : low[i]));
        }
        var rows = List.of(new ExactLinearProgram.Constraint(terms, target),
                new ExactLinearProgram.Constraint(reverse, target.negate()));
        BigInteger[] previous = null;
        long previousWork = -1;
        for (long quantum : new long[] {20_000_000, 137}) {
            var budget = new PlanningBudget(0, 20_000_000, 128L << 20, () -> false, System::nanoTime);
            try (var search = new CountMeetInMiddle(rows, low, high, budget, Math.max(1024, quantum)).retained()) {
                for (;;) {
                    while (!search.step()) {}
                    if (!search.paused()) break;
                    long before = budget.searchWork(), bytes = budget.reservedBytes();
                    check(search.step() && before == budget.searchWork() && bytes == budget.reservedBytes(),
                            "Paused complement moved before its next grant");
                    search.resume(quantum);
                }
                BigInteger[] answer = search.counts();
                check(answer != null && !search.infeasible(), "Retained complement failed");
                check(budget.diagnostics().contains("reused=1"), "Retained test missed reflection");
                BigInteger sum = BigInteger.ZERO;
                for (int i = 0; i < n; i++) {
                    check(answer[i].equals(i < 40 && (i == 0 || i % 5 == 0) ? high[i] : low[i]), "Wrong restored coordinate");
                    sum = sum.add(terms.get(i).multiply(answer[i]));
                }
                check(sum.equals(target), "Restored counts fail the original equation");
                if (previous != null) check(Arrays.equals(previous, answer) && previousWork == budget.searchWork(),
                        "Sliced complement discarded or repeated work");
                previous = answer;
                previousWork = budget.searchWork();
            }
            check(budget.reservedBytes() == 0, "Retained complement leaked memory");
        }
    }

    private static BigInteger sum(long[] values, long mask) {
        BigInteger result = BigInteger.ZERO;
        for (int i = 0; i < values.length; i++) if ((mask & (1L << i)) != 0)
            result = result.add(BigInteger.valueOf(values[i]));
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
