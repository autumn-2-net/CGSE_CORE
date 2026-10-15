// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

/** Structural coverage is a service hint, with one retained deferred attempt. */
public final class CountPackingScheduleTest {
    public static void main(String[] args) throws Exception {
        var budget = budget();
        long[][] dense = new long[10][40]; long[] bounds = new long[10];
        for (var row : dense) Arrays.fill(row, 1);
        Arrays.fill(bounds, 5);
        long ten = 1023L << 30;
        var broad = CountPackingSchedule.analyze(dense, bounds, 10, 0, ten, 1_048_576, budget);
        check(broad.admitted() && broad.coupled() == 10 && broad.covered() == 176 &&
                broad.roundings() == 1024 && broad.resumeWork() == 0, "Missed new coupled-rounding coverage");
        var narrow = CountPackingSchedule.analyze(dense, new long[10], 10, 0, 31L << 35, 1_048_576, budget);
        check(narrow.admitted() && narrow.covered() == 26 && narrow.roundings() == 32 &&
                narrow.resumeWork() == 851_968, "Mostly covered roundings displaced the retained frontier");

        long[][] independent = new long[5][40]; long[] one = {1, 1, 1, 1, 1};
        for (int i = 0; i < 10; i++) independent[i / 2][30 + i] = 1;
        var separate = CountPackingSchedule.analyze(independent, one, 5, 0, ten, 1_048_576, budget);
        check(separate.coupled() == 2 && separate.resumeWork() == 1_048_576 &&
                separate.fractional() == broad.fractional(), "Scheduler ignored capacity components");
        // Enlarged inventory makes all these capacity rows inactive in the
        // proposed rounding face. The old request's coupling cannot be reused.
        var loose = CountPackingSchedule.analyze(dense, new long[] {40,40,40,40,40,40,40,40,40,40},
                10, 0, ten, 1_048_576, budget);
        check(loose.coupled() == 1 && loose.resumeWork() == 1_048_576, "Stale inventory-dependent coupling");

        // Permuting within a half and reversing row order preserves coverage
        // and connectivity; moving fractional bits between halves changes the
        // actual old neighborhood and must change its coverage observation.
        var permuted = new long[5][40];
        for (int r = 0; r < 5; r++) for (int i = 0; i < 40; i++)
            permuted[r][i] = independent[4 - r][i < 20 ? 19 - i : 59 - i];
        var same = CountPackingSchedule.analyze(permuted, one, 5, 0, 1023L << 20, 1_048_576, budget);
        check(separate.equals(same), "Hint depends on row/column numbering instead of structure");
        var balanced = CountPackingSchedule.analyze(dense, new long[10], 10, 0, 3L | (7L << 20), 1_048_576, budget);
        check(balanced.covered() == 32 && balanced.resumeWork() == 1_048_576, "Missed already-covered split roundings");
        var huge = CountPackingSchedule.analyze(new long[1][64], new long[1], 1, 0, -1L, 1_048_576, budget);
        check(!huge.admitted(), "Unbounded all-roundings domain admitted");
        check(budget.reservedBytes() == 0 && budget.nodes() > 0, "Analysis leaked or ran uncharged");
        budget.cancel();
        try {
            CountPackingSchedule.analyze(dense, bounds, 10, 0, ten, 1_048_576, budget);
            throw new AssertionError("Cancelled structural analysis ran");
        } catch (CancellationException expected) {}
        deferredHandoff();
        System.out.println("Coverage, capacity coupling, inventory scope, permutations, deferred handoff and fixed ceilings passed");
    }

