// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Private Boolean candidate search with a retained LP basis at each depth.
 * Numerical failures and exhausted branches never prove the face infeasible.
 * Only exact checks of every input row can publish a candidate.
 */
final class CountPackingLpSearch implements AutoCloseable {
    private static final long MAGNITUDE_LIMIT = 1L << 60;
    private static final BigInteger PRICE_SCALE = BigInteger.valueOf(65536);
    private final long[][] rows, capacity;
    private final long[] bounds, rhs, profit;
    private final long target;
    private final PlanningBudget budget;
    private final int n, m, total;
    private final double[] scales;
    private final double objectiveScale;
    private final Frame[] frames;
    private final BigInteger[] weights;
    private long memory, assignment, nodes, pivots, refusals;
    private int depth;
    private boolean initialized, complete, feasible;

    private static final class Frame {
        final double[][] tableau;
        final double[] point, cost;
        final int[] basis;
        final boolean[] basic;
        long fixed, ones;
        int branch = -1, turn, iterations;
        boolean first;

        Frame(int rows, int columns) {
            tableau = new double[rows][columns];
            point = new double[columns]; cost = new double[columns];
            basis = new int[rows]; basic = new boolean[columns];
        }
    }

    static CountPackingLpSearch create(long[][] rows, long[] bounds, PlanningBudget budget) {
        if (rows.length == 0 || rows.length != bounds.length || rows.length > 35) return null;
        int n = rows[0].length;
        if (n < 1 || n > 64) return null;
        int demand = -1, capacities = 0;
        for (int r = 0; r < rows.length; r++) {
            if (rows[r].length != n || bounds[r] <= -MAGNITUDE_LIMIT || bounds[r] >= MAGNITUDE_LIMIT) return null;
            long magnitude = Math.abs(bounds[r]);
            boolean positive = false, negative = false;
            for (long value : rows[r]) {
                budget.check();
                if (value <= -MAGNITUDE_LIMIT || value >= MAGNITUDE_LIMIT) return null;
                long absolute = Math.abs(value);
                if (absolute >= MAGNITUDE_LIMIT - magnitude) return null;
                magnitude += absolute;
                positive |= value > 0; negative |= value < 0;
            }
            if (negative && !positive && demand < 0) demand = r;
            else if (positive && !negative) capacities++;
        }
        if (demand < 0 || capacities == 0) return null;
        // Reserve all depth buffers and bounded exact-integer scratch before
        // allocation. Revisited siblings reuse their depth's existing arrays.
        int total = n + capacities;
        long bytes = 8192L + (n + 1L) * (512 + 24L * total + 8L * capacities * total + 48L * capacities);
        if (!budget.tryReserve(bytes)) return null;
        try {
            var result = new CountPackingLpSearch(rows, bounds, demand, capacities, budget, bytes);
            bytes = 0;
            return result;
        } finally { budget.release(bytes); }
    }

    private CountPackingLpSearch(long[][] rows, long[] bounds, int demand, int capacities,
                                 PlanningBudget budget, long memory) {
        this.rows = rows; this.bounds = bounds; this.budget = budget; this.memory = memory;
        n = rows[0].length; m = capacities; total = n + m;
        capacity = new long[m][]; rhs = new long[m]; profit = new long[n];
        scales = new double[m]; frames = new Frame[n + 1]; weights = new BigInteger[m];
        long largest = 1;
        for (int i = 0; i < n; i++) { profit[i] = -rows[demand][i]; largest = Math.max(largest, profit[i]); }
        objectiveScale = largest; target = -bounds[demand];
        var root = frames[0] = new Frame(m, total);
        for (int i = 0; i < n; i++) root.cost[i] = profit[i] / objectiveScale;
        int at = 0;
        for (int r = 0; r < rows.length; r++) {
            boolean positive = false, negative = false;
            for (long value : rows[r]) { budget.check(); positive |= value > 0; negative |= value < 0; }
            if (!positive || negative) continue;
            capacity[at] = rows[r]; rhs[at] = bounds[r];
            if (bounds[r] < 0) { complete = true; return; }
            long scale = 1; for (long value : rows[r]) scale = Math.max(scale, value);
            scales[at] = scale;
            budget.charge((total + 3L) / 4);
            for (int i = 0; i < n; i++) root.tableau[at][i] = rows[r][i] / scales[at];
            root.tableau[at][n + at] = 1; root.point[n + at] = bounds[r] / scales[at];
            root.basic[n + at] = true; root.basis[at] = n + at;
            at++;
        }
    }

