// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;
import static org.cgse.core.HistoricalFixtures.*;

/** FocusedWarm and CountCounterReview's proof-retention case, without native solver imports. */
public final class HistoricalWarmReplayTest {
    private static boolean subsetOracle(Fixture fixture) {
        int n = (fixture.recipes().size() - 1) / 2;
        long[] x = new long[n], y = new long[n];
        for (int i = 0; i < n; i++) { var out = fixture.recipes().get(2 * i).outputs(); x[i] = out.get("X0"); y[i] = out.get("X1"); }
        var goal = fixture.recipes().get(2 * n).inputs();
        long sx = 0, sy = 0;
        for (int bits = 0, previous = 0; bits < 1 << n; bits++) {
            int gray = bits ^ bits >>> 1;
            if (bits > 0) {
                int changed = gray ^ previous, at = Integer.numberOfTrailingZeros(changed), sign = (gray & changed) == 0 ? -1 : 1;
                sx += sign * x[at]; sy += sign * y[at];
            }
            if (sx == goal.get("X0") && sy == goal.get("X1")) return true;
            previous = gray;
        }
        return false;
    }

    // Independent unit propagation + exhaustive Boolean branching for the old
    // unplanted 24-variable input. It neither calls CGSE nor a numerical solver.
    private static boolean satOracle(Fixture fixture) {
        int clauses = (int) fixture.recipes().get(fixture.recipes().size() - 1).inputs().keySet().stream().filter(k -> k.startsWith("C")).count();
        int[] positive = new int[clauses], negative = new int[clauses];
        for (var r : fixture.recipes()) if (r.id().startsWith("v")) {
            var id = r.id().substring(1).split("_"); int bit = 1 << Integer.parseInt(id[0]);
            for (var key : r.outputs().keySet()) if (key.startsWith("C")) {
                int c = Integer.parseInt(key.substring(1));
                if (id[1].equals("1")) positive[c] |= bit; else negative[c] |= bit;
            }
        }
        return sat(positive, negative, 0, 0);
    }

    private static boolean sat(int[] positive, int[] negative, int yes, int no) {
        while (true) {
            int choose = 0; boolean changed = false;
            for (int i = 0; i < positive.length; i++) {
                if ((positive[i] & yes) != 0 || (negative[i] & no) != 0) continue;
                int remaining = (positive[i] | negative[i]) & ~(yes | no);
                if (remaining == 0) return false;
                if (Integer.bitCount(remaining) == 1) {
                    if ((positive[i] & remaining) != 0) yes |= remaining; else no |= remaining;
                    changed = true;
                } else if (choose == 0 || Integer.bitCount(remaining) < Integer.bitCount(choose)) choose = remaining;
            }
            if (changed) continue;
            if (choose == 0) return true;
            int bit = Integer.lowestOneBit(choose);
            return sat(positive, negative, yes | bit, no) || sat(positive, negative, yes, no | bit);
        }
    }

    public static void main(String[] args) {
        var fixtures = List.of(multi(20,2,1000,42,0), multi(20,2,1000,42,1), randomSat(24,103,0),
                HistoricalFixtures.sat(96,384,42), explicitPairs(multi(20,2,1000,42,0)), multi(48,2,1000,42,0));
        int checked = 0;
        for (int id = 0; id < fixtures.size(); id++) {
            var f = fixtures.get(id);
            boolean oracle = id == 1 ? subsetOracle(f) : id == 2 ? satOracle(f) : true; // Other generators plant their witnesses.
            var compiler = new GraphCompiler<>(f.recipes());
            for (int round = 0; round < 3; round++) {
                var budget = new PlanningBudget(0,40_000_000,256L<<20,()->false,System::nanoTime);
                var work = new GraphPlanningWork<>(compiler,f.target(),f.amount(),f.stock(),true,true,budget);
                try {
                    while (!work.step()) {}
                    var p = work.result();
                    if (!Set.of(GraphPlan.Result.FEASIBLE,GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL,GraphPlan.Result.INFEASIBLE,
                            GraphPlan.Result.MISSING_INPUT,GraphPlan.Result.MISSING_SEED).contains(p.result()))
                        throw new AssertionError("Unresolved historical warm input: "+f.name()+" "+p.result());
                    if (p.feasible() != oracle) throw new AssertionError("Independent warm oracle mismatch: "+f.name());
                    if (p.feasible()) { verify(f,summary(p.steps(),p.recipes())); PlanVerifier.verifyRuntimeInventory(p); }
                    else if (!p.missingExact().isEmpty()) {
                        var funded = new LinkedHashMap<>(f.stock());
                        p.missingExact().forEach((k,v)->funded.merge(k,v.longValueExact(),Math::addExact));
                        verify(new Fixture(f.name(),f.recipes(),funded,f.target(),f.amount(),f.dag()),summary(p.steps(),p.recipes()));
                    }
                    checked++;
                } finally { work.close(); }
                if (budget.reservedBytes()!=0) throw new AssertionError("Warm request leaked memory");
            }
        }
        var f=fixtures.get(2);
        if(satOracle(f))throw new AssertionError("proof-retention fixture must be UNSAT");
        var budget=new PlanningBudget(0,40_000_000,256L<<20,()->false,System::nanoTime);
        var work=new GraphPlanningWork<>(new GraphCompiler<>(f.recipes()),f.target(),f.amount(),f.stock(),true,true,budget);
        try {
            while(!work.step()) if(budget.diagnostics().contains("order_proven_infeasible"))break;
            var limited=work.limited(new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT));
            if(limited.result()!=GraphPlan.Result.INFEASIBLE)throw new AssertionError("Completed proof lost at preview cutoff: "+limited.result());
        } finally { work.close(); }
        if(budget.reservedBytes()!=0)throw new AssertionError("Proof cutoff leaked");
        System.out.println("Historical warm inputs: "+checked+" cold/warm replays; independent subset/CNF checks and proof retention passed");
    }
}
