// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Compare compound deltas with full original-row activities, not cached scores. */
public final class CountJumpTransfersTest {
    private static int checked;

    public static void main(String[] args) throws Exception {
        scores();
        admission();
        witnesses();
        check(plateau(1, false).equals(plateau(65536, false)), "Handoffs changed the compound frontier");
        plateau(4096, true);
        System.out.println("Group transfers: " + checked + " original-row delta checks, admission, retained frontier and cancellation passed");
    }

    private static void scores() throws Exception {
        Method transfer = CountJump.class.getDeclaredMethod("transferScore", int.class, int.class, int.class);
        Method compound = CountJump.class.getDeclaredMethod("compoundTransferScore", int.class, int.class, int.class,
                int.class, int.class, int.class);
        transfer.setAccessible(true); compound.setAccessible(true);
        for (int seed = 0; seed < 96; seed++) {
            var random = new Random(8891 + seed);
            int groups = 2 + seed % 6, size = groups * 2;
            var lower = new BigInteger[size]; var upper = new BigInteger[size]; var initial = new BigInteger[size];
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int group = 0; group < groups; group++) {
                int first = 2 * group, capacity = 2 + random.nextInt(5);
                BigInteger shift = seed % 3 == 0 ? BigInteger.ONE.shiftLeft(70).negate() : BigInteger.valueOf(-9);
                lower[first] = lower[first + 1] = shift;
                upper[first] = upper[first + 1] = shift.add(BigInteger.valueOf(capacity));
                int allocated = random.nextInt(capacity + 1);
                initial[first] = shift.add(BigInteger.valueOf(allocated));
                initial[first + 1] = shift.add(BigInteger.valueOf(capacity - allocated));
                var terms = new LinkedHashMap<Integer, BigInteger>();
                // Input insertion order is not the coordinate ordering.
                terms.put(first + 1, BigInteger.ONE); terms.put(first, BigInteger.ONE);
                equality(rows, terms, shift.multiply(BigInteger.TWO).add(BigInteger.valueOf(capacity)));
            }
            for (int r = 0; r < 2 * size; r++) {
                var terms = new LinkedHashMap<Integer, BigInteger>();
                for (int id = size - 1; id >= 0; id--) if (random.nextBoolean())
                    terms.put(id, BigInteger.valueOf(random.nextInt(19) - 9));
                BigInteger bound = activity(terms, initial).add(BigInteger.valueOf(random.nextInt(9) - 4));
                rows.add(new ExactLinearProgram.Constraint(terms, bound));
            }
            Collections.shuffle(rows, random);
            var budget = new PlanningBudget(0, 4_000_000, 32L << 20, () -> false, () -> 0L);
            try (var jump = new CountJump(rows, lower, upper, budget, 100000, initial).retained()) {
                while ((int) field(jump, "initialized") < rows.size()) check(!jump.step(), "Initialization stopped");
                var weights = (double[]) field(jump, "weights");
                for (int r = 0; r < weights.length; r++) weights[r] = 1 + random.nextInt(4);
                for (int a = 0; a < size; a += 2) for (int sign : new int[]{-1, 1}) {
                    BigInteger[] next = exchange(initial, a, a + 1, sign);
                    if (!within(next, lower, upper)) continue;
                    double expected = objective(rows, weights, next) - objective(rows, weights, initial);
                    double actual = (double) transfer.invoke(jump, a, a + 1, sign);
                    checkClose(expected, actual); checked++;
                    for (int c = a + 2; c < size; c += 2) for (int other : new int[]{-1, 1}) {
                        BigInteger[] both = exchange(next, c, c + 1, other);
                        if (!within(both, lower, upper)) continue;
                        expected = objective(rows, weights, both) - objective(rows, weights, initial);
                        actual = (double) compound.invoke(jump, a, a + 1, sign, c, c + 1, other);
                        checkClose(expected, actual); checked++;
                    }
                }
                check(Arrays.equals(initial, (BigInteger[]) field(jump, "values")), "Scoring changed the candidate");
            }
            check(budget.reservedBytes() == 0, "Delta workspace leaked");
        }
    }

    private static void admission() {
        var lower = new BigInteger[]{BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO};
        var upper = new BigInteger[]{BigInteger.TWO, BigInteger.TWO, BigInteger.ZERO};
        var rows = List.of(new ExactLinearProgram.Constraint(Map.of(1, BigInteger.TWO, 0, BigInteger.TWO), BigInteger.TWO),
                new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE, 1, BigInteger.ONE), BigInteger.TWO));
        var budget = new PlanningBudget(0, 100000, 1L << 20, () -> false, () -> 0L);
        try (var transfers = CountJumpTransfers.create(rows, lower, upper, budget)) {
            check(transfers != null && transfers.size() == 1, "Duplicate/ordered groups were not deduplicated");
            check(transfers.first(0) == 0 && transfers.second(0) == 1, "Wrong exchange coordinates");
        }
        check(budget.reservedBytes() == 0, "Admission workspace leaked");
        long held = budget.availableBytes(); check(budget.tryReserve(held), "Memory guard");
        check(CountJumpTransfers.create(rows, lower, upper, budget) == null, "Optional memory refusal ignored");
        budget.release(held);
        upper[0] = upper[1] = BigInteger.ONE;
        check(CountJumpTransfers.create(rows, lower, upper, budget) == null, "Existing Boolean pair search was duplicated");
        upper[0] = null;
        check(CountJumpTransfers.create(rows, lower, upper, budget) == null, "Unbounded allocation admitted");
        check(budget.reservedBytes() == 0, "Refusal leaked memory");
    }

    private static void witnesses() throws Exception {
        var constructor = CountJump.class.getDeclaredConstructor(List.class, BigInteger[].class, BigInteger[].class,
                PlanningBudget.class, long.class, BigInteger[].class, boolean.class);
        constructor.setAccessible(true);
        for (boolean compound : new boolean[]{false, true}) for (int order = 0; order < 4; order++) {
            int size = compound ? 4 : 2;
            var low = new BigInteger[size]; var high = new BigInteger[size]; var initial = new BigInteger[size];
            BigInteger shift = order % 2 == 0 ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(70).negate();
            Arrays.fill(low, shift); Arrays.fill(high, shift.add(BigInteger.TWO));
            for (int i = 0; i < size; i++) initial[i] = shift.add(i % 2 == 0 ? BigInteger.TWO : BigInteger.ZERO);
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int i = 0; i < size; i += 2)
                equality(rows, Map.of(i, BigInteger.ONE, i + 1, BigInteger.ONE), shift.multiply(BigInteger.TWO).add(BigInteger.TWO));
            if (compound) {
                // Either group exchange breaks a-c=0 as much as it improves
                // a+c=2. The simultaneous exchanges satisfy both relations.
                equality(rows, Map.of(0, BigInteger.ONE, 2, BigInteger.ONE.negate()), BigInteger.ZERO);
                equality(rows, Map.of(0, BigInteger.ONE, 2, BigInteger.ONE), shift.multiply(BigInteger.TWO).add(BigInteger.TWO));
            } else equality(rows, Map.of(0, BigInteger.TWO, 1, BigInteger.ONE), shift.multiply(BigInteger.valueOf(3)).add(BigInteger.valueOf(3)));
            Collections.shuffle(rows, new Random(order));
            var budget = new PlanningBudget(0, 1_000_000, 32L << 20, () -> false, () -> 0L);
            try (var jump = ((CountJump) constructor.newInstance(rows, low, high, budget, 16384L, initial, true)).retained()) {
                while (!jump.step()) {}
                var counts = jump.counts();
                check(counts != null && within(counts, low, high), "Exchange witness lost");
                for (BigInteger value : counts) check(value.equals(shift.add(BigInteger.ONE)), "Wrong original-coordinate witness");
                for (var row : rows) check(activity(row.terms(), counts).compareTo(row.upper()) <= 0, "Original row violated");
                check((int) field(jump, compound ? "compoundTransfers" : "transferMoves") > 0, "Exchange route was not exercised");
            }
            check(budget.reservedBytes() == 0, "Witness workspace leaked");
        }
    }

    private static String plateau(long quantum, boolean refuseScout) throws Exception {
        int groups = 8, size = 2 * groups + 1;
        var lower = new BigInteger[size]; var upper = new BigInteger[size]; var initial = new BigInteger[size];
        Arrays.fill(lower, BigInteger.ZERO); Arrays.fill(upper, BigInteger.TWO); Arrays.fill(initial, BigInteger.ZERO);
        upper[size - 1] = BigInteger.ZERO;
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int i = 0; i < 2 * groups; i += 2) {
            initial[i] = BigInteger.TWO;
            equality(rows, Map.of(i, BigInteger.ONE, i + 1, BigInteger.ONE), BigInteger.TWO);
        }
        // Independently impossible: a fixed zero cannot be at most -1.
        // Exchanges remain candidates only, never an infeasibility certificate.
        rows.add(new ExactLinearProgram.Constraint(Map.of(size - 1, BigInteger.ONE), BigInteger.ONE.negate()));
        var cancelled = new AtomicBoolean();
        var budget = new PlanningBudget(0, 1_000_000, 32L << 20, cancelled::get, () -> 0L);
        String snapshot;
        long held = 0, ordinaryBytes = 0;
        try (var jump = new CountJump(rows, lower, upper, budget, 1024, initial).retained()) {
            while (true) {
                if (refuseScout && held == 0 && budget.nodes() >= 250000) {
                    ordinaryBytes = budget.reservedBytes();
                    held = budget.availableBytes() - 4096;
                    check(budget.tryReserve(held), "Scout memory guard");
                }
                if (jump.step()) {
                    check(jump.paused(), "Impossible neighborhood ended as a witness");
                    jump.resume(quantum);
                }
                CountJump scout = (CountJump) field(jump, "scout");
                if (refuseScout && (boolean) field(jump, "scoutAttempted")) {
                    check(scout == null, "Insufficient scratch space admitted a child");
                    check(budget.reservedBytes() == ordinaryBytes + held, "Declined child leaked directions");
                    break;
                }
                if (scout != null && (boolean) field(scout, "compoundingTransfers") && (int) field(scout, "compoundSeed") >= 2) break;
            }
            check(jump.counts() == null, "Impossible neighborhood accepted");
            check(Arrays.equals(initial, (BigInteger[]) field(jump, "values")), "Failed compound trial changed the point");
            CountJump scout = (CountJump) field(jump, "scout");
            snapshot = scout == null ? "refused" : field(jump, "work") + ":" + field(scout, "compoundCursor") + ":" + field(scout, "compoundSeed");
            var worker = Executors.newSingleThreadExecutor();
            try {
                worker.submit(() -> {
                    if (jump.step() && jump.paused()) { jump.resume(quantum); jump.step(); }
                }).get();
            } finally { worker.shutdownNow(); }
            check(jump.counts() == null, "Worker handoff accepted an impossible candidate");
            cancelled.set(true);
            boolean stopped = false;
            try {
                for (int i = 0; i < 3; i++) if (jump.step() && jump.paused()) jump.resume(quantum);
            } catch (CancellationException expected) { stopped = true; }
            check(stopped, "Cancellation did not stop compound scoring");
        } finally { budget.release(held); }
        check(budget.reservedBytes() == 0, "Cancelled transfer workspace leaked");
        return snapshot;
    }

    private static void equality(List<ExactLinearProgram.Constraint> rows, Map<Integer, BigInteger> terms, BigInteger rhs) {
        rows.add(new ExactLinearProgram.Constraint(terms, rhs));
        var negative = new LinkedHashMap<Integer, BigInteger>();
        terms.forEach((id, value) -> negative.put(id, value.negate()));
        rows.add(new ExactLinearProgram.Constraint(negative, rhs.negate()));
    }

    private static BigInteger[] exchange(BigInteger[] point, int first, int second, int sign) {
        var result = point.clone();
        result[first] = result[first].add(BigInteger.valueOf(sign));
        result[second] = result[second].subtract(BigInteger.valueOf(sign));
        return result;
    }

    private static boolean within(BigInteger[] point, BigInteger[] lower, BigInteger[] upper) {
        for (int i = 0; i < point.length; i++) if (point[i].compareTo(lower[i]) < 0 || point[i].compareTo(upper[i]) > 0) return false;
        return true;
    }

    private static BigInteger activity(Map<Integer, BigInteger> terms, BigInteger[] point) {
        var result = BigInteger.ZERO;
        for (var term : terms.entrySet()) result = result.add(term.getValue().multiply(point[term.getKey()]));
        return result;
    }

    private static double objective(List<ExactLinearProgram.Constraint> rows, double[] weights, BigInteger[] point) {
        double result = 0;
        for (int r = 0; r < rows.size(); r++) {
            var row = rows.get(r); var scale = BigInteger.ONE;
            for (var a : row.terms().values()) scale = scale.max(a.abs());
            result += weights[r] * activity(row.terms(), point).subtract(row.upper()).max(BigInteger.ZERO).doubleValue() / scale.doubleValue();
        }
        return result;
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }

    private static void checkClose(double expected, double actual) {
        check(Math.abs(expected - actual) <= 1e-10 * Math.max(1, Math.abs(expected)), "Combined delta differs from full-row oracle");
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
