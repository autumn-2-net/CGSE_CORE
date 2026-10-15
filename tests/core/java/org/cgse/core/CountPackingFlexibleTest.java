// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent exhaustive neighborhoods, including zero-cost rounding choices. */
public final class CountPackingFlexibleTest {
    public static void main(String[] args) throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try {
            for (int trial = 0; trial < 256; trial++) {
                var random = new Random(151051L + trial);
                int n = 2 + trial % 13, m = 1 + trial % 8, radius = 1 + trial % 4;
                long seed = random.nextInt(1 << n), flexible = random.nextInt(1 << n);
                if (flexible == 0) flexible = 1;
                long[][] a = new long[m][n]; long[] b = new long[m];
                long planted = random.nextInt(1 << n);
                for (int r = 0; r < m; r++) {
                    for (int i = 0; i < n; i++) {
                        a[r][i] = random.nextInt(31) - 15;
                        if ((planted & (1L << i)) != 0) b[r] += a[r][i];
                    }
                    b[r] += random.nextInt(9) - 4;
                }
                int paid = paidRadius(n, radius, flexible);
                var budget = budget(40_000_000, 64L << 20, () -> false);
                if (paid < 0) {
                    try {
                        new CountPackingExchange(a, b, seed, budget, radius, flexible);
                        throw new AssertionError("Oversized all-roundings domain admitted");
                    } catch (IllegalArgumentException expected) {}
                    check(budget.reservedBytes() == 0, "Admission refusal leaked");
                    continue;
                }
                boolean expected = false;
                for (long x = 0; x < 1L << n; x++)
                    if (allowed(x ^ seed, n, flexible, paid) && valid(a, b, x)) { expected = true; break; }
                Long found = solve(a, b, seed, radius, flexible, budget, trial < 8 ? worker : null);
                check((found != null) == expected, "Contradicted full neighborhood oracle " + trial);
                if (found != null) check(valid(a, b, found) && allowed(found ^ seed, n, flexible, paid), "Invalid witness");
            }
            widerRounding(worker);
            ownedScout(worker);
        } finally { worker.shutdownNow(); }
        boundaries();
        lifecycle();
        System.out.println("256 exhaustive flexible-neighborhood controls, 12-change witnesses, large exact rows, word boundary, handoff, cancellation and cleanup passed");
    }

    private static void widerRounding(ExecutorService worker) throws Exception {
        int n = 20;
        long flexible = 31 | (31L << 10), witness = flexible | (1L << 6) | (1L << 16);
        long[][] a = new long[12][n]; long[] b = new long[12];
        int row = 0;
        for (int i = 0; i < n; i++) if ((witness & (1L << i)) != 0) { a[row][i] = -1; b[row++] = -1; }
        for (long scale : new long[] {1, 1L << 53}) {
            long[][] scaled = new long[a.length][n]; long[] rhs = new long[b.length];
            for (int r = 0; r < a.length; r++) {
                rhs[r] = b[r] * scale;
                for (int i = 0; i < n; i++) scaled[r][i] = a[r][i] * scale;
            }
            var ordinary = budget(40_000_000, 64L << 20, () -> false);
            check(solve(scaled, rhs, 0, 4, 0, ordinary, null) == null, "Four-change face unexpectedly contains twelve-change witness");
            var whole = solve(scaled, rhs, 0, 3, flexible, budget(40_000_000, 64L << 20, () -> false), null);
            var sliced = solve(scaled, rhs, 0, 3, flexible, budget(40_000_000, 64L << 20, () -> false), worker);
            check(whole != null && (whole & witness) == witness && Objects.equals(whole, sliced), "LP-rounding choices lost on handoff");
        }
    }

    private static void boundaries() throws Exception {
        long[][] a = new long[35][64]; long[] b = new long[35];
        a[34][63] = -1; b[34] = -1;
        var found = solve(a, b, 0, 1, Long.MIN_VALUE | 3, budget(40_000_000, 64L << 20, () -> false), null);
        check(found != null && (found & Long.MIN_VALUE) != 0, "Bit 64 or row 35 lost");
        // No right choice repairs this left-side requirement. It is still a
        // satisfiable unrestricted model: restricted closure is not a proof.
        a = new long[1][20]; b = new long[] {-8};
        Arrays.fill(a[0], 0, 10, -1);
        check(solve(a, b, 0, 3, 31 | (31L << 10), budget(40_000_000, 64L << 20, () -> false), null) == null &&
                valid(a, b, 255), "Empty filtered index became an unrestricted conclusion");
        var invalid = budget(40_000_000, 64L << 20, () -> false);
        try {
            new CountPackingExchange(new long[][] {{1, 1}}, new long[] {1}, 0, invalid, 3, 1L << 2);
            throw new AssertionError("Out-of-face flexible bit admitted");
        } catch (IllegalArgumentException expected) {}
        check(invalid.reservedBytes() == 0, "Invalid mask leaked");
    }

    private static void ownedScout(ExecutorService worker) throws Exception {
        var lo = new BigInteger[64]; var hi = new BigInteger[64];
        Arrays.fill(lo, BigInteger.ZERO); Arrays.fill(hi, BigInteger.ZERO);
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        var target = new TreeMap<Integer, BigInteger>();
        for (int i = 0; i < 8; i++) { hi[i] = BigInteger.ONE; target.put(i, BigInteger.ONE.negate()); }
        for (int i = 0; i < 8; i += 2)
            rows.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.TWO, i + 1, BigInteger.TWO), BigInteger.valueOf(3)));
        rows.add(new ExactLinearProgram.Constraint(target, BigInteger.valueOf(-5)));
        var view = new CountModelViews.View("fractional-scout-scope", rows, lo, hi,
                new CountModelViews.Shape(64, 16, 2, 2), null, CountModelViews.Semantics.EQUIVALENT, null);
        var budget = budget(40_000_000, 64L << 20, () -> false);
        try (var core = CountPackingCore.create(view, new CountModelViews.Domains(lo, hi, 0), budget, Long.MAX_VALUE)) {
            while (field(core, "search") == null) check(!core.step(), "Fixture closed before LP neighborhood");
            check((int) field(core, "fractional") > 0, "Fixture has no fractional choices");
            Object original = field(core, "search"), projection = field(core, "coreCoefficients");
            long maximum = (long) field(core, "maximumWork"), normal = (long) field(core, "normalWork");
            // Enter both owned frontiers directly, without burning unrelated
            // DFS work. Their real indices still advance on the worker below.
            invoke(core, "beginScout");
            long limit = (long) field(core, "flexibleLimit");
            check(limit <= normal && limit > (long) field(core, "scoutLimit"), "Unbounded complementary allowance");
            check((boolean) invoke(core, "beginFlexibleScout"), "Complementary scout was not admitted");
            Object search = field(core, "search");
            for (int i = 0; i < 5; i++) { core.resume(1); worker.submit(core::step).get(); }
            check(field(core, "search") == search && (long) field(core, "scoutLimit") == limit &&
                    (long) field(core, "maximumWork") == maximum && (long) field(core, "normalWork") == normal,
                    "Handoff discarded the index or replenished a ceiling");
            check(!(boolean) invoke(core, "beginFlexibleScout"), "Complementary attempt was renewed");
            invoke(core, "endScout");
            check(field(core, "search") == original && field(core, "coreCoefficients") == projection &&
                    !(boolean) field(core, "flexibleScout"), "Original exact frontier was not restored");
            check(core.counts() == null, "Local neighborhood manufactured an incumbent");
        }
        check(budget.reservedBytes() == 0, "Saved frontier or flexible index leaked");
    }

    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static Object invoke(Object object, String name) throws Exception {
        var method = object.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(object);
    }

    private static void lifecycle() {
        long[][] a = new long[2][32]; long[] b = {16, -16};
        Arrays.fill(a[0], 1); Arrays.fill(a[1], -1);
        long flexible = 15 | (15L << 16);
        var tiny = budget(40_000_000, 1, () -> false);
        try (var search = new CountPackingExchange(a, b, 0, tiny, 3, flexible)) {
            check(search.step() && !search.feasible(), "Memory refusal reported a witness");
        }
        check(tiny.reservedBytes() == 0, "Memory refusal leaked");
        var stop = new AtomicBoolean();
        var cancelled = budget(40_000_000, 64L << 20, stop::get);
        try (var search = new CountPackingExchange(a, b, 0, cancelled, 3, flexible)) {
            for (int i = 0; i < 20; i++) check(!search.step(), "Fixture closed before cancellation");
            stop.set(true);
            try { search.step(); throw new AssertionError("Ignored cancellation"); }
            catch (CancellationException expected) {}
        }
        check(cancelled.reservedBytes() == 0, "Cancelled index leaked");
        var exhausted = budget(512, 64L << 20, () -> false);
        try {
            try (var search = new CountPackingExchange(a, b, 0, exhausted, 3, flexible)) {
                while (!search.step()) {}
                throw new AssertionError("Budget limit returned a completed search");
            }
        } catch (PlanningBudget.Exhausted expected) {}
        check(exhausted.reservedBytes() == 0, "Interrupted construction leaked");
    }

    private static Long solve(long[][] a, long[] b, long seed, int radius, long flexible,
                              PlanningBudget budget, ExecutorService worker) throws Exception {
        try (var search = new CountPackingExchange(a, b, seed, budget, radius, flexible)) {
            while (!(worker == null ? search.step() : worker.submit(search::step).get())) {}
            return search.feasible() ? search.assignment() : null;
        } finally { check(budget.reservedBytes() == 0, "Retained exchange leaked"); }
    }

    /** Count actual subsets, independently of the implementation's binomial formula. */
    private static int paidRadius(int n, int radius, long flexible) {
        int width = (n + 1) / 2, cap = 0;
        for (int mask = 0; mask < 1 << width; mask++) if (Integer.bitCount(mask) <= 4) cap++;
        for (int r = radius; r >= 0; r--) {
            boolean fits = true;
            for (int side = 0; side < 2; side++) {
                int start = side == 0 ? 0 : n / 2, size = side == 0 ? n / 2 : n - n / 2, count = 0;
                for (int mask = 0; mask < 1 << size; mask++)
                    if (Long.bitCount(((long) mask << start) & ~flexible) <= r) count++;
                fits &= count <= cap;
            }
            if (fits) return r;
        }
        return -1;
    }
    private static boolean allowed(long changes, int n, long flexible, int radius) {
        long left = (1L << (n / 2)) - 1;
        return Long.bitCount(changes & left & ~flexible) <= radius && Long.bitCount(changes & ~left & ~flexible) <= radius;
    }
    private static boolean valid(long[][] a, long[] b, long mask) {
        for (int r = 0; r < a.length; r++) {
            var sum = BigInteger.ZERO;
            for (int i = 0; i < a[r].length; i++) if ((mask & (1L << i)) != 0) sum = sum.add(BigInteger.valueOf(a[r][i]));
            if (sum.compareTo(BigInteger.valueOf(b[r])) > 0) return false;
        }
        return true;
    }
    private static PlanningBudget budget(long work, long memory, java.util.function.BooleanSupplier stop) {
        return new PlanningBudget(0, work, memory, stop, System::nanoTime);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
