// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** A finite LP-guided repair of a wide allocation bin; failure proves nothing. */
final class CountVectorNeighborhood implements CountContinuation {
    private static final long REPAIR_WORK = 786432;
    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private final CountLpHint.Session relaxation = new CountLpHint.Session();
    private final long maximumWork;
    private CountAffineLattice repair;
    private double[] point;
    private BigInteger[] seed, counts;
    private int[] free;
    private long memory, work, until, lpWork, trialStarted, completedProgress;
    private int attempt;
    private boolean complete, paused;

    static CountVectorNeighborhood create(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                                          BigInteger[] upper, PlanningBudget budget, long quantum) {
        if (lower.length <= 32 || lower.length > 128 || upper.length != lower.length || rows.size() > 12 || quantum <= 0 ||
                budget.remainingWork() < 32768) return null;
        long terms = 0;
        for (var row : rows) { budget.check(); terms += row.terms().size(); }
        if (terms > 1536) return null;
        for (int i = 0; i < lower.length; i++) {
            budget.check();
            if (upper[i] == null || lower[i].signum() < 0 || lower[i].bitLength() > 30 || upper[i].bitLength() > 30 ||
                    lower[i].compareTo(upper[i]) > 0) return null;
        }
        long bytes = 8192L + 384L * lower.length + 384L * terms;
        if (!budget.tryReserve(bytes)) return null;
        try { return new CountVectorNeighborhood(rows, lower, upper, budget, quantum, bytes); }
        catch (RuntimeException | Error failure) { budget.release(bytes); throw failure; }
    }

    private CountVectorNeighborhood(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                                    BigInteger[] upper, PlanningBudget budget, long quantum, long bytes) {
        this.rows = rows; this.lower = lower; this.upper = upper; this.budget = budget;
        memory = bytes;
        maximumWork = Math.min(3 * REPAIR_WORK, budget.remainingWork() / 8);
        until = CountContinuation.deadline(0, quantum, budget);
    }

    @Override public boolean step() {
        if (complete || paused) return true;
        budget.checkpoint();
        if (work >= maximumWork) return finish("local_limit");
        if (work >= until) return paused = true;
        long before = budget.threadWork();
        try {
            budget.check();
            if (point == null) {
                if (lpWork >= 262144) return finish("relaxation_limit");
                long grant = Math.min(32768, Math.min(262144 - lpWork, maximumWork - work));
                if (grant < 1024) return finish("relaxation_limit");
                var admission = new CountLpHint.Attempt();
                long started = budget.threadWork();
                try (var result = relaxation.pending() ? relaxation.resume(grant, admission) :
                        relaxation.start(rows, lower, upper, budget, grant, admission)) {
                    if (result == null) {
                        if (!relaxation.pending() || budget.threadWork() == started) return finish("no_relaxation");
                        return false;
                    }
                    point = result.point.clone();
                    seed = new BigInteger[point.length];
                    for (int i = 0; i < point.length; i++) {
                        budget.check();
                        if (!Double.isFinite(point[i]) || point[i] < lower[i].doubleValue() - 1e-5 ||
                                point[i] > upper[i].doubleValue() + 1e-5) return finish("invalid_hint");
                        seed[i] = BigInteger.valueOf(Math.round(point[i])).max(lower[i]).min(upper[i]);
                    }
                    completedProgress++;
                    if (valid(seed)) { counts = seed.clone(); return finish("rounded_candidate"); }
                    return false;
                } finally { lpWork += budget.threadWork() - before; }
            }
            if (repair == null && !beginRepair()) return finish("unsupported_core");
            boolean done = repair.step();
            if (done && repair.paused()) {
                long grant = Math.min(32768, Math.min(maximumWork - work,
                        REPAIR_WORK - (work - trialStarted))) - (budget.threadWork() - before);
                if (grant > 0) { repair.resume(grant); return false; }
            } else if (done && repair.counts() != null) {
                var local = repair.counts();
                for (int i = 0; i < free.length; i++) { budget.check(); seed[free[i]] = local[i]; }
                if (valid(seed)) { counts = seed.clone(); return finish("verified_candidate"); }
            }
            if (done || work + budget.threadWork() - before - trialStarted >= REPAIR_WORK) {
                completedProgress += repair.progress(); repair.close(); repair = null;
                if (++attempt == 3) return finish("cores_unresolved");
            }
            return false;
        } finally { work += budget.threadWork() - before; }
    }

