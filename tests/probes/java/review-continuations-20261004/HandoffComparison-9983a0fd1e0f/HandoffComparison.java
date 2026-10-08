package org.cgse.core;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.Method;
import com.google.gson.*;

public final class HandoffComparison {
    static final Gson gson=new Gson();
    static Map<String,Long> amounts(JsonObject object) {var result=new LinkedHashMap<String,Long>();for(var entry:object.entrySet())result.put(entry.getKey(),entry.getValue().getAsLong());return result;}
    public static void main(String[] args)throws Exception {
        Method start=null;try{start=IntegerCountBranch.class.getDeclaredMethod("beginScheduling");start.setAccessible(true);}catch(NoSuchMethodException baseline){}
        var examples=JsonParser.parseString(Files.readString(Path.of(".local/review-continuations-20261004/handoff-cases.json"))).getAsJsonArray();
        var rows=new ArrayList<Map<String,Object>>();
        for(var entry:examples) {
            var row=entry.getAsJsonObject();var e=row.getAsJsonObject("example");var recipes=new ArrayList<GraphRecipe<String>>();
            for(var element:e.getAsJsonArray("recipes")){var r=element.getAsJsonObject();String id=r.get("id").getAsString();recipes.add(new GraphRecipe<>(id,id,amounts(r.getAsJsonObject("inputs")).entrySet().stream().map(x->new GraphRecipe.Slot<>(x.getKey(),x.getValue())).toList(),amounts(r.getAsJsonObject("outputs"))));}
            recipes.add(new GraphRecipe<>("finish","finish",List.of(),Map.of("goal",1L)));
            var values=e.getAsJsonArray("counts");BigInteger[] counts=new BigInteger[values.size()+1];for(int i=0;i<values.size();i++)counts[i]=values.get(i).getAsBigInteger();counts[values.size()]=BigInteger.ONE;
            var stock=amounts(e.getAsJsonObject("stock"));var budget=new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);
            String status="UNKNOWN";int proposals=0;long work;
            try(var model=RecipeCountModel.forShell(recipes,Map.of("goal",BigInteger.ONE),stock,Set.of(),budget);var execution=new CountExecution<>(model,budget);
                var branch=new IntegerCountBranch<>(model,execution,"goal",1,stock,Map.of(),Set.of(),false,true,budget,System.nanoTime(),List.of())) {
                branch.initialized=true;branch.linearConstraints=new ArrayList<>(model.constraints);branch.failureNeighborhoodTried=true;
                branch.lower=new BigInteger[counts.length];Arrays.fill(branch.lower,BigInteger.ZERO);branch.upper=counts.clone();
                long before=budget.nodes();
                do {
                    branch.counts=counts.clone();branch.jumpCandidate=true;branch.jumpLate=true;branch.state=IntegerCountBranch.State.OPEN;
                    if(start==null){branch.schedulingWork=0;branch.scheduling=new CountSchedule<>(model,branch.counts,budget);}else start.invoke(branch);
                    while(branch.state==IntegerCountBranch.State.OPEN)branch.run(1024,List.of(),List.of(),List.of(),null,()->false);
                    proposals++;
                    if(branch.state==IntegerCountBranch.State.FOUND){PlanVerifier.verifyRuntimeInventory(branch.plan);status="WITNESS";break;}
                    if(branch.state!=IntegerCountBranch.State.UNRESOLVED)throw new AssertionError("bad state "+branch.state);
                }while(proposals<8);
                work=budget.nodes()-before;
            }
            if(budget.reservedBytes()!=0)throw new AssertionError("leak");
            rows.add(Map.of("seed",row.get("seed").getAsInt(),"status",status,"work",work,"proposals",proposals,"peakBytes",budget.peakBytes()));
        }
        var report=Map.of("continuationEnabled",start!=null,"cases",rows.size(),"solved",rows.stream().filter(x->x.get("status").equals("WITNESS")).count(),"work",rows.stream().mapToLong(x->((Number)x.get("work")).longValue()).sum(),"results",rows);
        Files.writeString(Path.of(args[0],"handoff-comparison.json"),gson.toJson(report));System.out.println(gson.toJson(report));
    }
}
