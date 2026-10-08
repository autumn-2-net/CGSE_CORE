package org.cgse.core;

import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;

public final class StructuralBoundaryProbe {
    static GraphRecipe<String> recipe(String id, String input, String output) {
        return new GraphRecipe<>(id, id, List.of(new GraphRecipe.Slot<>(input, 1)), Map.of(output, 1L));
    }
    static Map<String,Object> run(String id,int length,long amount,int mode,long memory,boolean require) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        recipes.add(recipe("left", "left", "x0"));
        recipes.add(recipe("right", "right", "x0"));
        for (int i=1;i<=length;i++) recipes.add(recipe("tail"+i,"x"+(i-1),i==length?"target":"x"+i));
        Collections.shuffle(recipes,new Random(id.hashCode()));
        var stock=new LinkedHashMap<String,Long>();
        stock.put("left",amount/2);stock.put("right",amount-amount/2);
        var seeds=new LinkedHashMap<String,Long>();
        if(mode==1)stock.put("target",Long.MAX_VALUE);
        if(mode==2){stock.put("x8",17L);seeds.put("x8",1L);}
        var budget=new PlanningBudget(0,20_000_000,memory,()->false,System::nanoTime);
        String status="UNKNOWN",limit="";int resumes=0;
        try(var search=new IntegerCountSearch<>(new GraphCompiler<>(recipes),"target",amount,stock,seeds,Set.of(),Set.of(),false,mode!=2,budget,System.nanoTime())) {
            // Setup must remain legal before the first step, even on shell-eligible models.
            search.scout(1);
            for(int round=0;round<100;round++) {
                while(!search.step()) {}
                if(search.result()!=null){
                    PlanVerifier.verifyRuntimeInventory(search.result());
                    if(search.result().feasible())status="SAT";
                    break;
                }
                if(search.infeasible()){status="UNSAT";break;}
                if(!search.paused()||budget.remainingWork()<8192)break;
                search.resume();resumes++;
            }
        }catch(PlanningBudget.Exhausted e){limit=e.limit().toString();}
        if(budget.reservedBytes()!=0||status.equals("UNSAT")||require&&!status.equals("SAT"))
            throw new AssertionError(id+" "+status+" "+limit+" leak="+budget.reservedBytes()+" trace="+budget.diagnostics());
        return Map.of("id",id,"status",status,"work",budget.nodes(),"limit",limit,"resumes",resumes,"memory",memory);
    }
    public static void main(String[]args)throws Exception {
        var results=new ArrayList<Map<String,Object>>();
        for(int length:new int[]{16,32,64}) for(long amount:new long[]{1,1000,Integer.MAX_VALUE,1L+Integer.MAX_VALUE,9_007_199_254_740_993L,Long.MAX_VALUE/2,Long.MAX_VALUE})
            for(int mode=0;mode<3;mode++)results.add(run("large-"+length+"-"+amount+"-"+mode,length,amount,mode,128L<<20,true));
        for(int units=4;units<=64;units++)results.add(run("memory-"+units,32,1000,0,units*131072L,false));
        Files.writeString(Path.of(args[0],"structural-boundaries.json"),new Gson().toJson(results));
        System.out.println(Map.of("cases",results.size(),"solved",results.stream().filter(r->r.get("status").equals("SAT")).count(),"resumes",results.stream().mapToInt(r->(int)r.get("resumes")).sum()));
    }
}
