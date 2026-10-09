// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

/** Independent subset enumeration and original-coordinate checks for the four-list candidate path. */
public final class CountResidueJoinTest {
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {}
    private record Result(BigInteger[] counts, boolean infeasible, long work, int pauses, String trace) {}

    public static void main(String[] args) throws Exception {
        smallOracle();
        continuation();
        limits();
        scope();
        skewedBuckets();
        parallel();
        System.out.println("Residue join: 240 finite models / 720 orders; signed and duplicate sums; " +
                "continuation, skewed tables, original bounds, cancellation and shared budgets passed");
    }

    private static void smallOracle() {
        Random random = new Random(490821);
        int sat = 0, unsat = 0, full = 0;
        long assignments = 0;
        for (int seed = 0; seed < 240; seed++) {
            int n = 6 + seed % 8;
            long[] values = new long[n];
            long target = 0;
            for (int i = 0; i < n; i++) {
                values[i] = ((long) (random.nextInt(33) - 16) << (seed % 3 * 17)) + random.nextInt(3) - 1;
                if (random.nextBoolean()) target += values[i];
            }
            if (seed % 3 == 0) target += 257;
            boolean feasible = false;
            for (int mask = 0; mask < 1 << n; mask++) feasible |= sum(values, mask) == target;
            assignments += 1 << n;
            if (feasible) sat++; else unsat++;
            for (int order = 0; order < 3; order++) {
                if (order > 0) for (int i = n - 1; i > 0; i--) {
                    int other = random.nextInt(i + 1);
                    long value = values[i]; values[i] = values[other]; values[other] = value;
                }
                long span = 0;
                for (long value : values) span += Math.abs(value);
                var shape = CountResidueJoin.Shape.create(n, span);
                var budget = budget(4_000_000, 32L << 20);
                try (var join = new CountResidueJoin(values, target, budget, shape)) {
                    while (!join.step()) {}
                    long mask = join.candidate();
                    if (mask >= 0) check(mask < 1L << n && sum(values, mask) == target, "Invalid subset witness");
                    check(feasible || mask < 0, "Contradicted independent exhaustive oracle");
                    if (budget.diagnostics().contains("truncated=0")) {
                        check((mask >= 0) == feasible, "Untruncated join lost a finite subset");
                        full++;
                    }
                    long before = budget.searchWork();
                    check(join.step() && before == budget.searchWork(), "Completed join repeated work");
                }
                check(budget.reservedBytes() == 0, "Oracle join leaked memory");
            }
        }
        check(sat >= 100 && unsat >= 40 && full >= 650, "Insufficient oracle coverage");
        System.out.println("Independent subset assignments=" + assignments + "; SAT=" + sat + "; UNSAT=" + unsat + "; untruncated=" + full);
    }

    private static Model model(int variables, int seed, int offsetBits) {
        Random random = new Random(509201L + seed);
        var forward = new LinkedHashMap<Integer, BigInteger>();
        var reverse = new LinkedHashMap<Integer, BigInteger>();
        BigInteger[] low = new BigInteger[variables], high = new BigInteger[variables];
        BigInteger target = BigInteger.ZERO;
        for (int i = 0; i < variables; i++) {
            low[i] = BigInteger.ONE.shiftLeft(offsetBits).add(BigInteger.valueOf(random.nextInt(11) - 5));
            if (i % 3 == 0) low[i] = low[i].negate();
            high[i] = low[i].add(BigInteger.ONE);
            BigInteger coefficient = BigInteger.valueOf((1 << 18) + random.nextInt(1 << 18));
            if (i % 5 == 0) coefficient = coefficient.negate();
            forward.put(i, coefficient);
            reverse.put(i, coefficient.negate());
            target = target.add(coefficient.multiply(random.nextBoolean() ? low[i] : high[i]));
        }
        return new Model(List.of(new ExactLinearProgram.Constraint(forward, target),
                new ExactLinearProgram.Constraint(reverse, target.negate())), low, high);
    }

