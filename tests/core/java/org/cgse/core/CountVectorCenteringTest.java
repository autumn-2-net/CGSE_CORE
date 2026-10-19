// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Interior hints remain disposable; only exact full-domain witnesses survive. */
public final class CountVectorCenteringTest {
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high,
                         BigInteger[] witness) {}
    private record Result(BigInteger[] counts, long work, int pauses) {}

    public static void main(String[] args) throws Exception {
        int solved = 0;
        for (int seed = 0; seed < 12; seed++) {
            var model = model(seed);
            check(valid(model, model.witness), "Independent planted oracle");
            var whole = solve(model, 262144, null);
            var sliced = solve(model, seed == 0 ? 1 : 113, null);
            check(Arrays.equals(whole.counts, sliced.counts) && whole.work == sliced.work,
                    "Projection or repair restarted across handoffs");
            check(sliced.pauses > 0, "Continuation was not exercised");
            if (whole.counts != null) solved++;
            System.out.println(seed + " " + (whole.counts == null ? "UNKNOWN" : "SAT") + " work=" + whole.work);
        }
        check(solved == 12, "Lost a maintained interior bin: " + solved);
        var worker = Executors.newSingleThreadExecutor();
        try {
            check(Arrays.equals(solve(model(0), 127, null).counts, solve(model(0), 127, worker).counts),
                    "Worker handoff changed the candidate");
        } finally { worker.shutdownNow(); }
        boundaries();
        System.out.println("Interior vector repairs: planted bins, sliced/worker handoffs, exact scope and lifetime checks passed");
    }

    private static Model model(int seed) {
        int n = new int[] {40, 64, 96, 128}[seed % 4];
        var random = new Random(982113L + seed);
        var low = new BigInteger[n]; var high = new BigInteger[n]; var witness = new BigInteger[n];
        for (int i = 0; i < n; i++) {
            int width = i % 7 == 0 ? 0 : 1 + random.nextInt(3);
            low[i] = BigInteger.valueOf(i % 5 + (seed % 3 == 0 ? 1L << 25 : 0));
            high[i] = low[i].add(BigInteger.valueOf(width));
            witness[i] = low[i].add(BigInteger.valueOf(random.nextInt(width + 1)));
        }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int d = 0; d < 2 + seed % 2; d++) {
            var terms = new TreeMap<Integer, BigInteger>();
            for (int i = 0; i < n; i++) terms.put(i, BigInteger.valueOf(1 + random.nextInt(97)));
            var rhs = activity(terms, witness);
            rows.add(new ExactLinearProgram.Constraint(terms, rhs));
            var opposite = new TreeMap<Integer, BigInteger>(); terms.forEach((i, a) -> opposite.put(i, a.negate()));
            rows.add(new ExactLinearProgram.Constraint(opposite, rhs.negate()));
        }
        return new Model(rows, low, high, witness);
    }

    private static Result solve(Model model, long quantum, ExecutorService worker) throws Exception {
        var budget = budget(() -> false); int pauses = 0; BigInteger[] counts;
        try (var search = CountVectorNeighborhood.centered(model.rows, model.low, model.high, budget, quantum)) {
            check(search != null, "Expected centered admission");
            for (;;) {
                boolean done = worker == null ? search.step() : worker.submit(search::step).get();
                if (!done) continue;
                if (!search.paused()) break;
                pauses++; search.resume(quantum);
            }
            counts = search.counts();
            if (counts != null) check(valid(model, counts), "Numeric hint escaped exact validation");
            check(search.work() <= 2_400_000, "Renewed finite repair allowance");
            long spent = budget.nodes(); search.step();
            check(spent == budget.nodes(), "Completed repair repeated work");
            search.close(); search.close();
            check(search.step() && !search.paused(), "Closed frontier resumed");
        }
        check(budget.reservedBytes() == 0, "Interior or exact repair workspace leaked");
        return new Result(counts, budget.nodes(), pauses);
    }

    private static void boundaries() throws Exception {
        var base = model(1); var rows = new ArrayList<>(base.rows);
        // Different exact bounds can collapse to the same double. Projection
        // success must not certify these contradictory original rows.
        var large = BigInteger.ONE.shiftLeft(60);
        rows.add(new ExactLinearProgram.Constraint(Map.of(1, large), large.multiply(base.low[1])));
        rows.add(new ExactLinearProgram.Constraint(Map.of(1, large.negate()), large.multiply(base.low[1]).add(BigInteger.ONE).negate()));
        check(solve(new Model(rows, base.low, base.high, null), 113, null).counts == null,
                "Floating-point alias accepted contradictory integer rows");
        var overflow = List.of(new ExactLinearProgram.Constraint(Map.of(1, BigInteger.ONE.shiftLeft(600)), BigInteger.ZERO));
        check(solve(new Model(overflow, base.low, base.high, null), 113, null).counts == null,
                "Nonfinite projection became a witness");
        var cancelled = new AtomicBoolean(); var budget = budget(cancelled::get);
        try (var search = CountVectorNeighborhood.centered(base.rows, base.low, base.high, budget, 113)) {
            check(search != null, "Cancellation admission");
            for (int i = 0; i < 8; i++) { if (search.step() && search.paused()) search.resume(113); }
            cancelled.set(true); boolean stopped = false;
            try { search.step(); } catch (CancellationException expected) { stopped = true; }
            check(stopped, "Retained projection ignored cancellation");
        }
        check(budget.reservedBytes() == 0, "Cancelled projection retained memory");
        budget = budget(() -> false); long held = budget.availableBytes();
        check(budget.tryReserve(held), "Reserve test memory");
        check(CountVectorNeighborhood.centered(base.rows, base.low, base.high, budget, 4096) == null,
                "Interior hint ignored memory admission");
        budget.release(held);
        var high = base.high.clone(); high[0] = null;
        check(CountVectorNeighborhood.centered(base.rows, base.low, high, budget, 4096) == null,
                "Unbounded endpoint invented");
        check(budget.reservedBytes() == 0, "Refusal retained memory");
    }

    private static BigInteger activity(Map<Integer, BigInteger> terms, BigInteger[] values) {
        var sum = BigInteger.ZERO;
        for (var term : terms.entrySet()) sum = sum.add(term.getValue().multiply(values[term.getKey()]));
        return sum;
    }
    private static boolean valid(Model model, BigInteger[] values) {
        for (int i = 0; i < values.length; i++) if (values[i].compareTo(model.low[i]) < 0 || values[i].compareTo(model.high[i]) > 0) return false;
        for (var row : model.rows) if (activity(row.terms(), values).compareTo(row.upper()) > 0) return false;
        return true;
    }
    private static PlanningBudget budget(java.util.function.BooleanSupplier cancelled) {
        return new PlanningBudget(0, 32_000_000, 64L << 20, cancelled, System::nanoTime);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
