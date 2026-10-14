// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A late exchange shares the original allowance, never its private restrictions. */
public final class CountPackingScoutTest {
    public static void main(String[] args) throws Exception {
        widerWitness();
        handoff(0);
        handoff(1);
        handoff(2);
        System.out.println("Eight-coordinate witness, retained learned frontier, fixed ceilings, worker handoff and scout cancellation passed");
    }

    private static void widerWitness() {
        var rows = new long[2][16];
        Arrays.fill(rows[0], 1); Arrays.fill(rows[1], -1);
        var bounds = new long[] {8, -8};
        for (var mode : new CountPackingNeighborhood.Mode[] {CountPackingNeighborhood.Mode.EXCHANGE, CountPackingNeighborhood.Mode.EXCHANGE_FOUR}) {
            var budget = CountPackingExchangeTest.budget(16L << 20, () -> false);
            try (var search = new CountPackingNeighborhood(rows, bounds, 0, budget, mode)) {
                while (!search.step()) {}
                check(search.feasible() == (mode == CountPackingNeighborhood.Mode.EXCHANGE_FOUR), "Exchange radius ignored");
                if (search.feasible()) check(Long.bitCount(search.assignment()) == 8 &&
                        CountPackingExchangeTest.valid(rows, bounds, search.assignment()), "Invalid wide witness");
            }
            check(budget.reservedBytes() == 0, "Wide neighborhood leaked");
        }
    }

    private static void handoff(int ending) throws Exception {
        var low = new BigInteger[64]; var high = new BigInteger[64];
        Arrays.fill(low, BigInteger.ZERO); Arrays.fill(high, BigInteger.ONE);
        var capacity = new TreeMap<Integer, BigInteger>(); var demand = new TreeMap<Integer, BigInteger>();
        for (int i = 0; i < 64; i++) { capacity.put(i, BigInteger.ONE); demand.put(i, BigInteger.ONE.negate()); }
        var rows = List.of(new ExactLinearProgram.Constraint(capacity, BigInteger.TEN),
                new ExactLinearProgram.Constraint(demand, BigInteger.valueOf(-8)));
        var view = new CountModelViews.View("wide-scout-scope", rows, low, high,
                new CountModelViews.Shape(64, 128, 1, 1), null, CountModelViews.Semantics.EQUIVALENT, null);
        var stop = new AtomicBoolean();
        var budget = CountPackingExchangeTest.budget(64L << 20, stop::get);
        var worker = Executors.newSingleThreadExecutor();
        try (var core = CountPackingCore.create(view, new CountModelViews.Domains(low, high, 0), budget, Long.MAX_VALUE)) {
            while (get(core, "search") == null) check(!core.step(), "Fixture closed before its frontier");
            long maximum = (long) get(core, "maximumWork"), normal = (long) get(core, "normalWork"), learning = (long) get(core, "learningLimit");
            // Exercise retained handoffs without replaying millions of unrelated
            // enumeration operations. Only the owned-work clock is advanced.
            set(core, "work", normal); core.resume(Long.MAX_VALUE); core.step();
            check(core.phase() == CountPackingCore.Phase.CONFLICT_REPAIR, "Missing learned repair");
            int epoch = core.completionEpoch();
            Object frontier = get(core, "search"), projection = get(core, "coreCoefficients"), free = get(core, "free");
            set(core, "work", get(core, "wideScoutAt")); core.resume(Long.MAX_VALUE);
            worker.submit(core::step).get();
            check((boolean) get(core, "wideScout") && get(core, "search") != frontier, "Late scout did not retain the frontier");
            Object scout = get(core, "search");
            for (int i = 0; i < 10; i++) {
                core.resume(1);
                worker.submit(core::step).get();
                check(get(core, "search") == scout, "Worker handoff rebuilt the exchange index");
            }
            check((long) get(core, "maximumWork") == maximum && (long) get(core, "normalWork") == normal &&
                    (long) get(core, "learningLimit") == learning && (long) get(core, "scoutLimit") <= maximum,
                    "Late scout replenished a work allowance");
            // Only the certified aggregate can escape the restricted search.
            var witness = new BigInteger[64]; Arrays.fill(witness, BigInteger.ZERO);
            for (int i = 0; i < 8; i++) witness[i] = BigInteger.ONE;
            for (var cut : core.sharedRows()) {
                var sum = BigInteger.ZERO;
                for (var t : cut.row().terms().entrySet()) sum = sum.add(t.getValue().multiply(witness[t.getKey()]));
                check(sum.compareTo(cut.row().upper()) <= 0, "Scout restriction escaped as a shared fact");
            }
            if (ending == 2) {
                core.resume(Long.MAX_VALUE); stop.set(true);
                try { core.step(); throw new AssertionError("Scout ignored cancellation"); }
                catch (CancellationException expected) {}
            } else if (ending == 1) {
                set(core, "work", get(core, "scoutLimit")); core.resume(Long.MAX_VALUE); core.step();
                check(!(boolean) get(core, "scouting") && (boolean) get(core, "wideScoutAttempted") &&
                        get(core, "scoutBase") == null && core.phase() == CountPackingCore.Phase.CONFLICT_REPAIR &&
                        get(core, "search") == frontier && get(core, "coreCoefficients") == projection && get(core, "free") == free,
                        "Scout work expired the saved PB frontier");
                check(core.completionEpoch() == epoch + 1 && core.restoredScoutWork() > 0,
                        "Missing bounded restoration event");
                long scoutWork = core.restoredScoutWork();
                set(core, "work", learning + scoutWork); core.resume(Long.MAX_VALUE); core.step();
                check(core.phase() == CountPackingCore.Phase.DISTANCE_REPAIR && core.completionEpoch() == epoch + 2 &&
                        core.restoredScoutWork() == scoutWork && (long) get(core, "maximumWork") == maximum,
                        "Original phase allowance or completion event was replenished");
            } else {
                var endScout = CountPackingCore.class.getDeclaredMethod("endScout");
                endScout.setAccessible(true); endScout.invoke(core);
                check(!(boolean) get(core, "scouting") && (boolean) get(core, "wideScoutAttempted") &&
                        get(core, "search") == frontier && get(core, "coreCoefficients") == projection && get(core, "free") == free,
                        "Early scout closure discarded or recompiled the original learned frontier");
                check(core.completionEpoch() == epoch + 1, "Early restoration was not observable");
            }
        } finally { worker.shutdownNow(); }
        check(budget.reservedBytes() == 0, "Saved frontier or exchange reservation leaked");
    }

    private static Field field(Object target, String name) throws Exception { var f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f; }
    private static Object get(Object target, String name) throws Exception { return field(target, name).get(target); }
    private static void set(Object target, String name, Object value) throws Exception { field(target, name).set(target, value); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
