// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/** Independent Boolean enumeration and portable original-row certificates. */
public final class RationalLpLearningTest {
    private static int cuts, witnesses, negatives, pauses;

    public static void main(String[] args) throws Exception {
        var journal = new CountProof.Journal(16L << 20);
        var random = new Random(2026100903L);
        for (int sample = 0; sample < 360; sample++) {
            int n = 3 + random.nextInt(6), plant = random.nextInt(1 << n);
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int r = 0, m = 4 + random.nextInt(9); r < m; r++) {
                var terms = new LinkedHashMap<Integer, BigInteger>();
                long bound = sample % 3 == 0 ? random.nextInt(9) - 7 : random.nextInt(5);
                for (int i = 0; i < n; i++) {
                    int a = random.nextInt(41) - 20;
                    if (a != 0) terms.put(i, BigInteger.valueOf(a));
                    if (sample % 3 != 0 && (plant & (1 << i)) != 0) bound += a;
                }
                // Equivalent scaled rows exercise small, large and mixed dual multipliers.
                var scale = BigInteger.ONE.shiftLeft(sample % 6 == 0 ? 80 : 0).multiply(BigInteger.valueOf(1 + random.nextInt(11)));
                terms.replaceAll((id, a) -> a.multiply(scale));
                rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(bound).multiply(scale)));
            }
            if ((sample & 1) != 0) Collections.shuffle(rows, random);
            var original = enumerate(rows, n);
            var low = filled(n, 0);
            var high = filled(n, 1);
            var budget = budget(4_000_000, 64L << 20);
            try (var session = new CountLpLearning.Session()) {
                for (int domain = 0; domain < 4; domain++) {
                    if (domain > 0) {
                        int id = random.nextInt(n);
                        low[id] = high[id] = BigInteger.valueOf(random.nextInt(2));
                    }
                    try (var proposal = session.solve(rows, low, high, budget, 200_000)) {
                        if (proposal == null || proposal.cut == null) continue;
                        var cut = proposal.cut;
                        var derivation = new CountProof.Derivation("original_rows", n, rows.stream().map(CountProof::row).toList(),
                                List.of(new CountProof.Combination(cut.parents(), cut.divisor(), CountProof.row(cut.row()))));
                        check(CountProof.verify(derivation, 1_000_000) == CountProof.Verdict.VERIFIED, "Invalid exact row sum");
                        // Current fixed domains must not become hidden premises: check ALL original witnesses.
                        for (int point : original) check(satisfies(cut.row(), point), "Branch-dependent cut escaped");
                        if (cuts < 64) journal.add(derivation);
                        cuts++;
                    }
                }
            }
            check(budget.reservedBytes() == 0, "LP session leaked");
            var solverBudget = budget(4_000_000, 64L << 20);
            try (var solver = new CountLcg(rows, filled(n, 0), filled(n, 1), solverBudget, 1024, sample % 7 == 0).learnedRelaxation()) {
                while (true) {
                    while (!solver.step()) {}
                    if (!solver.paused()) break;
                    pauses++;
                    solver.resume(128);
                }
                if (solver.counts() != null) {
                    int point = 0;
                    for (int i = 0; i < n; i++) {
                        check(solver.counts()[i].equals(BigInteger.ZERO) || solver.counts()[i].equals(BigInteger.ONE), "Non-Boolean witness");
                        if (solver.counts()[i].signum() != 0) point |= 1 << i;
                    }
                    check(original.contains(point), "Invalid original witness");
                    witnesses++;
                } else {
                    check(solver.infeasible() && original.isEmpty(), "Wrong negative or incomplete small model " + sample + ": " + budget.diagnostics() + " / " + solverBudget.diagnostics());
                    if (sample % 7 == 0) check(CountProof.verify(solver.certificate(), 1_000_000) == CountProof.Verdict.VERIFIED, "Closed LCG proof failed");
                    negatives++;
                }
            }
            check(solverBudget.reservedBytes() == 0, "Retained LP search leaked");
        }
        check(cuts > 100 && witnesses > 100 && negatives > 40 && pauses > 0, "Insufficient coverage");
        Path archive = Files.createTempFile("cgse-lp-rational-", ".bin");
        try {
            journal.write(archive);
            var replay = CountProof.read(archive);
            check(replay.derivations().size() == 64, "Missing proof archive records");
            for (var proof : replay.derivations()) check(CountProof.verify(proof, 1_000_000) == CountProof.Verdict.VERIFIED, "Proof replay failed");
        } finally { Files.delete(archive); }
        boundaries();
        System.out.println("Rational LP: models=360 domains=1440 cuts=" + cuts + " witnesses=" + witnesses +
                " negatives=" + negatives + " pauses=" + pauses + "; proof replay, cancellation, limits and cleanup passed");
    }

    private static void boundaries() {
        var rows = List.of(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.valueOf(-7), 1, BigInteger.valueOf(-9)), BigInteger.valueOf(-8)),
                new ExactLinearProgram.Constraint(Map.of(0, BigInteger.valueOf(2), 1, BigInteger.valueOf(3)), BigInteger.valueOf(2)));
        for (int stop = 1; stop <= 300; stop++) {
            int limit = stop;
            var checks = new AtomicInteger();
            var budget = new PlanningBudget(0, 1_000_000, 64L << 20, () -> checks.incrementAndGet() > limit, System::nanoTime);
            try (var session = new CountLpLearning.Session(); var result = session.solve(rows, filled(2, 0), filled(2, 1), budget, 200_000)) {
                // A late cancellation may occur after this small model completes.
            } catch (CancellationException expected) {}
            check(budget.reservedBytes() == 0, "Cancelled LP leaked");
        }
        for (long work : new long[] {1, 16, 128, 1024}) for (long bytes : new long[] {512, 16384, 1 << 20}) {
            var budget = budget(work, bytes);
            try (var session = new CountLpLearning.Session(); var result = session.solve(rows, filled(2, 0), filled(2, 1), budget, 200_000)) {
                if (result != null && result.cut != null) for (int point : enumerate(rows, 2)) check(satisfies(result.cut.row(), point), "Invalid limited cut");
            } catch (PlanningBudget.Exhausted expected) {}
            check(budget.reservedBytes() == 0, "Budget-limited LP leaked");
        }
    }

    private static Set<Integer> enumerate(List<ExactLinearProgram.Constraint> rows, int n) {
        var result = new HashSet<Integer>();
        for (int point = 0; point < (1 << n); point++) {
            boolean valid = true;
            for (var row : rows) if (!satisfies(row, point)) { valid = false; break; }
            if (valid) result.add(point);
        }
        return result;
    }

    private static boolean satisfies(ExactLinearProgram.Constraint row, int point) {
        var sum = BigInteger.ZERO;
        for (var term : row.terms().entrySet()) if ((point & (1 << term.getKey())) != 0) sum = sum.add(term.getValue());
        return sum.compareTo(row.upper()) <= 0;
    }
    private static BigInteger[] filled(int n, int value) { var a = new BigInteger[n]; Arrays.fill(a, BigInteger.valueOf(value)); return a; }
    private static PlanningBudget budget(long work, long bytes) { return new PlanningBudget(0, work, bytes, () -> false, System::nanoTime); }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
