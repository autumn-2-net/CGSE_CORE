// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Original-coordinate modular proofs, with determinant and cyclic-Latin oracles. */
public final class CountSparseCongruenceTest {
    private static final BigInteger ZERO = BigInteger.ZERO, ONE = BigInteger.ONE;

    public static void main(String[] args) throws Exception {
        latin();
        determinants();
        boundaries();
        System.out.println("Sparse congruence: cyclic modular proofs, 1200 determinant oracles, sparse ambient coordinates, proof replay and refusal boundaries passed");
    }

    private static void latin() throws Exception {
        for (int n : new int[]{4, 8, 16, 20, 15, 21}) {
            var rows = latinRows(n);
            for (int seed = 0; seed < 3; seed++) {
                Collections.shuffle(rows, new Random(121208 + seed));
                // In even order, sum(row)+sum(column)-sum(symbol) is n/2 mod n.
                // In odd order, the diagonal is an explicit integer solution.
                if (n % 2 != 0) {
                    var diagonal = new BigInteger[n * n]; Arrays.fill(diagonal, ZERO);
                    for (int i = 0; i < n; i++) diagonal[n * i + i] = ONE;
                    check(valid(rows, diagonal), "Odd diagonal must satisfy all original equations");
                }
                var budget = budget(40_000_000, 16L << 20, () -> false);
                var journal = new CountProof.Journal(4L << 20); budget.proofJournal(journal);
                try (var work = new CountCongruence(rows, n * n, budget)) {
                    while (!work.step()) {}
                    check(work.infeasible() == (n % 2 == 0), "Cyclic modular conclusion n=" + n + ", seed=" + seed);
                }
                check(budget.reservedBytes() == 0, "Cyclic proof leaked memory");
                for (var proof : journal.divisibility()) {
                    check(CountProof.verify(proof, 1_000_000) == CountProof.Verdict.VERIFIED, "Original proof invalid");
                    var tampered = new CountProof.Divisibility(proof.scope(), proof.variables(), proof.axioms(),
                            Collections.nCopies(proof.multipliers().size(), ZERO));
                    check(CountProof.verify(tampered, 1_000_000) == CountProof.Verdict.INVALID, "Empty combination accepted");
                }
                if (n % 2 == 0) {
                    check(!journal.divisibility().isEmpty(), "Negative has no independent certificate");
                    var file = Files.createTempFile("cgse-sparse-congruence-", ".cgp");
                    try {
                        journal.write(file);
                        var loaded = CountProof.read(file);
                        check(!loaded.truncated() && loaded.divisibility().equals(journal.divisibility()), "Proof roundtrip changed scope");
                        for (var proof : loaded.divisibility())
                            check(CountProof.verify(proof, 1_000_000) == CountProof.Verdict.VERIFIED, "Serialized proof failed");
                    } finally { Files.deleteIfExists(file); }
                }
            }
            if (n % 2 == 0) {
                // Two diagonals cover every row, column and symbol twice. The
                // same coefficient structure with a new RHS is now feasible.
                var doubled = rows.stream().map(row -> new ExactLinearProgram.Constraint(row.terms(), row.upper().multiply(BigInteger.TWO))).toList();
                var point = new BigInteger[n * n]; Arrays.fill(point, ZERO);
                for (int i = 0; i < n; i++) {
                    point[n * i + i] = ONE;
                    point[n * i + (i + 1) % n] = ONE;
                }
                check(valid(doubled, point), "Doubled cyclic system has no explicit witness");
                var budget = budget(40_000_000, 16L << 20, () -> false);
                try (var work = new CountCongruence(doubled, n * n, budget)) {
                    while (!work.step()) {}
                    check(!work.infeasible(), "Old RHS proof rejected the doubled witness");
                }
                check(budget.reservedBytes() == 0, "Changed RHS leaked memory");
            }
        }
    }

