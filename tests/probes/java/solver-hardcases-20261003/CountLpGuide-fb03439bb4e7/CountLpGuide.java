package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Bounded numerical relaxation for branching preferences only, never a proof. */
final class CountLpGuide implements AutoCloseable {

    private final PlanningBudget budget;
    private final BigInteger[] lower;
    private final int n, m;
    private final long allowance;
    private double[][] table;
    private int[] basic, nonbasic;
    private double[] point;
    private long memory, ticks;
    private int pivotRow = -1, pivotColumn, cell, pivots;
    private double divisor;
    private boolean started, complete;

    CountLpGuide(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high,
                 PlanningBudget budget, long allowance) {
        this.budget = budget;
        this.allowance = allowance;
        lower = low;
        n = low.length;
        m = rows.size() + n;
        long cells = (m + 1L) * (n + 2L);
        if (n == 0 || n > 1024 || cells > 1_048_576 || allowance < 4096 ||
                Arrays.stream(high).anyMatch(Objects::isNull)) {
            complete = true;
            return;
        }
        long bytes = 2048 + 16 * cells + 32L * (n + m);
        if (!budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        try {
            table = new double[m + 1][n + 2];
            basic = new int[m];
            nonbasic = new int[n + 1];
            for (int i = 0; i < m; i++) {
                basic[i] = n + i;
            }
            for (int j = 0; j < n; j++) nonbasic[j] = j;
            nonbasic[n] = -1;
            for (int r = 0; r < rows.size(); r++) {
                var row = rows.get(r);
                BigInteger rhs = row.upper(), largest = BigInteger.ONE;
                for (var term : row.terms().entrySet()) {
                    charge();
                    rhs = rhs.subtract(term.getValue().multiply(low[term.getKey()]));
                    largest = largest.max(term.getValue().abs());
                }
                largest = largest.max(rhs.abs());
                if (largest.bitLength() > 900) {
                    complete = true;
                    return;
                }
                double scale = largest.doubleValue();
                for (var term : row.terms().entrySet()) {
                    charge();
                    table[r][term.getKey()] = term.getValue().doubleValue() / scale;
                }
                table[r][n + 1] = rhs.doubleValue() / scale;
            }
            for (int j = 0; j < n; j++) {
                charge();
                BigInteger width = high[j].subtract(low[j]);
                if (width.signum() < 0 || width.bitLength() > 48) {
                    complete = true;
                    return;
                }
                table[rows.size() + j][j] = 1;
                table[rows.size() + j][n + 1] = width.doubleValue();
            }
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    boolean step() {
        if (complete) return true;
        if (PlanningBudget.units(ticks) >= allowance || pivots >= 2048) return finish(false);
        if (pivotRow >= 0) return pivot();
        int leaving = -1;
        for (int r = 0; r < m; r++) {
            charge();
            if (table[r][n + 1] < -1e-9 && (leaving < 0 || basic[r] < basic[leaving])) leaving = r;
        }
        if (leaving < 0) return finish(true);
        // The zero objective is dual feasible at every basis. Dual simplex
        // repairs a negative basic value without a dense artificial column.
        // Bland's ordering limits cycling; numerical failure only loses a hint.
        int entering = -1;
        for (int j = 0; j < n; j++) {
            charge();
            if (table[leaving][j] < -1e-10 && (entering < 0 || nonbasic[j] < nonbasic[entering])) entering = j;
        }
        return entering < 0 ? finish(false) : beginPivot(leaving, entering);
    }

    private boolean beginPivot(int row, int column) {
        divisor = table[row][column];
        if (!Double.isFinite(divisor) || Math.abs(divisor) < 1e-12) return finish(false);
        pivotRow = row;
        pivotColumn = column;
        cell = 0;
        pivots++;
        return false;
    }

    private boolean pivot() {
        int end = Math.min((m + 1) * (n + 2), cell + 1024);
        for (; cell < end; cell++) {
            int r = cell / (n + 2), c = cell % (n + 2);
            if (r == pivotRow || c == pivotColumn) continue;
            if (table[r][pivotColumn] == 0) {
                // Skip a zero column segment without charging or touching its
                // unrelated cells. The next continuation starts on a full row.
                cell = (r + 1) * (n + 2) - 1;
                continue;
            }
            charge();
            table[r][c] -= table[pivotRow][c] * (table[r][pivotColumn] / divisor);
            if (!Double.isFinite(table[r][c])) return finish(false);
        }
        if (cell < (m + 1) * (n + 2)) return false;
        for (int c = 0; c < n + 2; c++) if (c != pivotColumn) {
            charge();
            table[pivotRow][c] /= divisor;
        }
        for (int r = 0; r < m + 1; r++) if (r != pivotRow) {
            charge();
            table[r][pivotColumn] /= -divisor;
        }
        table[pivotRow][pivotColumn] = 1 / divisor;
        int old = basic[pivotRow];
        basic[pivotRow] = nonbasic[pivotColumn];
        nonbasic[pivotColumn] = old;
        pivotRow = -1;
        return false;
    }

    private boolean finish(boolean feasible) {
        if (feasible) {
            point = new double[n];
            for (int i = 0; i < m; i++) {
                charge();
                if (basic[i] >= 0 && basic[i] < n) point[basic[i]] = table[i][n + 1];
            }
        }
        complete = true;
        budget.note("count_lp_guide", "point=" + (point != null) + "; pivots=" + pivots + "; work=" + PlanningBudget.units(ticks) + "; heuristic_only");
        return true;
    }

    BigInteger[] rounded() {
        if (point == null) return null;
        BigInteger[] result = lower.clone();
        for (int i = 0; i < result.length; i++) {
            charge();
            if (!Double.isFinite(point[i]) || Math.abs(point[i]) > (1L << 49)) return null;
            result[i] = result[i].add(BigInteger.valueOf(Math.round(point[i])));
        }
        return result;
    }

    double[] fractionalities() {
        if (point == null) return null;
        double[] values = new double[n];
        for (int i = 0; i < n; i++) values[i] = Math.abs(point[i] - Math.rint(point[i]));
        return values;
    }

    private void charge() {
        ticks += budget.operation(PlanningBudget.Operation.SCAN, 0);
    }

    @Override
    public void close() {
        table = null;
        budget.release(memory);
        memory = 0;
    }
}
