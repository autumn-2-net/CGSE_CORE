// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Original-premise proof replay and exhaustive assignments, independent of the separator. */
public final class ZeroHalfCutsTest {
    record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high, ExactRational[] point) {}

    public static void main(String[] args) throws Exception {
        for (int n : new int[]{5, 7, 9, 13, 25, 41, 65, 97}) for (int order = 0; order < 3; order++) {
            Model model = cycle(n, order, false);
            var cuts = separate(model, false, 64L << 20, true);
            check(!cuts.isEmpty(), "Missed full odd-cycle combination: " + n + "/" + order);
            check(!fits(cuts, model.point), "Cut did not separate the fractional point");
            check(separate(cycle(n, order, true), false, 64L << 20, true).isEmpty(), "Open chain was closed into a cycle");
        }
        Model five = cycle(5, 0, false);
        var oldBudget = budget(64L << 20);
        try (var old = new CountRoundingCuts(five.rows, five.low, five.point, oldBudget)) {
            while (!old.step()) {}
            check(old.cuts().isEmpty(), "Control should need more than three rows");
        }
        check(oldBudget.reservedBytes() == 0, "Control leaked");
        exhaustive();
        boundsAndClosure();
        boundaries();
        System.out.println("Zero-half: 24 odd cycles, 24 open neighbors, 240 exhaustive signed/large-offset models; bound premises, sparse closure, certificate replay and resource boundaries passed");
    }

    static Model cycle(int n, int order, boolean missing) {
        var random = new Random(9137 + order);
        var low = new BigInteger[n];
        var high = new BigInteger[n];
        var point = new ExactRational[n];
        var signs = new BigInteger[n];
        for (int i = 0; i < n; i++) {
            low[i] = order % 3 == 0 ? BigInteger.ZERO : BigInteger.valueOf(random.nextInt(9) - 4);
            if (order % 3 == 2) low[i] = low[i].add(BigInteger.ONE.shiftLeft(90).multiply(BigInteger.valueOf(i % 2 == 0 ? 1 : -1)));
            high[i] = low[i].add(BigInteger.ONE);
            point[i] = ExactRational.of(low[i]).add(new ExactRational(BigInteger.ONE, BigInteger.TWO));
            signs[i] = order == 0 || random.nextBoolean() ? BigInteger.ONE : BigInteger.ONE.negate();
        }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int i = 0; i < n - (missing ? 1 : 0); i++) {
            int j = (i + 1) % n;
            BigInteger rhs = BigInteger.ONE;
            for (int id : new int[]{i, j}) rhs = rhs.add(signs[id].signum() > 0 ? low[id] : high[id].negate());
            rows.add(new ExactLinearProgram.Constraint(Map.of(i, signs[i], j, signs[j]), rhs));
        }
        Collections.shuffle(rows, random);
        return new Model(rows, low, high, point);
    }

    private static void exhaustive() throws Exception {
        var random = new Random(94751);
        for (int sample = 0; sample < 240; sample++) {
            Model source = cycle(9, sample, sample % 7 == 0);
            var rows = new ArrayList<>(source.rows);
            for (int r = 0; r < 4; r++) {
                var terms = new LinkedHashMap<Integer, BigInteger>();
                BigInteger rhs = BigInteger.valueOf(random.nextInt(7) - 2);
                for (int i = 0; i < 9; i++) if (random.nextBoolean()) {
                    BigInteger a = BigInteger.valueOf(random.nextInt(5) - 2);
                    if (a.signum() != 0) { terms.put(i, a); rhs = rhs.add(a.multiply(source.low[i])); }
                }
                rows.add(new ExactLinearProgram.Constraint(terms, rhs));
            }
            Collections.shuffle(rows, random);
            Model model = new Model(rows, source.low, source.high, source.point);
            var cuts = separate(model, false, sample % 11 == 0 ? 16384 : 64L << 20, sample % 9 == 0);
            for (int mask = 0; mask < 512; mask++) {
                var point = new ExactRational[9];
                for (int i = 0; i < 9; i++) point[i] = ExactRational.of(model.low[i].add(BigInteger.valueOf((mask >>> i) & 1)));
                if (fits(rows, point)) check(fits(cuts, point), "Removed original integer assignment: " + sample);
            }
        }
    }

    private static void boundsAndClosure() throws Exception {
        for (boolean missing : new boolean[]{false, true}) {
            int length = 9, n = 3 * length;
            Model source = cycle(n, 0, false);
            var low = Arrays.copyOf(source.low, n + 1);
            var high = Arrays.copyOf(source.high, n + 1);
            low[n] = BigInteger.ONE;
            high[n] = BigInteger.TWO;
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int g = 0; g < 3; g++) for (int i = 0; i < length; i++) {
                if (missing && g == 0 && i == length - 1) continue;
                rows.add(new ExactLinearProgram.Constraint(Map.of(g * length + i, BigInteger.ONE,
                        g * length + (i + 1) % length, BigInteger.ONE, n, BigInteger.ONE), BigInteger.TWO));
            }
            var demand = new LinkedHashMap<Integer, BigInteger>();
            for (int i = 0; i < n; i++) demand.put(i, BigInteger.ONE.negate());
            demand.put(n, BigInteger.valueOf(13));
            rows.add(new ExactLinearProgram.Constraint(demand, BigInteger.ZERO));
            var model = new Model(rows, low, high, null);
            var cuts = separate(model, true, 64L << 20, true);
            if (!missing) check(cuts.stream().anyMatch(c -> c.terms().equals(Map.of(n, BigInteger.ONE)) && c.upper().signum() == 0), "Lost shared lower-bound premise in closure");
            else {
                var witness = new ExactRational[n + 1];
                Arrays.fill(witness, ExactRational.ZERO);
                witness[n] = ExactRational.ONE;
                for (int g = 0; g < 3; g++) for (int i = 0; i < length - (g == 0 ? 0 : 1); i += 2) witness[g * length + i] = ExactRational.ONE;
                check(fits(rows, witness) && fits(cuts, witness), "Open feasible neighbor lost to sparse closure");
            }
        }
    }

    private static List<ExactLinearProgram.Constraint> separate(Model model, boolean pairs, long memory, boolean archive) throws Exception {
        var budget = budget(memory);
        var journal = new CountProof.Journal(32L << 20);
        budget.proofJournal(journal);
        List<ExactLinearProgram.Constraint> cuts;
        try (var search = pairs ? CountZeroHalfCuts.pairs(model.rows, model.low, model.high, budget) :
                new CountZeroHalfCuts(model.rows, model.low, model.high, model.point, budget)) {
            if (search == null) cuts = List.of();
            else { while (!search.step()) {} cuts = search.cuts(); }
        }
        check(budget.reservedBytes() == 0, "Separator leaked");
        check(!journal.truncated(), "Proof truncated");
        if (archive) {
            Path path = Files.createTempFile("cgse-zero-half", ".proof");
            try { journal.write(path); journal = CountProof.read(path); }
            finally { Files.delete(path); }
        }
        var established = new HashSet<>(model.rows.stream().map(CountProof::row).toList());
        for (int i = 0; i < model.low.length; i++) {
            established.add(new CountProof.Row(Map.of(i, BigInteger.ONE.negate()), model.low[i].negate()));
            if (model.high[i] != null) established.add(new CountProof.Row(Map.of(i, BigInteger.ONE), model.high[i]));
        }
        for (var proof : journal.rounding()) {
            check(established.containsAll(proof.axioms()), "Invented rounding premise");
            check(CountProof.verify(proof, 1_000_000) == CountProof.Verdict.VERIFIED, "Invalid rounding certificate");
            established.add(proof.consequence());
        }
        for (var proof : journal.derivations()) {
            check(established.containsAll(proof.axioms()), "Invented sparse-closure premise");
            check(CountProof.verify(proof, 1_000_000) == CountProof.Verdict.VERIFIED, "Invalid aggregation certificate");
            proof.steps().forEach(step -> established.add(step.consequence()));
        }
        for (var cut : cuts) check(established.contains(CountProof.row(cut)), "Cut lacks an original-premise proof");
        return cuts;
    }

    private static void boundaries() throws Exception {
        Model model = cycle(97, 0, false);
        for (long bytes : new long[]{512, 16384, 131072}) separate(model, false, bytes, true);
        for (int threshold : new int[]{0, 100, 500, 1000, 2500}) {
            PlanningBudget[] owner = {null};
            owner[0] = new PlanningBudget(0, 20_000_000, 64L << 20,
                    () -> owner[0] != null && owner[0].nodes() >= threshold, System::nanoTime);
            try (var search = new CountZeroHalfCuts(model.rows, model.low, model.high, model.point, owner[0])) {
                while (!search.step()) {}
                throw new AssertionError("Cancellation ignored");
            } catch (CancellationException expected) {}
            check(owner[0].reservedBytes() == 0, "Cancellation leaked");
            var shared = budget(64L << 20);
            try (var search = new CountZeroHalfCuts(model.rows, model.low, model.high, model.point, shared)) {
                shared.charge(20_000_000 - threshold);
                try { while (!search.step()) {} throw new AssertionError("Shared cutoff ignored"); }
                catch (PlanningBudget.Exhausted expected) {}
            }
            check(shared.reservedBytes() == 0, "Shared cutoff leaked");
        }
        var tiny = new PlanningBudget(0, 1024, 64L << 20, () -> false, System::nanoTime);
        try (var search = new CountZeroHalfCuts(model.rows, model.low, model.high, model.point, tiny)) {
            check(search.step() && search.cuts().isEmpty(), "Admission cutoff fabricated a cut");
            search.close();
        }
        check(tiny.reservedBytes() == 0, "Tiny request leaked");
    }

    private static boolean fits(List<ExactLinearProgram.Constraint> rows, ExactRational[] point) {
        for (var row : rows) {
            ExactRational sum = ExactRational.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(point[term.getKey()].multiply(ExactRational.of(term.getValue())));
            if (sum.compareTo(ExactRational.of(row.upper())) > 0) return false;
        }
        return true;
    }
    private static PlanningBudget budget(long bytes) { return new PlanningBudget(0, 20_000_000, bytes, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
