package org.cgse.core;
import java.math.BigInteger;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

public final class BudgetSafeProbe {
    static int assertions, threaded;
    static final long SATURATED=PlanningBudget.units(Long.MAX_VALUE);
    static void check(boolean yes,String why){assertions++;if(!yes)throw new AssertionError(why);}
    static PlanningBudget budget(long work){return new PlanningBudget(0,work,64L<<20,()->false,System::nanoTime);}
    @SuppressWarnings("unchecked") static long[] clock()throws Exception{var f=PlanningBudget.class.getDeclaredField("THREAD_NODES");f.setAccessible(true);return ((ThreadLocal<long[]>)f.get(null)).get();}
    static void expectLimit(Runnable r){try{r.run();throw new AssertionError("expected search limit");}catch(PlanningBudget.Exhausted e){check(e.limit()==PlanningBudget.Limit.SEARCH_LIMIT,"wrong limit");}}
    static void ordinary(long units,int fraction)throws Exception{
        long[]clock=clock();clock[0]=units;clock[1]=fraction;var b=budget(100_000_000);long start=b.threadWork(),ticks=0;Random random=new Random(441771);
        for(int i=0;i<50000;i++){
            int kind=random.nextInt(5),bits=random.nextInt(4096),amount;
            if(kind==0){b.check();amount=16;}else if(kind==1){amount=random.nextInt(97);b.charge(amount);amount*=16;}else{
                var op=PlanningBudget.Operation.values()[kind-2];amount=b.operation(op,bits);
                int words=(int)Math.max(1,Math.min(32,(bits+63L)/64));check(amount==op.ticks*(op==PlanningBudget.Operation.SCAN?1:words),"operation weight");
            }
            ticks+=amount;long expected=units+(fraction+ticks+15)/16;
            check(b.nodes()==(ticks+15)/16,"global arithmetic");check(b.threadWork()==expected,"modulo clock");
            check(b.threadWork()-start==(fraction+ticks+15)/16-(fraction==0?0:1),"clock delta");
            check(b.remainingWork()==100_000_000-b.nodes(),"remaining");check(clock[1]>=0&&clock[1]<16,"fraction invariant");
        }
    }
    static void sticky()throws Exception{
        for(boolean bulk:new boolean[]{false,true}){
            var b=budget(Long.MAX_VALUE);long initial=b.threadWork();
            if(!bulk)b.charge(Long.MAX_VALUE/16);
            long before=b.threadWork();expectLimit(()->{if(bulk)b.charge(Long.MAX_VALUE/16+1);else b.check();});
            check(b.nodes()==SATURATED&&b.remainingWork()==0,"overflow not saturated");check(b.threadWork()==before,"rejected overflow charged thread");
            for(int i=0;i<50;i++){expectLimit(b::check);expectLimit(()->b.charge(0));expectLimit(()->b.charge(Long.MAX_VALUE/16));expectLimit(()->b.operation(PlanningBudget.Operation.SCAN,0));check(b.nodes()==SATURATED&&b.remainingWork()==0,"overflow reopened");}
            var next=budget(100);long start=next.threadWork();next.charge(47);check(next.threadWork()-start==47&&next.nodes()==47,"later request stopped clock");
        }
        var valid=budget(100);try{valid.charge(-1);throw new AssertionError("negative work accepted");}catch(IllegalArgumentException ok){}valid.check();check(valid.nodes()==1,"negative input poisoned unrelated valid work");
    }
    static void ordinaryCap(){
        var b=budget(10);b.charge(10);expectLimit(b::check);check(b.nodes()==11&&b.remainingWork()==0,"changed ordinary overshoot");expectLimit(b::check);check(b.nodes()==12,"ordinary overshoot must keep old accounting");
    }
    static void concurrent()throws Exception{
        var pool=Executors.newFixedThreadPool(8);
        try{for(int round=0;round<100;round++){
            int workers=1+(round%8),limit=41+round;var b=budget(limit);var gate=new CountDownLatch(1);var jobs=new ArrayList<Future<long[]>>();
            for(int t=0;t<workers;t++)jobs.add(pool.submit(()->{gate.await();long start=b.threadWork();int accepted=0;while(true){try{b.check();accepted++;}catch(PlanningBudget.Exhausted e){break;}}long delta=b.threadWork()-start;var next=budget(20);long after=next.threadWork();next.charge(7);return new long[]{accepted,delta,next.threadWork()-after};}));
            gate.countDown();long accepted=0,delta=0;for(var f:jobs){var v=f.get();accepted+=v[0];delta+=v[1];check(v[2]==7,"worker next-request progress");threaded++;}
            check(accepted==limit,"concurrent successful work crossed cap");check(b.nodes()==limit+workers,"concurrent charges lost");check(delta==b.nodes(),"worker clock charges lost");check(b.remainingWork()==0,"concurrent remaining");
        }
        var b=budget(Long.MAX_VALUE);b.charge(Long.MAX_VALUE/16-10);var jobs=new ArrayList<Future<Boolean>>();var gate=new CountDownLatch(1);
        for(int t=0;t<8;t++)jobs.add(pool.submit(()->{gate.await();for(int i=0;i<20;i++)try{b.charge(8);}catch(PlanningBudget.Exhausted e){return b.nodes()>=0&&b.remainingWork()==0;}return false;}));gate.countDown();for(var f:jobs)check(f.get(),"concurrent overflow");check(b.nodes()==SATURATED&&b.remainingWork()==0,"global overflow sentinel");expectLimit(b::check);
        }finally{pool.shutdownNow();check(pool.awaitTermination(5,TimeUnit.SECONDS),"executor retained");}
    }
    static void coreDeadline()throws Exception{
        var b=budget(1_000_000);var proof=new CountProof.Certificate("clock-test",1,List.of(new CountProof.Row(Map.of(0,BigInteger.ONE),BigInteger.ZERO),new CountProof.Row(Map.of(0,BigInteger.ONE.negate()),BigInteger.ONE.negate())),List.of(),List.of(),true);
        try(var core=new CountCoreMinimize(List.of(),new BigInteger[]{BigInteger.ZERO},new BigInteger[]{BigInteger.ONE},List.of(),new BitSet(),proof,b,16384)){
            Method trace=CountCoreMinimize.class.getDeclaredMethod("traceCharge",long.class);trace.setAccessible(true);
            long[]clock=clock();clock[0]=Long.MAX_VALUE-4;clock[1]=0;long start=b.threadWork(),until=start+10;
            for(int i=0;i<10;i++)trace.invoke(core,until);
            check(b.threadWork()-start==10,"deadline clock delta");
            try{trace.invoke(core,until);throw new AssertionError("deadline did not expire");}catch(InvocationTargetException e){check(e.getCause().getClass().getSimpleName().equals("TraceLimit"),"wrong trace expiry");}
        }check(b.reservedBytes()==0,"core probe leak");
    }
    public static void main(String[]args)throws Exception{
        ordinary(0,0);ordinary(Long.MAX_VALUE-10,13);ordinary(Long.MIN_VALUE+10,7);sticky();ordinaryCap();concurrent();coreDeadline();
        long[]clock=clock();clock[0]=clock[1]=0;BudgetRangeProbe.main(args);
        System.out.println("PASS assertions="+assertions+" threaded="+threaded+" weights_unchanged=true sticky_overflow=true wrap_deadline=true");
    }
}
