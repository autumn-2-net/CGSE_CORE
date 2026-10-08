package org.cgse.core;
import java.util.*;

public final class InFlightReview {
    record Key(int id) {static long hashes;public int hashCode(){hashes++;return id;}}
    public static long[] run(int width) {
        Key shared=new Key(-1),target=new Key(-2);Map<Key,Long> initial=new LinkedHashMap<>(),finish=new LinkedHashMap<>();
        Map<String,GraphRecipe<Key>> recipes=new LinkedHashMap<>();List<PlanStep> steps=new ArrayList<>();
        for(int i=0;i<width;i++) {
            Key raw=new Key(i),side=new Key(width+i);initial.put(raw,1L);finish.put(side,2L);
            String id="r"+i;recipes.put(id,new GraphRecipe<>(id,id,List.of(new GraphRecipe.Slot<>(raw,1)),Map.of(shared,3L,side,2L)));steps.add(new PlanStep.Batch(id,1));
        }
        finish.put(shared,3L*width+4);recipes.put("finish",new GraphRecipe<>("finish","finish",finish.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),Map.of(target,1L)));steps.add(new PlanStep.Batch("finish",1));
        Map<Key,Long> reservation=new LinkedHashMap<>(initial);reservation.put(shared,4L);
        var plan=new GraphPlan<>(target,1,true,new PlanStep.Sequence(steps),recipes,reservation,Map.of(),Map.of(),GraphPlan.Result.FEASIBLE,0,0);
        var runtime=new GraphJobRuntime<>(plan,initial,Map.of(shared,4L));int[] pushed={0};
        var adapter=new GraphJobRuntime.Adapter<Key>() {
            public long capacity(GraphRecipe<Key> r,long n){return n;}
            public GraphJobRuntime.Outcome push(GraphRecipe<Key> r,long n,Map<Key,Long> in){pushed[0]++;return GraphJobRuntime.Outcome.ACCEPTED;}
            public long deliver(Key k,long n){return n;}
            public long refund(Key k,long n){return n;}
        };
        for(int tick=0;tick<10000&&pushed[0]<width;tick++)runtime.tick(adapter,tick,128);
        if(pushed[0]!=width)throw new AssertionError("Dispatch incomplete");
        Key.hashes=0;long start=System.nanoTime(),sum=0;
        for(int i=0;i<4096;i++)sum+=runtime.inFlight(shared);
        long elapsed=System.nanoTime()-start,hashes=Key.hashes;
        if(sum!=4096L*3*width)throw new AssertionError("Status total");
        if(runtime.accept(shared,Long.MAX_VALUE,true)!=3L*width+4||runtime.inFlight(shared)!=3L*width)throw new AssertionError("Simulate changed account");
        runtime.accept(shared,4L+width,false);var restored=new GraphJobRuntime<>(runtime.snapshot());
        if(restored.inFlight(shared)!=2L*width)throw new AssertionError("Partial/reload");
        for(int i=width-1;i>=0;i--)restored.accept(new Key(width+i),2,false);
        restored.accept(shared,2L*width,false);
        if(!restored.inFlight().isEmpty()||restored.inFlight(shared)!=0)throw new AssertionError("Return account");
        var completing=new GraphJobRuntime.Adapter<Key>() {
            public long capacity(GraphRecipe<Key> r,long n){return n;}
            public GraphJobRuntime.Outcome push(GraphRecipe<Key> r,long n,Map<Key,Long> in){restored.accept(target,n,false);return GraphJobRuntime.Outcome.ACCEPTED;}
            public long deliver(Key k,long n){return n;}
            public long refund(Key k,long n){return n;}
        };
        for(int tick=10000;tick<10200&&!restored.finished();tick++)restored.tick(completing,tick,64);
        if(restored.state()!=GraphJobRuntime.State.COMPLETED)throw new AssertionError("Unsettled");
        return new long[]{elapsed,hashes};
    }
    public static void main(String[] args) {
        for(int n:new int[]{1,32,256,1024,4096})run(n);
        System.out.println("PASS physical/external query separation, simulated returns, partial returns, reload, joint output settlement and completion");
    }
}
