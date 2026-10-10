// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent Gram determinants check insertion choices and retained factor updates. */
public final class CountLatticePotentialTest {
    private record Factors(ExactRational[][] mu, ExactRational[] norms) {}
    private record Reduced(List<BigInteger[]> basis, long work, long progress, int pauses, int deepSwaps) {}

    public static void main(String[] args) throws Exception {
        int choices = 0, swaps = 0, pauses = 0;
        for (int seed = 0; seed < 96; seed++) {
            var basis = basis(3 + seed % 7, seed);
            var factors = factors(basis);
            for (int pivot = 1; pivot < basis.size(); pivot++) {
                int expected = choice(basis, pivot);
                var budget = budget(8_000_000, 1L << 20);
                try (var scan = new CountLatticePotential(factors.mu, factors.norms, pivot, budget)) {
                    while (!scan.step()) {}
                    check(!scan.declined() && scan.insertion() == expected, "Wrong insertion: " + seed + "/" + pivot);
                    long work = budget.nodes();
                    check(scan.step() && budget.nodes() == work, "Completed scan repeated work");
                    if (expected >= 0) choices++;
                }
                check(budget.reservedBytes() == 0, "Scan leaked");
            }
        }
        var executor = Executors.newSingleThreadExecutor();
        try {
            for (int seed = 0; seed < 24; seed++) {
                var input = basis(5 + seed % 5, 1000 + seed);
                var whole = reduce(input, Long.MAX_VALUE, null);
                var sliced = reduce(input, 7, seed % 2 == 0 ? executor : null);
                check(Arrays.deepEquals(whole.basis.toArray(), sliced.basis.toArray()), "Handoff changed basis");
                check(whole.work == sliced.work && whole.progress == sliced.progress, "Handoff changed work/progress");
                check(determinant(input).abs().equals(determinant(whole.basis).abs()), "Reduction changed lattice determinant");
                check(choice(whole.basis, whole.basis.size() - 1) < 0, "Reduction left a useful last insertion");
                swaps += sliced.deepSwaps;
                pauses += sliced.pauses;
            }
        } finally { executor.shutdownNow(); }
        check(choices > 80 && swaps > 5 && pauses > 100, "Insufficient insertion/continuation coverage");
        limits();
        ownerLimits();
        reuse();
        rounding();
        System.out.println("Lattice potential: 96 determinant-oracle bases; choices=" + choices +
                "; 24 whole/sliced reductions, deep swaps=" + swaps + "; pauses=" + pauses +
                "; exact factor updates, thread handoff, cancellation, precision and memory limits passed");
    }

    private static int choice(List<BigInteger[]> basis, int pivot) {
        // Rebuild the complete prefix Gram determinants after each proposed
        // permutation. This does not use the production potential recurrence.
        BigInteger original = potential(basis), best = original;
        int target = -1;
        for (int j = pivot - 1; j >= 0; j--) {
            var changed = new ArrayList<>(basis);
            changed.add(j, changed.remove(pivot));
            var value = potential(changed);
            if (value.compareTo(best) < 0) { best = value; target = j; }
        }
        return best.shiftLeft(2).compareTo(original.multiply(BigInteger.valueOf(3))) < 0 ? target : -1;
    }

    private static BigInteger potential(List<BigInteger[]> basis) {
        BigInteger result = BigInteger.ONE;
        for (int n = 1; n <= basis.size(); n++) {
            var gram = new ArrayList<BigInteger[]>();
            for (int i = 0; i < n; i++) {
                var row = new BigInteger[n];
                for (int j = 0; j < n; j++) {
                    row[j] = BigInteger.ZERO;
                    for (int k = 0; k < basis.get(i).length; k++) row[j] = row[j].add(basis.get(i)[k].multiply(basis.get(j)[k]));
                }
                gram.add(row);
            }
            result = result.multiply(determinant(gram));
        }
        return result;
    }

