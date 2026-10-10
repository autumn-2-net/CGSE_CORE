// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Independent vector Gram-Schmidt oracle for integral factors and all row operations. */
public final class CountIntegralGramTest {
    private static final ExactRational HALF = new ExactRational(BigInteger.ONE, BigInteger.TWO);
    private static final ExactRational DELTA = new ExactRational(BigInteger.valueOf(99), BigInteger.valueOf(100));

    public static void main(String[] args) throws Exception {
        int matrices = 0, updates = 0;
        for (int seed = 0; seed < 96; seed++) {
            Random random = new Random(2026101101L + seed);
            int d = 2 + seed % 7, n = d + seed % 9;
            List<BigInteger[]> basis = new ArrayList<>();
            for (int j = 0; j < d; j++) {
                var row = new BigInteger[n];
                for (int i = 0; i < n; i++) {
                    row[i] = BigInteger.valueOf(i < j ? 0 : i == j ? 1 + random.nextInt(4) : random.nextInt(9) - 4);
                    if (seed % 7 == 0 && i % 3 == 0) row[i] = row[i].shiftLeft(31);
                }
                basis.add(row);
            }
            Collections.shuffle(basis, random);
            AtomicLong work = new AtomicLong();
            var factors = new CountIntegralGram(basis, work::incrementAndGet);
            while (!factors.step()) {}
            check(!factors.dependent() && factors.completedRows() == d && work.get() > 0, "Factor completion/accounting");
            long finished = work.get();
            check(factors.step() && work.get() == finished, "Completed factors did more work");
            verify(basis, factors); matrices++;
            for (int operation = 0; operation < 36; operation++) {
                int k = 1 + random.nextInt(d - 1), j = random.nextInt(k);
                if (operation % 3 == 0) {
                    factors.swap(k);
                    Collections.swap(basis, k, k - 1);
                } else if (operation % 3 == 1) {
                    BigInteger q = BigInteger.valueOf(random.nextInt(7) - 3);
                    factors.add(k, j, q);
                    for (int i = 0; i < n; i++) basis.get(k)[i] = basis.get(k)[i].add(q.multiply(basis.get(j)[i]));
                } else {
                    factors.negate(k);
                    for (int i = 0; i < n; i++) basis.get(k)[i] = basis.get(k)[i].negate();
                }
                verify(basis, factors); updates++;
            }
        }
        boundaries();
        handoff();
        System.out.println("Integral Gram: " + matrices + " independent vector matrices and " + updates +
                " exact swap/add/negation updates; rounding, rank, precision, charging and worker handoff passed");
    }

    private static void verify(List<BigInteger[]> basis, CountIntegralGram actual) {
        int d = basis.size(), n = basis.get(0).length;
        var orthogonal = new ExactRational[d][n];
        var norms = new ExactRational[d];
        for (int i = 0; i < d; i++) {
            for (int k = 0; k < n; k++) orthogonal[i][k] = ExactRational.of(basis.get(i)[k]);
            for (int j = 0; j < i; j++) {
                ExactRational dot = ExactRational.ZERO;
                for (int k = 0; k < n; k++) dot = dot.add(ExactRational.of(basis.get(i)[k]).multiply(orthogonal[j][k]));
                var mu = dot.divide(norms[j]);
                check(mu.equals(actual.mu(i, j)), "Incorrect projection");
                check(actual.nearest(i, j).equals(mu.add(HALF).floor()), "Rounding changed direction/tie");
                check(actual.needsReduction(i, j) == ((mu.signum() < 0 ? mu.negate() : mu).compareTo(HALF) > 0), "Size reduction decision");
                for (int k = 0; k < n; k++) orthogonal[i][k] = orthogonal[i][k].subtract(mu.multiply(orthogonal[j][k]));
            }
            ExactRational norm = ExactRational.ZERO;
            for (int k = 0; k < n; k++) norm = norm.add(orthogonal[i][k].multiply(orthogonal[i][k]));
            check(norm.equals(actual.norm(i)), "Incorrect squared length");
            norms[i] = norm;
            if (i > 0) {
                var mu = actual.mu(i, i - 1);
                boolean expected = norm.compareTo(DELTA.subtract(mu.multiply(mu)).multiply(norms[i - 1])) >= 0;
                check(actual.lovasz(i, 99, 100) == expected, "Incorrect Lovasz decision");
            }
        }
    }

    private static void boundaries() {
        for (long numerator = -9; numerator <= 9; numerator++) {
            var basis = List.of(new BigInteger[] {BigInteger.TWO, BigInteger.ZERO},
                    new BigInteger[] {BigInteger.valueOf(numerator), BigInteger.ONE});
            var factors = new CountIntegralGram(basis, () -> {});
            while (!factors.step()) {}
            verify(basis, factors);
        }
        BigInteger large = BigInteger.ONE.shiftLeft(400);
        var close = List.of(new BigInteger[] {large, BigInteger.ONE, BigInteger.ZERO},
                new BigInteger[] {large.add(BigInteger.ONE), BigInteger.ONE, BigInteger.ONE});
        var factors = new CountIntegralGram(close, () -> {});
        while (!factors.step()) {}
        verify(close, factors);
        for (var basis : List.of(List.<BigInteger[]>of(new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO}),
                List.of(new BigInteger[] {BigInteger.ONE, BigInteger.TWO}, new BigInteger[] {BigInteger.TWO, BigInteger.valueOf(4)}))) {
            var dependent = new CountIntegralGram(basis, () -> {});
            while (!dependent.step()) {}
            check(dependent.dependent(), "Dependent basis accepted");
        }
        var empty = new CountIntegralGram(List.of(), () -> { throw new AssertionError("Empty work"); });
        check(empty.step() && !empty.dependent(), "Empty basis");
        var huge = new CountIntegralGram(List.<BigInteger[]>of(new BigInteger[] {BigInteger.ONE.shiftLeft(1100)}), () -> {});
        try { while (!huge.step()) {} throw new AssertionError("Missing precision refusal"); }
        catch (ExactRational.PrecisionLimit expected) {}
        var cancelled = new CountIntegralGram(close, () -> { throw new CancellationException(); });
        try { cancelled.step(); throw new AssertionError("Cancellation lost"); }
        catch (CancellationException expected) {}
    }

    private static void handoff() throws Exception {
        var basis = new ArrayList<BigInteger[]>();
        basis.add(new BigInteger[] {BigInteger.ONE, BigInteger.TWO, BigInteger.ZERO});
        basis.add(new BigInteger[] {BigInteger.TWO, BigInteger.ZERO, BigInteger.ONE});
        var work = new AtomicLong();
        var factors = new CountIntegralGram(basis, work::incrementAndGet);
        var pool = Executors.newFixedThreadPool(2);
        try {
            while (!pool.submit(factors::step).get(10, TimeUnit.SECONDS)) {}
            pool.submit(() -> { factors.swap(1); Collections.swap(basis, 0, 1); }).get(10, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        verify(basis, factors);
        check(work.get() > 0, "No numeric work charged");
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
