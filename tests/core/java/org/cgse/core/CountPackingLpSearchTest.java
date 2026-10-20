// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Original Boolean oracles, including arbitrary (not necessarily optimal) prices. */
public final class CountPackingLpSearchTest {
    public static void main(String[] args) throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        int positives = 0, prunes = 0, implications = 0, reusedDepths = 0;
        try {
            for (int trial = 0; trial < 192; trial++) {
                var random = new Random(20261020L + trial);
                int n = 4 + trial % 8, m = 1 + trial % 5;
                long scale = trial % 7 == 0 ? 1L << 45 : 1;
                long[][] a = new long[m + 1][n]; long[] b = new long[m + 1];
                for (int j = 0; j < n; j++) a[m][j] = -(1 + random.nextInt(30)) * scale;
                for (int r = 0; r < m; r++) {
                    for (int j = 0; j < n; j++) { a[r][j] = (1 + random.nextInt(30)) * scale; b[r] += a[r][j]; }
                    b[r] /= 2;
                }
                long best = 0;
                for (long x = 0; x < 1L << n; x++) if (fits(a, b, x, m)) {
                    long value = 0;
                    for (int j = 0; j < n; j++) if ((x & (1L << j)) != 0) value -= a[m][j];
                    best = Math.max(best, value);
                }
                boolean expected = trial % 3 != 0;
                b[m] = -(best + (expected ? 0 : scale));
                Long direct = solve(a, b, null);
                if (direct != null) {
                    check(expected && fits(a, b, direct, a.length), "Invalid restored Boolean witness");
                    positives++;
                }
                if (trial < 24) check(Objects.equals(direct, solve(a, b, worker)), "Worker handoff changed the LP tree");

                // A price vector only proposes an integer certificate. Check
                // box rejection and every simultaneous fixing against original
                // assignments, without trusting LP optimality or its point.
                var budget = budget(40_000_000, 64L << 20, () -> false);
                try (var search = CountPackingLpSearch.create(a, b, budget)) {
                    check(search != null, "Small exact domain declined");
                    var frame = ((Object[]) get(search, "frames"))[0];
                    var cost = (double[]) get(frame, "cost");
                    var scales = (double[]) get(search, "scales");
                    double objective = (double) get(search, "objectiveScale");
                    var possible = CountPackingLpSearch.class.getDeclaredMethod("possible", frame.getClass());
                    possible.setAccessible(true);
                    for (int box = 0; box < 8; box++) {
                        long fixed = random.nextInt(1 << n), ones = random.nextInt(1 << n) & fixed;
                        set(frame, "fixed", fixed); set(frame, "ones", ones);
                        for (int r = 0; r < m; r++) {
                            double price = box == 0 ? Double.NaN : box == 1 ? Double.POSITIVE_INFINITY : random.nextInt(9) / 2.0;
                            cost[n + r] = -price * scales[r] / objective;
                        }
                        boolean admitted = (boolean) possible.invoke(search, frame);
                        long nextFixed = (long) get(frame, "fixed"), nextOnes = (long) get(frame, "ones");
                        check((nextFixed & fixed) == fixed && (nextOnes & fixed) == ones,
                                "Certificate changed an inherited box");
                        check((nextOnes & ~nextFixed) == 0, "Certificate fixed a value outside its box");
                        implications += Long.bitCount(nextFixed & ~fixed);
                        if (!admitted) prunes++;
                        for (long x = 0; x < 1L << n; x++) if ((x & fixed) == ones && fits(a, b, x, a.length))
                            check(admitted && (x & nextFixed) == nextOnes,
                                    "Integer pricing removed an original witness");
                    }
                }
                check(budget.reservedBytes() == 0, "Exact certificate scratch leaked");
            }
            check(positives == 128 && prunes >= 100, "Positive or exact-bound coverage disappeared");
            check(implications >= 20, "Reduced-cost fixing was silently disabled");
            reusedDepths = reuseBuffers();
            pricedBoundaries();
            boundaries(worker);
            scope();
            lifecycle();
            ownerHandoff(worker);
        } finally { worker.shutdownNow(); }
        System.out.println("Packing LP: 192 exhaustive domains, " + positives + " exact witnesses, " + prunes +
                " checked box prunes, " + implications + " checked fixings, " + reusedDepths +
                " retained depth buffers; scope, budgets and handoffs passed");
    }

    private static Long solve(long[][] a, long[] b, ExecutorService worker) throws Exception {
        var budget = budget(40_000_000, 64L << 20, () -> false);
        Long result;
        try (var search = CountPackingLpSearch.create(a, b, budget)) {
            check(search != null, "Expected admission");
            for (int turn = 0; ; turn++) {
                check(turn < 100000, "Unbounded numerical candidate search");
                if (worker == null ? search.step() : worker.submit(search::step).get()) break;
            }
            result = search.feasible() ? search.assignment() : null;
        }
        check(budget.reservedBytes() == 0, "Candidate search leaked");
        return result;
    }

    private static int reuseBuffers() throws Exception {
        int n = 12; long[][] a = new long[4][n]; long[] b = {87, 93, 99, -1000};
        var random = new Random(618);
        for (int r = 0; r < 3; r++) for (int j = 0; j < n; j++) a[r][j] = 1 + random.nextInt(30);
        Arrays.fill(a[3], -100);
        var budget = budget(40_000_000, 64L << 20, () -> false);
        try (var search = CountPackingLpSearch.create(a, b, budget)) {
            // Repeated copies at the same depth must not allocate a new basis
            // or carry the previous sibling's fixings/branch state forward.
            var frames = (Object[]) get(search, "frames");
            var copy = CountPackingLpSearch.class.getDeclaredMethod("copy", frames[0].getClass());
            copy.setAccessible(true);
            Object first = copy.invoke(search, frames[0]);
            var tableau = get(first, "tableau"); var basis = get(first, "basis");
            set(first, "fixed", -1L); set(first, "ones", -1L); set(first, "branch", 7); set(first, "turn", 2);
            set(first, "iterations", 500);
            Object second = copy.invoke(search, frames[0]);
            check(first == second && get(second, "tableau") == tableau && get(second, "basis") == basis,
                    "Reallocated a retained depth");
            check((long) get(second, "fixed") == 0 && (long) get(second, "ones") == 0 &&
                    (int) get(second, "branch") == -1 && (int) get(second, "turn") == 0 &&
                    (int) get(second, "iterations") == 0, "Sibling inherited stale state");
            return 1;
        } finally { check(budget.reservedBytes() == 0, "Depth buffers leaked"); }
    }

    private static void boundaries(ExecutorService worker) throws Exception {
        for (int n : new int[] {1, 31, 32, 63, 64}) {
            long[][] a = new long[2][n]; long[] b = {n, -n};
            Arrays.fill(a[0], 1); Arrays.fill(a[1], -1);
            long expected = n == 64 ? -1L : (1L << n) - 1;
            check(Objects.equals(solve(a, b, worker), expected), "Signed bit mask truncated at " + n);
        }
        var budget = budget(40_000_000, 64L << 20, () -> false);
        for (long value : new long[] {Long.MIN_VALUE, Long.MAX_VALUE, 1L << 60, -(1L << 60)}) {
            check(CountPackingLpSearch.create(new long[][] {{value}, {-1}}, new long[] {0, -1}, budget) == null,
                    "Overflowing exact activity admitted");
        }
        check(CountPackingLpSearch.create(new long[][] {{1}, {-1}}, new long[] {Long.MIN_VALUE, -1}, budget) == null,
                "Overflowing bound admitted");
        check(CountPackingLpSearch.create(new long[2][65], new long[2], budget) == null, "Oversized mask admitted");
        check(CountPackingLpSearch.create(new long[][] {{1}, {1, 2}}, new long[2], budget) == null, "Unequal widths admitted");
        check(CountPackingLpSearch.create(new long[][] {{1}}, new long[] {1}, budget) == null, "Missing demand admitted");
        long rounded = 1L << 53;
        check(solve(new long[][] {{rounded + 1}, {-rounded}}, new long[] {rounded + 1, -(rounded + 1)}, null) == null,
                "Rounded doubles published an infeasible exact demand");
        check(budget.reservedBytes() == 0, "Admission refusal leaked");
    }

    private static void pricedBoundaries() throws Exception {
        // Strict inequality is essential: a loss equal to the available gap
        // still satisfies the requested target. Both singletons are feasible.
        price(new long[][] {{1, 1}, {-10, -6}}, new long[] {2, -6}, new double[] {0}, 0, 0, false);
        price(new long[][] {{1, 1}, {-10, -6}}, new long[] {2, -11}, new double[] {0}, 3, 3, false);
        price(new long[][] {{1, 5}, {-3, -1}}, new long[] {1, -3}, new double[] {1}, 3, 1, false);
        // The same certificate must survive overflow in multiplication, row
        // accumulation, and the gap. It must never saturate or round the target.
        long scale = 1L << 45;
        price(new long[][] {{scale, scale}, {-10 * scale, -6 * scale}},
                new long[] {2 * scale, -6 * scale}, new double[] {0}, 0, 0, true);
        price(new long[][] {{scale, 5 * scale}, {-3 * scale, -scale}},
                new long[] {scale, -3 * scale}, new double[] {1}, 3, 1, true);
        price(new long[][] {{1, 1}, {-1, -1}}, new long[] {1L << 59, -1},
                new double[] {1e12}, 0, 0, true);
        price(new long[][] {{1, 1}, {1, 1}, {-1, -1}}, new long[] {1L << 46, 1L << 46, -1},
                new double[] {1, 1}, 0, 0, true);
        price(new long[][] {{1, 1}, {-1, -1}}, new long[] {1L << 46, 1L << 46},
                new double[] {1}, 0, 0, true);
        // Overflow may occur in a capacity product even when profit, target
        // and bound products are tiny; wrapping 2^64 to zero loses this fixing.
        price(new long[][] {{1L << 48, 0}, {0, -1}}, new long[] {0, -1},
                new double[] {1}, 3, 2, true);
        // The absolute envelope of Long.MIN_VALUE cannot fit a positive long.
        // Widen it without abs()/negation overflow.
        long half = 1L << 46;
        price(new long[][] {{half, 0}, {half, 0}, {0, -1}}, new long[] {0, 0, -1},
                new double[] {1, 1}, 3, 2, true);
        // Arbitrary numerical prices are merely nonnegative integer proposals.
        for (double value : new double[] {-1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
            price(new long[][] {{1, 5}, {-3, -1}}, new long[] {1, -3}, new double[] {value}, 1, 1, false);
    }

    private static void price(long[][] a, long[] b, double[] prices, long expectedFixed,
                              long expectedOnes, boolean wide) throws Exception {
        var budget = budget(40_000_000, 64L << 20, () -> false);
        try (var search = CountPackingLpSearch.create(a, b, budget)) {
            check(search != null, "Priced boundary refused");
            Object frame = ((Object[]) get(search, "frames"))[0];
            double[] cost = (double[]) get(frame, "cost"), scales = (double[]) get(search, "scales");
            int n = a[0].length; double objective = (double) get(search, "objectiveScale");
            for (int r = 0; r < prices.length; r++) cost[n + r] = -prices[r] * scales[r] / objective;
            var possible = CountPackingLpSearch.class.getDeclaredMethod("possible", frame.getClass());
            possible.setAccessible(true);
            check((boolean) possible.invoke(search, frame), "Valid priced box was rejected");
            long fixed = (long) get(frame, "fixed"), ones = (long) get(frame, "ones");
            check(fixed == expectedFixed && ones == expectedOnes, "Wrong exact reduced-cost implication");
            check(((long) get(search, "wideBounds") > 0) == wide, "Overflow boundary used the wrong arithmetic");
            // Changing a nonbasic bound must preserve the original capacity
            // equations, even when its basic slack becomes temporarily negative.
            var point = (double[]) get(frame, "point");
            for (int r = 0; r < prices.length; r++) {
                double activity = point[n + r], expected = b[r] / scales[r];
                for (int j = 0; j < n; j++) activity += (a[r][j] / scales[r]) * point[j];
                check(Math.abs(activity - expected) <= 1e-9 * Math.max(1, Math.abs(expected)),
                        "Fixing a column broke its retained capacity equations");
            }
            for (long x = 0; x < 1L << n; x++) if (fits(a, b, x, a.length))
                check((x & fixed) == ones, "Priced boundary removed a feasible assignment");
        }
        check(budget.reservedBytes() == 0, "Priced scratch leaked");
    }

    private static void scope() {
        // The pure packing relaxation chooses both variables. A further
        // mixed row rejects that point but has a valid original witness x=01.
        long[][] a = {{1, 1}, {-1, -1}, {1, -1}}; long[] b = {2, -1, -1};
        var budget = budget(40_000_000, 64L << 20, () -> false);
        try (var lp = new CountPackingNeighborhood(a, b, 0, budget, CountPackingNeighborhood.Mode.RELAXATION);
             var exact = new CountPackingNeighborhood(a, b, 0, budget, false)) {
            while (!lp.step()) {}
            check(!lp.feasible() && !lp.provedInfeasible(), "Incomplete LP proposal became a scoped proof");
            while (!exact.step()) {}
            check(exact.feasible() && exact.assignment() == 2, "Original feasible frontier was disabled");
        }
        check(budget.reservedBytes() == 0, "Scope test leaked");
    }

    private static void lifecycle() {
        long[][] a = {{1, 1}, {-1, -1}}; long[] b = {1, -1};
        var tiny = budget(40_000_000, 1, () -> false);
        try (var lp = new CountPackingNeighborhood(a, b, 0, tiny, CountPackingNeighborhood.Mode.RELAXATION)) {
            check(lp.step() && !lp.feasible() && !lp.provedInfeasible(), "Memory refusal became a proof");
        }
        check(tiny.reservedBytes() == 0, "Refused candidate memory leaked");
        // Fail after reservation, while constructing the root tableau.
        var shortWork = budget(5, 64L << 20, () -> false);
        try {
            CountPackingLpSearch.create(a, b, shortWork);
            throw new AssertionError("Root construction ignored work limit");
        } catch (PlanningBudget.Exhausted expected) {}
        check(shortWork.reservedBytes() == 0, "Interrupted root constructor leaked");
        for (boolean workLimit : new boolean[] {false, true}) {
            var stop = new AtomicBoolean();
            var budget = budget(40_000_000, 64L << 20, stop::get);
            try (var search = CountPackingLpSearch.create(a, b, budget)) {
                if (workLimit) budget.charge(budget.remainingWork()); else stop.set(true);
                try {
                    search.step();
                    throw new AssertionError("LP ignored interruption");
                } catch (PlanningBudget.Exhausted expected) { check(workLimit, "Unexpected work limit"); }
                catch (CancellationException expected) { check(!workLimit, "Unexpected cancellation"); }
                check(!search.feasible(), "Interrupted proposal published");
                search.close(); search.close();
            }
            check(budget.reservedBytes() == 0, "Interrupted search leaked");
        }
    }

    private static void ownerHandoff(ExecutorService worker) throws Exception {
        int n = 64; var random = new Random(161017);
        var low = new BigInteger[n]; var high = new BigInteger[n];
        Arrays.fill(low, BigInteger.ZERO); Arrays.fill(high, BigInteger.ONE);
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        var demand = new TreeMap<Integer, BigInteger>(); var target = BigInteger.ZERO;
        for (int r = 0; r < 5; r++) {
            var terms = new TreeMap<Integer, BigInteger>(); var bound = BigInteger.ZERO;
            for (int i = 0; i < n; i++) {
                var coefficient = BigInteger.valueOf(1 + random.nextInt(100));
                terms.put(i, coefficient); demand.merge(i, coefficient.negate(), BigInteger::add);
                if (i % 2 == 0) bound = bound.add(coefficient);
            }
            rows.add(new ExactLinearProgram.Constraint(terms, bound)); target = target.add(bound);
        }
        rows.add(new ExactLinearProgram.Constraint(demand, target.negate()));
        var view = new CountModelViews.View("lp-handoff", rows, low, high,
                new CountModelViews.Shape(n, n * 6L, n, n), null, CountModelViews.Semantics.EQUIVALENT, null);
        var budget = budget(40_000_000, 64L << 20, () -> false);
        try (var owner = CountPackingCore.create(view, new CountModelViews.Domains(low, high, 0), budget, Long.MAX_VALUE)) {
            while (get(owner, "search") == null) check(!owner.step(), "Owner closed before enumeration");
            Object original = get(owner, "search"); long maximum = (long) get(owner, "maximumWork");
            // Control only phase boundaries; use the real compiled projection,
            // PB owner and retained enumeration rather than an external model.
            set(owner, "enumerationBase", invoke(owner, "parkSearch"));
            set(owner, "phase", CountPackingCore.Phase.CONFLICT_REPAIR);
            invoke(owner, "beginCore");
            Object pb = get(owner, "search");
            invoke(owner, "beginWideScout");
            Object projection = get(owner, "coreCoefficients");
            invoke(owner, "rememberLpScout");
            long allowance = (long) get(owner, "lpAllowance");
            check(allowance > 0, "Unused wide service not recorded");
            invoke(owner, "endScout");
            check(get(owner, "search") == pb && get(owner, "lpFace") != null && !(boolean) get(owner, "lpScout"),
                    "LP displaced PB's original service");
            invoke(owner, "resumeEnumeration");
            Object scoutBase = get(owner, "scoutBase");
            check(invoke(scoutBase, "search") == original && get(owner, "coreCoefficients") == projection &&
                    get(owner, "lpFace") == null && (boolean) get(owner, "lpScout"), "Lost original frontier or LP projection");
            check((long) get(owner, "scoutLimit") == Math.min(maximum, owner.work() + allowance), "Renewed deferred allowance");
            var search = (CountPackingNeighborhood) get(owner, "search");
            worker.submit(search::step).get();
            long deadline = (long) get(owner, "scoutLimit");
            owner.resume(17); owner.resume(31);
            check((long) get(owner, "scoutLimit") == deadline && (long) get(owner, "maximumWork") == maximum,
                    "Worker slice renewed inherited deadlines");
            invoke(owner, "endScout");
            check(get(owner, "search") == original && get(search, "relaxation") == null,
                    "LP expiry replaced the original enumeration or leaked its basis");
        }
        check(budget.reservedBytes() == 0, "Owner handoff leaked");
    }

    private static boolean fits(long[][] rows, long[] bounds, long mask, int count) {
        for (int r = 0; r < count; r++) {
            var sum = BigInteger.ZERO;
            for (int j = 0; j < rows[r].length; j++) if ((mask & (1L << j)) != 0)
                sum = sum.add(BigInteger.valueOf(rows[r][j]));
            if (sum.compareTo(BigInteger.valueOf(bounds[r])) > 0) return false;
        }
        return true;
    }
    private static PlanningBudget budget(long work, long bytes, java.util.function.BooleanSupplier stop) {
        return new PlanningBudget(0, work, bytes, stop, System::nanoTime);
    }
    private static Field field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Object get(Object target, String name) throws Exception { return field(target, name).get(target); }
    private static void set(Object target, String name, Object value) throws Exception { field(target, name).set(target, value); }
    private static Object invoke(Object target, String name) throws Exception {
        var method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(target);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