    private static Result solve(Model model, long first, long quantum) {
        var budget = budget(20_000_000, 128L << 20);
        Result result;
        int pauses = 0;
        try (var match = new CountMeetInMiddle(model.rows, model.low, model.high, budget, first).retained()) {
            for (;;) {
                while (!match.step()) {}
                if (!match.paused()) break;
                check(match.counts() == null && !match.infeasible(), "Pause escaped as a conclusion");
                long work = budget.searchWork(), bytes = budget.reservedBytes();
                check(match.step() && match.step(), "Paused matching moved without a quantum");
                check(work == budget.searchWork() && bytes == budget.reservedBytes(), "Polling a pause consumed resources");
                match.resume(quantum);
                check(++pauses < 100_000, "Lost continuation progress");
            }
            check(!match.infeasible(), "Candidate-only join claimed infeasibility");
            if (match.counts() != null) check(valid(model, match.counts()), "Witness escaped original coordinates");
            result = new Result(match.counts(), match.infeasible(), budget.searchWork(), pauses, budget.diagnostics());
        }
        check(budget.reservedBytes() == 0, "Matching workspace leaked");
        return result;
    }

    private static void continuation() {
        for (int seed = 0; seed < 12; seed++) {
            Model model = model(41 + seed, seed, seed % 2 == 0 ? 100 : 1100);
            Result sliced = solve(model, 1024, 53), whole = solve(model, 12_000_000, 12_000_000);
            check(sliced.counts != null && sliced.trace.contains("count_residue_join"), "Planted four-list boundary was not solved");
            check(sliced.pauses > 50, "Too few retained join handoffs");
            check(Arrays.equals(sliced.counts, whole.counts) && sliced.work == whole.work,
                    "Paused join rebuilt or discarded paid work");
        }
    }

    private static void limits() {
        long[] values = new long[48];
        for (int i = 0; i < values.length; i++) values[i] = (1L << 32) + 1009L * i * i;
        long span = Arrays.stream(values).sum(), target = span / 2;
        var shape = CountResidueJoin.Shape.create(values.length, span);
        for (int mode = 0; mode < 3; mode++) {
            var budget = budget(mode == 0 ? 100 : 20_000_000, mode == 1 ? shape.bytes() - 1 : 128L << 20);
            boolean interrupted = false;
            try (var join = new CountResidueJoin(values, target, budget, shape)) {
                if (mode == 2) {
                    while (budget.searchWork() < 100) check(!join.step(), "Expected quarter construction");
                    budget.cancel();
                }
                while (!join.step()) {}
                check(mode == 1 && join.candidate() < 0, "Refusal exposed a candidate");
            } catch (PlanningBudget.Exhausted limit) {
                check(mode == 0 && limit.limit() == PlanningBudget.Limit.SEARCH_LIMIT, "Wrong work refusal");
                interrupted = true;
            } catch (CancellationException cancelled) {
                check(mode == 2, "Unexpected cancellation");
                interrupted = true;
            }
            check(mode == 1 || interrupted, "Work/cancellation ignored");
            check(budget.reservedBytes() == 0, "Interrupted quarter lists leaked");
        }
        check(CountResidueJoin.Shape.create(53, span) == null && CountResidueJoin.Shape.create(48, Long.MAX_VALUE) == null,
                "Invalid shape admitted");
    }

