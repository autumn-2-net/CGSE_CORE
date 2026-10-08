package org.cgse.core;
import java.util.*;import java.nio.file.*;import java.lang.reflect.*;import java.util.function.*;
import appeng.api.crafting.*;import appeng.api.stacks.*;import appeng.api.networking.crafting.*;
import appeng.crafting.*;import appeng.crafting.pattern.*;
import org.gtlcore.gtlcore.integration.ae2.crafting.compiled.*;import net.minecraft.world.level.Level;
import static org.cgse.core.ContrastProbe.*;
import static org.cgse.core.AdversarialProbe.*;
/** Full pinned MAX_FAST execution including actual GTL boundary/tree/stack fallback and AE2 simulation attempt. */
public final class FullLegacyProbe {
 static AEKey key(String name){return new AEKey(name);}
 static String primary(GraphRecipe<String> r,Fixture f){
  if(r.outputs().containsKey(f.target()))return f.target();
  for(String k:r.outputs().keySet())if(k.matches("(?:b\\d+\\.)?D\\d+"))return k;
  for(String k:r.outputs().keySet())if(!r.inputs().containsKey(k))return k;
  return r.outputs().keySet().iterator().next();
 }
 static Map<String,Object> one(Fixture f,String indexing,long budget)throws Exception{
  Map<AEKey,List<IPatternDetails>> catalog=new LinkedHashMap<>();
  for(var r:f.recipes()){
   IPatternDetails.IInput[] inputs=r.inputs().entrySet().stream().map(e->new IPatternDetails.IInput(){public GenericStack[] getPossibleInputs(){return new GenericStack[]{new GenericStack(key(e.getKey()),1)};}public long getMultiplier(){return e.getValue();}public boolean isValid(AEKey k,Level l){return k.equals(key(e.getKey()));}public AEKey getRemainingKey(AEKey k){return null;}}).toArray(IPatternDetails.IInput[]::new);
   String preferred=primary(r,f);List<GenericStack> outputs=new ArrayList<>();outputs.add(new GenericStack(key(preferred),r.outputs().get(preferred)));for(var e:r.outputs().entrySet())if(!e.getKey().equals(preferred))outputs.add(new GenericStack(key(e.getKey()),e.getValue()));
   IPatternDetails p=new AEProcessingPattern(r.id(),inputs,outputs.toArray(GenericStack[]::new));
   Map<String,Long> encodedIn=new LinkedHashMap<>(),encodedOut=new LinkedHashMap<>();
   for(var in:inputs){var t=in.getPossibleInputs()[0];encodedIn.merge(t.what().id,Math.multiplyExact(t.amount(),in.getMultiplier()),Math::addExact);}
   for(var out:outputs)encodedOut.merge(out.what().id,out.amount(),Math::addExact);
   if(!encodedIn.equals(r.inputs())||!encodedOut.equals(r.outputs()))throw new AssertionError("Pattern host normalization changed recipe "+r.id());
   if(indexing.equals("all_outputs"))for(String k:r.outputs().keySet())catalog.computeIfAbsent(key(k),ignored->new ArrayList<>()).add(p);
   else catalog.computeIfAbsent(key(preferred),ignored->new ArrayList<>()).add(p);
  }
  ICraftingService service=new ICraftingService(){public Collection<IPatternDetails> getCraftingFor(AEKey k){return catalog.getOrDefault(k,List.of());}public boolean canEmitFor(AEKey k){return false;}public boolean isCraftable(AEKey k){return catalog.containsKey(k);}public AEKey getFuzzyCraftable(AEKey k,Predicate<AEKey> p){return isCraftable(k)&&p.test(k)?k:null;}};
  Map<AEKey,Long> stock=new LinkedHashMap<>();f.stock().forEach((k,v)->stock.put(key(k),v));
  long start=System.nanoTime();CraftingCalculation job=new CraftingCalculation(service,stock,key(f.target()),f.amount(),budget);CraftingPlan plan=null;String status="",detail="";
  try{plan=(CraftingPlan)job.computePlan();status=plan.simulation?"MISSING_PREVIEW":"FEASIBLE";}catch(InterruptedException limit){status="TIMEOUT";}catch(Throwable error){status="ERROR";detail=error.toString();error.printStackTrace();}
  double ms=(System.nanoTime()-start)/1e6;boolean valid=false;
  Map<String,Long> counts=new LinkedHashMap<>(),missing=new LinkedHashMap<>(),required=new LinkedHashMap<>();
  if(plan!=null){plan.counts.forEach((k,v)->counts.put(((AEProcessingPattern)k).id,v));for(var e:plan.missing)if(e.getLongValue()!=0)missing.put(e.getKey().id,e.getLongValue());for(var e:plan.required)if(e.getLongValue()!=0)required.put(e.getKey().id,e.getLongValue());
   if(!plan.simulation){Map<String,GraphRecipe<String>> recipes=new LinkedHashMap<>();f.recipes().forEach(r->recipes.put(r.id(),r));List<PlanStep> steps=new ArrayList<>();for(Object[] op:plan.trace)steps.add(new PlanStep.Batch(((AEProcessingPattern)op[0]).id,(Long)op[1]));
    try{verify(f,summary(new PlanStep.Sequence(steps),recipes));valid=true;}catch(AssertionError bad){detail="recorded_count_order_not_executable: "+bad;}
   }
  }
  Map<String,Object> metrics=new TreeMap<>();for(Field field:MaxFastMetrics.class.getDeclaredFields()){field.setAccessible(true);Object v=field.get(job.gtlcore$getMaxFastMetrics());if(v instanceof Number n&&n.doubleValue()!=0||v instanceof String s&&!s.equals("none"))metrics.put(field.getName(),v);}
  var row=new LinkedHashMap<String,Object>();row.put("case",f.name());row.put("recipes",f.recipes().size());row.put("solver","MAX_FAST_FULL");row.put("indexing",indexing);row.put("status",status);row.put("ms",ms);row.put("budget_ms",budget);row.put("detail",detail);row.put("trace_verified",valid);row.put("attempts",job.attempts);row.put("simulated_attempts",job.simulatedAttempts);row.put("metrics",metrics);row.put("counts",counts);row.put("missing",missing);row.put("required",required);return row;
 }
 public static void main(String[] args)throws Exception{
  Path input=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectories(output);String indexing=args.length>2?args[2]:"primary_only";long budget=args.length>3?Long.parseLong(args[3]):3000;int reps=args.length>4?Integer.parseInt(args[4]):1;
  Set<String> selected=args.length>5&&!args[5].equals("all")?new HashSet<>(Files.readAllLines(Path.of(args[5]))):null;
  for(int i=0;i<12;i++){var control=one(ContrastProbe.dag(60),indexing,3000);if(!control.get("status").equals("FEASIBLE"))throw new AssertionError(control);}
  for(Path file:Files.list(input).sorted().toList())if(file.toString().endsWith(".json")){
   var d=FusionProbe.map(new FusionProbe.Json(Files.readString(file)).value());Fixture f=FusionProbe.fixture(d);if(selected!=null&&!selected.contains(f.name()))continue;
   for(int rep=0;rep<reps;rep++){var r=one(f,indexing,budget);r.put("rep",rep);r.put("truth",d.get("truth"));Files.writeString(output.resolve("results.jsonl"),json(r)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);System.out.println(f.name()+" "+r.get("status")+" "+r.get("ms")+" attempts="+r.get("attempts")+" "+r.get("detail"));System.out.flush();}
  }
 }
}
