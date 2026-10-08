package org.cgse.core;

import java.util.*;

public final class HandoffReview {
    static GraphRecipe<String> recipe(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
    static String accounts(GraphJobRuntime<String> runtime){var s=runtime.snapshot();return s.state()+":"+new TreeMap<>(s.owned())+":"+new TreeMap<>(s.expected())+":"+new TreeMap<>(s.uncertainInputs())+":"+new TreeMap<>(s.acceptedRuns())+":"+s.obligations().flights().stream().map(f->f.id()+"/"+f.recipe()+"/"+new TreeMap<>(f.remaining())+"/"+f.ambiguous()).toList();}
    public static String run(int seed) {
        Random random=new Random(seed);long n=1+random.nextInt(1024);
        var a=recipe("a",Map.of("A",1L),Map.of("X",1L,"Z",1L));
        var b=recipe("b",Map.of("B",1L),Map.of("X",1L,"Y",1L));
        var end=recipe("end",Map.of("X",2L,"Y",1L,"Z",1L),Map.of("P",1L));
        var plan=new GraphPlan<>("P",n,true,new PlanStep.Sequence(List.of(new PlanStep.Batch("a",n),new PlanStep.Batch("b",n),new PlanStep.Batch("end",n))),Map.of("a",a,"b",b,"end",end),Map.of("A",n,"B",n),Map.of(),Map.of(),GraphPlan.Result.FEASIBLE,0,0);
        var runtime=new GraphJobRuntime<>(plan,plan.initial(),Map.of());List<String> states=new ArrayList<>();
        int mode=seed%5;
        var adapter=new GraphJobRuntime.Adapter<String>() {
            public long capacity(GraphRecipe<String> r,long count){return count;}
            public GraphJobRuntime.Outcome push(GraphRecipe<String> r,long count,Map<String,Long> in){
                if(!r.id().equals("b"))return GraphJobRuntime.Outcome.ACCEPTED;
                for(String key:List.of("Z","X","Y")) {
                    long request=key.equals("X")?n+(mode==1||mode==3?n:0):n;
                    if(key.equals("Y")&&mode==0)request=0;
                    long simulated=runtime.accept(key,request,true),accepted=runtime.accept(key,request,false);
                    if(simulated!=accepted)throw new AssertionError("Simulation differed");
                    if(runtime.waiting(key)==0&&runtime.accept(key,1,true)!=0)throw new AssertionError("Tombstone lost");
                }
                var saved=runtime.snapshot();var restored=new GraphJobRuntime<>(saved);
                if(restored.state()!=GraphJobRuntime.State.NEEDS_ATTENTION)throw new AssertionError("Ambiguous handoff not retained");
                states.add(accounts(restored));
                if(mode==4)runtime.cancel();
                if(mode==3)throw new IllegalStateException("after handoff");
                return mode==0||mode==1?GraphJobRuntime.Outcome.REJECTED:GraphJobRuntime.Outcome.ACCEPTED;
            }
            public long deliver(String k,long count){return count;}
            public long refund(String k,long count){return count;}
        };
        for(int tick=0;tick<4 && states.isEmpty();tick++)runtime.tick(adapter,tick,1);
        if(states.isEmpty())throw new AssertionError("Second handoff never reached");
        states.add(accounts(runtime));var restored=new GraphJobRuntime<>(runtime.snapshot());states.add(accounts(restored));
        return states.toString();
    }
    public static void main(String[] args){for(int i=0;i<1000;i++)run(i);System.out.println("PASS: 1000 handoffs with older returns, shared output, tombstones, simulation, synchronous saves, rejections, throws and cancellation");}
}