    private static void determinants() {
        var random = new Random(121209);
        for (int trial = 0; trial < 1200; trial++) {
            int n = 2 + random.nextInt(5);
            BigInteger[][] matrix; BigInteger det;
            do {
                matrix = new BigInteger[n][n];
                for (int i = 0; i < n; i++) for (int j = 0; j < n; j++)
                    matrix[i][j] = BigInteger.valueOf(random.nextInt(11) - 5);
                det = determinant(matrix);
            } while (det.signum() == 0);
            var rhs = new BigInteger[n];
            var plant = new BigInteger[n];
            for (int i = 0; i < n; i++) plant[i] = BigInteger.valueOf(random.nextInt(13) - 6);
            if (trial % 7 == 0) plant[0] = plant[0].add(ONE.shiftLeft(90));
            for (int i = 0; i < n; i++) {
                rhs[i] = ZERO;
                for (int j = 0; j < n; j++) rhs[i] = rhs[i].add(matrix[i][j].multiply(plant[j]));
                if (trial % 2 != 0) rhs[i] = rhs[i].add(BigInteger.valueOf(random.nextInt(5) - 2));
            }
            boolean integer = true;
            // Cramer's rule is independent of the solver's row/column Euclidean operations.
            for (int j = 0; j < n; j++) {
                var replaced = new BigInteger[n][n];
                for (int i = 0; i < n; i++) { replaced[i] = matrix[i].clone(); replaced[i][j] = rhs[i]; }
                integer &= determinant(replaced).remainder(det).signum() == 0;
            }
            int offset = trial % 3 == 0 ? 8000 : 0;
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int i = 0; i < n; i++) {
                var terms = new TreeMap<Integer, BigInteger>();
                for (int j = 0; j < n; j++) if (matrix[i][j].signum() != 0) terms.put(offset + j, matrix[i][j]);
                equality(rows, terms, rhs[i]);
            }
            Collections.shuffle(rows, random);
            var budget = budget(40_000_000, 4L << 20, () -> false);
            try (var work = new CountCongruence(rows, offset + n, budget)) {
                while (!work.step()) {}
                // Singleton equations are outside this auxiliary tool; require
                // complete classification only when every equation is admitted.
                boolean allPaired = rows.stream().allMatch(row -> row.terms().size() >= 2);
                check(!work.infeasible() || !integer, "Rejected integer solution at " + trial);
                if (allPaired) check(work.infeasible() != integer, "Missed exact small system at " + trial);
            }
            check(budget.reservedBytes() == 0, "Random proof leaked memory");
        }
    }

    private static BigInteger determinant(BigInteger[][] values) {
        int n = values.length;
        if (n == 1) return values[0][0];
        BigInteger total = ZERO;
        for (int column = 0; column < n; column++) {
            if (values[0][column].signum() == 0) continue;
            var minor = new BigInteger[n - 1][n - 1];
            for (int i = 1; i < n; i++) for (int j = 0, to = 0; j < n; j++)
                if (j != column) minor[i - 1][to++] = values[i][j];
            var value = values[0][column].multiply(determinant(minor));
            total = column % 2 == 0 ? total.add(value) : total.subtract(value);
        }
        return total;
    }

    private static void boundaries() {
        var rows = latinRows(16);
        for (long work : new long[]{1, 1024, 32768, 65536, 131072, 1_000_000})
            for (long bytes : new long[]{1024, 32_768, 131_072, 1L << 20}) {
                var budget = budget(work, bytes, () -> false);
                try (var search = new CountCongruence(rows, 256, budget)) { while (!search.step()) {} }
                catch (PlanningBudget.Exhausted expected) {}
                check(budget.reservedBytes() == 0, "Limited elimination leaked memory");
            }
        var cancelled = new AtomicBoolean();
        var budget = budget(40_000_000, 16L << 20, cancelled::get);
        try (var search = new CountCongruence(rows, 256, budget)) {
            check(!search.step(), "Cancellation fixture finished immediately");
            cancelled.set(true);
            try { search.step(); throw new AssertionError("Cancellation ignored"); }
            catch (java.util.concurrent.CancellationException expected) {}
        }
        check(budget.reservedBytes() == 0, "Cancellation leaked memory");
        var wide = new ArrayList<ExactLinearProgram.Constraint>();
        equality(wide, Map.of(0, ONE.shiftLeft(600), 1, ONE), ONE);
        equality(wide, Map.of(0, ONE, 1, ONE), ZERO);
        budget = budget(40_000_000, 16L << 20, () -> false);
        try (var search = new CountCongruence(wide, 2, budget)) {
            while (!search.step()) {}
            check(!search.infeasible(), "Magnitude refusal became a conclusion");
        }
        check(budget.reservedBytes() == 0, "Magnitude refusal leaked memory");
    }

    private static ArrayList<ExactLinearProgram.Constraint> latinRows(int n) {
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int type = 0; type < 3; type++) for (int k = 0; k < n; k++) {
            var terms = new TreeMap<Integer, BigInteger>();
            for (int i = 0; i < n; i++) for (int j = 0; j < n; j++)
                if ((type == 0 ? i : type == 1 ? j : (i + j) % n) == k) terms.put(i * n + j, ONE);
            equality(rows, terms, ONE);
        }
        return rows;
    }
    private static void equality(List<ExactLinearProgram.Constraint> rows, Map<Integer, BigInteger> terms, BigInteger rhs) {
        rows.add(new ExactLinearProgram.Constraint(terms, rhs));
        var opposite = new TreeMap<Integer, BigInteger>();
        terms.forEach((id, value) -> opposite.put(id, value.negate()));
        rows.add(new ExactLinearProgram.Constraint(opposite, rhs.negate()));
    }
    private static boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] point) {
        for (var row : rows) {
            var value = ZERO;
            for (var term : row.terms().entrySet()) value = value.add(term.getValue().multiply(point[term.getKey()]));
            if (value.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }
    private static PlanningBudget budget(long work, long bytes, java.util.function.BooleanSupplier cancel) {
        return new PlanningBudget(0, work, bytes, cancel, System::nanoTime);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
