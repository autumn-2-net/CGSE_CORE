package org.cgse.core;
import java.util.*;
import java.math.BigInteger;
public class OrderAssemblyProbe {
 static GraphRecipe<String> recipe(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
 public static void main(String[] args){
  var b=new PlanningBudget(0,40000000,256L<<20,()->false,System::nanoTime);
  var grow=recipe("grow",Map.of("A",2L,"B",3L),Map.of("A",1L,"C",3L));
  var back=recipe("back",Map.of("C",1L),Map.of("A",1L));
  var recipes=List.of(back,grow);var stock=Map.of("A",2L,"B",3L,"C",1L);var byId=Map.of("back",back,"grow",grow);
  var counts=new BigInteger[]{BigInteger.ONE,BigInteger.ONE};
  try(var model=RecipeCountModel.forShell(recipes,Map.of("C",BigInteger.valueOf(3)),stock,Set.of(),b);var pool=new CountScheduleContinuations<>(model,b)){
   try(var schedule=pool.acquire(counts)){while(!schedule.step()){}System.out.println("Scheduled: "+schedule.witness());pool.remember(counts,schedule.witness());}
   for(var p:List.of(new PlanStep.Sequence(List.of(new PlanStep.Batch("back",1),new PlanStep.Batch("grow",1))),new PlanStep.Sequence(List.of(new PlanStep.Batch("grow",1),new PlanStep.Batch("back",1))),pool.witness(counts))){
    try(var candidate=new AllocationSearch.Candidate<>(p,byId,"C",3,stock,Map.of(),Set.of(),true,true,false,b,System.nanoTime())){while(!candidate.step()){}System.out.println("Program: "+p+" => "+(candidate.plan==null?"REJECTED":candidate.plan.result())+" summaryneed="+candidate.summary.required()+" seeds="+candidate.seeds);}
   }
  }
  System.out.println("remaining="+b.reservedBytes());
  var back2=recipe("back",Map.of("C",2L),Map.of("A",1L,"C",1L));
  for(boolean recovery:List.of(false,true)) for(boolean reverse:List.of(false,true)){
   var budget=new PlanningBudget(0,40000000,256L<<20,()->false,System::nanoTime);
   var rr=reverse?List.of(grow,back2):List.of(back2,grow);
   try(var solver=new IntegerCountSearch<>(new GraphCompiler<>(rr),"C",4,Map.of("A",2L,"B",3L,"C",2L),Map.of("A",2L),Set.of(),Set.of(),true,true,budget,System.nanoTime(),null,recovery)){
    int rounds=0;do{while(!solver.step()){}if(solver.result()!=null||solver.infeasible()||!solver.paused())break;solver.resume();}while(++rounds<1000);
    System.out.println("Full recovery="+recovery+" reverse="+reverse+" result="+(solver.result()==null?"null":solver.result().result())+" infeasible="+solver.infeasible()+" work="+budget.nodes()+" diag="+budget.diagnostics());
   }
  }
 }
}
