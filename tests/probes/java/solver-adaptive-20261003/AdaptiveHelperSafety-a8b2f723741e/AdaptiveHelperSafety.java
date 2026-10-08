package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

public class AdaptiveHelperSafety {
    static int checks;
    static void expect(boolean condition) { checks++; if (!condition) throw new AssertionError("check=" + checks); }
    static Runnable charge(PlanningBudget budget) { return () -> budget.operation(PlanningBudget.Operation.SCAN, 0); }
    static java.util.function.BiConsumer<BigInteger,BigInteger> integer(PlanningBudget budget) { return (a,b) -> budget.operation(PlanningBudget.Operation.INTEGER, Math.max(a.bitLength(), b.bitLength())); }

    static void history() {
        var budget = new PlanningBudget(0, 100000, 1L << 20, () -> false, System::nanoTime);
        try (var history = new CountLcgReliability(2, budget, charge(budget), integer(budget))) {
            var point = new double[]{0.5, 0.5};
            expect(history.factor(1, 0, point) == 1);
            for (int sample = 0; sample < 2; sample++) for (boolean up : new boolean[]{false, true}) {
                history.observe(0, up, 0.5, 0, false);
                history.observe(1, up, 0.5, 20, false);
            }
            expect(history.factor(1, 0, point) == 1);
            for (int sample = 0; sample < 6; sample++) for (boolean up : new boolean[]{false, true}) {
                history.observe(0, up, 0.5, 0, false);
                history.observe(1, up, 0.5, 20, false);
            }
            expect(history.factor(1, 0, point) > 1);
            expect(history.factor(1, 0, point) <= 1.25);
            expect(history.factor(0, 1, point) == 1);
        }
        expect(budget.reservedBytes() == 0);
    }

    static void pool() {
        var budget = new PlanningBudget(0, 1000000, 1L << 20, () -> false, System::nanoTime);
        try (var pool = new CountLcgRowPool(2, 128, 2, budget, charge(budget))) {
            var sleeping = new BitSet(); var reactivated = new BitSet();
            for (int id = 2; id < 102; id++) { pool.added(id, 0); for (int j = 0; j < 4; j++) pool.observed(id, 0, 1000, 0); }
            for (int id = 2; id < 12; id++) pool.pin(id);
            for (int epoch = 0; epoch < 100; epoch++) {
                int decision = epoch * 32;
                pool.maintain(decision, id -> { sleeping.set(id); expect(id >= 12); }, id -> reactivated.set(id));
                expect(pool.active(0)); expect(pool.active(1));
                for (int id = 2; id < 12; id++) expect(pool.active(id));
            }
            expect(sleeping.cardinality() > 0);
            var lost = (BitSet) sleeping.clone(); lost.andNot(reactivated);
            expect(lost.isEmpty());
            for (int id = 2; id < 12; id++) pool.unpin(id);
            pool.restart(4000, id -> reactivated.set(id));
            for (int id = 2; id < 102; id++) expect(pool.active(id));
        }
        expect(budget.reservedBytes() == 0);
    }