    private static void scope() {
        Model base = model(48, 83, 100);
        var rows = new ArrayList<>(base.rows);
        rows.add(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.TWO, 1, BigInteger.valueOf(3)),
                base.low[0].multiply(BigInteger.TWO).add(base.low[1].multiply(BigInteger.valueOf(3))).add(BigInteger.TWO)));
        Result result = solve(new Model(rows, base.low, base.high), 12_000_000, 12_000_000);
        check(!result.trace.contains("count_residue_join") && !result.infeasible, "Extra active constraint dropped from signatures");
        BigInteger scale = BigInteger.ONE.shiftLeft(65);
        var wideRows = new ArrayList<ExactLinearProgram.Constraint>();
        // Coprime low bits keep normalization from erasing the unsafe prefix.
        var terms = new LinkedHashMap<Integer, BigInteger>();
        BigInteger target = BigInteger.ZERO;
        for (int i = 0; i < 48; i++) {
            BigInteger coefficient = scale.multiply(BigInteger.valueOf(i + 1)).add(BigInteger.ONE);
            terms.put(i, coefficient);
            target = target.add(coefficient.multiply(i % 2 == 0 ? base.low[i] : base.high[i]));
        }
        var opposite = new LinkedHashMap<Integer, BigInteger>();
        terms.forEach((id, a) -> opposite.put(id, a.negate()));
        wideRows.add(new ExactLinearProgram.Constraint(terms, target));
        wideRows.add(new ExactLinearProgram.Constraint(opposite, target.negate()));
        result = solve(new Model(wideRows, base.low, base.high), 12_000_000, 12_000_000);
        check(!result.trace.contains("count_residue_join") && !result.infeasible, "Uncertified primitive sums admitted");
    }

    private static void skewedBuckets() {
        long[] values = new long[12];
        for (int i = 0; i < 6; i++) { values[i] = 2L << i; values[i + 6] = 1000 + (1L << i); }
        var shape = CountResidueJoin.Shape.create(12, Arrays.stream(values).sum());
        var budget = budget(100_000, 32L << 20);
        try (var join = new CountResidueJoin(values, 1_000_000, budget, shape)) {
            while (!join.step()) {}
            check(join.candidate() < 0 && !budget.diagnostics().contains("truncated=0"), "Skewed-table boundary not exercised");
        }
        check(budget.reservedBytes() == 0, "Truncated table leaked");
    }

    private static void parallel() throws Exception {
        Model model = model(44, 31, 100);
        for (long work : new long[] {20_000, 2_000_000}) {
            var budget = budget(work, 64L << 20);
            var executor = Executors.newFixedThreadPool(4);
            try {
                var tasks = new ArrayList<Callable<Boolean>>();
                for (int i = 0; i < 4; i++) tasks.add(() -> {
                    try (var match = new CountMeetInMiddle(model.rows, model.low, model.high, budget, 1024).retained()) {
                        for (;;) {
                            while (!match.step()) {}
                            if (!match.paused()) break;
                            match.resume(113);
                        }
                        check(!match.infeasible(), "Parallel candidate became a negative proof");
                        if (match.counts() != null) check(valid(model, match.counts()), "Parallel reconstruction failed");
                        return match.counts() != null;
                    } catch (PlanningBudget.Exhausted limit) {
                        check(limit.limit() == PlanningBudget.Limit.SEARCH_LIMIT, "Wrong parallel limit");
                        return false;
                    }
                });
                int solved = 0;
                for (var result : executor.invokeAll(tasks)) if (result.get()) solved++;
                check(work == 20_000 ? solved < 4 : solved == 4, "Per-worker budget or failed dense join");
            } finally {
                executor.shutdownNow();
                check(executor.awaitTermination(10, TimeUnit.SECONDS), "Join workers remained live");
            }
            check(budget.searchWork() <= work + 4 && budget.reservedBytes() == 0, "Shared work/memory cap escaped");
        }
    }

    private static long sum(long[] values, long mask) {
        long result = 0;
        for (int i = 0; i < values.length; i++) if ((mask & (1L << i)) != 0) result += values[i];
        return result;
    }

    private static boolean valid(Model model, BigInteger[] point) {
        for (int i = 0; i < point.length; i++)
            if (point[i].compareTo(model.low[i]) < 0 || point[i].compareTo(model.high[i]) > 0) return false;
        for (var row : model.rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(point[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private static PlanningBudget budget(long work, long memory) {
        return new PlanningBudget(0, work, memory, () -> false, System::nanoTime);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
