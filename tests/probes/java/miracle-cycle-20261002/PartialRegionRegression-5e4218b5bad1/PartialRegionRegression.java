package org.cgse.core;
import java.util.*;
import java.math.BigInteger;

public class PartialRegionRegression {
    static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out) {
        return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);
    }
    public static void main(String[] args) {
        var recipes=List.of(r("crystal",Map.of("fluid",100000L,"raw",1L),Map.of("crystal",100L)),
                r("gas",Map.of("crystal",1L,"matter",100000L),Map.of("gas",90000L)),
                r("fluid",Map.of("gas",100000L),Map.of("fluid",1000L)));
        var ids=new LinkedHashMap<String,GraphRecipe<String>>();recipes.forEach(r->ids.put(r.id(),r));
        long[] amounts={1,999,1000,2000,1000000,2000000,Integer.MAX_VALUE,1_000_000_000_000L};
        int checked=0;
        for(long amount:amounts)for(long startingGas:new long[]{0,10000,100000}) {
            long liquidRuns=(amount+999)/1000;
            long gasRuns=Math.max(0,(liquidRuns*100000-startingGas+89999)/90000);
            var stock=Map.of("crystal",gasRuns+1,"gas",startingGas,"matter",gasRuns*100000,"raw",0L);
            var budget=new PlanningBudget(0,1_000_000,64L<<20,()->false,System::nanoTime);
            var selection=new RegionSelection<>(new GraphCompiler.Region<>(recipes,true),Map.of("fluid",BigInteger.valueOf(amount)),stock,"fluid",amount,true,true,Set.of(),budget,CatalystPolicy.MINIMAL,stock);
            while(!selection.step()){}
            var c=selection.result();if(c==null)throw new AssertionError("No witness for "+amount);
            var program=PlanStep.repeat(c.body(),c.runs());var summary=SequenceSummary.of(program,ids);
            if(summary.delta("fluid").compareTo(BigInteger.valueOf(amount))<0)throw new AssertionError("Target not produced");
            for(var k:List.of("crystal","gas","fluid","matter","raw"))if(summary.required(k).compareTo(BigInteger.valueOf(stock.getOrDefault(k,0L)))>0)throw new AssertionError("Invented startup "+k+" at "+amount);
            var counting=new PlanCountComputation(program);while(!counting.step(budget)){}
            if(counting.result().getOrDefault("crystal",BigInteger.ZERO).signum()!=0)throw new AssertionError("Wasteful full loop selected");
            checked++;
        }
        System.out.println("Partial SCC paths: "+checked+" exact prefix/inventory checks passed, including large amounts.");
    }
}
