package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Independent physical pool shared by orders, with random partial/inverted returns and reloads. */
public final class ConcurrentRuntimeReview {
    static final GraphRecipe<String> START = recipe("start",Map.of("c",1L,"raw",1L),Map.of("i",1L,"side",1L));
    static final GraphRecipe<String> END = recipe("end",Map.of("i",1L),Map.of("c",1L,"p",1L));
    static GraphRecipe<String> recipe(String id,Map<String,Long> in,Map<String,Long> out) {
        return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);
    }
    static final class World {
        final Random random;
        final List<Job> jobs=new ArrayList<>();
        final Map<String,Long> pool=new HashMap<>(),balance=new HashMap<>();
        World(long seed){random=new Random(seed);}
        void insert() {
            if(pool.isEmpty())return;
            List<String> keys=new ArrayList<>(pool.keySet());Collections.shuffle(keys,random);
            List<Job> order=new ArrayList<>(jobs);Collections.shuffle(order,random);
            for(String key:keys) {
                long available=Math.min(pool.getOrDefault(key,0L),1+random.nextInt(17));
                for(Job job:order) {
                    long accepted=job.runtime.accept(key,available,false);
                    pool.compute(key,(k,v)->v-accepted);
                    available-=accepted;
                    if(available==0)break;
                }
            }
            pool.values().removeIf(v->v==0);
        }
        void verify() {
            Map<String,Long> held=new HashMap<>(pool);
            for(Job job:jobs)job.runtime.owned().forEach((k,v)->held.merge(k,v,Long::sum));
            held.values().removeIf(v->v==0);
            Map<String,Long> wanted=new HashMap<>(balance);wanted.values().removeIf(v->v==0);
            if(!held.equals(wanted))throw new AssertionError("Physical conservation "+held+" != "+wanted);
        }
    }
    static final class Job implements GraphJobRuntime.Adapter<String> {
        final World world;
        final long amount,seeds;
        GraphJobRuntime<String> runtime;
        long delivered;
        Job(World world,int amount,int seeds) {
            this.world=world;this.amount=amount;this.seeds=seeds;
            var steps=new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("start",1),new PlanStep.Batch("end",1))),amount);
            var stock=Map.of("c",(long)seeds,"raw",(long)amount);
            var plan=new GraphPlan<>("p",amount,true,steps,Map.of("start",START,"end",END),stock,Map.of("c",(long)seeds),Map.of(),GraphPlan.Result.FEASIBLE,0,0);
            runtime=new GraphJobRuntime<>(plan,stock,Map.of());
            stock.forEach((k,v)->world.balance.merge(k,v,Long::sum));
        }
        public long capacity(GraphRecipe<String> recipe,long requested){return world.random.nextInt(9)==0?0:Math.min(requested,1+world.random.nextInt(17));}
        public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe,long runs,Map<String,Long> inputs){
            if(world.random.nextInt(13)==0)return GraphJobRuntime.Outcome.REJECTED;
            inputs.forEach((k,v)->world.balance.merge(k,-v,Long::sum));
            recipe.executionOutputs().forEach((k,v)->{world.balance.merge(k,v*runs,Long::sum);world.pool.merge(k,v*runs,Long::sum);});
            if(world.random.nextBoolean())world.insert();
            return GraphJobRuntime.Outcome.ACCEPTED;
        }
        public long deliver(String key,long amount){long accepted=Math.min(amount,1+world.random.nextInt(19));delivered+=accepted;world.balance.merge(key,-accepted,Long::sum);return accepted;}
        public long refund(String key,long amount){long accepted=world.random.nextInt(8)==0?0:Math.min(amount,1+world.random.nextInt(19));world.balance.merge(key,-accepted,Long::sum);return accepted;}
    }
    public static void main(String[] args) {
        largeSharedGrowth();
        int completed=0;
        for(int trial=0;trial<300;trial++) {
            World world=new World(390422+trial);
            int count=2+world.random.nextInt(3);
            for(int tick=0;tick<4000;tick++) {
                if(tick%7==0&&world.jobs.size()<count)world.jobs.add(new Job(world,40+world.random.nextInt(70),1+world.random.nextInt(8)));
                world.insert();
                for(Job job:world.jobs){
                    job.runtime.tick(job,tick,1+world.random.nextInt(8));
                    if(world.random.nextInt(6)==0)job.runtime=new GraphJobRuntime<>(job.runtime.snapshot());
                    world.verify();
                    if(job.runtime.state()==GraphJobRuntime.State.NEEDS_ATTENTION)throw new AssertionError("Unexpected uncertainty "+job.runtime.reason());
                }
                if(world.jobs.size()==count&&world.jobs.stream().allMatch(j->j.runtime.finished()))break;
            }
            for(Job job:world.jobs) {
                if(job.runtime.state()!=GraphJobRuntime.State.COMPLETED||job.delivered!=job.amount)throw new AssertionError("Stalled "+trial+" "+job.runtime.reason());
                if(!job.runtime.snapshot().acceptedRuns().equals(Map.of("start",BigInteger.valueOf(job.amount),"end",BigInteger.valueOf(job.amount))))throw new AssertionError("Duplicate or omitted work");
                completed++;
            }
            if(!world.pool.isEmpty())throw new AssertionError("Lost physical returns");
        }
        System.out.println("PASS: "+completed+" interleaved orders in 300 worlds, randomized reloads, byproduct/catalyst return order, rejected pushes, partial settlement and exact conservation");
    }

    static void largeSharedGrowth() {
        GraphRecipe<String> grow=recipe("grow",Map.of("c",1L,"raw",1L),Map.of("c",2L));
        PlanStep program=new PlanStep.Batch("grow",1);
        for(int i=0;i<60;i++)program=new PlanStep.Sequence(List.of(program,program));
        long amount=1L<<60;
        var plan=new GraphPlan<>("c",amount,true,program,Map.of("grow",grow),Map.of("c",1L,"raw",amount),Map.of("c",1L),Map.of(),GraphPlan.Result.FEASIBLE,0,0);
        GraphJobRuntime<String>[] running=new GraphJobRuntime[]{new GraphJobRuntime<>(plan,plan.initial(),Map.of())};
        long[] totals=new long[3];
        var adapter=new GraphJobRuntime.Adapter<String>(){
            public long capacity(GraphRecipe<String> recipe,long requested){return requested;}
            public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe,long runs,Map<String,Long> inputs){
                totals[0]++;
                if(running[0].accept("c",runs*2,false)!=runs*2)throw new AssertionError("Synchronous long return lost");
                return GraphJobRuntime.Outcome.ACCEPTED;
            }
            public long deliver(String key,long n){totals[1]+=n;return n;}
            public long refund(String key,long n){totals[2]+=n;return n;}
        };
        for(int tick=0;tick<100&&!running[0].finished();tick++){
            running[0].tick(adapter,tick,8);
            running[0]=new GraphJobRuntime<>(running[0].snapshot());
        }
        if(running[0].state()!=GraphJobRuntime.State.COMPLETED||totals[0]>61||totals[1]!=amount||totals[2]!=1)
            throw new AssertionError("Shared growth batching "+Arrays.toString(totals)+" "+running[0].state());
        System.out.println("PASS: shared 2^60-run growth completed in "+totals[0]+" provider pushes with exact delivery, seed return and reloads");
    }
}
