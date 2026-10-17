// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Whole-key subset bounds must retain eligibility, scope and continuation. */
public final class StateEntityCohortsTest {
    private static int checks;
    public static void main(String[] args) {
        subset(); passingRelaxation(); boundaries();
        System.out.println("StateEntityCohortsTest: assertions=" + checks);
    }

    private static void subset() {
        for (int unrelated : new int[]{1, 40}) for (boolean reverse : List.of(false, true))
            for (boolean prefer : List.of(false, true)) {
                var demands = new ArrayList<StateEntityAllocation.Demand>();
                var supplies = new ArrayList<StateEntityAllocation.Supply<Integer>>();
                // Eleven size-two slots need eleven whole cohorts. Ten keys
                // of size three suffice for splittable entities (30 >= 22),
                // but only ten whole cohorts. Disjoint surplus cannot help.
                for (int d = 0; d < 11 + unrelated; d++) demands.add(new StateEntityAllocation.Demand(d, 2, false));
                for (int s = 0; s < 11; s++) supplies.add(new StateEntityAllocation.Supply<>(s,
                        BigInteger.valueOf(s == 10 ? 1_000_000 : 3), 1));
                if (reverse) { Collections.reverse(demands); Collections.reverse(supplies); }
                var budget = new PlanningBudget(0, 100_000, 1 << 20, () -> false, System::nanoTime);
                try (var task = StateEntityMatching.create(demands, supplies, (d, s) -> d.slot() < 11 ? s < 10 : s == 10,
                        prefer ? (d, s) -> s : null, budget)) {
                    int steps = 0; while (!task.step()) check(++steps < 1000, "Enumerated permutations of fragmented keys");
                    check(!task.feasible() && task.deficit() == null, "Cohort deficit became an entity Hall deficit");
                    var proof = task.fragmentation();
                    check(proof != null && proof.threshold() == 2 && proof.available() == 10, "Missing cohort certificate");
                    check(new HashSet<>(proof.slots()).equals(new HashSet<>(java.util.stream.IntStream.range(0, 11).boxed().toList())),
                            "Unrelated stock contaminated the support");
                }
                check(budget.reservedBytes() == 0, "Subset workspace leak");
            }
    }

    private static void passingRelaxation() {
        // Every threshold passes, but [6,6,4] cannot fit into [8,8]. Passing
        // threshold matching must resume the retained exact search.
        var demands = List.of(new StateEntityAllocation.Demand(0, 6, false), new StateEntityAllocation.Demand(1, 6, false),
                new StateEntityAllocation.Demand(2, 4, false));
        var supplies = List.of(new StateEntityAllocation.Supply<>(0, BigInteger.valueOf(8), 1),
                new StateEntityAllocation.Supply<>(1, BigInteger.valueOf(8), 1),
                new StateEntityAllocation.Supply<>(2, BigInteger.valueOf(100), 1));
        var budget = budget();
        try (var task = StateEntityMatching.create(demands, supplies, (d, s) -> s < 2, budget)) {
            while (!task.step()) {}
            check(!task.feasible() && task.deficit() == null, "Accepted a necessary threshold check as a witness");
            check(task.fragmentation() != null && task.fragmentation().threshold() == 0, "Invented a threshold certificate");
        }
        // Let the last slot mix: exact residual flow now fills two fragments.
        var mixed = new ArrayList<>(demands); mixed.set(2, new StateEntityAllocation.Demand(2, 4, true));
        try (var task = StateEntityMatching.create(mixed, supplies, (d, s) -> s < 2, budget)) {
            while (!task.step()) {}
            check(task.feasible() && task.fragmentation() == null, "Rejected a mixed-slot witness");
        }
        // Only the lower threshold detects the obstruction; sizes differ.
        var descending = List.of(new StateEntityAllocation.Demand(7, 4, false), new StateEntityAllocation.Demand(9, 3, false),
                new StateEntityAllocation.Demand(11, 3, false));
        var stock = List.of(new StateEntityAllocation.Supply<>(0, BigInteger.valueOf(5), 1),
                new StateEntityAllocation.Supply<>(1, BigInteger.valueOf(5), 1));
        try (var task = StateEntityCohorts.create(descending, 3, stock, new boolean[]{true,true,true,true,true,true}, budget)) {
            while (!task.step()) {}
            var proof = task.deficit();
            check(!task.possible() && proof.threshold() == 3 && proof.available() == 2 && proof.slots().size() == 3,
                    "Failed to advance thresholds or preserve original slot IDs");
        }
        check(budget.reservedBytes() == 0, "Passing relaxation retained storage");
    }

