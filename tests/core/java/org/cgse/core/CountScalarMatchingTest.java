// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

/** Independent finite boxes, original-coordinate witnesses and interrupted scalar tables. */
public final class CountScalarMatchingTest {
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {}
    private record Result(BigInteger[] counts, boolean infeasible, long work, int pauses, String trace) {}

    public static void main(String[] args) throws Exception {
        int scalar = 0, sat = 0, unsat = 0;
        for (int seed = 0; seed < 192; seed++) {
            Model model = model(10, seed, seed % 3 == 0);
            boolean feasible = false;
            for (int code = 0; code < 1 << model.low.length; code++) {
                BigInteger[] point = model.low.clone();
                for (int i = 0; i < point.length; i++) if ((code & 1 << i) != 0) point[i] = model.high[i];
                feasible |= valid(model, point);
            }
            if (feasible) sat++; else unsat++;
            for (int order = 0; order < 3; order++) {
                var rows = new ArrayList<>(model.rows);
                Collections.shuffle(rows, new Random(8209L * seed + order));
                Model permuted = new Model(rows, model.low, model.high);
                Result result = solve(permuted, 1024, 37);
                check((result.counts != null) == feasible && result.infeasible == !feasible,
                        "Wrong scalar conclusion: " + seed + "/" + order + "/" + result.trace);
                if (result.trace.contains("count_match_scalar")) scalar++;
            }
        }
        check(scalar >= 200 && sat >= 50 && unsat >= 30,
                "Insufficient scalar/result coverage: scalar=" + scalar + "; SAT=" + sat + "; UNSAT=" + unsat);
        continuation();
        limits();
        admission();
        sharedBudget();
        System.out.println("Scalar matching: 576 ordered boxes / 196608 exhaustive assignments; scalar=" + scalar +
                "; SAT=" + sat + "; UNSAT=" + unsat + "; continuations, admission, memory, cancellation and parallel caps passed");
    }

