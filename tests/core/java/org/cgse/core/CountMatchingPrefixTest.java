// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Independent checks for partially rejected prefixes and restored parent sums. */
public final class CountMatchingPrefixTest {
    public static void main(String[] args) {
        exhaustive();
        wideArithmetic();
        interrupted();
        System.out.println("Finite matching prefixes: 96 exhaustive models, 1100-bit rows, local/memory cutoffs and cancellation passed");
    }

    private static PlanningBudget budget(long memory) {
        return new PlanningBudget(0, 20_000_000, memory, () -> false, System::nanoTime);
    }

    private static void exhaustive() {
        var random = new Random(8102907);
        for (int trial = 0; trial < 96; trial++) {
            int n = 10, bits = new int[] { 0, 65, 257, 1100 }[trial % 4];
            BigInteger[] low = new BigInteger[n], high = new BigInteger[n], anchor = new BigInteger[n];
            Arrays.fill(low, BigInteger.ONE.shiftLeft(100).add(BigInteger.valueOf(trial)));
            for (int i = 0; i < n; i++) {
                high[i] = low[i].add(BigInteger.ONE);
                anchor[i] = low[i].add(BigInteger.valueOf(random.nextInt(2)));
            }
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int d = 0; d < 4; d++) {
                var terms = new LinkedHashMap<Integer, BigInteger>();
                BigInteger target = BigInteger.ZERO;
                for (int i = 0; i < n; i++) {
                    BigInteger a = BigInteger.valueOf(random.nextInt(15) - 7).shiftLeft(bits)
                            .add(BigInteger.valueOf(random.nextInt(5) - 2));
                    if (a.signum() != 0) terms.put(i, a);
                    target = target.add(a.multiply(anchor[i]));
                }
                if (trial % 3 == 0 && d == 0) target = target.add(BigInteger.ONE);
                BigInteger slack = BigInteger.valueOf(trial % 2);
                rows.add(new ExactLinearProgram.Constraint(terms, target.add(slack)));
                var opposite = new LinkedHashMap<Integer, BigInteger>();
                terms.forEach((id, value) -> opposite.put(id, value.negate()));
                rows.add(new ExactLinearProgram.Constraint(opposite, target.negate()));
            }
            Collections.shuffle(rows, random);
            boolean possible = false;
            for (int code = 0; code < 1 << n; code++) {
                BigInteger[] point = low.clone();
                for (int i = 0; i < n; i++) if ((code & 1 << i) != 0) point[i] = high[i];
                possible |= valid(rows, low, high, point);
            }
            var budget = budget(64L << 20);
            try (var match = new CountMeetInMiddle(rows, low, high, budget)) {
                while (!match.step()) {}
                check((match.counts() != null) == possible, "Lost prefix completion " + trial);
                check(match.infeasible() != possible, "Wrong finite-domain conclusion " + trial);
                if (possible) check(valid(rows, low, high, match.counts()), "Invalid restored prefix " + trial);
            }
            check(budget.reservedBytes() == 0, "Prefix workspace leaked");
        }
    }

    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {}

    private static Model model(int variables, int bits) {
        var random = new Random(600133);
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int d = 0; d < 8; d++) {
            var terms = new LinkedHashMap<Integer, BigInteger>();
            var opposite = new LinkedHashMap<Integer, BigInteger>();
            BigInteger target = BigInteger.ZERO;
            for (int i = 0; i < variables; i++) {
                BigInteger a = BigInteger.valueOf(1 + random.nextInt(1000)).shiftLeft(bits)
                        .add(BigInteger.valueOf(random.nextInt(7)));
                terms.put(i, a);
                opposite.put(i, a.negate());
                if (i % 3 == 0) target = target.add(a);
            }
            rows.add(new ExactLinearProgram.Constraint(terms, target));
            rows.add(new ExactLinearProgram.Constraint(opposite, target.negate()));
        }
        BigInteger[] low = new BigInteger[variables], high = new BigInteger[variables];
        Arrays.fill(low, BigInteger.ZERO);
        Arrays.fill(high, BigInteger.ONE);
        return new Model(rows, low, high);
    }

    private static void wideArithmetic() {
        var model = model(24, 1100);
        var budget = budget(64L << 20);
        try (var match = new CountMeetInMiddle(model.rows, model.low, model.high, budget)) {
            while (!match.step()) {}
            check(valid(model.rows, model.low, model.high, match.counts()), "Wide exact prefix arithmetic lost a planted witness");
        }
        check(budget.reservedBytes() == 0, "Wide prefix workspace leaked");
    }

    private static void interrupted() {
        var model = model(32, 0);
        for (int mode = 0; mode < 3; mode++) {
            var budget = budget(mode == 1 ? 1L << 20 : 64L << 20);
            try (var match = new CountMeetInMiddle(model.rows, model.low, model.high, budget, mode == 0 ? 2048 : 12_000_000)) {
                if (mode == 2) {
                    while (budget.searchWork() < 20000) check(!match.step(), "Expected a live matching frontier");
                    budget.cancel();
                    boolean cancelled = false;
                    try { match.step(); } catch (CancellationException expected) { cancelled = true; }
                    check(cancelled, "Live prefix enumeration ignored cancellation");
                } else {
                    while (!match.step()) {}
                    check(match.counts() == null && !match.infeasible(), "Cutoff became a negative proof");
                    if (mode == 0) check(budget.searchWork() <= 2050, "Local matching quota was ignored");
                    else check(budget.peakBytes() <= 1L << 20, "Prefix snapshots exceeded reservation cap");
                }
            }
            check(budget.reservedBytes() == 0, "Interrupted prefix workspace leaked");
        }
    }

    private static boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high, BigInteger[] values) {
        if (values == null || values.length != low.length) return false;
        for (int i = 0; i < values.length; i++)
            if (values[i].compareTo(low[i]) < 0 || values[i].compareTo(high[i]) > 0) return false;
        for (var row : rows) {
            BigInteger total = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) total = total.add(term.getValue().multiply(values[term.getKey()]));
            if (total.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
