// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent integer row oracle for optional structured count candidates. */
public final class CountDemandProjectionTest {
    private static int checks;
    public static void main(String[] args) {
        for (int length : new int[] {4, 40, 550}) {
            var rows = chain(length, BigInteger.valueOf(64));
            var counts = solve(length + 2, rows, 16_000_000, 128L << 20);
            check(counts != null && valid(rows, counts), "Finite mixed-source chain lost its witness, length=" + length);
            check(counts[length].signum() > 0 && counts[length + 1].signum() > 0, "Capacity ignored one required source");
        }
        var repeated = new ArrayList<>(chain(550, BigInteger.valueOf(64)));
        repeated.add(repeated.get(0)); // Forced gross production repeats a terminal target's net row.
        repeated.add(repeated.get(0));
        var repeatedCounts = solve(552, repeated, 16_000_000, 128L << 20);
        check(repeatedCounts != null && valid(repeated, repeatedCounts), "Repeated goal rows changed source projection");
        var coproduct = List.of(row(Map.of(0, -3), -9), row(Map.of(0, -1, 1, -2), -20), row(Map.of(0, 1, 1, 1), 20));
        var mixed = solve(2, coproduct, 2_000_000, 16L << 20);
        check(mixed != null && valid(coproduct, mixed), "A coproduct equality discarded another output obligation");
        var cycle = List.of(row(Map.of(0, -2, 1, 1), -10), row(Map.of(0, 1, 1, -2, 2, -1), 0));
        var feedback = solve(3, cycle, 2_000_000, 16L << 20);
        check(feedback != null && valid(cycle, feedback), "Rational feedback restoration escaped integer rows");
        var aliases = List.of(row(Map.of(0, -1, 1, -1, 2, -2), -9),
                row(Map.of(0, 1, 1, 1), 4), row(Map.of(2, 1), 3));
        var aliased = solve(3, aliases, 2_000_000, 16L << 20);
        check(aliased != null && valid(aliases, aliased) && aliased[1].signum() == 0,
                "Equivalent columns duplicated a capacity or lost a required source");
        var scc = List.of(row(Map.of(0, -2, 1, 1, 2, 1), 0), row(Map.of(0, 1, 1, -2, 2, 3), 0),
                row(Map.of(2, -1, 3, -1), -10), row(Map.of(3, 1), 2));
        var coupled = solve(4, scc, 2_000_000, 16L << 20);
        check(coupled != null && valid(scc, coupled), "Exact SCC elimination or residual repair lost a fractional coupled witness");
        var constantChain = List.of(row(Map.of(0, -1), -64), row(Map.of(0, 1, 1, -3), 0));
        var constant = solve(2, constantChain, 2_000_000, 16L << 20);
        check(constant != null && valid(constantChain, constant), "Fully eliminated chain declined its constant candidate");
        var constantScc = List.of(row(Map.of(0, -2, 1, 3), -1), row(Map.of(0, 1, 1, -4), 0));
        var constantFeedback = solve(2, constantScc, 2_000_000, 16L << 20);
        check(constantFeedback != null && valid(constantScc, constantFeedback), "Fully eliminated SCC skipped exact integer repair");
        var huge = BigInteger.ONE.shiftLeft(90).add(BigInteger.valueOf(37));
        var exactConstantRows = List.of(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE.negate()), huge.negate()));
        var exactConstant = solve(1, exactConstantRows, 2_000_000, 16L << 20);
        check(exactConstant != null && exactConstant[0].equals(huge), "Constant projection lost precision beyond long");
        var wide = List.of(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE.negate(), 1, BigInteger.ONE.negate()), huge.negate()),
                new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE), huge.subtract(BigInteger.valueOf(100))),
                new ExactLinearProgram.Constraint(Map.of(1, BigInteger.ONE), BigInteger.valueOf(200)));
        var exact = solve(2, wide, 2_000_000, 16L << 20);
        // The floating phase may decline, but cannot publish its rounded point.
        check(exact == null || valid(wide, exact), "Large exact capacity accepted an unchecked numerical point");
        check(solve(42, chain(40, BigInteger.valueOf(64)), 20, 16L << 20) == null, "Local allowance fabricated a candidate");
        check(solve(42, chain(40, BigInteger.valueOf(64)), 2_000_000, 16) == null, "Memory refusal fabricated a candidate");
        var impossible = List.of(row(Map.of(0, -1, 1, -1), -10), row(Map.of(0, 1), 3), row(Map.of(1, 1), 3));
        check(solve(2, impossible, 2_000_000, 16L << 20) == null, "Contradictory capacities fabricated a candidate");
        exactCapacityRestoration();
        wideNumericAdmission();
        pivotStockBoundary();
        cancellation();
        System.out.println("CountDemandProjectionTest PASS: checks=" + checks);
    }

    private static void exactCapacityRestoration() {
        for (boolean wide : List.of(false, true)) for (boolean alias : List.of(false, true)) for (int order = 0; order < 3; order++) {
            int length = wide ? 17 : 39;
            BigInteger scale = wide ? BigInteger.ONE.shiftLeft(65).add(BigInteger.valueOf(3)) : BigInteger.ONE;
            BigInteger first = scale.multiply(BigInteger.valueOf(wide ? 16 : 11));
            BigInteger second = scale.multiply(BigInteger.valueOf(wide ? 13 : 2));
            BigInteger demand = first.multiply(BigInteger.TWO).add(second.multiply(BigInteger.valueOf(3)));
            int variables = length + (alias ? 3 : 2);
            var original = new ArrayList<ExactLinearProgram.Constraint>();
            original.add(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE.negate()), demand.negate()));
            for (int i = 0; i + 1 < length; i++) original.add(row(Map.of(i, 1, i + 1, -1), 0));
            var sources = new LinkedHashMap<Integer, BigInteger>();
            sources.put(length - 1, BigInteger.ONE); sources.put(length, BigInteger.valueOf(-2)); sources.put(length + 1, BigInteger.valueOf(-3));
            var capacity = new LinkedHashMap<Integer, BigInteger>(); capacity.put(length, BigInteger.ONE);
            if (alias) { sources.put(length + 2, BigInteger.valueOf(-2)); capacity.put(length + 2, BigInteger.ONE); }
            original.add(new ExactLinearProgram.Constraint(sources, BigInteger.ZERO));
            original.add(new ExactLinearProgram.Constraint(capacity, first));
            original.add(new ExactLinearProgram.Constraint(Map.of(length + 1, BigInteger.ONE), second));

            var columns = new ArrayList<Integer>(); for (int i = 0; i < variables; i++) columns.add(i);
            if (order == 1) Collections.reverse(columns);
            else if (order == 2) Collections.shuffle(columns, new Random(20261022));
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (var constraint : original) {
                var terms = new LinkedHashMap<Integer, BigInteger>();
                constraint.terms().forEach((k, v) -> terms.put(columns.get(k), v));
                rows.add(new ExactLinearProgram.Constraint(terms, constraint.upper()));
            }
            if (order == 1) Collections.reverse(rows);
            else if (order == 2) Collections.shuffle(rows, new Random(20261023));
            // The small case can produce the double 11.000000000000002. The
            // large case cannot represent either source capacity in a double.
            // Both have exact required totals, not a tolerance-sized allowance.
            var counts = solve(variables, rows, 2_000_000, 16L << 20);
            check(counts != null && valid(rows, counts), "Tight exact capacities rejected a feasible restored point; wide=" + wide + "; alias=" + alias + "; order=" + order);
            BigInteger actualFirst = counts[columns.get(length)];
            if (alias) actualFirst = actualFirst.add(counts[columns.get(length + 2)]);
            check(actualFirst.equals(first) && counts[columns.get(length + 1)].equals(second), "Restoration changed a proven integer capacity");
            for (int i = 0; i < length; i++) check(counts[columns.get(i)].equals(demand), "Restored chain lost its exact demand");
        }
    }

    private static void wideNumericAdmission() {
        int variables = 768;
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int i = 0; i < variables; i++) rows.add(row(Map.of(i, 1), 3));
        for (int i = 0; i < variables; i++) rows.add(row(Map.of(i, -1), i == 0 ? -1 : 0));
        var objective = new BigInteger[variables]; Arrays.fill(objective, BigInteger.ZERO);
        // More than a million cells, but below the separate wide projection's
        // cap. The 10 MiB case fits a cold tableau but cannot retain its matrix.
        for (long bytes : new long[]{10L << 20, 32L << 20}) {
            var budget = new PlanningBudget(0, 8_000_000, bytes, () -> false, System::nanoTime);
            check(CountNumericRelaxation.solve(variables, rows, objective, budget, 2_000_000) == null,
                    "Wide projection changed ordinary branch admission");
            try (var session = new CountNumericRelaxation.Session(true, true)) {
                for (int demand : new int[]{1, 2}) {
                    rows.set(variables, row(Map.of(0, -1), -demand));
                    CountNumericRelaxation.Result proposal;
                    do { proposal = session.solve(variables, rows, objective, budget, 2_000_000); }
                    while (proposal == null && session.pending());
                    check(proposal != null && proposal.point() != null && !proposal.phaseOneInfeasible(),
                            "Funded wide tableau or its cold fallback was declined");
                    var exact = new BigInteger[variables];
                    for (int i = 0; i < variables; i++) exact[i] = java.math.BigDecimal.valueOf(proposal.point()[i]).toBigIntegerExact();
                    check(valid(rows, exact), "Wide proposal failed the original exact integer rows");
                }
            }
            check(budget.reservedBytes() == 0 && budget.peakBytes() <= bytes, "Wide tableau escaped its memory account");
        }
        var denied = new PlanningBudget(0, 8_000_000, 4096, () -> false, System::nanoTime);
        try (var session = new CountNumericRelaxation.Session(true, true)) {
            check(session.solve(variables, rows, objective, denied, 2_000_000) == null && !session.pending(),
                    "Memory refusal retained a wide numeric continuation");
        }
        check(denied.reservedBytes() == 0, "Refused wide tableau retained memory");
        var oversized = new ArrayList<ExactLinearProgram.Constraint>();
        for (int i = 0; i < 2048; i++) oversized.add(row(Map.of(i % 1024, 1), 3));
        var largeObjective = new BigInteger[1024]; Arrays.fill(largeObjective, BigInteger.ZERO);
        var cap = new PlanningBudget(0, 8_000_000, 128L << 20, () -> false, System::nanoTime);
        try (var session = new CountNumericRelaxation.Session(true, true)) {
            check(session.solve(1024, oversized, largeObjective, cap, 2_000_000) == null && !session.pending(),
                    "Wide projection bypassed its total cell cap");
        }
        check(cap.peakBytes() == 0, "Rejected dimensions allocated a tableau");
    }

    private static void pivotStockBoundary() {
        // Integer lower bounds require one run of A's producer at amount 6,
        // but making both material inequalities tight fixes it at -1/22.
        // Freeing that optional equality must preserve the original inequalities.
        for (int copies : new int[]{1, 4}) for (int amount : new int[]{4, 6, 8, 12}) for (int order = 0; order < 3; order++) {
            var rows = pivotRows(copies, amount);
            if (order == 1) Collections.reverse(rows);
            if (order == 2) Collections.shuffle(rows, new Random(20261024));
            var known = new BigInteger[5 * copies];
            for (int i = 0; i < copies; i++) {
                known[5 * i] = known[5 * i + 2] = BigInteger.valueOf(10L * amount);
                known[5 * i + 1] = known[5 * i + 3] = known[5 * i + 4] = BigInteger.valueOf(amount);
            }
            check(valid(rows, known), "Synthetic boundary lost its independent feasible count witness");
            var budget = new PlanningBudget(0, 2_000_000, 16L << 20, () -> false, System::nanoTime);
            try (var projection = new CountDemandProjection(known.length, rows, budget, 100_000, 512, true)) {
                while (!projection.step()) {}
                check(projection.result() != null && valid(rows, projection.result()),
                        "Optional equality discarded funded integer rows: " + copies + "/" + amount + "/" + order);
                if (amount == 6) check(budget.diagnostics().contains("count_demand_repivot"), "Control skipped the conflicting equality");
            }
            check(budget.reservedBytes() == 0, "Repivoted projection retained workspace");
        }
        var rows = pivotRows(4, 6);
        for (long cap : new long[]{100, 300, 600}) {
            var budget = new PlanningBudget(0, 2_000_000, 16L << 20, () -> false, System::nanoTime);
            try (var projection = new CountDemandProjection(20, rows, budget, cap, 512, true)) {
                while (!projection.step()) {}
                check(projection.result() == null, "Tiny projection allowance fabricated complete counts");
                check(projection.work() <= cap + 1, "Repivot replenished its work allowance");
            }
            check(budget.reservedBytes() == 0, "Bounded repivot retained workspace");
        }
        for (long bytes : new long[]{1, 4096, 8192, 32768, 65536}) {
            var budget = new PlanningBudget(0, 2_000_000, bytes, () -> false, System::nanoTime);
            try (var projection = new CountDemandProjection(20, rows, budget, 100_000, 512, true)) {
                while (!projection.step()) {}
                check(projection.result() == null || valid(rows, projection.result()), "Memory refusal published invalid counts");
            }
            check(budget.reservedBytes() == 0 && budget.peakBytes() <= bytes, "Repivot escaped memory accounting");
        }
        var cancelled = new AtomicBoolean();
        var budget = new PlanningBudget(0, 2_000_000, 16L << 20, cancelled::get, System::nanoTime);
        try (var projection = new CountDemandProjection(20, rows, budget, 100_000, 512, true)) {
            while (!budget.diagnostics().contains("count_demand_repivot")) check(!projection.step(), "Repivot cancellation control ended early");
            cancelled.set(true);
            try { projection.step(); throw new AssertionError("Repivot swallowed cancellation"); }
            catch (java.util.concurrent.CancellationException expected) { checks++; }
            check(projection.result() == null, "Cancelled repivot published incomplete counts");
        }
        check(budget.reservedBytes() == 0, "Cancelled repivot retained workspace");
    }

    private static ArrayList<ExactLinearProgram.Constraint> pivotRows(int copies, int amount) {
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int i = 0; i < copies; i++) {
            int a = 5 * i, b = a + 1, c = a + 2, d = a + 3, target = a + 4;
            rows.add(row(Map.of(target, -1), -amount));
            rows.add(row(Map.of(a, -5, c, 3, target, 3), 29));
            rows.add(row(Map.of(b, -5, target, 4), 0));
            rows.add(row(Map.of(a, 1, c, -5, target, 3), 0));
            rows.add(row(Map.of(b, 2, d, -3, target, 2), 26));
        }
        return rows;
    }

    private static List<ExactLinearProgram.Constraint> chain(int length, BigInteger amount) {
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        rows.add(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE.negate()), amount.negate()));
        for (int i = 0; i + 1 < length; i++) rows.add(row(Map.of(i, 1, i + 1, -1), 0));
        rows.add(row(Map.of(length - 1, 1, length, -2, length + 1, -3), 0));
        rows.add(row(Map.of(length, 1), 11)); rows.add(row(Map.of(length + 1, 1), 20));
        return rows;
    }

    private static BigInteger[] solve(int variables, List<ExactLinearProgram.Constraint> rows, long work, long bytes) {
        var budget = new PlanningBudget(0, 64_000_000, bytes, () -> false, System::nanoTime);
        BigInteger[] result;
        try (var projection = new CountDemandProjection(variables, rows, budget, work)) {
            while (!projection.step()) {}
            result = projection.result();
        }
        check(budget.reservedBytes() == 0, "Projection retained memory");
        return result;
    }

    private static void cancellation() {
        for (int stop : new int[] {1, 10, 50}) {
            var flag = new AtomicBoolean(); var budget = new PlanningBudget(0, 64_000_000, 64L << 20, flag::get, System::nanoTime);
            try (var projection = new CountDemandProjection(552, chain(550, BigInteger.valueOf(64)), budget, 8_000_000)) {
                for (int i = 0; i < stop; i++) check(!projection.step(), "Cancellation control ended before its stop");
                flag.set(true);
                try { projection.step(); throw new AssertionError("Projection swallowed cancellation"); }
                catch (java.util.concurrent.CancellationException expected) { checks++; }
                check(projection.result() == null, "Cancelled projection published unfinished counts");
            }
            check(budget.reservedBytes() == 0, "Cancelled projection retained memory");
        }
    }

    private static ExactLinearProgram.Constraint row(Map<Integer, Integer> terms, long upper) {
        var values = new TreeMap<Integer, BigInteger>(); terms.forEach((i, a) -> values.put(i, BigInteger.valueOf(a)));
        return new ExactLinearProgram.Constraint(values, BigInteger.valueOf(upper));
    }
    private static boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] values) {
        for (var value : values) if (value == null || value.signum() < 0) return false;
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var t : row.terms().entrySet()) sum = sum.add(t.getValue().multiply(values[t.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }
    private static void check(boolean condition, String detail) { checks++; if (!condition) throw new AssertionError(detail); }
}
