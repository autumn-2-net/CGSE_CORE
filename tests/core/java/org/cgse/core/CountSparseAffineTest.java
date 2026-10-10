// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Sparse state must agree with the dense integer vectors at every observed checkpoint. */
public final class CountSparseAffineTest {
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {}
    private record Result(BigInteger[] counts, long work, long progress, int pauses) {}
    private static final Field BASIS = field("basis"), SUPPORTS = field("supports"), PHASE = field("phase");
    private static final Field EQUATIONS = field("equations"), EQUATION = field("equation"), EXACT = field("exactEquations"), POINT = field("point");
    private static long audits;
    private static int cancellations, sizeReductions, swaps;

    public static void main(String[] args) throws Exception {
        for (int n : new int[] {8, 31, 63, 64, 65, 96, 127, 128}) for (int seed = 0; seed < 4; seed++) {
            Model model = model(n, seed);
            Result full = solve(model, 131072, 131072, null);
            Result sliced = solve(model, 1024, 17, null);
            same(full, sliced);
            check(full.counts != null, "Planted sparse model declined: " + n + "/" + seed);
            if (n >= 31) check(sliced.pauses > 0, "Continuation not exercised");
        }
        // Dense signed balances exercise supports becoming empty/nonempty
        // during size reduction and prefix/deep swaps, not only unit columns.
        for (int seed = 0; seed < 48; seed++) {
            Model model = dense(seed);
            same(solve(model, 131072, 131072, null), solve(model, 1024, 113, null));
        }
        var pool = Executors.newFixedThreadPool(2);
        try {
            Model model = model(65, 5);
            same(solve(model, 1024, 73, null), solve(model, 1024, 73, pool));
        } finally { pool.shutdownNow(); }
        sparseWork();
        limits();
        check(cancellations > 0 && sizeReductions > 0 && swaps > 0, "Missing basis mutation coverage");
        System.out.println("Sparse affine: 81 paired whole/sliced/handoff models; dense-state audits=" + audits +
                "; cancellations=" + cancellations + "; size reductions=" + sizeReductions + "; swaps=" + swaps);
    }

