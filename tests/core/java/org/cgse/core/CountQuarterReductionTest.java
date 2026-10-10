// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Independent subset oracles for exact quarter representatives and shared scratch epochs. */
public final class CountQuarterReductionTest {
    public static void main(String[] args) {
        finiteOracles();
        denseOddQuarters();
        retainedOriginalCoordinates();
        cancellationDuringReduction();
        System.out.println("Quarter reduction: finite signed/zero oracles, dense scratch refusal, " +
                "large-offset reconstruction, identical sliced work and cancellation passed");
    }

    private static void finiteOracles() {
        Random random = new Random(111023);
        int duplicates = 0;
        for (int sample = 0; sample < 160; sample++) {
            int n = 8 + sample % 7;
            long[] values = new long[n], types = new long[3 + sample % 3];
            for (int i = 0; i < types.length; i++) types[i] = random.nextInt(31) - 15;
            values[0] = 1;
            for (int i = 1; i < n; i++) values[i] = types[random.nextInt(types.length)] * (1L << (sample % 3 * 20));
            // This independent exhaustive oracle includes impossible and zero
            // targets. It does not use quarter masks, hash keys or residues.
            Set<BigInteger> sums = new HashSet<>();
            for (long mask = 0; mask < 1L << n; mask++) sums.add(sum(values, mask));
            long planted = sum(values, random.nextLong(1L << n)).longValueExact();
            for (long target : new long[] {planted, planted + 1, 0, Arrays.stream(values).map(Math::abs).sum() + 1}) {
                var budget = budget(2_000_000);
                var shape = shape(values);
                try (var search = new CountResidueJoin(values, target, budget, shape)) {
                    while (!search.step()) {}
                    long answer = search.candidate();
                    if (answer >= 0) check(answer < 1L << n && sum(values, answer).equals(BigInteger.valueOf(target)),
                            "Quarter representative failed the original equation");
                    if (budget.diagnostics().contains("truncated=0")) check((answer >= 0) == sums.contains(BigInteger.valueOf(target)),
                            "Exact quarter reduction removed the only sum");
                    if (!budget.diagnostics().contains("duplicates=0")) duplicates++;
                }
                check(budget.reservedBytes() == 0, "Finite quarter workspace leaked");
            }
        }
        check(duplicates > 400, "Repeated-quarter path not covered");
    }

    private static void denseOddQuarters() {
        for (int n : new int[] {45, 49}) {
            long[] values = new long[n];
            long target = 0;
            for (int i = 0; i < n; i++) {
                values[i] = i % 5 - 2;
                if (i % 3 == 0) target += values[i];
            }
            var shape = shape(values);
            check(shape.capacity() < 2L * (1L << (shape.split() - shape.first())), "Fixture missed scratch refusal");
            var budget = budget(1_000_000);
            try (var search = new CountResidueJoin(values, target, budget, shape)) {
                while (!search.step()) {}
                check(search.candidate() >= 0 && sum(values, search.candidate()).equals(BigInteger.valueOf(target)),
                        "Dense odd quarter did not retain all choices on scratch refusal");
            }
            check(budget.reservedBytes() == 0, "Dense refused scratch leaked");
        }
    }

    private static void retainedOriginalCoordinates() {
        int n = 48;
        long[] types = {3456789012345L, 1987654321099L, 2876543210113L, 4765432101237L};
        BigInteger[] low = new BigInteger[n], high = new BigInteger[n];
        var positive = new LinkedHashMap<Integer, BigInteger>();
        var negative = new LinkedHashMap<Integer, BigInteger>();
        BigInteger target = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            low[i] = BigInteger.ONE.shiftLeft(100 + i % 3).negate().add(BigInteger.valueOf(i - 17));
            high[i] = low[i].add(BigInteger.ONE);
            BigInteger weight = BigInteger.valueOf(types[i % types.length] * (i % 5 == 0 ? -1 : 1));
            positive.put(i, weight);
            negative.put(i, weight.negate());
            target = target.add(weight.multiply(i % 3 == 0 ? high[i] : low[i]));
        }
        var rows = List.of(new ExactLinearProgram.Constraint(positive, target),
                new ExactLinearProgram.Constraint(negative, target.negate()));
        BigInteger[] previous = null;
        long previousWork = -1;
        for (long quantum : new long[] {1_000_000, 37}) {
            var budget = budget(1_000_000);
            int pauses = 0;
            try (var search = new CountMeetInMiddle(rows, low, high, budget, Math.max(1024, quantum)).retained()) {
                for (;;) {
                    while (!search.step()) {}
                    if (!search.paused()) break;
                    long charged = budget.searchWork(), bytes = budget.reservedBytes();
                    check(search.step() && budget.searchWork() == charged && budget.reservedBytes() == bytes,
                            "Polling a quarter continuation repeated paid work");
                    search.resume(quantum);
                    check(++pauses < 100_000, "Quarter continuation stopped making progress");
                }
                BigInteger[] answer = search.counts();
                check(answer != null && !search.infeasible(), "Repeated exact sums exhausted the request");
                BigInteger actual = BigInteger.ZERO;
                for (int i = 0; i < n; i++) {
                    check(answer[i].equals(low[i]) || answer[i].equals(high[i]), "Restored quarter mask escaped its domain");
                    actual = actual.add(positive.get(i).multiply(answer[i]));
                }
                check(actual.equals(target), "Large-offset original equation rejected the witness");
                check(budget.diagnostics().contains("duplicates=") && !budget.diagnostics().contains("duplicates=0"),
                        "Repeated-coordinate boundary bypassed quarter reduction");
                if (previous != null) {
                    check(Arrays.equals(previous, answer) && previousWork == budget.searchWork(), "Sliced reduction rebuilt its table");
                    check(pauses > 100, "Insufficient collision/quarter continuation coverage");
                }
                previous = answer;
                previousWork = budget.searchWork();
            }
            check(budget.reservedBytes() == 0, "Retained quarter workspace leaked");
        }
    }

    private static void cancellationDuringReduction() {
        long[] values = new long[48];
        for (int i = 0; i < values.length; i++) values[i] = (i % 3 + 1) * 1234567891011L;
        var budget = budget(1_000_000);
        boolean cancelled = false;
        try (var search = new CountResidueJoin(values, 1234567891011L, budget, shape(values))) {
            // Four 4096-entry quarters have been materialized; reduction is
            // unfinished and uses the same table as the subsequent pair join.
            while (budget.searchWork() < 17_100) check(!search.step(), "Expected unfinished quarter reduction");
            budget.cancel();
            search.step();
        } catch (CancellationException expected) {
            cancelled = true;
        }
        check(cancelled && budget.reservedBytes() == 0, "Cancellation retained quarter scratch");
    }

    private static CountResidueJoin.Shape shape(long[] values) {
        long span = Arrays.stream(values).map(Math::abs).sum();
        var shape = CountResidueJoin.Shape.create(values.length, span);
        if (shape == null) throw new AssertionError("Invalid test arithmetic certificate");
        return shape;
    }

    private static PlanningBudget budget(long work) {
        return new PlanningBudget(0, work, 64L << 20, () -> false, System::nanoTime);
    }

    private static BigInteger sum(long[] values, long mask) {
        BigInteger total = BigInteger.ZERO;
        for (int i = 0; i < values.length; i++) if ((mask & (1L << i)) != 0) total = total.add(BigInteger.valueOf(values[i]));
        return total;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
