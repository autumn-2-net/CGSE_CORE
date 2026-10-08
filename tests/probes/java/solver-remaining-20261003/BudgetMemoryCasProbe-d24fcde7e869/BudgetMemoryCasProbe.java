package org.cgse.core;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class BudgetMemoryCasProbe {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static PlanningBudget budget(long timeout, long cap, AtomicLong clock) {
        return new PlanningBudget(timeout, Integer.MAX_VALUE, cap, () -> false, clock::get);
    }
    static volatile PlanningBudget shared;
    static void concurrent(boolean huge, boolean oversized) throws Exception {
        int workers = huge ? 3 : 2, rounds = 100000;
        CyclicBarrier begin = new CyclicBarrier(workers + 1), end = new CyclicBarrier(workers + 1);
        AtomicInteger accepted = new AtomicInteger(), rejectedSmall = new AtomicInteger();
        Thread[] threads = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            int id = i;
            threads[i] = new Thread(() -> {
                try {
                    for (int round = 0; round < rounds; round++) {
                        begin.await();
                        try {
                            shared.reserve(huge ? Long.MAX_VALUE : id == 0 ? oversized ? Long.MAX_VALUE : 101 : 1);
                            accepted.incrementAndGet();
                        } catch (PlanningBudget.Exhausted expected) {
                            if (!huge && id == 1) rejectedSmall.incrementAndGet();
                        }
                        end.await();
                    }
                } catch (Throwable error) { throw new RuntimeException(error); }
            });
            threads[i].start();
        }
        int bad = 0;
        long exampleLedger = 0;
        int exampleAccepted = 0;
        for (int round = 0; round < rounds; round++) {
            shared = budget(0, huge ? Long.MAX_VALUE : 100, new AtomicLong());
            accepted.set(0);
            begin.await();
            end.await();
            if (huge && (accepted.get() > 1 || shared.reservedBytes() < 0)) {
                bad++;
                exampleLedger = shared.reservedBytes();
                exampleAccepted = accepted.get();
            }
        }
        for (Thread thread : threads) thread.join();
        check(bad == 0 && rejectedSmall.get() == 0, "concurrent admission invariant");
        System.out.println("concurrent huge=" + huge + " rounds=" + rounds + " invalidAcceptedRounds=" + bad +
                " acceptedExample=" + exampleAccepted + " ledgerExample=" + exampleLedger + " validSmallRejected=" + rejectedSmall.get());
    }
    static void randomHeld(long cap) throws Exception {
        int rounds = 5000, workers = 4;
        CyclicBarrier begin = new CyclicBarrier(workers + 1), end = new CyclicBarrier(workers + 1);
        long[] held = new long[workers];
        AtomicInteger errors = new AtomicInteger();
        PlanningBudget budget = budget(0, cap, new AtomicLong());
        Thread[] threads = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            int id = i;
            threads[i] = new Thread(() -> {
                java.util.Random random = new java.util.Random(1000 + id);
                try {
                    for (int round = 0; round < rounds; round++) {
                        begin.await();
                        if (random.nextBoolean() && held[id] > 0) {
                            long amount = random.nextBoolean() ? held[id] : Math.min(held[id], random.nextInt(1000));
                            budget.release(amount);
                            held[id] -= amount;
                        } else {
                            long amount = random.nextInt(4) == 0 ? cap : random.nextInt(1000);
                            try {
                                boolean accepted;
                                if (random.nextBoolean()) accepted = budget.tryReserve(amount);
                                else { budget.reserve(amount); accepted = true; }
                                if (accepted) held[id] = Math.addExact(held[id], amount);
                            } catch (PlanningBudget.Exhausted expected) {}
                        }
                        end.await();
                    }
                } catch (Throwable failure) { errors.incrementAndGet(); begin.reset(); end.reset(); }
            });
            threads[i].start();
        }
        for (int round = 0; round < rounds; round++) {
            begin.await();
            end.await();
            java.math.BigInteger total = java.math.BigInteger.ZERO;
            for (long amount : held) total = total.add(java.math.BigInteger.valueOf(amount));
            check(errors.get() == 0 && total.signum() >= 0 && total.compareTo(java.math.BigInteger.valueOf(cap)) <= 0 &&
                    total.equals(java.math.BigInteger.valueOf(budget.reservedBytes())), "random held ledger");
        }
        for (Thread thread : threads) thread.join();
        for (long amount : held) budget.release(amount);
        check(budget.reservedBytes() == 0 && budget.peakBytes() <= cap, "random close");
        System.out.println("randomHeld cap=" + cap + " rounds=" + rounds + " workers=" + workers + " close=0");
    }
    public static void main(String[] args) throws Exception {
        long memory = Integer.MAX_VALUE * (1L << 20);
        check(memory == 2251799812636672L, "IntMAX MiB conversion");
        var clock = new AtomicLong(Long.MAX_VALUE - 1234);
        var b = budget(Integer.MAX_VALUE, memory, clock);
        b.checkpoint();
        long timeout = Integer.MAX_VALUE * 1000000L;
        clock.addAndGet(timeout - 1);
        b.checkpoint();
        clock.incrementAndGet();
        try { b.checkpoint(); throw new AssertionError("timeout missing"); }
        catch (PlanningBudget.Exhausted expected) { check(expected.limit() == PlanningBudget.Limit.TIMEOUT, "timeout kind"); }
        var m = budget(0, Long.MAX_VALUE, new AtomicLong());
        check(m.tryReserve(Long.MAX_VALUE), "reserve LongMAX exact");
        check(!m.tryReserve(1), "try reserve overflow declines");
        try { m.reserve(1); throw new AssertionError("overflow missing"); }
        catch (PlanningBudget.Exhausted expected) { check(m.reservedBytes() == Long.MAX_VALUE, "failed reserve rollback"); }
        m.release(Long.MAX_VALUE);
        check(m.reservedBytes() == 0 && m.peakBytes() == Long.MAX_VALUE, "large lifecycle");
        budget(Long.MAX_VALUE / 1000000L, 1, new AtomicLong());
        try { budget(Long.MAX_VALUE / 1000000L + 1, 1, new AtomicLong()); throw new AssertionError("timeout overflow missing"); }
        catch (ArithmeticException expected) { checks++; }
        try { budget(Long.MAX_VALUE, 1, new AtomicLong()); throw new AssertionError("LongMAX timeout missing"); }
        catch (ArithmeticException expected) { checks++; }
        var invalid = budget(0, 100, new AtomicLong());
        try { invalid.release(1); throw new AssertionError("unbalanced release missing"); }
        catch (IllegalStateException expected) { check(invalid.reservedBytes() == 0, "over release leaves ledger"); }
        invalid.reserve(20);
        for (long amount : new long[] {-1, Long.MIN_VALUE, 21, Long.MAX_VALUE}) {
            try { invalid.release(amount); throw new AssertionError("invalid release missing"); }
            catch (IllegalStateException expected) { check(invalid.reservedBytes() == 20, "invalid release preserves existing balance"); }
        }
        invalid.release(20);
        check(invalid.reservedBytes() == 0, "valid release after invalid attempts");
        concurrent(false, false);
        concurrent(false, true);
        concurrent(true, false);
        randomHeld(4096);
        randomHeld(Long.MAX_VALUE);
        System.out.println("boundaryChecks=" + checks);
    }
}
