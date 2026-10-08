package org.cgse.core;
import java.math.BigInteger;import java.util.*;
final class CountProbedCoverHelper{
 record Lit(int id,boolean high,BigInteger lo,BigInteger hi,ExactRational distance){}
 static List<ExactLinearProgram.Constraint> separate(List<ExactLinearProgram.Constraint> rows,BigInteger[] lo,BigInteger[] hi,ExactRational[] point,PlanningBudget budget){
  if(lo.length>128||rows.size()>512)return List.of();long started=budget.threadWork(),allowance=Math.min(131072,budget.remainingWork()/32);if(allowance<8192)return List.of();
  var base=new ArrayList<>(rows);List<Lit> integral=new ArrayList<>(),fractional=new ArrayList<>();
  for(int i=0;i<lo.length;i++){base.add(new ExactLinearProgram.Constraint(Map.of(i,BigInteger.ONE.negate()),lo[i].negate()));if(hi[i]!=null)base.add(new ExactLinearProgram.Constraint(Map.of(i,BigInteger.ONE),hi[i]));if(hi[i]==null||!hi[i].subtract(lo[i]).equals(BigInteger.ONE))continue;ExactRational x=point[i].subtract(ExactRational.of(lo[i]));if(x.signum()<0||x.compareTo(ExactRational.ONE)>0)return List.of();boolean high=x.compareTo(ExactRational.of(BigInteger.ONE,BigInteger.TWO))>=0;ExactRational dist=high?ExactRational.ONE.subtract(x):x;Lit lit=new Lit(i,high,lo[i],hi[i],dist);(dist.signum()==0?integral:fractional).add(lit);}
  fractional.sort(Comparator.comparing(Lit::distance).thenComparingInt(Lit::id));List<ExactLinearProgram.Constraint> cuts=new ArrayList<>();Set<ExactLinearProgram.Constraint> seen=new HashSet<>(rows);
  for(int trial=0;trial<Math.min(16,fractional.size()+1)&&budget.threadWork()-started<allowance&&cuts.size()<4;trial++){
   var selected=new ArrayList<>(integral);ExactRational distance=ExactRational.ZERO;for(int k=0;k<fractional.size();k++){Lit lit=fractional.get((trial+k)%fractional.size());if(distance.add(lit.distance()).compareTo(ExactRational.ONE)>=0)continue;selected.add(lit);distance=distance.add(lit.distance());}
   var scope=new ArrayList<>(base);for(Lit lit:selected)scope.add(lit.high()?new ExactLinearProgram.Constraint(Map.of(lit.id(),BigInteger.ONE.negate()),lit.hi().negate()):new ExactLinearProgram.Constraint(Map.of(lit.id(),BigInteger.ONE),lit.lo()));
   try(var propagation=new CountBounds(lo.length,scope,budget,base.size())){
    while(budget.threadWork()-started<allowance&&!propagation.step()){}if(!propagation.blocked())continue;BitSet core=propagation.conflictingAssumptions();if(core==null)continue;
    Map<Integer,BigInteger> terms=new LinkedHashMap<>();BigInteger rhs=BigInteger.valueOf(core.cardinality()-1L);ExactRational activity=ExactRational.ZERO;
    for(int i=core.nextSetBit(0);i>=0;i=core.nextSetBit(i+1)){Lit lit=selected.get(i);terms.put(lit.id(),lit.high()?BigInteger.ONE:BigInteger.ONE.negate());rhs=rhs.subtract(lit.high()?lit.lo().negate():lit.hi());activity=activity.add(ExactRational.ONE.subtract(lit.distance()));}
    var cut=new ExactLinearProgram.Constraint(terms,rhs);if(activity.compareTo(ExactRational.of(BigInteger.valueOf(core.cardinality()-1L)))>0&&seen.add(cut))cuts.add(cut);
   }
  }
  System.err.println("PROBED cuts="+cuts.size()+" work="+(budget.threadWork()-started));return cuts;
 }
}
