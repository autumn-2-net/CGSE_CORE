// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exhaustive original-coordinate oracles for retained, compiled equation slices. */
public final class CountEquationSlicesTest {
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {}
    private record Result(BigInteger[] counts, long work, int pauses) {}

    public static void main(String[] args) {
        int sat = 0, unsat = 0, pauses = 0;
        for (int seed = 0; seed < 600; seed++) {
            var random = new Random(710013L + seed);
            var model = random(random, seed);
            boolean possible = brute(model, model.low.clone(), 0);
            for (int order = 0; order < 2; order++) {
                Collections.shuffle(model.rows, random);
                var whole = solve(model, 131072, 131072);
                var sliced = solve(model, 256, 13);
                check((whole.counts != null) == possible, "Exhaustive oracle mismatch " + seed + "/" + order);
                check(Arrays.equals(whole.counts, sliced.counts), "Continuation changed the witness " + seed);
                check(whole.work == sliced.work, "Continuation repeated preparation " + seed);
                pauses += sliced.pauses;
                if (possible) { check(valid(model, whole.counts), "Invalid witness"); sat++; }
                else unsat++;
            }
        }
        check(sat > 50 && unsat > 50 && pauses > 20, "Insufficient oracle/continuation coverage");
        var gap = gap();
        var result = solve(gap, 256, 17);
        check(result.counts != null && valid(gap, result.counts), "Missed projected interval witness");
        check(result.work < 12000, "Rescanned fixed terms or enumerated an excluded interval: " + result.work);
        limits(gap);
        System.out.println("Equation slices: 1200 exhaustive cases; SAT=" + sat + "; UNSAT=" + unsat +
                "; pauses=" + pauses + "; projected interval work=" + result.work + "; limits and disposal passed");
    }

