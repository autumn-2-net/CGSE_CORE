// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.*;
import java.math.BigInteger;
import java.util.*;

/** Exact primitive propagation against the existing BigInteger path and finite enumeration. */
public final class CountLcgFiniteTest {
    private static final BigInteger MIN = BigInteger.valueOf(Long.MIN_VALUE), MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final Method ACTIVITY = method("activity", int.class), PROPAGATE = method("propagate", int.class);
    private static final Field ACTIVITIES = field(CountLcg.class, "rowActivity"), LOW = field(CountLcg.class, "low"),
            HIGH = field(CountLcg.class, "high"), TRAIL = field(CountLcg.class, "trail");
    private static int boundaries, models, fast, slow, resumptions;

    public static void main(String[] args) throws Exception {
        boundaries();
        trajectories();
        require(fast > 100 && slow > 100 && resumptions > 0,
                "missing path coverage: fast=" + fast + ", slow=" + slow + ", resumptions=" + resumptions);
        System.out.println("Finite LCG: " + boundaries + " exact row boundaries, " + models +
                " exhaustive paired trajectories; primitive=" + fast + ", arbitrary=" + slow +
                ", resumptions=" + resumptions + "; identical work, reasons and certificates");
    }

    private static void boundaries() throws Exception {
        BigInteger[][] boxes = {
                {BigInteger.valueOf(-7), BigInteger.valueOf(9)}, {MIN, MIN.add(BigInteger.valueOf(5))},
                {MAX.subtract(BigInteger.valueOf(5)), MAX}, {MIN, BigInteger.ONE.negate()},
                {BigInteger.ONE.negate(), MAX.subtract(BigInteger.ONE)}, {MIN, BigInteger.ZERO},
                {MAX, MAX.add(BigInteger.TWO)}, {BigInteger.ONE.shiftLeft(130).negate(), BigInteger.ZERO},
                {BigInteger.TWO, BigInteger.TWO}
        };
        BigInteger[] coefficients = {BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE.negate(), BigInteger.TWO,
                BigInteger.TWO.negate(), MAX, MAX.negate(), MIN, MAX.add(BigInteger.ONE), BigInteger.ONE.shiftLeft(129)};
        BigInteger[] residuals = {BigInteger.ZERO, BigInteger.ONE, BigInteger.TWO, MAX.subtract(BigInteger.ONE),
                MAX, MAX.add(BigInteger.ONE), BigInteger.ONE.shiftLeft(131)};
        for (int box = 0; box < boxes.length; box++) for (BigInteger a : coefficients) for (BigInteger residual : residuals) {
            var low = new BigInteger[3];
            var high = new BigInteger[3];
            var terms = new LinkedHashMap<Integer, BigInteger>();
            BigInteger minimum = BigInteger.ZERO;
            for (int i = 0; i < 3; i++) {
                low[i] = boxes[(box + i) % boxes.length][0];
                high[i] = boxes[(box + i) % boxes.length][1];
                BigInteger coefficient = i == 0 ? a : i == 1 ? a.negate() : BigInteger.valueOf(3);
                terms.put(i, coefficient);
                minimum = minimum.add(coefficient.multiply(coefficient.signum() >= 0 ? low[i] : high[i]));
            }
            var row = new ExactLinearProgram.Constraint(terms, minimum.add(residual));
            BigInteger[] expectedLow = low.clone(), expectedHigh = high.clone();
            for (var term : terms.entrySet()) {
                int i = term.getKey();
                if (term.getValue().signum() > 0)
                    expectedHigh[i] = expectedHigh[i].min(low[i].add(residual.divide(term.getValue())));
                else if (term.getValue().signum() < 0)
                    expectedLow[i] = expectedLow[i].max(high[i].subtract(residual.divide(term.getValue().negate())));
            }
            PlanningBudget left = budget(), right = budget();
            try (var exact = new CountLcg(List.of(row), low, high, left, 100000);
                 var reference = new CountLcg(List.of(row), low, high, right, 100000)) {
                prepare(exact, 1, false);
                prepare(reference, 1, true);
                PROPAGATE.invoke(exact, 0);
                PROPAGATE.invoke(reference, 0);
                same(exact, reference, left, right);
                require(Arrays.equals((BigInteger[]) LOW.get(exact), expectedLow), "independent lower bound");
                require(Arrays.equals((BigInteger[]) HIGH.get(exact), expectedHigh), "independent upper bound");
            }
            require(left.reservedBytes() == 0 && right.reservedBytes() == 0, "row reservation");
            boundaries++;
        }
    }

