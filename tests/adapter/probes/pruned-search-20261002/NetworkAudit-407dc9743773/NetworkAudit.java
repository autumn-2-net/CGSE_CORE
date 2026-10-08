package local.prunedaudit;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.storage.MEStorage;
import appeng.api.stacks.*;
import appeng.crafting.CraftingCalculation;
import appeng.me.service.CraftingService;
import com.google.gson.*;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.level.Level;
import org.gtlcore.gtlcore.config.*;
import org.gtlcore.gtlcore.integration.ae2.graph.*;
import org.cgse.core.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Local-only audit: fixed inventory, real registered patterns, previews only. */
public final class NetworkAudit {
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().create();
    private static final ExecutorService DRIVER=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"local-network-audit");t.setDaemon(true);return t;});
    private static final ExecutorService LEGACY=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"local-maxfast-audit");t.setDaemon(true);return t;});
    private static final PlanningScheduler WORKER=new PlanningScheduler(1,4,4096,2_000_000L);
    private static final Path DIR=Path.of("local/pruned-search-final-20261002");
    private static final Map<AEKey,String> KEYS=new ConcurrentHashMap<>();
    private static final GtlPatternCatalog CATALOG=new GtlPatternCatalog();
    private static IGrid frozen;
    private static Level level;
    private static CraftingService service;
    private static IActionSource source;
    private static KeyCounter inventory;
    private static Map<AEKey,Long> allStock;
    private static List<AEKey> targets;
    private static int parallel;
    private static long generation;

    static String key(AEKey k){return KEYS.computeIfAbsent(k,x->x.toTagGeneric().toString());}
    static JsonObject amounts(Map<AEKey,? extends Number> values){var o=new JsonObject();values.forEach((k,v)->o.addProperty(key(k),v.toString()));return o;}
    static JsonObject counter(KeyCounter values){var o=new JsonObject();for(var v:values)if(v.getLongValue()!=0)o.addProperty(key(v.getKey()),Long.toString(v.getLongValue()));return o;}
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,Object delegate,java.util.function.BiFunction<Method,Object[],Object> override){
        return (T)Proxy.newProxyInstance(NetworkAudit.class.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
            if(m.getDeclaringClass()==Object.class){if(m.getName().equals("hashCode"))return System.identityHashCode(p);if(m.getName().equals("equals"))return p==a[0];return "Frozen audit "+type.getSimpleName();}
            Object value=override.apply(m,a==null?new Object[0]:a);if(value!=PASS)return value;
            try{return m.invoke(delegate,a);}catch(InvocationTargetException e){throw e.getCause();}
        });
    }
    static final Object PASS=new Object();
    public static CompletableFuture<String> prepare(Level world,IGrid grid,IActionSource action){
        level=world;service=(CraftingService)grid.getCraftingService();source=action;
        inventory=new KeyCounter();allStock=new LinkedHashMap<>();
        for(var e:grid.getStorageService().getCachedInventory())if(e.getLongValue()>0){inventory.add(e.getKey(),e.getLongValue());allStock.put(e.getKey(),e.getLongValue());}
        var raw=grid.getStorageService().getInventory();
        MEStorage storage=proxy(MEStorage.class,raw,(m,a)->switch(m.getName()){
            case "extract"->{if(a[2]!=Actionable.SIMULATE)throw new IllegalStateException("Audit cannot extract");yield Math.min(inventory.get((AEKey)a[0]),(long)a[1]);}
            case "insert"->throw new IllegalStateException("Audit cannot insert");
            case "getAvailableStacks"->{var out=(KeyCounter)a[0];for(var e:inventory)out.add(e.getKey(),e.getLongValue());yield null;}
            default->PASS;
        });
        var frozenStorage=proxy(IStorageService.class,grid.getStorageService(),(m,a)->switch(m.getName()){
            case "getCachedInventory"->inventory;case "getInventory"->storage;default->PASS;
        });
        targets=new ArrayList<>(service.getCraftables(k->true));targets.sort(Comparator.comparing(NetworkAudit::key));
        Map<AEKey,List<IPatternDetails>> producers=new LinkedHashMap<>();Set<AEKey> emitable=new HashSet<>();
        for(var k:targets){producers.put(k,List.copyOf(service.getCraftingFor(k)));if(service.canEmitFor(k))emitable.add(k);}
        var frozenCrafting=proxy(ICraftingService.class,service,(m,a)->switch(m.getName()){
            case "getCraftingFor"->producers.getOrDefault((AEKey)a[0],List.of());
            case "isCraftable"->producers.containsKey((AEKey)a[0]);
            case "canEmitFor"->emitable.contains((AEKey)a[0]);
            default->PASS;
        });
        frozen=proxy(IGrid.class,grid,(m,a)->switch(m.getName()){
            case "getStorageService"->frozenStorage;case "getCraftingService"->frozenCrafting;default->PASS;
        });
        generation=((GraphRequestTracker)service).gtlcore$graphProviderGeneration();parallel=1;
        for(var cpu:service.getCpus())if(!cpu.isBusy())parallel=(int)Math.max(parallel,Math.min(4096L,(long)cpu.getCoProcessors()+1));
        var b=budget(2_000_000_000L,0);var f=new CompletableFuture<GtlPatternCatalog.Snapshot>();
        var capture=CATALOG.begin(frozen,service,level,source,targets.get(0),new LinkedHashSet<>(targets),b);
        GraphSnapshots.enqueue(capture,b,f,t->{});
        return f.thenCompose(s->WORKER.submit(s.structure().catalog().build(b),b).thenApply(p->{
            var o=new JsonObject();o.addProperty("ok",true);o.addProperty("target",key(targets.get(0)));o.add("stock",amounts(allStock));
            var ext=new JsonArray();s.emitable().forEach(k->ext.add(key(k)));o.add("external",ext);
            var recipes=new JsonArray();var keys=new LinkedHashSet<AEKey>();
            for(var r:p.compiler().catalog()){var v=new JsonObject();v.addProperty("id",r.id());v.addProperty("binding",r.binding());var slots=new JsonArray();for(var slot:r.slots()){var z=new JsonObject();z.addProperty("key",key(slot.key()));z.addProperty("amount",Long.toString(slot.amount()));z.addProperty("input_slot",slot.inputSlot());z.addProperty("configuration",slot.configuration());z.addProperty("reusable",slot.reusable());slots.add(z);}v.add("slots",slots);v.add("outputs",amounts(r.outputs()));recipes.add(v);keys.addAll(r.executionOutputs().keySet());}
            o.add("recipes",recipes);var order=new JsonObject();for(var k:keys){var ids=new JsonArray();p.compiler().producers(k).forEach(r->ids.add(r.id()));order.add(key(k),ids);}o.add("producers",order);
            var outTargets=new JsonArray();targets.forEach(k->outTargets.add(key(k)));o.add("targets",outTargets);o.addProperty("parallelism",parallel);o.addProperty("max_extra_copies",ConfigHolder.INSTANCE.ae2GraphMaxExtraCatalystCopies);o.addProperty("generation",generation);
            write("catalog.json",JSON.toJson(o));return JSON.toJson(o);
        }));
    }
    static <T> CompletableFuture<T> onServer(Callable<T> task){var f=new CompletableFuture<T>();net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().execute(()->{try{f.complete(task.call());}catch(Throwable e){f.completeExceptionally(e);}});return f;}
    static PlanningBudget budget(long work,long timeout){return new PlanningBudget(timeout,work,128L<<20,()->false,System::nanoTime);}
    static void write(String name,String value){try{Files.createDirectories(DIR);Files.writeString(DIR.resolve(name),value);}catch(Exception e){throw new RuntimeException(e);}}
    static void append(String name,String value){try{Files.createDirectories(DIR);Files.writeString(DIR.resolve(name),value+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception e){throw new RuntimeException(e);}}
    static void dump(CapturedPatternCatalog.Prepared p,GtlPatternCatalog.Snapshot s,AEKey target,String name){
        var o=new JsonObject();o.addProperty("target",key(target));o.add("stock",amounts(s.stock()));var ext=new JsonArray();s.emitable().forEach(k->ext.add(key(k)));o.add("external",ext);
        var recipes=new JsonArray();var keys=new LinkedHashSet<AEKey>();
        for(var r:p.compiler().catalog()){var v=new JsonObject();v.addProperty("id",r.id());v.addProperty("binding",r.binding());var slots=new JsonArray();for(var slot:r.slots()){var z=new JsonObject();z.addProperty("key",key(slot.key()));z.addProperty("amount",Long.toString(slot.amount()));z.addProperty("input_slot",slot.inputSlot());z.addProperty("configuration",slot.configuration());z.addProperty("reusable",slot.reusable());slots.add(z);}v.add("slots",slots);v.add("outputs",amounts(r.outputs()));recipes.add(v);keys.addAll(r.executionOutputs().keySet());}
        o.add("recipes",recipes);var order=new JsonObject();for(var k:keys){var ids=new JsonArray();p.compiler().producers(k).forEach(r->ids.add(r.id()));order.add(key(k),ids);}o.add("producers",order);o.addProperty("parallelism",parallel);o.addProperty("max_extra_copies",ConfigHolder.INSTANCE.ae2GraphMaxExtraCatalystCopies);write(name,JSON.toJson(o));
    }
    static CompletableFuture<GtlPatternCatalog.Snapshot> capture(AEKey target,PlanningBudget b){var f=new CompletableFuture<GtlPatternCatalog.Snapshot>();net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().execute(()->{try{GraphSnapshots.enqueue(CATALOG.begin(frozen,service,level,source,target,b),b,f,t->{});}catch(Throwable e){f.completeExceptionally(e);}});return f;}
    public static CompletableFuture<String> batch(String input,String output){
        if(frozen==null)throw new IllegalStateException("prepare first");
        var cases=JsonParser.parseString(input).getAsJsonArray();
        return CompletableFuture.supplyAsync(()->{
            int done=0;for(var entry:cases){var c=entry.getAsJsonObject();var row=new JsonObject();String id=c.get("id").getAsString();row.addProperty("id",id);
                try{inventory=new KeyCounter();if(c.has("stock")){for(var e:c.getAsJsonObject("stock").entrySet()){long n=e.getValue().getAsLong();if(n>0)inventory.add(AEKey.fromTagGeneric(TagParser.m_129359_(e.getKey())),n);}}else allStock.forEach(inventory::add);
                    AEKey target=AEKey.fromTagGeneric(TagParser.m_129359_(c.get("target").getAsString()));long amount=c.get("amount").getAsLong();row.addProperty("target",key(target));row.addProperty("amount",Long.toString(amount));row.addProperty("generation_before",((GraphRequestTracker)service).gtlcore$graphProviderGeneration());
                    var legacy=onServer(()->new CraftingCalculation(level,frozen,()->source,new GenericStack(target,amount),CalculationStrategy.REPORT_MISSING_ITEMS)).get();
                    if(ConfigHolder.INSTANCE.ae2CalculationMode!=AE2CalculationMode.MAX_FAST)throw new IllegalStateException("Not MAX_FAST");
                    long start=System.nanoTime();var lf=LEGACY.submit(legacy::run);var lo=new JsonObject();
                    try{var lp=lf.get(15,TimeUnit.SECONDS);lo.addProperty("result",lp.simulation()?"MISSING_INPUT":"FEASIBLE");lo.add("missing",counter(lp.missingItems()));lo.add("initial",counter(lp.usedItems()));lo.addProperty("bytes",lp.bytes());lo.addProperty("recipes",lp.patternTimes().size());}
                    catch(TimeoutException ex){lf.cancel(true);lo.addProperty("result","TIMEOUT");}
                    catch(Exception ex){lo.addProperty("result","ERROR");lo.addProperty("error",ex.toString());}
                    lo.addProperty("ms",(System.nanoTime()-start)/1e6);row.add("maxfast",lo);
                    var b=budget(c.has("work")?c.get("work").getAsLong():20_000_000L,c.has("timeout")?c.get("timeout").getAsLong():15_000L);start=System.nanoTime();var go=new JsonObject();
                    try{var snap=capture(target,b).get(30,TimeUnit.SECONDS);var p=WORKER.submit(snap.structure().catalog().build(b),b).get(30,TimeUnit.SECONDS);if(c.has("dump")&&c.get("dump").getAsBoolean())dump(p,snap,target,id+"-snapshot.json");
                        var work=new CatalystPlanningWork<AEKey>(new CatalystPolicy(parallel,ConfigHolder.INSTANCE.ae2GraphMaxExtraCatalystCopies),b,policy->new GraphPlanningWork<>(p.compiler(),target,amount,snap.stock(),snap.emitable(),Map.of(),true,true,b).catalysts(policy));GraphPlan<AEKey> plan;
                        try{try{while(!work.step()){}plan=work.result();}catch(PlanningBudget.Exhausted ex){plan=work.limited(ex);}}finally{work.close();}
                        if(plan.feasible()){PlanVerifier.verify(plan);PlanVerifier.verifyRuntimeInventory(plan);}
                        go.addProperty("result",plan.result().toString());go.add("missing",amounts(plan.missingExact()));go.add("initial",amounts(plan.initialExact()));go.add("seeds",amounts(plan.seeds()));go.addProperty("recipes",plan.patternTimesExact().size());go.addProperty("bounded",snap.structure().boundedAlternatives());
                    }catch(Exception ex){go.addProperty("result","ERROR");go.addProperty("error",ex.toString());}
                    go.addProperty("work",b.nodes());go.addProperty("peak_bytes",b.peakBytes());go.addProperty("diagnostics",b.diagnostics());go.addProperty("ms",(System.nanoTime()-start)/1e6);row.add("cgse",go);row.addProperty("generation_after",((GraphRequestTracker)service).gtlcore$graphProviderGeneration());
                }catch(Throwable ex){row.addProperty("error",ex.toString());}
                append(output,JSON.toJson(row));done++;if(done%25==0)System.out.println("[Network Audit] completed "+done+"/"+cases.size()+" last="+id);
            }
            var result=new JsonObject();result.addProperty("ok",true);result.addProperty("completed",done);result.addProperty("file",output);return JSON.toJson(result);
        },DRIVER);
    }
}
