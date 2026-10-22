// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Exhaustive tiny-domain oracle for reversible sparse capacity propagation. */
public final class CountDemandAllocationTest {
    private static int checks;
    public static void main(String[] args) {
        var random = new Random(0xCA9AC17L);
        for (int sample = 0; sample < 1200; sample++) {
            int variables = 2 + sample % 2;
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int i = 0; i < variables; i++) rows.add(row(Map.of(i, 1), 4));
            for (int r = 0; r < 4; r++) {
                var terms = new LinkedHashMap<Integer, Integer>();
                for (int i = 0; i < variables; i++) { int a = random.nextInt(7) - 3; if (a != 0) terms.put(i, a); }
                rows.add(row(terms, random.nextInt(17) - 8));
            }
            boolean expected = enumerate(rows, new BigInteger[variables], 0);
            var answer = solve(variables, rows, 16L << 20);
            check((answer != null) == expected, "Tiny finite domain disagrees with exhaustive oracle, sample=" + sample);
            if (answer != null) check(valid(rows, answer), "Allocation escaped an original constraint");
        }
        var a = BigInteger.ONE.shiftLeft(90).add(BigInteger.valueOf(7));
        var b = BigInteger.ONE.shiftLeft(88).add(BigInteger.valueOf(11));
        var exact = List.of(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE), a),
                new ExactLinearProgram.Constraint(Map.of(1, BigInteger.ONE), b),
                new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE.negate(), 1, BigInteger.ONE.negate()), a.add(b).negate()));
        var counts = solve(2, exact, 16L << 20);
        check(counts != null && counts[0].equals(a) && counts[1].equals(b), "Capacity split lost exact quantities above long");
        check(solve(2, exact, 16) == null, "Optional memory refusal invented counts");
        repeatedFeedbackRollback();
        System.out.println("CountDemandAllocationTest PASS: checks=" + checks);
    }

    private static void repeatedFeedbackRollback() {
        // Choose x or y in each of two independent demand rows. Choosing
        // both x's makes a >= b + 1 and b >= a, so finite capacity proves
        // that combination impossible. It takes many small tightenings;
        // the other three source combinations have explicit zero-a/b oracles.
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int variable : new int[] {0, 1, 4, 5}) rows.add(row(Map.of(variable, 1), 1));
        rows.add(row(Map.of(2, 1), 40_000));
        rows.add(row(Map.of(3, 1), 40_000));
        rows.add(row(new TreeMap<>(Map.of(0, -1, 1, -1)), -1));
        rows.add(row(new TreeMap<>(Map.of(4, -1, 5, -1)), -1));
        rows.add(row(new TreeMap<>(Map.of(0, 1, 4, 1, 3, 1, 2, -1)), 1));
        rows.add(row(new TreeMap<>(Map.of(2, 1, 3, -1)), 0));
        var expected = new HashSet<List<BigInteger>>();
        for (int[] word : new int[][] {{1, 0, 0, 0, 0, 1}, {0, 1, 0, 0, 1, 0}, {0, 1, 0, 0, 0, 1}}) {
            var values = Arrays.stream(word).mapToObj(BigInteger::valueOf).toArray(BigInteger[]::new);
            check(valid(rows, values), "Independent feedback boundary oracle is invalid");
            expected.add(List.of(values));
        }
        var producers = new ArrayList<int[]>();
        for (var row : rows) producers.add(row.terms().entrySet().stream().filter(e -> e.getValue().signum() < 0)
                .mapToInt(Map.Entry::getKey).sorted().toArray());
        var budget = new PlanningBudget(0, 2_000_000, 128L << 10, () -> false, System::nanoTime);
        try (var search = new CountDemandAllocation(6, rows, producers, budget)) {
            do {
                while (!search.step()) {}
                var result = search.result();
                check(result != null && valid(rows, result), "Feedback rollback lost an executable source choice");
                check(expected.remove(List.of(result)), "Rollback repeated or invented a source allocation");
            } while (search.reject());
            check(expected.isEmpty(), "Nested rollback failed to restore a sibling's bounds");
        }
        check(budget.reservedBytes() == 0, "Coalesced rollback retained its trail");
        for (int stop : new int[] {25, 250, 2500}) {
            var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            var interrupted = new PlanningBudget(0, 2_000_000, 128L << 10, cancelled::get, System::nanoTime);
            try (var search = new CountDemandAllocation(6, rows, producers, interrupted)) {
                for (int step = 0; step < stop; step++) check(!search.step(), "Feedback probe ended before cancellation");
                cancelled.set(true);
                try { search.step(); throw new AssertionError("Cancellation was swallowed during feedback propagation"); }
                catch (java.util.concurrent.CancellationException expectedCancellation) { checks++; }
                check(search.result() == null, "Interrupted propagation published unfinished counts");
            }
            check(interrupted.reservedBytes() == 0, "Interrupted feedback retained its coalesced trail");
        }
        var limited = new PlanningBudget(0, 2_000_000, 128L << 10, () -> false, System::nanoTime);
        long occupied;
        try (var search = new CountDemandAllocation(6, rows, producers, limited)) {
            check(limited.reservedBytes() > 0, "Memory control did not reach the allocation phase");
            occupied = limited.availableBytes() - 256;
            limited.reserve(occupied);
            while (!search.step()) {}
            check(search.result() == null, "Trail memory refusal published unfinished counts");
        }
        limited.release(occupied);
        check(limited.reservedBytes() == 0, "Trail memory refusal retained reservations");
    }

    private static BigInteger[] solve(int variables, List<ExactLinearProgram.Constraint> rows, long memory) {
        var producers = new ArrayList<int[]>();
        for (var row : rows) producers.add(row.terms().entrySet().stream().filter(e -> e.getValue().signum() < 0)
                .mapToInt(Map.Entry::getKey).toArray());
        var budget = new PlanningBudget(0, 2_000_000, memory, () -> false, System::nanoTime);
        BigInteger[] result;
        try (var search = new CountDemandAllocation(variables, rows, producers, budget)) {
            while (!search.step()) {}
            result = search.result();
        }
        check(budget.reservedBytes() == 0, "Capacity branch or rollback leaked reservations");
        return result;
    }

    private static boolean enumerate(List<ExactLinearProgram.Constraint> rows, BigInteger[] values, int at) {
        if (at == values.length) return valid(rows, values);
        for (int value = 0; value <= 4; value++) {
            values[at] = BigInteger.valueOf(value);
            if (enumerate(rows, values, at + 1)) return true;
        }
        return false;
    }
    private static boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] values) {
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(values[term.getKey()].multiply(term.getValue()));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }
    private static ExactLinearProgram.Constraint row(Map<Integer, Integer> terms, long upper) {
        var exact = new LinkedHashMap<Integer, BigInteger>(); terms.forEach((k, v) -> exact.put(k, BigInteger.valueOf(v)));
        return new ExactLinearProgram.Constraint(exact, BigInteger.valueOf(upper));
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
