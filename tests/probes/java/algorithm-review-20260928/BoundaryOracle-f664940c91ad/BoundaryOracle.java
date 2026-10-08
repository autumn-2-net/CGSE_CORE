package org.cgse.core;
import java.math.*;import java.util.*;import java.util.concurrent.*;
public final class BoundaryOracle {
 static final BigInteger Z=BigInteger.ZERO,O=BigInteger.ONE;
 static PlanningBudget budget(){return new PlanningBudget(0,20000000,256L<<20,()->false,System::nanoTime);}
 static GraphRecipe<String> recipe(String id,Map<String,Long>in,Map<String,Long>out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
 public static void main(String[]args)throws Exception{
  var rs=List.of(recipe("a",Map.of("A",1L),Map.of("B",1L,"Q",1L)),recipe("b",Map.of("B",1L),Map.of("A",1L,"Q",1L)));
  var owner=budget();try(var model=RecipeCountModel.create(new GraphCompiler<>(rs),"Q",6,Map.of("A",1L),Map.of(),Set.of(),Set.of(),false,owner)){
   for(int stop=1;stop<1024;stop++){int[]calls={0};int limit=stop;var b=new PlanningBudget(0,20000000,256L<<20,()->++calls[0]>=limit,System::nanoTime);try(var p=new CountBoundedSchedule<>(model,new BigInteger[]{BigInteger.valueOf(3),BigInteger.valueOf(3)},b)){while(!p.step()){}}catch(CancellationException expected){}if(b.reservedBytes()!=0)throw new AssertionError("schedule cancellation leak "+stop);}
   for(long bytes:new long[]{1,2048,8192,16384,32768,65536}){var b=new PlanningBudget(0,20000000,bytes,()->false,System::nanoTime);try(var p=new CountBoundedSchedule<>(model,new BigInteger[]{O,O},b)){while(!p.step()){}if(p.result()==CountSchedule.Result.DEAD)throw new AssertionError("low memory false infeasibility");}if(b.reservedBytes()!=0)throw new AssertionError("schedule memory leak");}
   var b=budget();try(var p=new CountBoundedSchedule<>(model,new BigInteger[]{O.shiftLeft(130),O},b)){if(!p.step()||p.result()!=CountSchedule.Result.UNKNOWN)throw new AssertionError("large horizon enumerated");}if(b.reservedBytes()!=0)throw new AssertionError("large horizon leak");
  }if(owner.reservedBytes()!=0)throw new AssertionError("owner leak");
  int guards=0;for(int seeded=0;seeded<2;seeded++)for(int replenish=0;replenish<2;replenish++){
   var recipes=new ArrayList<GraphRecipe<String>>();for(int i=0;i<20;i++)recipes.add(recipe("c"+i,Map.of("K"+i,1L,"F",1L),Map.of("K"+((i+1)%20),1L,"Q",1L)));
   if(replenish!=0)recipes.add(recipe("seed",Map.of("S",1L),Map.of("K0",1L)));
   var b=budget();var stock=new HashMap<String,Long>();stock.put("F",20L);if(seeded!=0)stock.put("K0",1L);if(replenish!=0)stock.put("S",1L);
   try(var model=RecipeCountModel.create(new GraphCompiler<>(recipes),"Q",20,stock,Map.of(),Set.of(),Set.of(),false,b);var p=new CountExecution<>(model,b)){
    var point=new ExactRational[model.recipes.size()];Arrays.fill(point,ExactRational.ONE);boolean violation=false;for(var proof:p.proofs()){if(proof.materials().size()==20)guards++;if(proof.guard().violated(point,b))violation=true;}
    if(violation!=(seeded==0&&replenish==0))throw new AssertionError("feedback pool omitted seed/replenishment");
   }if(b.reservedBytes()!=0)throw new AssertionError("pool leak");
  }if(guards==0)throw new AssertionError("large feedback pool never exercised");
  var b=budget();b.enableMetrics();try(var history=new CountBranchHistory(4,b)){
   var executor=Executors.newFixedThreadPool(4);try{var jobs=new ArrayList<Future<?>>();for(int worker=0;worker<4;worker++){final int id=worker;jobs.add(executor.submit(()->{long start=b.threadWork();for(int i=0;i<1000;i++){b.check();history.observe(id,i%2,new ExactRational(O,BigInteger.TWO),i%17);}b.strategy("worker"+id,b.threadWork()-start,17);if(!history.reliable(id)||!Double.isFinite(history.score(id,new ExactRational(O,O.shiftLeft(1024)))))throw new AssertionError("history score");}));}for(var job:jobs)job.get();}finally{executor.shutdownNow();}
   if(b.nodes()!=4000||b.metrics().strategies().size()!=4)throw new AssertionError("strategy accounting");for(var metric:b.metrics().strategies().values())if(metric.chargedWork()!=1000||metric.steps()!=1)throw new AssertionError("cross-worker charged work");
  }if(b.reservedBytes()!=0)throw new AssertionError("history leak");
  var silent=budget();silent.strategy("ignored",17,5);if(!silent.metrics().strategies().isEmpty())throw new AssertionError("disabled metrics overhead");
  System.out.println("PASS 1023 bounded-schedule cancellations, 6 memory cutoffs, 130-bit decline; 20-stage startup pools with replenishment; four-worker inference history and exact per-worker work accounting");
 }
}
