package org.cgse.core;
import java.util.*;
import java.math.BigInteger;
public class LoopBatchingChecks {
 static int checks;
 static final BigInteger MAX=BigInteger.valueOf(Long.MAX_VALUE);
 static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
 public static void main(String[] args){
  Random rng=new Random(828748);
  for(int t=0;t<10000;t++){
   var recipes=new LinkedHashMap<String,GraphRecipe<String>>();var counts=new LinkedHashMap<String,BigInteger>();var stock=new LinkedHashMap<String,BigInteger>();
   for(int k=0;k<4;k++)stock.put("k"+k,BigInteger.valueOf(rng.nextInt(45)));
   for(int i=0;i<2+rng.nextInt(4);i++){
    var in=new LinkedHashMap<String,Long>();var out=new LinkedHashMap<String,Long>();
    for(int k=0;k<4;k++){long n=rng.nextInt(7),m=rng.nextInt(7);if(n>0)in.put("k"+k,n);if(m>0)out.put("k"+k,m);}
    if(out.isEmpty())out.put("k0",1L);
    recipes.put("r"+i,r("r"+i,in,out));counts.put("r"+i,BigInteger.valueOf(1+rng.nextInt(4)));
   }
   long n=LoopBatching.iterations(counts,2+rng.nextInt(80),recipes,k->stock.getOrDefault(k,BigInteger.ZERO));
   if(n>0){
    var steps=new ArrayList<PlanStep>();counts.forEach((id,c)->steps.add(PlanStep.batch(id,c.multiply(BigInteger.valueOf(n)))));
    var sum=SequenceSummary.of(new PlanStep.Sequence(steps),recipes);
    for(String k:stock.keySet()){check(stock.get(k).compareTo(sum.required(k))>=0,"unfunded regroup");check(stock.get(k).add(sum.peak(k)).compareTo(MAX)<=0,"overflow regroup");}
   }
  }
  var a=r("a",Map.of("X",1L,"raw",1L),Map.of("Y",1L));var b=r("b",Map.of("Y",1L),Map.of("X",1L,"P",1L));
  var recipes=Map.of("a",a,"b",b);var steps=new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("a",1),new PlanStep.Batch("b",1))),10000);
  var p=new GraphPlan<>("P",10000,true,steps,recipes,Map.of("X",64L,"raw",10000L),Map.of("X",64L),Map.of(),GraphPlan.Result.FEASIBLE,0,0);
  PlanVerifier.verifyRuntimeInventory(p);
  var rt=new GraphJobRuntime<>(p,p.initial(),Map.of());
  var batches=new ArrayList<Long>();var ids=new ArrayList<String>();
  var adapter=new GraphJobRuntime.Adapter<String>(){
   public long capacity(GraphRecipe<String> r,long n){return n;}
   public GraphJobRuntime.Outcome push(GraphRecipe<String> r,long n,Map<String,Long> input){batches.add(n);ids.add(r.id());return GraphJobRuntime.Outcome.ACCEPTED;}
   public long refund(String k,long n){return n;}public long deliver(String k,long n){return n;}
  };
  for(int i=0;i<20&&batches.isEmpty();i++)rt.tick(adapter,i,1);
  check(batches.get(0)==64,"funded multi-recipe loop must dispatch 64, got "+batches);
  rt=new GraphJobRuntime<>(rt.snapshot());
  check(rt.accept("Y",32,false)==32,"partial real return");rt.tick(adapter,30,2);
  check(batches.size()==2&&batches.get(1)==32&&ids.get(1).equals("b"),"partial return streams: "+batches);
  for(int tick=31;tick<10000&&!rt.finished();tick++){
   for(var e:rt.expected().entrySet())rt.accept(e.getKey(),e.getValue(),false);
   rt.tick(adapter,tick,20);
   if(tick%7==0)rt=new GraphJobRuntime<>(rt.snapshot());
  }
  check(rt.finished(),"loop did not settle");
  var counts=new LinkedHashMap<String,BigInteger>();counts.put("a",BigInteger.ONE);counts.put("b",BigInteger.ONE);
  check(LoopBatching.iterations(counts,Long.MAX_VALUE,recipes,k->k.equals("X")||k.equals("raw")?MAX:BigInteger.ZERO)==Long.MAX_VALUE,"long funded loop");
  System.out.println("LOOP_BATCHING_PASS checks="+checks+" first_batches="+batches.subList(0,Math.min(6,batches.size()))+" total_dispatches="+batches.size());
 }
}
