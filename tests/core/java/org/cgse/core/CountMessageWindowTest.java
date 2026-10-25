// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.*;

/** Independent tuples and untruncated dense sums check target-window messages. */
public final class CountMessageWindowTest {
    private static long tupleChecks, messageChecks, laterSweeps, nonuniformCavities, zeroWidthTerms;
    private static int checks;

    private record Factor(int[] ids, int[] coefficients, int rhs, int range) {}

    private static Object field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }

    private static void check(boolean condition, String detail) { checks++; if (!condition) throw new AssertionError(detail); }

    private static void normalize(double[] values) {
        double sum = 0; for (double v : values) sum += v;
        if (sum == 0 || !Double.isFinite(sum)) { Arrays.fill(values, 1.0 / values.length); return; }
        for (int i = 0; i < values.length; i++) values[i] /= sum;
    }

    /** Full polynomial convolution, with no target windows or clipped reads. */
    private static double[][] dense(Factor f, int[] widths, int[] masks, double[][] incoming, double[][] previous) {
        int n = f.ids.length; int[] spans = new int[n + 1];
        double[][] prefix = new double[n + 1][];
        prefix[0] = new double[]{1};
        for (int k = 0; k < n; k++) {
            int i = f.ids[k], a = Math.abs(f.coefficients[k]);
            spans[k + 1] = spans[k] + a * widths[i];
            prefix[k + 1] = new double[spans[k + 1] + 1];
            for (int v = 0; v <= widths[i]; v++) if ((masks[i] & (1 << v)) != 0) {
                int shift = a * (f.coefficients[k] > 0 ? v : widths[i] - v);
                for (int s = 0; s < prefix[k].length; s++) prefix[k + 1][s + shift] += prefix[k][s] * incoming[k][v];
            }
        }
        double[][] result = new double[n][]; double[] adjoint = new double[f.range + 1]; adjoint[f.rhs] = 1;
        for (int k = n - 1; k >= 0; k--) {
            int i = f.ids[k]; double[] next = new double[spans[k] + 1]; result[k] = new double[widths[i] + 1];
            for (int v = 0; v <= widths[i]; v++) if ((masks[i] & (1 << v)) != 0) {
                int shift = Math.abs(f.coefficients[k]) * (f.coefficients[k] > 0 ? v : widths[i] - v);
                for (int s = 0; s < next.length; s++) {
                    result[k][v] += prefix[k][s] * adjoint[s + shift]; next[s] += incoming[k][v] * adjoint[s + shift];
                }
            }
            normalize(result[k]);
            for (int v = 0; v < result[k].length; v++) result[k][v] = .35 * previous[k][v] + .65 * result[k][v];
            adjoint = next;
        }
        return result;
    }

    /** Enumerates all currently allowed tuples; unrelated to polynomial DP. */
    private static void tuples(Factor f, int[] widths, int[] masks, double[][] incoming,
                               int at, int sum, int[] values, double[][] counts) {
        if (at == f.ids.length) {
            tupleChecks++;
            if (sum == f.rhs) for (int k = 0; k < values.length; k++) {
                // A factor-to-variable message excludes that variable's own
                // cavity weight. Explicit products also handle zero weights.
                double weight = 1;
                for (int other = 0; other < values.length; other++)
                    if (other != k) weight *= incoming[other][values[other]];
                counts[k][values[k]] += weight;
            }
            return;
        }
        int i = f.ids[at];
        for (int v = 0; v <= widths[i]; v++) if ((masks[i] & (1 << v)) != 0) {
            values[at] = v;
            tuples(f, widths, masks, incoming, at + 1, sum + Math.abs(f.coefficients[at]) *
                    (f.coefficients[at] > 0 ? v : widths[i] - v), values, counts);
        }
    }

    private static void run(int seed) throws Exception {
        Random random = new Random(135790L + seed); int n = 2 + seed % 9;
        BigInteger[] low = new BigInteger[n], high = new BigInteger[n], planted = new BigInteger[n];
        for (int i = 0; i < n; i++) {
            low[i] = BigInteger.valueOf(random.nextInt(7) - 3);
            if (seed % 11 == 0) low[i] = low[i].add(BigInteger.ONE.shiftLeft(180).multiply(BigInteger.valueOf(i % 2 == 0 ? 1 : -1)));
            int width = i == seed % n ? 15 : seed % 3 == 0 && i == (seed + 1) % n ? 0 : 1;
            high[i] = low[i].add(BigInteger.valueOf(width));
            int value = seed % 5 == 0 ? 0 : seed % 5 == 1 ? width : random.nextInt(width + 1);
            planted[i] = low[i].add(BigInteger.valueOf(value));
        }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        BigInteger scale = seed % 7 == 0 ? BigInteger.ONE.shiftLeft(160).add(BigInteger.ONE) : BigInteger.ONE;
        for (int r = 0; r < 1 + seed % 2; r++) {
            var coefficients = new LinkedHashMap<Integer, BigInteger>(); BigInteger rhs = BigInteger.ZERO;
            for (int i = 0; i < n; i++) {
                BigInteger a = scale.multiply(BigInteger.valueOf((2 + random.nextInt(12)) * (random.nextBoolean() ? 1 : -1)));
                coefficients.put(i, a); rhs = rhs.add(a.multiply(planted[i]));
            }
            rows.add(new ExactLinearProgram.Constraint(coefficients, rhs));
            var reverse = new LinkedHashMap<Integer, BigInteger>(); coefficients.forEach((i, a) -> reverse.put(i, a.negate()));
            rows.add(new ExactLinearProgram.Constraint(reverse, rhs.negate()));
        }
        var budget = new PlanningBudget(0, 60_000_000, 64L << 20, () -> false, System::nanoTime);
        try (var search = CountMessageSearch.create(rows, low, high, budget, 1)) {
            check(search != null, "Missing finite equality candidate");
            while ((int) field(search, "phase") != 2) {
                if (search.paused()) search.resume(1);
                check(!search.step() || search.paused(), "Lost constructive input before first factor");
            }
            for (int sweep = 0; sweep < 3; sweep++) {
                int[] widths = ((int[]) field(search, "widths")).clone(), masks = ((int[]) field(search, "masks")).clone();
                Object[] raw = (Object[]) field(search, "factors");
                var previous = new ArrayList<double[][]>(); var cavities = new ArrayList<double[][]>();
                for (Object object : raw) {
                    double[][] messages = (double[][]) field(object, "messages");
                    previous.add(Arrays.stream(messages).map(double[]::clone).toArray(double[][]::new));
                    cavities.add(new double[messages.length][]);
                }
                long before = search.progress();
                while (search.progress() == before) {
                    // Capture the real cavity inputs before convolution. The
                    // reference never reconstructs logs or message collection.
                    if ((int) field(search, "phase") == 2 && (int) field(search, "factorPart") == 2 &&
                            (int) field(search, "value") == 0) {
                        int row = (int) field(search, "cursor"), term = (int) field(search, "term");
                        int id = ((int[]) field(raw[row], "ids"))[term];
                        double[] incoming = ((double[][]) field(search, "incoming"))[term];
                        cavities.get(row)[term] = Arrays.copyOf(incoming, widths[id] + 1);
                    }
                    if (search.paused()) search.resume(seed % 2 == 0 ? 1 : 32768);
                    check(!search.step() || search.paused(), "Lost requested message sweep");
                }
                if (sweep > 0) laterSweeps++;
                for (int r = 0; r < raw.length; r++) {
                    var factor = new Factor((int[]) field(raw[r], "ids"), (int[]) field(raw[r], "coefficient"),
                            (int) field(raw[r], "rhs"), (int) field(raw[r], "range"));
                    for (int k = 0; k < factor.ids.length; k++) {
                        double[] cavity = cavities.get(r)[k]; check(cavity != null, "Missing captured cavity input");
                        int i = factor.ids[k]; if (widths[i] == 0) zeroWidthTerms++;
                        double first = -1;
                        for (int v = 0; v < cavity.length; v++) if ((masks[i] & (1 << v)) != 0) {
                            if (first < 0) first = cavity[v];
                            else if (Math.abs(first - cavity[v]) > 1e-12) { nonuniformCavities++; break; }
                        }
                    }
                    double[][] expected = dense(factor, widths, masks, cavities.get(r), previous.get(r));
                    double[][] counts = new double[factor.ids.length][];
                    for (int k = 0; k < counts.length; k++) counts[k] = new double[widths[factor.ids[k]] + 1];
                    tuples(factor, widths, masks, cavities.get(r), 0, 0, new int[counts.length], counts);
                    double[][] actual = (double[][]) field(raw[r], "messages");
                    for (int k = 0; k < actual.length; k++) {
                        double count = Arrays.stream(counts[k]).sum(); check(count > 0, "No local tuple for constructive equations");
                        for (int v = 0; v < actual[k].length; v++) {
                            messageChecks++;
                            check(Double.doubleToLongBits(actual[k][v]) == Double.doubleToLongBits(expected[k][v]),
                                    "Clipping changed dense floating result: seed=" + seed + "; sweep=" + sweep + "; row=" + r + "; term=" + k + "; value=" + v);
                            double enumerated = .35 * previous.get(r)[k][v] + .65 * counts[k][v] / count;
                            check(Math.abs(actual[k][v] - enumerated) < 1e-13, "Message disagrees with independent weighted tuples");
                        }
                    }
                }
                if ((int) field(search, "phase") == 5) break; // Candidate verification follows; no further sweep.
                while ((int) field(search, "phase") != 2) {
                    if (search.paused()) search.resume(1);
                    check(!search.step() || search.paused(), "Lost continuation before next factor");
                }
            }
        }
        check(budget.reservedBytes() == 0, "Paused message workspace leaked");
    }

    public static void main(String[] args) throws Exception {
        for (int seed = 0; seed < 192; seed++) run(seed);
        check(tupleChecks > 1000 && messageChecks > 1000, "Insufficient complete tuple coverage");
        check(zeroWidthTerms > 0 && laterSweeps > 0 && nonuniformCavities > 0,
                "Missing zero-width or later nonuniform-message coverage");
        System.out.println("Message target windows: " + checks + " checks, " + tupleChecks + " complete tuples, " + messageChecks +
                " bitwise dense-message comparisons, " + zeroWidthTerms + " zero-width terms, " + laterSweeps +
                " later sweeps, " + nonuniformCavities + " nonuniform cavities; weighted tuple oracle passed");
    }
}