    private static BigInteger determinant(List<BigInteger[]> matrix) {
        var a = matrix.stream().map(BigInteger[]::clone).toArray(BigInteger[][]::new);
        BigInteger divisor = BigInteger.ONE;
        int sign = 1, n = a.length;
        for (int k = 0; k < n - 1; k++) {
            int pivot = k;
            while (pivot < n && a[pivot][k].signum() == 0) pivot++;
            if (pivot == n) return BigInteger.ZERO;
            if (pivot != k) { var old = a[k]; a[k] = a[pivot]; a[pivot] = old; sign = -sign; }
            for (int i = k + 1; i < n; i++) for (int j = k + 1; j < n; j++) {
                var value = a[i][j].multiply(a[k][k]).subtract(a[i][k].multiply(a[k][j])).divideAndRemainder(divisor);
                check(value[1].signum() == 0, "Non-integral Bareiss oracle");
                a[i][j] = value[0];
            }
            divisor = a[k][k];
        }
        return a[n - 1][n - 1].multiply(BigInteger.valueOf(sign));
    }

    private static List<BigInteger[]> basis(int n, int seed) {
        var random = new Random(7101026L + seed);
        var result = new ArrayList<BigInteger[]>();
        for (int i = 0; i < n; i++) {
            var row = new BigInteger[n];
            for (int j = 0; j < n; j++) row[j] = BigInteger.valueOf(j < i ? 0 : j == i ? 1 + random.nextInt(20) : random.nextInt(41) - 20);
            result.add(row);
        }
        Collections.shuffle(result, random);
        return result;
    }

    private static Factors factors(List<BigInteger[]> basis) {
        var gram = new CountGramSchmidt(basis, () -> {});
        while (!gram.step()) {}
        check(!gram.dependent(), "Independent basis declined");
        return new Factors(gram.mu(), gram.norms());
    }

    @SuppressWarnings("unchecked")
    private static Reduced reduce(List<BigInteger[]> input, long quantum, ExecutorService executor) throws Exception {
        int n = input.size(), pauses = 0, swaps = 0;
        var low = new BigInteger[n]; Arrays.fill(low, BigInteger.ZERO);
        var high = new BigInteger[n]; Arrays.fill(high, BigInteger.TEN);
        var budget = budget(8_000_000, 2L << 20);
        var basis = input.stream().map(BigInteger[]::clone).toList();
        var factors = factors(basis);
        BigInteger insertionPotential = null;
        // Isolate the reduction boundary with a known integer lattice. Real
        // original-coordinate witnesses are separately pinned in the corpus.
        try (var search = new CountAffineLattice(List.of(), low, high, budget, 1024).retained()) {
            ((List<BigInteger[]>) get(search, "basis")).addAll(basis);
            injectSupports(search, basis);
            set(search, "point", low.clone()); set(search, "mu", factors.mu); set(search, "norms", factors.norms);
            set(search, "deep", true); set(search, "strengthened", true); set(search, "phase", 2);
            while ((int) get(search, "phase") != 3) {
                int from = (int) get(search, "insertionFrom");
                if (from >= 0 && from == (int) get(search, "pivot"))
                    insertionPotential = potential((List<BigInteger[]>) get(search, "basis"));
                long progress = search.progress();
                if (executor == null) search.step();
                else executor.submit(search::step).get();
                if (from >= 0) {
                    swaps++;
                    var current = (List<BigInteger[]>) get(search, "basis");
                    var rebuilt = factors(current);
                    check(Arrays.deepEquals(rebuilt.mu, (ExactRational[][]) get(search, "mu")) &&
                            Arrays.equals(rebuilt.norms, (ExactRational[]) get(search, "norms")), "Insertion corrupted Gram factors");
                    boolean finished = (int) get(search, "insertionFrom") < 0;
                    check(search.progress() - progress == (finished ? 1 : 0), "Partial insertion earned progress");
                    if (finished) check(potential(current).shiftLeft(2).compareTo(insertionPotential.multiply(BigInteger.valueOf(3))) < 0,
                            "Completed insertion did not certify its potential decrease");
                }
                if (search.paused()) {
                    long work = budget.nodes(), memory = budget.reservedBytes(), checkpoint = search.progress();
                    check(search.step() && search.step(), "Paused reduction ran");
                    check(work == budget.nodes() && memory == budget.reservedBytes() && checkpoint == search.progress(), "Paused poll changed state");
                    search.resume(quantum);
                    check(++pauses < 50000, "Reduction stalled");
                }
            }
            var result = new Reduced(new ArrayList<>((List<BigInteger[]>) get(search, "basis")), budget.nodes(), search.progress(), pauses, swaps);
            search.close();
            check(budget.reservedBytes() == 0, "Retained scan leaked");
            return result;
        }
    }

