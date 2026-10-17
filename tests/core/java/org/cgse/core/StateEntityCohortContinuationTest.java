// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Nested cohort graphs are checked by subset enumeration, not another matcher. */
public final class StateEntityCohortContinuationTest {
    private static int cases, checks;

    public static void main(String[] args) {
        exhaustive(); random(); alternating(); scaling(); boundaries();
        System.out.println("StateEntityCohortContinuationTest: cases=" + cases + "; assertions=" + checks);
    }

    private static void exhaustive() {
        for (int a = 0; a <= 5; a++) for (int b = 0; b <= 5; b++)
            for (int edges = 0; edges < 64; edges++) {
                boolean[][] allowed = new boolean[3][2];
                for (int d = 0; d < 3; d++) for (int s = 0; s < 2; s++)
                    allowed[d][s] = (edges & (1 << (d * 2 + s))) != 0;
                compare(new long[]{4, 3, 2}, new BigInteger[]{BigInteger.valueOf(a), BigInteger.valueOf(b)},
                        allowed, (edges & 1) != 0);
            }
    }

    private static void random() {
        var random = new Random(0xc04047L);
        for (int i = 0; i < 1200; i++) {
            int n = 1 + random.nextInt(8), m = random.nextInt(7);
            long[] need = new long[n]; BigInteger[] stock = new BigInteger[m];
            boolean[][] edges = new boolean[n][m];
            for (int d = 0; d < n; d++) {
                need[d] = 2 + random.nextInt(12);
                for (int s = 0; s < m; s++) edges[d][s] = random.nextInt(4) != 0;
            }
            for (int s = 0; s < m; s++) stock[s] = i % 19 == 0 ? BigInteger.ONE.shiftLeft(100 + s) :
                    BigInteger.valueOf(random.nextInt(30));
            compare(need, stock, edges, (i & 1) != 0);
        }
    }

    private static void compare(long[] need, BigInteger[] stock, boolean[][] edges, boolean reverse) {
        long failed = failingThreshold(need, stock, edges);
        var demands = new ArrayList<StateEntityAllocation.Demand>();
        var supplies = new ArrayList<StateEntityAllocation.Supply<Integer>>();
        for (int d = 0; d < need.length; d++) demands.add(new StateEntityAllocation.Demand(d, need[d], false));
        for (int s = 0; s < stock.length; s++) if (stock[s].signum() > 0)
            supplies.add(new StateEntityAllocation.Supply<>(s, stock[s], 1));
        if (reverse) { Collections.reverse(demands); Collections.reverse(supplies); }
        demands.sort((a,b) -> Long.compare(b.amount(), a.amount()));
        int width = supplies.size();
        boolean[] allowed = new boolean[need.length * width];
        for (int d = 0; d < need.length; d++) for (int s = 0; s < width; s++)
            allowed[d * width + s] = edges[demands.get(d).slot()][supplies.get(s).state()];
        var budget = budget(1_000_000);
        try (var task = StateEntityCohorts.create(demands, need.length, supplies, allowed, budget)) {
            check(task != null && task.deficit() == null, "Pending matching exported evidence");
            int steps = 0;
            while (!task.step()) check(++steps < 1000, "Did not retain continuation");
            check(task.possible() == (failed == 0), "Exhaustive Hall oracle disagrees");
            var proof = task.deficit();
            if (failed == 0) check(proof == null, "Passing relaxation exported failure");
            else {
                check(proof != null && proof.threshold() == failed, "Lost the first failing threshold");
                var slots = new HashSet<>(proof.slots());
                check(!slots.isEmpty() && slots.size() == proof.slots().size(), "Invalid support");
                BigInteger available = BigInteger.ZERO;
                for (int d : slots) check(d >= 0 && d < need.length && need[d] >= failed, "Changed slot identity");
                for (int s = 0; s < stock.length; s++) {
                    boolean neighbor = false;
                    for (int d : slots) neighbor |= edges[d][s];
                    if (neighbor) available = available.add(stock[s].divide(BigInteger.valueOf(failed)));
                }
                check(available.compareTo(BigInteger.valueOf(slots.size())) < 0 &&
                        available.equals(BigInteger.valueOf(proof.available())), "Invalid original-supply certificate");
            }
            task.close(); check(task.deficit() == null, "Closed matching exported evidence");
        }
        check(budget.reservedBytes() == 0, "Retained matching leaked"); cases++;
    }

    /** Enumerate all Hall subsets for every threshold, with original integers. */
    private static long failingThreshold(long[] need, BigInteger[] stock, boolean[][] edges) {
        long failure = 0;
        for (long q : need) for (int subset = 1; subset < (1 << need.length); subset++) {
            boolean active = true;
            for (int d = 0; d < need.length; d++) if ((subset & (1 << d)) != 0 && need[d] < q) active = false;
            if (!active) continue;
            BigInteger supply = BigInteger.ZERO;
            for (int s = 0; s < stock.length; s++) {
                boolean neighbor = false;
                for (int d = 0; d < need.length; d++) neighbor |= (subset & (1 << d)) != 0 && edges[d][s];
                if (neighbor) supply = supply.add(stock[s].divide(BigInteger.valueOf(q)));
            }
            if (supply.compareTo(BigInteger.valueOf(Integer.bitCount(subset))) < 0) failure = Math.max(failure, q);
        }
        return failure;
    }

