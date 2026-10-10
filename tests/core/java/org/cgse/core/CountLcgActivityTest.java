// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.*;
import java.math.BigInteger;
import java.util.*;

/** Cached row sums against independent original-row recomputation, including undo and promotion. */
public final class CountLcgActivityTest {
    private static final BigInteger MIN = BigInteger.valueOf(Long.MIN_VALUE), MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final Method ACTIVITY = method("activity", int.class), INVALIDATE = method("invalidateRow", int.class),
            UPDATE = method("updateEndpoint", int.class, boolean.class, BigInteger.class, BigInteger.class);
    private static final Field LOW = field(CountLcg.class, "low"), HIGH = field(CountLcg.class, "high");
    private static int checked, primitive, arbitrary, rebuilds, promotions;

    public static void main(String[] args) throws Exception {
        overflowAndUndo();
        randomTransitions();
        require(primitive > 1000 && arbitrary > 1000 && promotions >= 3 && rebuilds > 100,
                "missing sum-state coverage");
        System.out.println("LCG activities: " + checked + " original-row sums; primitive=" + primitive +
                ", arbitrary=" + arbitrary + ", promotions=" + promotions + ", rebuilds=" + rebuilds);
    }

    private static void overflowAndUndo() throws Exception {
        // Both operands fit, but the accumulated result does not. Undo must
        // restore the exact old total; a sleeping-row rebuild may demote again.
        overflow(List.of(row(1, 1)), new BigInteger[] {MAX.subtract(BigInteger.ONE), BigInteger.ZERO},
                new BigInteger[] {MAX, BigInteger.valueOf(4)}, 1, BigInteger.valueOf(3));
        // A single product overflows even though the previous cached total is zero.
        overflow(List.of(row(MAX, BigInteger.ONE)), new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO},
                new BigInteger[] {BigInteger.valueOf(3), BigInteger.ZERO}, 0, BigInteger.TWO);
        // Undo can itself require a product outside long: cancellation in the
        // row must not make a wrapped intermediate appear to be an exact sum.
        var rows = List.of(row(MAX, MAX.negate()));
        BigInteger[] low = {BigInteger.ZERO, BigInteger.ZERO}, high = {BigInteger.TWO, BigInteger.ZERO};
        var budget = budget();
        try (var search = new CountLcg(rows, low, high, budget, 100000)) {
            Object cached = ACTIVITY.invoke(search, 0);
            change(search, 0, true, BigInteger.TWO);
            inspect(search, rows, cached, 0);
            change(search, 0, true, BigInteger.ZERO);
            inspect(search, rows, cached, 0);
            require(sum(cached).signum() == 0, "overflowing undo lost cancellation");
            promotions++;
        }
        require(budget.reservedBytes() == 0, "overflow reservation");
    }

    private static void overflow(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high,
                                 int variable, BigInteger next) throws Exception {
        var budget = budget();
        try (var search = new CountLcg(rows, low, high, budget, 100000)) {
            Object cached = ACTIVITY.invoke(search, 0);
            inspect(search, rows, cached, 0);
            require(field(cached.getClass(), "minimum").get(cached) == null, "primitive initial sum");
            change(search, variable, true, next);
            inspect(search, rows, cached, 0);
            require(field(cached.getClass(), "minimum").get(cached) != null, "overflow not promoted");
            promotions++;
            change(search, variable, true, low[variable]);
            inspect(search, rows, cached, 0);
            require(field(cached.getClass(), "minimum").get(cached) != null, "promotion must survive undo");
            INVALIDATE.invoke(search, 0);
            cached = ACTIVITY.invoke(search, 0);
            inspect(search, rows, cached, 0);
            require(field(cached.getClass(), "minimum").get(cached) == null, "fresh exact rebuild can demote");
        }
        require(budget.reservedBytes() == 0, "promotion reservation");
    }

    private static void randomTransitions() throws Exception {
        var random = new Random(20261011);
        BigInteger[] coefficients = {BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE.negate(), BigInteger.TWO,
                BigInteger.valueOf(-7), MAX, MAX.negate(), MIN, BigInteger.ONE.shiftLeft(129)};
        for (int test = 0; test < 180; test++) {
            int n = 9;
            BigInteger origin = switch (test % 6) {
                case 1 -> MIN;
                case 2 -> MAX.subtract(BigInteger.valueOf(7));
                case 3 -> BigInteger.valueOf(-3);
                case 4 -> BigInteger.ONE.shiftLeft(130);
                default -> BigInteger.ZERO;
            };
            BigInteger[] low = new BigInteger[n], high = new BigInteger[n];
            Arrays.fill(low, origin);
            Arrays.fill(high, origin.add(BigInteger.valueOf(7)));
            if (test % 6 == 5) high[n - 1] = null;
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int r = 0; r < 5; r++) {
                var terms = new LinkedHashMap<Integer, BigInteger>();
                for (int i = 0; i < n; i++) {
                    BigInteger a = test % 3 == 0 ? BigInteger.valueOf(random.nextInt(15) - 7) :
                            coefficients[random.nextInt(coefficients.length)];
                    terms.put(i, a);
                }
                rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.ZERO));
            }
            var budget = budget();
            try (var search = new CountLcg(rows, low, high, budget, 1000000)) {
                Object[] caches = new Object[rows.size()];
                for (int r = 0; r < rows.size(); r++) caches[r] = ACTIVITY.invoke(search, r);
                // Direct endpoint events exercise the same cache contract as
                // implications/backjumps. We do not claim these artificial
                // events are a trail or use them to prove a solver result.
                for (int step = 0; step < 128; step++) {
                    int id = random.nextInt(n);
                    boolean minimum = random.nextBoolean();
                    BigInteger[] currentLow = (BigInteger[]) LOW.get(search), currentHigh = (BigInteger[]) HIGH.get(search);
                    BigInteger next;
                    if (minimum) {
                        int room = currentHigh[id] == null ? 7 : currentHigh[id].subtract(origin).intValueExact();
                        next = origin.add(BigInteger.valueOf(random.nextInt(room + 1)));
                    } else {
                        int first = currentLow[id].subtract(origin).intValueExact();
                        next = high[id] == null && random.nextInt(3) == 0 ? null :
                                origin.add(BigInteger.valueOf(first + random.nextInt(8 - first)));
                    }
                    change(search, id, minimum, next);
                    for (int r = 0; r < rows.size(); r++) inspect(search, rows, caches[r], r);
                    if (step % 17 == 0) {
                        int r = random.nextInt(rows.size());
                        INVALIDATE.invoke(search, r);
                        // This stands for a sleeping row missing several events.
                        for (int i = 0; i < n; i++) change(search, i, false, high[i]);
                        caches[r] = ACTIVITY.invoke(search, r);
                        inspect(search, rows, caches[r], r);
                        rebuilds++;
                    }
                }
            }
            require(budget.reservedBytes() == 0, "transition reservation");
        }
    }

    private static void change(CountLcg search, int id, boolean minimum, BigInteger next) throws Exception {
        BigInteger[] endpoints = (BigInteger[]) (minimum ? LOW : HIGH).get(search);
        BigInteger old = endpoints[id];
        if (Objects.equals(old, next)) return;
        endpoints[id] = next;
        UPDATE.invoke(search, id, minimum, old, next);
    }

    private static void inspect(CountLcg search, List<ExactLinearProgram.Constraint> rows, Object cache, int r) throws Exception {
        BigInteger total = BigInteger.ZERO;
        int infinities = 0, xor = 0;
        BigInteger[] low = (BigInteger[]) LOW.get(search), high = (BigInteger[]) HIGH.get(search);
        for (var term : rows.get(r).terms().entrySet()) {
            if (term.getValue().signum() == 0) continue;
            int id = term.getKey();
            BigInteger value = term.getValue().signum() > 0 ? low[id] : high[id];
            if (value == null) { infinities++; xor ^= id; }
            else total = total.add(term.getValue().multiply(value));
        }
        require(total.equals(sum(cache)), "cached sum differs from original row: " + total + " != " + sum(cache));
        require(field(cache.getClass(), "infinities").getInt(cache) == infinities, "infinity count");
        require(field(cache.getClass(), "infiniteXor").getInt(cache) == xor, "infinity owner");
        if (field(cache.getClass(), "minimum").get(cache) == null) primitive++;
        else arbitrary++;
        checked++;
    }

    private static BigInteger sum(Object cache) throws Exception {
        Method method = cache.getClass().getDeclaredMethod("minimum");
        method.setAccessible(true);
        return (BigInteger) method.invoke(cache);
    }

    private static ExactLinearProgram.Constraint row(long a, long b) { return row(BigInteger.valueOf(a), BigInteger.valueOf(b)); }
    private static ExactLinearProgram.Constraint row(BigInteger a, BigInteger b) {
        var terms = new LinkedHashMap<Integer, BigInteger>();
        terms.put(0, a); terms.put(1, b);
        return new ExactLinearProgram.Constraint(terms, BigInteger.ZERO);
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 20000000, 64L << 20, () -> false, System::nanoTime); }
    private static Field field(Class<?> type, String name) {
        try { var result = type.getDeclaredField(name); result.setAccessible(true); return result; }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static Method method(String name, Class<?>... parameters) {
        try { var result = CountLcg.class.getDeclaredMethod(name, parameters); result.setAccessible(true); return result; }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void require(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
