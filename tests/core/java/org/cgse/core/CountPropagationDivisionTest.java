// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.lang.reflect.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent finite-box oracle and a deterministic baseline/candidate transcript. */
public final class CountPropagationDivisionTest {
    private static int checks;
    private static MessageDigest transcript;

    public static void main(String[] args) throws Exception {
        transcript = MessageDigest.getInstance("SHA-256");
        var random = new Random(20261027);
        for (int quantityBits : new int[]{0, 65, 511}) for (int coefficientBits : new int[]{0, 17, 127, 500}) {
            for (int sample = 0; sample < 40; sample++) {
                var low = new BigInteger[3]; var high = new BigInteger[3];
                var terms = new LinkedHashMap<Integer, BigInteger>();
                for (int i = 0; i < 3; i++) {
                    low[i] = BigInteger.ONE.shiftLeft(quantityBits).add(BigInteger.valueOf(random.nextInt(4)));
                    high[i] = low[i].add(BigInteger.valueOf(random.nextInt(4)));
                    var coefficient = BigInteger.ONE.shiftLeft(coefficientBits).add(BigInteger.valueOf(random.nextInt(7)));
                    if (random.nextBoolean()) coefficient = coefficient.negate();
                    if ((sample + i) % 11 == 0) coefficient = BigInteger.ZERO;
                    terms.put(i, coefficient);
                }
                BigInteger upper = BigInteger.valueOf(sample % 3 - 1);
                for (int i = 0; i < low.length; i++) {
                    var chosen = sample % 4 == 0 ? low[i] : sample % 4 == 1 ? high[i] :
                            low[i].add(high[i].subtract(low[i]).divide(BigInteger.TWO));
                    upper = upper.add(terms.get(i).multiply(chosen));
                }
                var row = new ExactLinearProgram.Constraint(terms, upper);
                BigInteger[][] oracle = enumerate(row, low, high);
                for (boolean projection : new boolean[]{false, true}) {
                    var actual = propagate(projection, row, low, high, -1, false);
                    check(actual.accepted == (oracle != null), "Box feasibility changed");
                    if (oracle != null) {
                        check(Arrays.equals(actual.low, oracle[0]), "Lower bound differs from exact finite-box support");
                        check(Arrays.equals(actual.high, oracle[1]), "Upper bound differs from exact finite-box support");
                    }
                }
            }
        }
        infiniteBounds();
        limits();
        System.out.println("CountPropagationDivisionTest PASS: checks=" + checks + "; transcript=" +
                HexFormat.of().formatHex(transcript.digest()));
    }

    private static BigInteger[][] enumerate(ExactLinearProgram.Constraint row, BigInteger[] low, BigInteger[] high) {
        var result = new BigInteger[][]{new BigInteger[low.length], new BigInteger[low.length]};
        visit(row, low, high, new BigInteger[low.length], 0, result);
        return result[0][0] == null ? null : result;
    }