    private static void alternating() {
        // The new last slot can only use key zero. Retaining old assignments
        // must still allow an augmenting path through every earlier slot.
        compare(new long[]{8, 7, 6, 5}, new BigInteger[]{bi(8), bi(8), bi(8), bi(8)},
                new boolean[][]{{true,true,false,false},{false,true,true,false},{false,false,true,true},{true,false,false,false}}, false);
        // A lower threshold increases the multiplicity of a retained key.
        compare(new long[]{9, 6, 4, 3, 2}, new BigInteger[]{bi(18)},
                new boolean[][]{{true},{true},{true},{true},{true}}, false);
        // Old assignments must count towards used even after capacities grow;
        // the final two eligible keys still cannot cover all three slots.
        compare(new long[]{5, 4, 3}, new BigInteger[]{bi(5),bi(5),bi(1000)},
                new boolean[][]{{true,true,false},{true,true,false},{true,true,false}}, true);
        // Clamp huge quotients after division; never narrow supply or demand.
        compare(new long[]{Long.MAX_VALUE,Long.MAX_VALUE-1,2},
                new BigInteger[]{BigInteger.ONE.shiftLeft(100),bi(Long.MAX_VALUE)},
                new boolean[][]{{true,true},{false,true},{true,false}}, true);
    }

    private static void scaling() {
        for (int n : new int[]{16, 64, 128}) for (boolean reverse : List.of(false, true)) {
            var demands = new ArrayList<StateEntityAllocation.Demand>();
            var supplies = new ArrayList<StateEntityAllocation.Supply<Integer>>();
            for (int d = 0; d < n; d++) demands.add(new StateEntityAllocation.Demand(d, 4L * n - d, false));
            for (int s = 0; s < n; s++) supplies.add(new StateEntityAllocation.Supply<>(s, bi(4L * n), 1));
            if (reverse) Collections.reverse(supplies);
            boolean[] allowed = new boolean[n * n];
            for (int d = 0; d < n; d++) for (int s = 0; s < n; s++) allowed[d * n + s] = d == supplies.get(s).state();
            // One explicit distinct key per slot satisfies every threshold.
            // The cap rejects rebuilding all previous matchings at each q.
            var budget = budget(5L * n * n);
            try (var task = StateEntityCohorts.create(demands, n, supplies, allowed, budget)) {
                while (!task.step()) {}
                check(task.possible() && task.deficit() == null, "Lost explicit threshold assignment");
            }
            check(budget.reservedBytes() == 0 && budget.searchWork() <= 5L * n * n, "Repeated old threshold work");
            cases++;
        }
    }

    private static void boundaries() {
        var demands = List.of(new StateEntityAllocation.Demand(0, 8, false), new StateEntityAllocation.Demand(1, 7, false),
                new StateEntityAllocation.Demand(2, 6, false), new StateEntityAllocation.Demand(3, 5, false));
        var supplies = List.of(new StateEntityAllocation.Supply<>(0, bi(8), 1), new StateEntityAllocation.Supply<>(1, bi(8), 1),
                new StateEntityAllocation.Supply<>(2, bi(8), 1), new StateEntityAllocation.Supply<>(3, bi(8), 1));
        boolean[] edges = {true,true,false,false, false,true,true,false, false,false,true,true, true,false,false,false};
        for (int limit = 1; limit < 250; limit++) {
            var budget = budget(limit);
            try (var task = StateEntityCohorts.create(demands, 4, supplies, edges, budget)) {
                try {
                    while (!task.step()) check(task.deficit() == null, "Incomplete work exported failure");
                    check(task.possible() && task.deficit() == null, "Wrong completed result");
                } catch (PlanningBudget.Exhausted expected) {
                    check(task.deficit() == null, "Interrupted mutation exported failure");
                }
            }
            check(budget.reservedBytes() == 0 && budget.searchWork() <= limit + 1L, "Renewed budget or leaked memory");
        }
        for (int at = 0; at < 55; at++) {
            var cancelled = new AtomicBoolean();
            var budget = new PlanningBudget(0, 10_000, 1 << 20, cancelled::get, System::nanoTime);
            try (var task = StateEntityCohorts.create(demands, 4, supplies, edges, budget)) {
                for (int i = 0; i < at && !task.step(); i++) {}
                cancelled.set(true);
                try { task.step(); throw new AssertionError("Ignored cancellation"); }
                catch (CancellationException expected) { check(task.deficit() == null, "Cancelled work exported a deficit"); }
            }
            check(budget.reservedBytes() == 0, "Cancellation retained storage");
        }
        var tight = new PlanningBudget(0, 10_000, 1, () -> false, System::nanoTime);
        check(StateEntityCohorts.create(demands, 4, supplies, edges, tight) == null && tight.reservedBytes() == 0,
                "Optional retained matching ignored memory limit");
    }

    private static BigInteger bi(long x) { return BigInteger.valueOf(x); }
    private static PlanningBudget budget(long work) { return new PlanningBudget(0, work, 1 << 20, () -> false, System::nanoTime); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
