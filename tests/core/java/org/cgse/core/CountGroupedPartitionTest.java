// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CountGroupedPartitionTest {
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {}
    public static void main(String[] args) throws Exception {
        int solved = 0, unknown = 0;
        var workers = Executors.newSingleThreadExecutor();
        try {
            for (int seed = 0; seed < 96; seed++) {
                Model model = model(seed, 3 + seed % 3, 2 + seed % 2);
                boolean exists = enumerate(model, model.low.clone(), 0);
                BigInteger[] whole = run(model, 1_000_000, null);
                BigInteger[] sliced = run(model, 1, workers);
                check(Arrays.equals(whole, sliced), "Handoff changed result at " + seed);
                if (whole != null) { check(exists && valid(model, whole), "False witness at " + seed); solved++; }
                else unknown++;
                var changed = new ArrayList<>(model.rows);
                Collections.shuffle(changed, new Random(79L * seed));
                var reordered = new Model(changed, model.low, model.high);
                var alternate = run(reordered, 113, null);
                check(alternate == null || exists && valid(model, alternate), "Row order admitted invalid allocation");
            }
        } finally { workers.shutdownNow(); }
        check(solved > 60, "Candidate path did not solve the intended family: " + solved);
        limits();
        wideAndScope();
        binOrdering();
        portfolio();
        System.out.println("Grouped partition: 96 exhaustive oracles, three order/slice controls each; solved=" + solved +
                "; unresolved=" + unknown + "; wide coordinates, cancellation, memory and scope guards passed");
    }

    private static BigInteger[] run(Model model, long quantum, ExecutorService worker) throws Exception {
        return run(model, quantum, worker, 8_000_000);
    }

    private static BigInteger[] run(Model model, long quantum, ExecutorService worker, long work) throws Exception {
        var budget = new PlanningBudget(0, work, 64L << 20, () -> false, System::nanoTime);
        BigInteger[] result;
        try (var search = CountGroupedPartition.create(model.rows, model.low, model.high, budget, quantum)) {
            check(search != null, "Expected supported allocation");
            while (true) {
                boolean done = worker == null ? search.step() : worker.submit(search::step).get(30, TimeUnit.SECONDS);
                if (!done) continue;
                if (!search.paused()) break;
                search.resume(quantum);
            }
            result = search.counts();
        }
        check(budget.reservedBytes() == 0, "Retained workspace");
        return result;
    }

    private static Model model(int seed, int groups, int bins) {
        return model(seed, groups, bins, 17);
    }

    private static Model model(int seed, int groups, int bins, int range) {
        var random = new Random(7319L + seed);
        int n = groups * bins;
        var low = new BigInteger[n]; var high = new BigInteger[n];
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        int[] weights = new int[groups], goals = new int[bins];
        for (int g = 0; g < groups; g++) {
            int capacity = 1 + random.nextInt(2);
            weights[g] = 2 + random.nextInt(range);
            for (int k = 0; k < capacity; k++) goals[random.nextInt(bins)] += weights[g];
            var terms = new TreeMap<Integer, BigInteger>();
            var offset = BigInteger.ZERO;
            for (int b = 0; b < bins; b++) {
                int id = g * bins + b;
                low[id] = BigInteger.valueOf(seed % 3 - 1); high[id] = low[id].add(BigInteger.valueOf(capacity));
                terms.put(id, BigInteger.ONE); offset = offset.add(low[id]);
            }
            equal(rows, terms, offset.add(BigInteger.valueOf(capacity)));
        }
        for (int b = 0; b < bins; b++) {
            var terms = new TreeMap<Integer, BigInteger>();
            var rhs = BigInteger.valueOf(goals[b]);
            for (int g = 0; g < groups; g++) {
                int id = g * bins + b;
                var weight = BigInteger.valueOf(weights[g]);
                terms.put(id, weight); rhs = rhs.add(weight.multiply(low[id]));
            }
            equal(rows, terms, rhs);
        }
        return new Model(rows, low, high);
    }

    private static boolean enumerate(Model model, BigInteger[] point, int at) {
        // Independent original-row interval pruning followed by complete finite enumeration.
        for (var row : model.rows) {
            var least = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                int id = term.getKey(); var a = term.getValue();
                least = least.add(a.multiply(id < at ? point[id] : a.signum() > 0 ? model.low[id] : model.high[id]));
            }
            if (least.compareTo(row.upper()) > 0) return false;
        }
        if (at == point.length) return true;
        for (var x = model.low[at]; x.compareTo(model.high[at]) <= 0; x = x.add(BigInteger.ONE)) {
            point[at] = x;
            if (enumerate(model, point, at + 1)) return true;
        }
        return false;
    }

    private static boolean valid(Model model, BigInteger[] point) {
        for (int i = 0; i < point.length; i++) if (point[i].compareTo(model.low[i]) < 0 || point[i].compareTo(model.high[i]) > 0) return false;
        for (var row : model.rows) {
            var sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(point[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private static void limits() {
        Model model = model(2, 8, 3);
        var cancelled = new AtomicBoolean();
        var budget = new PlanningBudget(0, 8_000_000, 64L << 20, cancelled::get, System::nanoTime);
        try (var search = CountGroupedPartition.create(model.rows, model.low, model.high, budget, 1)) {
            check(search != null, "Missing cancellation frontier");
            while (!search.step()) {}
            check(search.paused() && search.counts() == null, "Pause is not completion");
            search.resume(Long.MAX_VALUE);
            cancelled.set(true);
            try { search.step(); throw new AssertionError("Cancellation ignored"); }
            catch (CancellationException expected) {}
        }
        check(budget.reservedBytes() == 0, "Cancellation leak");
        var noMemory = new PlanningBudget(0, 8_000_000, 1, () -> false, System::nanoTime);
        check(CountGroupedPartition.create(model.rows, model.low, model.high, noMemory, 1000) == null, "Ignored memory budget");
        var lowWork = new PlanningBudget(0, 200, 64L << 20, () -> false, System::nanoTime);
        check(CountGroupedPartition.create(model.rows, model.low, model.high, lowWork, 1000) == null, "Low budget admission");
        check(noMemory.reservedBytes() == 0 && lowWork.reservedBytes() == 0, "Refusal leak");
        Model scalar = model(19, 12, 2);
        var scalarBudget = new PlanningBudget(0, 8_000_000, 64L << 20, () -> false, System::nanoTime);
        check(CountGroupedPartition.createMultiway(scalar.rows, scalar.low, scalar.high, scalarBudget, 1000) == null,
                "Coupled portfolio displaced an existing scalar partition path");
        check(scalarBudget.reservedBytes() == 0, "Scalar admission leak");
    }

    private static void wideAndScope() throws Exception {
        Model original = model(7, 6, 3);
        var scale = BigInteger.ONE.shiftLeft(160).add(BigInteger.valueOf(7));
        var origin = BigInteger.ONE.shiftLeft(120).negate();
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (var row : original.rows) {
            var terms = new TreeMap<Integer, BigInteger>();
            var rhs = row.upper();
            for (var term : row.terms().entrySet()) {
                terms.put(term.getKey(), term.getValue().multiply(scale));
                rhs = rhs.add(term.getValue().multiply(origin));
            }
            rows.add(new ExactLinearProgram.Constraint(terms, rhs.multiply(scale)));
        }
        var lo = Arrays.stream(original.low).map(x -> x.add(origin)).toArray(BigInteger[]::new);
        var hi = Arrays.stream(original.high).map(x -> x.add(origin)).toArray(BigInteger[]::new);
        Model wide = new Model(rows, lo, hi);
        var candidate = run(wide, 17, null);
        check(candidate != null && valid(wide, candidate), "Wide offset / gcd failed");
        var budget = new PlanningBudget(0, 8_000_000, 64L << 20, () -> false, System::nanoTime);
        try (var search = CountGroupedPartition.create(rows, lo, hi, budget, 100)) {
            check(search != null && search.matches(rows, lo, hi), "Owned scope");
            var changed = lo.clone(); changed[0] = changed[0].add(BigInteger.ONE);
            check(!search.matches(rows, changed, hi), "Changed stock/domain reused");
            var extra = new ArrayList<>(rows); extra.add(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE), lo[0]));
            check(!search.matches(extra, lo, hi), "Changed row scope reused");
            lo[0] = BigInteger.ZERO;
            check(!search.matches(rows, lo, hi), "Caller owns domain snapshot");
        }
        check(budget.reservedBytes() == 0, "Scope workspace leak");
        var impossible = new ArrayList<>(original.rows);
        impossible.add(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE), original.low[0].subtract(BigInteger.ONE)));
        check(run(new Model(impossible, original.low, original.high), 31, null) == null, "Ignored an extra original row");
    }

    private static void binOrdering() throws Exception {
        // Public canonical manual-allocation fixture: rotating both bins and
        // item order missed this independently checked allocation on every retry.
        int[] weights = {55648, 92901, 77303, 14856, 20102, 55372, 52436, 21648,
                27447, 71693, 80278, 89751, 52488, 82915, 54237, 97524};
        int[] capacities = {1, 3, 3, 1, 1, 2, 2, 2, 1, 3, 3, 3, 3, 3, 3, 1};
        int[] goals = {572515, 551582, 1155090};
        int[] oracle = {0, 1, 0, 1, 0, 2, 1, 1, 1, 0, 0, 1, 1, 0, 0, 0, 1, 1, 1, 0, 1, 0, 1, 1,
                0, 0, 1, 1, 1, 1, 2, 1, 0, 0, 0, 3, 0, 1, 2, 0, 1, 2, 0, 1, 2, 1, 0, 0};
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        var low = new BigInteger[oracle.length]; var high = new BigInteger[oracle.length];
        Arrays.fill(low, BigInteger.ZERO);
        for (int g = 0; g < weights.length; g++) {
            var terms = new TreeMap<Integer, BigInteger>();
            for (int b = 0; b < goals.length; b++) {
                int id = g * goals.length + b;
                high[id] = BigInteger.valueOf(capacities[g]); terms.put(id, BigInteger.ONE);
            }
            equal(rows, terms, BigInteger.valueOf(capacities[g]));
        }
        for (int b = 0; b < goals.length; b++) {
            var terms = new TreeMap<Integer, BigInteger>();
            for (int g = 0; g < weights.length; g++) terms.put(g * goals.length + b, BigInteger.valueOf(weights[g]));
            equal(rows, terms, BigInteger.valueOf(goals[b]));
        }
        var model = new Model(rows, low, high);
        check(valid(model, Arrays.stream(oracle).mapToObj(BigInteger::valueOf).toArray(BigInteger[]::new)), "Invalid independent oracle");
        var whole = run(model, 4_000_000, null, 40_000_000);
        var sliced = run(model, 113, null, 40_000_000);
        check(whole != null && valid(model, whole) && Arrays.equals(whole, sliced), "Bin-order recovery lost the feasible allocation");
        for (int seed = 0; seed < 17; seed++) {
            var columns = new ArrayList<Integer>();
            for (int i = 0; i < low.length; i++) columns.add(i);
            var random = new Random(seed);
            Collections.shuffle(columns, random);
            var shiftedLow = new BigInteger[low.length]; var shiftedHigh = new BigInteger[low.length];
            for (int i = 0; i < low.length; i++) { shiftedLow[columns.get(i)] = low[i]; shiftedHigh[columns.get(i)] = high[i]; }
            var shiftedRows = new ArrayList<ExactLinearProgram.Constraint>();
            for (var row : rows) {
                var terms = new TreeMap<Integer, BigInteger>();
                row.terms().forEach((i, a) -> terms.put(columns.get(i), a));
                shiftedRows.add(new ExactLinearProgram.Constraint(terms, row.upper()));
            }
            Collections.shuffle(shiftedRows, random);
            var shifted = new Model(shiftedRows, shiftedLow, shiftedHigh);
            var result = run(shifted, 8192, null, 40_000_000);
            check(result != null && valid(shifted, result), "Column/row permutation lost recovery at " + seed);
            for (int i = 0; i < low.length; i++) check(result[columns.get(i)].equals(whole[i]), "Unique-weight canonical allocation drifted");
        }
    }

    private static void portfolio() {
        Model model = model(19, 20, 3, 50000);
        var budget = new PlanningBudget(0, 8_000_000, 64L << 20, () -> false, System::nanoTime);
        try (var views = CountModelViews.create(model.rows, model.low, model.high, budget);
             var search = new CountViewSearch(views, budget)) {
            search.resume(16384);
            check(search.hasGroupedPartition(), "Original capacity groups were hidden behind compilation");
            search.mode(CountPortfolioPolicy.Mode.PROOF);
            check(!search.hasGroupedPartition(), "Candidate task remained eligible for a proof-only turn");
            search.mode(CountPortfolioPolicy.Mode.FIRST_WITNESS);
            check(search.hasGroupedPartition(), "Goal change lost the retained candidate task");
            while (true) {
                if (!search.step()) continue;
                if (search.counts() != null || !search.retained()) break;
                search.resume(16384);
            }
            check(search.counts() != null && valid(model, search.counts()), "Retained table failed to deliver a checked witness");
            check(!search.hasGroupedPartition(), "Finished table stayed live");
            check(!search.infeasible(), "Candidate completion became a negative proof");
        }
        check(budget.reservedBytes() == 0, "Portfolio workspace leak");
    }

    private static void equal(List<ExactLinearProgram.Constraint> rows, Map<Integer, BigInteger> terms, BigInteger rhs) {
        rows.add(new ExactLinearProgram.Constraint(terms, rhs));
        var reverse = new TreeMap<Integer, BigInteger>();
        terms.forEach((i, a) -> reverse.put(i, a.negate()));
        rows.add(new ExactLinearProgram.Constraint(reverse, rhs.negate()));
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
