// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/** Vertex enumeration, independent dual checks, hot-basis ownership and interrupted pivots. */
public final class ExactPivotTest {
    private record Q(BigInteger n, BigInteger d) implements Comparable<Q> {
        Q {
            if (d.signum() == 0) throw new ArithmeticException();
            if (d.signum() < 0) { n = n.negate(); d = d.negate(); }
            BigInteger common = n.gcd(d);
            n = n.divide(common); d = d.divide(common);
        }
        Q add(Q value) { return new Q(n.multiply(value.d).add(value.n.multiply(d)), d.multiply(value.d)); }
        Q times(BigInteger value) { return new Q(n.multiply(value), d); }
        public int compareTo(Q value) { return n.multiply(value.d).compareTo(value.n.multiply(d)); }
    }
    private record Solved(ExactLinearProgram.Result result, ExactRational[] point, long work, long peak) {}
    private static final Q ZERO = q(BigInteger.ZERO);

    public static void main(String[] args) {
        planarOracle();
        slicedAndHot();
        workspaceAndPrecision();
        System.out.println("Exact pivots: 160 independent vertex oracles, sliced/hot bases, precision cutoffs, optional memory and cancellation passed");
    }

    private static void planarOracle() {
        Random random = new Random(431_719);
        for (int seed = 0; seed < 160; seed++) {
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            rows.add(row(8, 1, 0)); rows.add(row(9, 0, 1));
            for (int i = 0; i < 5; i++) rows.add(row(random.nextInt(49) - 8, random.nextInt(15) - 7, random.nextInt(15) - 7));
            Collections.shuffle(rows, random);
            BigInteger[] objective = {z(random.nextInt(15) - 7), z(random.nextInt(15) - 7)};
            Q expected = optimum(rows, objective);
            var budget = budget(64L << 20);
            try (var search = new ExactLinearProgram(2, rows, objective, budget)) {
                while (!search.step()) {}
                check(search.result() == (expected == null ? ExactLinearProgram.Result.INFEASIBLE : ExactLinearProgram.Result.OPTIMAL), "Vertex verdict " + seed);
                if (expected != null) check(verify(search, rows, objective).equals(expected), "Vertex optimum " + seed);
                else verifyNegative(search.certificate(), rows, 2);
            }
            check(budget.reservedBytes() == 0, "Vertex workspace leaked");
        }
    }

    private static Q optimum(List<ExactLinearProgram.Constraint> input, BigInteger[] objective) {
        var rows = new ArrayList<>(input);
        rows.add(row(0, -1, 0)); rows.add(row(0, 0, -1));
        Q best = null;
        for (int i = 0; i < rows.size(); i++) for (int j = 0; j < i; j++) {
            var a = rows.get(i); var b = rows.get(j);
            BigInteger ax = term(a, 0), ay = term(a, 1), bx = term(b, 0), by = term(b, 1);
            BigInteger determinant = ax.multiply(by).subtract(ay.multiply(bx));
            if (determinant.signum() == 0) continue;
            Q x = new Q(a.upper().multiply(by).subtract(ay.multiply(b.upper())), determinant);
            Q y = new Q(ax.multiply(b.upper()).subtract(a.upper().multiply(bx)), determinant);
            if (rows.stream().anyMatch(r -> x.times(term(r, 0)).add(y.times(term(r, 1))).compareTo(q(r.upper())) > 0)) continue;
            Q value = x.times(objective[0]).add(y.times(objective[1]));
            if (best == null || value.compareTo(best) > 0) best = value;
        }
        return best;
    }

