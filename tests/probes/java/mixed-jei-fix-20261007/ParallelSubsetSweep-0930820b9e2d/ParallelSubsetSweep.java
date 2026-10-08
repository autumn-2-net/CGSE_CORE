package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.math.BigInteger;
public final class ParallelSubsetSweep {
 static final class Observed implements PlanningScheduler.Work<GraphPlan<String>> {
  final CatalystPlanningWork<String> work;final CountDownLatch closed=new CountDownLatch(1);
  Observed(CatalystPlanningWork<String> work){this.work=work;}
  public boolean advance(PlanningScheduler.Slice slice){return work.advance(slice);}
  public GraphPlan<String> result(){return work.result();}
  public GraphPlan<String> limited(PlanningBudget.Exhausted limit){return work.limited(limit);}
  public CompletableFuture<?> waitingFor(){return work.waitingFor();}
  public void close(){try{work.close();}finally{closed.countDown();}}
 }
 static GraphCompiler<String> compiler(MixedSweep.Data manual,MixedSweep.Data extra,JsonObject c){
  long seed=c.get("seed").getAsLong();double fraction=c.get("fraction").getAsDouble();var selected=new ArrayList<>(extra.recipes());Collections.shuffle(selected,new Random(seed));selected=new ArrayList<>(selected.subList(0,(int)Math.ceil(fraction*selected.size())));var added=MinimizeStress.data(selected);
  if(c.get("mode").getAsString().equals("subset-shuffle"))return MixedSweep.compiler(manual,added,"random",seed^0xC6A4A7935BD1E995L);
  var random=new Random(seed^0xC6A4A7935BD1E995L);var catalog=SubsetSweep.interleave(manual.recipes(),selected,random);Map<String,List<GraphRecipe<String>>> producers=new LinkedHashMap<>();var keys=new LinkedHashSet<>(manual.producers().keySet());keys.addAll(added.producers().keySet());
  for(String key:keys)producers.put(key,SubsetSweep.interleave(manual.producers().getOrDefault(key,List.of()),added.producers().getOrDefault(key,List.of()),random));return new GraphCompiler<>(catalog,producers);
 }
 public static void main(String[] args)throws Exception{
  Path input=Path.of(args[0]);var config=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();var manual=MixedSweep.read(input.resolve("manual.json"));var extra=MixedSweep.read(input.resolve("extra-virtual.json"));int workers=Integer.parseInt(args[3]);boolean expanded=Boolean.parseBoolean(args[4]);long base=config.get("work").getAsLong(),limit=PlanningBudget.parallelWorkLimit(base,workers,expanded);int n=0,good=0,errors=0;
  try(var out=Files.newBufferedWriter(Path.of(args[2]));var scheduler=new PlanningScheduler(workers,4,2048,2_000_000L)){
   for(var group:config.getAsJsonArray("catalogs")){
    var c=group.getAsJsonObject();var compiler=compiler(manual,extra,c);String catalogHash=SubsetSweep.hash(compiler.catalog().stream().map(GraphRecipe::id).toList());
    for(var element:c.getAsJsonArray("requests")){
     var request=element.getAsJsonObject();String target=request.get("target").getAsString();long amount=request.get("amount").getAsLong();var row=request.deepCopy();row.addProperty("mode",c.get("mode").getAsString());row.add("fraction",c.get("fraction"));row.add("seed",c.get("seed"));row.addProperty("workers",workers);row.addProperty("expanded",expanded);row.addProperty("work_limit",limit);row.addProperty("memory_limit",config.get("memory").getAsLong());row.addProperty("catalog_sha256",catalogHash);
     var budget=new PlanningBudget(0,limit,config.get("memory").getAsLong(),()->false,System::nanoTime);budget.enableMetrics();var observed=new Observed(new CatalystPlanningWork<>(new CatalystPolicy(4096,64),budget,policy->new GraphPlanningWork<>(compiler,target,amount,manual.stock(),manual.external(),Map.of(),true,true,budget).catalysts(policy)));long started=System.nanoTime();
     try{
      var future=scheduler.submit(observed,budget);GraphPlan<String> plan;
      try{plan=future.get(180,TimeUnit.SECONDS);}catch(TimeoutException e){future.cancel(false);throw e;}
      if(!observed.closed.await(30,TimeUnit.SECONDS))throw new AssertionError("Work did not close");row.addProperty("result",plan.result().name());row.addProperty("feasible",plan.feasible());row.add("missing",MixedSweep.JSON.toJsonTree(plan.missingExact()));
      if(plan.feasible()){PlanVerifier.verify(plan);PlanVerifier.verifyRuntimeInventory(plan);plan.initialExact().forEach((key,needed)->{if(!manual.external().contains(key)&&needed.compareTo(BigInteger.valueOf(manual.stock().getOrDefault(key,0L)))>0)throw new AssertionError("Overdraw "+key);});plan.executionDependencies();row.addProperty("verified",true);good++;}
     }catch(Throwable error){row.addProperty("result","ERROR");row.addProperty("feasible",false);row.addProperty("error",error.toString());errors++;}
     row.addProperty("ms",(System.nanoTime()-started)/1e6);row.addProperty("work",budget.nodes());row.addProperty("peak_bytes",budget.peakBytes());row.addProperty("reserved_after_close",budget.reservedBytes());row.addProperty("diagnostics",budget.diagnostics());row.add("metrics",MixedSweep.JSON.toJsonTree(budget.metrics()));
     out.write(MixedSweep.JSON.toJson(row));out.newLine();out.flush();if(++n%10==0)System.out.println("done="+n+" feasible="+good+" errors="+errors+" workers="+workers+" expanded="+expanded);
    }
   }
  }System.out.println("FINAL "+good+"/"+n+" errors="+errors);if(errors>0)throw new AssertionError("Errors="+errors);
 }
}
