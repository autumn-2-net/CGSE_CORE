package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

public class CountStrategyChecks {
    static int checks;
    static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out){return ExactAnalysisChecks.r(id,in,out);}
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static GraphPlan<String> solve(String name,List<GraphRecipe<String>> recipes,String target,long amount,Map<String,Long> stock,Map<String,Long> seeds,boolean feasible,boolean impossible) {
        var budget=ExactAnalysisChecks.budget();long start=System.nanoTime();
        var work=new IntegerCountSearch<>(new GraphCompiler<>(recipes),target,amount,stock,seeds,Set.of(),Set.of(),true,true,budget,start);
        while(!work.step()){}
        var plan=work.result();
        System.out.printf("COUNT %s result=%s impossible=%s work=%d ms=%.3f counts=%s%n",name,plan==null?"none":plan.result(),work.infeasible(),budget.nodes(),(System.nanoTime()-start)/1e6,plan==null?Map.of():plan.patternTimesExact());
        check((plan!=null)==feasible,name+" unexpected plan");check(work.infeasible()==impossible,name+" incorrect impossibility");
        if(plan!=null){var verify=new PlanVerification<>(plan,budget);while(!verify.step()){} check(plan.initialExact().entrySet().stream().allMatch(e->e.getValue().compareTo(BigInteger.valueOf(stock.getOrDefault(e.getKey(),0L)))<=0),name+" unfunded witness");}
        return plan;
    }
    public static void main(String[] args) {
        solve("fractional-rounded",List.of(r("r",Map.of("A",3L),Map.of("B",2L))),"B",5,Map.of("A",9L),Map.of(),true,false);
        solve("integer-only-impossible",List.of(r("r1",Map.of("A",2L),Map.of("B",3L)),r("r2",Map.of("A",2L),Map.of("B",2L))),"B",4,Map.of("A",3L),Map.of(),false,true);
        for(long amount:new long[]{1,Long.MAX_VALUE}) {
            var recipes=List.of(r("ab",Map.of("A",1L),Map.of("B",1L)),r("ba",Map.of("B",1L),Map.of("A",1L,"P",1L)),r("lend",Map.of("S",1L),Map.of("A",1L)),r("repay",Map.of("A",1L),Map.of("S",1L)));
            var plan=solve("loan-"+amount,recipes,"P",amount,Map.of("S",1L),Map.of("S",1L),true,false);
            check(plan.patternTimesExact().get("lend").equals(BigInteger.ONE),"loan omitted");check(plan.patternTimesExact().get("repay").equals(BigInteger.ONE),"loan not repaid");
        }
        var thresholds=List.of(r("ab",Map.of("A",2L),Map.of("B",2L)),r("ba",Map.of("B",2L),Map.of("A",2L,"P",1L)),r("lend",Map.of("S",1L),Map.of("A",1L)),r("repay",Map.of("A",1L),Map.of("S",1L)));
        solve("integer-startup-refinement",thresholds,"P",1,Map.of("A",1L,"B",1L,"S",1L),Map.of("S",1L),true,false);
        solve("long-integer-startup-refinement",thresholds,"P",Long.MAX_VALUE,Map.of("A",1L,"B",1L,"S",1L),Map.of("S",1L),true,false);
        solve("coproduct-mixed",List.of(r("r1",Map.of("R",1L),Map.of("A",1L,"B",1L)),r("r2",Map.of("S",1L),Map.of("A",1L)),r("join",Map.of("A",2L,"B",1L),Map.of("P",1L))),"P",1,Map.of("R",1L,"S",1L),Map.of(),true,false);
        solve("fractional-triangle",List.of(r("ab",Map.of("R",1L),Map.of("A",1L,"B",1L)),r("ac",Map.of("R",1L),Map.of("A",1L,"C",1L)),r("bc",Map.of("R",1L),Map.of("B",1L,"C",1L)),r("join",Map.of("A",1L,"B",1L,"C",1L),Map.of("P",1L))),"P",1,Map.of("R",2L),Map.of(),true,false);
        solve("integer-triangle-impossible",List.of(r("ab",Map.of("RA",1L,"RB",1L),Map.of("A",1L,"B",1L)),r("ac",Map.of("RA",1L,"RC",1L),Map.of("A",1L,"C",1L)),r("bc",Map.of("RB",1L,"RC",1L),Map.of("B",1L,"C",1L)),r("join",Map.of("A",1L,"B",1L,"C",1L),Map.of("P",1L))),"P",1,Map.of("RA",1L,"RB",1L,"RC",1L),Map.of(),false,true);
        System.out.println("COUNT_STRATEGY_PASS "+checks);
    }
}
