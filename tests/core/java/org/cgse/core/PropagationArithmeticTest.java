// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Exact wide boundaries, signed cancellation and explanations survive arithmetic shortcuts. */
public final class PropagationArithmeticTest {
    private static final BigInteger[] VALUES = {BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE.negate(), BigInteger.TWO,
            BigInteger.valueOf(Long.MIN_VALUE), BigInteger.valueOf(Long.MAX_VALUE),
            BigInteger.ONE.shiftLeft(63), BigInteger.ONE.shiftLeft(129).negate()};

    public static void main(String[] args) throws Exception {
        var addProduct = CountBounds.class.getDeclaredMethod("addProduct", BigInteger.class, BigInteger.class, BigInteger.class);
        addProduct.setAccessible(true);
        for (BigInteger sum : VALUES) for (BigInteger a : VALUES) for (BigInteger b : VALUES)
            check(sum.add(a.multiply(b)).equals(addProduct.invoke(null, sum, a, b)), "multiply-add overflow");
        var residual = CountBounds.class.getDeclaredMethod("residual", BigInteger.class, BigInteger.class, BigInteger.class, BigInteger.class);
        residual.setAccessible(true);
        for (BigInteger limit : VALUES) for (BigInteger finite : VALUES) for (BigInteger coefficient : VALUES) for (BigInteger bound : VALUES)
            check(limit.subtract(finite.subtract(coefficient.multiply(bound))).equals(residual.invoke(null, limit, finite, coefficient, bound)),
                    "residual overflow or cancellation");
        var quotient = CountBounds.class.getDeclaredMethod("lowerQuotient", BigInteger.class, BigInteger.class);
        quotient.setAccessible(true);
        for (BigInteger limit : VALUES) for (BigInteger coefficient : VALUES) if (coefficient.signum() < 0)
            check(CheckedAmounts.ceilDiv(limit.negate(), coefficient.negate()).equals(quotient.invoke(null, limit, coefficient)),
                    "negative quotient changed");
        for (BigInteger a : VALUES) for (BigInteger b : VALUES) if (b.signum() > 0) {
            BigInteger expected = a.signum() <= 0 ? BigInteger.ZERO : a.subtract(BigInteger.ONE).divide(b).add(BigInteger.ONE);
            check(CheckedAmounts.ceilDiv(a, b).equals(expected), "ceil division changed");
        }
        Random random = new Random(20261011);
        for (int trial = 0; trial < 600; trial++) {
            int n = 2 + random.nextInt(7);
            BigInteger[] witness = new BigInteger[n];
            for (int i = 0; i < n; i++) witness[i] = VALUES[random.nextInt(VALUES.length)].abs();
            List<ExactLinearProgram.Constraint> rows = new ArrayList<>();
            for (int r = 0; r < 12; r++) {
                Map<Integer, BigInteger> terms = new LinkedHashMap<>();
                BigInteger rhs = BigInteger.valueOf(random.nextInt(3));
                for (int i = 0; i < n; i++) {
                    BigInteger coefficient = VALUES[random.nextInt(VALUES.length)];
                    if (coefficient.signum() != 0) terms.put(i, coefficient);
                    rhs = rhs.add(coefficient.multiply(witness[i]));
                }
                rows.add(new ExactLinearProgram.Constraint(terms, rhs));
            }
            // Exact fixing exercises residue tightening and branch reasons,
            // including products much wider than the returned variable bound.
            for (int i = 0; i < n; i++) {
                rows.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), witness[i]));
                rows.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE.negate()), witness[i].negate()));
            }
            var budget = new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime);
            try (var bounds = new CountBounds(n, rows, budget, 12)) {
                while (!bounds.step()) {}
                check(!bounds.blocked(), "valid wide witness rejected: " + trial);
                check(Arrays.equals(witness, bounds.lowerBounds()) && Arrays.equals(witness, bounds.upperBounds()),
                        "fixed witness changed: " + trial);
            }
            check(budget.reservedBytes() == 0, "propagation memory leaked");
        }
        System.out.println("Propagation arithmetic: exact products, signed ceilings and 600 wide fixed witnesses passed");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
