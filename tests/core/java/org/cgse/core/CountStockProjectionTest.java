// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Original integer inequalities remain the oracle when stock pivots reduce a wide proposal. */
public final class CountStockProjectionTest {
    private static final int LENGTH = 1100;
    private static final long ALLOWANCE = 4_000_000, MEMORY = 64L << 20;
    private static int checks;

    public static void main(String[] args) {
        fundedChain();
        stockConsumption();
        exactCapacities();
        coproductAndSources();
        numericalStockRelaxation();
        optionalFailure();
        limits();
        cancellationAndRetry();
        System.out.println("CountStockProjectionTest PASS: checks=" + checks);
    }

    private static void fundedChain() {
        for (int order = 0; order < 3; order++) {
            var original = chain(BigInteger.ONE, BigInteger.valueOf(4), BigInteger.ONE);
            var known = filled(LENGTH + 1, BigInteger.ONE);
            var model = reordered(original, known, order);
            check(valid(model.rows, model.known), "Funded-chain oracle is invalid");
            var counts = solve(model.known.length, model.rows, ALLOWANCE, MEMORY);
            check(counts != null && valid(model.rows, counts),
                    "Stock-funded single-output chain lost its wide candidate; order=" + order);
        }
    }

    private static void stockConsumption() {
        // Keeping every intermediate stock unchanged would require one raw
        // input. No raw exists, but one stored final intermediate funds the
        // requested output. Optional neutral equalities must be releasable.
        for (int order = 0; order < 3; order++) {
            var original = chain(BigInteger.ONE, BigInteger.valueOf(4), BigInteger.ZERO);
            var known = filled(LENGTH + 1, BigInteger.ZERO); known[0] = BigInteger.ONE;
            var model = reordered(original, known, order);
            check(valid(model.rows, model.known), "Stored-input oracle is invalid");
            var counts = solve(model.known.length, model.rows, ALLOWANCE, MEMORY);
            check(counts != null && valid(model.rows, counts),
                    "Optional stock retention excluded a funded original model; order=" + order);
        }
    }

    private static void exactCapacities() {
        BigInteger amount = BigInteger.ONE.shiftLeft(90).add(BigInteger.valueOf(37));
        var rows = chain(amount, amount, amount);
        var known = filled(LENGTH + 1, amount);
        check(valid(rows, known), "Exact-capacity oracle is invalid");
        var counts = solve(known.length, rows, ALLOWANCE, MEMORY);
        check(counts != null && valid(rows, counts), "Stock projection rounded a capacity beyond long");
        check(counts[0].compareTo(amount) >= 0 && counts[LENGTH].compareTo(amount) <= 0,
                "Exact demand or raw capacity changed during restoration");

        // A one-unit raw deficit cannot invalidate the original stocked
        // model. The optional retained-stock proposal needs to give way.
        var stocked = chain(BigInteger.valueOf(7), BigInteger.valueOf(7), BigInteger.valueOf(6));
        var stockedKnown = filled(LENGTH + 1, BigInteger.ZERO); stockedKnown[0] = BigInteger.valueOf(7);
        check(valid(stocked, stockedKnown), "One-unit stock boundary oracle is invalid");
        var consumed = solve(stockedKnown.length, stocked, ALLOWANCE, MEMORY);
        check(consumed != null && valid(stocked, consumed), "A tight raw boundary suppressed usable stock");
    }

    private static void coproductAndSources() {
        int first = LENGTH + 1, second = first + 1;
        var original = new ArrayList<>(chain(BigInteger.ONE, BigInteger.valueOf(4), BigInteger.ONE));
        // Both sources are mandatory at these capacities; each has two
        // outputs. Treating either output row as a single-output equality
        // would lose the other obligation or duplicate its source capacity.
        original.add(row(Map.of(first, -2, second, -3), -5));
        original.add(row(Map.of(first, -1, second, -2), -3));
        original.add(row(Map.of(first, 1), 1));
        original.add(row(Map.of(second, 1), 1));
        for (int order = 0; order < 3; order++) {
            var model = reordered(original, filled(second + 1, BigInteger.ONE), order);
            check(valid(model.rows, model.known), "Coproduct/source oracle is invalid");
            var counts = solve(model.known.length, model.rows, ALLOWANCE, MEMORY);
            check(counts != null && valid(model.rows, counts),
                    "Stock pivots lost an independent coproduct/source obligation; order=" + order);
        }
    }

