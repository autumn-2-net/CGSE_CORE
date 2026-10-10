// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.*;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Independent finite-box equivalence oracle for learned integer-bound minimization. */
public final class CountLcgMinimizeTest {
    private static final Class<?> LITERAL = nested("Literal"), FRONTIER = nested("ConflictFrontier");
    private static final Constructor<?> LITERAL_CTOR = constructor(LITERAL, int.class, boolean.class, BigInteger.class);
    private static final Constructor<?> FRONTIER_CTOR = constructor(FRONTIER, CountLcg.class);
    private static final Method TIGHTEN = method(CountLcg.class, "tighten", LITERAL, List.class);
    private static final Method PROPAGATE = method(CountLcg.class, "propagate", int.class);
    private static final Method CHARGE = method(CountLcg.class, "charge");
    private static final Method ADD = method(FRONTIER, "addAll", Collection.class);
    private static final Method LITERALS = method(FRONTIER, "literals");
    private static final Method ROW = method(LITERAL, "row");
    private static final Method MINIMIZE = method(CountLcg.class, "minimize", FRONTIER, int.class);
    private static final Field LEVEL = field("level"), WORK = field("work"), MIN_WORK = field("minimizationWork");
    private static int boxes, removed, refusals, models, handoffs;
    private static long searchMinimized;

    public static void main(String[] args) throws Exception {
        for (BigInteger offset : List.of(BigInteger.ZERO, BigInteger.ONE.shiftLeft(90), BigInteger.ONE.shiftLeft(90).negate()))
            for (boolean minimum : new boolean[] {true, false})
                for (int kind = 0; kind < 3; kind++)
                    for (int refusal = 0; refusal < 3; refusal++) chain(offset, minimum, kind, refusal);
        unrepresentedAntecedent();
        partialExplanation();
        trajectories();
        check(removed > 0 && refusals == 36 && searchMinimized > 0, "Missing actual minimization/refusal coverage");
        System.out.println("Integer conflict minimization: " + boxes + " independently enumerated boxes; removed=" + removed +
                "; memory refusals=" + refusals + "; models=" + models + "; search removals=" + searchMinimized + "; thread handoffs=" + handoffs +
                "; both directions, weak bounds, partial walks, offsets, proof replay and cleanup passed");
    }

