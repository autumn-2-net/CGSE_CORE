package org.cgse.core;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class BudgetMemoryProbe {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static PlanningBudget budget(long timeout, long cap, AtomicLong clock) {
        return new PlanningBudget(timeout, Integer.MAX_VALUE, cap, () -> false, clock::get);
    }
    static volatile PlanningBudget shared;
    static void concurrent(boolean huge) throws Exception {
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
                            shared.reserve(huge ? Long.MAX_VALUE : id == 0 ? 101 : 1);
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
        System.out.println("concurrent huge=" + huge + " rounds=" + rounds + " invalidAcceptedRounds=" + bad +
                " acceptedExample=" + exampleAccepted + " ledgerExample=" + exampleLedger + " validSmallRejected=" + rejectedSmall.get());
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
        catch (IllegalStateException expected) { check(invalid.reservedBytes() == -1, "invalid release mutates ledger"); }
        concurrent(false);
        concurrent(true);
        System.out.println("boundaryChecks=" + checks);
    }
}
