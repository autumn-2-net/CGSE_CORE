package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.math.BigInteger;

public final class NetworkOracleProbe {
    public static void main(String[] args)throws Exception {
        var cases=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();var json=new Gson();
        int total=0,feasible=0,unknown=0;long checked=0;
        try(var writer=new PrintWriter(Files.newBufferedWriter(Path.of(args[1])))){
            for(var element:cases){var c=element.getAsJsonObject();var recipes=new ArrayList<GraphRecipe<String>>();
                for(var e:c.getAsJsonArray("recipes")){var r=e.getAsJsonObject();var slots=new ArrayList<GraphRecipe.Slot<String>>();var out=new LinkedHashMap<String,Long>();
                    for(var s:r.getAsJsonArray("slots")){var a=s.getAsJsonObject();slots.add(new GraphRecipe.Slot<>(a.get("key").getAsString(),a.get("amount").getAsLong()));}
                    r.getAsJsonObject("outputs").entrySet().forEach(v->out.put(v.getKey(),v.getValue().getAsLong()));
                    recipes.add(new GraphRecipe<>(r.get("id").getAsString(),r.get("id").getAsString(),slots,out));}
                var stock=new LinkedHashMap<String,Long>();c.getAsJsonObject("stock").entrySet().forEach(e->stock.put(e.getKey(),e.getValue().getAsLong()));
                String target=c.get("target").getAsString();long amount=c.get("amount").getAsLong();boolean expected=c.get("expected").getAsString().equals("FEASIBLE");
                for(int variation=0;variation<3;variation++){
                    var order=new ArrayList<>(recipes);if(variation>0)Collections.shuffle(order,new Random(73L+variation+total));var compiler=new GraphCompiler<>(order);
                    var budget=new PlanningBudget(0,2_000_000,128L<<20,()->false,System::nanoTime);
                    try(var check=new MissingStockAnalysis<>(compiler,target,stock,Set.of(),Set.of(),Set.of(),true,budget)){
                        while(!check.step()){}var pruned=check.unreachableRecipes();
                        if(expected&&check.blocked())throw new AssertionError("startup false negative "+c.get("id"));
                        for(var fired:c.getAsJsonArray("fired")){checked++;if(pruned.contains(fired.getAsString()))throw new AssertionError("pruned executable recipe "+c.get("id")+" "+fired);}
                    }
                    long first=budget.nodes();GraphPlan<String> plan;
                    var work=new GraphPlanningWork<>(compiler,target,amount,stock,Set.of(),Map.of(),true,true,budget);
                    try{try{while(!work.step()){}plan=work.result();}catch(PlanningBudget.Exhausted exhausted){plan=work.limited(exhausted);}
                        if(plan.feasible()){
                            if(!expected)throw new AssertionError("false witness "+c.get("id"));PlanVerifier.verify(plan);PlanVerifier.verifyRuntimeInventory(plan);
                            plan.initialExact().forEach((k,v)->{if(v.compareTo(BigInteger.valueOf(stock.getOrDefault(k,0L)))>0)throw new AssertionError("overdraw");});feasible++;
                        }else if(Set.of(GraphPlan.Result.INFEASIBLE,GraphPlan.Result.MISSING_INPUT,GraphPlan.Result.MISSING_SEED).contains(plan.result())){
                            if(expected)throw new AssertionError("FALSE NEGATIVE "+c.get("id")+" "+plan.result()+" "+budget.diagnostics());
                        }else unknown++;
                    }finally{work.close();}
                    if(budget.reservedBytes()!=0)throw new AssertionError("leak "+budget.reservedBytes());
                    var row=new LinkedHashMap<String,Object>();row.put("id",c.get("id").getAsString());row.put("variation",variation);row.put("expected",expected);row.put("result",plan.result());row.put("work",budget.nodes()-first);row.put("peak_bytes",budget.peakBytes());writer.println(json.toJson(row));writer.flush();
                    if(++total%384==0)System.out.println("total="+total+" feasible="+feasible+" unknown="+unknown+" checked_startups="+checked);
                }
            }
        }
        System.out.println("PASS total="+total+" feasible="+feasible+" unknown="+unknown+" checked_startups="+checked);
    }
}
