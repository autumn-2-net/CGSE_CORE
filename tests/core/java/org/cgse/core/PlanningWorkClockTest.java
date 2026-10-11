// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

/** A worker's previous request must not choose this request's fractional slice phase. */
public final class PlanningWorkClockTest {
    private static int handoffs;
    public static void main(String[] args) throws Exception {
        foreignFractions();
        nestedRequests();
        workerOwnership();
        retainedSearch();
        check(handoffs >= 24, "Retained searches did not exercise worker migration");
        System.out.println("Request-local work clocks: foreign fractions, nested requests, separate workers and " + handoffs + " retained handoffs passed");
    }

    private static PlanningBudget budget() {
        return new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime);
    }

    private static void previousRequest(int scans) {
        var previous = budget();
        previous.threadWork();
        previous.threadSearchWork();
        for (int i = 0; i < scans; i++) previous.operation(PlanningBudget.Operation.SCAN, 0);
        previous.compilationScan();
    }

    private static void foreignFractions() {
        for (int prefix = 0; prefix < 16; prefix++) {
            previousRequest(prefix);
            var b = budget();
            long all = b.threadWork(), search = b.threadSearchWork();
            b.operation(PlanningBudget.Operation.SCAN, 0);
            eq(b.threadWork() - all, 1, "Previous request shifted a new request's first fractional charge");
            eq(b.threadSearchWork() - search, 1, "Previous search shifted fractional quota");
            for (int i = 0; i < 3; i++) b.operation(PlanningBudget.Operation.SCAN, 0);
            eq(b.threadWork() - all, 1, "Four quarter operations did not make one unit");
            b.compilationScan();
            eq(b.threadWork() - all, 2, "Compilation omitted from local total");
            eq(b.threadSearchWork() - search, 1, "Compilation entered local search quota");
            eq(b.searchWork(), 1, "Search account changed");
            eq(b.compilationWork(), 1, "Compilation account changed");
        }
    }

    private static void nestedRequests() {
        var a = budget();
        var b = budget();
        long startA = a.threadWork(), startB = b.threadWork();
        a.charge(3);
        b.charge(11);
        a.compilationCharge(7);
        b.operation(PlanningBudget.Operation.SCAN, 0);
        eq(a.threadWork() - startA, 10, "Foreign request charged into local work");
        eq(b.threadWork() - startB, 12, "Switching budgets lost the original clock");
        eq(a.threadSearchWork(), 3, "Nested compilation changed search");
        eq(b.threadSearchWork(), 12, "Nested search ticks missing");
        a.operation(PlanningBudget.Operation.SCAN, 0);
        eq(a.threadWork() - startA, 11, "Restored request inherited foreign fraction");
    }

    private static void workerOwnership() throws Exception {
        var workers = workers(4);
        var shared = budget();
        try {
            for (int round = 0; round < 4; round++) {
                var tasks = new ArrayList<Future<long[]>>();
                for (int i = 0; i < workers.length; i++) {
                    int id = i, visit = round;
                    tasks.add(workers[i].submit(() -> {
                        previousRequest(visit + id);
                        long before = shared.threadWork();
                        shared.operation(PlanningBudget.Operation.SCAN, 0);
                        shared.compilationScan();
                        return new long[] {before, shared.threadWork(), shared.threadSearchWork()};
                    }));
                }
                for (var task : tasks) {
                    var result = task.get(30, TimeUnit.SECONDS);
                    eq(result[0], (round * 2 + 3) / 4, "Worker clock inherited another worker's work");
                    eq(result[1], ((round + 1) * 2 + 3) / 4, "Worker-local combined fraction");
                    eq(result[2], (round + 4) / 4, "Worker-local search fraction");
                }
            }
            eq(shared.searchWork(), 4, "Shared request failed to aggregate worker search");
            eq(shared.compilationWork(), 4, "Shared request failed to aggregate worker compilation");
        } finally { for (var worker : workers) worker.shutdownNow(); }
    }

    private record Run(List<BigInteger> counts, boolean infeasible, long work) {}

    private static void retainedSearch() throws Exception {
        var workers = workers(2);
        try {
            for (int seed = 0; seed < 6; seed++) {
                var random = new Random(seed);
                var rows = new ArrayList<ExactLinearProgram.Constraint>();
                int n = 48;
                var planted = new BigInteger[n];
                var low = new BigInteger[n];
                var high = new BigInteger[n];
                Arrays.fill(low, BigInteger.ZERO);
                Arrays.fill(high, BigInteger.valueOf(3));
                for (int i = 0; i < n; i++) planted[i] = BigInteger.valueOf(random.nextInt(4));
                for (int r = 0; r < 24; r++) {
                    var terms = new LinkedHashMap<Integer, BigInteger>();
                    for (int i = 0; i < 7; i++) terms.put(random.nextInt(n), BigInteger.valueOf(random.nextInt(9) - 4));
                    var bound = BigInteger.ZERO;
                    for (var term : terms.entrySet()) bound = bound.add(term.getValue().multiply(planted[term.getKey()]));
                    rows.add(new ExactLinearProgram.Constraint(terms, bound));
                    var negative = new LinkedHashMap<Integer, BigInteger>();
                    terms.forEach((id, value) -> negative.put(id, value.negate()));
                    rows.add(new ExactLinearProgram.Constraint(negative, bound.negate()));
                }
                var reference = run(rows, low, high, workers, 0);
                for (int prefix = 1; prefix < 4; prefix++)
                    check(reference.equals(run(rows, low, high, workers, prefix)), "Foreign work changed a retained search trajectory: seed=" + seed);
            }
        } finally { for (var worker : workers) worker.shutdownNow(); }
    }

    private static Run run(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high,
                           ExecutorService[] workers, int prefix) throws Exception {
        var b = budget();
        try (var search = new CountLcg(rows, low, high, b, 1024).lockBranching()) {
            for (int visit = 0; visit < 128; visit++) {
                boolean resume = visit != 0;
                if (resume) handoffs++;
                boolean finished = workers[visit % workers.length].submit(() -> {
                    previousRequest(prefix);
                    if (resume) search.resume(1024);
                    while (!search.step()) {}
                    return !search.paused() || search.counts() != null || search.infeasible();
                }).get(30, TimeUnit.SECONDS);
                if (finished) break;
            }
            var counts = search.counts();
            check(!search.infeasible(), "Rejected the independent planted witness");
            if (counts != null) {
                for (int i = 0; i < counts.length; i++) check(counts[i].compareTo(low[i]) >= 0 && counts[i].compareTo(high[i]) <= 0, "Count outside domain");
                for (var row : rows) {
                    var sum = BigInteger.ZERO;
                    for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(counts[term.getKey()]));
                    check(sum.compareTo(row.upper()) <= 0, "Invalid count witness");
                }
            }
            return new Run(counts == null ? List.of() : List.of(counts), search.infeasible(), b.searchWork());
        } finally { eq(b.reservedBytes(), 0, "Migrated search leaked"); }
    }

    private static ExecutorService[] workers(int size) {
        var result = new ExecutorService[size];
        for (int i = 0; i < size; i++) result[i] = Executors.newSingleThreadExecutor();
        return result;
    }

    private static void eq(long actual, long expected, String message) { check(actual == expected, message + ": " + actual + " != " + expected); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
