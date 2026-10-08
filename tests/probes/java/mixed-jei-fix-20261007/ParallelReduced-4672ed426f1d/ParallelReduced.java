package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.math.BigInteger;
public final class ParallelReduced {
 public static void main(String[] args)throws Exception{
  Path file=Path.of(args[0]);var data=MixedSweep.read(file);var root=JsonParser.parseString(Files.readString(file)).getAsJsonObject();var config=root.getAsJsonObject("config");var request=root.getAsJsonObject("request");
  int workers=Integer.parseInt(args[2]);boolean expanded=Boolean.parseBoolean(args[3]);long base=config.get("work").getAsLong(),limit=PlanningBudget.parallelWorkLimit(base,workers,expanded);
  var compiler=new GraphCompiler<>(data.recipes(),data.producers());var budget=new PlanningBudget(0,limit,config.get("memory").getAsLong(),()->false,System::nanoTime);budget.enableMetrics();
  var row=request.deepCopy();row.addProperty("workers",workers);row.addProperty("expanded",expanded);row.addProperty("work_limit",limit);
  var observed=new ParallelSubsetSweep.Observed(new CatalystPlanningWork<>(new CatalystPolicy(4096,64),budget,policy->new GraphPlanningWork<>(compiler,request.get("target").getAsString(),request.get("amount").getAsLong(),data.stock(),data.external(),Map.of(),true,true,budget).catalysts(policy)));
  long started=System.nanoTime();try(var scheduler=new PlanningScheduler(workers,4,2048,2_000_000L)){
   var plan=scheduler.submit(observed,budget).get(180,TimeUnit.SECONDS);if(!observed.closed.await(30,TimeUnit.SECONDS))throw new AssertionError("Work did not close");
   row.addProperty("result",plan.result().name());row.addProperty("feasible",plan.feasible());
   if(plan.feasible()){PlanVerifier.verify(plan);PlanVerifier.verifyRuntimeInventory(plan);plan.initialExact().forEach((key,needed)->{if(!data.external().contains(key)&&needed.compareTo(BigInteger.valueOf(data.stock().getOrDefault(key,0L)))>0)throw new AssertionError("Overdraw "+key);});plan.executionDependencies();row.addProperty("verified",true);}
  }
  row.addProperty("ms",(System.nanoTime()-started)/1e6);row.addProperty("work",budget.nodes());row.addProperty("peak_bytes",budget.peakBytes());row.addProperty("reserved_after_close",budget.reservedBytes());row.addProperty("diagnostics",budget.diagnostics());row.add("metrics",MixedSweep.JSON.toJsonTree(budget.metrics()));Files.writeString(Path.of(args[1]),MixedSweep.JSON.toJson(row));System.out.println(row.get("result")+" workers="+workers+" expanded="+expanded+" work="+row.get("work")+" ms="+row.get("ms"));
 }
}
