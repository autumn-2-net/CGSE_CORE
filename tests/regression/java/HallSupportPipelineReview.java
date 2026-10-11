// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Coupled wide counters keep a small matching kernel in the main count model. */
public final class HallSupportPipelineReview {
    public static void main(String[] args) {
        for (int n : new int[]{96, 600}) for (int order = 0; order < 3; order++) {
            solve(model(10, n, order, false), false);
            solve(model(5, n, order, true), true);
        }
        System.out.println("Main count stage: 6 coupled wide-domain Hall deficits and 6 independently witnessed neighbors; original count and primitive execution checks passed");
    }

    static HallCapacityTest.Model model(int groups, int n, int order, boolean missing) {
        var embedded = HallSupportTest.embed(HallCapacityTest.coloring(groups, groups - 1, missing, order), n, order, false);
        var high = embedded.high().clone();
        for (int i = 0; i < n; i++) if (high[i] == null) high[i] = BigInteger.valueOf(1000);
        var rows = new ArrayList<>(embedded.rows());
        var active = new HashSet<Integer>();
        for (int id : embedded.ids()) active.add(id);
        var shared = new TreeMap<Integer, BigInteger>();
        for (int i = 0; i < n; i++) if (!active.contains(i)) shared.put(i, BigInteger.ONE.negate());
        shared.put(embedded.ids()[0], BigInteger.ONE.negate());
        rows.add(new ExactLinearProgram.Constraint(shared, BigInteger.valueOf(-100)));
        if (missing) {
            // Groups zero and one share resource zero, whose pair edge is
            // absent. One unrelated wide counter funds the shared demand.
            var witness = embedded.low().clone();
            for (int g = 0; g < groups; g++) witness[embedded.ids()[g * (groups - 1) + Math.max(0, g - 1)]] = BigInteger.ONE;
            for (int i = 0; i < n; i++) if (!active.contains(i)) { witness[i] = BigInteger.valueOf(100); break; }
            checkRows(rows, witness);
        }
        return new HallCapacityTest.Model(rows, embedded.low(), high);
    }

    private static void solve(HallCapacityTest.Model model, boolean feasible) {
        var budget = new PlanningBudget(0, 40_000_000, 256L << 20, () -> false, System::nanoTime);
        var result = CountModelReplay.solve("main-counts", model.rows(), model.low(), model.high(), budget, 40_000_000);
        check(budget.reservedBytes() == 0, "Count-stage workspace leaked");
        check(feasible ? result.counts() != null : result.infeasible(), "Local Hall boundary unresolved: " + budget.diagnostics());
        if (result.counts() == null) {
            check(budget.nodes() < 1_000_000, "Small conflict lost support-based admission");
            return;
        }
        for (int i = 0; i < result.counts().length; i++)
            check(result.counts()[i].compareTo(model.low()[i]) >= 0 && result.counts()[i].compareTo(model.high()[i]) <= 0, "Original domain violated");
        checkRows(model.rows(), result.counts());
    }

    private static void checkRows(List<ExactLinearProgram.Constraint> rows, BigInteger[] point) {
        for (var row : rows) {
            var sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(point[term.getKey()]));
            check(sum.compareTo(row.upper()) <= 0, "Original row violated");
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
