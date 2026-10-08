package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.config.*;
import org.cgse.core.*;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.*;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.hooks.ticking.TickHandler;
import appeng.me.service.CraftingService;
import net.minecraft.nbt.CompoundTag;
import java.lang.reflect.*;
import java.util.*;

public final class ReplanRetryProbe {
    static boolean baseline;
    static Runnable changed;
    static Set<AEKey> watched=Set.of();
    static int watches, closes;
    static CatalogInvalidationProbe.Service service;
    static GraphCpuHost host;
    static GraphCpuController controller;
    static GraphJobRuntime<AEKey> runtime;
    static AEKey oldRaw, newRaw, target;
    static Method update;
    static long now;
    static Object get(Object owner,String name)throws Exception {
        var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);
    }
    static void set(Object owner,String name,Object value)throws Exception {
        var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);
    }
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);System.out.println("PASS "+label);}
    static void step(long tick)throws Exception {now=tick;set(TickHandler.instance(),"tickCounter",tick);try{update.invoke(controller,service);}catch(InvocationTargetException e){throw new RuntimeException(e.getCause());}}
    static GraphPlanningRequest request()throws Exception{return (GraphPlanningRequest)get(controller,"replanRequest");}
    static void completedFailure()throws Exception {
        var pending=request();if(pending!=null)pending.cancel(false);
        var missing=new GraphPlan<AEKey>(target,1,false,new PlanStep.Sequence(List.of()),Map.of(),Map.of(),Map.of(),Map.of(newRaw,1L),GraphPlan.Result.MISSING_INPUT,0,0);
        var failure=new GraphPlanningRequest(new PlanningBudget(0,1000,()->false));
        failure.dependencies(Set.of(newRaw,target));failure.complete(new AeGraphPlan(missing,Map.of(),Set.of(),Map.of()));
        set(controller,"replanRequest",failure);
    }
    static void fresh()throws Exception {
        if(controller!=null)controller.cancel();
        controller=new GraphCpuController(host);
        var recipe=new GraphRecipe<AEKey>("original-recipe","original-binding",List.of(new GraphRecipe.Slot<>(oldRaw,1)),Map.of(target,1L));
        var original=new GraphPlan<AEKey>(target,1,false,new PlanStep.Batch(recipe.id(),1),Map.of(recipe.id(),recipe),Map.of(oldRaw,1L),Map.of(),Map.of(),GraphPlan.Result.FEASIBLE,0,0);
        runtime=new GraphJobRuntime<>(original,Map.of(),Map.of(oldRaw,1L));
        var link=new CraftingLink(CraftingCpuHelper.generateLinkData(UUID.randomUUID(),true,false),host.cpu());
        set(controller,"runtime",runtime);set(controller,"link",link);
        var adapter=new GtlExecutionAdapter(host,link);adapter.services(service,null);set(controller,"adapter",adapter);
        changed=null;watched=Set.of();
    }
    static void boot()throws Exception {
        CatalogInvalidationProbe.boot();
        ConfigHolder.INSTANCE.ae2CraftingEngine=AECraftingEngine.LEGACY;
        ConfigHolder.INSTANCE.ae2GraphDiagnosticLogging=false;
        oldRaw=CatalogInvalidationProbe.rawA;newRaw=CatalogInvalidationProbe.rawB;target=CatalogInvalidationProbe.target;
        var storage=(IStorageService)Proxy.newProxyInstance(ReplanRetryProbe.class.getClassLoader(),new Class[]{IStorageService.class,GraphStorageWatch.class},(p,m,a)->switch(m.getName()){
            case "getCachedInventory"->new KeyCounter();
            case "gtlcore$watchGraphResources"->{watched=Set.copyOf((Set<AEKey>)a[0]);changed=(Runnable)a[1];watches++;yield (GraphStorageWatch.Subscription)()->{closes++;};}
            default->null;
        });
        service=new CatalogInvalidationProbe.Service(storage);
        var grid=(IGrid)Proxy.newProxyInstance(ReplanRetryProbe.class.getClassLoader(),new Class[]{IGrid.class},(p,m,a)->switch(m.getName()){
            case "getStorageService"->storage;case "getCraftingService"->service;case "hashCode"->System.identityHashCode(p);case "equals"->p==a[0];default->null;
        });
        var cpu=(ICraftingCPU)Proxy.newProxyInstance(ReplanRetryProbe.class.getClassLoader(),new Class[]{ICraftingCPU.class},(p,m,a)->m.getName().equals("getAvailableStorage")?Long.MAX_VALUE:null);
        host=(GraphCpuHost)Proxy.newProxyInstance(ReplanRetryProbe.class.getClassLoader(),new Class[]{GraphCpuHost.class},(p,m,a)->switch(m.getName()){
            case "grid"->grid;case "cpu"->cpu;case "level"->CatalogInvalidationProbe.level;case "active"->true;case "unboundedJobStorage"->true;default->null;
        });
        update=GraphCpuController.class.getDeclaredMethod("updateReplan",CraftingService.class);update.setAccessible(true);
        var spent=GraphSnapshots.class.getDeclaredField("spentNanos");spent.setAccessible(true);spent.setLong(null,2_000_000L);
    }
    static void storm()throws Exception {
        fresh();step(0);check(request()!=null,"invalid original binding starts a suffix replan");
        int failures=0, starts=1;
        for(long tick=1;tick<=1200;tick++){
            if(request()!=null){completedFailure();failures++;step(tick);}
            else {changed.run();step(tick);if(request()!=null)starts++;}
        }
        System.out.println("STORM mode="+(baseline?"baseline":"fixed")+" ticks=1200 failures="+failures+" requests="+starts);
        check(baseline?failures>=500:failures<=7,"continuous storage notifications have expected request bound");
        check(baseline?watched.contains(oldRaw):!watched.contains(oldRaw),"watch old recipe resources only when latest snapshot is unavailable");
        if(!baseline){
            var retry=(GraphReplanRetry)get(controller,"replanRetry");
            check(retry.failures()==failures,"retry count survives request replacement");
            check(((String)get(controller,"replanOrigin")).contains("original-recipe")&&((String)get(controller,"replanOrigin")).contains("NO_REGISTERED_PATTERN"),"original binding failure survives terminal missing-input retries");
        }
    }
    static void transitions()throws Exception {
        fresh();step(2000);completedFailure();step(2001);changed.run();
        step(2040);check(request()==null,"one early event stays deferred until first delay expires");
        step(2041);check(request()!=null,"latched event retries after delay without another notification");
        completedFailure();step(2042);changed.run();
        step(2082);check(request()==null,"second failure does not reset to first delay");
        runtime.suspend(true);step(2122);check(request()==null,"suspended job does not restart a pending retry");
        runtime.suspend(false);step(2123);check(request()!=null,"resume retains pending dependency event");
        completedFailure();step(2124);
        check(runtime.accept(oldRaw,1,false)==1,"physical outstanding input remains receivable during retry backoff");
        var saved=new CompoundTag();controller.write(saved);
        var restored=new GraphCpuController(host);restored.read(saved);
        check(!CraftingEngineRouter.useGraph()&&restored.ownsTask(),"saved graph task restores with LEGACY selected for new orders");
        check(((GraphReplanRetry)get(restored,"replanRetry")).failures()==0,"load starts a fresh transient retry window");
        ((GtlExecutionAdapter)get(restored,"adapter")).services(service,null);
        update.invoke(restored,service);
        check(get(restored,"replanRequest")!=null,"LEGACY-restored graph task still validates bindings and replans");
        controller.cancel();check(((GraphReplanRetry)get(controller,"replanRetry")).failures()==0,"cancellation clears retry history");
        check(get(controller,"checkpoint")==null&&get(controller,"dependencyWatch")==null,"cancellation releases checkpoint and subscription");
        restored.cancel();
        fresh();step(3000);completedFailure();step(3001);step(10000);
        check(request()==null,"cooldown expiry alone does not poll/replan an unchanged network");
        service.current.add(CatalogInvalidationProbe.pattern(newRaw,target,1));service.generation++;
        step(10001);check(request()!=null,"relevant provider repair wakes a waiting job");
        completedFailure();step(10002);check(watched.contains(newRaw)&&watched.contains(target),"input and finished target arrivals both stay watched");
        controller.cancel();
        fresh();step(11000);
        request().cancel(false);
        var failedSnapshot=new GraphPlanningRequest(new PlanningBudget(0,1000,()->false));failedSnapshot.completeExceptionally(new IllegalStateException("snapshot failed"));
        set(controller,"replanRequest",failedSnapshot);step(11001);
        check(watched.contains(oldRaw)&&watched.contains(target),"failed snapshot falls back to pending original resources");
        controller.cancel();
        check(watches==closes,"every installed dependency subscription is closed");
    }
    static void policy(){
        var retry=new GraphReplanRetry();long tick=0;int[] expected={40,80,160,320,600,600};
        for(int delay:expected){check(retry.failed(tick)==delay,"delay="+delay);check(!retry.ready(tick+delay-1)&&retry.ready(tick+delay),"exact retry boundary");tick+=delay;}
        check(retry.shouldLog(0,"MISSING")&&!retry.shouldLog(599,"MISSING")&&retry.shouldLog(600,"MISSING"),"same failure logs at most once per 600 ticks");
        check(retry.shouldLog(601,"OTHER"),"changed failure remains visible");
        retry.reset();check(retry.failed(700)==40,"successful/lifecycle reset restores initial delay");
    }
    public static void main(String[]args)throws Exception {
        baseline=args.length>0&&args[0].equals("baseline");boot();storm();if(!baseline){transitions();policy();}
        if(controller!=null)controller.cancel();
        var scheduler=CraftingEngineRouter.class.getDeclaredField("scheduler");scheduler.setAccessible(true);if(scheduler.get(null) instanceof AutoCloseable close)close.close();
        System.out.println("DONE real GraphCpuController with actual runtime and completed planning requests; service/storage/unstarted-level doubles, tick advancement without sleep");
    }
}
