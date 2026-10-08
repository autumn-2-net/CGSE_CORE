package org.cgse.core;
import java.util.*;
import static org.cgse.core.GeneralSearchReview.*;
public final class SourceJumpReview {
    public static void main(String[]args)throws Exception{
        var recipes=new ArrayList<GraphRecipe<String>>();var needs=new LinkedHashMap<String,Long>();var stock=new LinkedHashMap<String,Long>();
        for(int i=0;i<4;i++){
            needs.put("u"+i,1L);stock.put("a"+i,1L);stock.put("b"+i,1L);
            recipes.add(r("u"+i+"a",Map.of("a"+i,1L),Map.of("u"+i,1L)));
            recipes.add(r("u"+i+"b",Map.of("b"+i,1L),Map.of("u"+i,1L)));
        }
        needs.put("x",1L);stock.put("raw",1L);
        recipes.add(r("bad",Map.of("absent",1L),Map.of("x",1L)));
        recipes.add(r("good",Map.of("raw",1L),Map.of("x",1L)));
        recipes.add(0,r("finish",needs,Map.of("p",1L)));
        var b=budget();var compiler=new GraphCompiler<>(recipes);
        var compilation=compiler.begin("p",Set.of(),Map.of(),Set.of(),b);while(!compilation.step()){}
        var graph=compilation.result();
        var solving=new GraphSolve<>(graph,"p",1,stock,Set.of(),Map.of(),true,true,b,System.nanoTime(),CatalystPolicy.STOCK,stock);while(!solving.step()){}
        var proofs=new OrderProofs<>(RecipeCountModel.forProofs(compiler,"p",1,stock,Map.of(),Set.of(),Set.of(),true,b),b);
        try(var explain=new SourceExplanation<>(proofs,compiler,"p",Set.of(),Set.of(),graph,Map.of(),b)){while(!explain.step()){}check(explain.result()!=null,"source proof unavailable");}
        var work=new GraphPlanningWork<>(compiler,"p",1,stock,true,true,b);
        // Resume the order after another strategy published its checked source
        // explanation, before the next queued choice has been compiled.
        var proofField=GraphPlanningWork.class.getDeclaredField("proofs");proofField.setAccessible(true);proofField.set(work,proofs);
        var bestField=GraphPlanningWork.class.getDeclaredField("best");bestField.setAccessible(true);bestField.set(work,solving.result());
        try{
            while(!work.step()){}check(work.result().feasible(),"source repairs missed valid branch");PlanVerifier.verify(work.result());
            check(work.result().patternTimes().containsKey("good")&&!work.result().patternTimes().containsKey("bad"),"bad branch executed");
            check(b.diagnostics().contains("before_compile"),"precompile explanation not exercised "+b.diagnostics());
            System.out.println("SOURCE_INTEGRATION "+b.diagnostics());
        }finally{work.close();}
    }
}
