// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exhaustive original-domain oracles, not the LP's floating status. */
public final class CountPackingCoreTest {
    private record Fixture(CountModelViews.View view, boolean feasible) {}

    public static void main(String[] args) throws Exception {
        int checked = 0;
        var worker = Executors.newSingleThreadExecutor();
        try {
            for (int seed = 0; seed < 64; seed++) {
                for (boolean impossible : new boolean[] {false, true}) {
                    var fixture = fixture(seed, impossible, seed % 4 == 0 ? 1L << 30 : 1);
                    var whole = solve(fixture.view(), Long.MAX_VALUE, null);
                    check((whole != null) == fixture.feasible(), "Contradicted exhaustive oracle seed " + seed);
                    if (whole != null) check(valid(fixture.view(), whole), "Invalid material witness");
                    if (seed < 3) {
                        var sliced = solve(fixture.view(), 1, worker);
                        check(Arrays.equals(whole, sliced), "Worker handoff changed the retained frontier");
                    }
                    var permuted = permute(fixture.view(), seed);
                    var other = solve(permuted, 17, null);
                    check((other != null) == fixture.feasible(), "Row/coordinate order changed exhaustive result");
                    if (other != null) check(valid(permuted, other), "Bad restored permutation");
                    checked += 2;
                }
            }
        } finally { worker.shutdownNow(); }
        lifecycle();
        System.out.println("Packing cores: " + checked + " exhaustive SAT/UNSAT controls, exact scaled rows, fixed-one substitution, permutations and worker handoffs passed");
    }

