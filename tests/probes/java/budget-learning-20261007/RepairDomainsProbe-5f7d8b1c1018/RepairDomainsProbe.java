package org.cgse.core;
import java.math.*;
import java.util.*;
public class RepairDomainsProbe {
 static BigInteger b(long n){return BigInteger.valueOf(n);}
 static void require(boolean c,String m){if(!c)throw new AssertionError(m);}
 static void check(List<ExactLinearProgram.Constraint> rows,BigInteger[] values){
  for(var row:rows){BigInteger sum=BigInteger.ZERO;for(var e:row.terms().entrySet())sum=sum.add(e.getValue().multiply(values[e.getKey()]));require(sum.compareTo(row.upper())<=0,"invalid witness");}
 }
 public static void main(String[] args){
  int successes=0;
  for(int seed=0;seed<128;seed++){
   var r=new Random(seed);int n=3+r.nextInt(10);var ids=new ArrayList<Integer>();for(int i=0;i<n;i++)ids.add(i);Collections.shuffle(ids,r);
   var rows=new ArrayList<ExactLinearProgram.Constraint>();
   for(int i=1;i<n;i++){
    BigInteger a=BigInteger.ONE.shiftLeft(seed%4==0?128:0).multiply(b(1+r.nextInt(15)));
    rows.add(new ExactLinearProgram.Constraint(Map.of(ids.get(i-1),a,ids.get(i),a.negate()),BigInteger.ZERO));
    rows.add(new ExactLinearProgram.Constraint(Map.of(ids.get(i-1),a.negate(),ids.get(i),a),BigInteger.ZERO));
   }
   Collections.shuffle(rows,r);var lo=new BigInteger[n];var hi=new BigInteger[n];var p=new ExactRational[n];
   Arrays.fill(lo,BigInteger.ZERO);Arrays.fill(hi,seed%2==0?null:b(8));Arrays.fill(p,ExactRational.ZERO);
   var budget=new PlanningBudget(0,2_000_000,32L<<20,()->false,System::nanoTime);var region=new BitSet();region.set(ids.get(0));
   try(var search=new CountNeighborhood(rows,lo,hi,p,budget).failureRegion(region)){
    while(!search.step()){}var values=search.counts();
    if(values!=null){check(rows,values);require(values[ids.get(0)].signum()>0,"lost speculative move");successes++;}
   }
   require(budget.reservedBytes()==0,"memory leak");
  }
  System.out.println("chained repair witnesses="+successes+"/128");
  if(args.length>0)require(successes==128,"missed repair family");
 }
}