    private static void limits() throws Exception {
        var factors = factors(basis(9, 811));
        for (long bytes : new long[] {1, 32768}) {
            var budget = budget(100000, bytes);
            try (var scan = new CountLatticePotential(factors.mu, factors.norms, 8, budget)) {
                check(scan.step() && scan.declined() && scan.insertion() < 0, "Optional memory refusal ignored");
            }
            check(budget.reservedBytes() == 0, "Memory refusal leaked");
        }
        var cancelled = new AtomicBoolean();
        var budget = new PlanningBudget(0, 100000, 1L << 20, cancelled::get, System::nanoTime);
        try (var scan = new CountLatticePotential(factors.mu, factors.norms, 8, budget)) {
            check(!scan.step(), "No retained scan to cancel");
            cancelled.set(true);
            boolean rejected = false;
            try { scan.step(); } catch (CancellationException expected) { rejected = true; }
            check(rejected, "Cancelled scan continued");
        }
        check(budget.reservedBytes() == 0, "Cancelled scan leaked");
        budget = budget(1, 1L << 20);
        try (var scan = new CountLatticePotential(factors.mu, factors.norms, 8, budget)) {
            boolean rejected = false;
            try { while (!scan.step()) {} } catch (PlanningBudget.Exhausted expected) { rejected = true; }
            check(rejected, "Scan ignored hard request limit");
        }
        check(budget.reservedBytes() == 0, "Hard cutoff leaked");
        var huge = ExactRational.of(BigInteger.ONE.shiftLeft(1500));
        var mu = new ExactRational[][] {{ExactRational.ZERO, ExactRational.ZERO}, {huge, ExactRational.ZERO}};
        budget = budget(100000, 1L << 20);
        try (var scan = new CountLatticePotential(mu, new ExactRational[] {
                new ExactRational(BigInteger.ONE, BigInteger.ONE.shiftLeft(1500)), ExactRational.ONE}, 1, budget)) {
            while (!scan.step()) {}
            check(scan.declined() && scan.insertion() < 0, "Precision refusal emitted a choice");
            check(mu[1][0] == huge, "Refused scan mutated factors");
        }
        check(budget.reservedBytes() == 0, "Precision refusal leaked");
        for (int offset : new int[] {-1, 0, 1}) for (int denominatorBase : new int[] {1, 3, 11}) {
            // Outward intervals straddle the strict 3/4 boundary. The exact
            // fallback must distinguish both neighbors from equality.
            var denominator = BigInteger.ONE.shiftLeft(400);
            var ratio = new ExactRational(denominator.multiply(BigInteger.valueOf(3)).shiftRight(2).add(BigInteger.valueOf(offset)), denominator);
            var norm = new ExactRational(BigInteger.ONE, BigInteger.valueOf(denominatorBase));
            var coefficient = denominatorBase == 1 ? ExactRational.ZERO : new ExactRational(BigInteger.ONE, BigInteger.valueOf(3));
            budget = budget(100000, 1L << 20);
            try (var scan = new CountLatticePotential(new ExactRational[][] {
                    {ExactRational.ZERO, ExactRational.ZERO}, {coefficient, ExactRational.ZERO}},
                    new ExactRational[] {norm, norm.multiply(ratio.subtract(coefficient.multiply(coefficient)))}, 1, budget)) {
                while (!scan.step()) {}
                check(!scan.declined() && scan.insertion() == (offset < 0 ? 0 : -1), "Strict boundary rounded: " + offset);
                if (offset < 0) check((boolean) get(scan, "exact"), "Ambiguous interval did not use exact arithmetic");
            }
            check(budget.reservedBytes() == 0, "Boundary comparison leaked");
        }
        int n = 20;
        var zeroMu = new ExactRational[n][n]; for (var row : zeroMu) Arrays.fill(row, ExactRational.ZERO);
        var norms = new ExactRational[n]; Arrays.fill(norms, ExactRational.ONE); norms[n - 1] = ExactRational.of(BigInteger.ONE.shiftLeft(1024));
        budget = budget(100000, 1L << 20);
        try (var scan = new CountLatticePotential(zeroMu, norms, n - 1, budget)) {
            while (!scan.step()) {}
            check(scan.declined() && scan.insertion() < 0, "Unbounded exact product accepted");
        }
        check(budget.reservedBytes() == 0, "Wide product refusal leaked");
    }

