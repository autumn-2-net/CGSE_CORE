package org.gtlcore.test;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import appeng.api.stacks.*;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.me.service.CraftingService;
import appeng.me.service.helpers.NetworkCraftingProviders;
import com.google.gson.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.gtlcore.gtlcore.config.AE2CalculationMode;
import org.gtlcore.gtlcore.config.ConfigHolder;

import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Read-only planning through the genuine transformed CraftingCalculation / GRAPH. */
public final class GraphSubsetProbe {
    private static IGrid grid;
    private static Level level;
    private static IActionSource source;
    private static IGridNode node;
    private static NetworkCraftingProviders providers;
    private static IStorageProvider storage;
    private static final Map<String, AEKey> keys = new LinkedHashMap<>();
    private static final Map<AEKey, String> names = new LinkedHashMap<>();
    private static final Map<AEKey, Long> stock = new LinkedHashMap<>();
    private static final Map<IPatternDetails, String> ids = new LinkedHashMap<>();
    private static final List<IPatternDetails> recipes = new ArrayList<>();
    private static final JsonArray results = new JsonArray();
    private static CompletableFuture<GraphStressProbe.Sample> pending;
    private static AE2CalculationMode previousMode;
    private static org.gtlcore.gtlcore.config.AECraftingEngine previousEngine;
    private static int stage, delay;
    private static long maxBefore;
    private static final String[] LABELS = {"all_sources_1", "all_sources_2", "all_sources_3", "reversed_sources", "shuffled_sources", "known_witness_only", "funded_single_source_control"};

