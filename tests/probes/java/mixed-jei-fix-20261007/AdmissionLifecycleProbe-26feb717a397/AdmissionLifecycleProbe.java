package org.cgse.core;
import java.util.*;
public final class AdmissionLifecycleProbe {
 static GraphRecipe<String> recipe(String id,String input,String output){return new GraphRecipe<>(id,id,List.of(new GraphRecipe.Slot<>(input,1)),Map.of(output,1L));}
 static GraphCompiler<String> compiler(int n){var recipes=new ArrayList<GraphRecipe<String>>();recipes.add(recipe("cycle-t","X","T"));recipes.add(recipe("cycle-x","T","X"));recipes.add(recipe("good-t","c0","T"));for(int i=0;i<n;i++)recipes.add(recipe("chain-"+i,"c"+(i+1),"c"+i));return new GraphCompiler<>(recipes);}
 public static void main(String[]args)throws Exception{
  for(int n:new int[]{7000}){
   var c=compiler(n);var budget=new PlanningBudget(0,4_000_000,128L<<20,()->false,System::nanoTime);
   var work=new GraphPlanningWork<>(c,"T",1,Map.of("c"+n,1L),true,true,budget);try{
    var resume=GraphPlanningWork.class.getDeclaredField("stockResumePhase");resume.setAccessible(true);int resumed=0;int old=-1;while(!work.step()){int now=resume.getInt(work);if(old>=0&&now<0)resumed++;old=now;};System.out.println("resumed="+resumed);var p=work.result();if(!p.feasible())throw new AssertionError(p.result()+" "+budget.diagnostics());PlanVerifier.verify(p);PlanVerifier.verifyRuntimeInventory(p);System.out.println("n="+n+" result="+p.result()+" work="+budget.nodes()+" diagnostics="+budget.diagnostics());
   }finally{work.close();}if(budget.reservedBytes()!=0)throw new AssertionError("leak "+budget.reservedBytes());
  }
 }
}