    @SuppressWarnings("unchecked")
    private static CountAffineLattice injected(List<BigInteger[]> input, PlanningBudget budget) throws Exception {
        var low = new BigInteger[input.size()]; Arrays.fill(low, BigInteger.ZERO);
        var high = new BigInteger[input.size()]; Arrays.fill(high, BigInteger.TEN);
        var basis = input.stream().map(BigInteger[]::clone).toList();
        var factors = factors(basis);
        var search = new CountAffineLattice(List.of(), low, high, budget, 1024).retained();
        ((List<BigInteger[]>) get(search, "basis")).addAll(basis);
        injectSupports(search, basis);
        set(search, "point", low.clone()); set(search, "repairPoint", low.clone());
        set(search, "mu", factors.mu); set(search, "norms", factors.norms);
        set(search, "deep", true); set(search, "strengthened", true); set(search, "phase", 2);
        return search;
    }

    @SuppressWarnings("unchecked")
    private static void injectSupports(CountAffineLattice search, List<BigInteger[]> basis) throws Exception {
        for (var row : basis) {
            var support = new long[(row.length + 63) / 64];
            for (int i = 0; i < row.length; i++) if (row[i].signum() != 0) support[i >>> 6] |= 1L << (i & 63);
            ((List<long[]>) get(search, "supports")).add(support);
        }
    }

    private static void ownerLimits() throws Exception {
        int interrupted = 0;
        for (boolean duringInsertion : new boolean[] {false, true}) {
            boolean found = false;
            for (int seed = 0; seed < 24 && !found; seed++) {
                var cancelled = new AtomicBoolean();
                var budget = new PlanningBudget(0, 8_000_000, 1L << 20, cancelled::get, System::nanoTime);
                try (var search = injected(basis(9, 1000 + seed), budget)) {
                    while ((int) get(search, "phase") != 3) {
                        if (search.paused()) search.resume(37);
                        search.step();
                        if ((int) get(search, "phase") == 7 && !search.paused() &&
                                (duringInsertion ? (int) get(search, "insertionFrom") > (int) get(search, "insertionTo") + 1 :
                                        get(search, "potential") != null)) {
                            cancelled.set(true);
                            boolean rejected = false;
                            try { search.step(); } catch (CancellationException expected) { rejected = true; }
                            check(rejected, "Owner ignored cancellation during refinement");
                            found = true; interrupted++; break;
                        }
                    }
                }
                check(budget.reservedBytes() == 0, "Cancelled owner leaked a potential scan");
            }
            check(found, "Did not reach cancellation boundary: " + duringInsertion);
        }
        var budget = budget(1_000_000, 32768);
        try (var search = injected(basis(5, 101), budget)) {
            while ((int) get(search, "phase") != 4) {
                if (search.paused()) search.resume(37);
                check(!search.step() || search.paused(), "Optional refusal terminated the owner");
            }
            check(!(boolean) get(search, "deep") && get(search, "kernel") != null, "Memory refusal lost the ordinary kernel path");
        }
        check(budget.reservedBytes() == 0 && interrupted == 2, "Owner limit coverage failed");
    }

