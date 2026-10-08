package org.cgse.core;

import com.google.gson.Gson;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Local-only public coordinator tests and read-only observations of optional model scope. */
public final class BoundaryPipelineProbe {
    static int checks, publicCalls, cancelled, limited, macroContradictions;
    static final List<Map<String,Object>> records=new ArrayList<>();
    static void ok(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static Object field(Object o,String name)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static GraphRecipe<String> recipe(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(x->new GraphRecipe.Slot<>(x.getKey(),x.getValue())).toList(),out);}
    static PlanningBudget budget(long work,long bytes,AtomicBoolean stop){return new PlanningBudget(0,work,bytes,stop::get,System::nanoTime);}
    static List<GraphRecipe<String>> cycles(int seed){
        var recipes=new ArrayList<GraphRecipe<String>>();
        recipes.add(recipe("grow",Map.of("A",2L,"B",3L),Map.of("A",1L,"C",3L)));
        recipes.add(recipe("back",Map.of("C",1L),Map.of("A",1L)));
        for(int i=0;i<8;i++)recipes.add(recipe("missing-"+i,Map.of("unavailable"+i,1L),Map.of("C",1L)));
        Collections.shuffle(recipes,new Random(13+seed));return recipes;
    }
    static GraphPlan<String> sync(GraphCompiler<String> compiler,long amount,Map<String,Long> stock,Map<String,Long> seeds,Set<String> external,boolean force,PlanningBudget b){
        var work=new GraphPlanningWork<>(compiler,"C",amount,stock,external,seeds,false,force,b);GraphPlan<String> result;
        try{while(!work.step()){}result=work.result();}finally{work.close();}publicCalls++;
        if(result.feasible())PlanVerifier.verifyRuntimeInventory(result);return result;
    }
    static void changingScope(){
        for(int seed=0;seed<32;seed++){
            var compiler=new GraphCompiler<>(cycles(seed));
            // Capture a genuinely impossible order first; reuse the same immutable catalog after replenishment.
            var unavailable=sync(compiler,3,Map.of("A",1L,"B",3L),Map.of(),Set.of(),true,budget(2_000_000,64L<<20,new AtomicBoolean()));
            ok(!unavailable.feasible(),"unfunded no-seed cycle invented a plan");
            var funded=sync(compiler,3,Map.of("A",1L,"B",3L,"C",1L),Map.of(),Set.of(),true,budget(2_000_000,64L<<20,new AtomicBoolean()));
            ok(funded.feasible(),"old proof contaminated replenished inventory "+seed+" "+funded.result());
            var external=sync(compiler,3,Map.of("A",1L,"B",3L),Map.of(),Set.of("C"),false,budget(2_000_000,64L<<20,new AtomicBoolean()));
            ok(external.feasible(),"old proof contaminated external material scope "+seed+" "+external.result());
            var reserved=sync(compiler,3,Map.of("A",2L,"B",3L,"C",1L),Map.of("A",1L),Set.of(),true,budget(2_000_000,64L<<20,new AtomicBoolean()));
            ok(reserved.feasible(),"old proof contaminated required seed scope "+seed+" "+reserved.result());
        }
    }
    static void restrictedMacro()throws Exception{
        for(int order=0;order<16;order++){
            var recipes=new ArrayList<GraphRecipe<String>>();
            for(int i=0;i<12;i++){
                recipes.add(recipe("enter"+i,Map.of("A"+i,1L),Map.of("I"+i,1L)));
                recipes.add(recipe("leave"+i,Map.of("I"+i,1L),Map.of("A"+i,1L,"C",1L)));
            }
            Collections.shuffle(recipes,new Random(981+order));var compiler=new GraphCompiler<>(recipes);var stock=Map.of("I0",1L);var b=budget(4_000_000,128L<<20,new AtomicBoolean());
            // Candidate macros require A_i at entry and cannot use stock I0. Original primitives can.
            try(var model=RecipeCountModel.create(compiler,"C",1,stock,Map.of(),Set.of(),Set.of(),true,b);var execution=new CountExecution<>(model,b);var owner=new IntegerCountBranch<>(model,execution,"C",1,stock,Map.of(),Set.of(),false,true,b,0,List.of());var recovery=new CountRecovery<>(owner)){
                var inners=Collections.newSetFromMap(new IdentityHashMap<IntegerCountSearch<String>,Boolean>());
                int rounds=0;
                while(true){@SuppressWarnings("unchecked") var search=(IntegerCountSearch<String>)field(recovery,"search");if(search!=null)inners.add(search);if(recovery.step())break;ok(++rounds<100000,"macro scope never completed");}
                ok(recovery.witness()==null,"fixture unexpectedly has a complete macro witness");
                int disproved=0;for(var search:inners)if(search.infeasible())disproved++;ok(disproved>0,"fixture did not actually contradict restricted model");macroContradictions+=disproved;
            }
            ok(b.reservedBytes()==0,"restricted macro released memory incorrectly");
            var p=sync(compiler,1,stock,Map.of(),Set.of(),true,budget(4_000_000,128L<<20,new AtomicBoolean()));
            ok(p.feasible(),"restricted macro no-solution leaked to original primitive model "+order+" "+p.result());
            ok(p.patternTimesExact().getOrDefault("leave0",BigInteger.ZERO).signum()>0,"primitive intermediate entry missing");
        }
    }
    static void parallelAndLimits()throws Exception{
        try(var scheduler=new PlanningScheduler(4,64,64,100_000L)){
            var futures=new ArrayList<CompletableFuture<GraphPlan<String>>>();var budgets=new ArrayList<PlanningBudget>();var switches=new ArrayList<AtomicBoolean>();
            for(int i=0;i<48;i++){
                var stop=new AtomicBoolean(i%4==0);var b=budget(i%4==1?16:2_000_000,i%4==2?128:64L<<20,stop);switches.add(stop);budgets.add(b);
                var work=new GraphPlanningWork<>(new GraphCompiler<>(cycles(i)),"C",3,Map.of("A",1L,"B",3L,"C",1L),false,true,b);futures.add(scheduler.submit(work,b));
            }
            for(int i=0;i<futures.size();i++){
                try{var p=futures.get(i).get(30,TimeUnit.SECONDS);publicCalls++;
                    if(i%4==3){ok(p.feasible(),"parallel healthy neighbor lost witness "+i+" "+p.result());PlanVerifier.verifyRuntimeInventory(p);}
                    else{ok(p.feasible()||p.result()==GraphPlan.Result.SEARCH_LIMIT||p.result()==GraphPlan.Result.MEMORY_LIMIT,"local cutoff manufactured infeasibility "+p.result());limited++;}
                    records.add(Map.of("case",i,"result",p.result().name(),"work",budgets.get(i).nodes(),"reserved",budgets.get(i).reservedBytes()));
                }catch(CancellationException ex){ok(i%4==0,"healthy job cancelled");cancelled++;}
                catch(ExecutionException ex){if(ex.getCause() instanceof CancellationException){ok(i%4==0,"healthy job cancelled");cancelled++;}else throw ex;}
            }
            ok(cancelled==12,"cancelled jobs were not exercised");ok(limited==24,"work and memory bounds were not exercised");
        }
    }
    public static void main(String[]args)throws Exception{
        changingScope();restrictedMacro();parallelAndLimits();
        var result=new LinkedHashMap<String,Object>();result.put("checks",checks);result.put("publicGraphPlanningWorkCalls",publicCalls);result.put("restrictedMacroContradictions",macroContradictions);result.put("cancelledParallelOrders",cancelled);result.put("boundedParallelOrders",limited);result.put("parallelRecords",records);
        Files.writeString(Path.of(args[0],"boundary-pipeline.json"),new Gson().toJson(result));System.out.println(new Gson().toJson(result));
    }
}
