package org.cgse.core;

import java.util.List;
import java.util.concurrent.*;

public final class GlobalPlanningMemoryTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static PlanningBudget budget() { return new PlanningBudget(0, 10000, 1000, () -> false, System::nanoTime); }
    public static void main(String[] args) throws Exception {
        var pool = new PlanningMemory(100);
        var a = budget().memory(pool); var b = budget().memory(pool);
        a.reserve(60);
        check(!b.tryReserve(50) && b.reservedBytes() == 0 && pool.used() == 60, "Failed optional admission debited another request");
        try { b.reserve(50); throw new AssertionError("Global cap bypassed"); }
        catch (PlanningBudget.Exhausted expected) { check(expected.limit() == PlanningBudget.Limit.MEMORY_LIMIT, "Wrong limit"); }
        a.release(20); b.reserve(60);
        var fallback = budget().shareMemory(a);
        a.cancel(); b.release(20); fallback.reserve(20);
        check(pool.used() == 100, "Fallback escaped global account");
        a.closeMemory(); a.closeMemory();
        check(pool.used() == 40, "Parent disposal failed to release fallback workspace exactly once");
        fallback.release(20); b.closeMemory(); check(pool.used() == 0, "Late cleanup double-released memory");
        try (var scheduler = new PlanningScheduler(2, 4, 1, 1_000_000)) {
            var request = budget().memory(pool);
            var done = scheduler.submit(new PlanningScheduler.Work<Integer>() {
                public boolean advance(PlanningScheduler.Slice slice) { request.reserve(75); return true; }
                public Integer result() { return 1; }
            }, request);
            check(done.get(5, TimeUnit.SECONDS) == 1, "Scheduled job failed");
        }
        check(pool.used() == 0, "Scheduler retained completed workspace");
        var workers = Executors.newFixedThreadPool(4);
        try {
            var jobs = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 4; i++) jobs.add(workers.submit(() -> {
                var request = budget().memory(pool);
                try { for (int n = 0; n < 5000; n++) if (request.tryReserve(37)) {
                    check(pool.used() <= 100, "Concurrent global memory exceeded cap"); request.release(37);
                }} finally { request.closeMemory(); }
            }));
            for (var job : jobs) job.get(10, TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); }
        check(pool.used() == 0, "Concurrent cancellation leaked account");
        System.out.println("GlobalPlanningMemoryTest PASS");
    }
}