    private static void numericalStockRelaxation() {
        int retained = LENGTH + 1, first = retained + 1, second = first + 1;
        var original = new ArrayList<>(chain(BigInteger.TWO, BigInteger.valueOf(4), BigInteger.TWO));
        original.add(row(Map.of(0, 1, retained, -1), 4));
        original.add(row(Map.of(0, 1, first, -1, second, -1), 4));
        original.add(row(Map.of(retained, 1, first, 1, second, 1), 1));
        var known = filled(second + 1, BigInteger.TWO);
        known[retained] = known[first] = known[second] = BigInteger.ZERO;
        // The identical a/b columns may merge before optional pivots are
        // selected. An initial constant contradiction can release p, but
        // retaining the combined a/b stock still leaves p<=-1. That nonempty
        // projected row requires numerical relaxation of another assumption;
        // p=a=b=0 meets every original exact inequality using stock.
        for (int order = 0; order < 3; order++) {
            var model = reordered(original, known, order);
            check(valid(model.rows, model.known), "Numerical stock-relaxation oracle is invalid");
            var budget = budget(ALLOWANCE, MEMORY, new AtomicBoolean());
            try (var projection = new CountDemandProjection(model.known.length, model.rows, budget, ALLOWANCE, 512, true)) {
                while (!projection.step()) {}
                check(projection.result() != null && valid(model.rows, projection.result()),
                        "Numerical optional-stock contradiction lost the original witness; order=" + order);
                String diagnostics = budget.diagnostics();
                int compiled = diagnostics.indexOf("compiled; variables=");
                int released = compiled < 0 ? -1 : diagnostics.indexOf("count_demand_stock_release", compiled);
                // Earlier constant releases are allowed. At least one later
                // release must follow a completed projected numerical model;
                // disabling numerical relaxation loses this fixture's witness.
                check(compiled >= 0 && released > compiled,
                        "Fixture did not exercise numerical stock-assumption relaxation; order=" + order);
            }
            check(budget.reservedBytes() == 0 && budget.peakBytes() <= MEMORY,
                    "Numerical stock relaxation retained workspace");
        }
    }

    private static void optionalFailure() {
        var rows = new ArrayList<>(chain(BigInteger.ONE, BigInteger.valueOf(4), BigInteger.ONE));
        // This is an independently contradictory pair. A failed proposal
        // returns no witness; it may not turn its assumptions into a proof.
        rows.add(row(Map.of(0, 1), 0));
        var budget = budget(ALLOWANCE, MEMORY, new AtomicBoolean());
        budget.failureDetail("UNCHANGED_ORIGINAL_MODEL");
        try (var projection = new CountDemandProjection(LENGTH + 1, rows, budget, ALLOWANCE, 512, true)) {
            while (!projection.step()) {}
            check(projection.result() == null, "Contradictory original rows acquired a witness");
            check(budget.failureDetail().equals("UNCHANGED_ORIGINAL_MODEL"),
                    "Optional projection published a new infeasibility conclusion");
        }
        check(budget.reservedBytes() == 0, "Failed optional proposal retained memory");
    }

    private static void limits() {
        var rows = chain(BigInteger.ONE, BigInteger.valueOf(4), BigInteger.ZERO);
        for (long cap : new long[]{20, 300, 8000}) {
            var budget = budget(ALLOWANCE, MEMORY, new AtomicBoolean());
            try (var projection = new CountDemandProjection(LENGTH + 1, rows, budget, cap, 512, true)) {
                while (!projection.step()) {}
                check(projection.result() == null || valid(rows, projection.result()),
                        "A bounded stock proposal fabricated counts");
                check(projection.work() <= cap + 1, "Stock repivot replenished the local work allowance");
            }
            check(budget.reservedBytes() == 0, "Work-limited stock proposal retained memory");
        }
        for (long bytes : new long[]{1, 4096, 1L << 20, 4L << 20}) {
            var budget = budget(ALLOWANCE, bytes, new AtomicBoolean());
            try (var projection = new CountDemandProjection(LENGTH + 1, rows, budget, ALLOWANCE, 512, true)) {
                while (!projection.step()) {}
                check(projection.result() == null || valid(rows, projection.result()),
                        "Memory refusal published invalid stock counts");
            }
            check(budget.reservedBytes() == 0 && budget.peakBytes() <= bytes,
                    "Stock proposal escaped its memory account");
        }
    }

