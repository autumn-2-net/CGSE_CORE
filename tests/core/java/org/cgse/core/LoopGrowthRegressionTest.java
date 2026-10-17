package org.cgse.core;

import java.util.*;

/** Productive cycles must grow beyond an earlier provider cap, with exact delayed physical returns. */
public final class LoopGrowthRegressionTest {
    static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    public static void main(String[] args) {
        for (boolean nested : List.of(false, true)) for (boolean capped : List.of(false, true)) run(nested,capped);
    }
    static void run(boolean nested, boolean capped) {
        long amount = 1_048_576;
        var a = recipe("a",Map.of("A",1L,"R",1L),Map.of("B",1L));
        var b = recipe("b",Map.of("B",1L),Map.of("A",2L,"P",1L));
        PlanStep body = new PlanStep.Sequence(List.of(new PlanStep.Batch("a",1),new PlanStep.Batch("b",1)));
        PlanStep steps = nested ? new PlanStep.Repeat(new PlanStep.Repeat(body,16),amount/16) : new PlanStep.Repeat(body,amount);
        var plan = new GraphPlan<>("P", amount, true, steps, Map.of("a",a,"b",b),Map.of("A",1L,"R",amount),Map.of("A",1L),Map.of(),GraphPlan.Result.FEASIBLE,0,0);
        var runtime = new GraphJobRuntime<>(plan,plan.initial(),Map.of());
        class Machines implements GraphJobRuntime.Adapter<String> {
            final Map<String,Long> stock=new HashMap<>(plan.initial());
            final List<Map<String,Long>> returns=new ArrayList<>();
            long delivered, refundedA, peak, tick;
            public long capacity(GraphRecipe<String> r,long requested){return capped&&tick<20?Math.min(7,requested):requested;}
            public GraphJobRuntime.Outcome push(GraphRecipe<String> r,long runs,Map<String,Long> inputs){
                inputs.forEach((k,v)->{long left=stock.getOrDefault(k,0L)-v;if(left<0)throw new AssertionError("Spent future returns");stock.put(k,left);});
                var output=new HashMap<String,Long>();r.outputs().forEach((k,v)->output.put(k,Math.multiplyExact(v,runs)));returns.add(output);
                peak=Math.max(peak,runs);return GraphJobRuntime.Outcome.ACCEPTED;
            }
            public long deliver(String k,long n){if(!k.equals("P"))throw new AssertionError();delivered+=n;stock.merge(k,-n,Long::sum);return n;}
            public long refund(String k,long n){stock.merge(k,-n,Long::sum);if(k.equals("A"))refundedA+=n;return n;}
            void receive(){for(var output:returns)output.forEach((k,n)->{stock.merge(k,n,Long::sum);if(runtime.accept(k,n,false)!=n)throw new AssertionError("Unowned return");});returns.clear();}
        }
        var machines=new Machines();
        for(int tick=0;tick<1000&&!runtime.finished();tick++){machines.tick=tick;machines.receive();runtime.tick(machines,tick,64,()->0L);}
        if(!runtime.finished()||machines.delivered!=amount||machines.refundedA!=amount+1||machines.peak<32768)
            throw new AssertionError("Growth plateau nested="+nested+" capped="+capped+" peak="+machines.peak+" delivered="+machines.delivered+" state="+runtime.state());
        System.out.println("Loop growth nested="+nested+" capped="+capped+" peak="+machines.peak+" ticks="+machines.tick);
    }
}