    boolean step() {
        if (complete) return true;
        budget.checkpoint();
        if (depth < 0) { complete = true; return true; }
        var frame = frames[depth];
        if (!initialized) {
            if (++frame.iterations > 8192) { refusals++; complete = true; return true; }
            int state = primal(frame);
            if (state < 0) { refusals++; complete = true; return true; }
            if (state == 0) return false;
            initialized = true; frame.iterations = 0;
        }
        if (frame.branch >= 0) {
            if (frame.turn == 2) { depth--; return false; }
            boolean value = frame.turn++ == 0 ? frame.first : !frame.first;
            var child = copy(frame);
            child.fixed |= 1L << frame.branch;
            if (value) child.ones |= 1L << frame.branch; else child.ones &= ~(1L << frame.branch);
            if (!child.basic[frame.branch]) {
                double delta = (value ? 1 : 0) - child.point[frame.branch];
                budget.charge(m + 1);
                for (int r = 0; r < m; r++) child.point[child.basis[r]] -= child.tableau[r][frame.branch] * delta;
                child.point[frame.branch] = value ? 1 : 0;
            }
            depth++;
            return false;
        }
        if (++frame.iterations > 512) { refusals++; depth--; return false; }
        int state = dual(frame);
        if (state < 0) { refusals++; depth--; return false; }
        if (state == 0) return false;
        nodes++;
        if (!possible(frame)) { depth--; return false; }
        int branch = -1; double nearest = 1;
        long mask = frame.ones;
        for (int i = 0; i < n; i++) {
            budget.check(); double value = frame.point[i];
            if (!Double.isFinite(value)) { refusals++; depth--; return false; }
            if ((frame.fixed & (1L << i)) != 0) continue;
            if (value >= 0.5) mask |= 1L << i;
            if (value > 1e-6 && value < 1 - 1e-6 && Math.abs(value - 0.5) < nearest) {
                nearest = Math.abs(value - 0.5); branch = i;
            }
        }
        if (valid(mask)) { assignment = mask; feasible = complete = true; return true; }
        // Extra mixed rows need not belong to this numerical relaxation.
        // An integral LP point violating one is a declined proposal, not UNSAT.
        if (branch < 0) { refusals++; depth--; return false; }
        frame.branch = branch; frame.first = frame.point[branch] >= 0.5;
        return false;
    }

    private Frame copy(Frame source) {
        var target = frames[depth + 1];
        if (target == null) target = frames[depth + 1] = new Frame(m, total);
        for (int r = 0; r < m; r++) {
            budget.charge((total + 3L) / 4);
            System.arraycopy(source.tableau[r], 0, target.tableau[r], 0, total);
        }
        budget.charge(total + m);
        System.arraycopy(source.point, 0, target.point, 0, total);
        System.arraycopy(source.cost, 0, target.cost, 0, total);
        System.arraycopy(source.basis, 0, target.basis, 0, m);
        System.arraycopy(source.basic, 0, target.basic, 0, total);
        target.fixed = source.fixed; target.ones = source.ones;
        target.branch = -1; target.turn = target.iterations = 0; target.first = false;
        return target;
    }

    private int primal(Frame frame) {
        int enter = -1; double gain = 1e-8;
        budget.charge((total + 3L) / 4);
        for (int i = 0; i < total; i++) if (!frame.basic[i]) {
            double improvement = frame.point[i] > 0.5 ? -frame.cost[i] : frame.cost[i];
            if (improvement > gain) { enter = i; gain = improvement; }
        }
        if (enter < 0) return 1;
        double direction = frame.point[enter] > 0.5 ? -1 : 1;
        double delta = enter < n ? 1 : Double.POSITIVE_INFINITY;
        int leave = -1;
        budget.charge(m);
        for (int r = 0; r < m; r++) {
            double coefficient = frame.tableau[r][enter] * direction, value = frame.point[frame.basis[r]];
            double step = coefficient > 1e-10 ? value / coefficient :
                    coefficient < -1e-10 && frame.basis[r] < n ? (1 - value) / -coefficient : Double.POSITIVE_INFINITY;
            if (step < delta - 1e-9 || Math.abs(step - delta) <= 1e-9 && leave >= 0 && frame.basis[r] < frame.basis[leave]) {
                delta = Math.max(0, step); leave = r;
            }
        }
        if (!Double.isFinite(delta)) return -1;
        for (int r = 0; r < m; r++) frame.point[frame.basis[r]] -= frame.tableau[r][enter] * direction * delta;
        frame.point[enter] += direction * delta;
        if (leave < 0) return 0;
        int outgoing = frame.basis[leave];
        frame.point[outgoing] = outgoing < n && frame.point[outgoing] > 0.5 ? 1 : 0;
        return pivot(frame, leave, enter) ? 0 : -1;
    }

