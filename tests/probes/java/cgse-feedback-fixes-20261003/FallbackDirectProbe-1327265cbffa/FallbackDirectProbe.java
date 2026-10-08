package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

public final class FallbackDirectProbe {
    static int passed, funded;
    static GraphRecipe<String> r(String id, Map<String,Long> inputs, Map<String,Long> outputs) {
        return new GraphRecipe<>(id,id,inputs.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),outputs);
    }
    static Map<String,Long> m(Object... values) {
        Map<String,Long> result=new LinkedHashMap<>();
        for(int i=0;i<values.length;i+=2)result.put((String)values[i],((Number)values[i+1]).longValue());
        return result;
    }
    static void run(String name,List<GraphRecipe<String>> recipes,long amount,Map<String,Long> stock,Map<String,Long> seeds,boolean feasible) {
        var budget=new PlanningBudget(0,131072,16L<<20,()->false,System::nanoTime);
        var plan=GraphFallback.plan(new GraphCompiler<>(recipes),"T",amount,stock,Set.of(),seeds,false,true,budget);
        if(!Boolean.getBoolean("baseline") && plan.feasible()!=feasible)throw new AssertionError(name+" expected "+feasible+" got "+plan.result()+" missing "+plan.missingExact()+" counts "+plan.patternTimes());
        if(plan.feasible()) {
            funded++;
            try(var computation=new SummaryComputation<>(plan.steps(),plan.recipes(),budget)) {
                while(!computation.step()){}
                var summary=computation.result();
                for(String key:summary.keys()) {
                    BigInteger have=BigInteger.valueOf(key.equals("T")?0:stock.getOrDefault(key,0L));
                    if(have.compareTo(summary.required(key))<0)throw new AssertionError(name+" unfunded "+key);
                    BigInteger goal=BigInteger.valueOf(seeds.getOrDefault(key,0L)+(key.equals("T")?amount:0));
                    if(have.add(summary.delta(key)).compareTo(goal)<0)throw new AssertionError(name+" missing final "+key);
                }
            }
        }
        if(budget.reservedBytes()!=0)throw new AssertionError(name+" memory "+budget.reservedBytes());
        passed++;
        System.out.println(name+"\t"+plan.result()+"\twork="+budget.nodes()+"\tcounts="+plan.patternTimes()+"\tmissing="+plan.missingExact());
    }
    public static void main(String[]args) {
        var bad=r("bad",m("missing",1),m("T",1));
        run("later-funded",List.of(bad,r("good",m("ore",1),m("T",1))),1,m("ore",1),Map.of(),true);
        List<GraphRecipe<String>> many=new ArrayList<>();for(int i=0;i<25;i++)many.add(r("d"+i,m("x"+i,1),m("T",1)));
        many.add(r("good",m("ore",1),m("T",1)));run("source26",many,1,m("ore",1),Map.of(),true);
        var source=r("source",m("raw",1),m("A",1));var b=r("b",m("A",1),m("B",1));
        run("shared-refill",List.of(r("root",m("A",1,"B",1),m("T",1)),source,b),10,m("raw",20),Map.of(),true);
        run("sibling-backtrack",List.of(r("root",m("A",1,"B",1),m("T",1)),r("aR",m("R",1),m("A",1)),r("aS",m("S",1),m("A",1)),r("bR",m("R",1),m("B",1))),1,m("R",1,"S",1),Map.of(),true);
        var ta=r("ta",m("a",1),m("T",1));var tb=r("tb",m("b",1),m("T",1));
        run("mix4plus6",List.of(ta,tb),10,m("a",4,"b",6),Map.of(),true);
        run("mix-indirect4plus6",List.of(r("ta",m("X",1),m("T",1)),r("X",m("a",1),m("X",1)),tb),10,m("a",4,"b",6),Map.of(),true);
        run("mix-indirect-large",List.of(r("ta",m("X",1),m("T",1)),r("X",m("a",1),m("X",1)),tb),10_000_000_000_000L,m("a",4_000_000_000_000L,"b",6_000_000_000_000L),Map.of(),true);
        run("joint-output",List.of(r("root",m("A",1,"B",1),m("T",1)),r("joint",m("raw",1),m("A",1,"B",1))),10,m("raw",10),Map.of(),true);
        run("returned-tool",List.of(bad,r("root",m("hammer",1,"A",1),m("hammer",1,"T",1)),r("A",m("hammer",1,"ore",1),m("hammer",1,"A",1))),10,m("hammer",1,"ore",10),Map.of(),true);
        run("borrow-consumed-parent",List.of(bad,r("root",m("hammer",1,"A",1),m("T",1)),r("A",m("hammer",1,"ore",1),m("hammer",1,"A",1))),1,m("hammer",1,"ore",1),Map.of(),true);
        run("tool-missing",List.of(r("root",m("hammer",1,"A",1),m("hammer",1,"T",1)),r("A",m("hammer",1,"ore",1),m("hammer",1,"A",1))),1,m("ore",1),Map.of(),false);
        run("cycle",List.of(r("root",m("A",1),m("T",1)),r("cycle",m("T",1),m("A",1))),1,Map.of(),Map.of(),false);
        run("long-max",List.of(ta),Long.MAX_VALUE,m("a",Long.MAX_VALUE),Map.of(),true);
        run("seed-restoration",List.of(r("root",m("A",1),m("T",1)),source),1,m("A",1,"raw",1),m("A",1),true);
        run("missing-seed",List.of(r("root",m("A",1),m("T",1))),1,m("A",1),m("A",1),false);
        run("force-existing-target",List.of(ta),1,m("T",10),Map.of(),false);
        List<GraphRecipe<String>> missing=new ArrayList<>();for(int i=0;i<5000;i++)missing.add(r("miss"+i,m("unknown"+i,1),m("T",1)));
        run("bounded-preview",missing,1,Map.of(),Map.of(),false);
        System.out.println("PASS cases="+passed+" funded="+funded);
    }
}
