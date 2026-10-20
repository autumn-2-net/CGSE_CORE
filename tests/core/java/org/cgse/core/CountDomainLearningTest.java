// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;

/** Exhaust every original assignment to check SAT/UNSAT across learning and undo. */
public final class CountDomainLearningTest {
    private static final Field HOLES = field(CountLcg.class, "holeTrail");
    private static final Class<?> HOLE = nested("Hole"), LITERAL = nested("Literal");
    private static final Field VARIABLE = field(HOLE, "variable"), REMOVED = field(HOLE, "removed"), REASON = field(HOLE, "reason");
    private static final Field BOUND_VARIABLE = field(LITERAL, "variable"), MINIMUM = field(LITERAL, "minimum"), VALUE = field(LITERAL, "value");
    private static long explanations;
    public static void main(String[] args) throws Exception {
        allocationRefusal();
        crossEquationReason();
        long deleted = 0, restored = 0;
        int sat = 0, unsat = 0, cases = 0;
        var random = new Random(2026102043L);
        for (int seed = 0; seed < 256; seed++) {
            int n = 5 + seed % 2;
            BigInteger offset = seed % 7 == 0 ? BigInteger.ONE.shiftLeft(90).negate() : BigInteger.valueOf(-2);
            var lo = new BigInteger[n]; var hi = new BigInteger[n]; var planted = new BigInteger[n];
            Arrays.fill(lo, offset); Arrays.fill(hi, offset.add(BigInteger.valueOf(3)));
            for (int i = 0; i < n; i++) planted[i] = offset.add(BigInteger.valueOf(random.nextInt(4)));
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int r = 0; r < 3; r++) {
                var terms = new LinkedHashMap<Integer, BigInteger>();
                while (terms.size() < 3 + seed % 2) {
                    int a = random.nextInt(11) - 5;
                    if (a != 0) terms.put(random.nextInt(n), BigInteger.valueOf(a));
                }
                // Ensure that every model admits the optional weighted analysis.
                int first = terms.keySet().iterator().next(); terms.put(first, BigInteger.TWO);
                BigInteger rhs = sum(terms, planted);
                if ((seed & 1) == 1 && r == 0) rhs = rhs.add(BigInteger.ONE);
                rows.addAll(CountEqualitySupportTest.pair(terms, rhs));
            }
            var witnesses = new ArrayList<BigInteger[]>();
            for (int encoded = 0; encoded < (1 << (2 * n)); encoded++) {
                var point = new BigInteger[n];
                for (int i = 0; i < n; i++) point[i] = offset.add(BigInteger.valueOf((encoded >>> (2 * i)) & 3));
                if (valid(rows, point)) witnesses.add(point);
            }
            boolean expected = !witnesses.isEmpty();
            for (int order = 0; order < 3; order++) {
                var ordered = new ArrayList<>(rows);
                if (order != 0) Collections.shuffle(ordered, new Random(seed * 7L + order));
                var budget = new PlanningBudget(0, 4_000_000, 32L << 20, () -> false, System::nanoTime);
                try (var support = CountEqualitySupport.create(ordered, lo, hi, budget);
                     var search = new CountLcg(ordered, lo, hi, budget, 4096)) {
                    check(support != null, "No support admitted");
                    search.supportEqualities(support);
                    check(search.supportDomains(), "No domains admitted");
                    var seen = Collections.newSetFromMap(new IdentityHashMap<>());
                    while (true) {
                        if (search.paused()) search.resume(order == 0 ? 4096 : 1);
                        boolean done = search.step();
                        // Each recorded reason must justify its deletion on
                        // EVERY original-model witness, including witnesses
                        // outside the currently explored branch.
                        for (Object hole : (List<?>) HOLES.get(search)) if (seen.add(hole)) {
                            int variable = VARIABLE.getInt(hole), removed = REMOVED.getInt(hole);
                            for (BigInteger[] point : witnesses) {
                                boolean applies = true;
                                for (Object literal : (List<?>) REASON.get(hole)) {
                                    int cmp = point[BOUND_VARIABLE.getInt(literal)].compareTo((BigInteger) VALUE.get(literal));
                                    applies &= MINIMUM.getBoolean(literal) ? cmp >= 0 : cmp <= 0;
                                }
                                if (applies) check((removed & (1 << point[variable].subtract(lo[variable]).intValueExact())) == 0,
                                        "Deletion lost a conditional antecedent: seed=" + seed);
                            }
                            explanations++;
                        }
                        if (done && !search.paused()) break;
                    }
                    check((search.counts() != null) == expected, "Changed exhaustive SAT result: seed=" + seed + "; order=" + order);
                    check(search.infeasible() == !expected, "Lost exact completion");
                    if (expected) {
                        check(valid(rows, search.counts()), "Invalid original witness");
                        sat++;
                    } else unsat++;
                    deleted += statistic(search, "holesRemoved");
                    restored += statistic(search, "holesRestored");
                }
                check(budget.reservedBytes() == 0, "Domain trail leaked");
                cases++;
            }
        }
        check(sat > 0 && unsat > 0 && deleted > 0 && restored > 0, "Missing real learning/undo coverage");
        System.out.println("Domain learning: cases=" + cases + "; SAT=" + sat + "; UNSAT=" + unsat +
                "; deletions=" + deleted + "; restored=" + restored + "; explanations=" + explanations + "; exact original-model oracle passed");
    }
    private static void allocationRefusal() {
        BigInteger[] lo = {BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO};
        BigInteger[] hi = {BigInteger.valueOf(3), BigInteger.ONE, BigInteger.ONE};
        var rows = CountEqualitySupportTest.pair(Map.of(0, BigInteger.ONE, 1, BigInteger.TWO, 2, BigInteger.valueOf(3)), BigInteger.valueOf(3));
        var budget = new PlanningBudget(0, 4_000_000, 32L << 20, () -> false, System::nanoTime);
        try (var support = CountEqualitySupport.create(rows, lo, hi, budget);
             var search = new CountLcg(rows, lo, hi, budget, 4096)) {
            search.supportEqualities(support);
            long lease = budget.availableBytes() - 1;
            check(budget.tryReserve(lease), "Failed to prepare domain-specific refusal");
            long before = budget.reservedBytes();
            check(!search.supportDomains() && budget.reservedBytes() == before, "Partial domain allocation on refusal");
            budget.release(lease);
            check(search.supportDomains(), "Refusal corrupted fresh candidate");
            while (!search.step()) {}
            check(search.counts() != null, "Refusal lost feasible model");
        }
        check(budget.reservedBytes() == 0, "Refused domain workspace leaked");
    }
    private static void crossEquationReason() throws Exception {
        // At t=0, A removes x=2 without changing x's hull. B cannot see t,
        // but consumes that hole to force x=0. Its reason MUST retain t=0:
        // the original model also has the witness (x,y,z,t,u,v)=(2,1,0,1,0,0).
        BigInteger[] lo = new BigInteger[6], hi = new BigInteger[6];
        Arrays.fill(lo, BigInteger.ZERO); Arrays.fill(hi, BigInteger.ONE); hi[0] = BigInteger.valueOf(3);
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        rows.addAll(CountEqualitySupportTest.pair(Map.of(0, BigInteger.ONE, 1, BigInteger.TWO,
                2, BigInteger.valueOf(3), 3, BigInteger.ONE.negate()), BigInteger.valueOf(3)));
        rows.addAll(CountEqualitySupportTest.pair(Map.of(0, BigInteger.ONE, 4, BigInteger.TWO, 5, BigInteger.valueOf(3)), BigInteger.TWO));
        Constructor<?> literal = LITERAL.getDeclaredConstructor(int.class, boolean.class, BigInteger.class);
        literal.setAccessible(true);
        Method tighten = CountLcg.class.getDeclaredMethod("tighten", LITERAL, List.class);
        Method propagate = CountLcg.class.getDeclaredMethod("propagateSupport", int.class);
        Method undo = CountLcg.class.getDeclaredMethod("backtrack", int.class);
        tighten.setAccessible(true); propagate.setAccessible(true); undo.setAccessible(true);
        Field level = field(CountLcg.class, "level"), trail = field(CountLcg.class, "trail");
        Class<?> change = nested("Change");
        Field antecedents = field(change, "reason"), conclusion = field(change, "literal");
        var budget = new PlanningBudget(0, 4_000_000, 32L << 20, () -> false, System::nanoTime);
        try (var support = CountEqualitySupport.create(rows, lo, hi, budget);
             var search = new CountLcg(rows, lo, hi, budget, 4096)) {
            search.supportEqualities(support); check(search.supportDomains(), "No cross-equation admission");
            level.setInt(search, 1);
            tighten.invoke(search, literal.newInstance(3, false, BigInteger.ZERO), null);
            propagate.invoke(search, 0);
            check(((List<?>) HOLES.get(search)).size() == 1, "Missing conditional interior hole");
            propagate.invoke(search, 2);
            check(((List<?>) trail.get(search)).size() > 1, "Missing propagated bound using the hole");
            int checked = 0;
            for (int encoded = 0; encoded < 128; encoded++) {
                BigInteger[] point = new BigInteger[6];
                point[0] = BigInteger.valueOf(encoded & 3);
                for (int i = 1; i < 6; i++) point[i] = BigInteger.valueOf((encoded >>> (i + 1)) & 1);
                if (!valid(rows, point)) continue;
                checked++;
                for (Object item : (List<?>) trail.get(search)) {
                    var reason = (List<?>) antecedents.get(item);
                    if (reason == null) continue;
                    boolean applies = true;
                    for (Object term : reason) applies &= holds(term, point);
                    if (applies) check(holds(conclusion.get(item), point), "Lost cross-equation hole antecedent");
                }
            }
            check(checked >= 2, "Missing opposite-branch witnesses");
            undo.invoke(search, 0);
            check(((List<?>) HOLES.get(search)).isEmpty(), "Conditional hole survived branch undo");
            level.setInt(search, 1);
            tighten.invoke(search, literal.newInstance(0, true, BigInteger.TWO), null);
            while (true) {
                if (search.paused()) search.resume(31);
                if (search.step() && !search.paused()) break;
            }
            check(search.counts() != null && search.counts()[0].equals(BigInteger.TWO) && valid(rows, search.counts()),
                    "Backtracking lost the other branch's valid original witness");
        }
        check(budget.reservedBytes() == 0, "Cross-equation reason leaked");
    }
    private static boolean holds(Object literal, BigInteger[] point) throws IllegalAccessException {
        int cmp = point[BOUND_VARIABLE.getInt(literal)].compareTo((BigInteger) VALUE.get(literal));
        return MINIMUM.getBoolean(literal) ? cmp >= 0 : cmp <= 0;
    }
    private static Class<?> nested(String name) {
        return Arrays.stream(CountLcg.class.getDeclaredClasses()).filter(c -> c.getSimpleName().equals(name)).findFirst().orElseThrow();
    }
    private static Field field(Class<?> type, String name) {
        try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static long statistic(CountLcg search, String name) throws Exception {
        Field field = CountLcg.class.getDeclaredField(name); field.setAccessible(true); return field.getLong(search);
    }
    private static BigInteger sum(Map<Integer, BigInteger> terms, BigInteger[] point) {
        BigInteger sum = BigInteger.ZERO;
        for (var term : terms.entrySet()) sum = sum.add(term.getValue().multiply(point[term.getKey()]));
        return sum;
    }
    private static boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] point) {
        for (var row : rows) if (sum(row.terms(), point).compareTo(row.upper()) > 0) return false;
        return true;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
