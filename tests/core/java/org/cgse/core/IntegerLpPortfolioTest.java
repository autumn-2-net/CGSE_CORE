// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Independent finite oracle for the retained integer-LP/minimized-conflict combination. */
public final class IntegerLpPortfolioTest {
    public static void main(String[] args) throws Exception {
        admission();
        int positive = 0, negative = 0, handoffs = 0, pauses = 0;
        long cuts = 0;
        Random random = new Random(2026101113L);
        var worker = Executors.newSingleThreadExecutor();
        try {
            for (int sample = 0; sample < 180; sample++) {
                int n = 3 + random.nextInt(3);
                var low = new BigInteger[n];
                var high = new BigInteger[n];
                Arrays.fill(low, BigInteger.ZERO);
                Arrays.fill(high, BigInteger.valueOf(3));
                var rows = new ArrayList<ExactLinearProgram.Constraint>();
                var planted = new BigInteger[n];
                for (int i = 0; i < n; i++) planted[i] = BigInteger.valueOf(random.nextInt(4));
                for (int r = 0; r < 10; r++) {
                    var terms = new LinkedHashMap<Integer, BigInteger>();
                    BigInteger bound = BigInteger.valueOf(sample % 3 == 0 ? random.nextInt(19) - 9 : random.nextInt(3));
                    for (int i = 0; i < n; i++) {
                        var a = BigInteger.valueOf(random.nextInt(25) - 12);
                        if (a.signum() != 0) terms.put(i, a);
                        if (sample % 3 != 0) bound = bound.add(a.multiply(planted[i]));
                    }
                    rows.add(new ExactLinearProgram.Constraint(terms, bound));
                }
                Collections.shuffle(rows, random);
                var witnesses = new ArrayList<BigInteger[]>();
                enumerate(rows, new BigInteger[n], 0, witnesses);
                var budget = budget(2_000_000, 32L << 20);
                try (var search = new CountLcg(rows, low, high, budget, 16384, true)
                        .minimizeIntegerConflicts().integerRelaxation()) {
                    check(search.learnedRelaxationEnabled(), "Integer arm was not admitted");
                    boolean done = false;
                    while (!done) {
                        // An owned frontier may migrate between workers; it is
                        // never stepped by two workers at the same time.
                        done = worker.submit(() -> {
                            if (search.paused()) search.resume(4096);
                            while (!search.step()) {}
                            return !search.paused();
                        }).get(30, TimeUnit.SECONDS);
                        handoffs++;
                        if (!done) {
                            pauses++;
                            long work = budget.nodes(), memory = budget.reservedBytes();
                            check(search.step() && budget.nodes() == work && budget.reservedBytes() == memory,
                                    "A paused frontier consumed work or memory");
                            search.resume(131072);
                            while (!search.step()) {}
                            done = !search.paused();
                        }
                    }
                    var point = search.counts();
                    if (point != null) {
                        check(witnesses.stream().anyMatch(w -> Arrays.equals(w, point)), "Invalid auxiliary witness");
                        positive++;
                    } else {
                        check(search.infeasible() && witnesses.isEmpty(), "Local/numerical failure became a false proof");
                        negative++;
                    }
                    check(CountProof.verify(search.certificate(), 2_000_000) == CountProof.Verdict.VERIFIED,
                            "Integer LP and minimized reasons produced an invalid certificate");
                    cuts += search.cutProgress();
                }
                check(budget.reservedBytes() == 0, "Retained auxiliary leaked memory");
            }
            pauses += continuation(worker);
        } finally { worker.shutdownNow(); }
        check(positive > 80 && negative > 20 && handoffs >= 180 && pauses > 0 && cuts > 0,
                "Oracle coverage too small: " + positive + "/" + negative + "/" + handoffs + "/" + pauses + "/" + cuts);
        cancellation();
        System.out.println("Integer LP portfolio: 180 finite models; SAT=" + positive + "; UNSAT=" + negative +
                "; handoffs=" + handoffs + "; pauses=" + pauses + "; cuts=" + cuts +
                "; sparse admission, incomplete domains, limits and cancellation passed");
    }

