package org.cgse.core;

import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;
import java.math.BigInteger;

public final class StructuralBenchmark {
    static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
    public static void main(String[] args)throws Exception{
        var rows=new ArrayList<Map<String,Object>>();
        for(int length:new int[]{16,32,64,128,256})for(int seed=0;seed<6;seed++){
            var recipes=new ArrayList<GraphRecipe<String>>();var stock=new LinkedHashMap<String,Long>();
            // A mixed-source core must feed a deterministic tail. Neither producer can fund it alone.
            recipes.add(r("left",Map.of("left",1L),Map.of("x0",1L)));recipes.add(r("right",Map.of("right",1L),Map.of("x0",1L)));
            long amount=seed%2==0?100:1_000_000_000L;stock.put("left",amount/2);stock.put("right",amount/2);
            for(int i=1;i<=length;i++)recipes.add(r("r"+i,Map.of("x"+(i-1),1L),Map.of(i==length?"target":"x"+i,1L)));
            Collections.shuffle(recipes,new Random(seed));
            var budget=new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);long start=System.nanoTime();GraphPlan<String> plan;boolean infeasible;
            try(var search=new IntegerCountSearch<>(new GraphCompiler<>(recipes),"target",amount,stock,Map.of(),Set.of(),Set.of(),false,true,budget,start)){
                int rounds=0;do{while(!search.step()){}if(search.result()!=null||search.infeasible()||!search.paused()||budget.remainingWork()<8192)break;search.resume();}while(++rounds<1000);
                plan=search.result();infeasible=search.infeasible();if(plan!=null)PlanVerifier.verifyRuntimeInventory(plan);
            }
            var row=new LinkedHashMap<String,Object>();row.put("length",length);row.put("seed",seed);row.put("feasible",plan!=null&&plan.feasible());row.put("infeasible",infeasible);row.put("work",budget.nodes());row.put("ms",(System.nanoTime()-start)/1e6);row.put("diagnostics",budget.diagnostics());row.put("leak",budget.reservedBytes());rows.add(row);
            if(infeasible||budget.reservedBytes()!=0)throw new AssertionError(row);
            System.out.println(length+"/"+seed+" feasible="+row.get("feasible")+" work="+budget.nodes());
        }
        Files.writeString(Path.of(args[0],"structural-benchmark.json"),new Gson().toJson(rows));
    }
}
