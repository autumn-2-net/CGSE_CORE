package org.cgse.core;

import com.google.gson.*;
import java.nio.file.*;
import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Actual shared-order parallel execution through the unchanged production scheduler. */
public final class ParallelCountBenchmark {
    record Outcome(GraphPlan<String> plan, boolean infeasible, String limit) {}
    static final class ScheduledCounts implements PlanningScheduler.Work<Outcome> {
        final CountBenchmark.Embedding embedding;final PlanningBudget budget;final long started;
        final CompletableFuture<Void> closed=new CompletableFuture<>();final AtomicBoolean closing=new AtomicBoolean();
        final Set<String> coordinatorThreads=ConcurrentHashMap.newKeySet();
        IntegerCountSearch<String> search;Outcome outcome;int resumptions;
        ScheduledCounts(CountBenchmark.Embedding e,PlanningBudget b,long start){embedding=e;budget=b;started=start;}
        public boolean advance(PlanningScheduler.Slice slice){
            coordinatorThreads.add(Thread.currentThread().getName());
            if(search==null)search=new IntegerCountSearch<>(new GraphCompiler<>(embedding.recipes),"target",1,embedding.stock,Map.of(),Set.of(),Set.of(),false,true,budget,started);
            while(slice.next()){
                if(search.step(slice)){
                    var p=search.result();boolean impossible=search.infeasible();
                    if(p!=null||impossible||!search.paused()||budget.remainingWork()<8192){outcome=new Outcome(p,impossible,"");return true;}
                    search.resume();resumptions++;
                }
                if(search.waitingFor()!=null)return false;
            }
            return false;
        }
        public CompletableFuture<?> waitingFor(){return search==null?null:search.waitingFor();}
        public Outcome result(){return outcome;}
        public Outcome limited(PlanningBudget.Exhausted limit){
            outcome=new Outcome(search==null?null:search.result(),search!=null&&search.infeasible(),limit.limit().name());return outcome;
        }
        public void close(){
            if(!closing.compareAndSet(false,true))return;
            try{
                var waiting=waitingFor();if(search!=null)search.close();
                if(waiting!=null&&!waiting.isDone())waiting.whenComplete((value,failure)->{
                    try{search.close();closed.complete(null);}catch(Throwable error){closed.completeExceptionally(error);}
                });else closed.complete(null);
            }catch(Throwable error){closed.completeExceptionally(error);}
        }
    }
    public static void main(String[]args)throws Exception{
        JsonArray cases=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();
        JsonArray requests=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray();
        Map<String,JsonObject> byId=new HashMap<>();for(var value:cases){var c=value.getAsJsonObject();byId.put(c.get("id").getAsString(),c);}
        try(var writer=new PrintWriter(Files.newBufferedWriter(Path.of(args[2])))){
            for(var value:requests){var request=value.getAsJsonObject();String id=request.get("id").getAsString();var c=byId.get(id);if(c==null)throw new IllegalArgumentException("No fixture "+id);
                int variation=request.get("variation").getAsInt(),workers=request.get("workers").getAsInt(),n=c.getAsJsonArray("lower").size();
                long wall=request.get("wallMs").getAsLong(),baseMaximum=request.get("baseWorkLimit").getAsLong();
                boolean expanded=request.get("expandParallelWork").getAsBoolean();
                long maximum=PlanningBudget.parallelWorkLimit(baseMaximum,workers,expanded);
                var order=new ArrayList<Integer>();for(int i=0;i<n;i++)order.add(i);if(variation>0)Collections.shuffle(order,new Random(7001+variation));
                int[] remap=new int[n];for(int i=0;i<n;i++)remap[order.get(i)]=i;
                BigInteger[] lo=new BigInteger[n],hi=new BigInteger[n];for(int i=0;i<n;i++){lo[remap[i]]=c.getAsJsonArray("lower").get(i).getAsBigInteger();var h=c.getAsJsonArray("upper").get(i);hi[remap[i]]=h.isJsonNull()?null:h.getAsBigInteger();}
                var rows=new ArrayList<ExactLinearProgram.Constraint>();for(var raw:c.getAsJsonArray("rows")){var row=raw.getAsJsonObject();Map<Integer,BigInteger> terms=new LinkedHashMap<>();for(var term:row.getAsJsonObject("terms").entrySet())terms.put(remap[Integer.parseInt(term.getKey())],term.getValue().getAsBigInteger());rows.add(new ExactLinearProgram.Constraint(terms,row.get("upper").getAsBigInteger()));}
                if(variation>0)Collections.shuffle(rows,new Random(8009+variation));
                long preparing=System.nanoTime();var embedding=new CountBenchmark.Embedding(rows,lo,hi);double preparationMs=(System.nanoTime()-preparing)/1e6;
                var budget=new PlanningBudget(wall,maximum,256L<<20,()->false,System::nanoTime);budget.enableMetrics();budget.phase(PlanningBudget.Phase.SOLVE);
                long started=System.nanoTime();var work=new ScheduledCounts(embedding,budget,started);Outcome result=null;String error="",status="UNKNOWN";
                long solverFinished=started;int schedulerPeak=0;long schedulerSlices=0,schedulerActive=0;
                try(var scheduler=new PlanningScheduler(workers,1,4096,2_000_000)){
                    var submitted=scheduler.submit(work,budget);
                    try{result=submitted.get(wall+15000,TimeUnit.MILLISECONDS);solverFinished=System.nanoTime();}
                    catch(Throwable failure){error=failure.toString();status="ERROR";failure.printStackTrace(System.err);}
                    finally{
                        if(!submitted.isDone())submitted.cancel(false);
                        // Normal disposal occurs after the scheduler's worker timing scope ends.
                        // Do not race that final accounting with an early caller-side close.
                        work.closed.get(15,TimeUnit.SECONDS);
                        // close() may arrange release after a still-running child. Await the accounting quiescence,
                        // outside solver timing, rather than observing a race with the worker's finally block.
                        long cleanupDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                        while(budget.reservedBytes()!=0&&System.nanoTime()<cleanupDeadline)Thread.sleep(1);
                        schedulerPeak=scheduler.peakActive();schedulerSlices=scheduler.slices();schedulerActive=scheduler.activeNanos();
                    }
                }
                long validating=System.nanoTime();List<String>witness=null;
                if(result!=null){
                    if(result.plan()!=null){PlanVerifier.verifyRuntimeInventory(result.plan());BigInteger[] counts=new BigInteger[n];for(int i=0;i<n;i++)counts[i]=result.plan().patternTimesExact().getOrDefault("var"+i,BigInteger.ZERO).add(lo[i]);CountBenchmark.verify(rows,lo,hi,counts);status="SAT";witness=new ArrayList<>();for(int i=0;i<n;i++)witness.add(counts[remap[i]].toString());}
                    else if(result.infeasible())status="UNSAT";
                    else if(!result.limit().isEmpty())status="LIMIT";
                }
                Map<String,Object> record=new LinkedHashMap<>();record.put("request",request);record.put("id",id);record.put("variation",variation);record.put("workers",workers);record.put("status",status);record.put("expected",c.get("expected").getAsString());record.put("variables",n);record.put("rows",rows.size());record.put("wallCapMs",wall);record.put("workLimit",maximum);record.put("baseWorkLimit",baseMaximum);record.put("expandParallelWork",expanded);record.put("budgetPolicyApi","PlanningBudget.parallelWorkLimit");record.put("memoryLimit",256L<<20);record.put("solverMilliseconds",(solverFinished-started)/1e6);record.put("preparationMilliseconds",preparationMs);record.put("verificationMilliseconds",(System.nanoTime()-validating)/1e6);record.put("work",budget.nodes());record.put("peakBytes",budget.peakBytes());record.put("reservedAfterClose",budget.reservedBytes());record.put("limit",result==null?"":result.limit());record.put("error",error);record.put("resumeCount",work.resumptions);record.put("schedulerPeakActive",schedulerPeak);record.put("schedulerSlices",schedulerSlices);record.put("schedulerActiveNanos",schedulerActive);record.put("metrics",budget.metrics());record.put("coordinatorThreads",work.coordinatorThreads);record.put("diagnostics",budget.diagnostics());if(witness!=null)record.put("witness",witness);
                writer.println(CountBenchmark.JSON.toJson(record));writer.flush();System.out.println(id+" v"+variation+" workers="+workers+" "+status+" work="+budget.nodes()+" peak_workers="+schedulerPeak+" ms="+record.get("solverMilliseconds"));
                if(budget.reservedBytes()!=0||!error.isEmpty())throw new AssertionError("Lifecycle error "+record);
                if((status.equals("SAT")&&c.get("expected").getAsString().equals("UNSAT"))||(status.equals("UNSAT")&&c.get("expected").getAsString().equals("SAT")))throw new AssertionError("Wrong mathematical result "+record);
            }
        }
    }
}