    private static void boundaries() {
        var demands = List.of(new StateEntityAllocation.Demand(0, 2, false), new StateEntityAllocation.Demand(1, 2, false));
        var supplies = List.of(new StateEntityAllocation.Supply<>(0, BigInteger.ONE, 1),
                new StateEntityAllocation.Supply<>(1, BigInteger.valueOf(3), 1),
                new StateEntityAllocation.Supply<>(2, BigInteger.ONE.shiftLeft(100), 1));
        boolean[] edges = {true,true,false,true,true,false};
        for (int limit = 1; limit < 160; limit++) {
            var budget = new PlanningBudget(0, limit, 1 << 20, () -> false, System::nanoTime);
            try (var task = StateEntityCohorts.create(demands, 2, supplies, edges, budget)) {
                check(task.deficit() == null, "Pending threshold exported feedback");
                while (!task.step()) {}
                var proof = task.deficit();
                check(!task.possible() && proof != null && proof.available() == 1, "Overflowed or counted ineligible surplus");
                task.close(); check(task.deficit() == null, "Closed threshold exported feedback");
            } catch (PlanningBudget.Exhausted expected) { /* No proof escaped incomplete work. */ }
            check(budget.reservedBytes() == 0 && budget.searchWork() <= limit + 1L, "Threshold renewed its budget or leaked");
        }
        for (int at = 0; at < 15; at++) {
            var cancel = new AtomicBoolean();
            var budget = new PlanningBudget(0, 10_000, 1 << 20, cancel::get, System::nanoTime);
            try (var task = StateEntityCohorts.create(demands, 2, supplies, edges, budget)) {
                for (int i = 0; i < at && !task.step(); i++) {}
                cancel.set(true);
                try { task.step(); throw new AssertionError("Ignored cancellation"); } catch (CancellationException expected) {}
            }
            check(budget.reservedBytes() == 0, "Cancellation leaked threshold storage");
        }
        // Optional workspace refusal falls back to the original exact search.
        long base = 768 + 192 * 2 + 224 * 3 + 96 * 6;
        var tight = new PlanningBudget(0, 10_000, base, () -> false, System::nanoTime);
        try (var task = StateEntityMatching.create(demands, supplies, (d, s) -> s < 2, tight)) {
            check(task != null, "Optional workspace displaced the exact search");
            while (!task.step()) {}
            check(!task.feasible() && task.fragmentation().threshold() == 0, "Refused workspace changed the answer");
        }
        check(tight.reservedBytes() == 0 && tight.peakBytes() <= base, "Fallback exceeded memory");
        var wide = List.of(new StateEntityAllocation.Demand(0, Long.MAX_VALUE, false),
                new StateEntityAllocation.Demand(1, Long.MAX_VALUE, false));
        var budget = budget();
        try (var task = StateEntityCohorts.create(wide, 2, supplies, new boolean[]{true,true,true,true,true,true}, budget)) {
            while (!task.step()) {}
            check(task.possible() && task.deficit() == null, "Narrowed a wide cohort quotient before saturation");
        }
        check(budget.reservedBytes() == 0, "Wide quotient leaked");
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 1_000_000, 1 << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
