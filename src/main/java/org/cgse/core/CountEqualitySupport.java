// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Exact bounded-sum supports borrowed by a private candidate search. */
final class CountEqualitySupport implements AutoCloseable {
    private static final int MAX_TERMS = 12, MAX_WIDTH = 15, MAX_RANGE = 2048;

    /** Borrowed until the next call. All bounds describe the same input box. */
    record Result(int[] variables, BigInteger[] lower, BigInteger[] upper, boolean empty) {}

    private static final class Equation {
        final ExactLinearProgram.Constraint row;
        final int[] ids;
        final BigInteger[] coefficients, previousLow, previousHigh;
        Result previousResult;

        Equation(ExactLinearProgram.Constraint row) {
            this.row = row;
            ids = row.terms().keySet().stream().mapToInt(Integer::intValue).toArray();
            coefficients = row.terms().values().toArray(BigInteger[]::new);
            previousLow = new BigInteger[ids.length];
            previousHigh = new BigInteger[ids.length];
        }
    }

    private final PlanningBudget budget;
    private Equation[] equations;
    private long memory, calls, changes, failures;

    private CountEqualitySupport(PlanningBudget budget, long memory) {
        this.budget = budget;
        this.memory = memory;
    }

    static CountEqualitySupport create(List<ExactLinearProgram.Constraint> rows, BigInteger[] low,
                                       BigInteger[] high, PlanningBudget budget) {
        if (low.length > 512 || rows.size() > 2048 || low.length != high.length) return null;
        long terms = 0;
        for (var row : rows) terms += row.terms().size();
        if (terms > 16384) return null;
        // Pairing maps, cached boxes, exact arithmetic and the largest live
        // prefix/suffix arrays (including temporary shifted words) are covered.
        // Cached pre-update endpoints and supported hulls can each retain
        // distinct 1024-bit integers after the LCG trail has backtracked.
        long bytes = 65536L + 512L * terms + 192L * rows.size() + 128L * low.length;
        if (!budget.tryReserve(bytes)) return null;
        var result = new CountEqualitySupport(budget, bytes);
        try {
            var known = new HashMap<Map<Integer, BigInteger>, Integer>();
            for (int r = 0; r < rows.size(); r++) {
                budget.check();
                var row = rows.get(r);
                Integer before = known.get(row.terms());
                if (before == null || row.upper().compareTo(rows.get(before).upper()) < 0) known.put(row.terms(), r);
            }
            result.equations = new Equation[rows.size()];
            int pairs = 0;
            for (int r = 0; r < rows.size(); r++) {
                budget.check();
                var row = rows.get(r);
                if (result.equations[r] != null || row.terms().size() < 2 || row.terms().size() > MAX_TERMS) continue;
                var opposite = new HashMap<Integer, BigInteger>();
                boolean small = row.upper().bitLength() <= 1024, weighted = false;
                for (var term : row.terms().entrySet()) {
                    budget.check();
                    int i = term.getKey();
                    if (i < 0 || i >= low.length || low[i] == null || high[i] == null) { small = false; break; }
                    BigInteger width = high[i].subtract(low[i]);
                    small &= width.signum() >= 0 && width.compareTo(BigInteger.valueOf(MAX_WIDTH)) <= 0 &&
                            Math.max(low[i].bitLength(), high[i].bitLength()) <= 1024 && term.getValue().bitLength() <= 1024;
                    weighted |= term.getValue().abs().compareTo(BigInteger.ONE) > 0;
                    opposite.put(i, term.getValue().negate());
                }
                Integer reverse = known.get(opposite);
                if (!small || !weighted || reverse == null || reverse == r || !row.upper().negate().equals(rows.get(reverse).upper())) continue;
                var equation = new Equation(row);
                result.equations[r] = result.equations[reverse] = equation;
                pairs++;
            }
            if (pairs == 0) { result.close(); return null; }
            budget.note("count_equality_support", "pairs=" + pairs + "; variables=" + low.length + "; private_candidate_scope");
            return result;
        } catch (RuntimeException | Error failure) {
            result.close();
            throw failure;
        }
    }

