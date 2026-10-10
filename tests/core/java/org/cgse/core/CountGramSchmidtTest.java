// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Vector orthogonalization is an independent oracle for the integer Gram factors. */
public final class CountGramSchmidtTest {
    private record Factors(ExactRational[][] mu, ExactRational[] norms, long work) {}
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {}
    private record Solved(BigInteger[] counts, long work, int pauses, long progress) {}

    public static void main(String[] args) throws Exception {
        long before = 0, after = 0;
        for (int seed = 0; seed < 256; seed++) {
            int n = 1 + seed % 48, d = 1 + seed % Math.min(n, 12);
            var basis = basis(n, d, seed);
            Factors reference = vectors(basis), candidate = factors(basis);
            check(Arrays.deepEquals(reference.mu, candidate.mu), "Projection differs: " + seed);
            check(Arrays.equals(reference.norms, candidate.norms), "Squared norms differ: " + seed);
            before += reference.work;
            after += candidate.work;
        }
        for (int n : new int[] {49, 64, 96, 128}) {
            var basis = basis(n, 24, 41 + 7 * n);
            Factors reference = vectors(basis), candidate = factors(basis);
            check(Arrays.deepEquals(reference.mu, candidate.mu) && Arrays.equals(reference.norms, candidate.norms), "Wide ambient factors differ");
            before += reference.work;
            after += candidate.work;
        }
        BigInteger large = BigInteger.ONE.shiftLeft(400);
        var huge = List.of(new BigInteger[] {large, BigInteger.ONE, BigInteger.ZERO},
                new BigInteger[] {large.add(BigInteger.ONE), BigInteger.ONE, BigInteger.ONE});
        check(Arrays.deepEquals(vectors(huge).mu, factors(huge).mu), "Near-dependent large integer projections differ");
        check(Arrays.equals(vectors(huge).norms, factors(huge).norms), "Near-dependent large integer norms differ");
        check(after < before, "Integer Gram path did not reduce arithmetic loops");
        for (var bad : List.of(List.<BigInteger[]>of(new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO}),
                List.of(new BigInteger[] {BigInteger.ONE, BigInteger.TWO}, new BigInteger[] {BigInteger.TWO, BigInteger.valueOf(4)}))) {
            var factor = new CountGramSchmidt(bad, () -> {});
            while (!factor.step()) {}
            check(factor.dependent(), "Dependent basis not detected");
        }
        var empty = new CountGramSchmidt(List.of(), () -> { throw new AssertionError("Empty work"); });
        check(empty.step() && !empty.dependent(), "Empty basis");
        sparse();
        handoff();
        expandedAdmission();
        expandedKernel();
        limits();
        System.out.println("Integer Gram factors: 261 matrices match vector oracle; work=" + before + "->" + after +
                "; 49/64/96/128-dimensional affine boxes, rank refusal, continuation, limits and thread handoff passed");
    }

    private static List<BigInteger[]> basis(int n, int d, int seed) {
        var random = new Random(913711L + seed);
        var result = new ArrayList<BigInteger[]>();
        for (int j = 0; j < d; j++) {
            var row = new BigInteger[n];
            for (int i = 0; i < n; i++) {
                // A triangular leading minor guarantees independent rows.
                long value = i < j ? 0 : i == j ? 1 + random.nextInt(4) : random.nextInt(11) - 5;
                row[i] = BigInteger.valueOf(value);
                if (seed % 7 == 0 && i % 3 == 0) row[i] = row[i].shiftLeft(32);
            }
            result.add(row);
        }
        return result;
    }

    private static Factors factors(List<BigInteger[]> basis) {
        long[] work = {0};
        var factor = new CountGramSchmidt(basis, () -> work[0]++);
        while (!factor.step()) {}
        check(!factor.dependent(), "Independent matrix declined");
        long previous = work[0];
        check(factor.step() && work[0] == previous, "Completed factorization charged again");
        return new Factors(factor.mu(), factor.norms(), work[0]);
    }

    private static void sparse() {
        long denseWork = 0, sparseWork = 0;
        for (int seed = 0; seed < 96; seed++) {
            var random = new Random(19671010L + seed);
            int n = 32 + 24 * (seed % 4), d = 4 + seed % 21;
            var columns = new ArrayList<Integer>();
            for (int i = 0; i < n; i++) columns.add(i);
            Collections.shuffle(columns, random);
            var basis = new ArrayList<BigInteger[]>();
            for (int j = 0; j < d; j++) {
                var row = new BigInteger[n];
                Arrays.fill(row, BigInteger.ZERO);
                row[columns.get(j)] = BigInteger.valueOf(j % 2 == 0 ? 2 : -1);
                for (int i = j + 1; i < n; i++) if (random.nextInt(8) == 0)
                    row[columns.get(i)] = BigInteger.valueOf(random.nextInt(11) - 5);
                basis.add(row);
            }
            Collections.shuffle(basis, random);
            Factors expected = vectors(basis), actual = factors(basis);
            check(Arrays.deepEquals(expected.mu, actual.mu) && Arrays.equals(expected.norms, actual.norms), "Sparse factors differ: " + seed);
            // Cost of visiting the full integer-dot and triangular recurrence
            // spaces; the new count includes building both sparse supports.
            denseWork += (long) n * d * (d + 1) / 2 + (long) d * (d - 1) * (d + 1) / 6;
            sparseWork += actual.work;
        }
        var cancelledProjection = List.of(new BigInteger[] {BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO},
                new BigInteger[] {BigInteger.ONE, BigInteger.ONE.negate(), BigInteger.ZERO, BigInteger.ZERO},
                new BigInteger[] {BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO},
                new BigInteger[] {BigInteger.ONE, BigInteger.ONE.negate(), BigInteger.ONE, BigInteger.ONE});
        Factors expected = vectors(cancelledProjection), actual = factors(cancelledProjection);
        check(expected.mu[1][0].signum() == 0, "Missing cancelled projection");
        check(Arrays.deepEquals(expected.mu, actual.mu) && Arrays.equals(expected.norms, actual.norms), "Cancelled projection lost later contributions");
        check(sparseWork < denseWork, "Sparse indexing did not save repeated full-space visits");
        System.out.println("Sparse Gram: 97 independent vector oracles; dense integer-loop work=" + denseWork + "->" + sparseWork);
    }

    private static Factors vectors(List<BigInteger[]> basis) {
        int d = basis.size(), n = basis.get(0).length;
        var orthogonal = new ExactRational[d][n];
        var mu = new ExactRational[d][d];
        var norms = new ExactRational[d];
        long work = 0;
        for (int i = 0; i < d; i++) {
            Arrays.fill(mu[i], ExactRational.ZERO);
            var original = new ExactRational[n];
            for (int k = 0; k < n; k++) original[k] = orthogonal[i][k] = ExactRational.of(basis.get(i)[k]);
            for (int j = 0; j < i; j++) {
                ExactRational product = ExactRational.ZERO;
                for (int k = 0; k < n; k++) { work++; product = product.add(original[k].multiply(orthogonal[j][k])); }
                mu[i][j] = product.divide(norms[j]);
                for (int k = 0; k < n; k++) { work++; orthogonal[i][k] = orthogonal[i][k].subtract(mu[i][j].multiply(orthogonal[j][k])); }
            }
            ExactRational norm = ExactRational.ZERO;
            for (int k = 0; k < n; k++) { work++; norm = norm.add(orthogonal[i][k].multiply(orthogonal[i][k])); }
            norms[i] = norm;
        }
        return new Factors(mu, norms, work);
    }

    private static void handoff() throws Exception {
        var basis = basis(64, 24, 43);
        var expected = factors(basis);
        var work = new AtomicInteger();
        var candidate = new CountGramSchmidt(basis, work::incrementAndGet);
        var pool = Executors.newFixedThreadPool(2);
        try {
            while (!pool.submit(candidate::step).get(10, TimeUnit.SECONDS)) {}
        } finally { pool.shutdownNow(); }
        check(Arrays.deepEquals(expected.mu, candidate.mu()) && Arrays.equals(expected.norms, candidate.norms()) && expected.work == work.get(), "Handoff changed Gram computation");
    }

    private static Model model(int n, boolean shifted) {
        var low = new BigInteger[n];
        var high = new BigInteger[n];
        for (int i = 0; i < n; i++) {
            low[i] = BigInteger.valueOf(i % 7 - 3);
            if (shifted) low[i] = low[i].add(BigInteger.ONE.shiftLeft(90).multiply(BigInteger.valueOf(i % 2 == 0 ? 1 : -1)));
            high[i] = low[i].add(BigInteger.valueOf(12));
        }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int i = 4; i < n; i++) {
            int free = i % 4;
            var terms = Map.of(i, BigInteger.ONE, free, BigInteger.TWO.negate());
            BigInteger rhs = low[i].subtract(low[free].multiply(BigInteger.TWO));
            equation(rows, terms, rhs);
        }
        return new Model(rows, low, high);
    }

    private static void equation(List<ExactLinearProgram.Constraint> rows, Map<Integer, BigInteger> terms, BigInteger rhs) {
        rows.add(new ExactLinearProgram.Constraint(terms, rhs));
        var opposite = new LinkedHashMap<Integer, BigInteger>();
        terms.forEach((id, a) -> opposite.put(id, a.negate()));
        rows.add(new ExactLinearProgram.Constraint(opposite, rhs.negate()));
    }

    private static Solved solve(Model model, long first, long quantum) {
        var budget = budget(20_000_000, 128L << 20);
        BigInteger[] counts;
        int pauses = 0;
        long progress;
        try (var search = new CountAffineLattice(model.rows, model.low, model.high, budget, first).retained()) {
            while (true) {
                while (!search.step()) {}
                if (!search.paused()) break;
                check(search.counts() == null, "Paused witness escaped");
                long work = budget.nodes();
                long previousProgress = search.progress();
                check(search.step() && work == budget.nodes() && previousProgress == search.progress(), "Paused factorization changed work or progress");
                search.resume(quantum);
                check(++pauses < 100000, "No progress");
            }
            counts = search.counts();
            progress = search.progress();
            check(counts != null && valid(model, counts), "Expanded affine model not solved");
        }
        check(budget.reservedBytes() == 0, "Expanded matrix workspace leaked");
        return new Solved(counts, budget.nodes(), pauses, progress);
    }

    private static void expandedAdmission() {
        for (int n : new int[] {49, 64, 96, 128}) for (boolean shifted : new boolean[] {false, true}) {
            Model model = model(n, shifted);
            Solved whole = solve(model, 131072, 131072), sliced = solve(model, 1024, 127);
            check(Arrays.equals(whole.counts, sliced.counts) && whole.work == sliced.work && whole.progress == sliced.progress && sliced.pauses > 0,
                    "Expanded continuation changed result/work/progress");
        }
        // Many syntactically different equations can still have rank two.
        // The admission estimate must not replace the actual post-elimination check.
        var low = new BigInteger[64]; Arrays.fill(low, BigInteger.ZERO);
        var high = new BigInteger[64]; Arrays.fill(high, BigInteger.TEN);
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int i = 1; i <= 24; i++) {
            BigInteger a = BigInteger.valueOf(i);
            equation(rows, Map.of(0, a.multiply(BigInteger.TWO), 1, a), a.multiply(BigInteger.valueOf(7)));
            equation(rows, Map.of(2, a.multiply(BigInteger.TWO), 3, a), a.multiply(BigInteger.valueOf(7)));
        }
        for (var constraints : List.of(rows, rows.subList(0, 4))) {
            var budget = budget(2_000_000, 64L << 20);
            try (var search = new CountAffineLattice(constraints, low, high, budget, 131072).retained()) {
                do {
                    if (search.paused()) search.resume(131072);
                    while (!search.step()) {}
                } while (search.paused());
                check(search.counts() == null, "Large residual kernel bypassed admission");
            }
            check(budget.reservedBytes() == 0, "Rank refusal leaked");
        }
    }

    private static void limits() {
        Model model = model(64, false);
        for (long bytes : new long[] {1, 65536, 1048576}) {
            var budget = budget(1_000_000, bytes);
            try (var search = new CountAffineLattice(model.rows, model.low, model.high, budget).retained()) {
                while (!search.step()) {}
                check(search.counts() == null, "Memory refusal became a candidate");
            }
            check(budget.reservedBytes() == 0, "Memory refusal leaked");
        }
        for (int stop : new int[] {1, 100, 1000, 5000, 12000}) {
            AtomicInteger checks = new AtomicInteger();
            var budget = new PlanningBudget(0, 20_000_000, 64L << 20, () -> checks.incrementAndGet() >= stop, System::nanoTime);
            try (var search = new CountAffineLattice(model.rows, model.low, model.high, budget, 1024).retained()) {
                do {
                    if (search.paused()) search.resume(2048);
                    while (!search.step()) {}
                } while (search.paused());
            } catch (CancellationException expected) {}
            check(budget.reservedBytes() == 0, "Cancelled expanded workspace leaked");
        }
        var budget = budget(35000, 64L << 20);
        try (var search = new CountAffineLattice(model.rows, model.low, model.high, budget, 1024).retained()) {
            do {
                if (search.paused()) search.resume(2048);
                while (!search.step()) {}
            } while (search.paused());
        } catch (PlanningBudget.Exhausted expected) {}
        check(budget.reservedBytes() == 0, "Hard cutoff leaked");
    }

    private static void expandedKernel() {
        for (int n : new int[] {64, 128}) {
            var low = new BigInteger[n]; Arrays.fill(low, BigInteger.ZERO);
            var high = low.clone();
            high[0] = high[2] = BigInteger.TEN;
            high[1] = BigInteger.valueOf(20);
            var first = low.clone(); first[0] = first[1] = BigInteger.ONE;
            var second = low.clone(); second[1] = second[2] = BigInteger.ONE;
            var basis = List.of(first, second);
            var mu = new ExactRational[][] {{ExactRational.ZERO, ExactRational.ZERO},
                    {new ExactRational(BigInteger.ONE, BigInteger.TWO), ExactRational.ZERO}};
            var norms = new ExactRational[] {ExactRational.of(BigInteger.TWO), new ExactRational(BigInteger.valueOf(3), BigInteger.TWO)};
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int r = 0; r < 160; r++) rows.add(new ExactLinearProgram.Constraint(Map.of(r % 2 * 2, BigInteger.ONE.negate()), BigInteger.valueOf(-5)));
            BigInteger[] previous = null;
            long previousWork = -1;
            for (long quantum : new long[] {131072, 17}) {
                var budget = budget(2_000_000, 64L << 20);
                try (var search = new CountKernelSearch(rows, low, high, low, basis, mu, norms, budget, Math.max(2048, quantum)).retained()) {
                    do {
                        if (search.paused()) search.resume(quantum);
                        while (!search.step()) {}
                    } while (search.paused());
                    check(search.counts() != null && valid(new Model(rows, low, high), search.counts()), "Expanded projected kernel invalid");
                    if (previous != null) check(Arrays.equals(previous, search.counts()) && previousWork == budget.nodes(), "Kernel handoff changed trajectory/work");
                    previous = search.counts();
                    previousWork = budget.nodes();
                }
                check(budget.reservedBytes() == 0, "Expanded projected kernel leaked");
            }
        }
        Model over = model(129, false);
        var budget = budget(2_000_000, 64L << 20);
        try (var search = new CountAffineLattice(over.rows, over.low, over.high, budget).retained()) {
            check(search.step() && search.counts() == null && !search.paused(), "Oversized ambient matrix admitted");
        }
        check(budget.reservedBytes() == 0, "Oversized admission leaked");
    }

    private static boolean valid(Model model, BigInteger[] values) {
        for (int i = 0; i < values.length; i++) if (values[i].compareTo(model.low[i]) < 0 || values[i].compareTo(model.high[i]) > 0) return false;
        for (var row : model.rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(values[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private static PlanningBudget budget(long work, long memory) {
        return new PlanningBudget(0, work, memory, () -> false, System::nanoTime);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
