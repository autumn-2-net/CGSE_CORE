package org.cgse.core;

import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;

public final class ReviewSemanticsProbe {
    static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
    static void solve(String name,List<GraphRecipe<String>> core,Map<String,Long> stock,long amount){
        var recipes=new ArrayList<>(core);for(int i=1;i<=20;i++)recipes.add(r("tail"+i,Map.of(i==1?"B":"x"+(i-1),1L),Map.of(i==20?"target":"x"+i,1L)));
        for(int seed=0;seed<12;seed++){
            Collections.shuffle(recipes,new Random(seed));var b=new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);
            try(var search=new IntegerCountSearch<>(new GraphCompiler<>(recipes),"target",amount,stock,Map.of(),Set.of(),Set.of(),false,true,b,System.nanoTime())){
                for(int i=0;i<100;i++){while(!search.step()){}if(search.result()!=null||search.infeasible()||!search.paused())break;search.resume();}
                if(search.result()==null||!search.result().feasible())throw new AssertionError(name+" "+seed+" "+b.diagnostics());
                PlanVerifier.verifyRuntimeInventory(search.result());
            }
            if(b.reservedBytes()!=0)throw new AssertionError("leak "+name);
        }
    }
    public static void main(String[]args)throws Exception{
        solve("leftover exit",List.of(r("R1",Map.of("A",2L),Map.of("X",3L)),r("R2",Map.of("X",2L),Map.of("B",1L))),Map.of("A",2L),1);
        solve("fan in round once",List.of(r("source",Map.of("A",1L),Map.of("X",3L)),r("left",Map.of("X",1L),Map.of("Y",1L)),r("right",Map.of("X",1L),Map.of("Z",1L)),r("join",Map.of("Y",1L,"Z",1L),Map.of("B",1L))),Map.of("A",1L),1);
        solve("same net different startup",List.of(r("no seed",Map.of("missing",1L),Map.of("missing",1L,"B",1L)),r("has seed",Map.of("seed",1L),Map.of("seed",1L,"B",1L))),Map.of("seed",1L),7);
        Files.writeString(Path.of(args[0],"review-semantics.json"),new Gson().toJson(Map.of("cases",36,"failures",0)));System.out.println("Review counterexamples: 36 passed");
    }
}
