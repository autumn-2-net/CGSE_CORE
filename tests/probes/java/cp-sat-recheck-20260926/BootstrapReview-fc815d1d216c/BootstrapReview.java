package org.cgse.core;
import java.util.*;
import java.math.BigInteger;
public final class BootstrapReview {
    static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
    record S(Map<String,BigInteger> need,Map<String,BigInteger> delta){}
    static S summary(PlanStep p,Map<String,GraphRecipe<String>> recipes){
        if(p instanceof PlanStep.Batch b){var r=recipes.get(b.recipe());Map<String,BigInteger> need=new HashMap<>(),d=new HashMap<>();r.inputs().forEach((k,v)->{need.put(k,BigInteger.valueOf(v));d.put(k,BigInteger.valueOf(v).negate());});r.outputs().forEach((k,v)->d.merge(k,BigInteger.valueOf(v),BigInteger::add));return repeat(new S(need,d),BigInteger.valueOf(b.runs()));}
        if(p instanceof PlanStep.Repeat r)return repeat(summary(r.body(),recipes),BigInteger.valueOf(r.times()));
        Map<String,BigInteger> need=new HashMap<>(),d=new HashMap<>();for(var part:((PlanStep.Sequence)p).children()){var s=summary(part,recipes);s.need.forEach((k,v)->need.merge(k,v.subtract(d.getOrDefault(k,BigInteger.ZERO)).max(BigInteger.ZERO),BigInteger::max));s.delta.forEach((k,v)->d.merge(k,v,BigInteger::add));}return new S(need,d);
    }
    static S repeat(S s,BigInteger n){Map<String,BigInteger> need=new HashMap<>(),d=new HashMap<>();if(n.signum()==0)return new S(need,d);Set<String> keys=new HashSet<>(s.need.keySet());keys.addAll(s.delta.keySet());for(String k:keys){var v=s.delta.getOrDefault(k,BigInteger.ZERO);need.put(k,s.need.getOrDefault(k,BigInteger.ZERO).add(v.min(BigInteger.ZERO).negate().multiply(n.subtract(BigInteger.ONE))));d.put(k,v.multiply(n));}return new S(need,d);}
    static RegionSelection.Choice<String> run(List<GraphRecipe<String>> recipes,List<PlanStep> parts,long times,int expected){
        Map<String,GraphRecipe<String>> by=new LinkedHashMap<>();recipes.forEach(r->by.put(r.id(),r));var body=new PlanStep.Sequence(parts);var s=summary(body,by);Map<String,Long> seeds=new LinkedHashMap<>();Set<String> made=new HashSet<>();recipes.forEach(r->made.addAll(r.outputs().keySet()));for(String k:made)if(s.need.getOrDefault(k,BigInteger.ZERO).signum()>0&&s.delta.getOrDefault(k,BigInteger.ZERO).signum()>=0)seeds.put(k,s.need.get(k).longValueExact());
        var choice=new RegionSelection.Choice<>(body,SequenceSummary.of(body,by),BigInteger.valueOf(times),seeds);
        var budget=new PlanningBudget(10000,4_000_000,128L<<20,()->false,System::nanoTime);RegionSelection.Choice<String> result;
        try(var solve=new RegionBootstrap<>(recipes,choice,Map.of(),Set.of(),budget)){while(!solve.step()){}result=solve.result();}
        var actual=repeat(summary(result.body(),by),result.runs());var old=repeat(s,BigInteger.valueOf(times));
        for(String k:result.summary().keys()){
            var n=result.summary().required(k).add(result.summary().delta(k).min(BigInteger.ZERO).negate().multiply(result.runs().subtract(BigInteger.ONE)));
            if(!n.equals(actual.need.getOrDefault(k,BigInteger.ZERO)))throw new AssertionError("prefix summary mismatch "+k);
        }
        for(var e:old.delta.entrySet())if(e.getValue().signum()>0&&actual.delta.getOrDefault(e.getKey(),BigInteger.ZERO).compareTo(e.getValue())<0)throw new AssertionError("lost gain");
        for(var e:result.seeds().entrySet())if(actual.delta.getOrDefault(e.getKey(),BigInteger.ZERO).signum()<0)throw new AssertionError("spent seed");
        if(result.seeds().size()!=expected)throw new AssertionError("expected="+expected+" got="+result.seeds()+" trace="+budget.diagnostics());
        System.out.println("PASS recipes="+recipes.size()+" times="+times+" seeds="+seeds.size()+"->"+result.seeds().size()+" work="+budget.nodes());return result;
    }
    public static void main(String[]args){
        for(int n:new int[]{3,7,24,64})for(long amount:new long[]{1,1_000_000_000L,Long.MAX_VALUE}){
            var rs=new ArrayList<GraphRecipe<String>>();var steps=new ArrayList<PlanStep>();for(int i=0;i<n;i++){var out=new LinkedHashMap<String,Long>();out.put("S"+((i+1)%n),1L);if(i==n-1)out.put("P",1L);rs.add(r("R"+i,Map.of("S"+i,1L),out));steps.add(new PlanStep.Batch("R"+i,1));}Collections.reverse(steps);var result=run(rs,steps,amount,1);if(result.seeds().values().iterator().next()!=1)throw new AssertionError("ring needs one token");
        }
        var separate=List.of(r("a",Map.of("A",1L),Map.of("A",1L,"X",1L)),r("b",Map.of("B",1L),Map.of("B",1L,"Y",1L)),r("p",Map.of("X",1L,"Y",1L),Map.of("P",1L)));
        run(separate,List.of(new PlanStep.Batch("a",1),new PlanStep.Batch("b",1),new PlanStep.Batch("p",1)),Long.MAX_VALUE,2);
        var bootstrap=List.of(r("grow",Map.of("A",1L,"R",1L),Map.of("A",1L,"P",1L)),r("make",Map.of("R",1L),Map.of("A",1L)));
        run(bootstrap,List.of(new PlanStep.Batch("grow",1)),Long.MAX_VALUE,0);
        System.out.println("PASS independent compressed-prefix, promised gains, seed conservation, conjunctive startup and long boundaries");
    }
}
