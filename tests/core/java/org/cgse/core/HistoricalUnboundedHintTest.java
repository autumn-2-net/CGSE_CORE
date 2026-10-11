// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Both historical deferred-hint reproducers: finite child hints must not cap an unbounded parent. */
public final class HistoricalUnboundedHintTest {
    private static BigInteger b(long n) { return BigInteger.valueOf(n); }

    public static void main(String[] args) {
        int colors = 5, vertices = colors + 1, n = colors * vertices + 1, escape = n - 1;
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int v = 0; v < vertices; v++) {
            var upper = new LinkedHashMap<Integer, BigInteger>();
            var lower = new LinkedHashMap<Integer, BigInteger>();
            for (int c = 0; c < colors; c++) { upper.put(v * colors + c, b(1)); lower.put(v * colors + c, b(-1)); }
            rows.add(new ExactLinearProgram.Constraint(upper, b(1)));
            rows.add(new ExactLinearProgram.Constraint(lower, b(-1)));
        }
        for (int v = 0; v < vertices; v++) for (int w = v + 1; w < vertices; w++) for (int c = 0; c < colors; c++)
            rows.add(new ExactLinearProgram.Constraint(Map.of(v * colors + c, b(2), w * colors + c, b(2), escape, b(-1)), b(2)));
        int cases = 0, resumes = 0;
        for (BigInteger cap : new BigInteger[] {null, b(2), b(1), b(0)}) for (int quantum : new int[] {1, 64, 1024, 131072}) {
            var low = new BigInteger[n]; var high = new BigInteger[n];
            Arrays.fill(low, b(0)); Arrays.fill(high, b(1)); high[escape] = cap;
            var budget = new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime);
            try (var solver = new CountLcg(rows, low, high, budget, 1024, true)) {
                while (true) {
                    while (!solver.step()) {}
                    if (!solver.paused()) break;
                    if (solver.infeasible()) throw new AssertionError("Local allowance became UNSAT");
                    long work = budget.threadWork(), memory = budget.reservedBytes();
                    if (!solver.step() || budget.threadWork() != work || budget.reservedBytes() != memory)
                        throw new AssertionError("Paused solver ran without allowance");
                    solver.resume(quantum); resumes++;
                }
                boolean possible = cap == null || cap.compareTo(b(2)) >= 0;
                var x = solver.counts();
                if (possible) {
                    if (x == null || solver.infeasible()) throw new AssertionError("Finite hint lost unbounded escape");
                    for (int i = 0; i < n; i++) if (x[i].signum() < 0 || high[i] != null && x[i].compareTo(high[i]) > 0)
                        throw new AssertionError("Hint escaped original bounds");
                    for (var row : rows) {
                        var sum = BigInteger.ZERO;
                        for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(x[term.getKey()]));
                        if (sum.compareTo(row.upper()) > 0) throw new AssertionError("Invalid coloring witness");
                    }
                } else if (x != null || !solver.infeasible() || CountProof.verify(solver.certificate(), 20_000_000) != CountProof.Verdict.VERIFIED)
                    throw new AssertionError("Pigeonhole proof missing or invalid");
                cases++;
            }
            if (budget.reservedBytes() != 0) throw new AssertionError("Hint continuation leaked");
        }
        if (resumes == 0) throw new AssertionError("No continuation exercised");
        System.out.println("Historical unbounded hints: " + cases + " finite/unbounded domains; " + resumes + " continuations, witnesses and independent proofs passed");
    }
}