    public static void start(IGrid g, Level l, IActionSource s, AEKey paper) throws Exception {
        if (pending != null || delay != 0) throw new IllegalStateException("Already running");
        grid=g; level=l; source=s; stage=0;
        keys.clear(); names.clear(); stock.clear(); ids.clear(); recipes.clear();
        var fixture = JsonParser.parseString(Files.readString(Path.of("subset20-review.json"))).getAsJsonObject();
        for (var entry : fixture.getAsJsonObject("stock").entrySet()) stock.put(key(paper,entry.getKey()),entry.getValue().getAsLong());
        for (var entry : fixture.getAsJsonArray("recipes")) {
            var r=entry.getAsJsonObject();
            var inputs=stacks(paper,r.getAsJsonObject("inputs"));
            var outputs=stacks(paper,r.getAsJsonObject("outputs"));
            var pattern=new AEProcessingPattern(AEItemKey.of(PatternDetailsHelper.encodeProcessingPattern(inputs,outputs)));
            recipes.add(pattern); ids.put(pattern,r.get("id").getAsString());
        }
        var present=new KeyCounter(); grid.getStorageService().getInventory().getAvailableStacks(present);
        for(var k:keys.values()) if(present.get(k)!=0 || !grid.getCraftingService().getCraftingFor(k).isEmpty()) throw new AssertionError("Fixture keys already present");
        previousMode=ConfigHolder.INSTANCE.ae2CalculationMode;
        previousEngine=ConfigHolder.INSTANCE.ae2CraftingEngine;
        ConfigHolder.INSTANCE.ae2CraftingEngine=org.gtlcore.gtlcore.config.AECraftingEngine.GRAPH;
        ConfigHolder.INSTANCE.ae2CalculationMode=AE2CalculationMode.MAX_FAST;
        var field=CraftingService.class.getDeclaredField("craftingProviders");field.setAccessible(true);
        providers=(NetworkCraftingProviders)field.get(grid.getCraftingService());
        System.out.println("[Graph Subset] START mode="+ConfigHolder.INSTANCE.ae2CraftingEngine+" recipes="+recipes.size()+" resources="+keys.size()+" amount=1");
        install(); delay=20;
    }
    private static AEKey key(AEKey paper,String name) {
        return keys.computeIfAbsent(name,n->{
            var tag=new CompoundTag(); tag.m_128359_("subset_review_20260925",n);
            var key=AEItemKey.of((Item)paper.getPrimaryKey(),tag); names.put(key,n); return key;
        });
    }
    private static GenericStack[] stacks(AEKey paper,JsonObject map) {
        return map.entrySet().stream().map(e->new GenericStack(key(paper,e.getKey()),e.getValue().getAsLong())).toArray(GenericStack[]::new);
    }
    private static void install() {
        var selected=new ArrayList<>(recipes);
        if(stage==3)Collections.reverse(selected);
        if(stage==4)Collections.shuffle(selected,new Random(314159));
        if(stage==5) {
            var yes=Set.of(2,3,7,8,9,12,13);
            selected.removeIf(r->{var id=ids.get(r);if(id.equals("finish"))return false;
                int index=Integer.parseInt(id.substring(id.startsWith("yes")?3:2));
                return yes.contains(index)!=id.startsWith("yes");});
        }
        if(stage==6) {
            selected.removeIf(r->!Set.of("yes2","no0","finish").contains(ids.get(r)));
            stock.put(keys.get("U2"),5L);stock.put(keys.get("U0"),16L);
        }
        ICraftingProvider provider=new ICraftingProvider() {
            public List<IPatternDetails> getAvailablePatterns(){return selected;}
            public boolean isBusy(){return false;}
            public boolean pushPattern(IPatternDetails p,KeyCounter[] inputs){throw new AssertionError("Read-only test submitted a job");}
        };
        node=(IGridNode)Proxy.newProxyInstance(GraphSubsetProbe.class.getClassLoader(),new Class<?>[]{IGridNode.class},(proxy,method,args)->switch(method.getName()){
            case "getService" -> args[0]==ICraftingProvider.class?provider:null;
            case "getGrid" -> grid;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy==args[0];
            case "toString" -> "Local subset-sum review";
            default -> null;
        });
        MEStorage inventory=new MEStorage(){
            public net.minecraft.network.chat.Component getDescription(){return keys.get("GOAL").getDisplayName();}
            public void getAvailableStacks(KeyCounter out){stock.forEach(out::add);}
            public long extract(AEKey key,long amount,Actionable mode,IActionSource who){
                if(mode==Actionable.MODULATE)throw new AssertionError("Read-only fixture extraction");
                return Math.min(stock.getOrDefault(key,0L),amount);
            }
        };
        storage=mounts->mounts.mount(inventory);
        grid.getStorageService().addGlobalStorageProvider(storage);
        providers.addProvider(node);
        System.out.println("[Graph Subset] REGISTER label="+LABELS[stage]+" patterns="+selected.size());
    }
    private static void remove() {
        if(node!=null) providers.removeProvider(node);
        if(storage!=null) grid.getStorageService().removeGlobalStorageProvider(storage);
        node=null;storage=null;
    }
    public static void tick() {
        try {
            if(delay>0 && --delay==0) {
                var current=new KeyCounter(); grid.getStorageService().getInventory().getAvailableStacks(current);
                for(var entry:stock.entrySet())if(current.get(entry.getKey())!=entry.getValue())throw new AssertionError("Wrong captured inventory");
                if(grid.getCraftingService().getCraftingFor(keys.get("GOAL")).size()!=1)throw new AssertionError("Missing target binding");
                maxBefore=LegacyCalls.maxFast.get();
                pending=GraphStressProbe.graph(grid,level,source,keys.get("GOAL"),1);
            }
            if(pending==null || !pending.isDone())return;
            var sample=pending.join();pending=null;
            var row=new JsonObject();row.addProperty("case",LABELS[stage]);row.addProperty("failure",sample.failure());
            row.addProperty("wall_ms",sample.wall()/1e6);row.addProperty("compute_ms",sample.work()/1e6);
            row.addProperty("max_fast_calls",LegacyCalls.maxFast.get()-maxBefore);
            var p=sample.plan();
            if(p!=null) {
                row.addProperty("simulation",p.simulation());row.add("used",counter(p.usedItems()));
                row.add("missing",counter(p.missingItems()));row.add("emitted",counter(p.emittedItems()));
                var counts=new JsonObject();p.patternTimes().forEach((r,n)->counts.addProperty(ids.getOrDefault(r,"unknown"),n));row.add("counts",counts);
                String actual=verify(p,false),funded=verify(p,true);
                row.addProperty("actual_inventory_execution",actual);row.addProperty("funded_preview_execution",funded);
                row.addProperty("feasible",!p.simulation() && p.missingItems().isEmpty() && p.emittedItems().isEmpty() && actual.equals("OK"));
                
                if (!row.get("feasible").getAsBoolean()) throw new AssertionError("Graph fixture failed "+row);
                if (LegacyCalls.maxFast.get()!=maxBefore) throw new AssertionError("Unexpected legacy fallback");
                var nativePlan=(org.gtlcore.gtlcore.integration.ae2.graph.AeGraphPlan)p;
                org.cgse.core.PlanVerifier.verify(nativePlan.graph());
                row.addProperty("graph_result",nativePlan.graph().result().name());
                GraphPacketProbe.roundTrip(appeng.menu.me.crafting.CraftingPlanSummary.fromJob(grid,source,p));
            } else row.addProperty("feasible",false);
            results.add(row);
            System.out.println("[Graph Subset] RESULT "+row);
            remove();
            if(++stage<LABELS.length){install();delay=20;return;}
            ConfigHolder.INSTANCE.ae2CalculationMode=previousMode;
            if(previousEngine!=null)ConfigHolder.INSTANCE.ae2CraftingEngine=previousEngine;
            Files.writeString(Path.of("subset20-graph-results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(results));
            System.out.println("[Graph Subset] DONE");
        } catch(Throwable t) {
            pending=null;delay=0;remove();
            if(previousMode!=null)ConfigHolder.INSTANCE.ae2CalculationMode=previousMode;
            if(previousEngine!=null)ConfigHolder.INSTANCE.ae2CraftingEngine=previousEngine;
            System.out.println("[Graph Subset] FAIL "+t);t.printStackTrace();
        }
    }
    private static JsonObject counter(KeyCounter counter) {
        var out=new JsonObject();for(var e:counter)out.addProperty(names.getOrDefault(e.getKey(),"unrelated:"+e.getKey()),e.getLongValue());return out;
    }
    private static String verify(ICraftingPlan p,boolean funded) {
        var held=new LinkedHashMap<AEKey,BigInteger>();stock.forEach((k,v)->held.put(k,BigInteger.valueOf(v)));
        if(funded)for(var e:p.missingItems())held.merge(e.getKey(),BigInteger.valueOf(e.getLongValue()),BigInteger::add);
        for(var key:p.patternTimes().keySet())if(!ids.containsKey(key))return "UNKNOWN_PATTERN";
        for(var r:recipes) {
            long runs=p.patternTimes().getOrDefault(r,0L);
            if(runs<0)return "NEGATIVE_COUNT";
            if(runs==0)continue;
            for(var slot:r.getInputs()) {
                var in=slot.getPossibleInputs()[0];
                var need=BigInteger.valueOf(in.amount()).multiply(BigInteger.valueOf(slot.getMultiplier())).multiply(BigInteger.valueOf(runs));
                if(held.getOrDefault(in.what(),BigInteger.ZERO).compareTo(need)<0)return "UNFUNDED "+ids.get(r)+" "+names.get(in.what())+" needs="+need+" available="+held.getOrDefault(in.what(),BigInteger.ZERO);
                held.merge(in.what(),need.negate(),BigInteger::add);
            }
            for(var out:r.getOutputs())held.merge(out.what(),BigInteger.valueOf(out.amount()).multiply(BigInteger.valueOf(runs)),BigInteger::add);
        }
        return held.getOrDefault(keys.get("GOAL"),BigInteger.ZERO).compareTo(BigInteger.ONE)>=0?"OK":"UNMET_TARGET";
    }
}
