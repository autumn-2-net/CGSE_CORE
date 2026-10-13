// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exact original-domain oracles distinguish a restricted repair from a proof. */
public final class CountPackingRepairTest {
    public static void main(String[] args) throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try {
            for (int trial = 0; trial < 256; trial++) {
                var random = new Random(24941L + trial);
                int n = 2 + trial % 10, m = 2 + trial % 7;
                var a = new long[m][n]; var b = new long[m];
                long planted = random.nextInt(1 << n), seed = random.nextInt(1 << n);
                for (int r = 0; r < m; r++) {
                    for (int i = 0; i < n; i++) {
                        a[r][i] = random.nextInt(19) - 9;
                        if ((planted & (1L << i)) != 0) b[r] += a[r][i];
                    }
                    b[r] += random.nextInt(9) - 4;
                }
                int radius = random.nextInt(n + 1);
                boolean feasible = false, nearby = false;
                for (long mask = 0; mask < 1L << n; mask++) if (valid(a, b, mask)) {
                    feasible = true;
                    nearby |= Long.bitCount(mask ^ seed) <= radius;
                }
                var found = solve(a, b, seed, true, trial < 8 ? worker : null);
                check((found != null) == feasible, "PB repair contradicted full Boolean oracle: " + trial);
                if (found != null) check(valid(a, b, found), "Invalid learned repair");
                var restricted = Arrays.copyOf(a, m + 1);
                var bounds = Arrays.copyOf(b, m + 1);
                restricted[m] = new long[n];
                for (int i = 0; i < n; i++) restricted[m][i] = (seed & (1L << i)) == 0 ? 1 : -1;
                bounds[m] = radius - Long.bitCount(seed);
                found = solve(restricted, bounds, seed, false, trial < 8 ? worker : null);
                check((found != null) == nearby, "Distance projection contradicted Hamming oracle: " + trial);
                if (found != null) check(valid(a, b, found) && Long.bitCount(found ^ seed) <= radius, "Invalid nearby witness");
            }
        } finally { worker.shutdownNow(); }
        wordBoundary();
        phaseScope();
        lifecycle();
        System.out.println("Packing repairs: 512 exhaustive Boolean/distance controls, scoped failure, cached projection, fixed phase caps and cleanup passed");
    }

    private static Long solve(long[][] a, long[] b, long seed, boolean learned, ExecutorService worker) throws Exception {
        var budget = budget(40_000_000, 64L << 20, () -> false);
        var journal = learned && (seed & 7) == 0 ? new CountProof.Journal(8L << 20) : null;
        if (journal != null) budget.proofJournal(journal);
        try (var repair = new CountPackingNeighborhood(a, b, seed, budget, learned)) {
            for (int turns = 0; ; turns++) {
                check(turns < 500000, "Repair spin");
                if (worker == null ? repair.step() : worker.submit(repair::step).get()) break;
            }
            if (journal != null) for (var entry : journal.entries())
                check(CountProof.verify(entry, 1_000_000) == CountProof.Verdict.VERIFIED, "Invalid scoped PB proof");
            return repair.feasible() ? repair.assignment() : null;
        } finally { check(budget.reservedBytes() == 0, "Repair reservation leaked"); }
    }

    private static void wordBoundary() throws Exception {
        var a = new long[35][64]; var b = new long[35];
        Arrays.fill(a[0], 1); b[0] = 1; a[34][63] = -1; b[34] = -1;
        for (boolean learned : new boolean[] {false, true})
            check(Objects.equals(solve(a, b, 0, learned, null), Long.MIN_VALUE), "Lost row 35 or bit 64");
    }

    private static CountModelViews.View view() {
        var low = new BigInteger[64]; var high = new BigInteger[64];
        Arrays.fill(low, BigInteger.ZERO); Arrays.fill(high, BigInteger.ONE);
        var capacity = new TreeMap<Integer, BigInteger>(); var demand = new TreeMap<Integer, BigInteger>();
        for (int i = 0; i < 64; i++) { capacity.put(i, BigInteger.ONE); demand.put(i, BigInteger.ONE.negate()); }
        var rows = List.of(new ExactLinearProgram.Constraint(capacity, BigInteger.TEN),
                new ExactLinearProgram.Constraint(demand, BigInteger.valueOf(-8)));
        return new CountModelViews.View("repair-scope", rows, low, high,
                new CountModelViews.Shape(64, 128, 1, 1), null, CountModelViews.Semantics.EQUIVALENT, null);
    }

    private static void phaseScope() throws Exception {
        var view = view(); var budget = budget(40_000_000, 64L << 20, () -> false);
        try (var core = CountPackingCore.create(view, new CountModelViews.Domains(view.lower(), view.upper(), 0), budget, Long.MAX_VALUE)) {
            while (get(core, "search") == null) check(!core.step(), "Fixture ended before its frontier");
            Object originalSearch = get(core, "search");
            int originalSize = (int) get(core, "coreSize");
            set(core, "work", get(core, "scoutAt")); core.resume(Long.MAX_VALUE); core.step();
            check((boolean) get(core, "scouting"), "Missing bounded exchange scout");
            var endScout = CountPackingCore.class.getDeclaredMethod("endScout");
            endScout.setAccessible(true); endScout.invoke(core);
            check(!(boolean) get(core, "scouting") && (int) get(core, "coreSize") == originalSize &&
                    get(core, "search") == originalSearch, "Scout discarded the original retained frontier");
            long ceiling = (long) get(core, "maximumWork");
            Object projection = get(core, "coreCoefficients");
            // Advance only the observed owned-work clock to exercise both
            // bounded handoffs without millions of unrelated enumeration steps.
            set(core, "work", get(core, "normalWork")); core.resume(Long.MAX_VALUE); core.step();
            check(core.phase() == CountPackingCore.Phase.CONFLICT_REPAIR, "Missing learned phase marker");
            check(get(core, "coreCoefficients") == projection, "Phase rebuilt the same projection");
            set(core, "work", get(core, "learningLimit")); core.resume(Long.MAX_VALUE); core.step();
            check(core.phase() == CountPackingCore.Phase.DISTANCE_REPAIR, "Missing distance phase marker");
            check(get(core, "coreCoefficients") == projection, "Distance phase lost compiled rows");
            check((long) get(core, "maximumWork") == ceiling, "Handoff replenished its total allowance");
            // Proposal zero is legal as a hint but at distance <= 6 it cannot
            // meet the demand of eight. The original model has 8-of-64 witnesses.
            set(core, "seed", new boolean[64]); set(core, "coreSize", 64); set(core, "compiledSize", 0);
            var begin = CountPackingCore.class.getDeclaredMethod("beginCore"); begin.setAccessible(true); begin.invoke(core);
            while (!core.step()) {}
            check(!core.paused() && core.counts() == null, "Restricted face should close without a witness");
            var witness = new BigInteger[64]; Arrays.fill(witness, BigInteger.ZERO);
            for (int i = 0; i < 8; i++) witness[i] = BigInteger.ONE;
            for (var cut : core.sharedRows()) {
                var sum = BigInteger.ZERO;
                for (var t : cut.row().terms().entrySet()) sum = sum.add(t.getValue().multiply(witness[t.getKey()]));
                check(sum.compareTo(cut.row().upper()) <= 0, "Local distance or fixing escaped as a shared fact");
            }
        }
        check(budget.reservedBytes() == 0, "Scoped repair leaked");
    }

    private static void lifecycle() {
        var a = new long[][] {{1, 1}, {-1, -1}}; var b = new long[] {1, -1};
        var tiny = budget(40_000_000, 1, () -> false);
        try (var repair = new CountPackingNeighborhood(a, b, 0, tiny, true)) {
            check(repair.step() && !repair.feasible(), "Optional repair ignored memory refusal");
        }
        check(tiny.reservedBytes() == 0, "Declined reconstruction leaked");
        var stop = new AtomicBoolean(); var cancelled = budget(40_000_000, 64L << 20, stop::get);
        stop.set(true);
        try {
            new CountPackingNeighborhood(a, b, 0, cancelled, true);
            throw new AssertionError("Cancelled reconstruction continued");
        } catch (CancellationException expected) {}
        check(cancelled.reservedBytes() == 0, "Interrupted constructor leaked");
    }

    private static boolean valid(long[][] rows, long[] bounds, long mask) {
        for (int r = 0; r < rows.length; r++) {
            var sum = BigInteger.ZERO;
            for (int i = 0; i < rows[r].length; i++) if ((mask & (1L << i)) != 0) sum = sum.add(BigInteger.valueOf(rows[r][i]));
            if (sum.compareTo(BigInteger.valueOf(bounds[r])) > 0) return false;
        }
        return true;
    }
    private static Field field(Object target, String name) throws Exception { var f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f; }
    private static Object get(Object target, String name) throws Exception { return field(target, name).get(target); }
    private static void set(Object target, String name, Object value) throws Exception { field(target, name).set(target, value); }
    private static PlanningBudget budget(long work, long memory, java.util.function.BooleanSupplier stop) {
        return new PlanningBudget(0, work, memory, stop, System::nanoTime);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
