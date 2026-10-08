package org.cgse.core;
import java.util.*;
import java.util.concurrent.*;
public class BranchParallelBench {
    record Case(String name,List<GraphRecipe<String>> recipes,Map<String,Long> stock,long amount){}
    record Sample(long nanos,long work,long memory,long workers){}
    static Case triangles(int size){
        var recipes=new ArrayList<GraphRecipe<String>>();var stock=new LinkedHashMap<String,Long>();var need=new LinkedHashMap<String,Long>();
        for(int i=0;i<size;i++){
            stock.put("R"+i,2L);need.put("Q"+i,1L);
            recipes.add(BranchSchedulingChecks.r("ab"+i,Map.of("R"+i,1L),Map.of("A"+i,1L,"B"+i,1L)));
            recipes.add(BranchSchedulingChecks.r("ac"+i,Map.of("R"+i,1L),Map.of("A"+i,1L,"C"+i,1L)));
            recipes.add(BranchSchedulingChecks.r("bc"+i,Map.of("R"+i,1L),Map.of("B"+i,1L,"C"+i,1L)));
            recipes.add(BranchSchedulingChecks.r("join"+i,Map.of("A"+i,1L,"B"+i,1L,"C"+i,1L),Map.of("Q"+i,1L)));
        }
        recipes.add(BranchSchedulingChecks.r("finish",need,Map.of("P",1L)));
        return new Case("triangles"+size,recipes,stock,1);
    }
    static Sample sample(PlanningScheduler scheduler,Case c)throws Exception{
        var b=BranchSchedulingChecks.budget();b.enableMetrics();
        long started=System.nanoTime();var w=new BranchSchedulingChecks.Work(c.recipes,c.amount,c.stock,b);
        var p=scheduler.submit(w,b).get(60,TimeUnit.SECONDS);long elapsed=System.nanoTime()-started;
        if(p==null)throw new AssertionError(c.name+" "+b.diagnostics());PlanVerifier.verify(p);BranchSchedulingChecks.awaitReleased(b);
        return new Sample(elapsed,b.nodes(),b.peakBytes(),b.metrics().peakActiveWorkers());
    }
    public static void main(String[]args)throws Exception{
        var cases=List.of(new Case("unresolved-long",BranchSchedulingChecks.competing(true),Map.of("A",1L,"F",1L),Long.MAX_VALUE),triangles(3),triangles(4));
        var schedulers=new ArrayList<PlanningScheduler>();for(int n:new int[]{1,4,8,16})schedulers.add(new PlanningScheduler(n,32,512,1_000_000));
        try{
            System.out.println("CPU logical="+Runtime.getRuntime().availableProcessors()+" warm=16 samples=31 alternated");
            for(var c:cases){
                long[][] times=new long[4][31],work=new long[4][31],memory=new long[4][31],active=new long[4][31];
                for(int i=-16;i<31;i++)for(int j=0;j<4;j++){
                    int v=Math.floorMod(i+j,4);var s=sample(schedulers.get(v),c);
                    if(i>=0){times[v][i]=s.nanos;work[v][i]=s.work;memory[v][i]=s.memory;active[v][i]=s.workers;}
                }
                for(int i=0;i<4;i++){
                    Arrays.sort(times[i]);Arrays.sort(work[i]);Arrays.sort(memory[i]);Arrays.sort(active[i]);
                    System.out.printf(Locale.ROOT,"PARALLEL case=%s threads=%d median_ms=%.4f work=%d peak_bytes=%d active=%d%n",c.name,List.of(1,4,8,16).get(i),times[i][15]/1e6,work[i][15],memory[i][15],active[i][15]);
                }
            }
        }finally{schedulers.forEach(PlanningScheduler::close);}
    }
}
