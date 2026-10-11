// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exhaustive equivalence includes signed domains, fractional row ratios and inconsistent equalities. */
public final class CountRowSparsifierTest {
    private static long checked;
    private static int changed, proofs;

    public static void main(String[] args) throws Exception {
        for (int seed = 0; seed < 160; seed++) exhaustive(seed);
        chained();
        refusals();
        budgets();
        benchmark();
        archive();
        check(changed >= 80 && proofs >= 40, "Insufficient transformed and independently proved cases");
        System.out.println("Row sparsifier: exact assignments=" + checked + "; changed=" + changed + "; proofs=" + proofs +
                "; limits and original-view recovery passed");
    }

    private static PlanningBudget budget(long work, long bytes) {
        return new PlanningBudget(0, work, bytes, () -> false, System::nanoTime);
    }

    private static void exhaustive(int seed) {
        var random = new Random(731103L + seed);
        int n = 3 + seed % 3;
        var equation = new TreeMap<Integer, BigInteger>();
        for (int i = 0; i < n; i++) equation.put(i, BigInteger.valueOf(2L * (1 + random.nextInt(4)) * (random.nextBoolean() ? 1 : -1)));
        BigInteger rhs = BigInteger.valueOf(random.nextInt(13) - 6);
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        equation(rows, equation, rhs);
        for (int j = 0; j < 4; j++) {
            int multiplier = random.nextBoolean() ? 3 : -3;
            var terms = new TreeMap<Integer, BigInteger>();
            for (var term : equation.entrySet()) terms.put(term.getKey(), term.getValue().divide(BigInteger.TWO).multiply(BigInteger.valueOf(multiplier)));
            int at = random.nextInt(n);
            terms.merge(at, BigInteger.valueOf(random.nextInt(7) - 3), BigInteger::add);
            terms.values().removeIf(value -> value.signum() == 0);
            rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(random.nextInt(21) - 10)));
        }
        Collections.shuffle(rows, random);
        // Some equalities themselves are redundant combinations. Their pivots
        // must survive when the transformed rows are empty or contradictory.
        if (seed % 4 == 0) equation(rows, equation, rhs.add(BigInteger.ONE));
        if (seed % 7 == 0) rows.add(rows.get(0));
        BigInteger offset = seed % 2 == 0 ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(100).negate();
        var shifted = new ArrayList<ExactLinearProgram.Constraint>();
        for (var row : rows) {
            BigInteger bound = row.upper();
            for (var a : row.terms().values()) bound = bound.add(a.multiply(offset));
            shifted.add(new ExactLinearProgram.Constraint(row.terms(), bound));
        }
        var budget = budget(40_000_000, 128L << 20);
        var journal = new CountProof.Journal(8L << 20);
        if (seed % 2 != 0) budget.proofJournal(journal);
        try (var compiler = new CountRowSparsifier(shifted, budget)) {
            var result = compiler.compile();
            if (result != shifted) changed++;
            check(result.equals(compiler.compile()), "Repeated compilation changed result");
            for (var proof : journal.derivations()) {
                check(CountProof.verify(proof, 2_000_000) == CountProof.Verdict.VERIFIED, "Invalid sparse-row certificate");
                for (var row : result) check(proof.axioms().contains(CountProof.row(row)) ||
                        proof.steps().stream().anyMatch(step -> step.consequence().equals(CountProof.row(row))), "Unexplained output row");
                proofs++;
            }
            BigInteger[] point = new BigInteger[n];
            int total = (int) Math.pow(5, n);
            for (int code = 0; code < total; code++) {
                int rest = code;
                for (int i = 0; i < n; i++) { point[i] = offset.add(BigInteger.valueOf(rest % 5 - 2)); rest /= 5; }
                check(valid(shifted, point) == valid(result, point), "Changed integer feasible set: " + seed + "/" + code);
                checked++;
            }
        }
        check(budget.reservedBytes() == 0, "Compiler memory leak");
    }

    private static List<ExactLinearProgram.Constraint> hiddenBounds(int n) {
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        var sum = new TreeMap<Integer, BigInteger>();
        for (int i = 0; i < n; i++) sum.put(i, BigInteger.ONE);
        equation(rows, sum, BigInteger.valueOf(n / 2));
        for (int i = 0; i < n; i++) {
            var terms = new TreeMap<Integer, BigInteger>();
            for (int j = 0; j < n; j++) terms.put(j, BigInteger.valueOf(i % 2 == 0 ? 5 : -3));
            terms.merge(i, BigInteger.ONE, BigInteger::add);
            rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf((i % 2 == 0 ? 5 : -3) * (n / 2) + i % 2)));
        }
        return rows;
    }

    private static void archive() throws Exception {
        var budget = budget(40_000_000, 128L << 20);
        var journal = new CountProof.Journal(8L << 20);
        budget.proofJournal(journal);
        var rows = hiddenBounds(16);
        try (var compiler = new CountRowSparsifier(rows, budget)) {
            check(compiler.compile() != rows && !journal.derivations().isEmpty(), "Proof-mode sparsification declined");
        }
        var path = Files.createTempFile("sparse-equality-proof-", ".cgp");
        try {
            journal.write(path);
            var restored = CountProof.read(path);
            check(restored.derivations().equals(journal.derivations()), "Lost compilation proof in archive");
            for (var proof : restored.derivations())
                check(CountProof.verify(proof, 2_000_000) == CountProof.Verdict.VERIFIED, "Archived proof did not replay");
            var proof = restored.derivations().get(0);
            var changed = new ArrayList<>(proof.axioms());
            var row = changed.get(0);
            changed.set(0, new CountProof.Row(row.terms(), row.upper().add(BigInteger.ONE)));
            var forged = new CountProof.Derivation(proof.scope(), proof.variables(), changed, proof.steps());
            check(CountProof.verify(forged, 2_000_000) == CountProof.Verdict.INVALID, "Changed equality premise accepted");
        } finally { Files.delete(path); }
        check(budget.reservedBytes() == 0, "Proof compilation memory leak");
    }

    private static void chained() {
        var rows = hiddenBounds(8);
        var chained = new TreeMap<Integer, BigInteger>(rows.get(0).terms());
        chained.merge(0, BigInteger.ONE, BigInteger::add);
        chained.merge(1, BigInteger.ONE.negate(), BigInteger::add);
        chained.values().removeIf(value -> value.signum() == 0);
        equation(rows, chained, BigInteger.valueOf(3));
        var budget = budget(40_000_000, 128L << 20);
        try (var compiler = new CountRowSparsifier(rows, budget)) {
            var result = compiler.compile();
            check(result != rows, "Chained equality was not sparsified");
            for (int bits = 0; bits < 256; bits++) {
                var point = new BigInteger[8];
                for (int i = 0; i < 8; i++) point[i] = BigInteger.valueOf(bits >> i & 1);
                check(valid(rows, point) == valid(result, point), "Lost retained equality premise");
                checked++;
            }
        }
        check(budget.reservedBytes() == 0, "Chained memory leak");
    }

    private static void budgets() {
        var rows = hiddenBounds(48);
        for (long work : new long[] {1, 500, 10_000, 100_000, 1_000_000, 2_000_000, 3_000_000, 40_000_000})
            for (long bytes : new long[] {1024, 128L << 20}) for (boolean proof : new boolean[] {false, true}) {
                var budget = budget(work, bytes);
                if (proof) budget.proofJournal(new CountProof.Journal(8L << 20));
                try (var compiler = new CountRowSparsifier(rows, budget)) {
                    var result = compiler.compile();
                    var witness = new BigInteger[48];
                    for (int i = 0; i < 48; i++) witness[i] = BigInteger.valueOf(i % 2);
                    check(valid(result, witness), "Budget refusal changed model");
                    check(budget.nodes() <= Math.min(65536, work / 64), "Local work overshoot");
                }
                check(budget.reservedBytes() == 0, "Refusal leaked memory");
            }
        var cancelled = new AtomicBoolean(true);
        var budget = new PlanningBudget(0, 40_000_000, 128L << 20, cancelled::get, System::nanoTime);
        try (var compiler = new CountRowSparsifier(rows, budget)) {
            try { compiler.compile(); throw new AssertionError("Cancellation ignored"); }
            catch (CancellationException expected) { }
        }
        check(budget.reservedBytes() == 0, "Cancellation leaked memory");
    }

    private static void refusals() {
        var rows = hiddenBounds(8);
        // A one-unit band is not an equality and cannot cancel another row.
        var face = rows.get(1);
        rows.set(1, new ExactLinearProgram.Constraint(face.terms(), face.upper().add(BigInteger.ONE)));
        var budget = budget(40_000_000, 128L << 20);
        try (var compiler = new CountRowSparsifier(rows, budget)) {
            check(compiler.compile() == rows, "Inequality band was treated as an equality");
        }
        rows = hiddenBounds(8);
        var terms = new TreeMap<>(rows.get(0).terms());
        terms.put(0, BigInteger.ZERO);
        rows.set(0, new ExactLinearProgram.Constraint(terms, rows.get(0).upper()));
        try (var compiler = new CountRowSparsifier(rows, budget)) {
            check(compiler.compile() == rows, "Noncanonical zero support was used");
        }
        rows = hiddenBounds(8);
        terms = new TreeMap<>(rows.get(0).terms());
        terms.put(0, BigInteger.ONE.shiftLeft(4096));
        rows.set(0, new ExactLinearProgram.Constraint(terms, rows.get(0).upper()));
        try (var compiler = new CountRowSparsifier(rows, budget)) {
            check(compiler.compile() == rows, "Wide coefficient exceeded reserved scratch representation");
        }
        check(budget.reservedBytes() == 0, "Non-equality refusal memory leak");
    }

    private static void benchmark() {
        var rows = hiddenBounds(32);
        var lo = new BigInteger[32]; var hi = new BigInteger[32];
        Arrays.fill(lo, BigInteger.ZERO); Arrays.fill(hi, BigInteger.valueOf(100));
        var budget = budget(40_000_000, 128L << 20);
        try (var views = CountModelViews.create(rows, lo, hi, budget)) {
            check(views != null, "View admission failed");
            views.compileLight();
            var sparse = views.available().stream().filter(v -> v.name().equals("sparse_equalities")).findFirst().orElseThrow();
            check(sparse.shape().terms() * 4 < rows.stream().mapToLong(r -> r.terms().size()).sum(), "Insufficient sparsification");
            var point = new BigInteger[32];
            for (int i = 0; i < 32; i++) point[i] = BigInteger.valueOf(i % 2);
            check(Arrays.equals(point, views.restoreAndCheck(sparse, point)), "Original coordinates changed");
            var baseline = solve(rows, lo, hi);
            var candidate = solve(sparse.rows(), lo, hi);
            check(candidate.solved && candidate.work < baseline.work, "Sparse propagation did not save work");
            System.out.println("Shared-equality propagation: " + baseline + " -> " + candidate + "; compile=" + budget.nodes());
        }
        check(budget.reservedBytes() == 0, "View memory leak");
    }

    private record Solve(boolean solved, long work) {}

    private static Solve solve(List<ExactLinearProgram.Constraint> rows, BigInteger[] lo, BigInteger[] hi) {
        var budget = budget(40_000_000, 128L << 20);
        boolean solved;
        try (var solver = new CountLcg(rows, lo, hi, budget, 262144)) {
            while (!solver.step()) { }
            solved = solver.counts() != null;
            check(!solver.infeasible(), "Known witness rejected");
            if (solved) check(valid(rows, solver.counts()), "Invalid witness");
        }
        check(budget.reservedBytes() == 0, "Search memory leak");
        return new Solve(solved, budget.nodes());
    }

    private static void equation(List<ExactLinearProgram.Constraint> rows, Map<Integer, BigInteger> terms, BigInteger rhs) {
        rows.add(new ExactLinearProgram.Constraint(terms, rhs));
        var reverse = new TreeMap<Integer, BigInteger>();
        terms.forEach((id, value) -> reverse.put(id, value.negate()));
        rows.add(new ExactLinearProgram.Constraint(reverse, rhs.negate()));
    }

    private static boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] point) {
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(point[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