    static void probes() {
        var budget = new PlanningBudget(0, 2000000, 1L << 20, () -> false, System::nanoTime);
        int n = 16;
        var low = new BigInteger[n]; var high = new BigInteger[n];
        Arrays.fill(low, BigInteger.ZERO); Arrays.fill(high, BigInteger.ONE);
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int i = 1; i < n; i++) rows.add(new ExactLinearProgram.Constraint(Map.of(0, BigInteger.ONE, i, BigInteger.ONE), BigInteger.ONE));
        try (var history = new CountLcgReliability(n, budget, charge(budget), integer(budget))) {
            for (int step = 0; step < 128; step++) history.probe(0, 0.5, step * 32, 1_000_000, rows, low, high);
            for (int i = 0; i < n; i++) { expect(low[i].equals(BigInteger.ZERO)); expect(high[i].equals(BigInteger.ONE)); }
            expect(budget.nodes() <= 1_000_000 / 64 + 512);
            expect(history.diagnostic().contains("probes=6;"));
        }
        expect(budget.reservedBytes() == 0);
    }

    static void quotas() {
        int cancelled = 0;
        for (int cutoff = 1; cutoff <= 300; cutoff++) {
            int cancelAt = cutoff;
            var counter = new AtomicInteger();
            var budget = new PlanningBudget(0, 1000000, 1L << 20, () -> counter.incrementAndGet() >= cancelAt, System::nanoTime);
            try (var history = new CountLcgReliability(16, budget, charge(budget), integer(budget));
                 var pool = new CountLcgRowPool(2, 128, 2, budget, charge(budget))) {
                for (int i = 2; i < 100; i++) { pool.added(i, 0); pool.observed(i, 0, 100, 0); }
                for (int i = 0; i < 100; i++) history.observe(i % 16, i % 2 == 0, 0.5, 3, false);
                pool.maintain(128, id -> {}, id -> {});
            } catch (CancellationException expected) { cancelled++; }
            expect(budget.reservedBytes() == 0);
        }
        expect(cancelled == 300);
        for (long memory : new long[]{1, 128, 1024, 2048, 4096, 8192}) {
            var budget = new PlanningBudget(0, 100000, memory, () -> false, System::nanoTime);
            try (var history = new CountLcgReliability(16, budget, charge(budget), integer(budget)); var pool = new CountLcgRowPool(2, 128, 2, budget, charge(budget))) {
                history.observe(0, true, 0.5, 2, false);
                pool.added(2, 0); pool.pin(2); pool.unpin(2);
            }
            expect(budget.reservedBytes() == 0);
        }
    }

    static void cachedOrder() throws Exception {
        int n = 9;
        var low = new BigInteger[n]; var high = new BigInteger[n];
        Arrays.fill(low, BigInteger.ZERO); Arrays.fill(high, BigInteger.ONE);
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int i = 0; i < n; i++) rows.add(new ExactLinearProgram.Constraint(Map.of(i,BigInteger.ONE,(i+1)%n,BigInteger.ONE),BigInteger.ONE));
        var all = new TreeMap<Integer,BigInteger>(); for (int i = 0; i < n; i++) all.put(i,BigInteger.ONE.negate());
        rows.add(new ExactLinearProgram.Constraint(all, BigInteger.valueOf(-5)));
        var budget = new PlanningBudget(0, 2000000, 64L << 20, () -> false, System::nanoTime);
        var rowField = CountLcg.class.getDeclaredField("rows"); rowField.setAccessible(true);
        var activity = CountLcg.class.getDeclaredMethod("activity",int.class); activity.setAccessible(true);
        var invalidate = CountLcg.class.getDeclaredMethod("invalidateRow",int.class); invalidate.setAccessible(true);
        try (var search = new CountLcg(rows,low,high,budget,2000000,true).learnedRelaxation()) {
            while (((List<?>)rowField.get(search)).size() == rows.size()) {
                if (search.step()) { expect(search.paused()); search.resume(65536); }
            }
            Object cache = activity.invoke(search,rows.size());
            var orderField = cache.getClass().getDeclaredField("booleanOrder"); orderField.setAccessible(true);
            Object order = orderField.get(cache);
            expect(order != null);
            long reserved = budget.reservedBytes();
            for (int i = 0; i < 1000; i++) {
                invalidate.invoke(search,rows.size());
                Object next = activity.invoke(search,rows.size());
                expect(next == cache); expect(orderField.get(next) == order); expect(budget.reservedBytes() == reserved);
            }
            do { while (!search.step()) {} if (!search.paused()) break; search.resume(65536); } while (true);
            expect(search.infeasible());
            expect(CountProof.verify(search.certificate(),2_000_000) == CountProof.Verdict.VERIFIED);
        }
        expect(budget.reservedBytes() == 0);
    }

    public static void main(String[] args) throws Exception { history(); pool(); probes(); quotas(); cachedOrder(); System.out.println("ADAPTIVE_HELPERS checks=" + checks + " leaks=0"); }
}
