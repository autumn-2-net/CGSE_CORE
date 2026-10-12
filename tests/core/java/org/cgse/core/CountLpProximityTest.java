// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Full Boolean enumeration checks the neighborhood independently of its join/tree. */
public final class CountLpProximityTest {
    private static int witnesses, exhausted;

    public static void main(String[] args) throws Exception {
        for (int sample = 0; sample < 1200; sample++) random(sample);
        for (int sample = 0; sample < 600; sample++) equalities(sample);
        equalityCollisions();
        factoredEqualities();
        threeFlips();
        limits();
        changedScope();
        handoffs();
        sharedAdmission();
        check(witnesses > 300 && exhausted > 200, "Insufficient oracle coverage");
        System.out.println("LP proximity: 1800 exact Boolean oracles, row orders, scaled rows, equality collisions, three-flip joins, " +
                "scope changes, worker handoffs, limits and cleanup; witnesses=" + witnesses + "; exhausted=" + exhausted);
    }

    private static void equalities(int sample) {
        var random = new Random(121220000L + sample);
        int n = 4 + random.nextInt(7), plant = random.nextInt(1 << n);
        var low = filled(n, 0); var high = filled(n, 1);
        var point = new double[n];
        for (int i = 0; i < n; i++) {
            point[i] = random.nextInt(3) == 0 ? .5 : random.nextInt(2);
            if (sample % 4 == 0 && random.nextInt(4) == 0) low[i] = high[i] = BigInteger.valueOf((plant >>> i) & 1);
        }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int r = 0, m = 1 + random.nextInt(4); r < m; r++) {
            var terms = new TreeMap<Integer, BigInteger>();
            var opposite = new TreeMap<Integer, BigInteger>();
            long rhs = 0;
            for (int i = 0; i < n; i++) {
                long coefficient = random.nextInt(19) - 9;
                terms.put(i, BigInteger.valueOf(coefficient));
                if ((plant & (1 << i)) != 0) rhs += coefficient;
            }
            var scale = BigInteger.ONE.shiftLeft(sample % 7 == 0 ? 90 : 0);
            terms.forEach((i, a) -> opposite.put(i, a.negate().multiply(scale)));
            rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(rhs)));
            // Different scaling on the opposite row must not change its equality.
            rows.add(new ExactLinearProgram.Constraint(opposite, BigInteger.valueOf(-rhs).multiply(scale)));
        }
        // Equal signatures still have to satisfy every remaining inequality.
        for (int r = 0; r < 3; r++) rows.add(row(random.nextInt(5) - 2, random.nextInt(n), random.nextInt(11) - 5));
        boolean expected = false;
        for (int bits = 0; bits < 1 << n; bits++) {
            var value = decode(bits, n);
            expected |= inNeighborhood(value, low, high, point) && valid(rows, low, high, value);
        }
        for (int order = 0; order < 2; order++) {
            if (order != 0) Collections.shuffle(rows, random);
            var budget = budget(8_000_000, 64L << 20);
            try (var search = CountLpProximity.create(rows, low, high, point, budget)) {
                if (search == null) continue; // More than eight fractional coordinates are outside this tool.
                while (!search.step()) {}
                var value = search.counts();
                check((value != null) == expected, "Equality neighborhood mismatch " + sample + " " + budget.diagnostics());
                if (value != null) check(valid(rows, low, high, value) && inNeighborhood(value, low, high, point), "Invalid equality witness");
            }
            check(budget.reservedBytes() == 0, "Equality run leaked");
        }
    }

    private static void equalityCollisions() {
        // Long.hashCode(1) == Long.hashCode(2^32). A bucket hit is not evidence
        // that the equality holds, nor that the other inequalities hold.
        var terms = Map.of(0, BigInteger.ONE, 1, BigInteger.ONE.shiftLeft(32));
        var negative = new TreeMap<Integer, BigInteger>();
        terms.forEach((i, a) -> negative.put(i, a.negate()));
        var rows = new ArrayList<>(List.of(new ExactLinearProgram.Constraint(terms, BigInteger.ONE),
                new ExactLinearProgram.Constraint(negative, BigInteger.ONE.negate())));
        for (int allowed = 0; allowed <= 1; allowed++) {
            var constrained = new ArrayList<>(rows); constrained.add(row(allowed, 0, 1));
            var budget = budget(8_000_000, 64L << 20);
            try (var search = CountLpProximity.create(constrained, filled(2, 0), filled(2, 1), new double[] {.5,.5}, budget)) {
                while (!search.step()) {}
                check((search.counts() != null) == (allowed == 1), "Collision bypassed a material row");
                if (allowed == 1) check(Arrays.equals(search.counts(), new BigInteger[] {BigInteger.ONE, BigInteger.ZERO}), "Collision selected wrong witness");
            }
            check(budget.reservedBytes() == 0, "Collision run leaked");
        }
        // Opposite coefficients with different endpoints form a band, not an equality.
        var band = List.of(row(2, 0, 1, 1, 1), row(-1, 0, -1, 1, -1), row(-2, 0, -1, 1, -1));
        var budget = budget(8_000_000, 64L << 20);
        try (var search = CountLpProximity.create(band, filled(2, 0), filled(2, 1), new double[] {.5,.5}, budget)) {
            while (!search.step()) {}
            check(Arrays.equals(search.counts(), filled(2, 1)), "A band was collapsed to its wrong endpoint");
        }
        check(budget.reservedBytes() == 0, "Band run leaked");
    }

    private static void factoredEqualities() {
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int r = 0; r < 8; r++) {
            var terms = new TreeMap<Integer, BigInteger>();
            var opposite = new TreeMap<Integer, BigInteger>();
            for (int i = r; i < 32; i += 8) {
                terms.put(i, BigInteger.ONE); opposite.put(i, BigInteger.ONE.negate());
            }
            rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(4)));
            rows.add(new ExactLinearProgram.Constraint(opposite, BigInteger.valueOf(-4)));
        }
        double[] point = new double[32]; Arrays.fill(point, 0, 8, .5);
        var budget = budget(8_000_000, 64L << 20);
        try (var search = CountLpProximity.create(rows, filled(32, 0), filled(32, 1), point, budget)) {
            while (!search.step()) {}
            check(search.counts() == null && budget.diagnostics().contains("neighborhood_exhausted"), "Equality join never reached its queries");
            check(budget.nodes() < 65000, "Repeated equality combinations consumed the scout cap");
            check(budget.reservedBytes() < 250000, "Factored equality table retained Cartesian workspace");
        }
        // The original model is feasible (all ones), outside this neighborhood.
        check(valid(rows, filled(32, 0), filled(32, 1), filled(32, 1)), "Bad restricted-exhaustion oracle");
        check(budget.reservedBytes() == 0, "Factored equality run leaked");
        for (int stop = 25; stop <= 2500; stop += 25) {
            int until = stop; var calls = new AtomicInteger();
            var cancelled = new PlanningBudget(0, 8_000_000, 64L << 20, () -> calls.incrementAndGet() >= until, System::nanoTime);
            try (var search = CountLpProximity.create(rows, filled(32, 0), filled(32, 1), point, cancelled)) {
                if (search != null) while (!search.step()) {}
            } catch (CancellationException expected) {}
            check(cancelled.reservedBytes() == 0, "Partial equality index leaked on cancellation");
        }
    }

    private static void random(int sample) {
        var random = new Random(121210000L + sample);
        int n = 3 + random.nextInt(8), plant = random.nextInt(1 << n);
        var low = filled(n, 0);
        var high = filled(n, 1);
        var point = new double[n];
        for (int i = 0; i < n; i++) {
            point[i] = random.nextInt(3) == 0 ? 0.25 + random.nextInt(3) / 4.0 : random.nextBoolean() ? 0 : 1;
            if (sample % 5 == 0 && random.nextInt(4) == 0) low[i] = high[i] = BigInteger.valueOf((plant >>> i) & 1);
        }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int r = 0, m = 2 + random.nextInt(7); r < m; r++) {
            var terms = new TreeMap<Integer, BigInteger>();
            long rhs = sample % 3 == 0 ? random.nextInt(15) - 10 : random.nextInt(5);
            for (int i = 0; i < n; i++) {
                int a = random.nextInt(31) - 15;
                if (a != 0) terms.put(i, BigInteger.valueOf(a));
                if (sample % 3 != 0 && (plant & (1 << i)) != 0) rhs += a;
            }
            BigInteger scale = BigInteger.ONE.shiftLeft(sample % 11 == 0 ? 90 : 0);
            terms.replaceAll((i, a) -> a.multiply(scale));
            // Negative nonmultiples verify floor normalization, not truncation.
            rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(rhs).multiply(scale)
                    .add(sample % 11 == 0 ? scale.subtract(BigInteger.ONE) : BigInteger.ZERO)));
        }
        boolean expected = false;
        for (int bits = 0; bits < 1 << n; bits++) {
            var value = decode(bits, n);
            if (inNeighborhood(value, low, high, point) && valid(rows, low, high, value)) expected = true;
        }
        for (int permutation = 0; permutation < 2; permutation++) {
            if (permutation != 0) Collections.shuffle(rows, random);
            var budget = budget(8_000_000, 64L << 20);
            var weights = new TreeMap<Integer, BigInteger>();
            if (permutation != 0) for (int i = 0; i < n; i++)
                weights.put(i, BigInteger.valueOf(random.nextInt(21) - 10).shiftLeft(sample % 7 == 0 ? 90 : 0));
            var preference = permutation == 0 ? null : new ExactLinearProgram.Constraint(weights, BigInteger.ZERO);
            try (var search = CountLpProximity.create(rows, low, high, point, preference, budget)) {
                if (search == null) {
                    check(Arrays.stream(point).filter(v -> v > 1e-6 && v < 1 - 1e-6).count() > 8, "Unexpected refusal");
                    continue;
                }
                int steps = 0;
                while (!search.step()) check(++steps < 10000, "No progress");
                var value = search.counts();
                check((value != null) == expected, "Neighborhood mismatch sample=" + sample + " " + budget.diagnostics());
                if (value != null) {
                    check(valid(rows, low, high, value), "Invalid original rows");
                    check(inNeighborhood(value, low, high, point), "Witness outside declared neighborhood");
                    if (permutation == 0) witnesses++;
                } else if (permutation == 0) exhausted++;
            }
            check(budget.reservedBytes() == 0, "Oracle run leaked");
        }
    }

    private static void threeFlips() {
        // Exactly three integral coordinates must change. Fractional coordinates
        // remain free; all rows participate in the join, including signed rows.
        var rows = List.of(row(-3, 0, -1, 1, -1, 2, -1), row(1, 3, 1, 4, 1), row(-1, 3, -1, 4, -1));
        var low = filled(5, 0); var high = filled(5, 1);
        var point = new double[] {0, 0, 0, 0.5, 0.5};
        var budget = budget(8_000_000, 64L << 20);
        try (var search = CountLpProximity.create(rows, low, high, point, budget)) {
            while (!search.step()) {}
            check(search.counts() != null && valid(rows, low, high, search.counts()), "Three changes were omitted");
        }
        // A fourth integral change is outside this tool; it must return no
        // witness and let the complete search handle this feasible problem.
        var four = List.of(row(-4, 0, -1, 1, -1, 2, -1, 3, -1));
        try (var search = CountLpProximity.create(four, filled(4, 0), filled(4, 1), new double[4], budget)) {
            while (!search.step()) {}
            check(search.counts() == null, "Restricted exhaustion became a witness");
        }
        check(budget.reservedBytes() == 0, "Three-flip run leaked");
    }

    private static void limits() {
        var rows = List.of(row(-3, 0, -2, 1, -2, 2, -2), row(3, 0, 2, 1, 2, 2, 2));
        for (long bytes : new long[] {1, 512, 4096, 1 << 20}) for (long work : new long[] {1, 1024, 1000000}) {
            var budget = budget(work, bytes);
            try (var search = CountLpProximity.create(rows, filled(3, 0), filled(3, 1), new double[] {.5,.5,.5}, budget)) {
                if (search != null) {
                    while (!search.step()) {}
                    check(search.counts() == null, "Infeasible integer parity accepted");
                }
            }
            check(budget.reservedBytes() == 0, "Refused optional workspace leaked");
        }
        for (int cancel = 1; cancel <= 180; cancel++) {
            int stop = cancel;
            var checks = new AtomicInteger();
            var budget = new PlanningBudget(0, 8_000_000, 64L << 20, () -> checks.incrementAndGet() >= stop, System::nanoTime);
            try (var search = CountLpProximity.create(rows, filled(3, 0), filled(3, 1), new double[] {.5,.5,.5}, budget)) {
                if (search != null) while (!search.step()) {}
            } catch (CancellationException expected) {}
            check(budget.reservedBytes() == 0, "Cancellation leaked");
        }
        for (BigInteger coefficient : List.of(BigInteger.ONE.shiftLeft(61), BigInteger.ONE.shiftLeft(90))) {
            var wide = List.of(new ExactLinearProgram.Constraint(Map.of(0, coefficient, 1, BigInteger.ONE), coefficient));
            var budget = budget(8_000_000, 64L << 20);
            try (var search = CountLpProximity.create(wide, filled(2, 0), filled(2, 1), new double[] {.5,.5}, budget)) {
                while (!search.step()) {}
                check(search.counts() == null, "Unbounded long arithmetic admitted");
            }
            check(budget.reservedBytes() == 0, "Wide refusal leaked");
        }
        var budget = budget(8_000_000, 64L << 20);
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            check(CountLpProximity.create(rows, filled(3, 0), filled(3, 1), new double[] {bad, 0, 0}, budget) == null, "Nonfinite proposal");
        check(budget.reservedBytes() == 0, "Invalid proposal leaked");

        // A large admissible neighborhood must stop at its private allowance,
        // leave the global search alive and release its partially built table.
        var dense = new ArrayList<ExactLinearProgram.Constraint>();
        for (int r = 0; r < 16; r++) {
            var terms = new TreeMap<Integer, BigInteger>();
            for (int i = 0; i < 128; i++) terms.put(i, BigInteger.valueOf((i * 17 + r * 11) % 29 - 14));
            dense.add(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(-13)));
        }
        var point = new double[128]; Arrays.fill(point, 0, 8, .5);
        try (var search = CountLpProximity.create(dense, filled(128, 0), filled(128, 1), point, budget)) {
            check(search != null, "Large bounded neighborhood was not admitted");
            while (!search.step()) {}
            check(search.counts() == null && budget.diagnostics().contains("local_work_limit"), "Private cap not respected");
        }
        check(budget.nodes() < 200000 && budget.remainingWork() > 7_000_000, "Optional scout exhausted the request");
        check(budget.reservedBytes() == 0, "Partial neighborhood leaked");
    }

    private static void changedScope() {
        var budget = budget(8_000_000, 64L << 20);
        var rows = List.of(row(-2, 0, -1, 1, -1));
        for (int high = 0; high <= 1; high++) {
            try (var search = CountLpProximity.create(rows, filled(2, 0), filled(2, high), new double[] {.5,.5}, budget)) {
                while (!search.step()) {}
                check((search.counts() != null) == (high == 1), "Domain scope was reused incorrectly");
            }
        }
        try (var solver = new CountLcg(List.of(row(1, 0, 1, 1, 1)), filled(2, 0), filled(2, 1), budget, 1024)) {
            check(!solver.offerCandidate(new BigInteger[] {BigInteger.ONE, BigInteger.ONE}), "Candidate escaped original row");
            check(!solver.offerCandidate(new BigInteger[] {BigInteger.valueOf(-1), BigInteger.ZERO}), "Candidate escaped domain");
            solver.learn(new CountConflict(List.of(row(0, 0, 1)))); // Exclude x0=0.
            check(!solver.offerCandidate(filled(2, 0)), "Candidate ignored imported exclusion");
            check(solver.offerCandidate(new BigInteger[] {BigInteger.ONE, BigInteger.ZERO}), "Valid offer refused");
        }
        check(budget.reservedBytes() == 0, "Scope run leaked");
    }

    private static void handoffs() throws Exception {
        int n = 20;
        var terms = new TreeMap<Integer, BigInteger>();
        for (int i = 0; i < n; i++) terms.put(i, BigInteger.valueOf(-(i + 1)));
        var rows = List.of(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(-113)), row(3, 0, 2, 1, 2, 2, 2));
        double[] point = new double[n];
        for (int i = 0; i < 5; i++) point[i] = .5;
        long expectedWork = 0;
        BigInteger[] expected = null;
        var worker = Executors.newSingleThreadExecutor();
        try {
            for (int mode = 0; mode < 2; mode++) {
                var budget = budget(16_000_000, 64L << 20);
                long unrelated = 0;
                try (var search = CountLpProximity.create(rows, filled(n, 0), filled(n, 1), point, budget)) {
                    int visits = 0;
                    while (true) {
                        boolean done = mode == 1 && (visits++ & 1) == 0 ? worker.submit(search::step).get() : search.step();
                        if (done) break;
                        // Another strategy's work between visits must not consume
                        // this neighborhood's private allowance a second time.
                        if (mode == 1) { budget.charge(2000); unrelated += 2000; }
                    }
                    if (mode == 0) { expected = search.counts(); expectedWork = budget.nodes(); }
                    else {
                        check(Arrays.equals(expected, search.counts()), "Worker handoff changed the search");
                        check(Math.abs(expectedWork - (budget.nodes() - unrelated)) <= 2, "Wrong local work owner");
                    }
                }
                check(budget.reservedBytes() == 0, "Handoff leaked");
            }
        } finally { worker.shutdownNow(); }
    }

    private static void sharedAdmission() throws Exception {
        var workers = Executors.newFixedThreadPool(4);
        try {
            for (int request = 0; request < 8; request++) {
                var budget = budget(8_000_000, 64L << 20);
                var calls = new ArrayList<Callable<Boolean>>();
                for (int i = 0; i < 32; i++) calls.add(budget::claimLpProximity);
                int admitted = 0;
                for (var result : workers.invokeAll(calls)) if (result.get()) admitted++;
                check(admitted == 1, "Equivalent workers multiplied the root scout allowance");
                check(!budget.claimLpProximity(), "Repeated root view reclaimed a probe");
            }
        } finally { workers.shutdownNow(); }
    }

    private static boolean inNeighborhood(BigInteger[] value, BigInteger[] low, BigInteger[] high, double[] point) {
        int changes = 0;
        for (int i = 0; i < value.length; i++) {
            if (value[i].compareTo(low[i]) < 0 || value[i].compareTo(high[i]) > 0) return false;
            if (low[i].equals(high[i]) || point[i] > 1e-6 && point[i] < 1 - 1e-6) continue;
            if (value[i].intValue() != (point[i] >= .5 ? 1 : 0)) changes++;
        }
        return changes <= 3;
    }

    private static boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high, BigInteger[] value) {
        for (int i = 0; i < value.length; i++) if (value[i].compareTo(low[i]) < 0 || value[i].compareTo(high[i]) > 0) return false;
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(value[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private static BigInteger[] decode(int bits, int n) {
        var result = new BigInteger[n];
        for (int i = 0; i < n; i++) result[i] = BigInteger.valueOf((bits >>> i) & 1);
        return result;
    }

    private static BigInteger[] filled(int n, long value) {
        var result = new BigInteger[n]; Arrays.fill(result, BigInteger.valueOf(value)); return result;
    }

    private static ExactLinearProgram.Constraint row(long rhs, int... pairs) {
        var terms = new TreeMap<Integer, BigInteger>();
        for (int i = 0; i < pairs.length; i += 2) terms.put(pairs[i], BigInteger.valueOf(pairs[i + 1]));
        return new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(rhs));
    }

    private static PlanningBudget budget(long work, long bytes) { return new PlanningBudget(0, work, bytes, () -> false, System::nanoTime); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
