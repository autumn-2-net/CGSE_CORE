package org.cgse.core;

import com.google.gson.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Local-only: runs the real GraphPlanningWork state machine from its public constructor. */
public final class OuterProofScan {
    static final Gson JSON = new Gson();
    static final class Observed implements PlanningScheduler.Work<GraphPlan<String>> {
        final GraphPlanningWork<String> work;
        final PlanningBudget budget;
        final LinkedHashSet<String> diagnostics = new LinkedHashSet<>();
        final CountDownLatch closed = new CountDownLatch(1);
        Observed(GraphPlanningWork<String> work, PlanningBudget budget) {this.work=work;this.budget=budget;}
        public boolean advance(PlanningScheduler.Slice slice) {try{return work.advance(slice);}finally{capture();}}
        synchronized void capture(){diagnostics.addAll(Arrays.asList(budget.diagnostics().split(" \\| ")));}
        synchronized List<String> snapshot(){return List.copyOf(diagnostics);}
        public CompletableFuture<?> waitingFor(){return work.waitingFor();}
        public GraphPlan<String> result(){return work.result();}
        public GraphPlan<String> limited(PlanningBudget.Exhausted limit){return work.limited(limit);}
        public void close(){try{work.close();capture();}finally{closed.countDown();}}
    }
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);
        String filter=args.length>1?args[1]:"";
        int workers=args.length>2?Integer.parseInt(args[2]):1;
        long maxWork=args.length>3?Long.parseLong(args[3]):4_000_000;
        JsonArray cases=JsonParser.parseString(Files.readString(Path.of(".local/solver-weighted-20261003/public-finite.json"))).getAsJsonArray();
        try(var writer=Files.newBufferedWriter(out.resolve("outer-proof-scan.jsonl"));var scheduler=new PlanningScheduler(workers,4,2048,2_000_000L)){
            for(var raw:cases){var c=raw.getAsJsonObject();String id=c.get("id").getAsString();int n=c.getAsJsonArray("lower").size();if(n<8||n>188||!id.contains(filter))continue;
                var lo=new BigInteger[n];var hi=new BigInteger[n];for(int i=0;i<n;i++){lo[i]=c.getAsJsonArray("lower").get(i).getAsBigInteger();hi[i]=c.getAsJsonArray("upper").get(i).getAsBigInteger();}
                var rows=new ArrayList<ExactLinearProgram.Constraint>();for(var e:c.getAsJsonArray("rows")){var r=e.getAsJsonObject();var terms=new LinkedHashMap<Integer,BigInteger>();for(var t:r.getAsJsonObject("terms").entrySet())terms.put(Integer.parseInt(t.getKey()),t.getValue().getAsBigInteger());rows.add(new ExactLinearProgram.Constraint(terms,r.get("upper").getAsBigInteger()));}
                var embedding=new CountBenchmark.Embedding(rows,lo,hi);var budget=new PlanningBudget(0,maxWork,128L<<20,()->false,System::nanoTime);budget.enableMetrics();
                var compiler=new GraphCompiler<String>(embedding.recipes);var observed=new Observed(new GraphPlanningWork<>(compiler,"target",1,embedding.stock,false,true,budget),budget);
                long start=System.nanoTime();var plan=scheduler.submit(observed,budget).get(180,TimeUnit.SECONDS);
                if(!observed.closed.await(30,TimeUnit.SECONDS))throw new AssertionError("work did not close");
                if(plan.feasible()){PlanVerifier.verifyRuntimeInventory(plan);if(c.get("expected").getAsString().equals("UNSAT"))throw new AssertionError("invalid feasible "+id);}
                if(c.get("expected").getAsString().equals("SAT")&&(plan.result()==GraphPlan.Result.INFEASIBLE||plan.result()==GraphPlan.Result.MISSING_INPUT||plan.result()==GraphPlan.Result.MISSING_SEED))throw new AssertionError("invalid missing "+id+" "+plan.result()+" "+budget.diagnostics());
                var result=new LinkedHashMap<String,Object>();result.put("id",id);result.put("expected",c.get("expected").getAsString());result.put("result",plan.result());result.put("work",budget.nodes());result.put("millis",(System.nanoTime()-start)/1e6);result.put("reservedAfterClose",budget.reservedBytes());result.put("diagnostics",observed.snapshot());result.put("strategyMetrics",budget.metrics().strategies());
                writer.write(JSON.toJson(result));writer.newLine();writer.flush();System.out.println(id+" "+plan.result()+" work="+budget.nodes()+" trace="+observed.snapshot().stream().filter(x->x.toLowerCase().contains("proof")).toList());
            }
        }
    }
}
