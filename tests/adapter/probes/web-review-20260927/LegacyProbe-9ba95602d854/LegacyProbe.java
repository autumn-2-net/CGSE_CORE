package org.cgse.core;
import java.util.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.function.*;
import appeng.api.crafting.*;
import appeng.api.stacks.*;
import appeng.api.config.*;
import appeng.api.networking.crafting.*;
import appeng.crafting.*;
import appeng.crafting.inv.*;
import appeng.crafting.pattern.*;
import org.gtlcore.gtlcore.integration.ae2.crafting.*;
import org.gtlcore.gtlcore.integration.ae2.crafting.compiled.*;
import net.minecraft.world.level.Level;
import static org.cgse.core.ContrastProbe.*;
import static org.cgse.core.AdversarialProbe.*;

/** Executes unmodified MaxFastExecutor; AE host callbacks are explicitly NOT implemented; a callback is not itself an UNSAT result. */
public final class LegacyProbe {
 static final class UnsupportedHost extends RuntimeException {UnsupportedHost(String what){super(what);}}
 static Object defaultValue(Method m){Class<?> t=m.getReturnType();if(t==void.class)return null;if(t==boolean.class)return false;if(t==long.class)return 0L;if(t==int.class)return 0;return null;}
 @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,InvocationHandler h){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},h);}
 static AEKey key(String name){return new AEKey(name);}
 static String primary(GraphRecipe<String> r,Fixture f){
  if(r.outputs().containsKey(f.target()))return f.target();
  for(String k:r.outputs().keySet())if(k.matches("(?:b\\d+\\.)?D\\d+"))return k;
  for(String k:r.outputs().keySet())if(!r.inputs().containsKey(k))return k;
  return r.outputs().keySet().iterator().next();
 }
 static Map<String,Object> one(Fixture f,String indexing,long budget,boolean warm)throws Exception {
  long start=System.nanoTime(),deadline=start+budget*1000000L;
  MaxFastMetrics metrics=new MaxFastMetrics();var cache=new MaxFastExecutor.CompilationCache();Level level=new Level();
  Map<AEKey,List<IPatternDetails>> catalog=new LinkedHashMap<>();
  for(var r:f.recipes()){
   IPatternDetails.IInput[] inputs=r.inputs().entrySet().stream().map(e->new IPatternDetails.IInput(){public GenericStack[] getPossibleInputs(){return new GenericStack[]{new GenericStack(key(e.getKey()),e.getValue())};}public long getMultiplier(){return 1;}public boolean isValid(AEKey k,Level l){return k.equals(key(e.getKey()));}public AEKey getRemainingKey(AEKey k){return null;}}).toArray(IPatternDetails.IInput[]::new);
   String preferred=primary(r,f);List<GenericStack> outputs=new ArrayList<>();outputs.add(new GenericStack(key(preferred),r.outputs().get(preferred)));for(var e:r.outputs().entrySet())if(!e.getKey().equals(preferred))outputs.add(new GenericStack(key(e.getKey()),e.getValue()));
   IPatternDetails p=new AEProcessingPattern(r.id(),inputs,outputs.toArray(GenericStack[]::new));
   if(indexing.equals("all_outputs"))for(String k:r.outputs().keySet())catalog.computeIfAbsent(key(k),ignored->new ArrayList<>()).add(p);
   else catalog.computeIfAbsent(key(preferred),ignored->new ArrayList<>()).add(p);
  }
  ICraftingService service=new ICraftingService(){public Collection<IPatternDetails> getCraftingFor(AEKey k){return catalog.getOrDefault(k,List.of());}public boolean canEmitFor(AEKey k){return false;}public boolean isCraftable(AEKey k){return catalog.containsKey(k);}public AEKey getFuzzyCraftable(AEKey k,Predicate<AEKey> p){return isCraftable(k)&&p.test(k)?k:null;}};
  ICraftingCalculation calculation=proxy(ICraftingCalculation.class,(o,m,a)->switch(m.getName()){
   case "gtlcore$getMaxFastMetrics"->metrics;case "gtlcore$getMaxFastCompilationCache"->cache;case "gtlcore$getCalculationMode"->org.gtlcore.gtlcore.config.AE2CalculationMode.MAX_FAST;
   case "gtlcore$getCachedTemplates"->((Supplier<?>)a[4]).get();case "gtlcore$handlePausing"->{if(System.nanoTime()>deadline)throw new InterruptedException("budget");yield null;}
   default->defaultValue(m);
  });
  ICraftingTreeNode root=proxy(ICraftingTreeNode.class,(o,m,a)->{
   String name=m.getName();return switch(name){
    case "gtlcore$getMaxFastKey"->key(f.target());case "gtlcore$getMaxFastAmount"->1L;case "gtlcore$getMaxFastCraftingService"->service;case "gtlcore$getMaxFastCalculation"->calculation;case "gtlcore$getMaxFastLevel"->level;
    case "gtlcore$getMaxFastAncestorKeys","gtlcore$getMaxFastExternalAncestors"->new AEKey[0];case "gtlcore$isMaxFastPatternContextAllowed"->true;
    case "gtlcore$checkMaxFastCancellation"->{if(System.nanoTime()>deadline)throw new InterruptedException("budget");yield null;}
    case "gtlcore$runMaxFastPrefix"->(Long)a[1]-((CraftingSimulationState)a[0]).extract(key(f.target()),(Long)a[1],Actionable.MODULATE);
    case "gtlcore$extractMaxFastOutput"->((CraftingSimulationState)a[0]).extract(key(f.target()),(Long)a[1],Actionable.MODULATE);
    case "gtlcore$reportMaxFastMissing"->{if(a[0] instanceof AEKey k)throw new CraftBranchFailure(k,(Long)a[1]);throw new CraftBranchFailure(key(f.target()),(Long)a[0]);}
    case "gtlcore$runPreparedMaxFastBarrier","gtlcore$prepareMaxFastBarrier","gtlcore$runMaxFastBarrier","gtlcore$getOrCreateMaxFastProgram","gtlcore$runUltraFastTail","gtlcore$maxFastChildRequest","ultraFastRequest","fastRequest","legacyRequest"->throw new UnsupportedHost(name);
    default->defaultValue(m);
   };
  });
  CraftingSimulationState inventory=new CraftingSimulationState();f.stock().forEach((k,v)->inventory.held.put(key(k),v));var executor=new MaxFastExecutor(cache);String status="FAST_PATH_SUCCESS",detail="";
  try{executor.execute(root,inventory,f.amount(),null,metrics);}catch(UnsupportedHost unsupported){status="HOST_CALLBACK_REQUIRED";detail=unsupported.getMessage();}catch(CraftBranchFailure missing){status="MISSING_IN_FAST_PATH";detail=missing.toString();}catch(InterruptedException limit){status="TIMEOUT";}catch(Throwable error){status="HOST_OR_EXECUTOR_ERROR";detail=error.toString();}
  double ms=(System.nanoTime()-start)/1e6;
  if(status.equals("FAST_PATH_SUCCESS")){
   Map<String,GraphRecipe<String>> rs=new LinkedHashMap<>();for(var r:f.recipes())rs.put(r.id(),r);List<PlanStep> steps=new ArrayList<>();for(Object[] op:inventory.operations)steps.add(new PlanStep.Batch(((AEProcessingPattern)op[0]).id,(Long)op[1]));
   try{verify(f,summary(new PlanStep.Sequence(steps),rs));}catch(AssertionError bad){status="VALIDATION_FAILED";detail=bad.toString();}
  }
  Map<String,Object> counters=new TreeMap<>();for(Field field:MaxFastMetrics.class.getDeclaredFields())if(field.getName().startsWith("aggregation")||field.getName().startsWith("scc")){field.setAccessible(true);Object v=field.get(metrics);if(v instanceof Number n&&n.doubleValue()!=0||v instanceof String s&&!s.equals("none"))counters.put(field.getName(),v);}
  var row=new LinkedHashMap<String,Object>();row.put("case",f.name());row.put("recipes",f.recipes().size());row.put("indexing",indexing);row.put("status",status);row.put("ms",ms);row.put("detail",detail);row.put("metrics",counters);Map<String,Long> times=new LinkedHashMap<>();inventory.counts.forEach((k,v)->times.put(((AEProcessingPattern)k).id,v));row.put("counts",times);return row;
 }
 public static void main(String[] args)throws Exception{
  Path input=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectories(output);String indexing=args.length>2?args[2]:"primary_only";int repeat=args.length>3?Integer.parseInt(args[3]):1;
  for(int i=0;i<20;i++){var control=one(ContrastProbe.dag(100),indexing,3000,true);if(!control.get("status").equals("FAST_PATH_SUCCESS"))throw new AssertionError("Legacy adapter control failed: "+control);}
  for(Path file:Files.list(input).sorted().toList())if(file.toString().endsWith(".json")){
   var d=FusionProbe.map(new FusionProbe.Json(Files.readString(file)).value());Fixture f=FusionProbe.fixture(d);
   for(int i=0;i<repeat;i++){var r=one(f,indexing,3000,false);r.put("rep",i);Files.writeString(output.resolve("results.jsonl"),json(r)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);System.out.println(f.name()+" "+r.get("status")+" "+r.get("ms")+" "+r.get("detail"));}
  }
 }
}
