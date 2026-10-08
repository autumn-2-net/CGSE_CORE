package org.cgse.core;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Observe a naturally completed root proof; interrupt the enclosing handoff before its next slice. */
public final class ProofHandoffProbe {
    static Map<String,Long> amounts(JsonObject object){var out=new LinkedHashMap<String,Long>();for(var entry:object.entrySet())out.put(entry.getKey(),entry.getValue().getAsLong());return out;}
    static final class Work implements PlanningScheduler.Work<GraphPlan<String>> {
        final GraphPlanningWork<String> work;final PlanningBudget budget;final AtomicLong clock;final int fault;boolean proved,fired;
        Work(GraphPlanningWork<String> work,PlanningBudget budget,AtomicLong clock,int fault){this.work=work;this.budget=budget;this.clock=clock;this.fault=fault;}
        public boolean advance(PlanningScheduler.Slice slice){boolean done=work.advance(slice);String diag=budget.diagnostics();
            if(diag.contains("scope=full_root; outcome=PROVEN_INFEASIBLE"))proved=true;
            if(proved&&!done&&!fired){fired=true;switch(fault){case 0->budget.charge(budget.remainingWork()+1);case 1->budget.reserve(budget.availableBytes()+1);case 2->{clock.set(2_000_000);budget.checkpoint();}}}
            return done;
        }
        public CompletableFuture<?> waitingFor(){return work.waitingFor();}
        public GraphPlan<String> result(){return work.result();}
        public GraphPlan<String> limited(PlanningBudget.Exhausted limit){return work.limited(limit);}
        public void close(){work.close();}
    }
    public static void main(String[]args)throws Exception{
        var json=JsonParser.parseString(Files.readString(Path.of(".local/next-boundary-20261004/natural-proof-fixture.json"))).getAsJsonObject();var recipes=new ArrayList<GraphRecipe<String>>();
        for(var raw:json.getAsJsonArray("recipes")){var row=raw.getAsJsonObject();String id=row.get("id").getAsString();recipes.add(new GraphRecipe<>(id,id,amounts(row.getAsJsonObject("inputs")).entrySet().stream().map(x->new GraphRecipe.Slot<>(x.getKey(),x.getValue())).toList(),amounts(row.getAsJsonObject("outputs"))));}
        var results=new ArrayList<Map<String,Object>>();int lost=0;
        try(var scheduler=new PlanningScheduler(4,4,1,100_000L)){
            for(int fault=0;fault<3;fault++){
                var clock=new AtomicLong();var budget=new PlanningBudget(1,4_000_000,128L<<20,()->false,clock::get);var work=new Work(new GraphPlanningWork<>(new GraphCompiler<>(recipes),"target",1,amounts(json.getAsJsonObject("stock")),false,true,budget),budget,clock,fault);
                var plan=scheduler.submit(work,budget).get(30,TimeUnit.SECONDS);if(!work.proved||!work.fired)throw new AssertionError("Completed-root proof handoff not reached "+fault+" "+budget.diagnostics());
                boolean preserved=plan.result()==GraphPlan.Result.INFEASIBLE||plan.result()==GraphPlan.Result.MISSING_INPUT;if(!preserved)lost++;
                results.add(Map.of("fault",fault,"proofObservedBeforeCutoff",work.proved,"cutoffInjected",work.fired,"result",plan.result().name(),"proofPreserved",preserved,"diagnostics",budget.diagnostics()));
            }
        }
        var report=Map.of("normalProductionHandoffs",3,"proofLost",lost,"cases",results);Files.writeString(Path.of(args[0],"proof-handoff.json"),new Gson().toJson(report));System.out.println(new Gson().toJson(report));
        if(args.length>1&&args[1].equals("assert")&&lost!=0)throw new AssertionError("Finished proof lost "+lost+" cases");
    }
}
