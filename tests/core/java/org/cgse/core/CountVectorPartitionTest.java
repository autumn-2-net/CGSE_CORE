// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exact multidimensional allocations, scope boundaries and retained child handoffs. */
public final class CountVectorPartitionTest {
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high,
                         BigInteger[] witness) {}
    private record Result(BigInteger[] counts, long work, int pauses, String diagnostics) {}

    public static void main(String[] args) throws Exception {
        int solved = 0;
        for (int seed = 0; seed < 12; seed++) {
            var model = model(seed, seed % 3 == 0);
            check(valid(model, model.witness), "Constructive oracle");
            var whole = solve(model, 262144, null);
            var sliced = solve(model, seed == 0 ? 1 : 113, null);
            check(Arrays.equals(whole.counts, sliced.counts), "Handoffs changed the candidate");
            check(sliced.pauses > 0, "Retained path not reached");
            if (whole.counts != null) solved++;
            var changed = permute(model, 71877 + seed);
            solve(changed, 97, null);
        }
        check(solved >= 8, "Small vector allocations lost: " + solved);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var model = model(1, false);
            check(Arrays.equals(solve(model, 127, null).counts, solve(model, 127, pool).counts), "Worker handoff changed the candidate");
        } finally { pool.shutdownNow(); }
        residualMatching();
        boundaries();
        System.out.println("Vector partition: 12 whole/sliced pairs, 12 permutations, worker handoff and scope/limit controls; solved=" + solved);
    }

    private static Model model(int seed, boolean shifted) {
        int groups = 6 + seed % 4, bins = 2 + seed % 2, dimensions = 2 + seed % 2;
        return model(seed, shifted, groups, bins, dimensions, 31);
    }

    private static Model model(int seed, boolean shifted, int groups, int bins, int dimensions, int weights) {
        var random = new Random(920137L + seed);
        int n = groups * bins;
        var low = new BigInteger[n]; var high = new BigInteger[n]; var witness = new BigInteger[n];
        var capacity = new int[groups]; var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int g = 0; g < groups; g++) {
            capacity[g] = 1 + random.nextInt(3); int left = capacity[g];
            var terms = new TreeMap<Integer, BigInteger>();
            for (int b = 0; b < bins; b++) {
                int id = g * bins + b;
                low[id] = BigInteger.valueOf((g + b) % 5 - 2);
                if (shifted) low[id] = low[id].add(BigInteger.ONE.shiftLeft(70).multiply(BigInteger.valueOf((id & 1) == 0 ? 1 : -1)));
                high[id] = low[id].add(BigInteger.valueOf(capacity[g]));
                int amount = b == bins - 1 ? left : random.nextInt(left + 1); left -= amount;
                witness[id] = low[id].add(BigInteger.valueOf(amount));
                terms.put(id, BigInteger.ONE);
            }
            rows.add(new ExactLinearProgram.Constraint(terms, activity(terms, witness)));
        }
        // Two or three independent weight vectors shared by all destinations.
        for (int d = 0; d < dimensions; d++) {
            int[] weight = new int[groups]; for (int g = 0; g < groups; g++) weight[g] = 1 + random.nextInt(weights);
            for (int b = 0; b < bins; b++) {
                var terms = new TreeMap<Integer, BigInteger>();
                for (int g = 0; g < groups; g++) terms.put(g * bins + b, BigInteger.valueOf(-weight[g]));
                rows.add(new ExactLinearProgram.Constraint(terms, activity(terms, witness)));
            }
        }
        return new Model(rows, low, high, witness);
    }

    private static void residualMatching() throws Exception {
        for (int seed : new int[] { 0, 1, 6, 32 }) {
            var model = model(seed, seed == 6, 18 + seed % 7, 3, 2, 997);
            check(valid(model, model.witness), "Residual constructive oracle");
            var whole = solve(model, 262144, null);
            var sliced = solve(model, seed == 0 ? 1 : 113, null);
            check(whole.counts != null && sliced.counts != null, "Residual decomposition lost a known witness: " + seed);
            check(whole.diagnostics.contains("count_match_admission"), "Residual domain not reconsidered");
            check(whole.diagnostics.contains("proven_infeasible=true"), "Partial-allocation conflict not exercised");
            // Several chosen first bins have no completion, even though the
            // original model is feasible. Their exact exhaustion must retry the
            // decomposition without publishing a global negative conclusion.
        }
        var model = model(1, false, 19, 3, 2, 997);
        var pool = Executors.newSingleThreadExecutor();
        try {
            check(Arrays.equals(solve(model, 127, null).counts, solve(model, 127, pool).counts),
                    "Residual table was lost across workers");
        } finally { pool.shutdownNow(); }
        var cancel = new AtomicBoolean();
        var budget = new PlanningBudget(0, 16_000_000, 64L << 20, cancel::get, System::nanoTime);
        try (var search = CountVectorPartition.create(model.rows, model.low, model.high, budget, 4096)) {
            check(search != null, "Residual cancellation admission");
            while (!budget.diagnostics().contains("count_match_admission")) {
                if (!search.step()) continue;
                check(search.paused(), "Residual table not reached before cancellation"); search.resume(4096);
            }
            check(budget.reservedBytes() > 0, "Missing owned residual frontier");
            cancel.set(true);
            boolean stopped = false;
            try { search.step(); } catch (CancellationException expected) { stopped = true; }
            check(stopped, "Residual matcher ignored cancellation");
        }
        check(budget.reservedBytes() == 0, "Residual matcher or retained lattice leaked");
        System.out.println("Residual matching: 4 conflicted decompositions, sliced/shifted domains, worker handoff and cancellation");
    }

    private static Model permute(Model model, int seed) {
        var random = new Random(seed); var ids = new ArrayList<Integer>();
        for (int i = 0; i < model.low.length; i++) ids.add(i); Collections.shuffle(ids, random);
        var low = model.low.clone(); var high = model.high.clone(); var witness = model.witness.clone();
        int[] map = new int[low.length];
        for (int i = 0; i < map.length; i++) { map[ids.get(i)] = i; low[i] = model.low[ids.get(i)]; high[i] = model.high[ids.get(i)]; witness[i] = model.witness[ids.get(i)]; }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (var row : model.rows) {
            var terms = new LinkedHashMap<Integer, BigInteger>(); var entries = new ArrayList<>(row.terms().entrySet());
            Collections.shuffle(entries, random); for (var term : entries) terms.put(map[term.getKey()], term.getValue());
            rows.add(new ExactLinearProgram.Constraint(terms, row.upper()));
        }
        Collections.shuffle(rows, random); return new Model(rows, low, high, witness);
    }

    private static Result solve(Model model, long quantum, ExecutorService worker) throws Exception {
        var budget = new PlanningBudget(0, 16_000_000, 64L << 20, () -> false, System::nanoTime);
        int pauses = 0; BigInteger[] counts;
        try (var search = CountVectorPartition.create(model.rows, model.low, model.high, budget, quantum)) {
            check(search != null, "Expected vector structure");
            for (int visits = 0; ; visits++) {
                check(visits < 1_000_000, "Continuation did not advance");
                boolean done = worker == null ? search.step() : worker.submit(search::step).get(30, TimeUnit.SECONDS);
                if (!done) continue;
                if (!search.paused()) break;
                pauses++; search.resume(quantum);
            }
            counts = search.counts(); if (counts != null) check(valid(model, counts), "Invalid original-domain witness");
            check(search.matches(model.rows, model.low, model.high), "Own scope mismatch");
            var changed = model.high.clone(); changed[0] = changed[0].add(BigInteger.ONE);
            check(!search.matches(model.rows, model.low, changed), "Foreign domains reused");
        }
        check(budget.reservedBytes() == 0, "Leaked vector/affine memory");
        return new Result(counts, budget.nodes(), pauses, budget.diagnostics());
    }

    private static void boundaries() throws Exception {
        var model = model(2, false); var rows = new ArrayList<>(model.rows);
        // Singleton side conditions are checked in the original coordinates,
        // even though decomposition ignores them. These two cannot both hold.
        rows.add(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE), model.low[0]));
        rows.add(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE.negate()), model.low[0].add(BigInteger.ONE).negate()));
        check(solve(new Model(rows, model.low, model.high, model.witness), 4096, null).counts == null, "Failed face became a witness");
        var cancel = new AtomicBoolean();
        var budget = new PlanningBudget(0, 16_000_000, 64L << 20, cancel::get, System::nanoTime);
        long held = budget.availableBytes(); check(budget.tryReserve(held), "Reserve test guard");
        check(CountVectorPartition.create(model.rows, model.low, model.high, budget, 4096) == null, "Memory refusal ignored");
        budget.release(held);
        var high = model.high.clone(); high[0] = null;
        check(CountVectorPartition.create(model.rows, model.low, high, budget, 4096) == null, "Invented finite endpoint");
        try (var search = CountVectorPartition.create(model.rows, model.low, model.high, budget, 4096)) {
            check(search != null, "Cancellation admission"); search.step(); cancel.set(true);
            boolean stopped = false;
            try { search.step(); } catch (CancellationException expected) { stopped = true; }
            check(stopped, "Cancellation ignored");
        }
        check(budget.reservedBytes() == 0, "Cancellation/refusal memory leak");
        var lowBudget = new PlanningBudget(0, 8192, 64L << 20, () -> false, System::nanoTime);
        check(CountVectorPartition.create(model.rows, model.low, model.high, lowBudget, 4096) == null, "Small request displaced");
    }
    private static boolean valid(Model model, BigInteger[] p) {
        for (int i = 0; i < p.length; i++) if (p[i].compareTo(model.low[i]) < 0 || p[i].compareTo(model.high[i]) > 0) return false;
        for (var row : model.rows) if (activity(row.terms(), p).compareTo(row.upper()) > 0) return false;
        return true;
    }
    private static BigInteger activity(Map<Integer, BigInteger> terms, BigInteger[] p) {
        var sum = BigInteger.ZERO; for (var term : terms.entrySet()) sum = sum.add(term.getValue().multiply(p[term.getKey()])); return sum;
    }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
