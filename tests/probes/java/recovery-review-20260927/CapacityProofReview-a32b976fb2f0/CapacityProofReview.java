package org.cgse.core;
import java.math.BigInteger;import java.util.*;
public final class CapacityProofReview {
 static BigInteger z(long x){return BigInteger.valueOf(x);}
 static boolean valid(List<ExactLinearProgram.Constraint> rows,BigInteger[] values){for(var row:rows){BigInteger sum=BigInteger.ZERO;for(var e:row.terms().entrySet())sum=sum.add(e.getValue().multiply(values[e.getKey()]));if(sum.compareTo(row.upper())>0)return false;}return true;}
 public static void main(String[] args)throws Exception {
  Random random=new Random(722361);var field=CountBounds.class.getDeclaredField("rows");field.setAccessible(true);int solutions=0,closed=0,derived=0;
  for(int trial=0;trial<1500;trial++) {
   int n=4;List<ExactLinearProgram.Constraint> rows=new ArrayList<>();
   for(int i=0;i<n;i++)rows.add(new ExactLinearProgram.Constraint(Map.of(i,BigInteger.ONE),z(3)));
   for(int r=0;r<2;r++){Map<Integer,BigInteger> cap=new LinkedHashMap<>();for(int i=0;i<n;i++)if(random.nextBoolean()||i<2)cap.put(i,z(1+random.nextInt(5)));rows.add(new ExactLinearProgram.Constraint(cap,z(random.nextInt(28))));}
   for(int i=0;i<n;i++){int to=(i+1+random.nextInt(n-1))%n;rows.add(new ExactLinearProgram.Constraint(Map.of(i,z(-1-random.nextInt(5)),to,z(1+random.nextInt(5))),BigInteger.ZERO));}
   int global=rows.size();for(int r=0;r<2;r++){int i=random.nextInt(n),k=random.nextInt(4);boolean low=random.nextBoolean();rows.add(new ExactLinearProgram.Constraint(Map.of(i,z(low?-1:1)),z(low?-k:k)));}
   var budget=new PlanningBudget(0,1_000_000,32L<<20,()->false,System::nanoTime);
   try(var bounds=new CountBounds(n,rows,budget,global)) {
    while(!bounds.step()){}
    var lo=bounds.lowerBounds();var hi=bounds.upperBounds();
    var consequences=(List<ExactLinearProgram.Constraint>)field.get(bounds);derived+=consequences.size()-rows.size();
    BitSet conflict=bounds.conflictingAssumptions();List<ExactLinearProgram.Constraint> core=new ArrayList<>(rows.subList(0,global));
    if(conflict!=null)for(int i=conflict.nextSetBit(0);i>=0;i=conflict.nextSetBit(i+1))core.add(rows.get(global+i));
    int feasible=0;
    for(int code=0;code<256;code++) {
     BigInteger[] values=new BigInteger[n];for(int i=0;i<n;i++)values[i]=z((code>>(2*i))&3);
     if(valid(rows,values)) {
      feasible++;if(bounds.blocked()||!valid(consequences,values))throw new AssertionError("Unsound capacity consequence");
      for(int i=0;i<n;i++)if(values[i].compareTo(lo[i])<0||hi[i]!=null&&values[i].compareTo(hi[i])>0)throw new AssertionError("Lost valid count");
     }
     if(bounds.blocked()&&conflict!=null&&valid(core,values))throw new AssertionError("Invalid conflict explanation");
    }
    if(feasible>0)solutions++;else closed++;
   }
   if(budget.reservedBytes()!=0)throw new AssertionError("Memory leak");
  }
  if(derived==0||solutions==0||closed==0)throw new AssertionError("Vacuous oracle");
  System.out.println("PASS: 1500 bounded ratio/flow systems, "+derived+" derived rows independently checked; SAT="+solutions+" UNSAT="+closed+"; conflict assumptions and memory verified");
 }
}