    private static void reuse() {
        var factors = factors(basis(9, 1285));
        var freshBudget = budget(8_000_000, 1L << 20);
        var sharedBudget = budget(8_000_000, 1L << 20);
        try (var workspace = new CountLatticePotential.Workspace(factors.norms, sharedBudget)) {
            for (int iteration = 0; iteration < 128; iteration++) {
                int pivot = 1 + iteration % 8;
                try (var fresh = new CountLatticePotential(factors.mu, factors.norms, pivot, freshBudget);
                     var reused = new CountLatticePotential(factors.mu, factors.norms, pivot, sharedBudget, workspace)) {
                    while (!fresh.step()) {}
                    while (!reused.step()) {}
                    check(fresh.declined() == reused.declined() && fresh.insertion() == reused.insertion(), "Stale cached norm after swap");
                }
                check(sharedBudget.reservedBytes() > 0, "Child disposed owner's bounds");
                // Simulate the immutable replacements made by adjacent swaps;
                // half the campaign reuses an entirely unchanged factorization.
                if (iteration >= 64) {
                    int index = iteration % 9;
                    factors.norms[index] = factors.norms[index].multiply(new ExactRational(BigInteger.valueOf(7), BigInteger.valueOf(5)));
                }
            }
            check(sharedBudget.nodes() < freshBudget.nodes(), "Reuse did not avoid paid conversions");
            for (boolean differentBudget : new boolean[] {false, true}) {
                boolean rejected = false;
                try (var unused = new CountLatticePotential(factors.mu, differentBudget ? factors.norms : factors.norms.clone(),
                        8, differentBudget ? freshBudget : sharedBudget, workspace)) {}
                catch (IllegalArgumentException expected) { rejected = true; }
                check(rejected, "Bounds crossed a factorization/request scope");
            }
        }
        check(freshBudget.reservedBytes() == 0 && sharedBudget.reservedBytes() == 0, "Reused bounds leaked");
        System.out.println("Potential bound reuse: 128 fresh/cached scans agree through immutable replacements; work=" +
                freshBudget.nodes() + "->" + sharedBudget.nodes());
    }

    private static void rounding() throws Exception {
        var factors = factors(basis(3, 15));
        var budget = budget(100000, 1L << 20);
        var up = CountLatticePotential.class.getDeclaredMethod("up", BigInteger.class);
        var down = CountLatticePotential.class.getDeclaredMethod("down", BigInteger.class);
        up.setAccessible(true); down.setAccessible(true);
        BigInteger unit = BigInteger.ONE.shiftLeft(128);
        try (var scan = new CountLatticePotential(factors.mu, factors.norms, 2, budget)) {
            for (int bit : new int[] {0, 63, 127, 128, 129, 2048, 16383}) for (int offset : new int[] {-1, 0, 1}) {
                var input = BigInteger.ONE.shiftLeft(bit).add(BigInteger.valueOf(offset));
                var quotient = input.divideAndRemainder(unit);
                var ceil = quotient[1].signum() == 0 ? quotient[0] : quotient[0].add(BigInteger.ONE);
                check(up.invoke(scan, input).equals(ceil) && down.invoke(scan, input).equals(quotient[0]),
                        "Outward rounding lost a discarded bit: " + bit + "/" + offset);
            }
        }
        check(budget.reservedBytes() == 0, "Rounding check leaked");
    }

    private static Field field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Object get(Object object, String name) throws Exception { return field(object, name).get(object); }
    private static void set(Object object, String name, Object value) throws Exception { field(object, name).set(object, value); }
    private static PlanningBudget budget(long work, long memory) { return new PlanningBudget(0, work, memory, () -> false, System::nanoTime); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
