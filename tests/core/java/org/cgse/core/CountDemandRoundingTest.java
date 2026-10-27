// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Consumer rounding must not discard a funded mixed-source route. */
public final class CountDemandRoundingTest {
    private static int checks;

    public static void main(String[] args) {
        int retries = 0;
        for (long amount : new long[]{64, 1000, 1_000_000_000L}) {
            for (int order = 0; order < 3; order++) for (int sourceOrder = 0; sourceOrder < 3; sourceOrder++) {
                var rows = rows(amount, order);
                var columns = new ArrayList<>(List.of(0, 1, 2, 3, 4));
                if (sourceOrder == 1) Collections.reverse(columns);
                if (sourceOrder == 2) Collections.shuffle(columns, new Random(20261027));
                rows.replaceAll(row -> {
                    var terms = new TreeMap<Integer, BigInteger>();
                    row.terms().forEach((v, a) -> terms.put(columns.get(v), a));
                    return new ExactLinearProgram.Constraint(terms, row.upper());
                });
                var budget = new PlanningBudget(0, 2_000_000, 16L << 20, () -> false, System::nanoTime);
                try (var projection = new CountDemandProjection(5, rows, budget, 1_000_000)) {
                    while (!projection.step()) {}
                    check(valid(rows, projection.result()), "Lost mixed-source witness: " + amount + "/" + order + "/" + sourceOrder);
                    if (budget.diagnostics().contains("count_demand_rounding")) retries++;
                    check(projection.work() <= 1_000_001, "Rounding retry replenished work");
                }
                check(budget.reservedBytes() == 0, "Rounding retry retained workspace");
            }
        }
        check(retries > 0, "Rounding controls did not exercise the retry");
        cancellation();
        for (long cap : new long[]{1, 32, 256, 1024, 32768, 40000}) {
            var rows = rows(1000, 0);
            var budget = new PlanningBudget(0, 2_000_000, 16L << 20, () -> false, System::nanoTime);
            try (var projection = new CountDemandProjection(5, rows, budget, cap)) {
                while (!projection.step()) {}
                check(projection.work() <= cap + 1, "Rounding retry replenished its local allowance");
                check(projection.result() == null || valid(rows, projection.result()), "Cutoff published invalid counts");
            }
            check(budget.reservedBytes() == 0, "Bounded retry retained workspace");
        }
        for (long bytes : new long[]{1, 4096, 8192, 16384, 32768, 65536}) {
            var rows = rows(1000, 0);
            var budget = new PlanningBudget(0, 2_000_000, bytes, () -> false, System::nanoTime);
            try (var projection = new CountDemandProjection(5, rows, budget, 1_000_000)) {
                while (!projection.step()) {}
                check(projection.result() == null || valid(rows, projection.result()), "Memory refusal published invalid counts");
            }
            check(budget.reservedBytes() == 0 && budget.peakBytes() <= bytes, "Rounding retry escaped memory accounting");
        }
        System.out.println("CountDemandRoundingTest PASS: checks=" + checks + "; retries=" + retries);
    }

    private static ArrayList<ExactLinearProgram.Constraint> rows(long amount, int order) {
        // Two efficient sources share finite feed. Each also has a smaller
        // alternative source. An LP optimum exhausts the shared feed; ceiling
        // the two consumers separately can overdraw it by a few units.
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        rows.add(row(Map.of(0, -1), -amount));
        rows.add(row(Map.of(0, 7, 1, -3, 3, -1), 0));
        rows.add(row(Map.of(0, 5, 2, -3, 4, -1), 0));
        rows.add(row(Map.of(1, 2, 2, 3), 9 * amount));
        rows.add(row(Map.of(3, 1), 3 * amount));
        rows.add(row(Map.of(4, 1), 4 * amount));
        // Independent full integer witness: 2N and N efficient runs plus N
        // and 2N alternative runs, using only 7N of the available 9N feed.
        BigInteger n = BigInteger.valueOf(amount);
        check(valid(rows, new BigInteger[]{n, n.multiply(BigInteger.TWO), n, n, n.multiply(BigInteger.TWO)}),
                "Synthetic control has no funded witness");
        if (order == 1) Collections.reverse(rows);
        if (order == 2) Collections.shuffle(rows, new Random(20261026));
        return rows;
    }

    private static void cancellation() {
        var stop = new AtomicBoolean();
        var budget = new PlanningBudget(0, 2_000_000, 16L << 20, stop::get, System::nanoTime);
        try (var projection = new CountDemandProjection(5, rows(1000, 0), budget, 1_000_000)) {
            while (!budget.diagnostics().contains("count_demand_rounding"))
                check(!projection.step(), "Cancellation control did not enter rounding retry");
            stop.set(true);
            try { projection.step(); throw new AssertionError("Rounding retry swallowed cancellation"); }
            catch (java.util.concurrent.CancellationException expected) { checks++; }
            check(projection.result() == null, "Cancelled retry published an incomplete proposal");
        }
        check(budget.reservedBytes() == 0, "Cancelled retry retained workspace");
    }

    private static ExactLinearProgram.Constraint row(Map<Integer, Integer> terms, long upper) {
        var exact = new TreeMap<Integer, BigInteger>();
        terms.forEach((v, a) -> exact.put(v, BigInteger.valueOf(a)));
        return new ExactLinearProgram.Constraint(exact, BigInteger.valueOf(upper));
    }

    private static boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] counts) {
        if (counts == null) return false;
        for (var value : counts) if (value == null || value.signum() < 0) return false;
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(counts[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private static void check(boolean condition, String detail) { checks++; if (!condition) throw new AssertionError(detail); }
}
