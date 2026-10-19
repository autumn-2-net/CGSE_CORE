// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Wide multiplicities must survive normalization, residual reuse and execution checks. */
public final class CountVectorCapacityTest {
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high,
                         BigInteger[] witness) {}
    private record Result(BigInteger[] counts, long work) {}

    public static void main(String[] args) throws Exception {
        for (int seed = 0; seed < 6; seed++) {
            var model = model(seed, false);
            check(valid(model, model.witness), "Constructive oracle");
            var whole = solve(model, 262144, null);
            var sliced = solve(model, seed == 0 ? 1 : 113, null);
            check(whole.counts != null && Arrays.equals(whole.counts, sliced.counts),
                    "Wide allocation lost across handoffs: " + seed);
            var translated = model(seed, true);
            check(valid(translated, translated.witness), "Translated constructive oracle");
            check(solve(translated, 113, null).counts != null, "Large signed offsets lost: " + seed);
            System.out.println("Wide allocation " + seed + " work=" + whole.work);
        }
        var model = model(0, false);
        var worker = Executors.newSingleThreadExecutor();
        try {
            check(Arrays.equals(solve(model, 127, null).counts, solve(model, 127, worker).counts),
                    "Worker handoff changed the wide allocation");
        } finally { worker.shutdownNow(); }
        residualIdentity(model);
        boundaries(model);
        System.out.println("Wide vector allocations: ten-dimensional/large-count, shifted, handoff, exact identity and lifetime checks passed");
    }

    private static Model model(int seed, boolean shifted) {
        int groups = new int[] {64, 96, 120}[seed % 3], bins = 2 + seed / 3;
        int dimensions = new int[] {7, 9, 10}[seed % 3];
        var random = new Random(621983L + seed);
        int n = groups * bins;
        var low = new BigInteger[n + 1]; var high = new BigInteger[n + 1]; var witness = new BigInteger[n + 1];
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int g = 0; g < groups; g++) {
            int capacity = (seed % 2 == 0 ? 65537 : 1_000_000) + random.nextInt(199), left = capacity;
            var terms = new TreeMap<Integer, BigInteger>();
            for (int b = 0; b < bins; b++) {
                int id = g * bins + b;
                low[id] = BigInteger.valueOf((g + b) % 7 - 3);
                if (shifted) low[id] = low[id].add(BigInteger.ONE.shiftLeft(90).multiply(BigInteger.valueOf((id & 1) == 0 ? 1 : -1)));
                high[id] = low[id].add(BigInteger.valueOf(capacity));
                int amount = b == bins - 1 ? left : random.nextInt(left + 1); left -= amount;
                witness[id] = low[id].add(BigInteger.valueOf(amount)); terms.put(id, BigInteger.ONE);
            }
            rows.add(new ExactLinearProgram.Constraint(terms, activity(terms, witness)));
        }
        low[n] = high[n] = witness[n] = BigInteger.valueOf(7);
        for (int d = 0; d < dimensions; d++) {
            int[] weights = new int[groups]; for (int g = 0; g < groups; g++) weights[g] = 1 + random.nextInt(997);
            for (int b = 0; b < bins; b++) {
                var terms = new TreeMap<Integer, BigInteger>();
                for (int g = 0; g < groups; g++) terms.put(g * bins + b, BigInteger.valueOf(-weights[g]));
                // Fixed opposite-sign inputs must disappear only by exact RHS translation.
                terms.put(n, BigInteger.valueOf(11));
                rows.add(new ExactLinearProgram.Constraint(terms, activity(terms, witness)));
            }
        }
        return new Model(rows, low, high, witness);
    }

    private static Result solve(Model model, long quantum, ExecutorService worker) throws Exception {
        var budget = budget(() -> false); BigInteger[] counts;
        try (var search = CountVectorPartition.create(model.rows, model.low, model.high, budget, quantum)) {
            check(search != null, "Wide allocation refused");
            while (true) {
                boolean done = worker == null ? search.step() : worker.submit(search::step).get();
                if (!done) continue;
                if (!search.paused()) break;
                search.resume(quantum);
            }
            counts = search.counts();
            if (counts != null) check(valid(model, counts), "Candidate violates an original row or bound");
            check(search.work() < 8_400_000, "Local work quota renewed");
        }
        check(budget.reservedBytes() == 0, "Wide allocation retained workspace");
        return new Result(counts, budget.nodes());
    }

    private static void residualIdentity(Model model) throws Exception {
        var budget = budget(() -> false);
        try (var search = CountVectorPartition.create(model.rows, model.low, model.high, budget, 4096)) {
            check(search != null, "Residual identity admission");
            var field = CountVectorPartition.class.getDeclaredField("remaining"); field.setAccessible(true);
            var key = CountVectorPartition.class.getDeclaredMethod("residualKey"); key.setAccessible(true);
            var remaining = (BigInteger[]) field.get(search);
            var original = remaining[0]; var keys = new HashSet<>();
            // These residuals share their low byte. Reusing an exact rejection
            // between them would incorrectly prune a different feasible branch.
            for (int value : new int[] {0, 256, 65536, (1 << 30) - 256}) {
                remaining[0] = BigInteger.valueOf(value);
                check(keys.add(key.invoke(search)), "Distinct multiplicities aliased in residual conflict cache");
            }
            remaining[0] = original;
            Object first = key.invoke(search);
            var attempt = CountVectorPartition.class.getDeclaredField("attempt"); attempt.setAccessible(true);
            attempt.setInt(search, 1);
            check(!first.equals(key.invoke(search)), "Different destination reused a residual rejection");
            attempt.setInt(search, 2);
            check(first.equals(key.invoke(search)), "Column-order retry changed the same residual identity");
        }
        check(budget.reservedBytes() == 0, "Residual identity retained workspace");
    }

    private static void boundaries(Model model) throws Exception {
        var rows = new ArrayList<>(model.rows);
        rows.add(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE), model.low[0]));
        rows.add(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE.negate()), model.low[0].add(BigInteger.ONE).negate()));
        check(solve(new Model(rows, model.low, model.high, null), 4096, null).counts == null,
                "Decomposed candidate bypassed contradictory original side conditions");
        var cancelled = new AtomicBoolean(); var budget = budget(cancelled::get);
        try (var search = CountVectorPartition.create(model.rows, model.low, model.high, budget, 113)) {
            check(search != null, "Cancellation admission"); search.step(); cancelled.set(true);
            boolean stopped = false;
            try { search.step(); } catch (CancellationException expected) { stopped = true; }
            check(stopped, "Wide allocation ignored cancellation");
        }
        check(budget.reservedBytes() == 0, "Cancellation retained workspace");
        budget = budget(() -> false);
        var high = model.high.clone(); high[0] = model.low[0].add(BigInteger.ONE.shiftLeft(30));
        check(CountVectorPartition.create(model.rows, model.low, high, budget, 4096) == null, "Unsupported width was truncated");
        high[0] = null;
        check(CountVectorPartition.create(model.rows, model.low, high, budget, 4096) == null, "Unbounded width invented");
        long held = budget.availableBytes(); check(budget.tryReserve(held), "Reserve guard");
        check(CountVectorPartition.create(model.rows, model.low, model.high, budget, 4096) == null, "Memory guard ignored");
        budget.release(held);
        check(budget.reservedBytes() == 0, "Refusal retained workspace");
    }

    private static PlanningBudget budget(java.util.function.BooleanSupplier cancelled) {
        return new PlanningBudget(0, 40_000_000, 256L << 20, cancelled, System::nanoTime);
    }
    private static BigInteger activity(Map<Integer, BigInteger> terms, BigInteger[] value) {
        var sum = BigInteger.ZERO; for (var term : terms.entrySet()) sum = sum.add(term.getValue().multiply(value[term.getKey()])); return sum;
    }
    private static boolean valid(Model model, BigInteger[] value) {
        for (int i = 0; i < value.length; i++) if (value[i].compareTo(model.low[i]) < 0 || value[i].compareTo(model.high[i]) > 0) return false;
        for (var row : model.rows) if (activity(row.terms(), value).compareTo(row.upper()) > 0) return false;
        return true;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
