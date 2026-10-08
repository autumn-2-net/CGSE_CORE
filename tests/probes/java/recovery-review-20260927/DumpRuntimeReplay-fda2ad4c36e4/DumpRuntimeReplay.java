import com.google.gson.*;
import org.cgse.core.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;

/** Local execution/conservation replay against the detached real-world catalog. */
public class DumpRuntimeReplay extends DumpReplay {
 static final BigInteger MAX=BigInteger.valueOf(Long.MAX_VALUE);
 static long checks;
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static void add(Map<String,BigInteger> dest,String key,long count){dest.merge(key,BigInteger.valueOf(count),BigInteger::add);}
 static void load(String path)throws Exception{
  var root=JsonParser.parseString(Files.readString(Path.of(path))).getAsJsonObject();
  root.getAsJsonArray("keys").forEach(k->{var o=k.getAsJsonObject();labels.put(o.get("key").getAsString(),o.get("label").getAsString());});
  stock=amounts(root.getAsJsonObject("planningStock"));external=new HashSet<>(strings(root.getAsJsonArray("external")));
  root.getAsJsonObject("discovery").entrySet().forEach(e->discovery.put(e.getKey(),strings(e.getValue().getAsJsonArray())));
  root.getAsJsonArray("patterns").forEach(p->{var o=p.getAsJsonObject();String b=o.get("binding").getAsString();priorities.put(b,o.get("priority").getAsInt());dependencies.put(b,strings(o.getAsJsonArray("dependencies")));});
  for(var value:root.getAsJsonArray("recipes")){
   var r=value.getAsJsonObject();var slots=new ArrayList<GraphRecipe.Slot<String>>();for(var sv:r.getAsJsonArray("slots")){
    var s=sv.getAsJsonObject();slots.add(new GraphRecipe.Slot<>(s.get("key").getAsString(),s.get("amount").getAsLong(),s.get("slot").getAsInt(),s.get("configuration").getAsBoolean(),s.get("reusable").getAsBoolean()));
   }
   var recipe=new GraphRecipe<>(r.get("id").getAsString(),r.get("binding").getAsString(),slots,amounts(r.getAsJsonObject("outputs")));
   variants.computeIfAbsent(recipe.binding(),k->new ArrayList<>()).add(recipe);
  }
 }
 static class Machines implements GraphJobRuntime.Adapter<String>{
  GraphJobRuntime<String> runtime;
  final GraphPlan<String> plan;
  final Map<String,BigInteger> inputs=new HashMap<>(),outputs=new HashMap<>(),deliveries=new HashMap<>(),refunds=new HashMap<>(),runs=new HashMap<>();
  final ArrayList<Map<String,Long>> flights=new ArrayList<>();
  final int mode;
  long pushes,transfers,returns;
  Machines(GraphPlan<String> p,int mode){plan=p;runtime=new GraphJobRuntime<>(p,p.initial(),Map.of());this.mode=mode;}
  public long capacity(GraphRecipe<String> recipe,long requested){return mode==2?Math.min(requested,Long.MAX_VALUE/97):requested;}
  public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe,long count,Map<String,Long> taken){
   check(recipe.dispatchInputs(count).equals(taken),"incorrect physical escrow");taken.forEach((k,n)->add(inputs,k,n));add(runs,recipe.id(),count);pushes++;
   var out=new LinkedHashMap<String,Long>();recipe.executionOutputs().forEach((k,n)->{long number=Math.multiplyExact(n,count);add(outputs,k,number);out.put(k,number);});
   if(mode==0)out.forEach((k,n)->check(runtime.accept(k,n,false)==n,"synchronous return refused: "+k));else flights.add(out);
   return GraphJobRuntime.Outcome.ACCEPTED;
  }
  public long deliver(String key,long amount){check(key.equals(plan.target()),"wrong delivery");long accepted=mode==2?Math.min(amount,Long.MAX_VALUE/11):amount;add(deliveries,key,accepted);transfers++;return accepted;}
  public long refund(String key,long amount){long accepted=mode==2?Math.min(amount,Long.MAX_VALUE/13):amount;add(refunds,key,accepted);transfers++;return accepted;}
  void returnSome(){
   for(int i=flights.size()-1;i>=0;i--){var f=flights.get(i);var keys=new ArrayList<>(f.keySet());Collections.reverse(keys);
    for(String k:keys){long available=f.get(k);long offer=mode==2?Math.min(available,Long.MAX_VALUE/7):available;long got=runtime.accept(k,offer,false);check(got==offer,"physical return refused: "+k+" got="+got+" offer="+offer);returns++;if(got==available)f.remove(k);else f.put(k,available-got);}
    if(f.isEmpty())flights.remove(i);
   }
  }
  void conserved(){
   var keys=new HashSet<>(plan.initial().keySet());keys.addAll(inputs.keySet());keys.addAll(outputs.keySet());keys.addAll(runtime.owned().keySet());keys.addAll(deliveries.keySet());keys.addAll(refunds.keySet());
   var pending=new HashMap<String,BigInteger>();flights.forEach(f->f.forEach((k,n)->add(pending,k,n)));
   for(String k:keys){var total=BigInteger.valueOf(plan.initial().getOrDefault(k,0L)).add(outputs.getOrDefault(k,BigInteger.ZERO)).subtract(inputs.getOrDefault(k,BigInteger.ZERO));
    var physical=BigInteger.valueOf(runtime.held(k)).add(pending.getOrDefault(k,BigInteger.ZERO)).add(deliveries.getOrDefault(k,BigInteger.ZERO)).add(refunds.getOrDefault(k,BigInteger.ZERO));
    check(total.equals(physical),"conservation failed "+k+" actual="+physical+" expected="+total);
    check(BigInteger.valueOf(runtime.held(k)).add(BigInteger.valueOf(runtime.waiting(k))).compareTo(MAX)<=0,"physical headroom overflow "+k);
   }
   check(deliveries.getOrDefault(plan.target(),BigInteger.ZERO).add(BigInteger.valueOf(runtime.remainingDelivery())).equals(BigInteger.valueOf(plan.amount())),"delivery remainder changed");
  }
  JsonObject execute(){
   long start=System.nanoTime(),lastChange=0;int t;
   for(t=0;t<30000&&!runtime.finished();t++){
    long before=runtime.version();runtime.tick(this,t,128);if(mode>0)returnSome();
    if(t%7==0){conserved();if(mode>0)runtime=new GraphJobRuntime<>(runtime.snapshot());}
    if(runtime.version()!=before)lastChange=t;
    if(runtime.state()==GraphJobRuntime.State.NEEDS_ATTENTION||t-lastChange>60||System.nanoTime()-start>2_000_000_000L)break;
   }
   conserved();var row=new JsonObject();row.addProperty("mode",mode);row.addProperty("state",runtime.state().name());row.addProperty("reason",runtime.reason());row.addProperty("ticks",t);row.addProperty("pushes",pushes);row.addProperty("transfers",transfers);row.addProperty("ms",(System.nanoTime()-start)/1e6);
   if(runtime.state()==GraphJobRuntime.State.COMPLETED){check(deliveries.get(plan.target()).equals(BigInteger.valueOf(plan.amount())),"inexact completed delivery");check(runs.equals(plan.patternTimesExact()),"run counts truncated");check(flights.isEmpty(),"unfinished returns");}
   else {row.add("owned",JSON.toJsonTree(runtime.owned()));row.add("pending",JSON.toJsonTree(runtime.pendingRuns()));row.add("expected",JSON.toJsonTree(runtime.expected()));row.addProperty("remaining",Long.toString(runtime.remainingDelivery()));row.addProperty("witness",plan.steps().toString().substring(0,Math.min(2000,plan.steps().toString().length())));}
   return row;
  }
 }
 public static void main(String[] args)throws Exception{
  load(args[0]);var selected=Files.readString(Path.of(args[2])).trim().split(",|\\s+");var totals=new TreeMap<String,Integer>();
  try(var out=new PrintWriter(Files.newBufferedWriter(Path.of(args[1])))){
   for(String target:selected){
    var budget=new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);
    var c=compiler(target);var work=new GraphPlanningWork<>(c,target,Long.MAX_VALUE,stock,external,Map.of(),true,true,budget).catalysts(CatalystPolicy.MINIMAL);
    while(!work.step()){}var p=work.result();work.close();
    if(!p.feasible()){System.out.println("SKIP "+target+" "+p.result());continue;}
    for(int mode=0;mode<3;mode++){
     var row=new JsonObject();row.addProperty("target",target);row.addProperty("label",labels.get(target));row.addProperty("mode",mode);
     try{var result=new Machines(p,mode).execute();result.entrySet().forEach(e->row.add(e.getKey(),e.getValue()));}catch(Throwable e){row.addProperty("state","ERROR");row.addProperty("error",e.toString());}
     out.println(JSON.toJson(row));out.flush();String status=row.get("state").getAsString();totals.merge(status,1,Integer::sum);
     if(!status.equals("COMPLETED"))System.out.println("FAIL "+target+" "+labels.get(target)+" mode="+mode+" "+row.get("reason")+" "+row.get("error"));
    }
   }
  }
  System.out.println("FINAL "+totals+" checks="+checks);
 }
}
