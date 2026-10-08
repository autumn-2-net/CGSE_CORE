package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class PartitionEngineReview {
    record Sample(GraphPlan<String> plan,PlanningBudget budget,double ms){}
    static Sample run(LocalContrastReview.Fixture f){
        long start=System.nanoTime();var budget=new PlanningBudget(3000,20_000_000,256L<<20,()->false,System::nanoTime);
        var work=new GraphPlanner<>(new GraphCompiler<>(f.recipes())).begin(f.target(),f.amount(),f.stock(),true,true,budget);
        while(!work.step()){}var p=work.result();double ms=(System.nanoTime()-start)/1e6;
        if(p.feasible())LocalContrastReview.verify(f,LocalContrastReview.summary(p.steps(),p.recipes()));
        return new Sample(p,budget,ms);
    }
    static void check(String label,LocalContrastReview.Fixture f,boolean mustSolve){
        var s=run(f);System.out.println("CASE "+label+" result="+s.plan.result()+" ms="+s.ms+" checks="+s.budget.nodes()+" peak="+s.budget.peakBytes()+" trace="+s.budget.diagnostics());
        if(mustSolve&&!s.plan.feasible())throw new AssertionError(label+" failed "+s.plan.result());
    }
    static LocalContrastReview.Fixture scaled(LocalContrastReview.Fixture f,long factor){
        var rs=new ArrayList<GraphRecipe<String>>();
        for(var r:f.recipes()) {
            var in=new LinkedHashMap<String,Long>();var out=new LinkedHashMap<String,Long>();
            r.inputs().forEach((k,v)->in.put(k,k.equals("X")||k.equals("Y")?Math.multiplyExact(v,factor):v));
            r.outputs().forEach((k,v)->out.put(k,k.equals("X")||k.equals("Y")?Math.multiplyExact(v,factor):v));
            rs.add(LocalContrastReview.r(r.id(),in,out));
        }
        return new LocalContrastReview.Fixture("scaled",rs,f.stock(),f.target(),f.amount(),true);
    }
    static void scheduled(LocalContrastReview.Fixture f)throws Exception{
        for(int n:new int[]{1,4,8,16})try(var scheduler=new PlanningScheduler(n,16,512,1_000_000)){
            for(int i=0;i<5;i++){
                var b=new PlanningBudget(3000,20_000_000,256L<<20,()->false,System::nanoTime);
                var work=new GraphPlanner<>(new GraphCompiler<>(f.recipes())).begin(f.target(),f.amount(),f.stock(),true,true,b);
                var p=scheduler.submit(work,b).get(10,TimeUnit.SECONDS);
                if(!p.feasible())throw new AssertionError("Scheduled "+n+" "+p.result());
                LocalContrastReview.verify(f,LocalContrastReview.summary(p.steps(),p.recipes()));
            }
            System.out.println("SCHEDULE threads="+n+" passed=5/5");
        }
    }
    public static void main(String[] args)throws Exception{
        var f=LocalContrastReview.file(args[0]);
        for(int i=0;i<16;i++)if(!run(f).plan.feasible())throw new AssertionError("Warmup failed");
        double[] times=new double[21];var status=new LinkedHashSet<String>();
        for(int i=0;i<times.length;i++){var s=run(f);times[i]=s.ms;status.add(s.plan.result().name());if(!s.plan.feasible())throw new AssertionError("Measured failure");}
        Arrays.sort(times);System.out.println("BENCH subset20 warmup=16 passed=21/21 median_ms="+times[10]+" min_ms="+times[0]+" max_ms="+times[20]+" status="+status);
        check("original",f,true);
        var balanced=new ArrayList<GraphRecipe<String>>();
        for(var r:f.recipes()){
            if(r.id().equals("yes2"))balanced.add(LocalContrastReview.r(r.id(),r.inputs(),Map.of("X",25617L)));
            else if(r.id().equals("no2"))balanced.add(LocalContrastReview.r(r.id(),r.inputs(),Map.of("Y",25617L)));
            else if(r.id().equals("finish"))balanced.add(LocalContrastReview.r(r.id(),Map.of("X",50270L,"Y",50270L),r.outputs()));
            else balanced.add(r);
        }
        check("balanced_finish_range_1_to_2",new LocalContrastReview.Fixture("balanced",balanced,f.stock(),f.target(),1,true),true);
        check("near_long_coefficients",scaled(f,Long.MAX_VALUE/81593),true);
        for(int n:new int[]{12,28,36,48})check("sources_"+n,LocalContrastReview.subset(n,42),true);
        var reversed=new ArrayList<>(f.recipes());Collections.reverse(reversed);check("reversed",new LocalContrastReview.Fixture("reverse",reversed,f.stock(),f.target(),1,true),true);
        Collections.shuffle(reversed,new Random(791));check("shuffled",new LocalContrastReview.Fixture("shuffle",reversed,f.stock(),f.target(),1,true),true);
        for(long amount:new long[]{1,Integer.MAX_VALUE,Long.MAX_VALUE})check("cycle_"+amount,LocalContrastReview.cycle(amount),true);
        scheduled(f);
    }
}