    private static void chain(BigInteger offset, boolean minimum, int kind, int refusal) throws Exception {
        // a <= b <= c (or its opposite); d is an unrelated last-level literal.
        int sign = minimum ? 1 : -1;
        var rows = List.of(row(Map.of(0, BigInteger.valueOf(sign), 1, BigInteger.valueOf(-sign)), 0),
                row(Map.of(1, BigInteger.valueOf(sign), 2, BigInteger.valueOf(-sign)), 0));
        var low = new BigInteger[4];
        var high = new BigInteger[4];
        Arrays.fill(low, offset);
        Arrays.fill(high, offset.add(BigInteger.valueOf(4)));
        var budget = new PlanningBudget(0, 2_000_000, 4L << 20, () -> false, System::nanoTime);
        try (var search = new CountLcg(rows, low, high, budget, 100_000, true)) {
            LEVEL.setInt(search, 1);
            TIGHTEN.invoke(search, literal(0, minimum, offset.add(BigInteger.TWO)), null);
            if (kind == 2) TIGHTEN.invoke(search, literal(0, !minimum,
                    offset.add(BigInteger.valueOf(minimum ? 3 : 1))), null);
            PROPAGATE.invoke(search, 0);
            PROPAGATE.invoke(search, 1);
            LEVEL.setInt(search, 2);
            TIGHTEN.invoke(search, literal(3, true, offset.add(BigInteger.ONE)), null);
            // Charge real work before making the optional minimization eligible.
            while (WORK.getLong(search) < 8192) CHARGE.invoke(search);
            Object frontier = FRONTIER_CTOR.newInstance(search);
            int bound = kind == 0 ? 2 : kind == 1 ? (minimum ? 1 : 3) : (minimum ? 3 : 1);
            Object a = literal(0, kind == 2 ? !minimum : minimum, offset.add(BigInteger.valueOf(bound)));
            var initial = List.of(a, literal(1, minimum, offset.add(BigInteger.TWO)),
                    literal(2, minimum, offset.add(BigInteger.TWO)), literal(3, true, offset.add(BigInteger.ONE)));
            ADD.invoke(frontier, initial);
            var before = literalRows(frontier);
            // First refuse the scratch list, then allow it but refuse the
            // two-term explanation (192 bytes). Neither may kill the search.
            long allowed = refusal == 2 ? 512 + 16L * before.size() + 96 : 0;
            long lease = refusal != 0 ? budget.availableBytes() - allowed : 0;
            if (refusal != 0) check(budget.tryReserve(lease), "Could not arrange optional memory refusal");
            long start = MIN_WORK.getLong(search);
            try {
                MINIMIZE.invoke(search, frontier, 2);
            } finally {
                budget.release(lease);
            }
            var after = literalRows(frontier);
            if (refusal != 0) {
                check(after.equals(before), "Refusal changed the learned conjunction");
                check(refusal == 1 ? MIN_WORK.getLong(search) == start : MIN_WORK.getLong(search) > start,
                        "Wrong allocation boundary exercised");
                refusals++;
            } else {
                check(after.size() < before.size(), "Transitive explanation was not minimized");
                check(after.size() == (kind == 0 ? 2 : 3), "A weak or opposite bound incorrectly implied the stronger bound");
                removed += before.size() - after.size();
            }
            for (int encoded = 0; encoded < 625; encoded++) {
                BigInteger[] point = new BigInteger[4];
                for (int i = 0, value = encoded; i < 4; i++, value /= 5)
                    point[i] = offset.add(BigInteger.valueOf(value % 5));
                if (satisfies(rows, point)) check(satisfies(before, point) == satisfies(after, point),
                        "Minimization changed the conjunction on an original-model witness");
            }
            boxes++;
        }
        check(budget.reservedBytes() == 0, "Minimization retained memory after close");
    }