    private static void slicedAndHot() {
        for (int n : new int[]{33, 257}) {
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            var terms = new LinkedHashMap<Integer, BigInteger>();
            var objective = new BigInteger[n];
            BigInteger total = BigInteger.ZERO;
            for (int i = 0; i < n; i++) {
                objective[i] = z(1 + i % 23);
                terms.put(i, objective[i]);
                total = total.add(objective[i]);
                if (n == 33 || i < 5) rows.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), z(3)));
            }
            var limit = new ExactLinearProgram.Constraint(terms, total);
            rows.add(limit);
            var budget = budget(64L << 20);
            ExactLinearProgram.Basis basis;
            try (var parent = new ExactLinearProgram(n, rows, objective, budget, null, true)) {
                int steps = 0;
                while (!parent.step()) steps++;
                check(steps > 8, "Did not cross sliced pivot rows");
                check(verify(parent, rows, objective).equals(q(total)), "Sliced optimum");
                basis = parent.takeBasis();
                check(basis != null, "No retained dense basis");
            }
            long retained = budget.reservedBytes();
            check(retained > 0, "Lost basis ownership");
            var child = new ArrayList<>(rows);
            child.add(new ExactLinearProgram.Constraint(terms, total.divide(BigInteger.TWO)));
            try (basis) {
                for (int repeat = 0; repeat < 3; repeat++) {
                    try (var hot = new ExactLinearProgram(n, child, objective, budget, basis, false)) {
                        while (!hot.step()) {}
                        check(hot.hot(), "Lost warm-basis reuse");
                        check(verify(hot, child, objective).equals(q(total.divide(BigInteger.TWO))), "Hot optimum");
                    }
                    check(budget.reservedBytes() == retained, "Child released or retained parent-owned workspace");
                }
            }
            check(budget.reservedBytes() == 0, "Basis scratch leaked");
            for (int stop : new int[]{1, 32, 256, 1024, 4096}) {
                var checks = new AtomicInteger();
                var cancelled = new PlanningBudget(0, 20_000_000, 64L << 20, () -> checks.incrementAndGet() >= stop, System::nanoTime);
                try (var search = new ExactLinearProgram(n, rows, objective, cancelled)) {
                    while (!search.step()) {}
                } catch (CancellationException expected) {}
                check(cancelled.reservedBytes() == 0, "Interrupted pivot leaked scratch");
            }
        }
    }

    private static void workspaceAndPrecision() {
        var rows = List.of(row(5, 1, 0), row(7, 0, 1), row(9, 2, 3));
        BigInteger[] objective = {z(3), z(4)};
        long tableBytes = (rows.size() + 2L) * 4 * 768 + 64 * (rows.size() + 4L);
        Solved uncached = solve(rows, objective, tableBytes + 767);
        Solved cached = solve(rows, objective, tableBytes + 768);
        check(uncached.result == ExactLinearProgram.Result.OPTIMAL && cached.result == uncached.result, "Optional cache became mandatory");
        check(Arrays.equals(cached.point, uncached.point) && cached.work == uncached.work, "Cache changed pivot choices or work");
        check(uncached.peak == tableBytes && cached.peak == tableBytes + 768, "Scratch reservation is not accounted");
        for (int bits : new int[]{1023, 1024}) {
            BigInteger scale = BigInteger.ONE.shiftLeft(bits);
            rows = List.of(new ExactLinearProgram.Constraint(Map.of(0, scale, 1, scale), scale),
                    new ExactLinearProgram.Constraint(Map.of(0, scale, 1, scale.multiply(BigInteger.TWO)), scale.multiply(BigInteger.TWO)));
            objective = new BigInteger[]{scale, scale};
            Solved result = solve(rows, objective, 64L << 20);
            check(result.result == (bits == 1023 ? ExactLinearProgram.Result.OPTIMAL : ExactLinearProgram.Result.UNKNOWN),
                    "Reordered pivot changed the old intermediate precision cutoff");
        }
    }

    private static Solved solve(List<ExactLinearProgram.Constraint> rows, BigInteger[] objective, long memory) {
        var budget = budget(memory);
        Solved answer;
        try (var search = new ExactLinearProgram(objective.length, rows, objective, budget)) {
            while (!search.step()) {}
            if (search.result() == ExactLinearProgram.Result.OPTIMAL) verify(search, rows, objective);
            answer = new Solved(search.result(), search.point(), budget.searchWork(), budget.peakBytes());
        }
        check(budget.reservedBytes() == 0, "LP workspace leaked");
        return answer;
    }

    private static Q verify(ExactLinearProgram search, List<ExactLinearProgram.Constraint> rows, BigInteger[] objective) {
        check(search.result() == ExactLinearProgram.Result.OPTIMAL, "Unverified optimum");
        var point = search.point(); var dual = search.optimumDual();
        check(point != null && dual != null && dual.length == rows.size(), "Missing primal/dual witnesses");
        Q primal = ZERO, bound = ZERO;
        for (int i = 0; i < point.length; i++) {
            check(point[i].signum() >= 0, "Negative primal coordinate");
            primal = primal.add(q(point[i]).times(objective[i]));
            Q column = ZERO;
            for (int r = 0; r < rows.size(); r++) column = column.add(q(dual[r]).times(term(rows.get(r), i)));
            check(column.compareTo(q(objective[i])) >= 0, "Invalid dual column");
        }
        for (int r = 0; r < rows.size(); r++) {
            check(dual[r].signum() >= 0, "Negative dual weight");
            bound = bound.add(q(dual[r]).times(rows.get(r).upper()));
            Q value = ZERO;
            for (var term : rows.get(r).terms().entrySet()) value = value.add(q(point[term.getKey()]).times(term.getValue()));
            check(value.compareTo(q(rows.get(r).upper())) <= 0, "Invalid primal row");
        }
        check(primal.equals(bound), "Primal/dual objective mismatch");
        return primal;
    }

    private static void verifyNegative(ExactRational[] weights, List<ExactLinearProgram.Constraint> rows, int n) {
        check(weights != null && weights.length == rows.size(), "Missing negative certificate");
        Q bound = ZERO;
        for (int r = 0; r < rows.size(); r++) {
            check(weights[r].signum() >= 0, "Negative Farkas multiplier");
            bound = bound.add(q(weights[r]).times(rows.get(r).upper()));
        }
        check(bound.compareTo(ZERO) < 0, "No contradiction");
        for (int i = 0; i < n; i++) {
            Q column = ZERO;
            for (int r = 0; r < rows.size(); r++) column = column.add(q(weights[r]).times(term(rows.get(r), i)));
            check(column.compareTo(ZERO) >= 0, "Invalid Farkas column");
        }
    }

    private static ExactLinearProgram.Constraint row(long upper, long a, long b) {
        return new ExactLinearProgram.Constraint(Map.of(0, z(a), 1, z(b)), z(upper));
    }
    private static BigInteger term(ExactLinearProgram.Constraint row, int id) { return row.terms().getOrDefault(id, BigInteger.ZERO); }
    private static PlanningBudget budget(long bytes) { return new PlanningBudget(0, 20_000_000, bytes, () -> false, System::nanoTime); }
    private static BigInteger z(long value) { return BigInteger.valueOf(value); }
    private static Q q(BigInteger value) { return new Q(value, BigInteger.ONE); }
    private static Q q(ExactRational value) { return new Q(value.numerator(), value.denominator()); }
    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