    private static void deferredHandoff() throws Exception {
        var lo = new BigInteger[64]; var hi = new BigInteger[64];
        Arrays.fill(lo, BigInteger.ZERO); Arrays.fill(hi, BigInteger.ZERO);
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        var target = new TreeMap<Integer, BigInteger>();
        for (int i = 0; i < 8; i++) { hi[i] = BigInteger.ONE; target.put(i, BigInteger.ONE.negate()); }
        for (int i = 0; i < 8; i += 2)
            rows.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.TWO, i + 1, BigInteger.TWO), BigInteger.valueOf(3)));
        rows.add(new ExactLinearProgram.Constraint(target, BigInteger.valueOf(-5)));
        var view = new CountModelViews.View("deferred-rounding-scope", rows, lo, hi,
                new CountModelViews.Shape(64, 16, 2, 2), null, CountModelViews.Semantics.EQUIVALENT, null);
        var budget = budget(); var worker = Executors.newSingleThreadExecutor();
        try (var core = CountPackingCore.create(view, new CountModelViews.Domains(lo, hi, 0), budget, Long.MAX_VALUE)) {
            while (field(core, "search") == null) check(!core.step(), "Fixture closed before the LP point");
            Object original = field(core, "search"), projection = field(core, "coreCoefficients");
            invoke(core, "beginScout");
            check(core.initialOpportunity(), "Initial exchange lost its companion service");
            check(!(boolean) invoke(core, "scheduleFlexibleScout") && (boolean) field(core, "flexiblePending"),
                    "Small independent components did not retain the original frontier first");
            long at = (long) field(core, "flexibleAt"), allowance = (long) field(core, "flexibleAllowance");
            long normal = (long) field(core, "normalWork"), maximum = (long) field(core, "maximumWork");
            invoke(core, "endScout");
            check(field(core, "search") == original && field(core, "coreCoefficients") == projection,
                    "Deferral rebuilt the original exact frontier");
            check(!core.initialOpportunity(), "Pending optional rounding renewed initial service");
            for (int i = 0; i < 3; i++) core.resume(1);
            check((long) field(core, "flexibleAt") == at && (long) field(core, "flexibleAllowance") == allowance,
                    "Resume replenished pending work");
            // Advance only the measured local clock to the saved admission
            // boundary; the real index then advances on another worker.
            set(core, "work", at); core.resume(Long.MAX_VALUE);
            worker.submit(core::step).get();
            check(!(boolean) field(core, "flexiblePending") && (boolean) field(core, "flexibleScout"),
                    "Deferred attempt was lost across a worker move");
            check(!core.initialOpportunity() && core.remainingAllowance() > 0,
                    "Rounding stole initial service or lost ordinary continuation");
            long deadline = (long) field(core, "scoutLimit"); Object deferred = field(core, "search");
            long roundingStart = (long) field(core, "roundingStart");
            check(core.failedRoundingWork() == 0, "Live rounding was reported as an unsuccessful attempt");
            check(deadline <= normal && deadline <= at + allowance && (long) field(core, "maximumWork") == maximum,
                    "Deferred admission increased a fixed allowance");
            for (int i = 0; i < 5; i++) { core.resume(1); worker.submit(core::step).get(); }
            check(field(core, "search") == deferred && (long) field(core, "scoutLimit") == deadline,
                    "Deferred handoff rebuilt or replenished the index");
            invoke(core, "endScout");
            check(core.failedRoundingWork() == core.work() - roundingStart && core.failedRoundingWork() > 0,
                    "Failed rounding lost its owned work across a worker move");
            long failed = core.failedRoundingWork();
            core.resume(1);
            check(core.failedRoundingWork() == failed, "Resume renewed a completed cooldown observation");
            check(field(core, "search") == original && field(core, "coreCoefficients") == projection &&
                    !(boolean) field(core, "flexiblePending") && core.counts() == null,
                    "Restricted attempt changed the original domain or renewed itself");
        } finally { worker.shutdownNow(); }
        check(budget.reservedBytes() == 0, "Deferred frontier leaked");
    }

    private static Object field(Object o, String n) throws Exception { var f=o.getClass().getDeclaredField(n); f.setAccessible(true); return f.get(o); }
    private static void set(Object o, String n, Object v) throws Exception { var f=o.getClass().getDeclaredField(n); f.setAccessible(true); f.set(o,v); }
    private static Object invoke(Object o, String n) throws Exception { var m=o.getClass().getDeclaredMethod(n); m.setAccessible(true); return m.invoke(o); }
    private static PlanningBudget budget() { return new PlanningBudget(0, 40_000_000, 64L << 20, () -> false, System::nanoTime); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
