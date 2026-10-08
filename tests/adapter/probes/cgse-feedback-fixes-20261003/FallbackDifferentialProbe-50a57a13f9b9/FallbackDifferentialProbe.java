package local.fallbackdiff;

import org.gtlcore.gtlcore.config.*;
import org.gtlcore.gtlcore.integration.ae2.graph.*;
import org.cgse.core.*;
import appeng.api.config.Actionable;
import appeng.api.crafting.*;
import appeng.api.networking.*;
import appeng.api.networking.crafting.*;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.*;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.*;
import appeng.api.storage.MEStorage;
import appeng.me.service.CraftingService;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import com.google.gson.*;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

@Mod("localfallbackdifferential")
public class FallbackDifferentialProbe {
    static Run active;
    public FallbackDifferentialProbe(){MinecraftForge.EVENT_BUS.addListener(this::register);MinecraftForge.EVENT_BUS.addListener(this::tick);}
    void register(RegisterCommandsEvent e){e.getDispatcher().register(Commands.m_82127_("fallback_differential").requires(s->s.m_6761_(4)).executes(c->{try{active=new Run(c.getSource().m_81372_());}catch(Throwable ex){ex.printStackTrace();System.out.println("[Fallback Differential] ABORT "+ex);}return 1;}));}
    void tick(TickEvent.ServerTickEvent e){if(e.phase!=TickEvent.Phase.END||active==null)return;try{if(active.tick()){active.close();active=null;}}catch(Throwable ex){ex.printStackTrace();System.out.println("[Fallback Differential] ABORT "+ex);active.close();active=null;}}
    static final class Run {
        final AECraftingEngine oldEngine=ConfigHolder.INSTANCE.ae2CraftingEngine;
        final AE2CalculationMode oldMode=ConfigHolder.INSTANCE.ae2CalculationMode;
        final ServerLevel level;final JsonArray cases;int index;
        Fixture fixture;Map<String,Object> result;Future<ICraftingPlan> future;appeng.crafting.CraftingCalculation legacy;long started;
        Run(ServerLevel level)throws Exception{this.level=level;cases=JsonParser.parseString(Files.readString(Path.of(System.getProperty("local.fallback.fixtures")))).getAsJsonArray();ConfigHolder.INSTANCE.ae2CalculationMode=AE2CalculationMode.MAX_FAST;ConfigHolder.INSTANCE.ae2CraftingEngine=AECraftingEngine.LEGACY;System.out.println("[Fallback Differential] START cases="+cases.size()+" java="+System.getProperty("java.version")+" nativeMAX_FAST=true");for(var mod:net.minecraftforge.fml.ModList.get().getMods())if(Set.of("minecraft","forge","ae2","gtceu","gtlcore","gtladditions").contains(mod.getModId()))System.out.println("[Fallback Differential] MOD "+mod.getModId()+"="+mod.getVersion());}
        boolean tick()throws Exception{
            if(future!=null){
                if(!future.isDone())legacy.simulateFor(10000);
                if(!future.isDone()){if(System.nanoTime()-started>30_000_000_000L){future.cancel(true);result.put("maxfast_error","timeout");finish();}return false;}
                try{var plan=future.get();check(!(plan instanceof AeGraphPlan),"Expected actual AE/MaxFast implementation");var actualMode=((org.gtlcore.gtlcore.integration.ae2.crafting.ICraftingCalculation)legacy).gtlcore$getCalculationMode();check(actualMode==AE2CalculationMode.MAX_FAST,"Native calculation was not MAX_FAST");result.put("maxfast_mode",actualMode.name());result.put("maxfast_class",plan.getClass().getName());result.put("maxfast_feasible",!plan.simulation());result.put("maxfast_used",fixture.counter(plan.usedItems()));result.put("maxfast_missing",fixture.counter(plan.missingItems()));
                    Map<String,BigInteger> counts=new LinkedHashMap<>();for(var entry:plan.patternTimes().entrySet())counts.put(fixture.names.get(entry.getKey()),BigInteger.valueOf(entry.getValue()));result.put("maxfast_runs",strings(counts));
                    if(!plan.simulation())result.put("maxfast_witness_valid",fixture.verifyCounts(counts,fixture.stockMap));
                }catch(Throwable ex){result.put("maxfast_error",ex.toString());}
                finish();if(index==cases.size()){System.out.println("[Fallback Differential] DONE cases="+index);return true;}
            }
            fixture=new Fixture(level,cases.get(index).getAsJsonObject());result=new LinkedHashMap<>();result.put("name",fixture.spec.get("name").getAsString());result.put("oracle_feasible",fixture.spec.get("oracle_feasible").getAsBoolean());result.put("amount",fixture.amount);result.put("recipes",fixture.spec.get("recipes"));result.put("actual_stock",fixture.named(fixture.stockMap));result.put("actual_target_candidates",fixture.service.getCraftingFor(fixture.target).stream().map(fixture.names::get).toList());
            if(fixture.spec.get("oracle_feasible").getAsBoolean()){
                Map<String,BigInteger> oracle=new LinkedHashMap<>();
                if(fixture.spec.has("oracle_runs"))fixture.spec.getAsJsonObject("oracle_runs").entrySet().forEach(e->oracle.put(e.getKey(),e.getValue().getAsBigInteger()));
                else {var sources=List.copyOf(fixture.service.getCraftingFor(fixture.target));oracle.put(fixture.names.get(sources.get(sources.size()-1)),BigInteger.valueOf(fixture.amount));}
                result.put("oracle_constructive_runs",strings(oracle));result.put("oracle_constructive_witness_valid",fixture.verifyCounts(oracle,fixture.stockMap));
            }
            var budget=new PlanningBudget(250,131072,16L<<20,()->false,System::nanoTime);
            try{
                var plan=GraphFallback.plan(fixture.compiler,fixture.target,fixture.amount,fixture.stockMap,Set.of(),Map.of(),true,true,budget);
                result.put("fallback_feasible",plan.feasible());result.put("fallback_result",plan.result().name());result.put("fallback_initial",fixture.named(plan.initialExact()));result.put("fallback_missing",fixture.named(plan.missingExact()));
                Map<String,BigInteger> counts=new LinkedHashMap<>();plan.patternTimesExact().forEach((id,n)->counts.merge(fixture.bindings.get(plan.recipes().get(id).binding()),n,BigInteger::add));result.put("fallback_runs",strings(counts));
                var funded=new GraphPlan<>(plan.target(),plan.amount(),plan.preserveSeeds(),plan.steps(),plan.recipes(),plan.initialExact(),plan.seeds(),Map.of(),GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL,0,0);PlanVerifier.verify(funded);
                Map<AEKey,BigInteger> supplied=new LinkedHashMap<>();fixture.stockMap.forEach((k,n)->supplied.put(k,BigInteger.valueOf(n)));plan.missingExact().forEach((k,n)->supplied.merge(k,n,BigInteger::add));
                result.put("fallback_funded_witness_valid",fixture.verifyCounts(counts,supplied));
                if(plan.feasible())result.put("fallback_witness_valid",fixture.verifyCounts(counts,fixture.stockMap));
            }catch(Throwable ex){result.put("fallback_error",ex.toString());}
            result.put("fallback_work",budget.nodes());result.put("fallback_reserved_after",budget.reservedBytes());
            legacy=new appeng.crafting.CraftingCalculation(level,fixture.grid,fixture.requester,new GenericStack(fixture.target,fixture.amount),CalculationStrategy.REPORT_MISSING_ITEMS);future=CompletableFuture.supplyAsync(legacy::run);started=System.nanoTime();return false;
        }
        void finish(){System.out.println("[Fallback Differential] RESULT "+new Gson().toJson(result));future=null;legacy=null;fixture=null;index++;}
        void close(){if(future!=null)future.cancel(true);ConfigHolder.INSTANCE.ae2CraftingEngine=oldEngine;ConfigHolder.INSTANCE.ae2CalculationMode=oldMode;}
    }
    static final class Fixture {
        final ServerLevel level;final JsonObject spec;final Map<String,AEKey> keys=new LinkedHashMap<>();final Map<AEKey,String> keyNames=new HashMap<>();
        final Map<AEKey,Long> stockMap=new LinkedHashMap<>();final KeyCounter stock=new KeyCounter();
        final Map<IPatternDetails,String> names=new HashMap<>();final Map<String,String> bindings=new HashMap<>();final Map<String,JsonObject> recipes=new LinkedHashMap<>();
        final List<IPatternDetails> current=new ArrayList<>();final IGrid grid;final CraftingService service;final IGridNode node;final ICraftingSimulationRequester requester;
        final AEKey target;final long amount;final GraphCompiler<AEKey> compiler;
        Fixture(ServerLevel level,JsonObject spec)throws Exception{
            this.level=level;this.spec=spec;target=key(spec.get("target").getAsString());amount=spec.get("amount").getAsLong();
            for(var value:spec.getAsJsonArray("recipes")){var r=value.getAsJsonObject();String id=r.get("id").getAsString();recipes.put(id,r);var p=PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(stacks(r.getAsJsonObject("inputs")),stacks(r.getAsJsonObject("outputs"))),level);check(p!=null,"decoded processing pattern");current.add(p);names.put(p,id);}
            var inventory=proxy(MEStorage.class,(p,m,a)->switch(m.getName()){case "getAvailableStacks"->{((KeyCounter)a[0]).addAll(stock);yield null;}case "extract"->{check(a[2]==Actionable.SIMULATE,"preview extracted real stock");yield Math.min((long)a[1],stock.get((AEKey)a[0]));}case "getDescription"->Component.m_237113_("local fallback differential stock");default->zero(m);});
            var storage=proxy(IStorageService.class,(p,m,a)->switch(m.getName()){case "getInventory"->inventory;case "getCachedInventory"->stock;default->zero(m);});
            var energy=proxy(IEnergyService.class,(p,m,a)->zero(m));CraftingService[] ref={null};
            grid=proxy(IGrid.class,(p,m,a)->switch(m.getName()){case "getCraftingService"->ref[0];case "getStorageService"->storage;case "getEnergyService"->energy;default->zero(m);});service=ref[0]=new CraftingService(grid,storage,energy);
            var provider=proxy(ICraftingProvider.class,(p,m,a)->switch(m.getName()){case "getAvailablePatterns"->current;case "getEmitableItems"->Set.of();default->zero(m);});node=proxy(IGridNode.class,(p,m,a)->switch(m.getName()){case "getGrid"->grid;case "getService"->a[0]==ICraftingProvider.class?provider:null;default->zero(m);});
            var host=proxy(IActionHost.class,(p,m,a)->m.getName().equals("getActionableNode")?node:zero(m));var source=proxy(IActionSource.class,(p,m,a)->m.getName().equals("machine")?Optional.of(host):Optional.empty());requester=new ICraftingSimulationRequester(){public IActionSource getActionSource(){return source;}public IGridNode getGridNode(){return node;}};
            service.refreshNodeCraftingProvider(node);
            spec.getAsJsonObject("stock").entrySet().forEach(e->stockMap.put(key(e.getKey()),e.getValue().getAsLong()));
            if(spec.has("last_source_stock")){var sources=List.copyOf(service.getCraftingFor(target));var selected=sources.get(sources.size()-1);for(var input:selected.getInputs())for(var stack:input.getPossibleInputs())stockMap.put(stack.what(),Math.multiplyExact(stack.amount(),amount));}
            stockMap.forEach(stock::add);
            var normalize=GtlPatternCatalog.class.getDeclaredMethod("normalize",IPatternDetails.class,String.class,KeyCounter.class,Level.class);normalize.setAccessible(true);
            var normalized=new ArrayList<GraphRecipe<AEKey>>();var pending=new ArrayDeque<AEKey>();pending.add(target);var seenKeys=new HashSet<AEKey>();var seenPatterns=new HashSet<IPatternDetails>();
            while(!pending.isEmpty()){var wanted=pending.removeFirst();if(!seenKeys.add(wanted))continue;for(var p:service.getCraftingFor(wanted))if(seenPatterns.add(p)){String binding=PatternFingerprint.of(p);bindings.put(binding,names.get(p));var rs=(List<GraphRecipe<AEKey>>)normalize.invoke(null,p,binding,stock,level);normalized.addAll(rs);for(var r:rs)pending.addAll(r.inputs().keySet());}}
            compiler=new GraphCompiler<>(normalized);
        }
        AEKey key(String name){return keys.computeIfAbsent(name,n->{var tag=new CompoundTag();tag.m_128359_("local_fallback_key",n);var key=AEItemKey.of(Items.f_42597_,tag);keyNames.put(key,n);return key;});}
        GenericStack[] stacks(JsonObject values){return values.entrySet().stream().map(e->new GenericStack(key(e.getKey()),e.getValue().getAsLong())).toArray(GenericStack[]::new);}
        Map<String,String> named(Map<AEKey,? extends Number> values){var out=new TreeMap<String,String>();values.forEach((k,n)->out.put(keyNames.get(k),n.toString()));return out;}
        Map<String,String> counter(KeyCounter values){var out=new TreeMap<String,String>();for(var e:values)out.put(keyNames.get(e.getKey()),Long.toString(e.getLongValue()));return out;}
        boolean verifyCounts(Map<String,BigInteger> counts,Map<AEKey,? extends Number> stock){
            var inventory=new HashMap<String,BigInteger>();stock.forEach((k,n)->inventory.put(keyNames.get(k),new BigInteger(n.toString())));var remaining=new LinkedHashMap<>(counts);int loops=0;
            while(!remaining.isEmpty()){
                boolean progress=false;for(var it=remaining.entrySet().iterator();it.hasNext();){var task=it.next();var recipe=recipes.get(task.getKey());check(recipe!=null,"unknown pattern count "+task.getKey());var in=recipe.getAsJsonObject("inputs");var out=recipe.getAsJsonObject("outputs");BigInteger runs=task.getValue();
                    for(var input:in.entrySet()){var consumed=input.getValue().getAsBigInteger();var have=inventory.getOrDefault(input.getKey(),BigInteger.ZERO);if(have.compareTo(consumed)<0){runs=BigInteger.ZERO;break;}var returned=out.has(input.getKey())?out.get(input.getKey()).getAsBigInteger():BigInteger.ZERO;if(consumed.compareTo(returned)>0)runs=runs.min(have.subtract(consumed).divide(consumed.subtract(returned)).add(BigInteger.ONE));}
                    if(runs.signum()==0)continue;progress=true;for(var input:in.entrySet())inventory.merge(input.getKey(),input.getValue().getAsBigInteger().multiply(runs).negate(),BigInteger::add);for(var output:out.entrySet())inventory.merge(output.getKey(),output.getValue().getAsBigInteger().multiply(runs),BigInteger::add);
                    var rest=task.getValue().subtract(runs);if(rest.signum()==0)it.remove();else task.setValue(rest);
                }if(!progress||++loops>10000)return false;
            }
            return inventory.getOrDefault(keyNames.get(target),BigInteger.ZERO).compareTo(BigInteger.valueOf(amount))>=0;
        }
    }
    static Map<String,String> strings(Map<String,BigInteger> map){var result=new LinkedHashMap<String,String>();map.forEach((k,n)->result.put(k,n.toString()));return result;}
    static <T>T proxy(Class<T> type,InvocationHandler h){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(p,m,a)->switch(m.getName()){case "hashCode"->System.identityHashCode(p);case "equals"->p==a[0];case "toString"->"local "+type.getSimpleName();default->h.invoke(p,m,a);}));}
    static Object zero(Method m){var t=m.getReturnType();if(t==boolean.class)return false;if(t==int.class)return 0;if(t==long.class)return 0L;if(t==double.class)return 0D;if(t==Optional.class)return Optional.empty();if(Set.class.isAssignableFrom(t))return Set.of();if(Collection.class.isAssignableFrom(t))return List.of();return null;}
    static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
}
