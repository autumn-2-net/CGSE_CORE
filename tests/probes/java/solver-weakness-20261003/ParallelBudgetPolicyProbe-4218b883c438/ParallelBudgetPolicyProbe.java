package org.cgse.core;

import java.math.BigInteger;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class ParallelBudgetPolicyProbe {
    private static int checks;
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("check " + checks); }
    public static void main(String[] args) throws Exception {
        Random random = new Random(917123);
        long[] special = {1, 2, 3, 4, 11, 12_000_000, 20_000_000, Integer.MAX_VALUE, Long.MAX_VALUE/2, Long.MAX_VALUE-1, Long.MAX_VALUE};
        for (int sample=0; sample<2059; sample++) {
            long base = sample<special.length ? special[sample] : Math.max(1, random.nextLong() & Long.MAX_VALUE);
            long previous=base;
            for (int workers=1; workers<=64; workers++) {
                check(PlanningBudget.parallelWorkLimit(base,workers,false)==base);
                long result=PlanningBudget.parallelWorkLimit(base,workers,true);
                BigInteger expected=BigInteger.valueOf(base);
                if (workers>1) expected=expected.multiply(BigInteger.valueOf(4+Math.min(4,workers))).divide(BigInteger.valueOf(4));
                check(result==expected.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact());
                check(result>=previous && result>=base);
                previous=result;
            }
        }
        for (boolean expanded:new boolean[]{false,true}) {
            for(int workers:new int[]{1,2,3,4,8}) {
                long cap=PlanningBudget.parallelWorkLimit(4000,workers,expanded);
                PlanningBudget budget=new PlanningBudget(0,cap,4096,()->false,System::nanoTime);
                ExecutorService pool=Executors.newFixedThreadPool(workers);
                CountDownLatch start=new CountDownLatch(1);
                java.util.List<Future<?>> futures=new java.util.ArrayList<>();
                for(int i=0;i<workers;i++) futures.add(pool.submit(()->{
                    try { start.await(); for(;;) budget.check(); }
                    catch(PlanningBudget.Exhausted exhausted) { if(exhausted.limit()!=PlanningBudget.Limit.SEARCH_LIMIT) throw new AssertionError(exhausted); }
                    catch(InterruptedException interrupted) { throw new AssertionError(interrupted); }
                }));
                start.countDown();
                for(Future<?> future:futures) future.get(10,TimeUnit.SECONDS);
                pool.shutdown();check(pool.awaitTermination(10,TimeUnit.SECONDS));
                check(budget.nodes()>cap && budget.nodes()<=cap+workers);
                check(!budget.tryReserve(4097));
                check(budget.tryReserve(4096));budget.release(4096);check(budget.reservedBytes()==0);
            }
        }
        AtomicLong clock=new AtomicLong();
        PlanningBudget deadline=new PlanningBudget(1,PlanningBudget.parallelWorkLimit(4000,4,true),4096,()->false,clock::get);
        deadline.start();clock.set(2_000_000);
        try {deadline.checkpoint();throw new AssertionError("timeout bypassed");}
        catch(PlanningBudget.Exhausted exhausted){check(exhausted.limit()==PlanningBudget.Limit.TIMEOUT);}
        PlanningBudget cancelled=new PlanningBudget(0,PlanningBudget.parallelWorkLimit(4000,4,true),4096,()->false,System::nanoTime);
        cancelled.cancel();
        try {cancelled.checkpoint();throw new AssertionError("cancellation bypassed");}
        catch(CancellationException expected){checks++;}
        System.out.println("PASS budget policy checks="+checks+" concurrent_caps=10 timeout=1 cancel=1 memory=10");
    }
}