    private boolean beginRepair() {
        var ranked = new ArrayList<Integer>();
        seed = new BigInteger[lower.length];
        for (int i = 0; i < lower.length; i++) {
            budget.check(); seed[i] = BigInteger.valueOf(Math.round(point[i])).max(lower[i]).min(upper[i]);
            if (!lower[i].equals(upper[i])) ranked.add(i);
        }
        // Fractional coordinates must remain free. Other fixings are temporary
        // candidate restrictions; deterministic diversification changes no domain.
        var random = new Random(731243L + (attempt == 2 ? 0 : attempt));
        for (int i = ranked.size(); i > 1; i--) { budget.check(); Collections.swap(ranked, i - 1, random.nextInt(i)); }
        ranked.sort(Comparator.comparingInt(i -> { budget.check(); return fractional(i) ? 0 : 1; }));
        long fractional = 0;
        for (int i : ranked) { budget.check(); if (fractional(i)) fractional++; }
        int width = attempt == 2 ? 28 : 24;
        if (ranked.size() < 2 || fractional > width) return false;
        free = ranked.stream().limit(width).sorted().mapToInt(Integer::intValue).toArray();
        int[] map = new int[lower.length]; Arrays.fill(map, -1);
        var lo = new BigInteger[free.length]; var hi = new BigInteger[free.length];
        for (int i = 0; i < free.length; i++) {
            budget.check(); int id = free[i]; map[id] = i; lo[i] = lower[id]; hi[i] = upper[id];
        }
        var projected = new ArrayList<ExactLinearProgram.Constraint>();
        for (var row : rows) {
            var rhs = row.upper(); var terms = new TreeMap<Integer, BigInteger>();
            for (var term : row.terms().entrySet()) {
                budget.check(); int id = term.getKey(); var a = term.getValue();
                if (map[id] >= 0) terms.put(map[id], a);
                else {
                    budget.operation(PlanningBudget.Operation.INTEGER, Math.max(rhs.bitLength(), a.bitLength() + seed[id].bitLength()));
                    rhs = rhs.subtract(a.multiply(seed[id]));
                }
            }
            projected.add(new ExactLinearProgram.Constraint(terms, rhs));
        }
        trialStarted = work;
        repair = new CountAffineLattice(projected, lo, hi, budget, 32768).retained().enumerateBox();
        budget.note("count_vector_neighborhood", "attempt=" + attempt + "; variables=" + lower.length + "; free=" + free.length);
        return true;
    }

    private boolean fractional(int i) { return Math.abs(point[i] - Math.rint(point[i])) > 1e-6; }
    private boolean valid(BigInteger[] value) {
        for (int i = 0; i < value.length; i++) {
            budget.check(); if (value[i].compareTo(lower[i]) < 0 || value[i].compareTo(upper[i]) > 0) return false;
        }
        for (var row : rows) {
            var sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                budget.check(); var a = term.getValue(); var x = value[term.getKey()];
                budget.operation(PlanningBudget.Operation.INTEGER, Math.max(sum.bitLength(), a.bitLength() + x.bitLength()));
                sum = sum.add(a.multiply(x));
            }
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }
    private boolean finish(String reason) {
        complete = true; paused = false;
        budget.note("count_vector_neighborhood", reason + "; work=" + work + "; attempts=" + (attempt + 1));
        return true;
    }
    @Override public boolean paused() { return paused; }
    @Override public void resume(long quantum) { until = CountContinuation.deadline(work, quantum, budget); paused = false; }
    @Override public BigInteger[] counts() { return counts == null ? null : counts.clone(); }
    @Override public long work() { return work; }
    @Override public long progress() { return completedProgress + (repair == null ? 0 : repair.progress()); }
    @Override public boolean matches(List<ExactLinearProgram.Constraint> other, BigInteger[] low, BigInteger[] high) {
        return rows.equals(other) && Arrays.equals(lower, low) && Arrays.equals(upper, high);
    }
    @Override public void close() {
        relaxation.close();
        if (repair != null) { completedProgress += repair.progress(); repair.close(); repair = null; }
        budget.release(memory); memory = 0;
        complete = true; paused = false;
    }
}