    private static Model random(Random random, int seed) {
        int n = 3 + seed % 13;
        BigInteger[] low = new BigInteger[n], high = new BigInteger[n], point = new BigInteger[n];
        BigInteger origin = seed % 5 == 0 ? BigInteger.ONE.shiftLeft(90).negate() : BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            low[i] = origin.add(BigInteger.valueOf(random.nextInt(7) - 3));
            high[i] = low[i].add(BigInteger.valueOf(i < 3 ? 1 + random.nextInt(5) : 0));
            point[i] = low[i].add(BigInteger.valueOf(i < 3 ? random.nextInt(high[i].subtract(low[i]).intValue() + 1) : 0));
        }
        List<ExactLinearProgram.Constraint> rows = new ArrayList<>();
        Map<Integer, BigInteger> equation = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int value = random.nextInt(15) - 7;
            if (i < 3 && value == 0) value = seed % 2 == 0 ? 1 : -1;
            if (value != 0) equation.put(i, BigInteger.valueOf(value));
        }
        BigInteger rhs = dot(equation, point);
        if (seed % 3 == 0) rhs = rhs.add(BigInteger.valueOf(1 + random.nextInt(20)));
        rows.add(new ExactLinearProgram.Constraint(equation, rhs));
        Map<Integer, BigInteger> reverse = new LinkedHashMap<>();
        equation.forEach((id, value) -> reverse.put(id, value.negate()));
        rows.add(new ExactLinearProgram.Constraint(reverse, rhs.negate()));
        for (int j = 0; j < 3 + seed % 9; j++) {
            Map<Integer, BigInteger> terms = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                int value = random.nextInt(11) - 5;
                if (value != 0) terms.put(i, BigInteger.valueOf(value));
            }
            rows.add(new ExactLinearProgram.Constraint(terms, dot(terms, point).add(BigInteger.valueOf(random.nextInt(8)))));
        }
        return new Model(rows, low, high);
    }

    private static Model gap() {
        int n = 96;
        BigInteger[] low = new BigInteger[n], high = new BigInteger[n];
        Arrays.fill(low, BigInteger.valueOf(71));
        Arrays.fill(high, BigInteger.valueOf(71));
        for (int i = 0; i < 3; i++) { low[i] = BigInteger.ZERO; high[i] = BigInteger.valueOf(i == 2 ? 10000 : 50000); }
        Map<Integer, BigInteger> terms = new LinkedHashMap<>(Map.of(0, BigInteger.TWO, 1, BigInteger.valueOf(3), 2, BigInteger.valueOf(5)));
        BigInteger rhs = BigInteger.valueOf(60000);
        for (int i = 3; i < n; i++) {
            terms.put(i, BigInteger.valueOf(i));
            rhs = rhs.add(BigInteger.valueOf(i).multiply(low[i]));
        }
        Map<Integer, BigInteger> reverse = new LinkedHashMap<>();
        terms.forEach((id, value) -> reverse.put(id, value.negate()));
        var rows = new ArrayList<>(List.of(new ExactLinearProgram.Constraint(terms, rhs),
                new ExactLinearProgram.Constraint(reverse, rhs.negate()),
                new ExactLinearProgram.Constraint(Map.of(0, BigInteger.valueOf(-2), 1, BigInteger.valueOf(-3),
                        2, BigInteger.valueOf(-11)), BigInteger.valueOf(-114000))));
        // The last row and equation imply z >= 9000. Individual variable
        // bounds alone do not show this; the first 9000 slices are impossible.
        return new Model(rows, low, high);
    }

    private static Result solve(Model model, long first, long quantum) {
        var budget = budget(8_000_000, 64L << 20, new AtomicBoolean());
        int pauses = 0;
        BigInteger[] result;
        try (var search = new CountDiophantine(model.rows, model.low, model.high, budget, first).retained()) {
            for (;;) {
                while (!search.step()) {}
                if (!search.paused()) break;
                long work = budget.nodes(), bytes = budget.reservedBytes();
                for (int i = 0; i < 3; i++) check(search.step(), "Paused search resumed itself");
                check(work == budget.nodes() && bytes == budget.reservedBytes(), "Paused poll consumed resources");
                search.resume(quantum);
                check(++pauses < 20000, "Continuation stalled");
            }
            result = search.counts();
        }
        check(budget.reservedBytes() == 0, "Workspace leaked");
        return new Result(result, budget.nodes(), pauses);
    }

    private static void limits(Model model) {
        for (long memory : new long[] {1, 30000, 40000, 50000, 1L << 20}) {
            var budget = budget(1_000_000, memory, new AtomicBoolean());
            try (var search = new CountDiophantine(model.rows, model.low, model.high, budget)) {
                while (!search.step()) {}
                if (search.counts() != null) check(valid(model, search.counts()), "Memory-limited witness invalid");
            }
            check(budget.reservedBytes() == 0, "Memory refusal leaked");
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        var budget = budget(1_000_000, 64L << 20, cancelled);
        try (var search = new CountDiophantine(model.rows, model.low, model.high, budget, 256).retained()) {
            while (!search.step()) {}
            check(search.paused(), "Cancellation test needs retained state");
            cancelled.set(true);
            try { search.resume(17); search.step(); throw new AssertionError("Ignored cancellation"); }
            catch (CancellationException expected) { }
        }
        check(budget.reservedBytes() == 0, "Cancelled slices leaked");
        for (long work : new long[] {1, 64, 128}) {
            var limited = budget(work, 64L << 20, new AtomicBoolean());
            try (var search = new CountDiophantine(model.rows, model.low, model.high, limited)) {
                // Admission may decline when too little request work remains.
                while (!search.step()) {}
                check(search.counts() == null, "Tiny quota unexpectedly completed the case");
            } catch (PlanningBudget.Exhausted expected) { }
            check(limited.reservedBytes() == 0, "Request limit leaked");
        }
    }

    private static PlanningBudget budget(long work, long memory, AtomicBoolean cancelled) {
        return new PlanningBudget(0, work, memory, cancelled::get, System::nanoTime);
    }

    private static boolean brute(Model model, BigInteger[] point, int id) {
        if (id == 3) return valid(model, point);
        for (point[id] = model.low[id]; point[id].compareTo(model.high[id]) <= 0; point[id] = point[id].add(BigInteger.ONE))
            if (brute(model, point, id + 1)) return true;
        return false;
    }

    private static boolean valid(Model model, BigInteger[] point) {
        for (int i = 0; i < point.length; i++) if (point[i].compareTo(model.low[i]) < 0 || point[i].compareTo(model.high[i]) > 0) return false;
        for (var row : model.rows) if (dot(row.terms(), point).compareTo(row.upper()) > 0) return false;
        return true;
    }

    private static BigInteger dot(Map<Integer, BigInteger> row, BigInteger[] point) {
        BigInteger value = BigInteger.ZERO;
        for (var term : row.entrySet()) value = value.add(term.getValue().multiply(point[term.getKey()]));
        return value;
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
