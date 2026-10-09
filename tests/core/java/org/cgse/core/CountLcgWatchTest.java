// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/** Exhaustive model oracle for event-driven learned-bound propagation. */
public final class CountLcgWatchTest {
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {}
    private static int checked, resumptions, imported;

    public static void main(String[] args) {
        Random random = new Random(810_084);
        for (int trial = 0; trial < 600; trial++) {
            boolean binary = trial >= 300;
            int n = binary ? 11 : 6, width = binary ? 2 : 3;
            BigInteger offset = trial % 7 == 0 ? BigInteger.ONE.shiftLeft(80) : BigInteger.ZERO;
            BigInteger[] low = new BigInteger[n], high = new BigInteger[n];
            Arrays.fill(low, offset);
            Arrays.fill(high, offset.add(BigInteger.valueOf(width - 1)));
            List<ExactLinearProgram.Constraint> rows = new ArrayList<>();
            for (int j = 0; j < (binary ? 48 : 9); j++) {
                Map<Integer, BigInteger> terms = new LinkedHashMap<>();
                BigInteger bound;
                if (binary) {
                    // Random signed 3-CNF represented as exact inequalities.
                    int negatives = 0;
                    while (terms.size() < 3) {
                        int id = random.nextInt(n);
                        if (terms.containsKey(id)) continue;
                        int sign = random.nextBoolean() ? 1 : -1;
                        terms.put(id, BigInteger.valueOf(sign));
                        if (sign < 0) negatives++;
                    }
                    bound = BigInteger.valueOf(2 - negatives);
                } else {
                    for (int id = 0; id < n; id++) {
                        int value = random.nextInt(11) - 5;
                        if (value != 0) terms.put(id, BigInteger.valueOf(value));
                    }
                    bound = BigInteger.valueOf(random.nextInt(29) - 10);
                }
                for (BigInteger coefficient : terms.values()) bound = bound.add(offset.multiply(coefficient));
                rows.add(new ExactLinearProgram.Constraint(terms, bound));
            }
            Collections.shuffle(rows, random);
            run(new Model(rows, low, high), width, trial % 20 == 0);
        }
        integerFrontiers();
        wideArithmetic();
        pigeonAndLimits();
        System.out.println("LCG watches: " + checked + " exhaustive models, " + resumptions +
                " resumptions, " + imported + " imported nogoods, proofs and budget/cancellation cleanup passed");
    }

    /** Wider domains make relaxed reasons strengthen an already indexed bound. */
    private static void integerFrontiers() {
        Random random = new Random(914_377);
        for (int trial = 0; trial < 96; trial++) {
            int n = new int[] { 8, 5, 4 }[trial % 3];
            int width = new int[] { 3, 4, 16 }[trial % 3];
            BigInteger offset = trial % 5 == 0 ? BigInteger.ONE.shiftLeft(80).negate() : BigInteger.ZERO;
            BigInteger[] low = new BigInteger[n], high = new BigInteger[n], anchor = new BigInteger[n];
            Arrays.fill(low, offset);
            Arrays.fill(high, offset.add(BigInteger.valueOf(width - 1)));
            for (int i = 0; i < n; i++) anchor[i] = offset.add(BigInteger.valueOf(random.nextInt(width)));
            List<ExactLinearProgram.Constraint> rows = new ArrayList<>();
            for (int d = 0; d < 3; d++) {
                Map<Integer, BigInteger> terms = new LinkedHashMap<>(), opposite = new LinkedHashMap<>();
                BigInteger target = BigInteger.ZERO;
                for (int i = 0; i < n; i++) {
                    BigInteger a = BigInteger.valueOf(random.nextInt(31) - 15);
                    if (a.signum() == 0) continue;
                    terms.put(i, a);
                    opposite.put(i, a.negate());
                    target = target.add(a.multiply(anchor[i]));
                }
                if (d == 0 && trial % 2 == 0) target = target.add(BigInteger.ONE);
                rows.add(new ExactLinearProgram.Constraint(terms, target.add(BigInteger.valueOf(trial % 2))));
                rows.add(new ExactLinearProgram.Constraint(opposite, target.negate()));
            }
            Collections.shuffle(rows, random);
            run(new Model(rows, low, high), width, true);
        }
    }