    private static void visit(ExactLinearProgram.Constraint row, BigInteger[] low, BigInteger[] high,
                              BigInteger[] point, int coordinate, BigInteger[][] result) {
        if (coordinate != point.length) {
            for (var value = low[coordinate]; value.compareTo(high[coordinate]) <= 0; value = value.add(BigInteger.ONE)) {
                point[coordinate] = value;
                visit(row, low, high, point, coordinate + 1, result);
            }
            return;
        }
        var sum = BigInteger.ZERO;
        for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(point[term.getKey()]));
        if (sum.compareTo(row.upper()) > 0) return;
        for (int i = 0; i < point.length; i++) {
            result[0][i] = result[0][i] == null ? point[i] : result[0][i].min(point[i]);
            result[1][i] = result[1][i] == null ? point[i] : result[1][i].max(point[i]);
        }
    }

    private static void infiniteBounds() throws Exception {
        for (int bits : new int[]{0, 65, 500}) for (int coefficientBits : new int[]{0, 17, 500}) {
            var low = BigInteger.ONE.shiftLeft(bits).add(BigInteger.valueOf(3));
            var coefficient = BigInteger.ONE.shiftLeft(coefficientBits).add(BigInteger.valueOf(7));
            for (int delta : new int[]{-1, 0, 1, 3}) for (boolean negative : new boolean[]{false, true}) {
                var bound = low.add(BigInteger.valueOf(delta));
                var signed = negative ? coefficient.negate() : coefficient;
                // Offsets cover the last integer on both sides of an exact quotient.
                for (BigInteger offset : List.of(BigInteger.ZERO, BigInteger.ONE, coefficient.subtract(BigInteger.ONE))) {
                    var row = new ExactLinearProgram.Constraint(Map.of(0, signed), signed.multiply(bound).add(offset));
                    for (boolean projection : new boolean[]{false, true}) {
                        var actual = propagate(projection, row, new BigInteger[]{low}, new BigInteger[]{null}, -1, false);
                        check(actual.accepted == (negative || bound.compareTo(low) >= 0), "Unbounded row feasibility changed");
                        if (actual.accepted) {
                            check(actual.low[0].equals(negative ? low.max(bound) : low), "Signed ceiling lost its integer boundary");
                            check(Objects.equals(actual.high[0], negative ? null : bound), "Signed floor lost its integer boundary");
                        }
                    }
                }
            }
        }
    }

    private static void limits() throws Exception {
        var row = new ExactLinearProgram.Constraint(Map.of(0, BigInteger.valueOf(7), 1, BigInteger.valueOf(-13),
                2, BigInteger.valueOf(3)), BigInteger.valueOf(-4));
        var low = new BigInteger[]{BigInteger.TWO, BigInteger.TWO, BigInteger.TWO};
        var high = new BigInteger[]{BigInteger.valueOf(5), BigInteger.valueOf(5), BigInteger.valueOf(5)};
        for (boolean projection : new boolean[]{false, true}) {
            for (int allowance : new int[]{0, 1, 2, 5, 10, 20}) {
                var answer = propagate(projection, row, low, high, allowance, false);
                if (allowance == 0) check(answer.reason.equals("SEARCH_LIMIT"), "Work refusal was swallowed");
            }
            var cancelled = propagate(projection, row, low, high, -1, true);
            check(cancelled.reason.equals("CancellationException"), "Cancellation was swallowed");
            // An intermediate product may be much wider than its coefficient.
            // An inferred bound beyond the existing 1024-bit guard must still stop.
            var huge = BigInteger.ONE.shiftLeft(1023);
            var wide = new ExactLinearProgram.Constraint(Map.of(0, huge, 1, BigInteger.ONE.negate()), BigInteger.ZERO);
            var answer = propagate(projection, wide, new BigInteger[]{huge, BigInteger.ZERO},
                    new BigInteger[]{huge, null}, -1, false);
            check(!answer.accepted, "Division shortcut bypassed the bound precision guard");
        }
    }

    private record Outcome(boolean accepted, BigInteger[] low, BigInteger[] high, String reason) {}

    private static Outcome propagate(boolean projection, ExactLinearProgram.Constraint row, BigInteger[] low,
                                     BigInteger[] high, int workLeft, boolean cancel) throws Exception {
        var stop = new AtomicBoolean();
        var budget = new PlanningBudget(0, 8_000_000, 16L << 20, stop::get, System::nanoTime);
        budget.threadWork();
        List<ExactLinearProgram.Constraint> rows = List.of(row);
        AutoCloseable search = projection ? new CountDemandProjection(low.length, rows, budget, 64_000_000) :
                new CountDemandAllocation(low.length, rows, List.of(row.terms().entrySet().stream()
                        .filter(e -> e.getValue().signum() < 0).mapToInt(Map.Entry::getKey).toArray()), budget);
        try {
            // Install one nonnegative box directly so every quotient boundary
            // is tested independently of the portfolio's choice of a count word.
            field(search, "low").set(search, low.clone());
            field(search, "high").set(search, high.clone());
            if (!projection) {
                var deficit = row.upper().negate();
                for (var term : row.terms().entrySet()) deficit = deficit.add(term.getValue().multiply(low[term.getKey()]));
                field(search, "deficits").set(search, new BigInteger[]{deficit});
            }
            if (workLeft >= 0) budget.charge(budget.remainingWork() - workLeft);
            stop.set(cancel);
            long before = budget.nodes();
            var method = search.getClass().getDeclaredMethod("propagate", int.class); method.setAccessible(true);
            boolean accepted = true; String reason = "OK";
            try {
                var result = method.invoke(search, 0);
                if (!projection) accepted = (Boolean) result;
            } catch (InvocationTargetException invoked) {
                var failure = invoked.getCause(); accepted = false;
                if (failure instanceof PlanningBudget.Exhausted exhausted) reason = exhausted.limit().name();
                else if (failure instanceof java.util.concurrent.CancellationException) reason = "CancellationException";
                else if (projection && failure.getClass().getSimpleName().equals("Limit")) reason = "PROJECTION_LIMIT";
                else throw invoked;
            }
            var foundLow = ((BigInteger[]) field(search, "low").get(search)).clone();
            var foundHigh = ((BigInteger[]) field(search, "high").get(search)).clone();
            var record = projection + ":" + accepted + ":" + reason + ":" + (budget.nodes() - before) + ":" +
                    Arrays.toString(foundLow) + ":" + Arrays.toString(foundHigh) + "\n";
            transcript.update(record.getBytes(StandardCharsets.UTF_8));
            return new Outcome(accepted, foundLow, foundHigh, reason);
        } finally {
            search.close();
            check(budget.reservedBytes() == 0, "Propagation boundary retained reservations");
        }
    }

    private static Field field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
