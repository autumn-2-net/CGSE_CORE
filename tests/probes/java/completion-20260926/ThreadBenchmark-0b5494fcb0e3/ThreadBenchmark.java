package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.cgse.core.GeneralSearchReview.*;

public class ThreadBenchmark {
    record Case(String name,List<GraphRecipe<String>> recipes,Map<String,Long> stock,String target,long amount){}
    record Sample(double millis,int peak,long nodes){}
    static Case file(Path path)throws Exception{
        var o=JsonParser.parseString(Files.readString(path)).getAsJsonObject();var recipes=new ArrayList<GraphRecipe<String>>();
        for(var e:o.getAsJsonArray("recipes")){var a=e.getAsJsonObject();recipes.add(r(a.get("id").getAsString(),FixedReplay.longs(a.getAsJsonObject("inputs")),FixedReplay.longs(a.getAsJsonObject("outputs"))));}
        return new Case("finite12",recipes,FixedReplay.longs(o.getAsJsonObject("stock")),o.get("target").getAsString(),o.get("amount").getAsLong());
    }
    static List<Case> cases()throws Exception{
        var out=new ArrayList<Case>();var chain=new ArrayList<GraphRecipe<String>>();
        for(int i=0;i<2048;i++)chain.add(r("c"+i,Map.of("s"+i,1L),Map.of("s"+(i+1),1L)));
        out.add(new Case("chain2048",chain,Map.of("s0",Long.MAX_VALUE),"s2048",Long.MAX_VALUE));
        var wide=new ArrayList<GraphRecipe<String>>();var inputs=new LinkedHashMap<String,Long>();
        for(int i=0;i<1024;i++){wide.add(r("w"+i,Map.of("raw",1L),Map.of("x"+i,1L)));inputs.put("x"+i,1L);}
        wide.add(r("finish",inputs,Map.of("p",1L)));out.add(new Case("wide1024",wide,Map.of("raw",1024000L),"p",1000));
        var loop=List.of(r("out",Map.of("a",1L,"f",1L),Map.of("b",1L,"p",1L)),r("back",Map.of("b",1L),Map.of("a",1L)));
        out.add(new Case("long_cycle",loop,Map.of("a",1L,"f",Long.MAX_VALUE),"p",Long.MAX_VALUE));
        out.add(file(Path.of(".local/f4cb-counterexamples-20260926/batch/CGSE_batch_f4cbf89/recorded/screen/cases/bounded_n12_cap8_d2_w1000_s7_shift0.json")));
        return out;
    }
    static Sample run(PlanningScheduler scheduler,Case c,int jobs)throws Exception{
        var futures=new ArrayList<CompletableFuture<GraphPlan<String>>>();var budgets=new ArrayList<PlanningBudget>();
        long start=System.nanoTime();
        for(int i=0;i<jobs;i++){
            var b=new PlanningBudget(0,10000000,128L<<20,()->false,System::nanoTime);b.enableMetrics();budgets.add(b);
            futures.add(scheduler.submit(new GraphPlanningWork<>(new GraphCompiler<>(c.recipes()),c.target(),c.amount(),c.stock(),true,true,b),b));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(30,TimeUnit.SECONDS);
        double ms=(System.nanoTime()-start)/1e6;
        int peak=0;long nodes=0;
        for(int i=0;i<jobs;i++){
            var p=futures.get(i).join();check(p.feasible(),c.name()+" "+p.result()+" "+budgets.get(i).diagnostics());
            // Exact independent execution-summary check outside measured time.
            var s=FixedReplay.summary(p.steps(),p.recipes(),new IdentityHashMap<>());
            for(var e:s.need().entrySet())check(p.initialExact().getOrDefault(e.getKey(),z(0)).compareTo(e.getValue())>=0,"prefix");
            check(p.initialExact().getOrDefault(c.target(),z(0)).add(s.delta().getOrDefault(c.target(),z(0))).compareTo(z(c.amount()))>=0,"target");
            peak=Math.max(peak,Math.toIntExact(budgets.get(i).metrics().peakActiveWorkers()));nodes+=budgets.get(i).nodes();
        }
        return new Sample(ms,peak,nodes);
    }
    public static void main(String[]args)throws Exception{
        var cases=cases();var pools=new LinkedHashMap<Integer,PlanningScheduler>();
        for(int n:new int[]{1,4,8,16})pools.put(n,new PlanningScheduler(n,16,4096,2000000));
        var samples=new LinkedHashMap<String,List<Sample>>();
        try{
            for(int round=-8;round<21;round++)for(Case c:cases)for(int jobs:new int[]{1,16}){
                var order=new ArrayList<>(pools.keySet());Collections.rotate(order,Math.floorMod(round,4));
                for(int width:order){var s=run(pools.get(width),c,jobs);if(round>=0)samples.computeIfAbsent(c.name()+","+jobs+","+width,k->new ArrayList<>()).add(s);}
            }
            System.out.println("case,jobs,workers,median_ms,p95_ms,orders_per_second,max_order_workers,median_checks");
            for(var e:samples.entrySet()){
                var s=e.getValue().stream().sorted(Comparator.comparingDouble(Sample::millis)).toList();int jobs=Integer.parseInt(e.getKey().split(",")[1]);
                double med=s.get(s.size()/2).millis();long[] work=s.stream().mapToLong(Sample::nodes).sorted().toArray();
                System.out.printf(Locale.ROOT,"%s,%.4f,%.4f,%.2f,%d,%d%n",e.getKey(),med,s.get(19).millis(),jobs*1000/med,s.stream().mapToInt(Sample::peak).max().orElse(0),work[work.length/2]);
            }
        }finally{pools.values().forEach(PlanningScheduler::close);}
    }
}
