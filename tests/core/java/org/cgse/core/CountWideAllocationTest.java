// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CountWideAllocationTest {
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {}

    public static void main(String[] args) throws Exception {
        int witnesses = 0;
        var worker = Executors.newSingleThreadExecutor();
        try {
            for (int seed = 0; seed < 12; seed++) {
                Model m = model(seed);
                var whole = solve(m, Long.MAX_VALUE, null);
                var sliced = solve(m, 1, worker);
                check(Arrays.equals(whole, sliced), "Changed after worker handoff");
                if (whole != null) { check(valid(m, whole), "Invalid wide allocation"); witnesses++; }
                var rows = new ArrayList<>(m.rows); Collections.shuffle(rows, new Random(seed));
                var reordered = new Model(rows, m.low, m.high);
                var alternate = solve(reordered, 13, null);
                check(alternate == null || valid(reordered, alternate), "Invalid reordered allocation");
                rows.add(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE), m.low[0].subtract(BigInteger.ONE)));
                check(solve(new Model(rows, m.low, m.high), 71, null) == null, "Ignored extra material row");
            }
        } finally { worker.shutdownNow(); }
        check(witnesses >= 8, "Wide witness family not reached: " + witnesses);
        Model m = model(4);
        var cancel = new AtomicBoolean();
        var budget = new PlanningBudget(0, 8_000_000, 64L << 20, cancel::get, System::nanoTime);
        try (var search = CountGroupedPartition.createWide(m.rows, m.low, m.high, budget, 1)) {
            check(search != null && search.matches(m.rows, m.low, m.high), "Missing owned scope");
            var changed = m.high.clone(); changed[0] = changed[0].add(BigInteger.ONE);
            check(!search.matches(m.rows, m.low, changed), "Reused changed stock");
            search.step(); cancel.set(true);
            try { search.step(); throw new AssertionError("Ignored cancellation"); }
            catch (CancellationException expected) {}
        }
        check(budget.reservedBytes() == 0, "Cancellation retained allocation");
        var small = new PlanningBudget(0, 8_000_000, 1, () -> false, System::nanoTime);
        check(CountGroupedPartition.createWide(m.rows, m.low, m.high, small, 1) == null && small.reservedBytes() == 0,
                "Memory refusal retained allocation");
        System.out.println("Wide grouped allocation: " + witnesses + " constructive integer oracles; orders, handoffs, offsets, extra rows and limits passed");
    }

    private static BigInteger[] solve(Model m, long quantum, ExecutorService worker) throws Exception {
        var budget = new PlanningBudget(0, 8_000_000, 64L << 20, () -> false, System::nanoTime);
        BigInteger[] result;
        try (var search = CountGroupedPartition.createWide(m.rows, m.low, m.high, budget, quantum)) {
            if (search == null) result = null;
            else {
                for (;;) {
                    boolean done = worker == null ? search.step() : worker.submit(search::step).get(30, TimeUnit.SECONDS);
                    if (!done) continue;
                    if (!search.paused()) break;
                    search.resume(quantum);
                }
                result = search.counts();
            }
        }
        check(budget.reservedBytes() == 0, "Retained wide workspace");
        return result;
    }

    private static Model model(int seed) {
        int groups = 12, bins = 3, n = groups * bins;
        var random = new Random(20261013L + seed);
        BigInteger capacity = seed % 2 == 0 ? BigInteger.valueOf(1_000_003) : BigInteger.TEN.pow(25).add(BigInteger.valueOf(97));
        BigInteger offset = seed % 3 == 0 ? BigInteger.ONE.shiftLeft(100).negate() : BigInteger.ZERO;
        var low = new BigInteger[n]; var high = new BigInteger[n]; var oracle = new BigInteger[n];
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        var weights = new BigInteger[groups];
        for (int g = 0; g < groups; g++) {
            weights[g] = BigInteger.valueOf(1001 + random.nextInt(8000));
            var terms = new TreeMap<Integer, BigInteger>();
            BigInteger a = capacity.multiply(BigInteger.valueOf(10 + random.nextInt(30))).divide(BigInteger.valueOf(100));
            BigInteger b = capacity.multiply(BigInteger.valueOf(10 + random.nextInt(30))).divide(BigInteger.valueOf(100));
            for (int k = 0; k < bins; k++) {
                int id = bins * g + k;
                low[id] = offset; high[id] = offset.add(capacity); terms.put(id, BigInteger.ONE);
                oracle[id] = offset.add(k == 0 ? a : k == 1 ? b : capacity.subtract(a).subtract(b));
            }
            equality(rows, terms, capacity.add(offset.multiply(BigInteger.valueOf(bins))));
        }
        for (int k = 0; k < bins; k++) {
            var terms = new TreeMap<Integer, BigInteger>(); var goal = BigInteger.ZERO;
            for (int g = 0; g < groups; g++) {
                int id = bins * g + k; terms.put(id, weights[g]); goal = goal.add(weights[g].multiply(oracle[id]));
            }
            equality(rows, terms, goal);
        }
        Model model = new Model(rows, low, high);
        check(valid(model, oracle), "Invalid constructive oracle");
        return model;
    }

    private static void equality(List<ExactLinearProgram.Constraint> rows, Map<Integer, BigInteger> terms, BigInteger rhs) {
        rows.add(new ExactLinearProgram.Constraint(terms, rhs));
        var reverse = new TreeMap<Integer, BigInteger>(); terms.forEach((i, a) -> reverse.put(i, a.negate()));
        rows.add(new ExactLinearProgram.Constraint(reverse, rhs.negate()));
    }
    private static boolean valid(Model m, BigInteger[] p) {
        for (int i = 0; i < p.length; i++) if (p[i].compareTo(m.low[i]) < 0 || p[i].compareTo(m.high[i]) > 0) return false;
        for (var row : m.rows) {
            var sum = BigInteger.ZERO;
            for (var t : row.terms().entrySet()) sum = sum.add(t.getValue().multiply(p[t.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
