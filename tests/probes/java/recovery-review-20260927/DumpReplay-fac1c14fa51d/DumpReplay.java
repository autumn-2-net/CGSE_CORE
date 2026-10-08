import com.google.gson.*;
import org.cgse.core.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Runs only detached values; no Minecraft, Forge, AE or provider code on the classpath. */
public class DumpReplay {
 static final Gson JSON=new Gson();
 static Map<String,Long> stock;
 static Set<String> external;
 static Map<String,String> labels=new HashMap<>();
 static Map<String,List<GraphRecipe<String>>> variants=new HashMap<>();
 static Map<String,List<String>> discovery=new HashMap<>(), dependencies=new HashMap<>();
 static Map<String,Integer> priorities=new HashMap<>();
 static PrintWriter output;
 static AtomicInteger done=new AtomicInteger();
 static Map<String,Long> totals=new TreeMap<>();
 static String snapshotPath;
 static Map<String,Long> amounts(JsonObject o){var m=new LinkedHashMap<String,Long>();o.entrySet().forEach(e->m.put(e.getKey(),e.getValue().getAsLong()));return m;}
 static List<String> strings(JsonArray a){var r=new ArrayList<String>();a.forEach(v->r.add(v.getAsString()));return r;}
 static GraphCompiler<String> compiler(String target){
  var queue=new ArrayDeque<String>();var seen=new HashSet<String>();var used=new LinkedHashSet<String>();var recipes=new ArrayList<GraphRecipe<String>>();queue.add(target);
  while(!queue.isEmpty()){
   String key=queue.removeFirst();if(!seen.add(key))continue;
   for(String binding:discovery.getOrDefault(key,List.of()))if(used.add(binding)){
    recipes.addAll(variants.getOrDefault(binding,List.of()));queue.addAll(dependencies.getOrDefault(binding,List.of()));
   }
  }
  recipes.sort(Comparator.comparingInt((GraphRecipe<String> r)->priorities.getOrDefault(r.binding(),Integer.MIN_VALUE)).reversed());
  return new GraphCompiler<>(recipes);
 }
 static GraphPlan<String> run(GraphCompiler<String> compiler,String target,long amount,String mode,Map<String,Long> available,String changed){
  long start=System.nanoTime();var budget=new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);
  var row=new JsonObject();row.addProperty("target",target);row.addProperty("label",labels.get(target));row.addProperty("amount",Long.toString(amount));row.addProperty("mode",mode);row.addProperty("changed",changed);
  GraphPlan<String> result=null;
  try{
   boolean force=!(external.contains(target)&&compiler.producers(target).isEmpty());
   var work=new GraphPlanningWork<>(compiler,target,amount,available,external,Map.of(),true,force,budget).catalysts(CatalystPolicy.MINIMAL);
   while(!work.step()){}
   result=work.result();work.close();row.addProperty("result",result.result().toString());row.addProperty("missingKeys",result.missingExact().size());
   if(result.feasible()){
    PlanVerifier.verify(result);
    for(var need:result.initialExact().entrySet())if(!external.contains(need.getKey())&&need.getValue().compareTo(BigInteger.valueOf(available.getOrDefault(need.getKey(),0L)))>0)throw new AssertionError("stock overspend "+need);
    try{PlanVerifier.verifyRuntimeInventory(result);row.addProperty("physical","fits");}catch(ArithmeticException full){row.addProperty("physical","LONG_CAPACITY");
     var summary=SequenceSummary.of(result.steps(),result.recipes());var issues=new JsonArray();
     var keys=summary.keys();keys.addAll(result.initialExact().keySet());
     for(String key:keys){var initial=result.initialExact().getOrDefault(key,BigInteger.ZERO);var finish=initial.add(summary.delta(key));var peak=initial.add(summary.peak(key));
      if(initial.compareTo(BigInteger.valueOf(Long.MAX_VALUE))>0||finish.compareTo(BigInteger.valueOf(Long.MAX_VALUE))>0||peak.compareTo(BigInteger.valueOf(Long.MAX_VALUE))>0){
       var issue=new JsonObject();issue.addProperty("key",key);issue.addProperty("label",labels.get(key));issue.addProperty("initial",initial.toString());issue.addProperty("final",finish.toString());issue.addProperty("peak",peak.toString());issue.addProperty("external",external.contains(key));issue.addProperty("target",key.equals(target));
       issue.addProperty("consumed",result.recipes().values().stream().anyMatch(r->r.inputs().containsKey(key)));issues.add(issue);
      }
     }row.add("capacityIssues",issues);
    }
   }else if(!result.missingExact().isEmpty()){
    var funded=new GraphPlan<>(target,amount,result.preserveSeeds(),result.steps(),result.recipes(),result.initialExact(),result.seeds(),Map.of(),GraphPlan.Result.FEASIBLE,budget.nodes(),0);
    PlanVerifier.verify(funded);
    for(var need:result.initialExact().entrySet())if(!external.contains(need.getKey())){
     var lack=need.getValue().subtract(BigInteger.valueOf(available.getOrDefault(need.getKey(),0L))).max(BigInteger.ZERO);
     if(!lack.equals(result.missingExact().getOrDefault(need.getKey(),BigInteger.ZERO)))throw new AssertionError("incorrect deficit "+need.getKey());
    }
   }
  }catch(PlanningBudget.Exhausted exhausted){row.addProperty("result",exhausted.limit().toString());row.addProperty("error",exhausted.toString());}
   catch(Throwable e){row.addProperty("result","EXCEPTION");row.addProperty("error",e.toString());result=null;}
  row.addProperty("nodes",budget.nodes());row.addProperty("ms",(System.nanoTime()-start)/1_000_000.0);row.addProperty("recipes",compiler.catalog().size());
  String status=row.get("result").getAsString();if(!status.startsWith("FEASIBLE")&&!status.startsWith("MISSING"))row.addProperty("diagnostics",budget.diagnostics());
  synchronized(DumpReplay.class){output.println(JSON.toJson(row));output.flush();totals.merge(status,1L,Long::sum);int n=done.incrementAndGet();if(n%100==0)System.out.println("progress="+n+" "+totals);}
  return result;
 }
 public static void main(String[] args)throws Exception{
  snapshotPath=args[0];var root=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
  if(root.get("boundedAlternatives").getAsBoolean())throw new IllegalStateException("Capture omitted input alternatives");
  root.getAsJsonArray("keys").forEach(k->{var o=k.getAsJsonObject();labels.put(o.get("key").getAsString(),o.get("label").getAsString());});
  stock=amounts(root.getAsJsonObject("planningStock"));external=new HashSet<>(strings(root.getAsJsonArray("external")));
  root.getAsJsonObject("discovery").entrySet().forEach(e->discovery.put(e.getKey(),strings(e.getValue().getAsJsonArray())));
  root.getAsJsonArray("patterns").forEach(p->{var o=p.getAsJsonObject();String b=o.get("binding").getAsString();priorities.put(b,o.get("priority").getAsInt());dependencies.put(b,strings(o.getAsJsonArray("dependencies")));});
  for(var value:root.getAsJsonArray("recipes")){
   var r=value.getAsJsonObject();var slots=new ArrayList<GraphRecipe.Slot<String>>();for(var sv:r.getAsJsonArray("slots")){
    var s=sv.getAsJsonObject();slots.add(new GraphRecipe.Slot<>(s.get("key").getAsString(),s.get("amount").getAsLong(),s.get("slot").getAsInt(),s.get("configuration").getAsBoolean(),s.get("reusable").getAsBoolean()));
   }
   var recipe=new GraphRecipe<>(r.get("id").getAsString(),r.get("binding").getAsString(),slots,amounts(r.getAsJsonObject("outputs")));
   if(!recipe.executionOutputs().equals(amounts(r.getAsJsonObject("executionOutputs"))))throw new AssertionError("lost return semantics");
   variants.computeIfAbsent(recipe.binding(),k->new ArrayList<>()).add(recipe);
  }
  var targets=strings(root.getAsJsonArray("targets"));targets.sort(Comparator.comparing(labels::get));
  if(args.length>3)targets.removeIf(t->Arrays.stream(args[3].split(",")).noneMatch(f->labels.get(t).contains(f)||t.equals(f)));
  output=new PrintWriter(Files.newBufferedWriter(Path.of(args[1])));
  int threads=Integer.parseInt(args[2]);var pool=Executors.newFixedThreadPool(threads);var tasks=new ArrayList<Future<?>>();
  long start=System.nanoTime();System.out.println("targets="+targets.size()+" variants="+variants.size()+" workers="+threads);
  for(String target:targets)tasks.add(pool.submit(()->{
   var compiler=compiler(target);GraphPlan<String> boundary=null;
   for(long amount:new long[]{1,Integer.MAX_VALUE,Long.MAX_VALUE}){
    var p=run(compiler,target,amount,"stock",stock,"");if(amount==Integer.MAX_VALUE&&p!=null&&p.feasible())boundary=p;
   }
   run(compiler,target,1,"empty",Map.of(),"all=0");
   if(boundary!=null){
    for(var need:boundary.initialExact().entrySet())if(!external.contains(need.getKey())&&compiler.producers(need.getKey()).isEmpty()&&need.getValue().signum()>0&&need.getValue().compareTo(BigInteger.valueOf(Long.MAX_VALUE))<=0){
     var changed=new LinkedHashMap<>(stock);changed.put(need.getKey(),need.getValue().longValueExact()-1);run(compiler,target,Integer.MAX_VALUE,"short_one",changed,need.getKey()+"="+changed.get(need.getKey()));break;
    }
   }
  }));
  pool.shutdown();for(var task:tasks)task.get();output.close();System.out.println("FINAL cases="+done+" wallMs="+(System.nanoTime()-start)/1_000_000.0+" "+totals);
 }
}
