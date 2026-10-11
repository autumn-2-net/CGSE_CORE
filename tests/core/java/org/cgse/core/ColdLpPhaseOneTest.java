// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

/** Independent phase-I regression: all instances contain 0 >= 1 after summing two rows. */
public final class ColdLpPhaseOneTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static PlanningBudget budget() { return new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime); }
    public static void main(String[] args) throws Exception {
        long resumes = 0, totalWork = 0;
        var workers = new ExecutorService[] {Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor()};
        try {
            for (int seed = 0; seed < 48; seed++) {
                int n = 48;
                var random = new Random(7348921L + seed);
                var planted = new int[n];
                var cost = new BigInteger[n];
                for (int i = 0; i < n; i++) { planted[i] = random.nextInt(8); cost[i] = BigInteger.valueOf(random.nextInt(7) - 3); }
                var rows = new ArrayList<ExactLinearProgram.Constraint>();
                for (int r = 0; r < 24; r++) {
                    var terms = new LinkedHashMap<Integer, BigInteger>();
                    while (terms.size() < 9) terms.put(random.nextInt(n), BigInteger.valueOf(random.nextInt(9) + 1));
                    var rhs = BigInteger.ZERO;
                    for (var term : terms.entrySet()) rhs = rhs.add(term.getValue().multiply(BigInteger.valueOf(planted[term.getKey()])));
                    rows.add(new ExactLinearProgram.Constraint(terms, rhs));
                    var negative = new LinkedHashMap<Integer, BigInteger>();
                    terms.forEach((id, value) -> negative.put(id, value.negate()));
                    rows.add(new ExactLinearProgram.Constraint(negative, rhs.negate()));
                }
                for (int i = 0; i < n; i++) rows.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), BigInteger.valueOf(7)));
                rows.add(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE), BigInteger.ZERO));
                rows.add(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE.negate()), BigInteger.ONE.negate()));
                Collections.shuffle(rows, random);
                var referenceBudget = budget();
                CountNumericRelaxation.Result reference;
                try (var whole = new CountNumericRelaxation.Session()) {
                    reference = whole.solve(n, rows, cost, referenceBudget, 2_000_000);
                }
                check(reference != null && reference.phaseOneInfeasible() && reference.point() == null, "phase-I reference missing: " + seed);
                var slicedBudget = budget();
                try (var sliced = new CountNumericRelaxation.Session(true)) {
                    CountNumericRelaxation.Result actual = null;
                    int visits = 0;
                    while (actual == null && visits++ < 512) {
                        actual = workers[visits % 2].submit(() -> sliced.solve(n, rows, cost, slicedBudget, 2048)).get(30, TimeUnit.SECONDS);
                        if (actual == null) check(sliced.pending(), "Lost phase-I pending: " + seed);
                    }
                    check(actual != null && actual.phaseOneInfeasible() && actual.point() == null, "No phase-I completion: " + seed);
                    check(Arrays.equals(actual.dual(), reference.dual()), "Phase-I dual changed across visits: " + seed);
                    check(!sliced.pending() && slicedBudget.reservedBytes() == 0, "Infeasible tableau retained: " + seed);
                    check(sliced.rebuilt() == 1, "Phase-I rebuilt: " + seed);
                    resumes += sliced.resumed();
                    totalWork += slicedBudget.nodes();
                }
                check(slicedBudget.reservedBytes() == 0 && referenceBudget.reservedBytes() == 0, "Phase-I memory leak");
            }
        } finally { for (var worker : workers) worker.shutdownNow(); }
        check(resumes > 48, "Insufficient pending phase-I coverage");
        System.out.println("PASS phase-I infeasible paired=48, permuted rows, alternating workers; resumes=" + resumes + "; work=" + totalWork);
    }
}
