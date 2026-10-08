package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
final class CountObjectiveCutProbeHelper {
 static List<ExactLinearProgram.Constraint> separate(List<ExactLinearProgram.Constraint> input,BigInteger[] lower,BigInteger[] upper,PlanningBudget budget){
  if(lower.length<32||lower.length>128||input.size()>512)return List.of();long start=budget.threadWork(),allowance=Math.min(3_000_000,budget.remainingWork()/3);if(allowance<100_000)return List.of();
  ExactLinearProgram.Constraint objectiveRow=null;for(var row:input){budget.check();if(row.terms().size()<lower.length/2||row.terms().values().stream().anyMatch(v->v.signum()<=0)||row.terms().values().stream().allMatch(BigInteger.ONE::equals))continue;if(objectiveRow==null||row.terms().size()>objectiveRow.terms().size())objectiveRow=row;}
  if(objectiveRow==null)return List.of();BigInteger[] objective=new BigInteger[lower.length];Arrays.fill(objective,BigInteger.ZERO);for(var term:objectiveRow.terms().entrySet())objective[term.getKey()]=term.getValue().negate();
  List<ExactLinearProgram.Constraint> rows=new ArrayList<>(input),cuts=new ArrayList<>();for(int i=0;i<lower.length;i++){rows.add(new ExactLinearProgram.Constraint(Map.of(i,BigInteger.ONE.negate()),lower[i].negate()));if(upper[i]!=null)rows.add(new ExactLinearProgram.Constraint(Map.of(i,BigInteger.ONE),upper[i]));}
  rows=new ArrayList<>(new LinkedHashSet<>(rows));ExactLinearProgram.Basis basis=null;try{
   for(int round=0;round<4&&budget.threadWork()-start<allowance;round++){
    ExactRational[] point;ExactLinearProgram.Result status;ExactLinearProgram.Basis next;
    try(var lp=new ExactLinearProgram(lower.length,rows,objective,budget,basis,true)){if(basis!=null){basis.close();basis=null;}while(!lp.step()){if(budget.threadWork()-start>=allowance)return cuts;}point=lp.point();status=lp.result();next=lp.takeBasis();}
    basis=next;System.err.println("SIDE LP round="+round+" status="+status+" work="+(budget.threadWork()-start));if(status!=ExactLinearProgram.Result.OPTIMAL||point==null)break;
    var generated=CountGomory.separate(basis,point,budget);System.err.println("SIDE cuts="+generated.size());if(generated.isEmpty())break;for(var cut:generated)if(!rows.contains(cut)){rows.add(cut);cuts.add(cut);}
   }
  }finally{if(basis!=null)basis.close();}
  return cuts;
 }
}
