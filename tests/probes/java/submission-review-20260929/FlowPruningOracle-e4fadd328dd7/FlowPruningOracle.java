package org.cgse.core;
import java.math.BigInteger;
import java.util.*;

public class FlowPruningOracle {
    static long checks, changed;
    static GraphRecipe<String> r(String id, Map<String,Long> in, Map<String,Long> out) {
        return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);
    }
    static void check(boolean b,Object detail){if(!b)throw new AssertionError(detail);checks++;}
    static void walk(PlanStep step,Map<String,GraphRecipe<String>> recipes,Map<String,Long> state,Map<String,Long> minimum) {
        if(step instanceof PlanStep.Batch b) for(long i=0;i<b.runs();i++) {
            var r=recipes.get(b.recipe());
            r.inputs().forEach((k,n)->{long after=state.merge(k,-n,Long::sum);minimum.merge(k,after,Math::min);});
            r.outputs().forEach((k,n)->state.merge(k,n,Long::sum));
        } else if(step instanceof PlanStep.Repeat r) for(long i=0;i<r.times();i++)walk(r.body(),recipes,state,minimum);
        else for(var child:((PlanStep.Sequence)step).children())walk(child,recipes,state,minimum);
    }
    static PlanStep verify(PlanStep original,Map<String,GraphRecipe<String>> recipes) {
        var budget=new PlanningBudget(0,1_000_000,()->false);
        var replacement=PlanFlowPruning.optimize(original,recipes,budget);
        check(budget.reservedBytes()==0,"optimization leaked its workspace");
        if(replacement!=original) changed++;
        var oldEnd=new HashMap<String,Long>();var oldLow=new HashMap<String,Long>();
        var newEnd=new HashMap<String,Long>();var newLow=new HashMap<String,Long>();
        walk(original,recipes,oldEnd,oldLow);walk(replacement,recipes,newEnd,newLow);
        Set<String> keys=new HashSet<>(oldEnd.keySet());keys.addAll(newEnd.keySet());
        for(var k:keys) {
            check(newEnd.getOrDefault(k,0L)>=oldEnd.getOrDefault(k,0L),"net worsened "+k+" "+original+" -> "+replacement);
            check(Math.min(0,newLow.getOrDefault(k,0L))>=Math.min(0,oldLow.getOrDefault(k,0L)),"prefix worsened "+k+" "+original+" -> "+replacement);
        }
        return replacement;
    }
    public static void main(String[] args) {
        var recipes=Map.of("bc",r("bc",Map.of("B",1L),Map.of("C",1L)),"ca",r("ca",Map.of("C",1L),Map.of("A",1L)),"ab",r("ab",Map.of("A",1L),Map.of("B",1L)));
        var turn=new PlanStep.Sequence(List.of(new PlanStep.Batch("bc",2),new PlanStep.Batch("ca",2),new PlanStep.Batch("ab",1)));
        var reduced=verify(turn,recipes);
        check(PlanCountComputation.of(reduced).equals(Map.of("bc",BigInteger.ONE,"ca",BigInteger.ONE)),reduced);
        var repeated=new PlanStep.Repeat(turn,Long.MAX_VALUE);
        reduced=PlanFlowPruning.optimize(repeated,recipes,new PlanningBudget(0,1_000_000,()->false));
        check(PlanCountComputation.of(reduced).equals(Map.of("bc",BigInteger.valueOf(Long.MAX_VALUE),"ca",BigInteger.valueOf(Long.MAX_VALUE))),"long turnover");
        var productive=Map.of("make",r("make",Map.of("C",1L,"R",1L),Map.of("I",1L)),"return",r("return",Map.of("I",1L),Map.of("C",1L,"P",1L)));
        var useful=new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("make",1),new PlanStep.Batch("return",1))),8);
        check(PlanCountComputation.of(verify(useful,productive)).equals(Map.of("make",BigInteger.valueOf(8),"return",BigInteger.valueOf(8))),"removed useful catalyst loop");
        // Lower net consumption alone is insufficient: startup reachability matters.
        var startup=Map.of("seed",r("seed",Map.of("R",1L),Map.of("C",1L)),"grow",r("grow",Map.of("C",1L),Map.of("C",1L,"P",1L)),"drain",r("drain",Map.of("C",1L),Map.of("R",1L)));
        var initialized=new PlanStep.Sequence(List.of(new PlanStep.Batch("seed",1),new PlanStep.Batch("grow",1),new PlanStep.Batch("drain",1)));
        var certified=verify(initialized,startup);
        check(PlanCountComputation.of(certified).getOrDefault("seed",BigInteger.ZERO).signum()>0,"removed required startup");
        var sensitive=new LinkedHashMap<>(recipes);
        sensitive.put("bc",new GraphRecipe<>("bc","bc",List.of(new GraphRecipe.Slot<>("B",1L),new GraphRecipe.Slot<>("config",1L,1,true)),Map.of("C",1L)));
        check(PlanFlowPruning.optimize(turn,sensitive,new PlanningBudget(0,1_000_000,()->false))==turn,"changed a dispatch-sensitive batch");
        var exhausted=new PlanningBudget(0,100,()->false);
        check(PlanFlowPruning.optimize(turn,recipes,exhausted)==turn&&exhausted.reservedBytes()==0,"small budget does not rewrite the witness");
        var random=new Random(20260929);
        for(int test=0;test<5000;test++) {
            var rs=new LinkedHashMap<String,GraphRecipe<String>>();
            for(int j=0;j<2+random.nextInt(6);j++) {
                Map<String,Long> in=new LinkedHashMap<>(),out=new LinkedHashMap<>();
                for(int k=0;k<4;k++){if(random.nextBoolean())in.put("k"+k,1L+random.nextInt(3));if(random.nextBoolean())out.put("k"+k,1L+random.nextInt(3));}
                if(in.isEmpty())in.put("k0",1L);if(out.isEmpty())out.put("k3",1L);
                rs.put("r"+j,r("r"+j,in,out));
            }
            var ids=new ArrayList<>(rs.keySet());var steps=new ArrayList<PlanStep>();
            for(int i=0;i<2+random.nextInt(9);i++)steps.add(new PlanStep.Batch(ids.get(random.nextInt(ids.size())),1+random.nextInt(4)));
            PlanStep program=new PlanStep.Sequence(steps);
            if(random.nextBoolean())program=new PlanStep.Sequence(List.of(new PlanStep.Repeat(program,1+random.nextInt(3)),steps.get(0),steps.get(steps.size()-1)));
            verify(program,rs);
        }
        System.out.println("Flow pruning oracle PASS checks="+checks+" changed="+changed);
    }
}
