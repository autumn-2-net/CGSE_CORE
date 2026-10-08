package org.cgse.core;
import java.nio.file.*;import java.util.*;import com.google.gson.Gson;
public final class AutoExpandProbe {
 static Map<String,Object> run(long seed,boolean reload){
  var grow=new GraphRecipe<String>("grow","grow",List.of(new GraphRecipe.Slot<>("S",1)),Map.of("P",2L));
  var back=new GraphRecipe<String>("back","back",List.of(new GraphRecipe.Slot<>("P",1)),Map.of("S",1L));
  var body=new PlanStep.Sequence(List.of(new PlanStep.Batch("grow",1),new PlanStep.Batch("back",1)));
  var plan=new GraphPlan<>("P",1000,true,new PlanStep.Repeat(body,1000),Map.of("grow",grow,"back",back),Map.of("S",seed),Map.of("S",seed),Map.of(),GraphPlan.Result.FEASIBLE,0,0);
  class Host implements GraphJobRuntime.Adapter<String>{
   GraphJobRuntime<String> runtime=new GraphJobRuntime<>(plan,plan.initial(),Map.of());long maximum,deliver,pushes;Map<String,Long>returns=new HashMap<>();
   public long capacity(GraphRecipe<String> r,long n){maximum=Math.max(maximum,n);return n;}
   public GraphJobRuntime.Outcome push(GraphRecipe<String> r,long n,Map<String,Long>in){r.outputs().forEach((k,v)->returns.merge(k,v*n,Math::addExact));pushes++;return GraphJobRuntime.Outcome.ACCEPTED;}
   public long deliver(String k,long n){if(k.equals("P"))deliver+=n;return n;}public long refund(String k,long n){return n;}
   void tick(long tick){var old=new HashMap<>(returns);returns.clear();old.forEach((k,v)->{if(runtime.accept(k,v,false)!=v)throw new AssertionError("return lost");});runtime.tick(this,tick,64);if(reload&&tick%17==0)runtime=new GraphJobRuntime<>(runtime.snapshot());}
  }
  var h=new Host();long tick=0;while(!h.runtime.finished()&&tick<10000)h.tick(++tick);
  if(h.runtime.state()!=GraphJobRuntime.State.COMPLETED||h.deliver!=1000)throw new AssertionError("runtime incomplete "+h.runtime.state()+" "+h.runtime.reason());
  return Map.of("seed",seed,"reload",reload,"maximumOffered",h.maximum,"pushes",h.pushes,"ticks",tick);
 }
 public static void main(String[] args)throws Exception{var rows=List.of(run(1,false),run(4,false),run(1,true),run(4,true));System.out.println(rows);Files.writeString(Path.of(args[0],"auto-expand.json"),new Gson().toJson(rows));}
}
