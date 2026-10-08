package org.gtlcore.test;

import org.gtlcore.gtlcore.config.*;
import org.gtlcore.gtlcore.integration.ae2.graph.*;
import org.cgse.core.*;
import appeng.api.config.Actionable;
import appeng.api.crafting.*;
import appeng.api.networking.*;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.*;
import appeng.api.stacks.*;
import appeng.me.service.CraftingService;
import appeng.me.service.helpers.NetworkCraftingProviders;
import appeng.menu.me.crafting.CraftingPlanSummary;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.Proxy;

/** Local-only fixture: complete real snapshot/router/mixin path, without touching production worlds. */
public final class FallbackLiveProbe implements ICraftingProvider {
    static FallbackLiveProbe active;
    final IGrid network; final Level world; final IActionSource action;
    final Map<AEKey,Long> stock=new LinkedHashMap<>(); final List<IPatternDetails> patterns=new ArrayList<>();
    final NetworkCraftingProviders providers; final IGridNode node; final IStorageProvider store;
    final AEKey target; final long amount;
    final AECraftingEngine oldEngine; final boolean oldFallback,oldByproducts; final int oldSteps,oldTimeout;
    Future<ICraftingPlan> future; int phase,ticks;

    FallbackLiveProbe(IGrid grid,Level level,IActionSource source,AEKey paper)throws Exception {
        network=grid; world=level; action=source;
        var fixture=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of("fallback-review.json"))).getAsJsonObject();
        Map<String,AEKey> keys=new HashMap<>(); String nonce=UUID.randomUUID().toString();
        java.util.function.Function<String,AEKey> key=n->keys.computeIfAbsent(n,name->{var tag=new CompoundTag(); tag.m_128359_("fallback_probe",nonce+name); return AEItemKey.of((net.minecraft.world.item.Item)paper.getPrimaryKey(),tag);});
        fixture.getAsJsonObject("stock").entrySet().forEach(e->stock.put(key.apply(e.getKey()),e.getValue().getAsLong()));
        for(var value:fixture.getAsJsonArray("recipes")) {
            var r=value.getAsJsonObject();
            var in=r.getAsJsonObject("inputs").entrySet().stream().map(e->new GenericStack(key.apply(e.getKey()),e.getValue().getAsLong())).toArray(GenericStack[]::new);
            var out=r.getAsJsonObject("outputs").entrySet().stream().map(e->new GenericStack(key.apply(e.getKey()),e.getValue().getAsLong())).toArray(GenericStack[]::new);
            var pattern=PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(in,out),level);
            check(pattern!=null,"encoded pattern missing"); patterns.add(pattern);
        }
        target=key.apply(fixture.get("target").getAsString()); amount=fixture.get("amount").getAsLong();
        var field=CraftingService.class.getDeclaredField("craftingProviders"); field.setAccessible(true); providers=(NetworkCraftingProviders)field.get(grid.getCraftingService());
        node=(IGridNode)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{IGridNode.class},(p,m,x)->switch(m.getName()) {
            case "getService" -> x[0]==ICraftingProvider.class?this:null;
            case "getGrid" -> network;
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p==x[0]; default -> null;
        });
        store=mounts->mounts.mount(new MEStorage() {
            public net.minecraft.network.chat.Component getDescription(){return target.getDisplayName();}
            public void getAvailableStacks(KeyCounter out){stock.forEach(out::add);}
            public long extract(AEKey k,long n,Actionable mode,IActionSource src){check(mode==Actionable.SIMULATE,"preview extracted real stock"); return Math.min(n,stock.getOrDefault(k,0L));}
        });
        var c=ConfigHolder.INSTANCE;
        oldEngine=c.ae2CraftingEngine; oldSteps=c.ae2GraphPlannerMaxSteps; oldTimeout=c.ae2GraphPlannerTimeoutMs;
        oldFallback=c.ae2GraphFallback; oldByproducts=c.ae2GraphDiscoverByproducts;
        c.ae2CraftingEngine=AECraftingEngine.GRAPH; c.ae2GraphPlannerMaxSteps=100_000; c.ae2GraphPlannerTimeoutMs=10_000;
        c.ae2GraphDiscoverByproducts=true; c.ae2GraphFallback=false;
        network.getStorageService().addGlobalStorageProvider(store); providers.addProvider(node);
        future=calculate();
    }
    Future<ICraftingPlan> calculate(){return network.getCraftingService().beginCraftingCalculation(world,()->action,target,amount,CalculationStrategy.REPORT_MISSING_ITEMS);}
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void start(IGrid grid,Level level,IActionSource source,AEKey paper)throws Exception {check(active==null,"already active"); active=new FallbackLiveProbe(grid,level,source,paper); System.out.println("[Fallback Live] START actual providers=19 main_work_limit=100000");}
    public static void tick(){if(active==null)return; try{if(active.advance()){active.close();active=null;System.out.println("[Fallback Live] ALL PASS");}}catch(Throwable e){System.out.println("[Fallback Live] FAIL "+e);e.printStackTrace();active.close();active=null;}}
    boolean advance()throws Exception {
        check(++ticks<900,"live timeout phase="+phase);
        if(!future.isDone())return false;
        if(phase==0) {
            try {var unexpected=future.get();throw new AssertionError("fallback disabled should reach controlled limit, got "+unexpected);}
            catch(ExecutionException e) {
                Throwable cause=e.getCause(); while(cause.getCause()!=null)cause=cause.getCause();
                check(cause instanceof PlanningBudget.Exhausted,"wrong original failure "+cause);
                check(((PlanningBudget.Exhausted)cause).limit()==PlanningBudget.Limit.SEARCH_LIMIT,"wrong original limit "+cause);
                System.out.println("[Fallback Live] PASS disabled preserves SEARCH_LIMIT");
            }
            ConfigHolder.INSTANCE.ae2GraphFallback=true; future=calculate(); phase=1; return false;
        }
        var plan=future.get(); check(plan instanceof AeGraphPlan,"wrong engine result"); var graph=(AeGraphPlan)plan;
        check(graph.fallback(),"actual router lost fallback marker"); check(graph.simulation(),"expected conservative missing preview");
        var p=graph.graph(); var funded=new GraphPlan<>(p.target(),p.amount(),p.preserveSeeds(),p.steps(),p.recipes(),p.initialExact(),p.seeds(),Map.of(),GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL,0,0);
        PlanVerifier.verify(funded);
        var summary=CraftingPlanSummary.fromJob(network,action,graph); var copy=GraphPacketProbe.roundTrip(summary);
        check(((GraphPlanSummaryView)summary).gtlcore$fallback()&&((GraphPlanSummaryView)copy).gtlcore$fallback(),"actual AE2CT packet lost fallback marker");
        check(copy.isSimulation(),"packet lost conservative missing state");
        System.out.println("[Fallback Live] PASS enabled real snapshot/router/packet missing_keys="+p.missingExact().size()+" recipes="+p.recipes().size()+" feasible_claim=false");
        return true;
    }
    void close(){if(future!=null&&!future.isDone())future.cancel(false); providers.removeProvider(node); network.getStorageService().removeGlobalStorageProvider(store); var c=ConfigHolder.INSTANCE; c.ae2CraftingEngine=oldEngine; c.ae2GraphPlannerMaxSteps=oldSteps; c.ae2GraphPlannerTimeoutMs=oldTimeout; c.ae2GraphFallback=oldFallback; c.ae2GraphDiscoverByproducts=oldByproducts;}
    public List<IPatternDetails> getAvailablePatterns(){return patterns;}
    public boolean isBusy(){return false;}
    public boolean pushPattern(IPatternDetails pattern,KeyCounter[] inputs){throw new AssertionError("read-only preview dispatched pattern");}
}
