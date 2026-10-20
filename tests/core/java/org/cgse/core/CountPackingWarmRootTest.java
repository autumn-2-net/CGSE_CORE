// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Project actual parent bases, then check original equations and Boolean witnesses. */
public final class CountPackingWarmRootTest {
    private static int checks, positives, scaled, exteriorOnes;

    public static void main(String[] args) throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try {
            for (int trial = 0; trial < 96; trial++) run(trial, worker);
        } finally { worker.shutdownNow(); }
        check(positives == 96 && scaled > 30 && exteriorOnes > 30, "Projected basis coverage disappeared");
        System.out.println("Packing warm root: " + checks + " equation/scope checks, " + positives + " original witnesses");
    }

    private static void run(int trial, ExecutorService worker) throws Exception {
        var random = new Random(716203L + trial);
        int width = 13 + trial % 5, m = 1 + trial % 4;
        long[][] a = new long[m][width]; long[] b = new long[m], profit = new long[width];
        for (int j = 0; j < width; j++) profit[j] = 1 + random.nextInt(50);
        for (int r = 0; r < m; r++) for (int j = 0; j < width; j++) {
            a[r][j] = 1 + random.nextInt(50); b[r] += a[r][j];
        }
        for (int r = 0; r < m; r++) b[r] /= 2;
        var stop = new AtomicBoolean();
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, stop::get, System::nanoTime);
        var type = Class.forName("org.cgse.core.CountPackingCore$Relaxation");
        var ctor = type.getDeclaredConstructor(long[][].class, long[].class, long[].class, PlanningBudget.class);
        ctor.setAccessible(true);
        Object parent = ctor.newInstance(a, b, profit, budget);
        var step = type.getDeclaredMethod("step"); step.setAccessible(true);
        while (!(boolean) get(parent, "done")) check((boolean) step.invoke(parent), "Parent relaxation declined");
        double[] point = (double[]) get(parent, "point");
        int[] basis = (int[]) get(parent, "basis");
        var selected = new ArrayList<Integer>();
        for (int column : basis) if (column < width) selected.add(column);
        while (selected.size() < 8) {
            int column = random.nextInt(width);
            if (!selected.contains(column)) selected.add(column);
        }
        Collections.shuffle(selected, random);
        int[] free = selected.stream().mapToInt(x -> x).toArray();
        int n = free.length; boolean[] seed = new boolean[width], included = new boolean[width];
        for (int column : free) included[column] = true;
        for (int j = 0; j < width; j++) { seed[j] = point[j] >= 0.5; if (!included[j] && seed[j]) exteriorOnes++; }
        long[][] rows = new long[m + 1][n]; long[] bounds = new long[m + 1];
        for (int r = 0; r < m; r++) {
            bounds[r] = b[r];
            for (int j = 0; j < width; j++) if (!included[j] && seed[j]) bounds[r] -= a[r][j];
            for (int j = 0; j < n; j++) rows[r][j] = a[r][free[j]];
        }
        for (int j = 0; j < n; j++) rows[m][j] = -profit[free[j]];
        long best = 0;
        for (long mask = 0; mask < 1L << n; mask++) if (fits(rows, bounds, mask, m)) {
            long value = 0;
            for (int j = 0; j < n; j++) if ((mask & (1L << j)) != 0) value -= rows[m][j];
            best = Math.max(best, value);
        }
        bounds[m] = -best;
        var root = root(rows, bounds, budget, parent, free, seed);
        if (trial % 4 == 0) for (int j = 0; j < width; j++) if (!included[j]) {
            // Preserve the parent equations while departing slightly from an
            // exterior bound. The projection must substitute the actual seed.
            double delta = seed[j] ? -0.001 : 0.001;
            point[j] += delta;
            for (int r = 0; r < m; r++) point[basis[r]] -= root.tableau()[r][j] * delta;
            break;
        }
        long parentHash = hash(parent);
        try (var search = CountPackingLpSearch.create(rows, bounds, budget)) {
            check(search != null, "Projected face declined");
            Object cold = ((Object[]) get(search, "frames"))[0];
            // Equal values do not authorize another request/projection's basis.
            check(!search.warm(new CountPackingLpSearch.Root(rows.clone(), bounds, budget,
                    root.tableau(), root.point(), root.cost(), root.basis(), root.scales(),
                    root.objectiveScale(), free, seed)), "Accepted another row scope");
            check(!search.warm(new CountPackingLpSearch.Root(rows, bounds.clone(), budget,
                    root.tableau(), root.point(), root.cost(), root.basis(), root.scales(),
                    root.objectiveScale(), free, seed)), "Accepted another exterior scope");
            if (trial == 0) {
                check(!search.warm(new CountPackingLpSearch.Root(rows, bounds,
                        new PlanningBudget(0, 40_000_000, 64L << 20, () -> false, System::nanoTime),
                        root.tableau(), root.point(), root.cost(), root.basis(), root.scales(),
                        root.objectiveScale(), free, seed)), "Borrowed another request");
                int[] duplicate = free.clone(); duplicate[1] = duplicate[0];
                check(!search.warm(root(rows, bounds, budget, parent, duplicate, seed)), "Admitted duplicate columns");
                int saved = basis[0]; basis[0] = width + m;
                check(!search.warm(root), "Admitted an invalid basis");
                basis[0] = saved;
                for (int column : basis) if (column < width) {
                    int[] missing = free.clone();
                    int replacement = 0; while (included[replacement]) replacement++;
                    for (int j = 0; j < missing.length; j++) if (missing[j] == column) missing[j] = replacement;
                    check(!search.warm(root(rows, bounds, budget, parent, missing, seed)), "Dropped a basic column without factorization");
                    break;
                }
                // Rejection after scratch mutation must leave the cold root intact.
                double[] costs = root.cost(); double value = costs[free[n - 1]];
                costs[free[n - 1]] = Double.NaN;
                check(!search.warm(root), "Admitted non-finite copied data");
                costs[free[n - 1]] = value;
                check(((Object[]) get(search, "frames"))[0] == cold && !(boolean) get(search, "initialized"),
                        "Refusal partially replaced the cold root");
                long held = (64L << 20) - budget.reservedBytes();
                budget.reserve(held);
                check(!search.warm(root), "Ignored optional projection memory limit");
                budget.release(held);
                stop.set(true);
                try { search.warm(root); throw new AssertionError("Warm root swallowed cancellation"); }
                catch (CancellationException expected) {}
                stop.set(false);
                check(((Object[]) get(search, "frames"))[0] == cold, "Cancellation replaced the root");
            }
            check(worker.submit(() -> search.warm(root)).get(), "Eligible parent basis was not retained");
            check(!search.warm(root), "Repeated handoff overwrote a live frontier");
            equations(search, rows, bounds, root.scales());
            while (!worker.submit(search::step).get()) {}
            check(search.feasible() && fits(rows, bounds, search.assignment(), rows.length), "Warm search lost a small original witness");
            positives++;
            long mask = search.assignment();
            for (int r = 0; r < m; r++) {
                long used = 0;
                for (int j = 0; j < width; j++) if (!included[j] && seed[j]) used += a[r][j];
                for (int j = 0; j < n; j++) if ((mask & (1L << j)) != 0) used += a[r][free[j]];
                check(used <= b[r], "Restored mask violates an original capacity");
            }
        }
        check(hash(parent) == parentHash, "Warm projection mutated its parent");
        check(budget.reservedBytes() == 0, "Projected root leaked");
        if (trial == 0) {
            interrupted(root);
            capacityOrder(root);
        }
    }

    private static void capacityOrder(CountPackingLpSearch.Root source) throws Exception {
        // Dropping an empty projected capacity and adding a positive aggregate
        // can leave the same row count with a different numerical relaxation.
        // Shape and request identity alone do not establish the row mapping.
        int n = source.free().length;
        long[][] rows = {new long[n], source.rows()[1].clone(), source.rows()[0].clone()};
        rows[2][0] += 100;
        long[] bounds = {0, source.bounds()[1], source.bounds()[0]};
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, () -> false, System::nanoTime);
        var proposal = new CountPackingLpSearch.Root(rows, bounds, budget,
                source.tableau(), source.point(), source.cost(), source.basis(), source.scales(),
                source.objectiveScale(), source.free(), source.seed());
        try (var search = CountPackingLpSearch.create(rows, bounds, budget)) {
            check(search != null, "Capacity-order fixture declined");
            Object cold = ((Object[]) get(search, "frames"))[0];
            check(!search.warm(proposal), "Same row count admitted a different capacity mapping");
            check(((Object[]) get(search, "frames"))[0] == cold, "Row mismatch changed cold root");
        }
        check(budget.reservedBytes() == 0, "Row mismatch leaked");
    }

    private static void interrupted(CountPackingLpSearch.Root source) throws Exception {
        for (boolean cancel : new boolean[] {false, true}) {
            var calls = new AtomicInteger(Integer.MIN_VALUE);
            var budget = new PlanningBudget(0, 40_000_000, 64L << 20, () -> calls.incrementAndGet() > 25, System::nanoTime);
            var proposal = new CountPackingLpSearch.Root(source.rows(), source.bounds(), budget,
                    source.tableau(), source.point(), source.cost(), source.basis(), source.scales(),
                    source.objectiveScale(), source.free(), source.seed());
            try (var search = CountPackingLpSearch.create(source.rows(), source.bounds(), budget)) {
                Object cold = ((Object[]) get(search, "frames"))[0];
                if (cancel) calls.set(0); else budget.charge(budget.remainingWork() - 32);
                try {
                    search.warm(proposal);
                    throw new AssertionError("Projection ignored interruption");
                } catch (CancellationException expected) { check(cancel, "Wrong cancellation"); }
                catch (PlanningBudget.Exhausted expected) { check(!cancel, "Wrong work limit"); }
                check(((Object[]) get(search, "frames"))[0] == cold && !(boolean) get(search, "initialized"),
                        "Interrupted copy published a partial root");
                check(!search.feasible(), "Interrupted copy published a witness");
            }
            check(budget.reservedBytes() == 0, "Interrupted warm root leaked");
        }
    }

    private static void equations(Object search, long[][] rows, long[] bounds, double[] sourceScales) throws Exception {
        Object frame = ((Object[]) get(search, "frames"))[0];
        int n = rows[0].length, m = rows.length - 1;
        var scales = (double[]) get(search, "scales");
        var point = (double[]) get(frame, "point");
        var table = (double[][]) get(frame, "tableau");
        var basis = (int[]) get(frame, "basis"); var cost = (double[]) get(frame, "cost");
        double objective = (double) get(search, "objectiveScale");
        for (int r = 0; r < m; r++) {
            if (scales[r] != sourceScales[r]) scaled++;
            double value = point[n + r];
            for (int j = 0; j < n; j++) value += rows[r][j] * point[j] / scales[r];
            close(value, bounds[r] / scales[r], "Wrong projected exterior/slack scale");
            for (int j = 0; j < n + m; j++) {
                value = 0;
                for (int k = 0; k < m; k++)
                    value += coefficient(rows, scales, r, basis[k]) * table[k][j];
                close(value, coefficient(rows, scales, r, j), "Projected basis lost original equations");
            }
        }
        for (int j = 0; j < n + m; j++) {
            double expected = j < n ? -rows[m][j] / objective : 0;
            for (int k = 0; k < m; k++) if (basis[k] < n)
                expected -= (-rows[m][basis[k]] / objective) * table[k][j];
            close(cost[j], expected, "Projected reduced cost lost objective scale");
        }
    }
    private static double coefficient(long[][] rows, double[] scales, int r, int j) {
        int n = rows[0].length;
        return j < n ? rows[r][j] / scales[r] : j == n + r ? 1 : 0;
    }
    private static CountPackingLpSearch.Root root(long[][] rows, long[] bounds, PlanningBudget budget,
                                                   Object parent, int[] free, boolean[] seed) throws Exception {
        return new CountPackingLpSearch.Root(rows, bounds, budget, (double[][]) get(parent, "tableau"),
                (double[]) get(parent, "point"), (double[]) get(parent, "cost"), (int[]) get(parent, "basis"),
                (double[]) get(parent, "scales"), (double) get(parent, "objectiveScale"), free, seed);
    }
    private static boolean fits(long[][] rows, long[] bounds, long mask, int count) {
        for (int r = 0; r < count; r++) {
            BigInteger sum = BigInteger.ZERO;
            for (int j = 0; j < rows[r].length; j++) if ((mask & (1L << j)) != 0) sum = sum.add(BigInteger.valueOf(rows[r][j]));
            if (sum.compareTo(BigInteger.valueOf(bounds[r])) > 0) return false;
        }
        return true;
    }
    private static long hash(Object value) throws Exception {
        return Arrays.deepHashCode((double[][]) get(value, "tableau")) * 31L +
                Arrays.hashCode((double[]) get(value, "point")) * 17L +
                Arrays.hashCode((double[]) get(value, "cost")) * 7L + Arrays.hashCode((int[]) get(value, "basis"));
    }
    private static Object get(Object value, String name) throws Exception {
        var field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value);
    }
    private static void close(double actual, double expected, String message) {
        check(Math.abs(actual - expected) <= 1e-7 * Math.max(1, Math.abs(expected)), message);
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
