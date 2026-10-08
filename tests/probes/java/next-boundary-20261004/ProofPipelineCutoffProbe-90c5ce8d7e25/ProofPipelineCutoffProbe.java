package org.cgse.core;

import com.google.gson.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Interrupts public GraphPlanningWork only after its naturally admitted PROOF task is observed. */
public final class ProofPipelineCutoffProbe {
    static final class Work implements PlanningScheduler.Work<GraphPlan<String>> {
        final GraphPlanningWork<String> delegate;final PlanningBudget budget;final AtomicLong clock;final int fault;
        boolean entered, fired;
        Work(GraphPlanningWork<String> delegate,PlanningBudget budget,AtomicLong clock,int fault){this.delegate=delegate;this.budget=budget;this.clock=clock;this.fault=fault;}
        public boolean advance(PlanningScheduler.Slice slice){
            boolean done=delegate.advance(slice);
            if(budget.diagnostics().contains("count_proof_task"))entered=true;
            if(entered&&!fired&&!done){fired=true;switch(fault){
                case 1 -> {budget.cancel();budget.checkpoint();}
                case 2 -> {clock.set(2_000_000);budget.checkpoint();}
                case 3 -> budget.charge(budget.remainingWork()+1);
                case 4 -> budget.reserve(budget.availableBytes()+1);
            }}
            return done;
        }
        public CompletableFuture<?> waitingFor(){return delegate.waitingFor();}
        public GraphPlan<String> result(){return delegate.result();}
        public GraphPlan<String> limited(PlanningBudget.Exhausted limit){return delegate.limited(limit);}
        public void close(){delegate.close();}
    }
    static CountBenchmark.Embedding example()throws Exception{
        var all=JsonParser.parseString(Files.readString(Path.of(".local/solver-weighted-20261003/public-finite.json"))).getAsJsonArray();
        JsonObject c=null;for(var raw:all)if(raw.getAsJsonObject().get("id").getAsString().equals("scip/enigma"))c=raw.getAsJsonObject();
        int n=c.getAsJsonArray("lower").size();var lo=new BigInteger[n];var hi=new BigInteger[n];for(int i=0;i<n;i++){lo[i]=c.getAsJsonArray("lower").get(i).getAsBigInteger();hi[i]=c.getAsJsonArray("upper").get(i).getAsBigInteger();}
        var rows=new ArrayList<ExactLinearProgram.Constraint>();for(var raw:c.getAsJsonArray("rows")){var row=raw.getAsJsonObject();var terms=new LinkedHashMap<Integer,BigInteger>();for(var t:row.getAsJsonObject("terms").entrySet())terms.put(Integer.parseInt(t.getKey()),t.getValue().getAsBigInteger());rows.add(new ExactLinearProgram.Constraint(terms,row.get("upper").getAsBigInteger()));}
        return new CountBenchmark.Embedding(rows,lo,hi);
    }
    public static void main(String[]args)throws Exception{
        var example=example();var cases=new ArrayList<Work>();var futures=new ArrayList<CompletableFuture<GraphPlan<String>>>();var records=new ArrayList<Map<String,Object>>();int entered=0,cancelled=0,healthy=0;
        try(var scheduler=new PlanningScheduler(4,32,512,500_000L)){
            for(int i=0;i<20;i++){
                int fault=i%5;var clock=new AtomicLong();var budget=new PlanningBudget(1,4_000_000,128L<<20,()->false,clock::get);
                var work=new Work(new GraphPlanningWork<>(new GraphCompiler<>(example.recipes),"target",1,example.stock,false,true,budget),budget,clock,fault);cases.add(work);futures.add(scheduler.submit(work,budget));
            }
            for(int i=0;i<20;i++){
                var work=cases.get(i);String status;
                try{var p=futures.get(i).get(120,TimeUnit.SECONDS);status=p.result().name();
                    if(work.fault==0){if(!p.feasible())throw new AssertionError("Healthy SAT neighbor failed: "+status);PlanVerifier.verifyRuntimeInventory(p);healthy++;}
                    else{var expected=switch(work.fault){case 2->GraphPlan.Result.TIMEOUT;case 3->GraphPlan.Result.SEARCH_LIMIT;case 4->GraphPlan.Result.MEMORY_LIMIT;default->null;};if(p.result()!=expected)throw new AssertionError("Proof cutoff became "+status+" expected "+expected);}
                }catch(CancellationException error){if(work.fault!=1)throw error;status="CANCELLED";cancelled++;}
                catch(ExecutionException error){if(work.fault!=1||!(error.getCause() instanceof CancellationException))throw error;status="CANCELLED";cancelled++;}
                if(!work.entered||!work.fired)throw new AssertionError("No production PROOF task observed before requested boundary: "+i+" "+status+" "+work.budget.diagnostics());entered++;
                records.add(Map.of("case",i,"fault",work.fault,"result",status,"proofTaskObserved",work.entered,"work",work.budget.nodes()));
            }
        }
        if(entered!=20||healthy!=4||cancelled!=4)throw new AssertionError("Coverage incomplete");
        var report=Map.of("normalGraphPlanningWorkCalls",20,"naturallyAdmittedProofTasks",entered,"parallelHealthyWitnesses",healthy,"cancelledAfterProofAdmission",cancelled,"cases",records);Files.writeString(Path.of(args[0],"proof-pipeline-cutoffs.json"),new Gson().toJson(report));System.out.println(new Gson().toJson(report));
    }
}
