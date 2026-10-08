package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.math.BigInteger;
public final class WarmRankReplay {
 public static void main(String[] args)throws Exception{
  Path base=Path.of(args[0]);var config=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();var manual=MixedSweep.read(base.resolve("manual.json"));var extra=MixedSweep.read(base.resolve("extra-virtual.json"));var compiler=config.get("mode").getAsString().startsWith("subset-") ? ParallelSubsetSweep.compiler(manual,extra,config) : MixedSweep.compiler(manual,extra,config.get("mode").getAsString(),config.get("seed").getAsLong());var requests=config.getAsJsonArray("requests");var warmup=new JsonArray();
  for(int i=0;i<requests.size()-1;i++){var request=requests.get(i).getAsJsonObject();var row=MixedSweep.solve(manual,compiler,config,request);var shortRow=request.deepCopy();shortRow.add("result",row.get("result"));shortRow.add("work",row.get("work"));warmup.add(shortRow);if((i+1)%20==0)System.out.println("warmed="+(i+1));}
  System.setProperty("ranking.fixedlabel","true");var request=requests.get(requests.size()-1).getAsJsonObject();JsonObject row;
  if(args[3].equals("main"))row=MixedSweep.solve(manual,compiler,config,request);
  else {
   int variant=Integer.parseInt(args[3]);String target=request.get("target").getAsString();long amount=request.get("amount").getAsLong();var budget=new PlanningBudget(0,4_000_000,config.get("memory").getAsLong(),()->false,System::nanoTime);var ranking=new AtomicReference<GraphSourceRanking<String>>();long started=System.nanoTime();row=request.deepCopy();GraphPlan<String> plan=null;
   try(var view=new GraphStockViewWork<>(compiler,target,amount,manual.stock(),manual.external(),Map.of(),Set.of(),true,true,new CatalystPolicy(4096,64),budget,started,variant,()->{if(ranking.get()==null)ranking.set(GraphSourceRanking.create(compiler,manual.stock(),manual.external(),target,true,budget));return ranking.get();})){
    try{while(!view.step()){}plan=view.result();row.addProperty("result",plan==null?"NO_WITNESS":plan.result().name());}catch(PlanningBudget.Exhausted limit){row.addProperty("result",limit.limit().name());}
    row.addProperty("feasible",plan!=null&&plan.feasible());if(plan!=null&&plan.feasible()){PlanVerifier.verify(plan);PlanVerifier.verifyRuntimeInventory(plan);plan.initialExact().forEach((key,needed)->{if(!manual.external().contains(key)&&needed.compareTo(BigInteger.valueOf(manual.stock().getOrDefault(key,0L)))>0)throw new AssertionError("Overdraw "+key);});plan.executionDependencies();row.addProperty("verified",true);}
   }finally{if(ranking.get()!=null)ranking.get().close();}
   row.addProperty("work",budget.nodes());row.addProperty("peak_bytes",budget.peakBytes());row.addProperty("ms",(System.nanoTime()-started)/1e6);row.addProperty("diagnostics",budget.diagnostics());
  }
  var report=new JsonObject();report.add("warmup",warmup);report.add("final",row);report.addProperty("mode",args[3]);Files.writeString(Path.of(args[2]),MixedSweep.JSON.toJson(report));System.out.println(row.get("result")+" work="+row.get("work"));
 }
}