    private static int continuation(ExecutorService worker) throws Exception {
        var low = new BigInteger[40];
        var high = new BigInteger[40];
        Arrays.fill(low, BigInteger.ZERO);
        Arrays.fill(high, BigInteger.valueOf(3));
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int r = 0; r < 120; r++) {
            var terms = new LinkedHashMap<Integer, BigInteger>();
            var bound = BigInteger.ZERO;
            for (int i = 0; i < low.length; i++) {
                var a = BigInteger.valueOf(-2 - (r + i) % 5);
                terms.put(i, a);
                bound = bound.add(a); // The all-ones vector independently proves SAT.
            }
            rows.add(new ExactLinearProgram.Constraint(terms, bound));
        }
        int pauses = 0;
        var budget = budget(4_000_000, 32L << 20);
        try (var search = new CountLcg(rows, low, high, budget, 1024).minimizeIntegerConflicts().integerRelaxation()) {
            for (;;) {
                worker.submit(() -> { while (!search.step()) {} }).get(30, TimeUnit.SECONDS);
                if (!search.paused()) break;
                pauses++;
                long work = budget.nodes(), memory = budget.reservedBytes();
                check(search.step() && budget.nodes() == work && budget.reservedBytes() == memory, "Paused LP mutated on another worker");
                search.resume(32768);
                while (!search.step()) {}
                if (!search.paused()) break;
                pauses++;
                search.resume(32768);
            }
            var point = search.counts();
            check(point != null, "Lost the planted retained witness");
            for (int i = 0; i < point.length; i++) check(point[i].compareTo(low[i]) >= 0 && point[i].compareTo(high[i]) <= 0,
                    "Retained domain escaped");
            for (var row : rows) {
                var sum = BigInteger.ZERO;
                for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(point[term.getKey()]));
                check(sum.compareTo(row.upper()) <= 0, "Retained count violates original row");
            }
        }
        check(budget.reservedBytes() == 0, "Cross-worker LP continuation leaked");
        return pauses;
    }

    private static void admission() {
        var row = new ExactLinearProgram.Constraint(Map.of(0, BigInteger.TWO, 1, BigInteger.valueOf(-3)), BigInteger.TEN);
        var low = new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO};
        var high = new BigInteger[] {BigInteger.TEN, BigInteger.TEN};
        var budget = budget(1_000_000, 16L << 20);
        check(CountIntegerLpLearning.hasBoundedIntegerSupport(List.of(row), low, high, budget), "Finite support missed");
        check(!CountIntegerLpLearning.hasBoundedIntegerSupport(List.of(row), low,
                new BigInteger[] {BigInteger.ONE, BigInteger.ONE}, budget), "Boolean portfolio changed");
        check(!CountIntegerLpLearning.hasBoundedIntegerSupport(List.of(row), low,
                new BigInteger[] {null, BigInteger.TEN}, budget), "Infinite boundary was capped for admission");
        check(!CountIntegerLpLearning.hasBoundedIntegerSupport(List.of(row), low, low, budget), "Fixed row needs no LP");
        check(!CountIntegerLpLearning.hasBoundedIntegerSupport(List.of(row), low, high, budget(65535, 16L << 20)),
                "Small request reserved a speculative LP arm");
        var sparseLow = new BigInteger[1500];
        var sparseHigh = new BigInteger[1500];
        Arrays.fill(sparseLow, BigInteger.ZERO);
        sparseHigh[0] = sparseHigh[1] = BigInteger.TEN;
        check(CountIntegerLpLearning.hasBoundedIntegerSupport(List.of(row), sparseLow, sparseHigh, budget),
                "Unrelated unbounded columns excluded complete finite support");
        var unboundedRow = new ExactLinearProgram.Constraint(Map.of(0, BigInteger.TWO, 1499, BigInteger.ONE), BigInteger.ONE);
        check(!CountIntegerLpLearning.hasBoundedIntegerSupport(List.of(unboundedRow), sparseLow, sparseHigh, budget),
                "An omitted row term was mistaken for zero");
        check(budget.reservedBytes() == 0, "Admission retained workspace");
    }

    private static void cancellation() {
        var rows = List.of(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.valueOf(-7), 1, BigInteger.TWO), BigInteger.valueOf(-13)),
                new ExactLinearProgram.Constraint(Map.of(0, BigInteger.valueOf(5), 1, BigInteger.valueOf(-3)), BigInteger.ONE));
        for (int stop = 1; stop <= 240; stop++) {
            int at = stop;
            var checks = new AtomicInteger();
            var budget = new PlanningBudget(0, 1_000_000, 16L << 20, () -> checks.incrementAndGet() >= at, System::nanoTime);
            try (var search = new CountLcg(rows, new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO},
                    new BigInteger[] {BigInteger.TEN, BigInteger.TEN}, budget, 16384)
                    .minimizeIntegerConflicts().integerRelaxation()) {
                while (!search.step()) {}
            } catch (CancellationException expected) { /* shared cancellation owns the whole request */ }
            check(budget.reservedBytes() == 0, "Cancellation leaked at " + at);
        }
        for (long work : new long[] {128, 1_000_000}) for (long memory : new long[] {4096, 16384, 65536, 262144}) {
            var budget = budget(work, memory);
            try (var search = new CountLcg(rows, new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO},
                    new BigInteger[] {BigInteger.TEN, BigInteger.TEN}, budget, 131072)
                    .minimizeIntegerConflicts().integerRelaxation()) {
                while (!search.step()) {}
                check(!search.infeasible(), "Optional refusal fabricated a negative");
            } catch (PlanningBudget.Exhausted expected) { /* unknown, never infeasible */ }
            check(budget.reservedBytes() == 0, "Small budget leaked");
        }
    }

    private static void enumerate(List<ExactLinearProgram.Constraint> rows, BigInteger[] point, int id,
                                  List<BigInteger[]> witnesses) {
        if (id < point.length) {
            for (int value = 0; value <= 3; value++) {
                point[id] = BigInteger.valueOf(value);
                enumerate(rows, point, id + 1, witnesses);
            }
            return;
        }
        for (var row : rows) {
            var value = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) value = value.add(term.getValue().multiply(point[term.getKey()]));
            if (value.compareTo(row.upper()) > 0) return;
        }
        witnesses.add(point.clone());
    }

    private static PlanningBudget budget(long work, long memory) {
        return new PlanningBudget(0, work, memory, () -> false, System::nanoTime);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
