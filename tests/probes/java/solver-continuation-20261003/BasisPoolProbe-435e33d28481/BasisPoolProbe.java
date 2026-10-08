package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BasisPoolProbe {
    static long checks, calls, coldWork, warmWork, restored;
    static BigInteger b(long x) { return BigInteger.valueOf(x); }
    static void check(boolean ok, String detail) { checks++; if (!ok) throw new AssertionError(detail); }
    static PlanningBudget budget(long bytes) { return new PlanningBudget(0, 1_000_000_000L, bytes, () -> false, () -> 0L); }
    static List<ExactLinearProgram.Constraint> rows(int n, int face, int step) {
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        var random = new Random(17117 + face);
        for (int r = 0; r < n; r++) {
            var terms = new TreeMap<Integer, BigInteger>();
            for (int i = 0; i < n; i++) { int a = random.nextInt(11) - 5; if (a != 0) terms.put(i, b(a)); }
            rows.add(new ExactLinearProgram.Constraint(terms, b(2 + (step + r) % 13)));
        }
        for (int i = 0; i < n; i++) rows.add(new ExactLinearProgram.Constraint(Map.of(i, b(1)), b(1 + (step + i) % 7)));
        return rows;
    }
    static BigInteger[] costs(int n, int seed) {
        var random = new Random(seed); var result = new BigInteger[n];
        for (int i = 0; i < n; i++) result[i] = b(random.nextInt(21) - 10);
        return result;
    }
    static void compare(List<ExactLinearProgram.Constraint> rows, BigInteger[] costs,
                        CountNumericRelaxation.Result cold, CountNumericRelaxation.Result warm) {
        calls++; check(cold != null && warm != null, "declined");
        check(cold.phaseOneInfeasible() == warm.phaseOneInfeasible(), "feasibility mismatch");
        coldWork += cold.work(); warmWork += warm.work();
        if (warm.point() == null) return;
        double c = 0, w = 0;
        for (int i = 0; i < costs.length; i++) {
            double x = warm.point()[i]; check(Double.isFinite(x) && x >= -1e-6, "bad coordinate");
            c += costs[i].doubleValue() * cold.point()[i]; w += costs[i].doubleValue() * x;
        }
        check(Math.abs(c-w) <= 1e-6 * (1 + Math.abs(c) + Math.abs(w)), "objective mismatch");
        for (var row : rows) {
            double sum = 0, norm = 1 + Math.abs(row.upper().doubleValue());
            for (var t : row.terms().entrySet()) { double v = t.getValue().doubleValue() * warm.point()[t.getKey()]; sum += v; norm += Math.abs(v); }
            check(sum <= row.upper().doubleValue() + 1e-6 * norm, "row violated");
        }
    }
    static void alternating() {
        for (int n : new int[]{2, 4, 8, 16, 32}) for (int seed = 0; seed < 12; seed++) {
            var budget = budget(128L << 20);
            try (var session = new CountNumericRelaxation.Session()) {
                var cost = costs(n, seed);
                for (int step = 0; step < 100; step++) {
                    var rows = rows(n, step % 2, step);
                    compare(rows, cost, CountNumericRelaxation.solve(n, rows, cost, budget, 1_000_000),
                            session.solve(n, rows, cost, budget, 1_000_000));
                }
                check(session.restored() == 98, "did not retain alternating bases: " + session.restored());
                check(session.rebuilt() == 2, "rebuilt cached face"); restored += session.restored();
            }
            check(budget.reservedBytes() == 0, "alternation leaked");
        }
    }
    static void lifecycle() {
        for (long memory : new long[]{1, 1024, 4096, 16384, 65536, 1L << 20}) {
            var budget = budget(memory);
            try (var session = new CountNumericRelaxation.Session()) {
                for (int step = 0; step < 30; step++) {
                    session.solve(4, rows(4, step % 3, step), costs(4, step % 4), budget, 100_000);
                    check(budget.reservedBytes() >= 0 && budget.reservedBytes() <= memory, "memory cap");
                }
            }
            check(budget.reservedBytes() == 0, "low memory leak");
        }
        var budget = budget(128L << 20);
        try (var session = new CountNumericRelaxation.Session()) {
            for (int step = 0; step < 20; step++) session.solve(8, rows(8, step % 3, step), costs(8, 2), budget, 100_000);
            check(session.restored() == 0 && session.rebuilt() == 20, "LRU eviction or matrix validation");
            var second = budget(128L << 20);
            session.solve(8, rows(8, 0, 0), costs(8, 2), second, 100_000);
            check(budget.reservedBytes() == 0, "old request retained");
            session.close(); check(second.reservedBytes() == 0, "new request leak");
        }
        var cancelled = new AtomicBoolean();
        var stopping = new PlanningBudget(0, 1_000_000, 128L << 20, cancelled::get, () -> 0L);
        try (var session = new CountNumericRelaxation.Session()) {
            session.solve(8, rows(8, 0, 0), costs(8, 2), stopping, 100_000);
            session.solve(8, rows(8, 1, 0), costs(8, 2), stopping, 100_000);
            cancelled.set(true);
            try { session.solve(8, rows(8, 0, 1), costs(8, 2), stopping, 100_000); throw new AssertionError("missed cancellation"); }
            catch (CancellationException expected) { check(stopping.reservedBytes() == 0, "cancel leak"); }
        }
    }
    public static void main(String[] args) { alternating(); lifecycle(); System.out.println("BasisPoolProbe calls="+calls+" restored="+restored+" coldWork="+coldWork+" warmWork="+warmWork+" checks="+checks+" errors=0 leaks=0"); }
}
