// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Search a reduced integer kernel; only original-coordinate witnesses escape. */
final class CountKernelSearch implements AutoCloseable {
    private static final class Stop extends RuntimeException {
        Stop() { super(null, null, false, false); }
    }

    private final List<ExactLinearProgram.Constraint> original;
    private final BigInteger[] lower, upper, point;
    private final List<BigInteger[]> basis;
    private final PlanningBudget budget;
    private final long allowance;
    private CountJump search;
    private BigInteger[] counts;
    private long memory, work, started;
    private boolean complete;

    CountKernelSearch(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                      BigInteger[] point, List<BigInteger[]> basis, ExactRational[][] mu, ExactRational[] norms,
                      PlanningBudget budget, long maximumWork) {
        original = rows;
        this.lower = lower;
        this.upper = upper;
        this.point = point;
        this.basis = basis;
        this.budget = budget;
        allowance = Math.min(maximumWork, budget.remainingWork() / 16);
        started = budget.threadWork();
        int n = lower.length, d = basis.size();
        long bytes = 4096L + 2048L * n * d + 256L * (rows.size() + 2L * n) * (d + 1L);
        if (d < 2 || d >= n || n > 48 || rows.size() > 512 || allowance < 2048 || !budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        try {
            prepare(mu, norms);
        } catch (Stop | ExactRational.PrecisionLimit failure) {
            complete = true;
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        } finally { work = budget.threadWork() - started; }
    }

    private void prepare(ExactRational[][] mu, ExactRational[] norms) {
        int n = lower.length, d = basis.size();
        var orthogonal = new ExactRational[d][n];
        var dual = new ExactRational[d][n];
        // Reconstruct B* from the updated LLL factorization. Its cached vectors
        // can be stale after size reductions/swaps, while mu and norms are exact.
        for (int j = 0; j < d; j++) for (int i = 0; i < n; i++) {
            check();
            var value = ExactRational.of(basis.get(j)[i]);
            for (int k = 0; k < j; k++) {
                check();
                value = subtract(value, multiply(mu[j][k], orthogonal[k][i]));
            }
            orthogonal[j][i] = value;
        }
        BigInteger[] low = new BigInteger[d], high = new BigInteger[d];
        for (int j = d - 1; j >= 0; j--) {
            ExactRational min = ExactRational.ZERO, max = ExactRational.ZERO;
            for (int i = 0; i < n; i++) {
                check();
                if (upper[i] == null) throw new Stop();
                rationalCost(orthogonal[j][i], norms[j]);
                var coefficient = orthogonal[j][i].divide(norms[j]);
                for (int k = j + 1; k < d; k++) {
                    check();
                    coefficient = subtract(coefficient, multiply(mu[k][j], dual[k][i]));
                }
                dual[j][i] = coefficient;
                var first = ExactRational.of(lower[i].subtract(point[i]));
                var last = ExactRational.of(upper[i].subtract(point[i]));
                min = add(min, multiply(coefficient, coefficient.signum() >= 0 ? first : last));
                max = add(max, multiply(coefficient, coefficient.signum() >= 0 ? last : first));
            }
            low[j] = min.ceil();
            high[j] = max.floor();
            if (low[j].compareTo(high[j]) > 0) throw new Stop();
        }
        var projected = new ArrayList<ExactLinearProgram.Constraint>();
        for (var row : original) project(row, projected);
        for (int i = 0; i < n; i++) {
            project(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), upper[i]), projected);
            project(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE.negate()), lower[i].negate()), projected);
        }
        long remaining = allowance - (budget.threadWork() - started);
        if (remaining < 1024) throw new Stop();
        var initial = new BigInteger[d];
        Arrays.fill(initial, BigInteger.ZERO);
        search = new CountJump(projected, low, high, budget, remaining, initial);
        budget.note("count_kernel", "admitted; original=" + n + "; coordinates=" + d + "; rows=" + projected.size());
    }

    private void project(ExactLinearProgram.Constraint row, List<ExactLinearProgram.Constraint> result) {
        var terms = new LinkedHashMap<Integer, BigInteger>();
        BigInteger rhs = row.upper();
        for (var term : row.terms().entrySet()) {
            check();
            integerCost(term.getValue(), point[term.getKey()]);
            rhs = rhs.subtract(term.getValue().multiply(point[term.getKey()]));
        }
        for (int j = 0; j < basis.size(); j++) {
            BigInteger coefficient = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                check();
                integerCost(term.getValue(), basis.get(j)[term.getKey()]);
                coefficient = coefficient.add(term.getValue().multiply(basis.get(j)[term.getKey()]));
            }
            if (coefficient.signum() != 0) terms.put(j, coefficient);
        }
        if (terms.isEmpty()) {
            if (rhs.signum() < 0) throw new Stop();
        } else result.add(new ExactLinearProgram.Constraint(terms, rhs));
    }

    boolean step() {
        if (complete) return true;
        started = budget.threadWork();
        try {
            budget.checkpoint();
            if (work >= allowance) return complete = true;
            if (!search.step()) return false;
            var values = search.counts();
            if (values != null) {
                var restored = point.clone();
                for (int i = 0; i < restored.length; i++) {
                    for (int j = 0; j < values.length; j++) {
                        check();
                        integerCost(values[j], basis.get(j)[i]);
                        restored[i] = restored[i].add(values[j].multiply(basis.get(j)[i]));
                    }
                    if (restored[i].compareTo(lower[i]) < 0 || restored[i].compareTo(upper[i]) > 0) return complete = true;
                }
                for (var row : original) {
                    BigInteger sum = BigInteger.ZERO;
                    for (var term : row.terms().entrySet()) {
                        check();
                        integerCost(term.getValue(), restored[term.getKey()]);
                        sum = sum.add(term.getValue().multiply(restored[term.getKey()]));
                    }
                    if (sum.compareTo(row.upper()) > 0) return complete = true;
                }
                counts = restored;
            }
            return complete = true;
        } catch (Stop failure) { return complete = true; }
        finally { work += budget.threadWork() - started; }
    }

    private void check() {
        if (work + budget.threadWork() - started >= allowance) throw new Stop();
        budget.check();
    }
    private void integerCost(BigInteger a, BigInteger b) {
        budget.operation(PlanningBudget.Operation.INTEGER, Math.max(a.bitLength(), b.bitLength()));
    }
    private void rationalCost(ExactRational a, ExactRational b) {
        budget.operation(PlanningBudget.Operation.RATIONAL, Math.max(Math.max(a.numerator().bitLength(), a.denominator().bitLength()),
                Math.max(b.numerator().bitLength(), b.denominator().bitLength())));
        if (work + budget.threadWork() - started >= allowance) throw new Stop();
    }
    private ExactRational add(ExactRational a, ExactRational b) { rationalCost(a, b); return a.add(b); }
    private ExactRational subtract(ExactRational a, ExactRational b) { rationalCost(a, b); return a.subtract(b); }
    private ExactRational multiply(ExactRational a, ExactRational b) { rationalCost(a, b); return a.multiply(b); }
    BigInteger[] counts() { return counts; }
    @Override public void close() {
        if (search != null) search.close();
        search = null;
        budget.release(memory);
        memory = 0;
    }
}