    /** Exercise both primitive division boundaries and exact signed residuals. */
    private static void wideArithmetic() {
        Random random = new Random(716_881);
        int[] bits = { 1, 62, 63, 64, 129, 1023 };
        BigInteger[] offsets = { BigInteger.ZERO, BigInteger.valueOf(Long.MIN_VALUE),
                BigInteger.valueOf(Long.MAX_VALUE), BigInteger.ONE.shiftLeft(80).negate() };
        for (int trial = 0; trial < 96; trial++) {
            int n = 3, width = 3;
            var low = new BigInteger[n];
            var high = new BigInteger[n];
            var witness = new BigInteger[n];
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int i = 0; i < n; i++) {
                low[i] = offsets[(trial + i) % offsets.length];
                high[i] = low[i].add(BigInteger.TWO);
                witness[i] = low[i].add(BigInteger.valueOf(random.nextInt(width)));
                // The unbounded-domain variant must infer this cap itself.
                rows.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.valueOf(3)), high[i].multiply(BigInteger.valueOf(3))));
            }
            for (int r = 0; r < 3; r++) {
                var terms = new LinkedHashMap<Integer, BigInteger>();
                var reverse = new LinkedHashMap<Integer, BigInteger>();
                BigInteger rhs = BigInteger.ZERO;
                for (int i = 0; i < n; i++) {
                    BigInteger a = new BigInteger(bits[(trial + r + i) % bits.length], random).add(BigInteger.ONE);
                    if (random.nextBoolean()) a = a.negate();
                    terms.put(i, a);
                    reverse.put(i, a.negate());
                    rhs = rhs.add(a.multiply(witness[i]));
                }
                rows.add(new ExactLinearProgram.Constraint(terms, rhs.subtract(BigInteger.valueOf(trial % 2))));
                rows.add(new ExactLinearProgram.Constraint(reverse, rhs.negate()));
            }
            Collections.shuffle(rows, random);
            var model = new Model(rows, low, high);
            run(model, width, true);
            run(model, width, true, true);
        }
    }

    private static void run(Model model, int width, boolean proof) {
        run(model, width, proof, false);
    }

    private static void run(Model model, int width, boolean proof, boolean inferCaps) {
        int states = 1;
        for (int i = 0; i < model.low.length; i++) states *= width;
        boolean possible = false;
        List<CountConflict> forbidden = new ArrayList<>();
        for (int code = 0; code < states; code++) {
            BigInteger[] value = model.low.clone();
            for (int i = 0, k = code; i < value.length; i++, k /= width) value[i] = value[i].add(BigInteger.valueOf(k % width));
            if (valid(model, value)) possible = true;
            else if (forbidden.size() < 4) {
                List<ExactLinearProgram.Constraint> point = new ArrayList<>();
                for (int i = 0; i < value.length; i++) {
                    if (!value[i].equals(model.high[i])) point.add(row(i, 1, value[i]));
                    if (!value[i].equals(model.low[i])) point.add(row(i, -1, value[i].negate()));
                }
                forbidden.add(new CountConflict(point));
            }
        }
        PlanningBudget budget = budget(20_000_000, 64L << 20);
        BigInteger[] high = model.high.clone();
        if (inferCaps) Arrays.fill(high, null);
        try (CountLcg search = new CountLcg(model.rows, model.low, high, budget, 1024, proof)) {
            // These full assignments were independently rejected by an input
            // row; imported conflicts therefore never narrow the valid set.
            for (CountConflict clause : forbidden) if (search.learn(clause)) imported++;
            while (true) {
                if (!search.step()) continue;
                if (!search.paused()) break;
                resumptions++;
                search.resume(1024);
            }
            BigInteger[] counts = search.counts();
            require((counts != null) == possible && search.infeasible() != possible, "wrong conclusion " + checked);
            if (counts != null) require(valid(model, counts), "invalid witness " + checked);
            if (proof) require(CountProof.verify(search.certificate(), 20_000_000) == CountProof.Verdict.VERIFIED, "invalid proof " + checked);
        }
        require(budget.reservedBytes() == 0, "retained watch memory");
        checked++;
    }

    private static void pigeonAndLimits() {
        // This reaches conflict-pool pruning as well as restarts/backjumps;
        // the smaller seven-pigeon instance finishes before pruning begins.
        int pigeons = 8, holes = 7, n = pigeons * holes;
        List<ExactLinearProgram.Constraint> rows = new ArrayList<>();
        for (int p = 0; p < pigeons; p++) {
            Map<Integer, BigInteger> terms = new LinkedHashMap<>();
            for (int h = 0; h < holes; h++) terms.put(p * holes + h, BigInteger.ONE.negate());
            rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.ONE.negate()));
        }
        for (int h = 0; h < holes; h++) for (int p = 0; p < pigeons; p++) for (int q = 0; q < p; q++)
            rows.add(new ExactLinearProgram.Constraint(Map.of(p * holes + h, BigInteger.ONE, q * holes + h, BigInteger.ONE), BigInteger.ONE));
        BigInteger[] low = new BigInteger[n], high = new BigInteger[n];
        Arrays.fill(low, BigInteger.ZERO);
        Arrays.fill(high, BigInteger.ONE);
        PlanningBudget budget = budget(20_000_000, 64L << 20);
        try (CountLcg search = new CountLcg(rows, low, high, budget, 1024, true)) {
            while (true) {
                if (!search.step()) continue;
                if (!search.paused()) break;
                search.resume(4096);
                resumptions++;
            }
            require(search.infeasible(), "pigeonhole not closed");
            // Independent replay starts every learned clause from the original
            // axioms. Its verification cap is separate from the unchanged 20M
            // SEARCH budget and admits this much longer UNSAT certificate.
            require(CountProof.verify(search.certificate(), 200_000_000) == CountProof.Verdict.VERIFIED, "pigeonhole proof");
        }
        require(budget.reservedBytes() == 0, "pigeonhole memory");
        for (int stop : new int[] { 1, 20, 100, 1000, 10000, 50000 }) {
            AtomicInteger checks = new AtomicInteger();
            PlanningBudget cancelled = new PlanningBudget(0, 20_000_000, 64L << 20, () -> checks.incrementAndGet() >= stop, System::nanoTime);
            try (CountLcg search = new CountLcg(rows, low, high, cancelled, 1_000_000)) {
                while (!search.step()) {}
            } catch (CancellationException expected) {}
            require(cancelled.reservedBytes() == 0, "cancelled watch memory");
        }
        for (long memory : new long[] { 1, 32000, 64000, 128000 }) {
            PlanningBudget limited = budget(20_000_000, memory);
            try (CountLcg search = new CountLcg(rows, low, high, limited, 1_000_000)) {
                while (!search.step()) {}
                require(search.counts() == null, "false pigeon witness");
            } catch (PlanningBudget.Exhausted expected) {}
            require(limited.reservedBytes() == 0, "limited watch memory");
        }
    }

    private static ExactLinearProgram.Constraint row(int id, long coefficient, BigInteger bound) {
        return new ExactLinearProgram.Constraint(Map.of(id, BigInteger.valueOf(coefficient)), bound);
    }

    private static PlanningBudget budget(long work, long memory) {
        return new PlanningBudget(0, work, memory, () -> false, System::nanoTime);
    }

    private static boolean valid(Model model, BigInteger[] value) {
        for (int i = 0; i < value.length; i++) if (value[i].compareTo(model.low[i]) < 0 || value[i].compareTo(model.high[i]) > 0) return false;
        for (var row : model.rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(value[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
