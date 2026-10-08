package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
public final class DivingOracle {
 static final BigInteger Z=BigInteger.ZERO,O=BigInteger.ONE;
 static boolean valid(List<ExactLinearProgram.Constraint> rows,BigInteger[] x,BigInteger[] lo,BigInteger[] hi){
  for(int i=0;i<x.length;i++)if(x[i].compareTo(lo[i])<0||hi[i]!=null&&x[i].compareTo(hi[i])>0)return false;
  for(var r:rows){var v=Z;for(var e:r.terms().entrySet())v=v.add(e.getValue().multiply(x[e.getKey()]));if(v.compareTo(r.upper())>0)return false;}return true;
 }
 static boolean exhaustive(List<ExactLinearProgram.Constraint> rows,BigInteger[] lo,BigInteger[] hi,int i,BigInteger[] x){
  if(i==x.length)return valid(rows,x,lo,hi);
  for(var v=lo[i];v.compareTo(hi[i])<=0;v=v.add(O)){x[i]=v;if(exhaustive(rows,lo,hi,i+1,x))return true;}return false;
 }
 public static void main(String[]args){
  var rnd=new Random(0x5ca19b2026L);int cases=0,possible=0,found=0,lpInfeasible=0,fractional=0,earlyClosed=0;
  for(int sample=0;sample<700;sample++){
   int n=2+rnd.nextInt(4),m=2+rnd.nextInt(9);var rows=new ArrayList<ExactLinearProgram.Constraint>();
   var lo=new BigInteger[n];var hi=new BigInteger[n];for(int i=0;i<n;i++){lo[i]=BigInteger.valueOf(rnd.nextInt(2));hi[i]=lo[i].add(BigInteger.valueOf(1+rnd.nextInt(5)));}
   for(int j=0;j<m;j++){var terms=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<n;i++){int a=rnd.nextInt(9)-4;if(a!=0)terms.put(i,BigInteger.valueOf(a));}rows.add(new ExactLinearProgram.Constraint(terms,BigInteger.valueOf(rnd.nextInt(31)-10)));}
   boolean ref=exhaustive(rows,lo,hi,0,new BigInteger[n]);possible+=ref?1:0;cases++;
   var budget=new PlanningBudget(0,2_000_000,64L<<20,()->false,System::nanoTime);
   var bounded=new ArrayList<>(rows);for(int i=0;i<n;i++){bounded.add(new ExactLinearProgram.Constraint(Map.of(i,O.negate()),lo[i].negate()));bounded.add(new ExactLinearProgram.Constraint(Map.of(i,O),hi[i]));}
   ExactRational[] point;
   var obj=new BigInteger[n];Arrays.fill(obj,Z);
   try(var lp=new ExactLinearProgram(n,bounded,obj,budget)){while(!lp.step()){}point=lp.point();if(lp.result()!=ExactLinearProgram.Result.OPTIMAL){if(ref)throw new AssertionError("LP false infeasible "+sample);lpInfeasible++;continue;}}
   if(Arrays.stream(point).anyMatch(p->!p.integral()))fractional++;
   try(var dive=new CountDiving(rows,lo,hi,point,budget)){while(!dive.step()){}var counts=dive.counts();if(counts!=null){if(!ref||!valid(rows,counts,lo,hi))throw new AssertionError("invalid dive "+sample);found++;}}
   if(budget.reservedBytes()!=0)throw new AssertionError("finished memory leak "+sample+" "+budget.reservedBytes());
   for(int ticks:new int[]{0,1,3,7}){var early=new PlanningBudget(0,2_000_000,64L<<20,()->false,System::nanoTime);try(var dive=new CountDiving(rows,lo,hi,point,early)){for(int t=0;t<ticks&&!dive.step();t++){} }if(early.reservedBytes()!=0)throw new AssertionError("early close leak "+sample+" ticks="+ticks);earlyClosed++;}
  }
  System.out.println("PASS exhaustive_cases="+cases+" reachable="+possible+" dive_found="+found+" LP_infeasible="+lpInfeasible+" fractional_LP="+fractional+" early_close="+earlyClosed+" false_feasible=0 memory_leaks=0");
 }
}
