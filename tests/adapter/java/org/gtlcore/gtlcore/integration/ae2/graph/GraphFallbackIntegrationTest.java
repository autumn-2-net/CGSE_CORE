package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.config.ConfigHolder;
import org.cgse.core.*;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.*;
import appeng.api.stacks.AEKey;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Local-only router gates, using real AE plans/keys and immutable captured metadata. */
public final class GraphFallbackIntegrationTest {
    static Class<?> type;
    static void field(Object object,String name,Object value)throws Exception{
        if(name.equals("selected")){
            var p=type.getDeclaredField("planning");p.setAccessible(true);object=p.get(object);
            if(object==null)return;
            var f=RequestPlanningWork.class.getDeclaredField(name);f.setAccessible(true);f.set(object,value);
        }else{var f=type.getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    }
    static boolean finish(Object object)throws Exception{
        var p=type.getDeclaredField("planning");p.setAccessible(true);
        var core=RequestPlanningWork.class.getDeclaredMethod("finish");core.setAccessible(true);core.invoke(p.get(object));
        var host=type.getDeclaredMethod("finish");host.setAccessible(true);return Boolean.TRUE.equals(host.invoke(object));
    }
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static Object work(AEKey input,AEKey target,IPatternDetails binding,boolean bounded,boolean cancelled,boolean prepared)throws Exception {
        var budget=new PlanningBudget(0,1,()->false);
        var request=new GraphPlanningRequest(budget);
        var constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
        Object work=constructor.newInstance(null,new CompletableFuture<>(),target,7L,CalculationStrategy.REPORT_MISSING_ITEMS,budget,null,true,request);
        var recipe=new GraphRecipe<AEKey>("fallback","binding",List.of(new GraphRecipe.Slot<>(input,2)),Map.of(target,3L));
        var compiler=new GraphCompiler<>(List.of(recipe));
        var stock=Map.of(input,5L,target,100L);
        var structure=new GtlPatternCatalog.Structure(new CapturedPatternCatalog(List.of(),0),Set.of(input,target),Set.of(),Set.of(),bounded,Map.of(),0);
        field(work,"snapshot",new GtlPatternCatalog.Snapshot(structure,stock,Set.of(),0,false));
        if(prepared){
            field(work,"compiler",compiler);field(work,"prepared",new CapturedPatternCatalog.Prepared(compiler,Map.of("binding",binding)));
            field(work,"planning",new RequestPlanningWork<>(compiler,target,7L,stock,Set.of(),null,true,false,
                    CalculationStrategy.REPORT_MISSING_ITEMS.toString(),CatalystPolicy.MINIMAL,
                    ConfigHolder.INSTANCE.ae2GraphFallback,bounded,budget,request::isCancelled,report->{}));
        }
        field(work,"selected",new GraphPlan<>(target,7,true,new PlanStep.Sequence(List.of()),Map.of(),Map.of(),Map.of(),Map.of(),GraphPlan.Result.UNKNOWN,0,0));
        if(cancelled)request.cancel(false);
        return work;
    }
    static AeGraphPlan limited(Object w,PlanningBudget.Limit limit) {
        return (AeGraphPlan)((PlanningScheduler.Work<?>)w).limited(new PlanningBudget.Exhausted(limit));
    }
    static void reject(Object w,PlanningBudget.Limit limit) {
        try{limited(w,limit);throw new AssertionError("fallback bypassed "+limit);}catch(PlanningBudget.Exhausted expected){check(expected.limit()==limit,"failure changed");}
    }
    public static void run(AEKey input,AEKey target,IPatternDetails binding)throws Exception {
        type=Class.forName(CraftingEngineRouter.class.getName()+"$RequestWork");
        var prior=ConfigHolder.INSTANCE;
        try {
            ConfigHolder.INSTANCE=new ConfigHolder();
            check(ConfigHolder.INSTANCE.ae2GraphFallback,"fallback not enabled by default");
            for(var limit:List.of(PlanningBudget.Limit.SEARCH_LIMIT,PlanningBudget.Limit.TIMEOUT,PlanningBudget.Limit.MEMORY_LIMIT,PlanningBudget.Limit.GRAPH_LIMIT)) {
                var result=limited(work(input,target,binding,false,false,true),limit);
                check(result.fallback()&&result.simulation()&&result.missingItems().get(input)==1,"wrong routed fallback");
                check(result.patternTimes().get(binding)==3&&result.graph().seeds().isEmpty(),"binding or seed leak");
            }
            Object unknown=work(input,target,binding,true,false,true);
            check(finish(unknown),"UNKNOWN fallback did not finish");
            check(((AeGraphPlan)((PlanningScheduler.Work<?>)unknown).result()).fallback(),"bounded alternatives lost marker");
            ConfigHolder.INSTANCE.ae2GraphFallback=false;
            reject(work(input,target,binding,false,false,true),PlanningBudget.Limit.SEARCH_LIMIT);
            ConfigHolder.INSTANCE.ae2GraphFallback=true;
            reject(work(input,target,binding,false,true,true),PlanningBudget.Limit.SEARCH_LIMIT);
            reject(work(input,target,binding,false,false,false),PlanningBudget.Limit.MEMORY_LIMIT);
            reject(work(input,target,binding,false,false,true),PlanningBudget.Limit.QUEUE_LIMIT);
            Object unsupported=work(input,target,binding,false,false,true);
            field(unsupported,"selected",new GraphPlan<>(target,7,true,new PlanStep.Sequence(List.of()),Map.of(),Map.of(),Map.of(),Map.of(),GraphPlan.Result.UNSUPPORTED_PATTERN_SEMANTICS,0,0));
            try{finish(unsupported);throw new AssertionError("unsupported semantics bypassed");}catch(InvocationTargetException expected){
                check(PlanningFailure.result(expected.getCause())==GraphPlan.Result.UNSUPPORTED_PATTERN_SEMANTICS,"wrong unsupported failure");
                check("gtlcore.ae.graph.failure.unsupported_pattern_semantics".equals(GraphPlanningFailure.messageKey(expected.getCause())),"host failure translation changed");
            }
            var yaml=new dev.toma.configuration.config.format.YamlFormat();
            yaml.writeBoolean("ae2GraphFallback",false);
            var file=new java.io.File("graph-fallback-config.yml");yaml.writeFile(file);
            var read=new dev.toma.configuration.config.format.YamlFormat();read.readFile(file);
            check(!read.readBoolean("ae2GraphFallback"),"disabled setting did not round-trip");
            System.out.println("Graph fallback routing: default/off, UNKNOWN/limits, bounded alternatives, cancelled/queue/snapshot/unsupported exclusion, AE display and YAML passed");
        } finally {ConfigHolder.INSTANCE=prior;}
    }
}
