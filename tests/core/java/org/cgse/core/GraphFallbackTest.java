package org.cgse.core;

import org.cgse.core.*;
import java.math.BigInteger;
import java.util.*;

/** Local-only oracle for the deliberately incomplete ordinary fallback. */
public final class GraphFallbackTest {
    static int assertions;
    static GraphRecipe<String> r(String id, Map<String,Long> in, Map<String,Long> out) {
        return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);
    }
    static void check(boolean value,Object why) {assertions++;if(!value)throw new AssertionError(why);}
    static GraphPlan<String> plan(List<GraphRecipe<String>> recipes,String target,long n,Map<String,Long> stock) {
        var b=new PlanningBudget(0,131072,16L<<20,()->false,System::nanoTime);
        var p=GraphFallback.plan(new GraphCompiler<>(recipes),target,n,stock,Set.of(),Map.of(),true,true,b);
        check(b.reservedBytes()==0,"workspace retained");
        return p;
    }
    static void verify(GraphPlan<String> p) {
        Map<String,BigInteger> inventory=new HashMap<>(p.initialExact());
        ArrayDeque<PlanStep> steps=new ArrayDeque<>();steps.push(p.steps());int fired=0;
        while(!steps.isEmpty()) {
            var step=steps.pop();
            if(step instanceof PlanStep.Sequence seq)for(int i=seq.children().size()-1;i>=0;i--)steps.push(seq.children().get(i));
            else if(step instanceof PlanStep.Repeat repeat) {check(repeat.times()<100000,"oracle repeat bound");for(long i=0;i<repeat.times();i++)steps.push(repeat.body());}
            else {var batch=(PlanStep.Batch)step;var recipe=p.recipes().get(batch.recipe());
                for(long i=0;i<batch.runs();i++) {
                    check(++fired<1000000,"oracle bound");
                    for(var entry:recipe.inputs().entrySet()) {
                        var rest=inventory.getOrDefault(entry.getKey(),BigInteger.ZERO).subtract(BigInteger.valueOf(entry.getValue()));
                        check(rest.signum()>=0,"invalid prefix "+p.recipes()+" "+entry);inventory.put(entry.getKey(),rest);
                    }
                    recipe.outputs().forEach((key,n)->inventory.merge(key,BigInteger.valueOf(n),BigInteger::add));
                }
            }
        }
        inventory.merge(p.target(),BigInteger.valueOf(p.amount()).negate(),BigInteger::add);
        check(inventory.get(p.target()).signum()>=0,"missing delivery");
        p.seeds().forEach((key,n)->check(inventory.getOrDefault(key,BigInteger.ZERO).compareTo(BigInteger.valueOf(n))>=0,"lost restoration"));
    }
    public static void main(String[] args) {run();}
    public static void run() {
        var chain=List.of(r("plate",Map.of("ore",2L),Map.of("plate",3L)),r("p",Map.of("plate",2L),Map.of("p",1L)));
        var p=plan(chain,"p",7,Map.of("ore",9L,"p",100L));
        check(p.missing().equals(Map.of("ore",1L)),p.missing());check(p.patternTimes().equals(Map.of("plate",5L,"p",7L)),p.patternTimes());verify(p);
        var diamond=List.of(r("grind",Map.of("diamond",1L),Map.of("dust",1L)),r("restore",Map.of("dust",4L),Map.of("diamond",3L)));
        p=plan(diamond,"diamond",6,Map.of("diamond",1L));
        check(p.patternTimes().equals(Map.of("restore",2L)),"fallback ran lossy cycle");check(p.missing().equals(Map.of("dust",8L)),p.missing());check(p.seeds().isEmpty(),"invented seed proof");verify(p);
        var grow=r("grow",Map.of("a",1L,"raw",1L),Map.of("a",2L));
        p=plan(List.of(grow),"a",10,Map.of("a",100L,"raw",100L));
        check(p.patternTimes().isEmpty()&&p.missing().equals(Map.of("a",10L)),"cycle solver used or existing goal stock counted");verify(p);
        p=plan(List.of(grow,r("linear",Map.of("raw",1L),Map.of("a",1L))),"a",10,Map.of("raw",10L));
        check(p.feasible()&&p.patternTimes().equals(Map.of("linear",10L)),"did not skip self-cycle source");verify(p);
        var tool=r("tool",Map.of("hammer",1L,"ore",1L),Map.of("hammer",1L,"p",1L));
        p=plan(List.of(tool),"p",100,Map.of("ore",100L,"hammer",1L));
        check(p.feasible()&&p.initial().get("hammer")==1,"tool multiplied");verify(p);
        var virtual=new GraphRecipe<>("virtual","virtual",List.of(new GraphRecipe.Slot<>("lens",1,0,true,true),new GraphRecipe.Slot<>("ore",1)),Map.of("lens",1L,"p",1L));
        p=plan(List.of(virtual),"p",100,Map.of("ore",100L));check(p.missing().equals(Map.of("lens",1L)),p.missing());verify(p);
        var joint=List.of(r("parent",Map.of("b",1L),Map.of("p",1L)),r("child",Map.of("raw",1L),Map.of("b",1L,"p",1L)));
        p=plan(joint,"p",1,Map.of("raw",1L));check(p.feasible()&&p.patternTimes().equals(Map.of("child",1L)),"unneeded parent persisted");verify(p);
        var sibling=List.of(r("parent",new LinkedHashMap<>(Map.of("a",1L,"b",1L)),Map.of("p",1L)),r("a",Map.of("ore",1L),Map.of("a",1L)),r("b",Map.of("a",1L),Map.of("b",1L)));
        p=plan(sibling,"p",4,Map.of("ore",4L));verify(p);
        p=plan(List.of(r("first",Map.of("x",1L),Map.of("p",1L)),r("second",Map.of("y",1L),Map.of("p",1L))),"p",10,Map.of("y",10L));
        check(p.feasible()&&p.patternTimes().equals(Map.of("second",10L)),"fallback did not backtrack to the funded alternative");verify(p);
        var b=new PlanningBudget(0,131072,()->false);
        p=GraphFallback.plan(new GraphCompiler<>(List.of(tool)),"p",10,Map.of("ore",2L),Set.of("ore"),Map.of("hammer",1L),true,true,b);
        check(p.missing().equals(Map.of("hammer",1L)),p.missing());verify(p);check(b.reservedBytes()==0,"external leak");
        for(long n:new long[]{Integer.MAX_VALUE,Long.MAX_VALUE}) {
            p=plan(chain,"p",n,Map.of());
            var plates=BigInteger.valueOf(n).multiply(BigInteger.TWO).add(BigInteger.TWO).divide(BigInteger.valueOf(3));
            check(p.patternTimesExact().get("plate").equals(plates),"long rounds");
            check(p.missingExact().equals(Map.of("ore",plates.multiply(BigInteger.TWO))),"long deficit");
        }
        List<GraphRecipe<String>> deep=new ArrayList<>();
        for(int i=0;i<5000;i++)deep.add(r("r"+i,Map.of("k"+(i+1),1L),Map.of("k"+i,1L)));
        p=plan(deep,"k0",1,Map.of("k5000",1L));check(p.patternTimes().size()<=1024&&!p.missing().isEmpty(),"unbounded depth");verify(p);
        Random random=new Random(2992026);
        for(int trial=0;trial<500;trial++) {
            List<GraphRecipe<String>> recipes=new ArrayList<>();Map<String,Long> stock=new HashMap<>();
            for(int i=0;i<6;i++)stock.put("k"+i,(long)random.nextInt(8));
            for(int i=0;i<8;i++) {Map<String,Long> in=new LinkedHashMap<>(),out=new LinkedHashMap<>();
                for(int j=0;j<1+random.nextInt(2);j++)in.merge("k"+random.nextInt(6),1L+random.nextInt(3),Long::sum);
                for(int j=0;j<1+random.nextInt(2);j++)out.merge("k"+random.nextInt(6),1L+random.nextInt(3),Long::sum);
                recipes.add(r("r"+i,in,out));
            }
            p=plan(recipes,"k0",1+random.nextInt(6),stock);verify(p);
            for(var e:p.initialExact().entrySet()) {
                var have=BigInteger.valueOf(e.getKey().equals("k0")?0:stock.getOrDefault(e.getKey(),0L));
                check(p.missingExact().getOrDefault(e.getKey(),BigInteger.ZERO).equals(e.getValue().subtract(have).max(BigInteger.ZERO)),"incorrect shortage");
            }
        }
        for(int limit=1;limit<=160;limit++) {
            var budget=new PlanningBudget(0,limit,16L<<20,()->false,System::nanoTime);
            try{GraphFallback.plan(new GraphCompiler<>(chain),"p",7,Map.of(),Set.of(),Map.of(),true,true,budget);}catch(PlanningBudget.Exhausted expected){}
            check(budget.reservedBytes()==0,"work-limit leak "+limit);
        }
        for(long bytes:new long[]{128,256,1024,4096}) {
            var budget=new PlanningBudget(0,131072,bytes,()->false,System::nanoTime);
            try{GraphFallback.plan(new GraphCompiler<>(chain),"p",7,Map.of(),Set.of(),Map.of(),true,true,budget);}catch(PlanningBudget.Exhausted expected){}
            check(budget.reservedBytes()==0,"memory-limit leak "+bytes);
        }
        var cancelled=new PlanningBudget(0,131072,()->true);
        try{GraphFallback.plan(new GraphCompiler<>(chain),"p",1,Map.of(),Set.of(),Map.of(),true,true,cancelled);throw new AssertionError("cancellation ignored");}catch(java.util.concurrent.CancellationException expected){}
        check(cancelled.reservedBytes()==0,"cancel leak");
        System.out.println("Graph fallback: "+assertions+" assertions; cyclic cuts, bounded alternatives, joint outputs, tools, long, 500 prefix oracles and lifecycle passed");
    }
}