    Result propagate(int rowId, BigInteger[] low, BigInteger[] high) {
        // The parent may append certified rows later. Only the immutable,
        // originally paired equations participate in this private analysis.
        if (rowId >= equations.length || equations[rowId] == null) return null;
        Equation equation = equations[rowId];
        int n = equation.ids.length;
        boolean same = true;
        for (int k = 0; k < n; k++) {
            budget.check();
            int i = equation.ids[k];
            same &= low[i].equals(equation.previousLow[k]) && high[i].equals(equation.previousHigh[k]);
        }
        if (same) return equation.previousResult;
        equation.previousResult = null;
        // Equality, rather than a monotonic stamp, also handles backtracking.
        for (int k = 0; k < n; k++) {
            equation.previousLow[k] = low[equation.ids[k]];
            equation.previousHigh[k] = high[equation.ids[k]];
        }
        int[] width = new int[n], coefficient = new int[n];
        boolean[] negative = new boolean[n];
        BigInteger target = equation.row.upper(), gcd = BigInteger.ZERO;
        int free = 0;
        for (int k = 0; k < n; k++) {
            budget.check();
            int i = equation.ids[k];
            BigInteger a = equation.coefficients[k];
            negative[k] = a.signum() < 0;
            target = target.subtract(a.multiply(negative[k] ? high[i] : low[i]));
            width[k] = high[i].subtract(low[i]).intValueExact();
            if (width[k] > 0 && a.signum() != 0) { gcd = gcd.gcd(a); free++; }
        }
        // Interval propagation is cheaper while many coordinates are free.
        // A short residual is where exact holes can strengthen its endpoints.
        if (free < 2 || free > 4 || gcd.signum() == 0) return null;
        calls++;
        if (target.signum() < 0 || target.mod(gcd).signum() != 0) return empty(equation);
        target = target.divide(gcd);
        if (target.bitLength() > 30) return null;
        int rhs = target.intValue(), range = 0;
        for (int k = 0; k < n; k++) {
            budget.check();
            BigInteger value = width[k] == 0 ? BigInteger.ZERO : equation.coefficients[k].abs().divide(gcd);
            if (value.compareTo(BigInteger.valueOf(MAX_RANGE)) > 0) return null;
            coefficient[k] = value.intValue();
            range += coefficient[k] * width[k];
        }
        if (rhs > range) return empty(equation);
        if (range > MAX_RANGE) return null;
        if (rhs > range / 2) {
            rhs = range - rhs;
            for (int k = 0; k < n; k++) negative[k] = !negative[k];
        }
        var prefix = new BigInteger[n + 1];
        var suffix = new BigInteger[n + 1];
        prefix[0] = BigInteger.ONE;
        suffix[n] = BigInteger.ONE.shiftLeft(rhs);
        BigInteger mask = suffix[n].shiftLeft(1).subtract(BigInteger.ONE);
        long wordWork = 1L + (range >>> 6);
        for (int k = 0; k < n; k++) {
            BigInteger reachable = BigInteger.ZERO;
            for (int v = 0; v <= width[k]; v++) {
                budget.charge(wordWork);
                reachable = reachable.or(prefix[k].shiftLeft(coefficient[k] * v));
            }
            prefix[k + 1] = reachable.and(mask);
        }
        if (!prefix[n].testBit(rhs)) return empty(equation);
        for (int k = n - 1; k >= 0; k--) {
            BigInteger reachable = BigInteger.ZERO;
            for (int v = 0; v <= width[k]; v++) {
                budget.charge(wordWork);
                reachable = reachable.or(suffix[k + 1].shiftRight(coefficient[k] * v));
            }
            suffix[k] = reachable;
        }
        var resultLow = new BigInteger[n];
        var resultHigh = new BigInteger[n];
        boolean changed = false;
        for (int k = 0; k < n; k++) {
            int min = width[k] + 1, max = -1;
            for (int v = 0; v <= width[k]; v++) {
                budget.charge(wordWork);
                if (prefix[k].and(suffix[k + 1].shiftRight(coefficient[k] * v)).signum() != 0) {
                    int value = negative[k] ? width[k] - v : v;
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
            }
            if (max < 0) return empty(equation);
            resultLow[k] = low[equation.ids[k]].add(BigInteger.valueOf(min));
            resultHigh[k] = low[equation.ids[k]].add(BigInteger.valueOf(max));
            changed |= min > 0 || max < width[k];
        }
        if (!changed) return null;
        changes++;
        return equation.previousResult = new Result(equation.ids, resultLow, resultHigh, false);
    }

    private Result empty(Equation equation) {
        failures++;
        return equation.previousResult = new Result(equation.ids, null, null, true);
    }

    @Override public void close() {
        if (memory == 0) return;
        budget.note("count_equality_support", "calls=" + calls + "; changes=" + changes + "; empty_boxes=" + failures);
        equations = null;
        budget.release(memory);
        memory = 0;
    }
}