    private static Model model(int n, int seed) {
        var random = new Random(711001L + seed * 97L + n);
        var low = new BigInteger[n];
        var high = new BigInteger[n];
        var ids = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) {
            low[i] = BigInteger.valueOf(i % 7 - 3);
            if ((seed & 1) != 0) low[i] = low[i].add(BigInteger.ONE.shiftLeft(90).multiply(BigInteger.valueOf(i % 2 == 0 ? 1 : -1)));
            high[i] = low[i].add(BigInteger.valueOf(12));
            ids.add(i);
        }
        Collections.shuffle(ids, random);
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int j = 4; j < n; j++) {
            int i = ids.get(j), free = ids.get(j % 4);
            BigInteger a = BigInteger.valueOf(2 + j % 2);
            var terms = new TreeMap<Integer, BigInteger>();
            terms.put(i, BigInteger.ONE);
            terms.put(free, a.negate());
            equation(rows, terms, low[i].subtract(a.multiply(low[free])));
        }
        Collections.shuffle(rows, random);
        return new Model(rows, low, high);
    }

    private static Model dense(int seed) {
        var random = new Random(23777L + seed);
        int n = 6 + seed % 7;
        var low = new BigInteger[n];
        var high = new BigInteger[n];
        var planted = new BigInteger[n];
        for (int i = 0; i < n; i++) {
            low[i] = BigInteger.valueOf(-3);
            high[i] = BigInteger.valueOf(12);
            planted[i] = BigInteger.valueOf(random.nextInt(16) - 3);
        }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int j = 0; j < n - 3; j++) {
            var terms = new TreeMap<Integer, BigInteger>();
            BigInteger rhs = BigInteger.ZERO;
            for (int i = 0; i < n; i++) {
                var a = BigInteger.valueOf(random.nextInt(9) - 4);
                if (a.signum() != 0) terms.put(i, a);
                rhs = rhs.add(a.multiply(planted[i]));
            }
            equation(rows, terms, rhs);
        }
        Collections.shuffle(rows, random);
        return new Model(rows, low, high);
    }

    private static void equation(List<ExactLinearProgram.Constraint> rows, Map<Integer, BigInteger> terms, BigInteger rhs) {
        rows.add(new ExactLinearProgram.Constraint(terms, rhs));
        var reverse = new TreeMap<Integer, BigInteger>();
        terms.forEach((i, a) -> reverse.put(i, a.negate()));
        rows.add(new ExactLinearProgram.Constraint(reverse, rhs.negate()));
    }

    private static Result solve(Model model, long first, long quantum, ExecutorService pool) throws Exception {
        var budget = new PlanningBudget(0, 20_000_000, 128L << 20, () -> false, System::nanoTime);
        Result result;
        try (var search = new CountAffineLattice(model.rows, model.low, model.high, budget, first).retained()) {
            int pauses = 0;
            while (true) {
                int phase = PHASE.getInt(search);
                int equation = EQUATION.getInt(search);
                long previousProgress = search.progress();
                List<BigInteger[]> before = new ArrayList<>(basis(search));
                long[][] oldSupports = supports(search).stream().map(long[]::clone).toArray(long[][]::new);
                boolean done = pool == null ? search.step() : pool.submit(search::step).get(30, TimeUnit.SECONDS);
                audit(search, model.low.length);
                if (equation != EQUATION.getInt(search) ||
                        ((phase == 2 || phase == 6 || phase == 7) && search.progress() > previousProgress)) auditKernel(search);
                var after = basis(search);
                if (before.size() == after.size() && !before.isEmpty()) {
                    boolean moved = false;
                    for (int j = 0; j < after.size(); j++) {
                        moved |= before.get(j) != after.get(j);
                        int old = identityIndex(before, after.get(j));
                        if (old < 0) continue; // A fresh trial face resets the whole basis.
                        long[] now = supports(search).get(j);
                        for (int w = 0; w < now.length; w++)
                            cancellations += Long.bitCount(oldSupports[old][w] & ~now[w]);
                    }
                    if (moved) swaps++;
                    if ((phase == 2 || phase == 6) && !moved && search.progress() > previousProgress) sizeReductions++;
                }
                if (!done) continue;
                if (!search.paused()) break;
                pauses++;
                long work = budget.searchWork(), progress = search.progress();
                check(search.step() && budget.searchWork() == work && search.progress() == progress, "Paused solver changed state");
                search.resume(quantum);
            }
            BigInteger[] counts = search.counts();
            if (counts != null) valid(model, counts);
            result = new Result(counts, budget.searchWork(), search.progress(), pauses);
        }
        check(budget.reservedBytes() == 0, "Sparse workspace leaked");
        return result;
    }

    private static void audit(CountAffineLattice search, int n) throws Exception {
        var basis = basis(search);
        var support = supports(search);
        check(basis.size() == support.size(), "Unaligned support rows");
        var seen = Collections.newSetFromMap(new IdentityHashMap<BigInteger[], Boolean>());
        for (int j = 0; j < basis.size(); j++) {
            BigInteger[] row = basis.get(j);
            check(seen.add(row), "Aliased mutable basis rows");
            for (int i = 0; i < support.get(j).length * 64; i++) {
                boolean bit = (support.get(j)[i >>> 6] & (1L << (i & 63))) != 0;
                check(bit == (i < n && row[i].signum() != 0), "Stale support at " + j + ":" + i);
            }
        }
        audits++;
    }

    private static void limits() {
        Model m = model(65, 1);
        for (long memory : new long[] {1, 4096, 1L << 20}) {
            var budget = new PlanningBudget(0, 4_000_000, memory, () -> false, System::nanoTime);
            try (var search = new CountAffineLattice(m.rows, m.low, m.high, budget, 1024).retained()) {
                while (!search.step()) {}
                check(search.counts() == null && !search.paused(), "Memory refusal entered a basis");
            }
            check(budget.reservedBytes() == 0, "Memory refusal leaked");
        }
        for (int stop : new int[] {1, 100, 1000, 10000}) {
            var checks = new AtomicInteger();
            var budget = new PlanningBudget(0, 4_000_000, 64L << 20, () -> checks.incrementAndGet() >= stop, System::nanoTime);
            boolean cancelled = false;
            try (var search = new CountAffineLattice(m.rows, m.low, m.high, budget, 1024).retained()) {
                do {
                    if (search.paused()) search.resume(4096);
                    while (!search.step()) {}
                } while (search.paused());
            } catch (CancellationException expected) { cancelled = true; }
            check(cancelled && budget.reservedBytes() == 0, "Cancellation or release failed");
        }
    }

    @SuppressWarnings("unchecked")
    private static void auditKernel(CountAffineLattice search) throws Exception {
        var equations = (List<ExactLinearProgram.Constraint>) EQUATIONS.get(search);
        var point = (BigInteger[]) POINT.get(search);
        for (int r = 0; r < Math.min(EQUATION.getInt(search), EXACT.getInt(search)); r++) {
            var row = equations.get(r);
            BigInteger value = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) value = value.add(term.getValue().multiply(point[term.getKey()]));
            check(value.equals(row.upper()), "Elimination point lost an exact equation");
            for (var direction : basis(search)) {
                value = BigInteger.ZERO;
                for (var term : row.terms().entrySet()) value = value.add(term.getValue().multiply(direction[term.getKey()]));
                check(value.signum() == 0, "Direction left the exact integer kernel");
            }
        }
    }

    private static void sparseWork() throws Exception {
        var low = new BigInteger[128]; Arrays.fill(low, BigInteger.ZERO);
        var high = new BigInteger[128]; Arrays.fill(high, BigInteger.TEN);
        var u = low.clone(); var v = low.clone();
        u[0] = BigInteger.valueOf(14); u[63] = BigInteger.valueOf(-35); u[9] = BigInteger.ONE;
        u[127] = BigInteger.TEN;
        v[0] = BigInteger.TWO; v[63] = BigInteger.valueOf(-5);
        v[64] = BigInteger.valueOf(3); v[127] = BigInteger.valueOf(-7);
        var unchanged = v.clone();
        var budget = new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime);
        var subtract = CountAffineLattice.class.getDeclaredMethod("subtractBasis", int.class, int.class, BigInteger.class);
        subtract.setAccessible(true);
        try (var search = new CountAffineLattice(List.of(), low, high, budget)) {
            for (var row : List.of(u, v)) {
                basis(search).add(row);
                var bits = new long[2];
                for (int i = 0; i < row.length; i++) if (row[i].signum() != 0) bits[i >>> 6] |= 1L << (i & 63);
                supports(search).add(bits);
            }
            for (int q : new int[] {7, -3}) {
                var expected = u.clone();
                for (int i = 0; i < expected.length; i++) expected[i] = expected[i].subtract(BigInteger.valueOf(q).multiply(v[i]));
                long before = budget.searchWork();
                subtract.invoke(search, 0, 1, BigInteger.valueOf(q));
                check(budget.searchWork() - before == 6, "Expected two support words and four changed coordinates");
                check(Arrays.equals(u, expected) && Arrays.equals(v, unchanged), "Sparse subtraction changed dense arithmetic");
                audit(search, 128);
            }
        }
        check(budget.reservedBytes() == 0, "Isolated arithmetic retained memory");
    }

    private static void valid(Model model, BigInteger[] values) {
        for (int i = 0; i < values.length; i++)
            check(values[i].compareTo(model.low[i]) >= 0 && values[i].compareTo(model.high[i]) <= 0, "Invalid domain");
        for (var row : model.rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(values[term.getKey()]));
            check(sum.compareTo(row.upper()) <= 0, "Invalid original equality");
        }
    }

    private static void same(Result a, Result b) {
        check(Arrays.equals(a.counts, b.counts) && a.work == b.work && a.progress == b.progress, "Continuation changed witness/work/progress");
    }
    private static int identityIndex(List<BigInteger[]> rows, BigInteger[] row) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i) == row) return i;
        return -1;
    }
    @SuppressWarnings("unchecked") private static List<BigInteger[]> basis(CountAffineLattice search) throws Exception { return (List<BigInteger[]>) BASIS.get(search); }
    @SuppressWarnings("unchecked") private static List<long[]> supports(CountAffineLattice search) throws Exception { return (List<long[]>) SUPPORTS.get(search); }
    private static Field field(String name) {
        try {
            var field = CountAffineLattice.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