    private static void unrepresentedAntecedent() throws Exception {
        int length = 100, n = length + 3;
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int i = 0; i < length; i++) rows.add(row(Map.of(i, BigInteger.ONE, i + 1, BigInteger.ONE.negate()), 0));
        var low = new BigInteger[n];
        var high = new BigInteger[n];
        Arrays.fill(low, BigInteger.ZERO);
        Arrays.fill(high, BigInteger.valueOf(4));
        var budget = new PlanningBudget(0, 2_000_000, 4L << 20, () -> false, System::nanoTime);
        try (var search = new CountLcg(rows, low, high, budget, 100_000)) {
            LEVEL.setInt(search, 1);
            TIGHTEN.invoke(search, literal(0, true, BigInteger.TWO), null);
            for (int i = 0; i < length; i++) PROPAGATE.invoke(search, i);
            LEVEL.setInt(search, 2);
            TIGHTEN.invoke(search, literal(length + 1, true, BigInteger.ONE), null);
            LEVEL.setInt(search, 3);
            TIGHTEN.invoke(search, literal(length + 2, true, BigInteger.ONE), null);
            while (WORK.getLong(search) < 8192) CHARGE.invoke(search);
            // The direct reason needs x99>=2, absent from the conjunction.
            // The weaker x0>=1 cannot replace it despite the implication chain.
            MIN_WORK.setLong(search, WORK.getLong(search) / 32 - 48);
            Object frontier = FRONTIER_CTOR.newInstance(search);
            ADD.invoke(frontier, List.of(literal(0, true, BigInteger.ONE), literal(length, true, BigInteger.TWO),
                    literal(length + 1, true, BigInteger.ONE), literal(length + 2, true, BigInteger.ONE)));
            var before = literalRows(frontier);
            long start = MIN_WORK.getLong(search);
            MINIMIZE.invoke(search, frontier, 3);
            var after = literalRows(frontier);
            check(MIN_WORK.getLong(search) > start, "No implication check exercised");
            check(after.equals(before), "An unrepresented antecedent removed its candidate");
            BigInteger[] counterexample = new BigInteger[n];
            Arrays.fill(counterexample, BigInteger.ONE);
            check(satisfies(rows, counterexample) && !satisfies(after, counterexample), "Unrepresented-antecedent counterexample lost");
        }
        check(budget.reservedBytes() == 0, "Implication check leaked memory");
    }

    private static void trajectories() throws Exception {
        var random = new Random(202610104L);
        var journal = new CountProof.Journal(16L << 20);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            for (int sample = 0; sample < 120; sample++) {
                int n = 6, width = 4;
                var rows = new ArrayList<ExactLinearProgram.Constraint>();
                var low = new BigInteger[n];
                var high = new BigInteger[n];
                BigInteger offset = sample % 3 == 0 ? BigInteger.ONE.shiftLeft(90).negate() : BigInteger.ZERO;
                Arrays.fill(low, offset);
                Arrays.fill(high, offset.add(BigInteger.valueOf(width - 1)));
                int[] plant = random.ints(n, 0, width).toArray();
                for (int r = 0; r < 8; r++) {
                    var terms = new LinkedHashMap<Integer, BigInteger>();
                    BigInteger rhs = BigInteger.valueOf(sample % 2 == 0 ? random.nextInt(5) : random.nextInt(9) - 8);
                    for (int i = 0; i < n; i++) {
                        int a = random.nextInt(11) - 5;
                        if (a == 0) continue;
                        terms.put(i, BigInteger.valueOf(a));
                        rhs = rhs.add(offset.multiply(BigInteger.valueOf(a)));
                        if (sample % 2 == 0) rhs = rhs.add(BigInteger.valueOf((long) plant[i] * a));
                    }
                    rows.add(new ExactLinearProgram.Constraint(terms, rhs));
                }
                Collections.shuffle(rows, random);
                boolean exists = false;
                for (int value = 0; value < 4096; value++) {
                    var point = low.clone();
                    for (int i = 0, rest = value; i < n; i++, rest /= width)
                        point[i] = point[i].add(BigInteger.valueOf(rest % width));
                    exists |= satisfies(rows, point);
                }
                var budget = new PlanningBudget(0, 4_000_000, 16L << 20, () -> false, System::nanoTime);
                try (var search = new CountLcg(rows, low, high, budget, 1024, true).minimizeIntegerConflicts()) {
                    boolean complete = false;
                    while (!complete) {
                        complete = worker.submit(() -> {
                            if (search.paused()) search.resume(128);
                            while (!search.step()) {}
                            return !search.paused();
                        }).get();
                        handoffs++;
                        if (!complete) {
                            long work = budget.nodes(), memory = budget.reservedBytes();
                            check(search.step() && search.paused() && budget.nodes() == work &&
                                    budget.reservedBytes() == memory, "Polling a pause changed work or memory");
                            search.resume(128);
                            while (!search.step()) {}
                            complete = !search.paused();
                        }
                    }
                    check((search.counts() != null) == exists && search.infeasible() != exists, "Wrong finite-domain conclusion");
                    if (exists) check(satisfies(rows, search.counts()), "Wrong finite-domain witness");
                    check(CountProof.verify(search.certificate(), 4_000_000) == CountProof.Verdict.VERIFIED, "Invalid minimized proof");
                    searchMinimized += field("minimized").getLong(search);
                    journal.add(search.certificate());
                }
                check(budget.reservedBytes() == 0, "Thread handoff retained minimization memory");
                models++;
            }
        } finally {
            worker.shutdownNow();
        }
        var archive = Files.createTempFile("cgse-integer-minimization-", ".bin");
        try {
            journal.write(archive);
            var replay = CountProof.read(archive);
            check(replay.entries().size() == 120, "Missing integer proof archive records");
            for (var proof : replay.entries())
                check(CountProof.verify(proof, 4_000_000) == CountProof.Verdict.VERIFIED, "Integer proof archive replay failed");
        } finally {
            Files.delete(archive);
        }
    }

    private static void partialExplanation() throws Exception {
        int inputs = 128, n = inputs + 2;
        var terms = new LinkedHashMap<Integer, BigInteger>();
        for (int i = 0; i < inputs; i++) terms.put(i, BigInteger.ONE);
        terms.put(inputs, BigInteger.ONE.negate());
        var rows = List.of(row(terms, 0));
        var low = new BigInteger[n];
        var high = new BigInteger[n];
        Arrays.fill(low, BigInteger.ZERO);
        Arrays.fill(high, BigInteger.ONE);
        high[inputs] = BigInteger.valueOf(inputs);
        var budget = new PlanningBudget(0, 2_000_000, 4L << 20, () -> false, System::nanoTime);
        try (var search = new CountLcg(rows, low, high, budget, 100_000)) {
            LEVEL.setInt(search, 1);
            var literals = new ArrayList<Object>();
            for (int i = 0; i < inputs; i++) {
                var literal = literal(i, true, BigInteger.ONE);
                TIGHTEN.invoke(search, literal, null);
                if (i != 0) literals.add(literal);
            }
            PROPAGATE.invoke(search, 0);
            literals.add(literal(inputs, true, BigInteger.valueOf(inputs)));
            LEVEL.setInt(search, 2);
            var last = literal(inputs + 1, true, BigInteger.ONE);
            TIGHTEN.invoke(search, last, null);
            literals.add(last);
            while (WORK.getLong(search) < 8192) CHARGE.invoke(search);
            Object frontier = FRONTIER_CTOR.newInstance(search);
            ADD.invoke(frontier, literals);
            MIN_WORK.setLong(search, WORK.getLong(search) / 32 - 32);
            var before = literalRows(frontier);
            long reserved = budget.reservedBytes();
            MINIMIZE.invoke(search, frontier, 2);
            var after = literalRows(frontier);
            check(after.equals(before), "Partial reason removed an unproved bound");
            check(budget.reservedBytes() == reserved, "An unfinished reason remained cached");
            long retryAt = WORK.getLong(search) + 8192;
            while (WORK.getLong(search) < retryAt) CHARGE.invoke(search);
            MINIMIZE.invoke(search, frontier, 2);
            after = literalRows(frontier);
            check(after.equals(before), "Retry trusted an unfinished cached explanation");
            var point = new BigInteger[n];
            Arrays.fill(point, BigInteger.ONE);
            point[0] = BigInteger.ZERO;
            point[inputs] = BigInteger.valueOf(inputs - 1);
            check(satisfies(rows, point) && !satisfies(after, point), "Partial explanation counterexample lost");
        }
        check(budget.reservedBytes() == 0, "Partial explanation leaked memory");
    }

    private static List<ExactLinearProgram.Constraint> literalRows(Object frontier) throws Exception {
        var result = new ArrayList<ExactLinearProgram.Constraint>();
        for (Object literal : (List<?>) LITERALS.invoke(frontier)) result.add((ExactLinearProgram.Constraint) ROW.invoke(literal));
        return result;
    }

    private static boolean satisfies(List<ExactLinearProgram.Constraint> rows, BigInteger[] point) {
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(point[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private static ExactLinearProgram.Constraint row(Map<Integer, BigInteger> terms, int rhs) {
        return new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(rhs));
    }

    private static Object literal(int variable, boolean minimum, BigInteger value) throws Exception {
        return LITERAL_CTOR.newInstance(variable, minimum, value);
    }

    private static Class<?> nested(String name) {
        return Arrays.stream(CountLcg.class.getDeclaredClasses()).filter(c -> c.getSimpleName().equals(name)).findFirst().orElseThrow();
    }

    private static Constructor<?> constructor(Class<?> owner, Class<?>... args) {
        try {
            var result = owner.getDeclaredConstructor(args);
            result.setAccessible(true);
            return result;
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static Method method(Class<?> owner, String name, Class<?>... args) {
        try {
            var result = owner.getDeclaredMethod(name, args);
            result.setAccessible(true);
            return result;
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static Field field(String name) {
        try {
            var result = CountLcg.class.getDeclaredField(name);
            result.setAccessible(true);
            return result;
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
