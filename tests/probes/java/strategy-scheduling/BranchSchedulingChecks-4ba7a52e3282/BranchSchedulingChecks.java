package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class BranchSchedulingChecks {
    static int checks;
    static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out) {
        return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);
    }
    static void check(boolean ok,String msg){checks++;if(!ok)throw new AssertionError(msg);}
    static PlanningBudget budget(){return new PlanningBudget(0,8_000_000,128L<<20,()->false,System::nanoTime);}
    static List<GraphRecipe<String>> competing(boolean repair) {
        var recipes=new ArrayList<>(List.of(r("join",Map.of("B",1L,"C",1L),Map.of("A",2L,"P",1L)),
                r("left",Map.of("A",1L),Map.of("B",1L)),r("right",Map.of("A",1L),Map.of("C",1L))));
        if(repair)recipes.add(r("repair",Map.of("F",1L),Map.of("A",1L)));
        return recipes;
    }
    static class Work implements PlanningScheduler.Work<GraphPlan<String>> {
        final IntegerCountSearch<String> search;
        final AtomicInteger closed=new AtomicInteger();
        Work(List<GraphRecipe<String>> recipes,long n,Map<String,Long> stock,PlanningBudget budget){
            search=new IntegerCountSearch<>(new GraphCompiler<>(recipes),"P",n,stock,Map.of(),Set.of(),Set.of(),false,true,budget,System.nanoTime());
        }
        public boolean advance(PlanningScheduler.Slice slice){while(slice.next())if(search.step(slice))return true;else if(search.waitingFor()!=null)return false;return false;}
        public GraphPlan<String> result(){return search.result();}
        public CompletableFuture<?> waitingFor(){return search.waitingFor();}
        public GraphPlan<String> limited(PlanningBudget.Exhausted limit){return search.result();}
        public void close(){closed.incrementAndGet();search.close();}
    }
    static GraphPlan<String> run(PlanningScheduler scheduler,String name,List<GraphRecipe<String>> recipes,long n,Map<String,Long> stock)throws Exception{
        var b=budget();b.enableMetrics();var w=new Work(recipes,n,stock,b);
        var plan=scheduler.submit(w,b).get(30,TimeUnit.SECONDS);
        for(int spin=0;spin<5000&&w.closed.get()==0;spin++)Thread.sleep(1);
        awaitReleased(b);
        check(w.closed.get()==1,"close exactly once "+name);
        if(plan!=null){PlanVerifier.verify(plan);check(plan.initialExact().entrySet().stream().allMatch(e->e.getValue().compareTo(BigInteger.valueOf(stock.getOrDefault(e.getKey(),0L)))<=0),"unfunded "+name);}
        System.out.println("BRANCH "+name+" plan="+(plan==null?null:plan.patternTimesExact())+" infeasible="+w.search.infeasible()+" trace="+b.diagnostics());
        return plan;
    }
    static void awaitReleased(PlanningBudget b)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(b.reservedBytes()!=0&&System.nanoTime()<until)Thread.sleep(1);
        check(b.reservedBytes()==0,"memory leak "+b.reservedBytes());
    }
    public static void main(String[]args)throws Exception{
        var b=budget();var model=RecipeCountModel.create(new GraphCompiler<>(competing(true)),"P",100,Map.of("A",1L,"F",1L),Map.of(),Set.of(),Set.of(),true,b);
        var vector=new BigInteger[model.recipes.size()];for(int i=0;i<vector.length;i++)vector[i]=model.recipes.get(i).id().equals("repair")?BigInteger.ZERO:BigInteger.valueOf(100);
        var schedule=new CountSchedule<>(model,vector,b);while(!schedule.step()){}
        check(schedule.result()==CountSchedule.Result.UNKNOWN,"fixture must be unresolved, not a proof");schedule.close();model.close();awaitReleased(b);
        // A constructor interrupted before its owner receives it must release its workspace.
        var checkpoints=new AtomicInteger();var constructing=new PlanningBudget(0,100000,16L<<20,()->checkpoints.incrementAndGet()>=3,System::nanoTime);
        try{new ExactLinearProgram(1,List.of(new ExactLinearProgram.Constraint(Map.of(0,BigInteger.ONE),BigInteger.TEN),new ExactLinearProgram.Constraint(Map.of(0,BigInteger.ONE.negate()),BigInteger.ZERO)),new BigInteger[]{BigInteger.ONE.negate()},constructing);throw new AssertionError("constructor was not cancelled");}
        catch(CancellationException expected){check(constructing.reservedBytes()==0,"constructor cancellation leaked LP memory");}
        for(int threads:new int[]{1,4,8,16})try(var scheduler=new PlanningScheduler(threads,8,512,1_000_000)){
            for(long n:new long[]{100,Long.MAX_VALUE}){
                var p=run(scheduler,"unknown-sibling-"+threads+"-"+n,competing(true),n,Map.of("A",1L,"F",1L));
                check(p!=null&&p.patternTimesExact().getOrDefault("repair",BigInteger.ZERO).equals(BigInteger.ONE),"missed repair sibling");
            }
            var cheaper=List.of(r("expensive",Map.of("R",3L),Map.of("P",2L)),r("cheap",Map.of("R",1L),Map.of("P",1L)));
            var p=run(scheduler,"improve-incumbent-"+threads,cheaper,2,Map.of("R",3L));
            check(p.initialExact().equals(Map.of("R",BigInteger.TWO)),"kept expensive incumbent");
            var mixed=List.of(r("x",Map.of("X",1L),Map.of("P",1L)),r("y",Map.of("Y",1L),Map.of("P",1L)));
            p=run(scheduler,"mixed-sources-"+threads,mixed,2,Map.of("X",1L,"Y",1L));
            check(p.patternTimesExact().equals(Map.of("x",BigInteger.ONE,"y",BigInteger.ONE)),"lost mixed source");
            var unlike=List.of(r("item",Map.of("item",1L),Map.of("P",1L)),r("fluid",Map.of("fluid",1000L),Map.of("P",1L)));
            p=run(scheduler,"unlike-units-"+threads,unlike,1,Map.of("item",1L,"fluid",1000L));check(p!=null,"unlike units");
        }
        // UNKNOWN must survive exhaustion of all retained/limited candidates.
        b=budget();var w=new Work(competing(false),100,Map.of("A",1L),b);while(!w.search.step()){}
        check(w.search.result()==null&&!w.search.infeasible(),"unresolved vector learned as impossible");w.close();awaitReleased(b);
        // Thousands of work charges performed by another worker must not be charged to this branch again.
        b=budget();final var shared=b;long before=b.threadWork();var pool=Executors.newFixedThreadPool(4);
        var counts=new ArrayList<Future<Long>>();for(int i=0;i<4;i++)counts.add(pool.submit(()->{long prior=shared.threadWork();for(int j=0;j<10000;j++)shared.check();return shared.threadWork()-prior;}));
        long sum=0;for(var f:counts)sum+=f.get();pool.shutdown();check(sum==40000&&b.nodes()==40000&&b.threadWork()==before,"work refund/double charge");
        // A deadline while improving an incumbent returns that verified plan.
        var clock=new AtomicLong();var timed=new PlanningBudget(1,1_000_000,128L<<20,()->false,clock::get);
        var timedWork=new Work(List.of(r("expensive",Map.of("R",3L),Map.of("P",2L)),r("cheap",Map.of("R",1L),Map.of("P",1L))),2,Map.of("R",3L),timed){
            @Override public boolean advance(PlanningScheduler.Slice slice){
                while(slice.next()){
                    if(search.step(slice))return true;
                    if(search.waitingFor()!=null)return false;
                    if(search.result()!=null)clock.set(2_000_000);
                }return false;
            }
        };
        try(var scheduler=new PlanningScheduler(1,8,128,1_000_000)){
            var plan=scheduler.submit(timedWork,timed).get(5,TimeUnit.SECONDS);check(plan!=null,"deadline discarded incumbent");PlanVerifier.verify(plan);awaitReleased(timed);
        }
        // A failed child must return to the owner; it may retain a valid sibling.
        var forkBudget=budget();var answer=new AtomicInteger();var cleaned=new AtomicInteger();
        var forkWork=new PlanningScheduler.Work<Integer>(){
            CompletableFuture<?> wave;
            public boolean advance(PlanningScheduler.Slice slice){
                if(wave==null){wave=slice.fork(PlanningBudget.Phase.SOLVE,List.of(()->{answer.set(42);return 42;},()->{throw new PlanningBudget.Exhausted(PlanningBudget.Limit.TIMEOUT);}));return false;}
                wave.join();return true;
            }
            public CompletableFuture<?> waitingFor(){return wave;}
            public Integer result(){return answer.get();}
            public Integer limited(PlanningBudget.Exhausted limit){return answer.get();}
            public void close(){cleaned.incrementAndGet();}
        };
        try(var scheduler=new PlanningScheduler(2,8,32,1_000_000)){
            check(scheduler.submit(forkWork,forkBudget).get(5,TimeUnit.SECONDS)==42,"failed child bypassed incumbent recovery");
            for(int spin=0;spin<5000&&cleaned.get()==0;spin++)Thread.sleep(1);check(cleaned.get()==1,"fork cleanup");
        }
        // Cancel or shut down while branch waves are queued/running.
        for(int i=0;i<40;i++){
            b=budget();w=new Work(competing(false),Long.MAX_VALUE,Map.of("A",1L),b);
            var scheduler=new PlanningScheduler(4,8,64,100_000);var future=scheduler.submit(w,b);
            if(i%2==0)future.cancel(false);else Thread.sleep(1);
            scheduler.close();awaitReleased(b);check(w.closed.get()==1,"cancel/shutdown cleanup");
        }
        System.out.println("BRANCH_SCHEDULING_PASS "+checks);
    }
}