    private static Fixture fixture(int seed, boolean impossible, long scale) {
        var random = new Random(seed + 9031L);
        int active = 10 + seed % 5, dimensions = 2 + seed % 5, n = 64;
        var low = new BigInteger[n]; var high = new BigInteger[n];
        Arrays.fill(low, BigInteger.ZERO); Arrays.fill(high, BigInteger.ZERO);
        for (int i = 0; i < active; i++) high[i] = BigInteger.ONE;
        low[n - 1] = high[n - 1] = BigInteger.ONE;
        long[][] weights = new long[dimensions][active]; long[] caps = new long[dimensions], profit = new long[active];
        for (int i = 0; i < active; i++) profit[i] = 1 + random.nextInt(30);
        for (int d = 0; d < dimensions; d++) {
            for (int i = 0; i < active; i++) { weights[d][i] = 1 + random.nextInt(30); caps[d] += weights[d][i]; }
            caps[d] /= 2;
        }
        long best = 0;
        for (int mask = 0; mask < 1 << active; mask++) {
            boolean fits = true; long value = 0;
            for (int d = 0; d < dimensions; d++) {
                long sum = 0;
                for (int i = 0; i < active; i++) if ((mask & 1 << i) != 0) sum += weights[d][i];
                fits &= sum <= caps[d];
            }
            if (fits) {
                for (int i = 0; i < active; i++) if ((mask & 1 << i) != 0) value += profit[i];
                best = Math.max(best, value);
            }
        }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int d = 0; d < dimensions; d++) {
            var terms = new TreeMap<Integer, BigInteger>();
            for (int i = 0; i < active; i++) terms.put(i, BigInteger.valueOf(weights[d][i] * scale));
            terms.put(n - 1, BigInteger.valueOf(37 * scale));
            rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf((caps[d] + 37) * scale)));
        }
        var demand = new TreeMap<Integer, BigInteger>();
        for (int i = 0; i < active; i++) demand.put(i, BigInteger.valueOf(-profit[i] * scale));
        demand.put(n - 1, BigInteger.valueOf(-19 * scale));
        rows.add(new ExactLinearProgram.Constraint(demand, BigInteger.valueOf(-(best + (impossible ? 1 : 0) + 19) * scale)));
        return new Fixture(view(rows, low, high), !impossible);
    }

    private static CountModelViews.View view(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {
        long terms = rows.stream().mapToLong(r -> r.terms().size()).sum();
        return new CountModelViews.View("packing-test", rows, low, high,
                new CountModelViews.Shape(low.length, terms, 64, 64), null, CountModelViews.Semantics.EQUIVALENT, null);
    }

    private static CountModelViews.View permute(CountModelViews.View source, int seed) {
        var random = new Random(seed); var ids = new ArrayList<Integer>();
        for (int i = 0; i < source.lower().length; i++) ids.add(i);
        Collections.shuffle(ids, random);
        var low = new BigInteger[ids.size()]; var high = new BigInteger[ids.size()];
        for (int i = 0; i < ids.size(); i++) { low[ids.get(i)] = source.lower()[i]; high[ids.get(i)] = source.upper()[i]; }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (var row : source.rows()) {
            var terms = new LinkedHashMap<Integer, BigInteger>();
            for (var term : row.terms().entrySet()) terms.put(ids.get(term.getKey()), term.getValue());
            rows.add(new ExactLinearProgram.Constraint(terms, row.upper()));
        }
        Collections.shuffle(rows, random);
        return view(rows, low, high);
    }

    private static BigInteger[] solve(CountModelViews.View view, long quantum, ExecutorService worker) throws Exception {
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, () -> false, System::nanoTime);
        BigInteger[] result;
        try (var search = CountPackingCore.create(view, new CountModelViews.Domains(view.lower(), view.upper(), 0), budget, quantum)) {
            check(search != null, "Packing scout not admitted");
            int turns = 0;
            for (;;) {
                check(++turns < 500000, "No-progress continuation loop");
                boolean done = worker == null ? search.step() : worker.submit(search::step).get();
                if (!done) continue;
                if (!search.paused()) break;
                search.resume(quantum);
            }
            result = search.counts();
        }
        check(budget.reservedBytes() == 0, "Packing workspace leaked");
        return result;
    }

    private static void lifecycle() {
        var view = fixture(1, false, 1).view();
        var cancelled = new AtomicBoolean();
        var budget = new PlanningBudget(0, Integer.MAX_VALUE, 64L << 20, cancelled::get, System::nanoTime);
        try (var search = CountPackingCore.create(view, new CountModelViews.Domains(view.lower(), view.upper(), 0), budget, Long.MAX_VALUE)) {
            check(search.matches(view.rows(), view.lower(), view.upper()), "Lost scope");
            var changed = view.upper().clone(); changed[0] = BigInteger.ZERO;
            check(!search.matches(view.rows(), view.lower(), changed), "Reused stale bounds");
            var changedRows = new ArrayList<>(view.rows());
            var row = changedRows.get(0); changedRows.set(0, new ExactLinearProgram.Constraint(row.terms(), row.upper().add(BigInteger.ONE)));
            check(!search.matches(changedRows, view.lower(), view.upper()), "Reused stale inventory");
            search.step(); cancelled.set(true);
            try { search.step(); throw new AssertionError("Cancellation ignored"); }
            catch (CancellationException expected) {}
        }
        check(budget.reservedBytes() == 0, "Cancelled workspace leaked");
        var small = new PlanningBudget(0, 40_000_000, 1, () -> false, System::nanoTime);
        check(CountPackingCore.create(view, new CountModelViews.Domains(view.lower(), view.upper(), 0), small, 1) == null,
                "Memory refusal ignored");
        check(small.reservedBytes() == 0, "Declined reservation leaked");
        var drained = new PlanningBudget(0, 40_000_000, 64L << 20, () -> false, System::nanoTime);
        try (var search = CountPackingCore.create(view, new CountModelViews.Domains(view.lower(), view.upper(), 0), drained, Long.MAX_VALUE)) {
            search.step();
            drained.charge(drained.remainingWork() - 1); // Another worker consumes the shared remainder.
            try {
                for (int turn = 0; ; turn++) {
                    check(turn < 100, "Shared-budget tail spin");
                    if (search.step()) break;
                }
            } catch (PlanningBudget.Exhausted expected) {}
        }
        check(drained.reservedBytes() == 0, "Shared-budget exhaustion leaked");
        for (long work : new long[] {1, 524287, 524288, 524289, Integer.MAX_VALUE, Long.MAX_VALUE}) {
            var b = new PlanningBudget(0, work, 64L << 20, () -> false, System::nanoTime);
            try (var search = CountPackingCore.create(view, new CountModelViews.Domains(view.lower(), view.upper(), 0), b, Long.MAX_VALUE)) {
                if (search == null) continue;
                for (int turn = 0; ; turn++) {
                    check(turn < 500000, "Budget-tail spin");
                    if (search.step()) break;
                }
            } catch (PlanningBudget.Exhausted expected) {}
            check(b.reservedBytes() == 0, "Limit cleanup leaked");
        }
        var rows = new ArrayList<>(view.rows());
        rows.add(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE, 1, BigInteger.ONE.negate()), BigInteger.ZERO));
        var mixed = view(rows, view.lower(), view.upper());
        var b = new PlanningBudget(0, 40_000_000, 64L << 20, () -> false, System::nanoTime);
        check(CountPackingCore.create(mixed, new CountModelViews.Domains(mixed.lower(), mixed.upper(), 0), b, 1) == null,
                "Admitted unmodeled mixed-sign constraint");
        check(b.reservedBytes() == 0, "Unsupported scope leaked");
    }

    private static boolean valid(CountModelViews.View view, BigInteger[] values) {
        for (int i = 0; i < values.length; i++) if (values[i].compareTo(view.lower()[i]) < 0 || values[i].compareTo(view.upper()[i]) > 0) return false;
        for (var row : view.rows()) {
            var sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(values[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
