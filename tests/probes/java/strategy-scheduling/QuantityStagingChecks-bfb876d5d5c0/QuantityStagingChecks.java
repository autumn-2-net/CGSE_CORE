package org.cgse.core;
import java.util.*;

public class QuantityStagingChecks {
    static GraphRecipe<String> r(String id,long raw){return new GraphRecipe<>(id,id,List.of(new GraphRecipe.Slot<>("R",raw)),Map.of("P",1L));}
    static PlanningBudget budget(){return new PlanningBudget(0,2_000_000,128L<<20,()->false,System::nanoTime);}
    public static void main(String[] args){
        var compiler=new GraphCompiler<>(List.of(r("costly",3),r("cheap",2)));
        for(boolean discard:new boolean[]{true,false}){
            var b=budget();var q=new QuantityAnalysis<>(compiler,"P",1000000,Map.of("R",2000000L),Set.of(),Map.of(),Set.of(),b,true);
            int steps=0;while(!q.readyForHeavyAnalysis()){if(q.step())throw new AssertionError("No deferred exact analysis");if(++steps>10000)throw new AssertionError("No fast boundary");}
            if(b.reservedBytes()==0)throw new AssertionError("Lost resumable model");
            if(discard){q.discard();q.discard();}else{while(!q.step()){}if(q.blocked())throw new AssertionError("False missing after resume");}
            if(b.reservedBytes()!=0)throw new AssertionError("Deferred analysis leaked workspace");
        }
        for(long amount:new long[]{1,1_000_000,Long.MAX_VALUE/2})for(boolean missing:new boolean[]{false,true}){
            var b=budget();var w=new GraphPlanningWork<>(compiler,"P",amount,Map.of("R",amount*2-(missing?1:0)),true,true,b);
            while(!w.step()){}var p=w.result();
            if(p.feasible()==missing)throw new AssertionError("Incorrect result "+p.result());
            if(missing){if(!p.missingExact().equals(Map.of("R",java.math.BigInteger.ONE)))throw new AssertionError("Incorrect exact one-unit deficit");}
            else{PlanVerifier.verify(p);if(b.diagnostics().contains("resume_exact_analysis"))throw new AssertionError("Cheap alternative paid for heavy proof");}
        }
        var many=new ArrayList<GraphRecipe<String>>();for(int i=0;i<15;i++)many.add(r("cost"+i,3));many.add(r("cheap",2));
        var b=budget();var w=new GraphPlanningWork<>(new GraphCompiler<>(many),"P",1000000,Map.of("R",2000000L),true,true,b);
        while(!w.step()){}if(!w.result().feasible()||!b.diagnostics().contains("resume_exact_analysis"))throw new AssertionError("Heavy fallback was lost");
        PlanVerifier.verify(w.result());
        System.out.println("QUANTITY_STAGING_PASS deferred/resumed/discarded model, alternate source, long shortage-by-one, late integer fallback");
    }
}
