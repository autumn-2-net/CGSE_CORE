// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Differential finite-domain oracle, wide domains, scoped memoization and cutoffs. */
public final class InterfaceSearchTest {
    private static final BigInteger ZERO = BigInteger.ZERO, ONE = BigInteger.ONE;

    public static void main(String[] args) throws Exception {
        wideAndMemoized();
        randomOracle();
        widePairs();
        cancellation();
        limits();
        System.out.println("Interface search: 1,200 randomized oracle/order cases, wide domains, scoped cache, cancellation and limits passed");
    }

    private static PlanningBudget budget(long work) {
        return new PlanningBudget(0, work, 64L << 20, () -> false, System::nanoTime);
    }

    private static void equation(List<ExactLinearProgram.Constraint> rows, Map<Integer, BigInteger> terms, long rhs) {
        rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(rhs)));
        Map<Integer, BigInteger> reverse = new LinkedHashMap<>();
        terms.forEach((id, a) -> reverse.put(id, a.negate()));
        rows.add(new ExactLinearProgram.Constraint(reverse, BigInteger.valueOf(-rhs)));
    }

    private static void wideAndMemoized() throws Exception {
        List<ExactLinearProgram.Constraint> rows = new ArrayList<>();
        equation(rows, Map.of(2, ONE, 0, ONE.negate()), 100);
        for (int i = 3; i < 9; i++) equation(rows, Map.of(i, ONE, 0, ONE.negate(), 1, ONE.negate()), 100);
        equation(rows, Map.of(9, BigInteger.TWO, 0, BigInteger.TWO.negate(), 1, ONE.negate()), 201);
        BigInteger[] low = new BigInteger[10], high = new BigInteger[10];
        Arrays.fill(low, ZERO);
        Arrays.fill(high, BigInteger.TEN.pow(40));
        high[0] = high[1] = BigInteger.TWO;
        // One truly unbounded internal block must not make a tiny interface ineligible.
        high[3] = null;
        var budget = budget(4_000_000);
        var journal = new CountProof.Journal(8L << 20);
        budget.proofJournal(journal);
        try (var search = new CountInterfaceSearch(rows, low, high, budget, 200_000)) {
            while (!search.step()) {}
            var result = search.counts();
            check(result != null && valid(rows, low, high, result), "Wide block candidate missing");
            check(search.cacheHits() > 0, "Independent local interface results were not reused");
        }
        check(budget.reservedBytes() == 0, "Wide decomposition leaked");
        for (var proof : journal.entries()) check(CountProof.verify(proof, 5_000_000) == CountProof.Verdict.VERIFIED,
                "Invalid scoped component proof: " + proof.scope());
        var nextBudget = budget(4_000_000);
        // An equivalent topology with a changed RHS has its own answer table.
        rows.add(new ExactLinearProgram.Constraint(Map.of(9, ONE), ZERO));
        try (var search = new CountInterfaceSearch(rows, low, high, nextBudget, 200_000)) {
            while (!search.step()) {}
            check(search.counts() == null, "Component witness escaped its model/RHS scope");
        }
        check(nextBudget.reservedBytes() == 0, "Changed model leaked");
    }

    private static void randomOracle() {
        Random random = new Random(20261008);
        for (int sample = 0; sample < 300; sample++) {
            List<ExactLinearProgram.Constraint> original = new ArrayList<>();
            int offset = sample % 4;
            BigInteger[] low = {BigInteger.valueOf(offset), ZERO, ZERO, ZERO};
            BigInteger[] high = {BigInteger.valueOf(offset + 3), BigInteger.valueOf(80), BigInteger.valueOf(80), BigInteger.valueOf(80)};
            for (int block = 1; block <= 3; block++) {
                int coefficient = 1 + random.nextInt(9), shared = random.nextInt(9) - 4;
                int rhs = random.nextInt(450) - 20;
                equation(original, Map.of(block, BigInteger.valueOf(coefficient), 0, BigInteger.valueOf(shared)), rhs);
            }
            boolean expected = false;
            for (int s = offset; s <= offset + 3; s++) {
                boolean all = true;
                for (int block = 1; block <= 3; block++) {
                    boolean found = false;
                    for (int x = 0; x <= 80; x++) {
                        var row = original.get((block - 1) * 2);
                        BigInteger lhs = row.terms().get(block).multiply(BigInteger.valueOf(x)).add(
                                row.terms().get(0).multiply(BigInteger.valueOf(s)));
                        if (lhs.equals(row.upper())) { found = true; break; }
                    }
                    all &= found;
                }
                expected |= all;
            }
            for (int order = 0; order < 3; order++) {
                var rows = new ArrayList<>(original);
                Collections.shuffle(rows, random);
                List<Integer> mapping = new ArrayList<>(List.of(0, 1, 2, 3));
                Collections.shuffle(mapping, random);
                List<ExactLinearProgram.Constraint> permuted = new ArrayList<>();
                for (var row : rows) {
                    Map<Integer, BigInteger> terms = new LinkedHashMap<>();
                    row.terms().forEach((id, a) -> terms.put(mapping.get(id), a));
                    permuted.add(new ExactLinearProgram.Constraint(terms, row.upper()));
                }
                BigInteger[] plow = new BigInteger[4], phigh = new BigInteger[4];
                for (int i = 0; i < 4; i++) { plow[mapping.get(i)] = low[i]; phigh[mapping.get(i)] = high[i]; }
                var budget = budget(2_000_000);
                try (var search = new CountSeparator(permuted, plow, phigh, budget)) {
                    while (!search.step()) {}
                    var result = search.counts();
                    check((result != null) == expected, "Oracle mismatch sample=" + sample + " order=" + order + " " + budget.diagnostics());
                    check(!search.infeasible() || !expected, "Local failure became false global UNSAT");
                    if (result != null) check(valid(permuted, plow, phigh, result), "Invalid restored witness");
                }
                check(budget.reservedBytes() == 0, "Oracle case leaked");
            }
        }
    }

    private static void cancellation() {
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        equation(rows, Map.of(0, ONE, 1, BigInteger.TWO), 101);
        equation(rows, Map.of(0, ONE, 2, BigInteger.valueOf(3)), 102);
        BigInteger[] low = {ZERO, ZERO, ZERO}, high = {BigInteger.valueOf(3), BigInteger.valueOf(1000), null};
        for (int point = 1; point <= 120; point++) {
            AtomicInteger visits = new AtomicInteger();
            int at = point;
            var budget = new PlanningBudget(0, 2_000_000, 64L << 20, () -> visits.incrementAndGet() >= at, System::nanoTime);
            CountSeparator search = null;
            try {
                search = new CountSeparator(rows, low, high, budget);
                while (!search.step()) {}
            } catch (java.util.concurrent.CancellationException expected) {
                // Cancellation is not a mathematical status.
            } finally {
                if (search != null) { search.close(); search.close(); }
            }
            check(budget.reservedBytes() == 0, "Cancelled cutset leaked at " + point);
        }
    }

    private static void widePairs() {
        Random random = new Random(480123);
        for (int sample = 0; sample < 100; sample++) {
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            BigInteger[] low = {ZERO, ZERO, ZERO, ZERO, ZERO};
            BigInteger[] high = {BigInteger.valueOf(3), BigInteger.valueOf(40), BigInteger.valueOf(40), BigInteger.valueOf(40), BigInteger.valueOf(40)};
            for (int b = 0; b < 2; b++) {
                int x = 1 + 2 * b, y = x + 1;
                equation(rows, Map.of(0, BigInteger.valueOf(1 + random.nextInt(4)), x, BigInteger.valueOf(2 + random.nextInt(8)),
                        y, BigInteger.valueOf(2 + random.nextInt(8))), 1 + random.nextInt(300));
                rows.add(new ExactLinearProgram.Constraint(Map.of(x, ONE, y, ONE.negate()), BigInteger.valueOf(random.nextInt(30) - 10)));
            }
            boolean expected = false;
            for (int s = 0; s <= 3; s++) {
                boolean all = true;
                for (int block = 0; block < 2; block++) {
                    boolean found = false;
                    for (int x = 0; x <= 40 && !found; x++) for (int y = 0; y <= 40 && !found; y++) {
                        var values = low.clone();
                        values[0] = BigInteger.valueOf(s);
                        values[1 + block * 2] = BigInteger.valueOf(x);
                        values[2 + block * 2] = BigInteger.valueOf(y);
                        found = valid(rows.subList(block * 3, block * 3 + 3), low, high, values);
                    }
                    all &= found;
                }
                expected |= all;
            }
            for (int order = 0; order < 3; order++) {
                Collections.shuffle(rows, random);
                var budget = budget(4_000_000);
                try (var search = new CountSeparator(rows, low, high, budget)) {
                    while (!search.step()) {}
                    var values = search.counts();
                    check((values != null) == expected, "Wide pair oracle mismatch: " + sample + " " + budget.diagnostics());
                    if (values != null) check(valid(rows, low, high, values), "Wide pair violated original rows");
                }
                check(budget.reservedBytes() == 0, "Wide pair leaked");
            }
        }
    }

    private static void limits() {
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        equation(rows, Map.of(0, ONE, 1, BigInteger.TWO), 101);
        equation(rows, Map.of(0, ONE, 2, BigInteger.valueOf(3)), 102);
        BigInteger[] low = {ZERO, ZERO, ZERO}, high = {BigInteger.valueOf(3), BigInteger.valueOf(1000), null};
        for (long work : new long[]{1, 1024, 4096, 8192, 16384}) {
            var budget = budget(work);
            try (var search = new CountSeparator(rows, low, high, budget)) {
                while (!search.step()) {}
                check(!search.infeasible(), "Quota decline became a proof");
            } catch (PlanningBudget.Exhausted limit) {
                check(limit.limit() == PlanningBudget.Limit.SEARCH_LIMIT, "Unexpected quota");
            }
            check(budget.reservedBytes() == 0, "Quota leaked");
        }
        var budget = new PlanningBudget(0, 1_000_000, 512, () -> false, System::nanoTime);
        try (var search = new CountSeparator(rows, low, high, budget)) {
            check(search.step() && !search.infeasible() && search.counts() == null, "Memory admission became UNSAT");
        }
        check(budget.reservedBytes() == 0, "Memory decline leaked");
    }

    private static boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high, BigInteger[] values) {
        for (int i = 0; i < low.length; i++) if (values[i].compareTo(low[i]) < 0 || high[i] != null && values[i].compareTo(high[i]) > 0) return false;
        for (var row : rows) {
            BigInteger sum = ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(values[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
