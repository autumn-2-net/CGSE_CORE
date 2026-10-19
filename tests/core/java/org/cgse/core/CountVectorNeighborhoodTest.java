// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Restricted LP repairs keep exact full-bin verification and request ownership. */
public final class CountVectorNeighborhoodTest {
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high,
                         BigInteger[] witness) {}
    private record Result(BigInteger[] counts, long work, long peak, int pauses, String trace) {}

    public static void main(String[] args) throws Exception {
        int solved = 0;
        for (int seed = 0; seed < 12; seed++) {
            var model = model(seed, new int[] {40, 64, 96, 128}[seed % 4], 2 + seed % 2);
            check(valid(model, model.witness), "Constructive bin oracle");
            var whole = solve(model, 262144, null);
            var sliced = solve(model, seed == 0 ? 1 : 113, null);
            check(Arrays.equals(whole.counts, sliced.counts), "Handoff changed restricted repair");
            check(whole.work == sliced.work, "Handoff replenished or repeated charged work");
            check(sliced.pauses > 0, "Small grants did not exercise continuation");
            if (whole.counts != null) solved++;
            System.out.println(seed + " " + (whole.counts != null ? "SAT" : "UNKNOWN") + " work=" + whole.work);
        }
        check(solved == 12, "Lost a maintained constructive bin: " + solved);
        var input = model(0, 40, 2);
        var worker = Executors.newSingleThreadExecutor();
        try {
            check(Arrays.equals(solve(input, 127, null).counts, solve(input, 127, worker).counts),
                    "Owned LP/lattice lost across worker handoffs");
        } finally { worker.shutdownNow(); }
        translatedAllocation();
        boundaries(input);
        System.out.println("Vector neighborhoods: " + solved + "/12 planted bins; slicing, ownership and scope controls passed");
    }

    private static Model model(int seed, int n, int dimensions) {
        var random = new Random(851901L + seed);
        var low = new BigInteger[n]; var high = new BigInteger[n]; var witness = new BigInteger[n];
        for (int i = 0; i < n; i++) {
            int capacity = 1 + random.nextInt(3);
            low[i] = BigInteger.valueOf(i % 5); high[i] = low[i].add(BigInteger.valueOf(capacity));
            witness[i] = low[i].add(BigInteger.valueOf(random.nextInt(capacity + 1)));
        }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int d = 0; d < dimensions; d++) {
            var terms = new TreeMap<Integer, BigInteger>();
            for (int i = 0; i < n; i++) terms.put(i, BigInteger.valueOf(1 + random.nextInt(97)));
            BigInteger rhs = activity(terms, witness);
            rows.add(new ExactLinearProgram.Constraint(terms, rhs));
            var opposite = new TreeMap<Integer, BigInteger>(); terms.forEach((i, a) -> opposite.put(i, a.negate()));
            rows.add(new ExactLinearProgram.Constraint(opposite, rhs.negate()));
        }
        return new Model(rows, low, high, witness);
    }

    private static Result solve(Model model, long quantum, ExecutorService worker) throws Exception {
        var budget = budget(() -> false); int pauses = 0; BigInteger[] counts;
        try (var search = CountVectorNeighborhood.create(model.rows, model.low, model.high, budget, quantum)) {
            check(search != null, "Expected neighborhood admission");
            while (true) {
                boolean done = worker == null ? search.step() : worker.submit(search::step).get();
                if (!done) continue;
                if (!search.paused()) break;
                pauses++; search.resume(quantum);
            }
            counts = search.counts();
            if (counts != null) check(valid(model, counts), "Restricted proposal failed original-bin check");
            check(search.work() <= 2_400_000, "Restricted attempt exceeded finite opportunity");
            long spent = budget.nodes(); search.step();
            check(budget.nodes() == spent, "Completed search repeated work");
        }
        check(budget.reservedBytes() == 0, "LP or affine reservation leaked");
        return new Result(counts, budget.nodes(), budget.peakBytes(), pauses, budget.diagnostics());
    }

    private static void boundaries(Model input) throws Exception {
        var rows = new ArrayList<>(input.rows);
        rows.add(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE), input.low[0]));
        rows.add(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE.negate()), input.low[0].add(BigInteger.ONE).negate()));
        check(solve(new Model(rows, input.low, input.high, input.witness), 4096, null).counts == null,
                "Inconsistent full-bin side conditions accepted");
        var cancelled = new AtomicBoolean(); var budget = budget(cancelled::get);
        try (var search = CountVectorNeighborhood.create(input.rows, input.low, input.high, budget, 4096)) {
            check(search != null, "Cancellation admission");
            while (!budget.diagnostics().contains("count_vector_neighborhood")) {
                if (!search.step()) continue;
                check(search.paused(), "Repair frontier not reached before cancellation"); search.resume(4096);
            }
            cancelled.set(true);
            boolean stopped = false;
            try { search.step(); } catch (CancellationException expected) { stopped = true; }
            check(stopped, "Neighborhood ignored cancellation");
        }
        check(budget.reservedBytes() == 0, "Cancelled LP retained workspace");
        budget = budget(() -> false); long held = budget.availableBytes();
        check(budget.tryReserve(held), "Memory guard");
        check(CountVectorNeighborhood.create(input.rows, input.low, input.high, budget, 4096) == null,
                "Ignored reservation refusal");
        budget.release(held);
        var upper = input.high.clone(); upper[0] = null;
        check(CountVectorNeighborhood.create(input.rows, input.low, upper, budget, 4096) == null,
                "Invented finite endpoint");
        check(CountVectorNeighborhood.create(input.rows, input.low, input.high, budget, 0) == null,
                "Accepted nonpositive quantum");
        check(budget.reservedBytes() == 0, "Refusal leaked workspace");
        var tiny = new PlanningBudget(0, 32767, 64L << 20, () -> false, System::nanoTime);
        check(CountVectorNeighborhood.create(input.rows, input.low, input.high, tiny, 4096) == null,
                "Small request displaced");
    }

    private static void translatedAllocation() {
        var base = model(2, 64, 2); int n = base.low.length;
        var low = new BigInteger[2 * n]; var high = new BigInteger[2 * n]; var witness = new BigInteger[2 * n];
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int i = 0; i < n; i++) {
            BigInteger capacity = base.high[i].subtract(base.low[i]);
            for (int b = 0; b < 2; b++) {
                int id = 2 * i + b;
                low[id] = BigInteger.ONE.shiftLeft(70).multiply(BigInteger.valueOf(b == 0 ? 1 : -1));
                high[id] = low[id].add(capacity);
                BigInteger used = base.witness[i].subtract(base.low[i]);
                witness[id] = low[id].add(b == 0 ? used : capacity.subtract(used));
            }
            var terms = Map.of(2 * i, BigInteger.ONE, 2 * i + 1, BigInteger.ONE);
            rows.add(new ExactLinearProgram.Constraint(terms, activity(terms, witness)));
        }
        for (int d = 0; d < 2; d++) for (int b = 0; b < 2; b++) {
            var terms = new TreeMap<Integer, BigInteger>();
            for (var term : base.rows.get(2 * d).terms().entrySet()) terms.put(2 * term.getKey() + b, term.getValue().negate());
            rows.add(new ExactLinearProgram.Constraint(terms, activity(terms, witness)));
        }
        var input = new Model(rows, low, high, witness);
        check(valid(input, witness), "Translated allocation oracle");
        var budget = budget(() -> false);
        try (var search = CountVectorPartition.create(rows, low, high, budget, 113)) {
            check(search != null, "Wide translated groups rejected");
            for (;;) {
                if (!search.step()) continue;
                if (!search.paused()) break;
                search.resume(113);
            }
            check(search.counts() != null && valid(input, search.counts()), "Wide allocation lost offsets or capacities");
            check(budget.diagnostics().contains("count_vector_neighborhood"), "Restricted repair was bypassed");
        }
        check(budget.reservedBytes() == 0, "Wide allocation retained workspace");
    }
    private static PlanningBudget budget(java.util.function.BooleanSupplier cancelled) {
        return new PlanningBudget(0, 32_000_000, 64L << 20, cancelled, System::nanoTime);
    }
    private static BigInteger activity(Map<Integer, BigInteger> row, BigInteger[] values) {
        var result = BigInteger.ZERO;
        for (var term : row.entrySet()) result = result.add(term.getValue().multiply(values[term.getKey()]));
        return result;
    }
    private static boolean valid(Model model, BigInteger[] values) {
        for (int i = 0; i < values.length; i++) if (values[i].compareTo(model.low[i]) < 0 || values[i].compareTo(model.high[i]) > 0) return false;
        for (var row : model.rows) if (activity(row.terms(), values).compareTo(row.upper()) > 0) return false;
        return true;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