    private static Model model(int size, int seed, boolean disturb) {
        Random random = new Random(188391L + seed);
        BigInteger[] low = new BigInteger[size], high = new BigInteger[size];
        var terms = new LinkedHashMap<Integer, BigInteger>();
        var opposite = new LinkedHashMap<Integer, BigInteger>();
        BigInteger target = BigInteger.ZERO, maximum = BigInteger.ZERO;
        int bits = new int[] {0, 20, 48, 61}[Math.floorMod(seed, 4)];
        for (int i = 0; i < size; i++) {
            low[i] = BigInteger.valueOf(random.nextInt(9) - 4);
            if (seed % 7 == 0) low[i] = low[i].subtract(BigInteger.ONE.shiftLeft(100));
            high[i] = low[i].add(BigInteger.ONE);
            BigInteger a = BigInteger.valueOf(random.nextInt(15) - 7).shiftLeft(bits)
                    .add(BigInteger.valueOf(random.nextInt(3) - 1));
            if (a.signum() != 0) {
                terms.put(i, a);
                opposite.put(i, a.negate());
            }
            target = target.add(a.multiply(random.nextBoolean() ? low[i] : high[i]));
            maximum = maximum.add(a.multiply(a.signum() < 0 ? low[i] : high[i]));
        }
        if (disturb) target = target.add(BigInteger.valueOf(257));
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        rows.add(new ExactLinearProgram.Constraint(terms, target));
        rows.add(new ExactLinearProgram.Constraint(opposite, target.negate()));
        // This redundant inequality must not prevent the specialization; when
        // another independent row survives, the full signature must be kept.
        rows.add(new ExactLinearProgram.Constraint(terms, maximum));
        if (seed % 11 == 0) rows.add(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.TWO, 1, BigInteger.valueOf(3)),
                low[0].multiply(BigInteger.TWO).add(low[1].multiply(BigInteger.valueOf(3))).add(BigInteger.TWO)));
        return new Model(rows, low, high);
    }

    private static Result solve(Model model, long first, long quantum) {
        var budget = budget(20_000_000, 64L << 20);
        int pauses = 0;
        Result result;
        try (var matching = new CountMeetInMiddle(model.rows, model.low, model.high, budget, first).retained()) {
            for (;;) {
                while (!matching.step()) {}
                if (!matching.paused()) break;
                check(matching.counts() == null && !matching.infeasible(), "Pause produced a conclusion");
                long work = budget.searchWork(), memory = budget.reservedBytes();
                check(matching.step() && matching.step(), "Paused matcher moved without a quantum");
                check(work == budget.searchWork() && memory == budget.reservedBytes(), "Paused polling did work");
                matching.resume(quantum);
                check(++pauses < 100_000, "Continuation made no progress");
            }
            if (matching.counts() != null) check(valid(model, matching.counts()), "Invalid original-coordinate witness");
            result = new Result(matching.counts(), matching.infeasible(), budget.searchWork(), pauses, budget.diagnostics());
        }
        check(budget.reservedBytes() == 0, "Scalar workspace leaked");
        return result;
    }

    private static void continuation() {
        // Even and odd turns stop in both table construction and complementary
        // lookups. Their result and charged work must equal uninterrupted work.
        for (int seed : new int[] {17, 18, 29, 30}) {
            Model model = model(26, seed, true);
            Result sliced = solve(model, 1024, 17), whole = solve(model, 12_000_000, 12_000_000);
            check(sliced.trace.contains("count_match_scalar"), "Scalar continuation was not exercised");
            check(sliced.pauses > 100, "Too few scalar handoffs");
            check(Arrays.equals(sliced.counts, whole.counts) && sliced.infeasible == whole.infeasible &&
                    sliced.work == whole.work, "Handoff repeated or lost scalar work");
        }
    }

    private static void limits() {
        var random = new Random(929291);
        BigInteger[] low = new BigInteger[36], high = new BigInteger[36];
        Arrays.fill(low, BigInteger.ZERO);
        Arrays.fill(high, BigInteger.ONE);
        var terms = new LinkedHashMap<Integer, BigInteger>();
        var opposite = new LinkedHashMap<Integer, BigInteger>();
        BigInteger target = BigInteger.ZERO;
        for (int i = 0; i < low.length; i++) {
            BigInteger weight = BigInteger.valueOf(1 + (random.nextLong() & ((1L << 45) - 1)));
            terms.put(i, weight);
            opposite.put(i, weight.negate());
            if (i % 2 == 0) target = target.add(weight);
        }
        Model model = new Model(List.of(new ExactLinearProgram.Constraint(terms, target),
                new ExactLinearProgram.Constraint(opposite, target.negate())), low, high);
        for (int mode = 0; mode < 4; mode++) {
            var budget = budget(mode == 0 ? 30_000 : 20_000_000, mode == 1 ? 2L << 20 : 64L << 20);
            boolean interrupted = false;
            try (var matching = new CountMeetInMiddle(model.rows, model.low, model.high, budget,
                    mode == 3 ? 2048 : 12_000_000)) {
                if (mode == 0) matching.retained();
                if (mode == 2) {
                    while (budget.searchWork() < 30000) check(!matching.step(), "Expected live scalar search");
                    budget.cancel();
                }
                for (;;) {
                    while (!matching.step()) {}
                    if (!matching.paused()) break;
                    matching.resume(4096);
                }
                check(matching.counts() == null && !matching.infeasible(),
                        "Cutoff became a conclusion in mode " + mode + ": " + budget.diagnostics());
            } catch (PlanningBudget.Exhausted expected) {
                check(mode == 0 && expected.limit() == PlanningBudget.Limit.SEARCH_LIMIT, "Unexpected request limit");
                interrupted = true;
            } catch (CancellationException expected) {
                check(mode == 2, "Unexpected cancellation");
                interrupted = true;
            }
            check(mode != 0 && mode != 2 || interrupted, "Global work cap/cancellation was ignored");
            check(budget.reservedBytes() == 0, "Interrupted scalar workspace leaked");
            if (mode == 1) check(budget.peakBytes() <= 2L << 20, "Scalar table exceeded memory cap");
        }
    }

    private static void admission() {
        Model model = model(40, 18, false);
        var budget = budget(20_000_000, 256L << 20);
        long allowance = CountMeetInMiddle.scoutWork(model.rows, model.low, model.high, budget);
        check(allowance > 0 && allowance < budget.remainingWork() / 2, "Affordable scalar table was excluded");
        check(budget.reservedBytes() == 0, "Admission workspace leaked");
        check(CountMeetInMiddle.scoutWork(model.rows, model.low, model.high, budget(20000, 256L << 20)) == 0,
                "Scalar scout ignored remaining work");
        check(CountMeetInMiddle.scoutWork(model.rows, model.low, model.high, budget(20_000_000, 8L << 20)) == 0,
                "Scalar scout ignored remaining memory");
        Model unsafe = model(40, 19, false);
        check(CountMeetInMiddle.scoutWork(unsafe.rows, unsafe.low, unsafe.high, budget) == 0,
                "Unsafe primitive arithmetic received scalar admission");
        Model narrow = model(40, 20, false);
        check(CountMeetInMiddle.scoutWork(narrow.rows, narrow.low, narrow.high, budget) == 0,
                "Small sum ranges should retain their cheaper existing strategies");
    }

    private static PlanningBudget budget(long work, long memory) {
        return new PlanningBudget(0, work, memory, () -> false, System::nanoTime);
    }

    private static void sharedBudget() throws Exception {
        Model model = model(26, 18, false);
        for (long limit : new long[] {20_000, 2_000_000}) {
            var budget = budget(limit, 64L << 20);
            var executor = Executors.newFixedThreadPool(4);
            try {
                var tasks = new ArrayList<Callable<Boolean>>();
                for (int worker = 0; worker < 4; worker++) tasks.add(() -> {
                    try (var matching = new CountMeetInMiddle(model.rows, model.low, model.high, budget, 1024).retained()) {
                        for (;;) {
                            while (!matching.step()) {}
                            if (!matching.paused()) break;
                            matching.resume(257);
                        }
                        check(!matching.infeasible(), "Concurrent table contradicted a planted witness");
                        if (matching.counts() != null) check(valid(model, matching.counts()), "Invalid concurrent witness");
                        return matching.counts() != null;
                    } catch (PlanningBudget.Exhausted exhausted) {
                        check(exhausted.limit() == PlanningBudget.Limit.SEARCH_LIMIT, "Wrong concurrent limit");
                        return false;
                    }
                });
                int solved = 0;
                for (var result : executor.invokeAll(tasks)) if (result.get()) solved++;
                check(limit == 20_000 ? solved < 4 : solved == 4, "Independent budgets or stalled scalar workers");
            } finally {
                executor.shutdownNow();
                check(executor.awaitTermination(10, TimeUnit.SECONDS), "Scalar workers remained live");
            }
            check(budget.searchWork() <= limit + 4, "Concurrent scalar work escaped the shared cap");
            check(budget.reservedBytes() == 0, "Concurrent scalar workspace leaked");
        }
    }

    private static boolean valid(Model model, BigInteger[] point) {
        if (point == null) return false;
        for (int i = 0; i < point.length; i++)
            if (point[i].compareTo(model.low[i]) < 0 || point[i].compareTo(model.high[i]) > 0) return false;
        for (var row : model.rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(point[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