    private static void trajectories() throws Exception {
        Random random = new Random(20261010);
        for (int test = 0; test < 256; test++) {
            int n = 8, width = 3;
            BigInteger offset = switch (test % 4) {
                case 1 -> MIN;
                case 2 -> MAX.subtract(BigInteger.TWO);
                case 3 -> BigInteger.valueOf(-5);
                default -> BigInteger.ZERO;
            };
            BigInteger[] low = new BigInteger[n], high = new BigInteger[n], planted = new BigInteger[n];
            Arrays.fill(low, offset);
            Arrays.fill(high, offset.add(BigInteger.TWO));
            for (int i = 0; i < n; i++) planted[i] = offset.add(BigInteger.valueOf(random.nextInt(width)));
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int r = 0; r < 8; r++) {
                var terms = new LinkedHashMap<Integer, BigInteger>();
                BigInteger rhs = BigInteger.ZERO;
                for (int i = 0; i < n; i++) {
                    BigInteger a = BigInteger.valueOf(random.nextInt(15) - 7);
                    if (a.signum() == 0) continue;
                    terms.put(i, a);
                    rhs = rhs.add(a.multiply(planted[i]));
                }
                rows.add(new ExactLinearProgram.Constraint(terms, rhs.add(BigInteger.valueOf(test % 2 == 0 ? 0 : random.nextInt(11) - 8))));
            }
            boolean possible = false;
            for (int code = 0; code < 6561; code++) {
                var point = low.clone();
                for (int i = 0, k = code; i < n; i++, k /= width) point[i] = point[i].add(BigInteger.valueOf(k % width));
                if (valid(rows, point)) possible = true;
            }
            PlanningBudget left = budget(), right = budget();
            try (var exact = new CountLcg(rows, low, high, left, 1024, true);
                 var reference = new CountLcg(rows, low, high, right, 1024, true)) {
                prepare(exact, rows.size(), false);
                prepare(reference, rows.size(), true);
                while (true) {
                    disable(reference);
                    boolean end = exact.step();
                    require(reference.step() == end, "different yield");
                    same(exact, reference, left, right);
                    if (!end) continue;
                    require(exact.paused() == reference.paused(), "different pause");
                    if (!exact.paused()) break;
                    exact.resume(17);
                    reference.resume(17);
                    resumptions++;
                }
                require((exact.counts() != null) == possible && exact.infeasible() != possible, "finite oracle");
                require(Arrays.equals(exact.counts(), reference.counts()), "different witness");
                require(Objects.equals(exact.certificate(), reference.certificate()), "different proof");
                require(CountProof.verify(exact.certificate(), 2000000) == CountProof.Verdict.VERIFIED, "proof replay");
                if (exact.counts() != null) require(valid(rows, exact.counts()), "original rows");
            }
            require(left.reservedBytes() == 0 && right.reservedBytes() == 0, "trajectory reservation");
            models++;
        }
    }

    private static void prepare(CountLcg search, int rows, boolean disable) throws Exception {
        for (int i = 0; i < rows; i++) {
            Object activity = ACTIVITY.invoke(search, i);
            Field flag = field(activity.getClass(), "finiteLong");
            if (!disable) { if (flag.getBoolean(activity)) fast++; else slow++; }
            else flag.setBoolean(activity, false);
        }
    }

    private static void disable(CountLcg search) throws Exception {
        for (Object activity : (Object[]) ACTIVITIES.get(search))
            if (activity != null) field(activity.getClass(), "finiteLong").setBoolean(activity, false);
    }

    private static void same(CountLcg first, CountLcg second, PlanningBudget left, PlanningBudget right) throws Exception {
        require(Arrays.equals((BigInteger[]) LOW.get(first), (BigInteger[]) LOW.get(second)), "lower mismatch");
        require(Arrays.equals((BigInteger[]) HIGH.get(first), (BigInteger[]) HIGH.get(second)), "upper mismatch");
        require(left.nodes() == right.nodes(), "work mismatch");
        List<?> a = (List<?>) TRAIL.get(first), b = (List<?>) TRAIL.get(second);
        require(a.size() == b.size(), "trail mismatch");
        for (int i = 0; i < a.size(); i++) for (String name : List.of("literal", "linear")) {
            Field field = field(a.get(i).getClass(), name);
            require(Objects.equals(field.get(a.get(i)), field.get(b.get(i))), "reason mismatch");
        }
    }

    private static boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] point) {
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(point[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
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
