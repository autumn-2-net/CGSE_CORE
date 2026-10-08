package org.cgse.core;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.util.*;

/** Local white-box continuation fixture; injects a verified but force-rejected candidate at the final gate. */
public final class RejectedRoutingProbe {
    static Object get(Object x,String name)throws Exception { var f=x.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(x); }
    static void set(Object x,String name,Object value)throws Exception { var f=x.getClass().getDeclaredField(name);f.setAccessible(true);f.set(x,value); }
    static GraphPlanningWork<String> rejected(PlanningBudget budget)throws Exception {
        var out = new GraphRecipe<>("out","out",List.of(new GraphRecipe.Slot<>("C",1L)),Map.of("A",1L));
        var main = new GraphRecipe<>("main","main",List.of(new GraphRecipe.Slot<>("A",1L),new GraphRecipe.Slot<>("cfg",1L,1,true)),Map.of("C",3L));
        var work = new GraphPlanner<>(new GraphCompiler<>(List.of(out,main))).begin("C",3,Map.of("C",1L,"cfg",2L),false,true,budget);
        while ((int)get(work,"phase") != 4) { if(work.step())throw new AssertionError("finished before final gate"); }
        if(get(work,"best")!=null)throw new AssertionError("fixture unexpectedly had best");
        var bad = new GraphPlan<>("C",3,false,new PlanStep.Sequence(List.of(new PlanStep.Batch("out",1),new PlanStep.Batch("main",1))),Map.of("out",out,"main",main),Map.of("C",BigInteger.ONE,"cfg",BigInteger.ONE),Map.of(),Map.of(),GraphPlan.Result.FEASIBLE,0,0);
        ((PlanVerification<?>)get(work,"verifying")).close();
        set(work,"candidate",bad);set(work,"verifying",new PlanVerification<>(bad,budget));set(work,"triedTargetSeedConsumption",true);
        while ((int)get(work,"phase") == 4) { if(work.step())throw new AssertionError("rejected gate finished whole request"); }
        var best=(GraphPlan<?>)get(work,"best");
        if(best==null||best.feasible()||best.result()!=GraphPlan.Result.UNKNOWN||!best.missingExact().isEmpty())throw new AssertionError("missing/unsafe rejected-candidate placeholder");
        if(get(work,"verified")!=null)throw new AssertionError("unproductive candidate retained as verified");
        return work;
    }
    public static void main(String[] args)throws Exception {
        var budget=new PlanningBudget(0,2_000_000,64L<<20,()->false,System::nanoTime);
        var work=rejected(budget);
        try {
            while(!work.step()){}
            if(!(boolean)get(work,"allocationAttempted"))throw new AssertionError("allocation skipped after rejection");
            var p=work.result();if(!p.feasible())throw new AssertionError("known alternate witness not recovered "+p.result()+" "+budget.diagnostics());
            try(var verified=new PlanVerification<>(p,budget)){while(!verified.step()){}if(verified.summary().delta("C").compareTo(BigInteger.valueOf(3))<0)throw new AssertionError("allocation returned fake force witness");}
            System.out.println("rejected gate recovered "+p.result()+" counts="+p.patternTimesExact()+" allocation_tried="+get(work,"allocationAttempted")+" count_tried="+get(work,"countAttempted"));
        } finally { work.close(); }
        budget=new PlanningBudget(0,2_000_000,64L<<20,()->false,System::nanoTime);
        work=rejected(budget);
        try {
            var limited=work.limited(new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT));
            if(limited.feasible()||limited.result()!=GraphPlan.Result.SEARCH_LIMIT)throw new AssertionError("placeholder escaped through limited "+limited.result());
            System.out.println("rejected gate limited="+limited.result()+" (never feasible)");
        } finally { work.close(); }
    }
}
