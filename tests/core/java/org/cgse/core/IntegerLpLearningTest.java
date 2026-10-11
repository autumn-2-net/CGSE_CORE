// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/** Exhaustive bounded-integer oracle; branch-independent cuts and sparse-support replay. */
public final class IntegerLpLearningTest {
    private static int cuts, positives, negatives, pauses, learnedCuts, admissions;

    public static void main(String[] args) throws Exception {
        Random random = new Random(2026101107L);
        var journal = new CountProof.Journal(16L << 20);
        for (int sample = 0; sample < 400; sample++) {
            int n = 2 + random.nextInt(4);
            var lower = new BigInteger[n];
            var upper = new BigInteger[n];
            var plant = new BigInteger[n];
            for (int i = 0; i < n; i++) {
                lower[i] = BigInteger.valueOf(sample % 5 == 0 ? random.nextInt(5) - 3 : random.nextInt(3));
                upper[i] = lower[i].add(BigInteger.valueOf(1 + random.nextInt(3)));
                plant[i] = lower[i].add(BigInteger.valueOf(random.nextInt(upper[i].subtract(lower[i]).intValue() + 1)));
            }
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int r = 0, size = 3 + random.nextInt(7); r < size; r++) {
                Map<Integer, BigInteger> terms = new TreeMap<>();
                BigInteger bound = BigInteger.valueOf(sample % 3 == 0 ? random.nextInt(15) - 7 : random.nextInt(5));
                for (int i = 0; i < n; i++) {
                    BigInteger a = BigInteger.valueOf(random.nextInt(25) - 12);
                    if (a.signum() != 0) terms.put(i, a);
                    if (sample % 3 != 0) bound = bound.add(a.multiply(plant[i]));
                }
                var scale = BigInteger.ONE.shiftLeft(sample % 11 == 0 ? 70 : 0);
                terms.replaceAll((id, value) -> value.multiply(scale));
                rows.add(new ExactLinearProgram.Constraint(terms, bound.multiply(scale)));
            }
            Collections.shuffle(rows, random);
            List<BigInteger[]> witnesses = new ArrayList<>();
            enumerate(rows, lower, upper, lower.clone(), 0, witnesses);
            var budget = budget(4_000_000);
            try (var session = new CountLpLearning.Session(true)) {
                var low = lower.clone();
                var high = upper.clone();
                for (int branch = 0; branch < 4; branch++) {
                    if (branch > 0) {
                        int id = random.nextInt(n);
                        low[id] = high[id] = plant[id];
                    }
                    try (var result = session.solve(rows, low, high, budget, 200_000)) {
                        if (result == null || result.cut == null) continue;
                        checkCut(rows, n, result.cut, witnesses);
                        if (cuts < 64) journal.add(proof(rows, n, result.cut));
                        cuts++;
                    }
                }
            }
            check(budget.reservedBytes() == 0, "Integer LP workspace leak");
            if (admissions < 8 && sample % 5 != 0) portfolioAdmission(rows, lower, upper);
            // Exercise ordinary nonnegative count domains, with proof retention,
            // exact integer rounding, restarts and short continuation slices.
            if (sample % 5 == 0) continue;
            budget = budget(4_000_000);
            try (var solver = new CountLcg(rows, lower, upper, budget, 16384, sample % 7 == 0).integerRelaxation()) {
                check(solver.learnedRelaxationEnabled(), "Integer arm not enabled");
                for (;;) {
                    while (!solver.step()) {}
                    if (!solver.paused()) break;
                    pauses++;
                    solver.resume(4096);
                }
                learnedCuts += (int) solver.cutProgress();
                var values = solver.counts();
                if (values != null) {
                    check(witnesses.stream().anyMatch(w -> Arrays.equals(w, values)), "Invalid integer witness");
                    positives++;
                } else {
                    check(solver.infeasible() && witnesses.isEmpty(), "Incorrect integer conclusion: " + sample + " " + budget.diagnostics());
                    if (sample % 7 == 0) check(CountProof.verify(solver.certificate(), 1_000_000) == CountProof.Verdict.VERIFIED, "Bad integer proof");
                    negatives++;
                }
            }
            check(budget.reservedBytes() == 0, "Integer LCG leak");
            continuation(rows, lower, upper, witnesses);
        }
        largeContinuation();
        check(cuts > 50 && positives > 100 && negatives > 20 && learnedCuts > 5 && admissions > 0 && pauses > 0,
                "Integer coverage too small: " + cuts + "/" + positives + "/" + negatives + "/" + learnedCuts);
        sparseSupport();
        boundaries();
        var archive = Files.createTempFile("cgse-integer-lp-", ".bin");
        try {
            journal.write(archive);
            var replay = CountProof.read(archive);
            check(replay.derivations().size() == 64, "Missing derivation archive");
            for (var proof : replay.derivations()) check(CountProof.verify(proof, 1_000_000) == CountProof.Verdict.VERIFIED, "Bad independent replay");
        } finally { Files.delete(archive); }
        System.out.println("Integer LP: 400 models / 1600 domains; cuts=" + cuts + "; witnesses=" + positives +
                "; negatives=" + negatives + "; LCG cuts=" + learnedCuts + "; pauses=" + pauses + "; sparse supports, infinity, large counts, cancellation and proof replay passed");
    }

    private static void largeContinuation() {
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int r = 0; r < 256; r++) {
            Map<Integer, BigInteger> terms = new TreeMap<>();
            var bound = BigInteger.ZERO;
            for (int i = 0; i < 64; i++) {
                var coefficient = BigInteger.valueOf(-2 - (r + i) % 3);
                terms.put(i, coefficient);
                bound = bound.add(coefficient); // All ones is an independently known witness.
            }
            rows.add(new ExactLinearProgram.Constraint(terms, bound));
        }
        var budget = budget(4_000_000);
        try (var search = new CountLcg(rows, filled(64, 0), filled(64, 3), budget, 1024).integerRelaxation()) {
            for (;;) {
                while (!search.step()) {}
                if (!search.paused()) break;
                pauses++;
                search.resume(32768);
            }
            var point = search.counts();
            check(point != null && rows.stream().allMatch(row -> satisfies(row, point)), "Lost retained large integer witness");
            for (var value : point) check(value.signum() >= 0 && value.compareTo(BigInteger.valueOf(3)) <= 0, "Retained endpoint escaped");
        }
        check(budget.reservedBytes() == 0, "Large retained session leaked");
    }

    private static void continuation(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high,
                                     List<BigInteger[]> witnesses) {
        var budget = budget(4_000_000);
        try (var search = new CountLcg(rows, low, high, budget, 1024).integerRelaxation()) {
            for (;;) {
                while (!search.step()) {}
                if (!search.paused()) break;
                pauses++;
                search.resume(16384);
            }
            var point = search.counts();
            if (point == null) check(search.infeasible() && witnesses.isEmpty(), "Continuation changed the integer conclusion");
            else check(witnesses.stream().anyMatch(w -> Arrays.equals(w, point)), "Continuation lost exact bounds or rows");
        }
        check(budget.reservedBytes() == 0, "Integer continuation leaked");
    }

    private static void portfolioAdmission(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper) throws Exception {
        var budget = budget(1_000_000);
        try (var models = CountModelViews.create(rows, lower, upper, budget); var search = new CountViewSearch(models, budget)) {
            var scout = CountViewSearch.class.getDeclaredMethod("addIntegerLp");
            scout.setAccessible(true);
            check(!(Boolean) scout.invoke(search), "Integer LP displaced preliminary compilation");
            models.compileLight();
            if ((Boolean) scout.invoke(search)) {
                admissions++;
                check(models.cutVersion() > 0, "Another LCG was admitted without a certified consequence");
            }
            check(!(Boolean) scout.invoke(search), "A parent handoff renewed the one-time scout");
            search.resume(65536);
            while (!search.step()) {}
            if (search.counts() != null) for (var row : rows) check(satisfies(row, search.counts()), "Scout polluted the original count result");
        }
        check(budget.reservedBytes() == 0, "Portfolio scout leaked");
    }

    private static void sparseSupport() {
        int n = 2048;
        var low = filled(n, 0);
        var high = new BigInteger[n]; // Unrelated coordinates remain genuinely unbounded.
        high[173] = high[1701] = high[2011] = BigInteger.valueOf(5);
        var random = new Random(317);
        int supportedCuts = 0;
        for (int sample = 0; sample < 60; sample++) {
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int i = 0; i < 600; i++) rows.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE.negate()), BigInteger.ZERO));
            // The third column is a boundary input; dropping it can fabricate a contradiction.
            for (int r = 0; r < 7; r++) {
                var terms = new TreeMap<Integer, BigInteger>();
                for (int id : new int[] {173, 1701, 2011}) terms.put(id, BigInteger.valueOf(random.nextInt(19) - 9));
                rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(random.nextInt(25) - 12)));
            }
            var budget = budget(1_000_000);
            try (var session = new CountIntegerLpLearning()) {
                for (int repeat = 0; repeat < 2; repeat++) try (var result = session.solve(rows, low, high, 600, budget, 200_000)) {
                    if (result == null) continue;
                    if (result.point != null) check(Double.isNaN(result.point[2]), "Omitted column was assigned zero");
                    if (result.cut != null) {
                        check(CountProof.verify(proof(rows, n, result.cut), 1_000_000) == CountProof.Verdict.VERIFIED, "Local proof lost an original term/parent");
                        supportedCuts++;
                    }
                }
            }
            check(budget.reservedBytes() == 0, "Support workspace leak");
        }
        check(supportedCuts > 10, "Small support rejected because of unrelated unbounded columns: " + supportedCuts);
    }

    private static void boundaries() {
        var rows = List.of(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.valueOf(3), 1, BigInteger.valueOf(5)), BigInteger.valueOf(12)),
                new ExactLinearProgram.Constraint(Map.of(0, BigInteger.valueOf(-7), 1, BigInteger.valueOf(-9)), BigInteger.valueOf(-22)));
        for (int stop = 1; stop <= 300; stop++) {
            var checks = new AtomicInteger();
            int limit = stop;
            var budget = new PlanningBudget(0, 1_000_000, 64L << 20, () -> checks.incrementAndGet() > limit, System::nanoTime);
            try (var session = new CountIntegerLpLearning(); var result = session.solve(rows, filled(2, 0), filled(2, 10), -1, budget, 200_000)) {
                if (result != null && result.cut != null) check(CountProof.verify(proof(rows, 2, result.cut), 1_000_000) == CountProof.Verdict.VERIFIED, "Cancelled cut invalid");
            } catch (CancellationException ignored) {}
            check(budget.reservedBytes() == 0, "Cancelled support leaked");
        }
        for (long work : new long[] {1, 16, 1024, 8192}) {
            var budget = budget(work);
            try (var session = new CountIntegerLpLearning(); var result = session.solve(rows, filled(2, 0), filled(2, 10), -1, budget, 200_000)) {}
            catch (PlanningBudget.Exhausted ignored) {}
            check(budget.reservedBytes() == 0, "Work limit leaked");
        }
        for (long bytes : new long[] {512, 16384, 65536, 1 << 20}) {
            var limited = new PlanningBudget(0, 1_000_000, bytes, () -> false, System::nanoTime);
            try (var session = new CountIntegerLpLearning(); var result = session.solve(rows, filled(2, 0), filled(2, 10), -1, limited, 200_000)) {}
            catch (PlanningBudget.Exhausted ignored) {}
            check(limited.reservedBytes() == 0, "Memory-limited kernel leaked");
            try (var search = new CountLcg(rows, filled(2, 0), filled(2, 10), limited, 8192).integerRelaxation()) {
                while (!search.step()) {}
            } catch (PlanningBudget.Exhausted ignored) {}
            check(limited.reservedBytes() == 0, "Memory-limited integer LCG leaked");
        }
        var budget = budget(1_000_000);
        var huge = BigInteger.ONE.shiftLeft(90);
        var hugeRows = List.of(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.valueOf(2), 1, BigInteger.valueOf(3)), huge.multiply(BigInteger.valueOf(5)).add(BigInteger.ONE)),
                new ExactLinearProgram.Constraint(Map.of(0, BigInteger.valueOf(-3), 1, BigInteger.valueOf(-2)), huge.multiply(BigInteger.valueOf(-5)).subtract(BigInteger.valueOf(4))));
        try (var session = new CountLpLearning.Session(true)) {
            try (var result = session.solve(rows, filled(2, 0), new BigInteger[] {null, BigInteger.TEN}, budget, 200_000)) {
                check(result == null, "Unbounded integer domain was silently truncated");
            }
            try (var result = session.solve(hugeRows, new BigInteger[] {huge, huge}, new BigInteger[] {huge.add(BigInteger.TEN), huge.add(BigInteger.TEN)}, budget, 200_000)) {
                check(result != null, "Exact nonzero large-count shift was rejected");
                if (result.cut != null) check(CountProof.verify(proof(hugeRows, 2, result.cut), 1_000_000) == CountProof.Verdict.VERIFIED, "Large endpoint proof invalid");
            }
            var shifted = List.of(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE), huge.add(BigInteger.valueOf(3))),
                    new ExactLinearProgram.Constraint(Map.of(1, BigInteger.ONE.negate()), huge.add(BigInteger.ONE).negate()));
            try (var result = session.solve(shifted, new BigInteger[] {huge, huge}, new BigInteger[] {huge.add(BigInteger.TEN), huge.add(BigInteger.TEN)}, budget, 200_000)) {
                check(result != null && result.integerPoint != null && shifted.stream().allMatch(row -> satisfies(row, result.integerPoint)),
                        "Rounding a numerical proposal lost the exact large offset");
            }
        }
        check(budget.reservedBytes() == 0, "Boundary session leaked");
    }

    private static CountProof.Derivation proof(List<ExactLinearProgram.Constraint> rows, int n, CountLpLearning.Cut cut) {
        return new CountProof.Derivation("integer_original_rows", n, rows.stream().map(CountProof::row).toList(),
                List.of(new CountProof.Combination(cut.parents(), cut.divisor(), CountProof.row(cut.row()))));
    }
    private static void checkCut(List<ExactLinearProgram.Constraint> rows, int n, CountLpLearning.Cut cut, List<BigInteger[]> witnesses) {
        check(CountProof.verify(proof(rows, n, cut), 1_000_000) == CountProof.Verdict.VERIFIED, "Integer row combination invalid");
        for (var point : witnesses) check(satisfies(cut.row(), point), "Branch-dependent integer cut escaped");
    }
    private static void enumerate(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high,
                                  BigInteger[] point, int id, List<BigInteger[]> result) {
        if (id == point.length) {
            if (rows.stream().allMatch(row -> satisfies(row, point))) result.add(point.clone());
            return;
        }
        for (var value = low[id]; value.compareTo(high[id]) <= 0; value = value.add(BigInteger.ONE)) {
            point[id] = value;
            enumerate(rows, low, high, point, id + 1, result);
        }
    }
    private static boolean satisfies(ExactLinearProgram.Constraint row, BigInteger[] point) {
        var sum = BigInteger.ZERO;
        for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(point[term.getKey()]));
        return sum.compareTo(row.upper()) <= 0;
    }
    private static BigInteger[] filled(int n, long value) { var values = new BigInteger[n]; Arrays.fill(values, BigInteger.valueOf(value)); return values; }
    private static PlanningBudget budget(long work) { return new PlanningBudget(0, work, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
