package org.cgse.core;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.cgse.core.ContrastProbe.*;
public final class CoupledReview {
    static void check(Fixture f,GraphPlan<String> p,PlanningBudget b){if(!p.feasible())throw new AssertionError(f.name()+" "+p.result()+" "+b.diagnostics());verify(f,summary(p.steps(),p.recipes()));}
    public static void main(String[]args)throws Exception{
        int passed=0;
        for(int n:new int[]{12,20,32,48,64})for(int seed=0;seed<8;seed++){
            var f=sat(n,n*4,seed);var b=new PlanningBudget(10000,20_000_000,256L<<20,()->false,System::nanoTime);
            var p=new GraphPlanner<>(new GraphCompiler<>(f.recipes())).plan(f.target(),1,f.stock(),true,true,b);check(f,p,b);passed++;System.out.println("PASS n="+n+" seed="+seed+" work="+b.nodes());
        }
        var f=sat(32,128,42);
        for(int n:new int[]{1,4,8,16})try(var scheduler=new PlanningScheduler(n,16,512,1_000_000)){
            var b=new PlanningBudget(10000,20_000_000,256L<<20,()->false,System::nanoTime);var w=new GraphPlanner<>(new GraphCompiler<>(f.recipes())).begin(f.target(),1,f.stock(),true,true,b);var p=scheduler.submit(w,b).get(15,TimeUnit.SECONDS);check(f,p,b);System.out.println("PASS scheduled workers="+n+" work="+b.nodes());
        }
        System.out.println("PASS coupled fixtures="+passed+" plus workers 1/4/8/16; independent prefix and goal checks");
    }
}
