// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.*;

/** Integer breakpoints must survive ties between rounded, very large move gains. */
public final class CountJumpBoundaryTest {
    private static final Method RECOMPUTE;
    static {
        try {
            RECOMPUTE = CountJump.class.getDeclaredMethod("recompute", int.class);
            RECOMPUTE.setAccessible(true);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    public static void main(String[] args) throws Exception {
        int cases = 0;
        var random = new Random(107719);
        for (int sample = 0; sample < 160; sample++) {
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            BigInteger minimum = BigInteger.ZERO;
            int degree = sample % 4 == 0 ? 3 : 8 + sample % 60;
            BigInteger scale = BigInteger.TEN.pow(new int[]{3, 30, 100, 400}[sample % 4]);
            for (int r = 0; r < degree; r++) {
                BigInteger coefficient = BigInteger.valueOf(1 + random.nextInt(100));
                BigInteger rhs = scale.add(BigInteger.valueOf(random.nextInt(10000)));
                rows.add(new ExactLinearProgram.Constraint(Map.of(0, coefficient.negate(),
                        1, BigInteger.valueOf(101 + random.nextInt(100))), rhs.negate()));
                minimum = minimum.max(rhs.add(coefficient).subtract(BigInteger.ONE).divide(coefficient));
            }
            for (int mode = 0; mode < 4; mode++) {
                // The same exact boundary through the sweep, allocation-free
                // fallback, finite domains, and a resumable search.
                var budget = budget();
                BigInteger[] low = {BigInteger.ZERO, BigInteger.ZERO};
                BigInteger[] high = {mode == 2 ? minimum : null, BigInteger.ZERO};
                BigInteger[] initial = {sample % 2 == 0 ? BigInteger.ZERO : minimum.divide(BigInteger.TWO), BigInteger.ZERO};
                try (var jump = new CountJump(rows, low, high, budget, mode == 3 ? 1024 : 1_000_000, initial).retained()) {
                    if (mode != 3) {
                        while ((int) field(jump, "initialized") < rows.size())
                            check(!jump.step(), "initialization stopped");
                        long held = mode == 1 ? budget.availableBytes() : 0;
                        if (held != 0) check(budget.tryReserve(held), "memory guard");
                        try {
                            long before = budget.nodes();
                            RECOMPUTE.invoke(jump, 0);
                            var value = ((BigInteger[]) field(jump, "jumps"))[0];
                            check(value != null && value.compareTo(minimum) >= 0,
                                    "rounded gain stopped before boundary: " + value + " < " + minimum);
                            check(high[0] == null || value.compareTo(high[0]) <= 0, "jump exceeded domain");
                            check(budget.nodes() - before >= degree, "jump comparison was not charged");
                        } finally {
                            budget.release(held);
                        }
                    }
                    while (true) {
                        while (!jump.step()) {}
                        if (!jump.paused()) break;
                        jump.resume(1024);
                    }
                    BigInteger[] counts = jump.counts();
                    check(counts != null, "one-coordinate feasible plateau was missed");
                    check(counts[0].compareTo(minimum) >= 0 && counts[1].signum() == 0, "invalid witness");
                    for (var row : rows) check(activity(row, counts).compareTo(row.upper()) <= 0, "row violated");
                }
                check(budget.reservedBytes() == 0, "jump memory leak");
                cases++;
            }
        }
        // A tie breaker remains a candidate heuristic. It cannot round up
        // outside a finite domain to make an impossible system look feasible.
        for (int exponent : new int[]{30, 100, 400}) {
            BigInteger target = BigInteger.TEN.pow(exponent).add(BigInteger.ONE);
            var rows = List.of(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE.negate()), target.negate()));
            var budget = budget();
            try (var jump = new CountJump(rows, new BigInteger[]{BigInteger.ZERO},
                    new BigInteger[]{target.subtract(BigInteger.ONE)}, budget, 4096)) {
                while (!jump.step()) {}
                check(jump.counts() == null, "infeasible finite domain accepted");
            }
            check(budget.reservedBytes() == 0, "finite domain leak");
            cases++;
        }
        System.out.println("Jump boundary oracle: " + cases + " exact boundary/domain cases; sweep, low-memory fallback and continuation passed");
    }

    private static PlanningBudget budget() {
        return new PlanningBudget(0, 8_000_000, 64L << 20, () -> false, () -> 0L);
    }

    private static BigInteger activity(ExactLinearProgram.Constraint row, BigInteger[] point) {
        BigInteger value = BigInteger.ZERO;
        for (var term : row.terms().entrySet()) value = value.add(term.getValue().multiply(point[term.getKey()]));
        return value;
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
