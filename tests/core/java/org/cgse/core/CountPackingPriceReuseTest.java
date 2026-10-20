// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Independent box certificates; sharing products must never share implications. */
public final class CountPackingPriceReuseTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        for (int trial = 0; trial < 72; trial++) certificates(trial);
        for (boolean wide : new boolean[] {false, true}) {
            for (boolean cancel : new boolean[] {false, true}) interrupted(wide, cancel);
        }
        System.out.println("Packing price reuse: " + checks + " exact box/lifecycle checks");
    }

    private static void certificates(int trial) throws Exception {
        var random = new Random(912387L + trial);
        int n = 5 + trial % 5, m = 2 + trial % 3;
        long scale = trial % 3 == 0 ? 1L << 45 : 1;
        long[][] rows = new long[m + 1][n]; long[] bounds = new long[m + 1];
        for (int j = 0; j < n; j++) { rows[m][j] = -(1 + random.nextInt(16)) * scale; bounds[m] += rows[m][j] / 2; }
        for (int r = 0; r < m; r++) for (int j = 0; j < n; j++) {
            rows[r][j] = (1 + random.nextInt(16)) * scale; bounds[r] += rows[r][j] / 2;
        }
        var budget = budget(() -> false);
        var worker = Executors.newSingleThreadExecutor();
        try (var search = CountPackingLpSearch.create(rows, bounds, budget)) {
            check(search != null, "Certificate fixture declined");
            Object frame = ((Object[]) get(search, "frames"))[0];
            Method possible = CountPackingLpSearch.class.getDeclaredMethod("possible", frame.getClass());
            possible.setAccessible(true);
            long[] weights = new long[m];
            for (int round = 0; round < 4; round++) {
                // The last price changes while every preceding coordinate
                // stays equal. Returning to an earlier vector must also rebuild.
                weights[m - 1] = round == 3 ? 0 : round * 65536L;
                prices(search, frame, weights);
                long builds = (long) get(search, "priceBuilds");
                long reused = (long) get(search, "priceReuses");
                for (int box = 0; box < 32; box++) {
                    long fixed = random.nextInt(1 << n), ones = random.nextInt(1 << n) & fixed;
                    set(frame, "fixed", fixed); set(frame, "ones", ones);
                    boolean actual = box % 7 == 0 ?
                            worker.submit(() -> (boolean) possible.invoke(search, frame)).get() :
                            (boolean) possible.invoke(search, frame);
                    long nextFixed = (long) get(frame, "fixed"), nextOnes = (long) get(frame, "ones");
                    var rhs = BigInteger.ZERO;
                    for (int r = 0; r < m; r++)
                        rhs = rhs.add(BigInteger.valueOf(weights[r]).multiply(BigInteger.valueOf(bounds[r])));
                    var reduced = new BigInteger[n];
                    for (int j = 0; j < n; j++) {
                        reduced[j] = BigInteger.valueOf(-rows[m][j]).multiply(BigInteger.valueOf(65536));
                        for (int r = 0; r < m; r++)
                            reduced[j] = reduced[j].subtract(BigInteger.valueOf(weights[r]).multiply(BigInteger.valueOf(rows[r][j])));
                    }
                    // Exhaust the current Boolean box to derive its certificate
                    // maximum independently of the cached positive-part formula.
                    BigInteger maximum = null;
                    for (long mask = 0; mask < 1L << n; mask++) if ((mask & fixed) == ones) {
                        var value = rhs;
                        for (int j = 0; j < n; j++) if ((mask & (1L << j)) != 0) value = value.add(reduced[j]);
                        if (maximum == null || value.compareTo(maximum) > 0) maximum = value;
                    }
                    var gap = maximum.add(BigInteger.valueOf(bounds[m]).multiply(BigInteger.valueOf(65536)));
                    check(actual == (gap.signum() >= 0), "Reused another box's bound");
                    long expectedFixed = fixed, expectedOnes = ones;
                    if (actual) for (int j = 0; j < n; j++) if ((fixed & (1L << j)) == 0) {
                        if (reduced[j].compareTo(gap) > 0) { expectedFixed |= 1L << j; expectedOnes |= 1L << j; }
                        else if (reduced[j].compareTo(gap.negate()) < 0) expectedFixed |= 1L << j;
                    }
                    check(nextFixed == expectedFixed && nextOnes == expectedOnes, "Reused stale fixed literals");
                    check((long) get(search, "priceBuilds") == builds + 1, "Incomplete key or repeated certificate construction");
                    check((long) get(search, "priceReuses") == reused + box, "Identical prices were not reused");
                    for (long mask = 0; mask < 1L << n; mask++) if ((mask & fixed) == ones && fits(rows, bounds, mask))
                        check(actual && (mask & nextFixed) == nextOnes, "Certificate removed a feasible original assignment");
                }
            }
            check(((int) get(search, "certificate") == 2) == (scale > 1), "Wrong cached arithmetic width");
            // Non-finite proposed prices normalize to zero, including after a
            // fully built nonzero certificate. No former weight may survive.
            var cost = (double[]) get(frame, "cost");
            for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                weights[m - 1] = 65536; prices(search, frame, weights);
                possible.invoke(search, frame);
                cost[n + m - 1] = value;
                set(frame, "fixed", 0L); set(frame, "ones", 0L);
                possible.invoke(search, frame);
                check(Arrays.stream((long[]) get(search, "priceWeights")).allMatch(x -> x == 0), "Bad price retained old products");
            }
        } finally { worker.shutdownNow(); }
        check(budget.reservedBytes() == 0, "Certificate cache leaked");
    }

    private static void interrupted(boolean wide, boolean cancel) throws Exception {
        int n = 32, m = 4; long scale = wide ? 1L << 45 : 1;
        long[][] rows = new long[m + 1][n]; long[] bounds = new long[m + 1];
        for (int r = 0; r <= m; r++) { Arrays.fill(rows[r], r == m ? -scale : scale); bounds[r] = (r == m ? -1 : 1) * n * scale / 2; }
        var calls = new AtomicInteger(Integer.MIN_VALUE);
        var budget = budget(() -> calls.incrementAndGet() > 8);
        try (var search = CountPackingLpSearch.create(rows, bounds, budget)) {
            Object frame = ((Object[]) get(search, "frames"))[0];
            Method possible = CountPackingLpSearch.class.getDeclaredMethod("possible", frame.getClass());
            possible.setAccessible(true);
            long[] weights = {0, 0, 0, 0}; prices(search, frame, weights);
            possible.invoke(search, frame);
            check((int) get(search, "certificate") != 0, "Missing first certificate");
            weights[m - 1] = 65536; prices(search, frame, weights);
            if (cancel) calls.set(0);
            else budget.charge(budget.remainingWork() - 8);
            try {
                possible.invoke(search, frame);
                throw new AssertionError("Certificate rebuild ignored interruption");
            } catch (InvocationTargetException failure) {
                check(cancel ? failure.getCause() instanceof CancellationException :
                        failure.getCause() instanceof PlanningBudget.Exhausted, "Wrong interruption");
            }
            check((int) get(search, "certificate") == 0, "Published incomplete certificate");
            check(!search.feasible(), "Interrupted certificate became a witness");
        }
        check(budget.reservedBytes() == 0, "Interrupted certificate leaked");
    }

    private static void prices(Object search, Object frame, long[] weights) throws Exception {
        double[] scales = (double[]) get(search, "scales"), cost = (double[]) get(frame, "cost");
        int n = (int) get(search, "n"); double objective = (double) get(search, "objectiveScale");
        for (int r = 0; r < weights.length; r++) cost[n + r] = -(weights[r] / 65536.0) * scales[r] / objective;
    }
    private static boolean fits(long[][] rows, long[] bounds, long mask) {
        for (int r = 0; r < rows.length; r++) {
            var sum = BigInteger.ZERO;
            for (int j = 0; j < rows[r].length; j++) if ((mask & (1L << j)) != 0) sum = sum.add(BigInteger.valueOf(rows[r][j]));
            if (sum.compareTo(BigInteger.valueOf(bounds[r])) > 0) return false;
        }
        return true;
    }
    private static PlanningBudget budget(java.util.function.BooleanSupplier cancelled) {
        return new PlanningBudget(0, 40_000_000, 64L << 20, cancelled, System::nanoTime);
    }
    private static Field field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Object get(Object object, String name) throws Exception { return field(object, name).get(object); }
    private static void set(Object object, String name, Object value) throws Exception { field(object, name).set(object, value); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