    private int dual(Frame frame) {
        int leave = -1; double violation = 1e-7, required = 0;
        budget.charge(m);
        for (int r = 0; r < m; r++) {
            int id = frame.basis[r];
            double low = id < n && (frame.fixed & (1L << id)) != 0 ? ((frame.ones & (1L << id)) != 0 ? 1 : 0) : 0;
            double high = id < n ? ((frame.fixed & (1L << id)) != 0 ? low : 1) : Double.POSITIVE_INFINITY;
            double need = frame.point[id] < low ? low - frame.point[id] : frame.point[id] > high ? high - frame.point[id] : 0;
            if (Math.abs(need) > violation) { violation = Math.abs(need); leave = r; required = need; }
        }
        if (leave < 0) return 1;
        int enter = -1; double best = Double.POSITIVE_INFINITY;
        budget.charge((total + 3L) / 4);
        for (int i = 0; i < total; i++) if (!frame.basic[i] && (i >= n || (frame.fixed & (1L << i)) == 0)) {
            double direction = i < n && frame.point[i] > 0.5 ? -1 : 1;
            double improve = -frame.tableau[leave][i] * direction * Math.signum(required);
            if (improve <= 1e-10) continue;
            double ratio = Math.max(0, -frame.cost[i] * direction) / improve;
            if (ratio < best - 1e-10) { best = ratio; enter = i; }
        }
        if (enter < 0) return -1;
        double delta = -required / frame.tableau[leave][enter];
        budget.charge(m + 1);
        for (int r = 0; r < m; r++) frame.point[frame.basis[r]] -= frame.tableau[r][enter] * delta;
        frame.point[enter] += delta;
        frame.point[frame.basis[leave]] = Math.rint(frame.point[frame.basis[leave]]);
        return pivot(frame, leave, enter) ? 0 : -1;
    }

    private boolean pivot(Frame frame, int leave, int enter) {
        double pivot = frame.tableau[leave][enter];
        if (!Double.isFinite(pivot) || Math.abs(pivot) < 1e-12) return false;
        budget.charge((total + 3L) / 4);
        for (int i = 0; i < total; i++) frame.tableau[leave][i] /= pivot;
        for (int r = 0; r < m; r++) if (r != leave) {
            budget.charge((total + 3L) / 4); double factor = frame.tableau[r][enter];
            for (int i = 0; i < total; i++) frame.tableau[r][i] -= factor * frame.tableau[leave][i];
        }
        budget.charge((total + 3L) / 4); double factor = frame.cost[enter];
        for (int i = 0; i < total; i++) frame.cost[i] -= factor * frame.tableau[leave][i];
        frame.basic[frame.basis[leave]] = false; frame.basic[enter] = true; frame.basis[leave] = enter;
        pivots++;
        return true;
    }

    private boolean possible(Frame frame) {
        // Any nonnegative prices give a valid upper bound over this fixed
        // Boolean box. Round only the prices; rebuild the bound with integers.
        // No floating LP status, tableau value or reduced-cost tolerance proves it.
        var upper = BigInteger.ZERO;
        for (int r = 0; r < m; r++) {
            double price = -frame.cost[n + r] * objectiveScale / scales[r];
            long weight = Double.isFinite(price) && price > 0 ? Math.min(1_000_000_000_000L, Math.round(price * 65536)) : 0;
            budget.operation(PlanningBudget.Operation.INTEGER, 128);
            weights[r] = BigInteger.valueOf(weight);
            upper = upper.add(weights[r].multiply(BigInteger.valueOf(rhs[r])));
        }
        for (int j = 0; j < n; j++) {
            var reduced = BigInteger.valueOf(profit[j]).multiply(PRICE_SCALE);
            for (int r = 0; r < m; r++) {
                budget.operation(PlanningBudget.Operation.INTEGER, 128);
                reduced = reduced.subtract(weights[r].multiply(BigInteger.valueOf(capacity[r][j])));
            }
            if ((frame.fixed & (1L << j)) == 0 ? reduced.signum() > 0 : (frame.ones & (1L << j)) != 0)
                upper = upper.add(reduced);
        }
        return upper.compareTo(BigInteger.valueOf(target).multiply(PRICE_SCALE)) >= 0;
    }

    private boolean valid(long mask) {
        // Admission bounds the absolute row activity, so primitive sums are exact.
        for (int r = 0; r < rows.length; r++) {
            long sum = 0; budget.charge(n);
            for (int i = 0; i < n; i++) if ((mask & (1L << i)) != 0) sum += rows[r][i];
            if (sum > bounds[r]) return false;
        }
        return true;
    }

    boolean feasible() { return feasible; }
    long assignment() {
        if (!feasible) throw new IllegalStateException("No LP candidate");
        return assignment;
    }
    String statistics() { return "nodes=" + nodes + "; pivots=" + pivots + "; refusals=" + refusals + "; witness=" + feasible; }

    @Override public void close() {
        complete = true;
        Arrays.fill(frames, null); Arrays.fill(weights, null);
        budget.release(memory); memory = 0;
    }
}