    private static void cancellationAndRetry() {
        var rows = chain(BigInteger.ONE, BigInteger.valueOf(4), BigInteger.ONE);
        var original = List.copyOf(rows);
        for (boolean afterPivots : List.of(false, true)) {
            var stop = new AtomicBoolean(); var budget = budget(ALLOWANCE, MEMORY, stop);
            try (var projection = new CountDemandProjection(LENGTH + 1, rows, budget, ALLOWANCE, 512, true)) {
                check(!projection.step(), "Cancellation control ended before its first step");
                if (afterPivots) {
                    int steps = 0;
                    while (!budget.diagnostics().contains("count_demand_stock_projection")) {
                        check(++steps < 20_000 && !projection.step(), "Cancellation control never reached stock pivots");
                    }
                }
                stop.set(true);
                try { projection.step(); throw new AssertionError("Stock projection swallowed cancellation"); }
                catch (CancellationException expected) { checks++; }
                check(projection.result() == null, "Cancelled stock proposal published incomplete counts");
            }
            check(budget.reservedBytes() == 0, "Cancelled stock proposal retained memory");
            check(rows.equals(original), "Stock projection mutated caller-owned original rows");
            // Repeat on the same immutable inputs with fresh budgets. A
            // cancellation may not poison the following independent order.
            for (int repeat = 0; repeat < 2; repeat++) {
                var counts = solve(LENGTH + 1, rows, ALLOWANCE, MEMORY);
                check(counts != null && valid(rows, counts), "Cancelled order poisoned a subsequent stock proposal");
            }
        }
    }

    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] known) {}

    private static Model reordered(List<ExactLinearProgram.Constraint> original, BigInteger[] known, int order) {
        var columns = new ArrayList<Integer>();
        for (int i = 0; i < known.length; i++) columns.add(i);
        if (order == 1) Collections.reverse(columns);
        else if (order == 2) Collections.shuffle(columns, new Random(20261027));
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (var row : original) {
            var terms = new TreeMap<Integer, BigInteger>();
            row.terms().forEach((key, value) -> terms.put(columns.get(key), value));
            rows.add(new ExactLinearProgram.Constraint(terms, row.upper()));
        }
        if (order == 1) Collections.reverse(rows);
        else if (order == 2) Collections.shuffle(rows, new Random(20261028));
        var reordered = new BigInteger[known.length];
        for (int i = 0; i < known.length; i++) reordered[columns.get(i)] = known[i];
        return new Model(List.copyOf(rows), reordered);
    }

    private static List<ExactLinearProgram.Constraint> chain(BigInteger amount, BigInteger stock, BigInteger raw) {
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        rows.add(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE.negate()), amount.negate()));
        for (int i = 0; i < LENGTH; i++) rows.add(new ExactLinearProgram.Constraint(
                Map.of(i, BigInteger.ONE, i + 1, BigInteger.ONE.negate()), stock));
        rows.add(new ExactLinearProgram.Constraint(Map.of(LENGTH, BigInteger.ONE), raw));
        return List.copyOf(rows);
    }

    private static BigInteger[] solve(int variables, List<ExactLinearProgram.Constraint> rows, long work, long bytes) {
        var before = List.copyOf(rows);
        var budget = budget(ALLOWANCE, bytes, new AtomicBoolean());
        BigInteger[] result;
        try (var projection = new CountDemandProjection(variables, rows, budget, work, 512, true)) {
            while (!projection.step()) {}
            result = projection.result();
        }
        check(budget.reservedBytes() == 0 && budget.peakBytes() <= bytes, "Stock projection retained unaccounted workspace");
        check(rows.equals(before), "Projection modified original inequalities");
        return result;
    }

    private static PlanningBudget budget(long work, long bytes, AtomicBoolean stop) {
        return new PlanningBudget(0, Math.max(work * 2, 8_000_000), bytes, stop::get, System::nanoTime);
    }

    private static BigInteger[] filled(int length, BigInteger value) {
        var result = new BigInteger[length]; Arrays.fill(result, value); return result;
    }

    private static ExactLinearProgram.Constraint row(Map<Integer, Integer> terms, long upper) {
        var values = new TreeMap<Integer, BigInteger>();
        terms.forEach((key, value) -> values.put(key, BigInteger.valueOf(value)));
        return new ExactLinearProgram.Constraint(values, BigInteger.valueOf(upper));
    }

    private static boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] values) {
        for (var value : values) if (value == null || value.signum() < 0) return false;
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(values[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private static void check(boolean condition, String detail) {
        checks++; if (!condition) throw new AssertionError(detail);
    }
}
