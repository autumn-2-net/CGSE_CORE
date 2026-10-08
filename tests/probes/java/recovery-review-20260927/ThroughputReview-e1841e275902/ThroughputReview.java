package org.cgse.core;

import java.util.*;

public final class ThroughputReview {
    record Key(int id) {
        static long hashes;
        public int hashCode() { hashes++; return id; }
    }
    public static long[] run(int width) {
        Map<Key,Long> initial = new LinkedHashMap<>(), finalInputs = new LinkedHashMap<>();
        Map<String,GraphRecipe<Key>> recipes = new LinkedHashMap<>();
        List<PlanStep> steps = new ArrayList<>();
        Key target = new Key(-1);
        for (int i=0;i<width;i++) {
            Key raw = new Key(i), made = new Key(i+width);
            initial.put(raw,1L); finalInputs.put(made,1L);
            String id="r"+i;
            recipes.put(id,new GraphRecipe<>(id,id,List.of(new GraphRecipe.Slot<>(raw,1)),Map.of(made,1L)));
            steps.add(new PlanStep.Batch(id,1));
        }
        recipes.put("finish",new GraphRecipe<>("finish","finish",finalInputs.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),Map.of(target,1L)));
        steps.add(new PlanStep.Batch("finish",1));
        var plan = new GraphPlan<>(target,1,true,new PlanStep.Sequence(steps),recipes,initial,Map.of(),Map.of(),GraphPlan.Result.FEASIBLE,0,0);
        var runtime = new GraphJobRuntime<>(plan,initial,Map.of());
        long[] dispatched={0};
        var adapter = new GraphJobRuntime.Adapter<Key>() {
            public long capacity(GraphRecipe<Key> recipe,long n){return n;}
            public GraphJobRuntime.Outcome push(GraphRecipe<Key> recipe,long n,Map<Key,Long> inputs){
                if(recipe.id().equals("finish"))runtime.accept(target,1,false); else dispatched[0]++;
                return GraphJobRuntime.Outcome.ACCEPTED;
            }
            public long deliver(Key key,long n){return n;}
            public long refund(Key key,long n){return n;}
        };
        Key.hashes=0; long began=System.nanoTime();
        for(int tick=0;dispatched[0]<width && tick<100000;tick++)runtime.tick(adapter,tick,64);
        long elapsed=System.nanoTime()-began, hashes=Key.hashes;
        if(dispatched[0]!=width || runtime.expected().size()!=width)throw new AssertionError("Dispatch lost "+dispatched[0]);
        List<Key> returns=new ArrayList<>(finalInputs.keySet());Collections.shuffle(returns,new Random(93022));
        for(Key key:returns)if(runtime.accept(key,1,false)!=1)throw new AssertionError("Return lost");
        final var restored=new GraphJobRuntime<>(runtime.snapshot());
        var finish=new GraphJobRuntime.Adapter<Key>() {
            public long capacity(GraphRecipe<Key> recipe,long n){return n;}
            public GraphJobRuntime.Outcome push(GraphRecipe<Key> recipe,long n,Map<Key,Long> in){restored.accept(target,n,false);return GraphJobRuntime.Outcome.ACCEPTED;}
            public long deliver(Key key,long n){return n;}
            public long refund(Key key,long n){return n;}
        };
        for(int tick=0;tick<100&&!restored.finished();tick++)restored.tick(finish,tick+100000,64);
        if(restored.state()!=GraphJobRuntime.State.COMPLETED)throw new AssertionError("Reload stalled");
        return new long[]{elapsed,hashes};
    }
    public static void main(String[] args) {
        for(int width:new int[]{32,256,1024,4096}) {
            for(int i=0;i<4;i++)run(width);
            List<Long> times=new ArrayList<>(),work=new ArrayList<>();
            for(int i=0;i<11;i++){long[] result=run(width);times.add(result[0]);work.add(result[1]);}
            Collections.sort(times);Collections.sort(work);
            System.out.println(width+"\t"+times.get(5)/1e6+"\t"+work.get(5));
        }
    }
}
