package org.cgse.core;
import java.util.*;
import java.math.BigInteger;
public class LpDuplicateProofProbe {
 public static void main(String[]args){int proofs=0,dual=0;
  for(int seed=0;seed<120;seed++)for(boolean infeasible:new boolean[]{false,true}){
   var rows=new ArrayList<>(LpSafetyProbe.rows(64,seed));var objective=LpSafetyProbe.objective(64,seed);
   if(infeasible){rows.add(new ExactLinearProgram.Constraint(Map.of(0,BigInteger.ONE.negate()),BigInteger.valueOf(-2)));}
   for(int i=rows.size()-1;i>=0;i--)if(i%3!=0)rows.add(rows.get(i));Collections.shuffle(rows,new Random(seed));
   PlanningBudget budget=new PlanningBudget(0,4_000_000,128L<<20,()->false,System::nanoTime);
   try(var lp=new ExactLinearProgram(64,rows,objective,budget,null,true)){
    while(!lp.step()){}
    if(infeasible){if(lp.result()!=ExactLinearProgram.Result.INFEASIBLE)throw new AssertionError("missing infeasible");var w=lp.certificate();if(w==null||w.length!=rows.size())throw new AssertionError("Farkas ordering");if(CountProof.verify(CountProof.certificate("original_rows",64,rows,List.of(),w,true),4_000_000)!=CountProof.Verdict.VERIFIED)throw new AssertionError("invalid Farkas");proofs++;}
    else{if(lp.result()!=ExactLinearProgram.Result.OPTIMAL)throw new AssertionError("missing optimum");var w=lp.optimumDual();if(w==null||w.length!=rows.size())throw new AssertionError("dual ordering");ExactRational bound=ExactRational.ZERO;ExactRational[]sum=new ExactRational[64];Arrays.fill(sum,ExactRational.ZERO);for(int i=0;i<w.length;i++){if(w[i].signum()<0)throw new AssertionError("negative dual");bound=bound.add(w[i].multiply(ExactRational.of(rows.get(i).upper())));for(var t:rows.get(i).terms().entrySet())sum[t.getKey()]=sum[t.getKey()].add(w[i].multiply(ExactRational.of(t.getValue())));}for(int i=0;i<64;i++)if(sum[i].compareTo(ExactRational.of(objective[i]))<0)throw new AssertionError("dual column");if(!bound.equals(LpSafetyProbe.value(lp.point(),objective)))throw new AssertionError("duality gap");dual++;}
   }
   if(budget.reservedBytes()!=0)throw new AssertionError("leak");
  }
  int warm=0,optional=0;
  for(int n:new int[]{2,64})for(int seed=0;seed<30;seed++){
   var rows=new ArrayList<ExactLinearProgram.Constraint>();rows.add(new ExactLinearProgram.Constraint(Map.of(0,BigInteger.ONE),BigInteger.valueOf(3)));rows.add(new ExactLinearProgram.Constraint(Map.of(1,BigInteger.ONE),BigInteger.valueOf(4)));rows.add(new ExactLinearProgram.Constraint(Map.of(0,BigInteger.ONE,1,BigInteger.ONE),BigInteger.valueOf(5)));for(int i=2;i<n;i++)rows.add(new ExactLinearProgram.Constraint(Map.of(i,BigInteger.ONE),BigInteger.ZERO));
   var objective=new BigInteger[n];Arrays.fill(objective,BigInteger.ZERO);objective[0]=BigInteger.TWO;objective[1]=BigInteger.ONE;PlanningBudget budget=new PlanningBudget(0,4_000_000,128L<<20,()->false,System::nanoTime);
   try(var parent=new ExactLinearProgram(n,rows,objective,budget,null,true)){while(!parent.step()){}try(var basis=parent.takeBasis()){
    for(boolean tighten:new boolean[]{false,true}){
     var childRows=new ArrayList<>(rows);if(tighten)childRows.set(0,new ExactLinearProgram.Constraint(Map.of(0,BigInteger.ONE),BigInteger.TWO));Collections.shuffle(childRows,new Random(seed));
     try(var child=new ExactLinearProgram(n,childRows,objective,budget,basis,true)){while(!child.step()){}if(child.result()!=ExactLinearProgram.Result.OPTIMAL||!child.hot())throw new AssertionError("warm contract not exercised");var w=child.optimumDual();if(w==null){if(!tighten)throw new AssertionError("pure reordered dual absent");optional++;continue;}if(w.length!=childRows.size())throw new AssertionError("warm length");ExactRational bound=ExactRational.ZERO;ExactRational[]sum=new ExactRational[n];Arrays.fill(sum,ExactRational.ZERO);for(int i=0;i<w.length;i++){if(w[i].signum()<0)throw new AssertionError("warm sign");bound=bound.add(w[i].multiply(ExactRational.of(childRows.get(i).upper())));for(var t:childRows.get(i).terms().entrySet())sum[t.getKey()]=sum[t.getKey()].add(w[i].multiply(ExactRational.of(t.getValue())));}for(int i=0;i<n;i++)if(sum[i].compareTo(ExactRational.of(objective[i]))<0)throw new AssertionError("warm dual column");if(!bound.equals(LpSafetyProbe.value(child.point(),objective)))throw new AssertionError("warm dual gap");warm++;}
    }
   }}if(budget.reservedBytes()!=0)throw new AssertionError("warm leak");
  }
  System.out.println("DUPLICATE_PROOFS farkas="+proofs+" originalRowDualOptima="+dual+" reorderedWarmOptima="+warm+" optionalUnmappable="+optional+" leaks=0");
 }
}
