// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** A long residue walk must reuse its equation, fixed terms and retained cursor. */
public final class CountEquationResidueTest {
    public static void main(String[] args) {
        BigInteger[] low = new BigInteger[96], high = new BigInteger[96];
        Arrays.fill(low, BigInteger.valueOf(71));
        Arrays.fill(high, BigInteger.valueOf(71));
        for (int i = 0; i < 3; i++) {
            low[i] = BigInteger.ZERO;
            high[i] = BigInteger.valueOf(i == 2 ? 10000 : 50000);
        }
        Map<Integer, BigInteger> equation = new LinkedHashMap<>();
        equation.put(0, BigInteger.TWO);
        equation.put(1, BigInteger.valueOf(99991));
        equation.put(2, BigInteger.valueOf(5));
        BigInteger rhs = BigInteger.valueOf(10062100);
        for (int i = 3; i < low.length; i++) {
            equation.put(i, BigInteger.valueOf(i));
            rhs = rhs.add(BigInteger.valueOf(i).multiply(low[i]));
        }
        Map<Integer, BigInteger> reverse = new LinkedHashMap<>();
        equation.forEach((id, value) -> reverse.put(id, value.negate()));
        var rows = new ArrayList<>(List.of(new ExactLinearProgram.Constraint(equation, rhs),
                new ExactLinearProgram.Constraint(reverse, rhs.negate()),
                new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE, 2, BigInteger.ONE.negate()), BigInteger.ZERO),
                new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE.negate(), 2, BigInteger.ONE), BigInteger.ZERO)));
        // x=z reduces the two balances to 7*z+99991*y=10062100. In the
        // bounded interval 0<=z<=10000 the unique solution is z=9000,y=100.
        BigInteger[] expected = low.clone();
        expected[0] = expected[2] = BigInteger.valueOf(9000);
        expected[1] = BigInteger.valueOf(100);
        long originalWork = 0;
        for (int order = 0; order < 4; order++) {
            if (order > 0) Collections.shuffle(rows, new Random(order));
            long wholeWork = 0;
            for (boolean sliced : new boolean[] {false, true}) {
                var budget = new PlanningBudget(0, 8_000_000, 64L << 20, () -> false, System::nanoTime);
                try (var search = new CountDiophantine(rows, low, high, budget, sliced ? 256 : 131072).retained()) {
                    for (;;) {
                        while (!search.step()) {}
                        if (!search.paused()) break;
                        search.resume(sliced ? 37 : 131072);
                    }
                    if (!Arrays.equals(expected, search.counts())) throw new AssertionError("Missed unique original witness");
                    for (var row : rows) {
                        BigInteger sum = BigInteger.ZERO;
                        for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(search.counts()[term.getKey()]));
                        if (sum.compareTo(row.upper()) > 0) throw new AssertionError("Invalid original balance");
                    }
                }
                if (budget.reservedBytes() != 0) throw new AssertionError("Retained workspace leaked");
                if (budget.nodes() > 80000) throw new AssertionError("Repeated equation work: " + budget.nodes());
                if (!sliced) wholeWork = budget.nodes();
                else if (budget.nodes() != wholeWork) throw new AssertionError("Grant size changed the work");
            }
            if (order == 0) originalWork = wholeWork;
        }
        System.out.println("Equation residue: unique 9000th witness, four row orders and paired grants passed; work=" + originalWork);
    }
}
