// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Scalar tableau oracle, including the state left by an interrupted pivot. */
public final class NumericPivotBatchTest {
    private static final Map<String, Field> FIELDS = new HashMap<>();
    private static final Constructor<CountNumericRelaxation> CONSTRUCTOR;
    private static final Method PIVOT, FLUSH;
    static {
        try {
            CONSTRUCTOR = CountNumericRelaxation.class.getDeclaredConstructor(int.class, int.class, PlanningBudget.class, long.class);
            CONSTRUCTOR.setAccessible(true);
            PIVOT = CountNumericRelaxation.class.getDeclaredMethod("pivot", int.class, int.class);
            FLUSH = CountNumericRelaxation.class.getDeclaredMethod("flush");
            PIVOT.setAccessible(true); FLUSH.setAccessible(true);
            for (String name : List.of("tableau", "basic", "nonbasic", "pivotColumns", "operations", "charged", "pivots", "atomicPivot", "retainedCold")) {
                var field = CountNumericRelaxation.class.getDeclaredField(name);
                field.setAccessible(true); FIELDS.put(name, field);
            }
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }

    public static void main(String[] args) throws Exception {
        var random = new Random(2026101407L);
        var outcomes = new HashMap<String, Integer>();
        for (int sample = 0; sample < 4096; sample++) {
            int n = 1 + random.nextInt(sample % 8 == 0 ? 260 : 64), m = 1 + random.nextInt(12);
            long initial = sample % 1024, charged = initial / 256 * 16;
            long maximum = sample % 4 == 0 ? 1 + random.nextInt(48) : 1_000_000;
            long total = charged + (sample % 7 == 0 ? 1 + random.nextInt(64) : 1_000_000);
            int cancel = sample % 11 == 0 ? 1 + random.nextInt(6) : Integer.MAX_VALUE;
            var actualChecks = new AtomicInteger(); var expectedChecks = new AtomicInteger();
            var actualBudget = budget(total, actualChecks, cancel);
            var expectedBudget = budget(total, expectedChecks, cancel);
            // Restore an already-started tableau without consuming the test's
            // cancellation probes during setup.
            actualChecks.set(Integer.MIN_VALUE); expectedChecks.set(Integer.MIN_VALUE);
            actualBudget.charge(charged); expectedBudget.charge(charged);
            actualChecks.set(0); expectedChecks.set(0);
            var actual = CONSTRUCTOR.newInstance(n, m, actualBudget, maximum);
            var scalar = new Scalar(n, m, expectedBudget, maximum);
            var table = (double[][]) get(actual, "tableau");
            for (int r = 0; r < m + 2; r++) for (int c = 0; c < n + 2; c++) {
                double value = (random.nextInt(33) - 16) * Math.scalb(1.0, random.nextInt(9) - 4);
                if (sample % 3 == 0 && random.nextInt(3) != 0) value = 0.0;
                if (sample % 13 == 0 && random.nextInt(7) == 0) value = -0.0;
                if (sample % 19 == 0 && random.nextInt(9) == 0) value = Double.MIN_VALUE;
                if (sample % 23 == 0 && random.nextInt(9) == 0) value = Double.MAX_VALUE;
                table[r][c] = scalar.table[r][c] = value;
            }
            for (int r = 0; r < m; r++) ((int[]) get(actual, "basic"))[r] = scalar.basic[r] = n + r;
            for (int c = 0; c <= n; c++) ((int[]) get(actual, "nonbasic"))[c] = scalar.nonbasic[c] = c;
            int row = random.nextInt(m), column = random.nextInt(n + 1);
            double divisor = sample % 17 == 0
                    ? new double[]{0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY, 1e-16}[sample % 5]
                    : random.nextBoolean() ? 2.0 : -0.25;
            table[row][column] = scalar.table[row][column] = divisor;
            set(actual, "operations", initial); scalar.operations = initial;
            set(actual, "charged", charged); scalar.charged = charged;
            set(actual, "atomicPivot", sample % 5 == 0); scalar.atomic = sample % 5 == 0;
            set(actual, "retainedCold", sample % 2 == 0); scalar.retained = sample % 2 == 0;
            for (int pivot = 0; pivot < 2; pivot++) {
                final int r = row, c = column;
                String a = failure(() -> PIVOT.invoke(actual, r, c));
                String b = failure(() -> scalar.pivot(r, c));
                check(a.equals(b), "Different exit at " + sample + ": " + a + " / " + b);
                outcomes.merge(a, 1, Integer::sum);
                compare(actual, scalar, actualBudget, actualChecks, expectedChecks, sample);
                if (!a.equals("OK")) break;
                row = random.nextInt(m); column = random.nextInt(n + 1);
            }
            String a = failure(() -> FLUSH.invoke(actual)), b = failure(scalar::flush);
            check(a.equals(b), "Final flush differs");
            compare(actual, scalar, actualBudget, actualChecks, expectedChecks, sample);
            check(actualBudget.reservedBytes() == 0 && expectedBudget.reservedBytes() == 0, "Unexpected retained workspace");
        }
        for (String required : List.of("OK", "Stop", "Pause", "Exhausted", "CancellationException"))
            check(outcomes.getOrDefault(required, 0) > 10, "Missing exit coverage: " + required);
        System.out.println("Numeric pivot batching: 4096 scalar comparisons, raw bits, interruption state, work and cancellation passed: " + outcomes);
    }

    private static void compare(Object actual, Scalar scalar, PlanningBudget budget, AtomicInteger checks,
                                AtomicInteger expectedChecks, int sample) throws Exception {
        var table = (double[][]) get(actual, "tableau");
        for (int r = 0; r < table.length; r++) for (int c = 0; c < table[r].length; c++)
            check(Double.doubleToRawLongBits(table[r][c]) == Double.doubleToRawLongBits(scalar.table[r][c]),
                    "Tableau changed at " + sample + ": " + r + "," + c);
        check(Arrays.equals((int[]) get(actual, "basic"), scalar.basic), "Basis changed");
        check(Arrays.equals((int[]) get(actual, "nonbasic"), scalar.nonbasic), "Nonbasis changed");
        check(Arrays.equals((int[]) get(actual, "pivotColumns"), scalar.columns), "Sparse pivot support changed");
        check((long) get(actual, "operations") == scalar.operations && (long) get(actual, "charged") == scalar.charged,
                "Scalar accounting changed at " + sample);
        check((int) get(actual, "pivots") == scalar.pivots, "Pivot count changed");
        check(budget.nodes() == scalar.budget.nodes() && checks.get() == expectedChecks.get(), "Budget/checkpoints changed");
    }

    private static PlanningBudget budget(long units, AtomicInteger checks, int cancel) {
        return new PlanningBudget(0, units, 16L << 20, () -> checks.incrementAndGet() >= cancel, System::nanoTime);
    }
    private static Object get(Object target, String name) throws IllegalAccessException { return FIELDS.get(name).get(target); }
    private static void set(Object target, String name, Object value) throws IllegalAccessException { FIELDS.get(name).set(target, value); }
    private interface Action { void run() throws Exception; }
    private static String failure(Action action) throws Exception {
        try { action.run(); return "OK"; }
        catch (InvocationTargetException e) { return e.getCause().getClass().getSimpleName(); }
        catch (RuntimeException e) { return e.getClass().getSimpleName(); }
    }
    private static final class Stop extends RuntimeException {}
    private static final class Pause extends RuntimeException {}

    /** Deliberately scalar: one operation before each original arithmetic cell. */
    private static final class Scalar {
        final int n, m;
        final double[][] table;
        final int[] basic, nonbasic, columns;
        final PlanningBudget budget;
        final long maximum;
        long operations, charged;
        int pivots;
        boolean atomic, retained;
        Scalar(int n, int m, PlanningBudget budget, long maximum) {
            this.n = n; this.m = m; this.budget = budget; this.maximum = maximum;
            table = new double[m + 2][n + 2]; basic = new int[m]; nonbasic = new int[n + 1]; columns = new int[n + 2];
        }
        void pivot(int row, int column) {
            double pivot = finite(table[row][column]);
            if (Math.abs(pivot) < 1e-15) throw new Stop();
            pivots++;
            int size = 0;
            for (int c = 0; c < n + 2; c++) {
                operation();
                if (c != column && table[row][c] != 0) columns[size++] = c;
            }
            for (int r = 0; r < m + 2; r++) {
                operation();
                if (r == row || table[r][column] == 0) continue;
                double factor = finite(table[r][column] / pivot);
                for (int i = 0; i < size; i++) {
                    operation();
                    int c = columns[i];
                    table[r][c] = finite(table[r][c] - table[row][c] * factor);
                }
            }
            for (int c = 0; c < n + 2; c++) if (c != column) { operation(); table[row][c] = finite(table[row][c] / pivot); }
            for (int r = 0; r < m + 2; r++) if (r != row) { operation(); table[r][column] = finite(table[r][column] / -pivot); }
            table[row][column] = 1 / pivot;
            int old = basic[row]; basic[row] = nonbasic[column]; nonbasic[column] = old;
        }
        void operation() {
            operations++;
            if ((operations & 255) == 0) {
                flush();
                if (charged >= maximum && !atomic) { if (retained) throw new Pause(); throw new Stop(); }
            }
        }
        void flush() {
            long next = (operations + 15) / 16;
            if (next > charged) { long delta = next - charged; charged = next; budget.charge(delta); }
            else budget.checkpoint();
        }
        static double finite(double value) { if (!Double.isFinite(value)) throw new Stop(); return value; }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
